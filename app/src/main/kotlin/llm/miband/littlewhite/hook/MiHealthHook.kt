@file:Suppress("unused")

package llm.miband.littlewhite.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 环上LLM —— WebSocket 消息拦截的 Hook 实现（方案 A 文本层 + 方案 C 稳定兜底）
 *
 * 拦截 WebSocket 文本消息并完成：
 * 1. RecognizeResult —— 记录用户语音识别文本（dialogId -> text）；
 * 2. Template/Toast —— 触发 LLM 生成替换回答，阻塞等待后改写原 JSON 的 payload.text，
 *    从而让设备侧直接显示 LLM 的回答。
 *
 * 设多道独立容错的 Hook 点（任一成功即可工作，彼此互不影响）：
 * 1. 方案 A：`defpackage.oav#onMessage(WebSocket, String)`（LiteCryptWsClient，继承
 *    okhttp3.WebSocketListener；当前版本 enable_lite_crypt=false，参数 str 即明文 JSON）。
 *    由于 `defpackage.oav` 为混淆类名（版本升级会变），采用动态定位，并兜底去 Hook
 *    非混淆的 OkHttp 内部类 `okhttp3.internal.ws.RealWebSocket#onReadMessage(String)`
 *    （WebSocketReader 收到文本帧后回调 listener.onMessage 的入口）。
 * 2. 方案 C：`com.xiaomi.ai.api.common.APIUtils#readInstruction(String)` —— 非混淆稳定类，
 *    每个文本消息在 engine 内都会经过它解析为 Instruction，其入参即原始明文 JSON；
 *    在字符串层截获，覆盖所有消息类型（见 installReadInstructionHook）。
 *
 * 主文本层与方案 C 可能命中同一条 Toast，已用 [TOAST_DEDUP_MS] 时间窗去重避免重复调用 LLM。
 *
 * Hook API 严格遵循 lsposed-dev-guide.md 第 5/6 章：
 * `module.hook(Executable).setPriority(...).setExceptionMode(...).intercept(Hooker)`，
 * 拦截器内部通过 `chain.proceed()` 继续调用原方法。
 */
class MiHealthHook(
    private val module: XposedModule,
    private val config: ConfigStore,
    private val classLoader: ClassLoader,
) {

    private val tag = "MiHealthHook"

    /** 消息处理器：识别文本缓存 + LLM 替换回调编排（与具体 Hook 点解耦） */
    private val processor = WebSocketMessageProcessor(config)

    /** 全局 Json 实例：用于改写 Toast 的 payload.text */
    private val json = Json { ignoreUnknownKeys = true }

    /** Hook 是否已安装（幂等，避免重复注册） */
    private var hooksInstalled = false

    /**
     * Toast 去重表：dialogId -> 最近一次触发 LLM 替换的时间戳（毫秒）。
     * 主文本层 Hook 与方案 C（readInstruction 兜底）可能命中同一条 Toast，
     * 用时间窗去重避免重复发起 LLM 调用。
     */
    private val lastToastReplaceMs = ConcurrentHashMap<String, Long>()

    private companion object {
        /** 消息命名空间/名称常量（与小米 AI 协议一致，见 docs/reverse-notes.md） */
        const val NS_SPEECH_RECOGNIZER = "SpeechRecognizer"
        const val NAME_RECOGNIZE_RESULT = "RecognizeResult"
        const val NS_TEMPLATE = "Template"
        const val NAME_TOAST = "Toast"

        /** 目标类候选：defpackage.oav 为混淆类名，版本升级可能变化，按序尝试 */
        val TARGET_CLASS_CANDIDATES = listOf("defpackage.oav")

        /**
         * 等待 LLM 替换结果的最大阻塞时长（毫秒）。
         * 阻塞 WebSocket 读取线程太久会导致帧堆积/连接超时，故上限设 15s；
         * 超过后放弃替换、放行原始 Toast。
         */
        const val MAX_WAIT_MS = 15_000L

        /**
         * Toast 去重时间窗（毫秒）：同一 dialogId 在此窗口内只触发一次 LLM 替换。
         * 用于规避主文本层与方案 C 两条 hook 同时命中同一条 Toast 时的重复调用。
         */
        const val TOAST_DEDUP_MS = 2_000L
    }

    /** 目标方法信息 */
    private data class TargetMethod(
        val method: Method,
        /** String 消息参数在方法参数列表中的下标（oav 为 1，RealWebSocket 为 0） */
        val stringArgIndex: Int,
        val description: String,
    )

    /**
     * 安装 Hook。
     * 仅当 [ConfigStore.isEnabled] 时实际安装；同时注册配置变更监听以支持动态启停
     * （停用后 Hook 保留但处理逻辑通过 isEnabled 检查跳过，避免频繁卸载/重装）。
     */
    fun install() {
        // 配置变更监听：启用时自动补装 Hook
        config.registerOnChangeListener { _, _ ->
            if (config.isEnabled()) {
                installHooks()
            } else {
                LogCollector.i(tag, "模块已停用（Hook 保留，处理逻辑跳过）")
            }
        }

        if (config.isEnabled()) {
            installHooks()
        } else {
            LogCollector.i(tag, "模块未启用，跳过 Hook 安装（启用后经配置监听自动安装）")
        }
    }

    // ==================== Hook 安装 ====================

    /**
     * 幂等安装：并行注册多个互不影响的 Hook 点，各自 try-catch 容错，
     * 任一成功即可提供 WebSocket 消息拦截能力。
     *   1) 主文本层：LiteCryptWsClient(oav).onMessage —— 未命中时兜底 RealWebSocket；
     *   2) 方案 C 稳定兜底：异常外类 `com.xiaomi.ai.api.common.APIUtils#readInstruction(String)`。
     */
    private fun installHooks() {
        if (hooksInstalled) return
        hooksInstalled = true

        var anyInstalled = false
        // 1) 主文本层（方案 A）：混淆类 oav 或 OkHttp RealWebSocket 兜底
        findTargetMethod()?.let { if (installOne(it)) anyInstalled = true }
        // 2) 方案 C：非混淆稳定的 APIUtils.readInstruction 单点兜底
        if (installReadInstructionHook()) anyInstalled = true

        // 没有任何 Hook 点装上的话，允许下次配置变更时重试
        if (!anyInstalled) hooksInstalled = false
    }

    /**
     * 安装单个文本层拦截器。
     * —— 参照 lsposed-dev-guide.md 6.1：hook(method).setPriority().setExceptionMode().intercept(chain) ——
     * @return 是否成功；失败不影响其他 Hook 点安装
     */
    private fun installOne(target: TargetMethod): Boolean = try {
        module.hook(target.method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(XposedInterface.Hooker { chain ->
                onMessageIntercepted(chain, target.stringArgIndex)
            })
        LogCollector.i(tag, "Hook 安装成功: ${target.description}")
        true
    } catch (t: Throwable) {
        LogCollector.e(tag, "Hook 安装失败: ${target.description}", t)
        false
    }

    // ==================== 目标定位（动态查找） ====================

    /**
     * 动态定位目标方法：
     * 1. 候选类名加载 `onMessage(WebSocket, String)`（当前版本 defpackage.oav）；
     * 2. 兜底 `okhttp3.internal.ws.RealWebSocket#onReadMessage(String)`。
     */
    private fun findTargetMethod(): TargetMethod? {
        // 1. 方案 A 主实现：LiteCryptWsClient#onMessage(WebSocket, String)
        for (name in TARGET_CLASS_CANDIDATES) {
            try {
                val cls = classLoader.loadClass(name)
                val wsClass = classLoader.loadClass("okhttp3.WebSocket")
                val method = cls.getMethod("onMessage", wsClass, String::class.java)
                LogCollector.i(tag, "命中目标类: $name (继承 WebSocketListener)")
                return TargetMethod(method, 1, "$name#onMessage(WebSocket, String)")
            } catch (_: Throwable) {
                // 类不存在或签名不匹配，尝试下一个候选
            }
        }

        // 2. 兜底：RealWebSocket#onReadMessage(String)（OkHttp 非混淆内部类）
        try {
            val cls = classLoader.loadClass("okhttp3.internal.ws.RealWebSocket")
            for (mname in listOf("onReadMessage", "onMessage")) {
                try {
                    val method = cls.getDeclaredMethod(mname, String::class.java)
                    method.isAccessible = true
                    LogCollector.i(tag, "兜底命中: okhttp3.internal.ws.RealWebSocket#$mname(String)")
                    return TargetMethod(method, 0, "RealWebSocket#$mname(String)")
                } catch (_: Throwable) {
                    // 尝试下一个方法名
                }
            }
        } catch (_: Throwable) {
            LogCollector.w(tag, "RealWebSocket 类不可用（OkHttp 版本差异）")
        }

        return null
    }

    /**
     * 方案 C 稳定兜底：Hook `com.xiaomi.ai.api.common.APIUtils#readInstruction(String)`。
     *
     * 该类为非混淆类（见 docs/reverse-notes.md 3.3），`oav.onMessage` 内部收到文本消息后
     * 会先解密再调用 `APIUtils.readInstruction(json)` 把 JSON 解析成 Instruction。
     * 明文模式（enable_lite_crypt=false）下其入参即为明文 JSON，这里在字符串层直接截获，
     * 复用主文本层同样的处理逻辑（识别 RecognizeResult / 替换 Toast 的 payload.text）。
     * readInstruction 的返回值是 Instruction，我们只读不改放行，绝不破坏原始解析结果。
     *
     * @return 是否成功（类不存在/签名不匹配时返回 false，回退依赖主 Hook）
     */
    private fun installReadInstructionHook(): Boolean = try {
        val cls = classLoader.loadClass("com.xiaomi.ai.api.common.APIUtils")
        val method = cls.getDeclaredMethod("readInstruction", String::class.java)
        method.isAccessible = true
        module.hook(method)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(XposedInterface.Hooker { chain ->
                onMessageIntercepted(chain, 0) // readInstruction(String) 第 1 个参数即原始 JSON
            })
        LogCollector.i(tag, "方案C Hook 安装成功: APIUtils#readInstruction(String)")
        true
    } catch (t: Throwable) {
        LogCollector.e(tag, "方案C (APIUtils.readInstruction) 不可用，跳过（依赖主文本层 Hook）", t)
        false
    }

    /**
     * 拦截器核心：按消息类型分流处理，所有路径最终都继续执行原方法。
     * @return 原方法（或链中下一个拦截器）的返回值
     */
    private fun onMessageIntercepted(chain: XposedInterface.Chain, stringIndex: Int): Any? {
        // LSPlant 的 getArgs() 返回可变参数列表，可直接在拦截器内改写元素实现参数替换
        val args = chain.getArgs() as? MutableList<Any?> ?: return chain.proceed()
        if (args.size <= stringIndex) return chain.proceed()
        val raw = args[stringIndex] as? String ?: return chain.proceed()

        // 解析消息头（namespace/name/dialog_id），解析失败也放行
        val msg = WsMessage.parse(raw)

        when {
            // —— 语音识别结果：交给 processor 记录识别文本，随后放行 ——
            msg.namespace == NS_SPEECH_RECOGNIZER && msg.name == NAME_RECOGNIZE_RESULT -> {
                processor.process(msg)
                return chain.proceed()
            }
            // —— 小爱回答 Toast：阻塞等待 LLM 替换结果，改写 str 后放行 ——
            msg.namespace == NS_TEMPLATE && msg.name == NAME_TOAST -> {
                if (replaceToastBlocking(args, stringIndex, raw, msg)) {
                    LogCollector.i(tag, "Toast 回答已替换 dialogId=${msg.dialogId}")
                }
                return chain.proceed()
            }
            // —— 其他消息：交给 processor 统一处理（内部解析失败也放行）后透传 ——
            else -> {
                processor.process(msg)
                return chain.proceed()
            }
        }
    }

    /**
     * Toast 消息替换：触发 processor 后台 LLM 任务并阻塞等待结果，
     * 成功后直接改写原方法 String 参数（把 payload.text 换成 LLM 回答）。
     *
     * @return 是否成功改写参数
     */
    private fun replaceToastBlocking(
        args: MutableList<Any?>,
        stringIndex: Int,
        raw: String,
        msg: WsMessage,
    ): Boolean {
        // 前置条件：模块启用 / 已配置 API Key / 已记录到识别文本
        if (!config.isEnabled()) return false
        if (config.getApiKey().isBlank()) return false
        val dialogId = msg.dialogId ?: return false
        if (processor.getPendingQuery(dialogId).isNullOrBlank()) return false

        // 多 Hook 点（主文本层 + 方案 C readInstruction）可能命中同一条 Toast，
        // 时间窗去重：同一 dialogId 在窗口内已触发过替换则跳过，避免重复发起 LLM 调用
        val now = System.currentTimeMillis()
        val last = lastToastReplaceMs[dialogId]
        if (last != null && now - last < TOAST_DEDUP_MS) {
            LogCollector.i(tag, "同 dialogId 短时间内已替换过，跳过重复处理 dialogId=$dialogId")
            return false
        }

        // 注册一次性替换回调：LLM 完成后携带新文本唤醒等待方
        val latch = CountDownLatch(1)
        val answer = AtomicReference<String?>()
        val cb: (String, String) -> Unit = { dlgId, newText ->
            if (dlgId == dialogId) {
                answer.set(newText)
                latch.countDown()
            }
        }

        processor.registerReplacementCallback(cb)
        try {
            // 触发后台 LLM 任务（processor 在单线程池内执行 LlmClient.ask）
            processor.process(msg)
            // 阻塞等待：上限取配置超时与 MAX_WAIT_MS 的较小者，避免拖垮 WebSocket 读取线程
            val timeoutMs = config.getTimeoutMs().coerceIn(1000L, MAX_WAIT_MS)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            LogCollector.e(tag, "等待 LLM 替换结果异常", t)
        } finally {
            processor.unregisterReplacementCallback(cb)
        }

        val newText = answer.get() ?: return false
        val modified = replaceToastText(raw, newText)
        if (modified == raw) return false

        // 改写参数：LSPlant 的 getArgs() 返回原方法实际参数数组，改元素可被原方法读到
        return try {
            args[stringIndex] = modified
            // 记录触发时间，供本方法开头的去重逻辑使用
            lastToastReplaceMs[dialogId] = System.currentTimeMillis()
            true
        } catch (t: Throwable) {
            LogCollector.e(tag, "改写 onMessage 参数失败", t)
            false
        }
    }

    /** 把 Toast JSON 的 payload.text 替换为新文本；解析/替换失败返回原串 */
    private fun replaceToastText(rawJson: String, newText: String): String {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val payload = root["payload"] as? JsonObject ?: return rawJson
            val newPayload = JsonObject(payload.toMutableMap().apply { this["text"] = JsonPrimitive(newText) })
            val newRoot = JsonObject(root.toMutableMap().apply { this["payload"] = newPayload })
            newRoot.toString()
        } catch (t: Throwable) {
            LogCollector.w(tag, "替换 Toast 文本失败: ${t.message}")
            rawJson
        }
    }
}
