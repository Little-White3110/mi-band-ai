@file:Suppress("unused")

package llm.miband.littlewhite.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        const val NAME_GENERAL = "General"

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
        // 初始化回答模式状态机（幂等）
        ModeState.init(config)

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
        // getArgs() 返回的 List 实际为不可变实现（Collections$UnmodifiableList），
        // 无法原地改写参数；替换参数的正确方式是通过 chain.proceed(新参数数组) 重新执行原方法。
        val args = chain.getArgs()
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
            // —— 小爱回答（Toast / 开关开启时的 General）：阻塞等待 LLM 替换结果，
            //    用修改后的 JSON 重新执行原方法 ——
            isAnswerText(msg) -> {
                val modified = replaceToastBlocking(raw, msg)
                if (modified != null && modified != raw) {
                    LogCollector.i(tag, "${msg.name} 回答已替换 dialogId=${msg.dialogId}")
                    // 构造新参数数组：仅替换 stringIndex 位置的消息体，其余原样保留
                    val newArgs = arrayOfNulls<Any?>(args.size)
                    args.forEachIndexed { i, v -> newArgs[i] = v }
                    newArgs[stringIndex] = modified
                    return chain.proceed(newArgs)
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
     * 是否为本模块可替换的「回答文本」消息：
     * - Template.Toast：恒为可替换；
     * - Template.General（米家/设备类文本）：仅当配置开启拦截时纳入替换，默认放行。
     */
    private fun isAnswerText(msg: WsMessage): Boolean {
        if (msg.namespace != NS_TEMPLATE) return false
        return when (msg.name) {
            NAME_TOAST -> true
            NAME_GENERAL -> config.getInterceptGeneral()
            else -> false
        }
    }

    /**
     * Toast 消息替换：触发 processor 后台 LLM 任务并阻塞等待结果，
     * 成功后把 payload.text 替换为 LLM 回答，返回修改后的 JSON 字符串。
     *
     * @return 修改后的 JSON；未启用/无 Key/无识别文本/超时/失败时返回 null（保持原消息）
     */
    private fun replaceToastBlocking(raw: String, msg: WsMessage): String? {
        val dialogId = msg.dialogId ?: return null

        // —— 指令/查询分支：该 dialogId 命中过语音指令 → 直接替换为固定确认或当前模式文案，
        //    不阻塞、不调用 LLM，立即返回（先于模块启用/API Key 等前置检查）——
        val cmd = processor.consumeCommand(dialogId)
        if (cmd != null) {
            // 切换类 → 确认文案；查询模式类 → 当前实际模式文案
            val confirmation = cmd.mode?.let { ModeState.buildConfirmation(it) }
                ?: ModeState.buildModeStatus()
            LogCollector.i(tag, "指令确认文案 Toast dialogId=$dialogId → ${confirmation}")
            return replaceToastText(raw, confirmation)
        }

        // —— 小爱模式放行分支：当前处于小爱接管 → 不调 LLM，放行小爱原始回答 ——
        if (ModeState.resolveMode() == AnswerMode.XIAOAI) {
            LogCollector.i(tag, "小爱模式，Toast 透传 dialogId=$dialogId（不调 LLM）")
            return null
        }

        // 前置条件：模块启用 / 已配置 API Key / 已记录到识别文本
        if (!config.isEnabled()) return null
        if (config.getApiKey().isBlank()) return null
        if (processor.getPendingQuery(dialogId).isNullOrBlank()) return null

        // 多 Hook 点（主文本层 + 方案 C readInstruction）可能命中同一条 Toast，
        // 时间窗去重：同一 dialogId 在窗口内已触发过替换则跳过，避免重复发起 LLM 调用
        val now = System.currentTimeMillis()
        val last = lastToastReplaceMs[dialogId]
        if (last != null && now - last < TOAST_DEDUP_MS) {
            LogCollector.i(tag, "同 dialogId 短时间内已替换过，跳过重复处理 dialogId=$dialogId")
            return null
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

        val newText = answer.get() ?: return null
        val modified = replaceToastText(raw, newText)
        if (modified == raw) return null

        // 记录触发时间，供本方法开头的去重逻辑使用
        lastToastReplaceMs[dialogId] = System.currentTimeMillis()
        return modified
    }

    /**
     * 把回答消息中的"小爱原始回答文本"替换为新文本。
     *
     * 关键点：手环屏幕显示的文本可能不在 payload.text，而在 payload.display 等
     * 富卡片字段（米家/设备类）里。因此除 text 外，还要在整棵 JSON 中做深度替换：
     * 凡字符串字面量等于旧回答文本的，一律替换为 LLM 新文本（含 display/full_screen 等）。
     * 解析/替换失败返回原串，绝不破坏宿主消息。
     */
    private fun replaceToastText(rawJson: String, newText: String): String {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val payload = root["payload"] as? JsonObject ?: return rawJson
            // 旧回答文本（payload.text）
            val oldText = (payload["text"] as? JsonPrimitive)?.contentOrNull ?: return rawJson
            if (oldText.isEmpty()) return rawJson

            // 1) 先显式替换 payload.text（保持既有语义）
            val newPayload = JsonObject(payload.toMutableMap().apply { this["text"] = JsonPrimitive(newText) })
            val newRoot = JsonObject(root.toMutableMap().apply { this["payload"] = newPayload })
            // 2) 深度替换：整棵 JSON 中所有等于旧回答的字符串字面量（覆盖 display 等富卡片）
            val replaced = replaceStrings(newRoot, oldText, newText)
            replaced.toString()
        } catch (t: Throwable) {
            LogCollector.w(tag, "替换 Toast 文本失败: ${t.message}")
            rawJson
        }
    }

    /** 深度遍历 JSON，把所有字符串字面量内容 == old 的替换为 new（contentOrNull 仅对字符串返回内容） */
    private fun replaceStrings(el: JsonElement, old: String, newV: String): JsonElement = when (el) {
        is JsonPrimitive -> if (el.contentOrNull == old) JsonPrimitive(newV) else el
        is JsonObject -> JsonObject(el.entries.associate { (k, v) -> k to replaceStrings(v, old, newV) })
        is JsonArray -> JsonArray(el.map { replaceStrings(it, old, newV) })
        else -> el
    }
}
