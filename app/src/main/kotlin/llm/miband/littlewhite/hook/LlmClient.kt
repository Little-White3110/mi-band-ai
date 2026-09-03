package llm.miband.littlewhite.hook

import android.content.Context
import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.config.StatsContentProvider
import llm.miband.littlewhite.config.StatsStore
import llm.miband.littlewhite.log.LogCollector
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 环上LLM —— LLM 客户端（单例 object）
 *
 * 运行在 Hook 宿主进程（com.mi.health）内，负责把 WebSocket 收到的语音识别文本
 * 转成 OpenAI 兼容 / Anthropic 的 Chat 请求并取回回答。
 *
 * 设计要点：
 * - 直接使用 java.net.HttpURLConnection 同步直连（不引入 OkHttp，避免与宿主冲突）；
 * - 会话（dialogId -> 消息历史 + 时间戳）仅保存在内存，进程重启即清空；
 * - 请求体/响应解析全部使用 kotlinx.serialization 的 @Serializable data class；
 * - 任何异常不外抛：网络错误、非 2xx、解析失败一律返回 null，并记入 LogCollector；
 * - 日志中的 API Key 由 LogCollector 统一脱敏（替换为 sk-***）。
 *
 * 使用流程：进程启动时先 [init] 注入 [ConfigStore]，之后在回调线程直接调 [ask]。
 * 注意：本方法为同步网络调用，勿在主线程执行。
 */
object LlmClient {

    private const val TAG = "LlmClient"

    /** 会话缓存数量阈值：超过该值才触发一次惰性清理（防止无限增长） */
    private const val MAX_SESSIONS = 64

    /** 单条日志里请求/响应 body 的最大长度，超长截断避免刷屏 */
    private const val LOG_BODY_LIMIT = 1500

    /** 调用历史保留上限 */
    private const val MAX_CALL_LOGS = 200

    private const val ANSWER_RESERVE_TOKENS = 512

    /** Anthropic 思考预算下限（budget_tokens） */
    private const val MIN_THINKING_BUDGET_TOKENS = 1024

    /** 配置读取器：由 [init] 注入，未初始化前 [ask] 直接返回 null */
    private var config: ConfigStore? = null

    /** 宿主进程 Application Context（用于跨进程推统计到模块 App），由 MainModule 注入 */
    @Volatile
    private var hostContext: Context? = null

    /** 单线程池：异步推送统计快照到模块 App 进程，不阻塞 WebSocket 处理线程 */
    private val statsPusher: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RingOnLLM-StatsPusher").apply { isDaemon = true }
    }

    /** 会话缓存：dialogId -> 消息历史 + 最后访问时间戳（仅 independent 模式使用） */
    private val sessions = ConcurrentHashMap<String, SessionState>()

    /**
     * 连续上下文（single）模式全局会话槽：小米手环每次提问下发全新 dialog_id，
     * 若按 dialogId 分组历史永远续接不上；同一时刻手环只有单用户串行提问，
     * 窗口过期/新话题由 askLocked 按 lastAccessMs 判定清空即可。
     */
    @Volatile
    private var singleSession: SessionState? = null

    /** 保护 singleSession 懒创建的锁 */
    private val singleSessionLock = Any()

    /** 全局 Json 实例：忽略响应中的未知字段，兼容不同厂商的扩展字段 */
    private val json = Json { ignoreUnknownKeys = true }

    // ==================== API 调用统计 ====================

    /** 单次 API 调用记录 */
    data class ApiCallRecord(
        val timestamp: Long,       // 调用时间戳
        val apiType: String,        // "openai"/"anthropic"
        val model: String,          // 使用的模型名
        val querySummary: String,   // 查询文本摘要（前 40 字）
        val promptTokens: Int,      // 输入 token 数
        val completionTokens: Int,  // 输出 token 数
        val totalTokens: Int,       // 总 token 数
        val durationMs: Long,       // 请求耗时（毫秒）
        val success: Boolean,       // 是否成功
    )

    /** 调用历史（环形缓冲，满则丢弃最旧） */
    private val callLogs = ArrayDeque<ApiCallRecord>()

    /** 累计统计 */
    @Volatile private var totalCalls = 0
    @Volatile private var totalPromptTokens = 0L
    @Volatile private var totalCompletionTokens = 0L
    @Volatile private var totalFailures = 0

    /**
     * 获取统计快照（线程安全，供设置页 UI 读取）。
     * 优先返回持久化统计（模块 App 进程内，重启后仍保留）；
     * 持久化无数据（Hook 进程未初始化 StatsStore）时回退内存统计。
     */
    @JvmStatic
    fun getStatsSnapshot(): CallStats {
        val persisted = StatsStore.read()
        if (persisted.totalCalls > 0 || persisted.recentCalls.isNotEmpty()) {
            return persisted.toCallStats()
        }
        return memoryStatsSnapshot()
    }

    /** 内存统计快照（不经过持久化优先逻辑，供持久化同步与回退使用） */
    private fun memoryStatsSnapshot(): CallStats {
        synchronized(callLogs) {
            return CallStats(
                totalCalls = totalCalls,
                totalFailures = totalFailures,
                totalPromptTokens = totalPromptTokens,
                totalCompletionTokens = totalCompletionTokens,
                recentCalls = callLogs.toList().reversed().take(20),
            )
        }
    }

    /** 清除统计（内存 + 持久化） */
    @JvmStatic
    fun clearStats() {
        synchronized(callLogs) {
            callLogs.clear()
            totalCalls = 0
            totalPromptTokens = 0
            totalCompletionTokens = 0
            totalFailures = 0
        }
        // 同步清除持久化统计（仅模块 App 进程有效；Hook 进程未初始化则静默跳过）
        StatsStore.clear()
        LogCollector.i(TAG, "API 调用统计已清除")
    }

    data class CallStats(
        val totalCalls: Int,
        val totalFailures: Int,
        val totalPromptTokens: Long,
        val totalCompletionTokens: Long,
        val recentCalls: List<ApiCallRecord>,
    ) {
        val totalTokens: Long get() = totalPromptTokens + totalCompletionTokens
    }

    /** 持久化统计 -> CallStats 转换（totalTokens 由 prompt+completion 推算） */
    private fun StatsStore.PersistentStats.toCallStats(): CallStats = CallStats(
        totalCalls = totalCalls,
        totalFailures = totalFailures,
        totalPromptTokens = totalPromptTokens,
        totalCompletionTokens = totalCompletionTokens,
        recentCalls = recentCalls.map { c ->
            ApiCallRecord(
                timestamp = c.timestamp,
                apiType = c.apiType,
                model = c.model,
                querySummary = c.querySummary,
                promptTokens = c.promptTokens,
                completionTokens = c.completionTokens,
                totalTokens = c.promptTokens + c.completionTokens,
                durationMs = c.durationMs,
                success = c.success,
            )
        },
    )

    /** 记录一次 API 调用（内部调用，在 askLocked 结束后调用） */
    private fun recordCall(
        apiType: String,
        model: String,
        querySummary: String,
        promptTokens: Int,
        completionTokens: Int,
        durationMs: Long,
        success: Boolean,
    ) {
        val totalTk = promptTokens + completionTokens
        synchronized(callLogs) {
            totalCalls++
            totalPromptTokens += promptTokens
            totalCompletionTokens += completionTokens
            if (!success) totalFailures++
            val record = ApiCallRecord(
                timestamp = System.currentTimeMillis(),
                apiType = apiType,
                model = model,
                querySummary = querySummary.take(40),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTk,
                durationMs = durationMs,
                success = success,
            )
            callLogs.addLast(record)
            if (callLogs.size > MAX_CALL_LOGS) callLogs.removeFirst()
        }
        // 记录到日志（供导出查看）
        val status = if (success) "成功" else "失败"
        LogCollector.i(TAG, "调用统计 | $status | $apiType | $model | 输入${promptTokens}tk | 输出${completionTokens}tk | 总${totalTk}tk | 耗时${durationMs}ms")
    }

    /**
     * 把当前内存统计快照异步推送到模块 App 进程（经 StatsContentProvider 跨进程写入
     * 模块 App 的 SharedPreferences，设置页统计 Tab 可实时读取）。
     *
     * 若宿主 Context 未注入（如设置页内测试连接场景，本进程即模块 App，直接落盘即可），
     * 则退化为直接写入本进程 StatsStore。
     */
    private fun pushStatsToModuleApp() {
        val snapshot = memoryStatsSnapshot()
        val ctx = hostContext ?: resolveHostContext()
        if (ctx == null) {
            // 本进程就是模块 App（测试连接）或 Hook 进程早期（宿主 Context 暂不可用）：
            // 直接写本进程 StatsStore（Hook 进程未初始化时静默跳过，后续调用会再尝试推送）
            StatsStore.importFromMemory(snapshot)
            return
        }
        // 宿主进程（com.mi.health）：异步跨进程推送，避免阻塞 WebSocket 处理线程
        val payload = try {
            StatsStore.encode(snapshot)
        } catch (t: Throwable) {
            LogCollector.w(TAG, "统计快照序列化失败，跳过推送: ${t.message}")
            return
        }
        statsPusher.execute {
            try {
                ctx.contentResolver.call(
                    Uri.Builder().scheme("content").authority(StatsContentProvider.AUTHORITY).build(),
                    StatsContentProvider.METHOD_PUSH,
                    payload,
                    null,
                )
            } catch (t: Throwable) {
                LogCollector.w(TAG, "推送统计到模块 App 失败（不影响主流程）: ${t.message}")
            }
        }
    }

    /**
     * 解析宿主进程 Application Context（供跨进程推送统计）。
     * 优先使用 [init] 注入的 Context；注入失败时反射 ActivityThread.currentApplication() 兜底
     * （onModuleLoaded 阶段宿主 App 可能尚未完全启动，后续调用时通常已可用）。
     */
    private fun resolveHostContext(): Context? {
        val ctx = hostContext ?: runCatching {
            val thread = Class.forName("android.app.ActivityThread")
            thread.getDeclaredMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
        hostContext = ctx
        return ctx
    }

    /**
     * 初始化：注入配置读取器与宿主 Context。
     * 应在 Hook 进程启动（模块加载）时调用一次。
     *
     * @param hostContext 宿主进程 Application Context；null 时统计仅保留内存/日志，
     *                    无法跨进程同步到设置页（App 进程测试连接场景传 null 即可）。
     */
    fun init(config: ConfigStore, hostContext: Context? = null) {
        this.config = config
        this.hostContext = hostContext
        LogCollector.i(TAG, "LlmClient 已初始化")
    }

    /**
     * 注入宿主进程 Application Context（供跨进程推送统计）。
     * 与 [init] 的 hostContext 参数等效，专用于 init 后延迟补偿（如 onPackageLoaded
     * 时宿主 App 已完全启动，此时反射可获取到有效 Context）。
     */
    fun setHostContext(context: Context?) {
        if (context != null) hostContext = context
    }

    /**
     * 主调用入口：根据用户识别文本调用 LLM 获取回答。
     *
     * @param dialogId WebSocket 消息的 dialog_id，用于会话关联
     * @param queryText 用户语音识别文本
     * @return 回答文本；失败/超时/未初始化返回 null
     */
    fun ask(dialogId: String, queryText: String): String? {
        val cfg = config ?: run {
            LogCollector.e(TAG, "ask: LlmClient 尚未调用 init，返回 null")
            return null
        }
        val text = queryText.trim()
        if (text.isEmpty()) {
            LogCollector.w(TAG, "ask: 语音识别文本为空，忽略")
            return null
        }
        if (dialogId.isBlank()) {
            LogCollector.w(TAG, "ask: dialogId 为空，忽略")
            return null
        }

        // 会话过多时顺带清理超窗的旧会话，避免长期驻留内存
        evictStaleSessions(cfg)

        // single（连续上下文）模式复用全局会话槽：小爱协议每次提问下发全新
        // dialog_id，按 dialogId 分组会让历史永远续接不上；independent 模式
        // 保持按 dialogId 各开新会话的既有行为。
        val session: SessionState = if (cfg.getContextMode() == "single") {
            synchronized(singleSessionLock) {
                singleSession ?: SessionState().also { singleSession = it }
            }
        } else {
            sessions.getOrPut(dialogId) { SessionState() }
        }
        // 同一会话串行执行（含网络 I/O），避免并发改写历史导致消息乱序
        return synchronized(session) { askLocked(cfg, session, text, dialogId) }
    }

    // ==================== 会话管理 ====================

    /** 单个会话状态：消息历史 + 最后访问时间戳 */
    private class SessionState {
        /** 历史消息（仅 user/assistant 交替，不含 system），已按 contextLength 裁剪 */
        val messages = mutableListOf<ChatMessage>()
        /** 上次调用时间戳（毫秒） */
        var lastAccessMs = 0L
    }

    /** 加锁后的核心逻辑：组装消息 -> 路由请求 -> 记录历史 */
    private fun askLocked(
        cfg: ConfigStore,
        session: SessionState,
        text: String,
        dialogId: String,
    ): String? {
        val ctxMode = cfg.getContextMode()
        val windowMs = cfg.getContextWindowMs()
        val ctxLen = cfg.getContextLength().coerceAtLeast(0)
        val now = System.currentTimeMillis()

        // 会话续接判定：single 模式 且 距上次调用仍在窗口内 且 有历史 -> 沿用；否则开新会话
        val keepHistory = ctxMode == "single" &&
            session.messages.isNotEmpty() &&
            now - session.lastAccessMs < windowMs
        if (!keepHistory) session.messages.clear()

        // —— 组装消息：system 提示 + 最近历史（裁剪）+ 本轮 user ——
        val messages = ArrayList<ChatMessage>(ctxLen + 2)
        val systemPrompt = cfg.getSystemPrompt()
        if (systemPrompt.isNotBlank()) messages.add(ChatMessage("system", systemPrompt))

        var history = session.messages.takeLast(ctxLen)
        // 裁剪后若以 assistant 开头则再丢一条：兼容 Anthropic 要求消息以 user 起始且交替
        if (history.firstOrNull()?.role == "assistant") history = history.drop(1)
        LogCollector.i(
            TAG,
            "会话${if (keepHistory) "续接" else "新开"}（mode=$ctxMode, 距上次 ${now - session.lastAccessMs}ms）" +
                "携带历史 ${history.size} 条 dialogId=$dialogId",
        )
        messages.addAll(history)
        messages.add(ChatMessage("user", text))

        // —— 按 API 类型路由（默认 OpenAI 兼容），记录耗时用于统计 ——
        val apiType = cfg.getApiType().trim().lowercase()
        val startMs = System.currentTimeMillis()
        val result = when (apiType) {
            "anthropic" -> requestAnthropic(cfg, messages)
            else -> requestOpenAi(cfg, messages)
        }
        val durationMs = System.currentTimeMillis() - startMs

        // —— 记录 API 调用统计 ——
        recordCall(
            apiType = if (apiType == "anthropic") "anthropic" else "openai",
            model = cfg.getModel(),
            querySummary = text,
            promptTokens = result?.promptTokens ?: 0,
            completionTokens = result?.completionTokens ?: 0,
            durationMs = durationMs,
            success = result != null && !result.text.isNullOrBlank(),
        )
        // 持久化当前内存统计到模块 App 进程（经 ContentProvider 跨进程推送，
        // 设置页统计 Tab 才能读到手环真实调用数据）
        pushStatsToModuleApp()

        val answer = result?.text

        // 收尾：更新最后访问时间；成功后把本轮 user/assistant 写入历史并裁剪
        session.lastAccessMs = System.currentTimeMillis()
        if (answer != null) {
            session.messages.add(ChatMessage("user", text))
            session.messages.add(ChatMessage("assistant", answer.trim()))
            trimSession(session, ctxLen)
        }
        return answer
    }

    /** 一次请求的结果：回答文本 + token 用量（统计用） */
    private data class RequestResult(
        val text: String?,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    )

    /**
     * 裁剪会话历史：只保留最近 maxContext 条；丢最旧时避免以 assistant 开头，
     * 保证 user/assistant 交替规则（Anthropic 必需）。
     */
    private fun trimSession(session: SessionState, maxContext: Int) {
        if (maxContext <= 0) {
            session.messages.clear()
            return
        }
        if (session.messages.size > maxContext) {
            session.messages.subList(0, session.messages.size - maxContext).clear()
        }
        while (session.messages.firstOrNull()?.role == "assistant") {
            session.messages.removeAt(0)
        }
    }

    /** 惰性清理：会话数超过阈值时，剔除已超窗（至少 60s）的旧会话 */
    private fun evictStaleSessions(cfg: ConfigStore) {
        if (sessions.size <= MAX_SESSIONS) return
        val now = System.currentTimeMillis()
        val keepMs = cfg.getContextWindowMs().coerceAtLeast(60_000L)
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastAccessMs > keepMs) it.remove()
        }
    }

    // ==================== OpenAI 兼容路由 ====================

    /**
     * OpenAI 兼容请求：POST {base_url}/v1/chat/completions。
     * 思考模式（deepseek-reasoner）：模型自带推理，无需额外请求字段；
     * 响应中按需读取非标准的 reasoning_content 字段。
     */
    private fun requestOpenAi(cfg: ConfigStore, messages: List<ChatMessage>): RequestResult? {
        val baseUrl = cfg.getBaseUrl().trimEnd('/')
        if (baseUrl.isEmpty()) {
            LogCollector.e(TAG, "OpenAI: baseUrl 为空，跳过请求")
            return null
        }
        val apiKey = cfg.getApiKey()
        if (apiKey.isBlank()) {
            LogCollector.e(TAG, "OpenAI: API Key 为空，跳过请求")
            return null
        }
        val url = resolveApiUrl(cfg, baseUrl, OPENAI_PATH)

        // DeepSeek V4 思考模式由请求体 thinking 控制（默认 enabled，需显式 disabled 才关闭）；
        // 旧 deepseek-chat / deepseek-reasoner 模型名已弃用。
        // 仅当配置了思考相关字段时才发送，避免污染第三方 OpenAI 兼容服务。
        val thinkingOn = cfg.isThinkingMode()
        val deepSeekStyle = cfg.getModel().contains("deepseek", ignoreCase = true) ||
            baseUrl.contains("deepseek.com", ignoreCase = true)

        // 思考模式下 DeepSeek 文档声明 temperature/top_p 不生效：直接不传，避免歧义
        val temperature = if (thinkingOn && deepSeekStyle) null else cfg.getTemperature()
        val topP = if (thinkingOn && deepSeekStyle) null else cfg.getTopP()

        // 思考模式下推理 token 计入 max_tokens 总额且生成更慢：抬高上限与超时下限，
        // 否则预算会被思考过程耗尽，正式答案被截断（content 为空，只剩 reasoning_content）
        val maxTokens = resolveMaxTokens(cfg, thinkingOn)
        val timeoutMs = resolveTimeoutMs(cfg, thinkingOn)

        val body = OpenAiRequestBody(
            model = cfg.getModel(),
            messages = messages,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            stream = false, // 非流式，一次取回完整回答
            topK = cfg.getTopK()?.takeIf { it > 0 }, // 未设置或 <=0 则省略 top_k
            thinking = if (deepSeekStyle) OpenAiThinking(type = if (thinkingOn) "enabled" else "disabled") else null,
            reasoningEffort = if (thinkingOn && deepSeekStyle) cfg.getReasoningEffort() else null,
        )
        val bodyJson = json.encodeToString(OpenAiRequestBody.serializer(), body)
        LogCollector.i(TAG, "OpenAI 请求 url=$url body=${truncate(bodyJson)}")

        val response = postJson(
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = bodyJson,
            timeoutMs = timeoutMs,
        ) ?: return null

        return try {
            val resp = json.decodeFromString(OpenAiResponseBody.serializer(), response)
            val choice = resp.choices.firstOrNull()
            val message = choice?.message
            if (message == null) {
                LogCollector.e(TAG, "OpenAI 响应缺少 choices[0].message，原文=${truncate(response)}")
                return null
            }
            // 仅记录推理过程，绝不作为回答内容（DeepSeek 的 reasoning_content 字段）
            val reasoningEnabled = cfg.isThinkingMode()
            if (reasoningEnabled && !message.reasoningContent.isNullOrBlank()) {
                LogCollector.i(TAG, "reasoning_content: ${truncate(message.reasoningContent!!)}")
            }
            // 输出被 max_tokens 截断：答案不完整，记 WARN 提示调大 max_tokens
            if (choice.finishReason == "length") {
                LogCollector.w(TAG, "OpenAI 输出被 max_tokens 截断（finish_reason=length），建议调大 max_tokens")
            }

            val usage = resp.usage
            val content = message.content?.trim()
            val text = if (content.isNullOrEmpty()) {
                // content 为空说明答案被推理过程耗尽或响应异常。此处绝不回退使用
                // reasoning_content —— 思考过程是内部推理，不能作为回答展示给用户，
                // 直接放弃替换（保留小爱原始 Toast）才是正确行为。
                LogCollector.w(
                    TAG,
                    "OpenAI 响应 content 为空（思考预算耗尽或异常），放弃替换；" +
                        "reasoning=${message.reasoningContent?.length ?: 0}字，原文=${truncate(response)}",
                )
                null
            } else {
                content
            }
            RequestResult(
                text = text,
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens ?: 0,
            )
        } catch (t: Throwable) {
            LogCollector.e(TAG, "OpenAI 响应解析失败，原文=${truncate(response)}", t)
            null
        }
    }

    // ==================== Anthropic 路由 ====================

    /**
     * Anthropic 请求：POST {base_url}/v1/messages。
     * 思考模式开启时请求体附加 thinking 块；响应 content 可能含 thinking 与 text 两种块。
     */
    private fun requestAnthropic(cfg: ConfigStore, messages: List<ChatMessage>): RequestResult? {
        val baseUrl = cfg.getBaseUrl().trimEnd('/')
        if (baseUrl.isEmpty()) {
            LogCollector.e(TAG, "Anthropic: baseUrl 为空，跳过请求")
            return null
        }
        val apiKey = cfg.getApiKey()
        if (apiKey.isBlank()) {
            LogCollector.e(TAG, "Anthropic: API Key 为空，跳过请求")
            return null
        }
        val url = resolveApiUrl(cfg, baseUrl, ANTHROPIC_PATH)

        // Anthropic 官方 API 不接受 messages 内出现 system 角色，需拆到顶层 system 字段
        val system = messages.firstOrNull { it.role == "system" }?.content
        val chatMessages = messages.filterNot { it.role == "system" }

        // DeepSeek Anthropic 兼容端点同样支持思考模式（标准 thinking 块 + 特有 output_config.effort）
        val thinkingOn = cfg.isThinkingMode()
        val deepSeekStyle = cfg.getModel().contains("deepseek", ignoreCase = true) ||
            baseUrl.contains("deepseek.com", ignoreCase = true)

        // 思考模式下 temperature/top_p 不生效（DeepSeek 文档声明），直接不传
        val temperature = if (thinkingOn) null else cfg.getTemperature()
        val topP = if (thinkingOn) null else cfg.getTopP()

        // 思考模式下推理 token 计入 max_tokens，且 Anthropic 要求 budget_tokens < max_tokens
        val maxTokens = resolveMaxTokens(cfg, thinkingOn)
        val timeoutMs = resolveTimeoutMs(cfg, thinkingOn)

        val body = AnthropicRequestBody(
            model = cfg.getModel(),
            messages = chatMessages,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            topK = cfg.getTopK()?.takeIf { it > 0 }, // 未设置或 <=0 则省略 top_k
            system = system?.takeIf { it.isNotBlank() },
            thinking = if (thinkingOn) {
                // 预算 = 总上限 - 回答预留，保证 budget_tokens 严格小于 max_tokens
                AnthropicThinking(
                    budgetTokens = (maxTokens - ANSWER_RESERVE_TOKENS).coerceAtLeast(MIN_THINKING_BUDGET_TOKENS),
                )
            } else {
                null
            },
            // DeepSeek Anthropic 兼容端点的思考强度控制字段（仅思考模式 + DeepSeek 时发送）
            outputConfig = if (thinkingOn && deepSeekStyle) {
                AnthropicOutputConfig(effort = cfg.getReasoningEffort())
            } else {
                null
            },
        )
        val bodyJson = json.encodeToString(AnthropicRequestBody.serializer(), body)
        LogCollector.i(TAG, "Anthropic 请求 url=$url body=${truncate(bodyJson)}")

        val response = postJson(
            url = url,
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json",
            ),
            body = bodyJson,
            timeoutMs = timeoutMs,
        ) ?: return null

        return try {
            val resp = json.decodeFromString(AnthropicResponseBody.serializer(), response)
            // content 为块数组：可能是 [thinking, text]，只取 text 块作为回答，
            // thinking 块仅记录日志，绝不作为回答内容
            var answer: String? = null
            for (block in resp.content) {
                when (block.type) {
                    "thinking" -> if (!block.thinking.isNullOrBlank()) {
                        LogCollector.i(TAG, "thinking: ${truncate(block.thinking!!)}")
                    }
                    "text" -> {
                        val text = block.text?.trim()
                        if (!text.isNullOrEmpty() && answer == null) answer = text
                    }
                    // 其他类型块忽略
                }
            }
            if (answer == null) {
                LogCollector.e(TAG, "Anthropic 响应 content 中无 text 块，原文=${truncate(response)}")
            }
            val usage = resp.usage
            RequestResult(
                text = answer,
                promptTokens = usage?.inputTokens ?: 0,
                completionTokens = usage?.outputTokens ?: 0,
            )
        } catch (t: Throwable) {
            LogCollector.e(TAG, "Anthropic 响应解析失败，原文=${truncate(response)}", t)
            null
        }
    }

    // ==================== 思考模式参数解析 ====================

    /**
     * 解析实际 max_tokens：思考模式使用独立配置的 [ConfigStore.getThinkingMaxTokens]。
     * 推理 token 同样计入 max_tokens 总额，思考模式需要远大于普通模式的预算，
     * 否则预算会被思考过程耗尽，正式答案来不及生成即被截断（content 为空）。
     */
    private fun resolveMaxTokens(cfg: ConfigStore, thinkingOn: Boolean): Int =
        if (thinkingOn) cfg.getThinkingMaxTokens() else cfg.getMaxTokens().coerceAtLeast(1)

    /**
     * 解析实际请求超时：思考模式使用独立配置的 [ConfigStore.getThinkingTimeoutMs]。
     * 思考模式生成更慢，需给足时间，避免答案尚未生成就被判定超时。
     */
    private fun resolveTimeoutMs(cfg: ConfigStore, thinkingOn: Boolean): Long =
        if (thinkingOn) cfg.getThinkingTimeoutMs() else cfg.getTimeoutMs()

    // ==================== 底层 HTTP 请求 ====================

    /**
     * 发送同步 POST JSON 请求（HttpURLConnection）。
     * connectTimeout 与 readTimeout 均设为 timeoutMs；
     * 网络异常、非 2xx 响应统一返回 null 并记日志。
     */
    private fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): String? {
        val timeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            for ((key, value) in headers) conn.setRequestProperty(key, value)

            // 写入请求体（明文 key 仅出现在请求头，body 中无敏感信息）
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { return it.bufferedReader(Charsets.UTF_8).readText() }
            } else {
                // 读取错误响应体（可能为空），经 LogCollector 自动脱敏后记录
                val error = conn.errorStream?.use { it.bufferedReader(Charsets.UTF_8).readText() } ?: ""
                LogCollector.e(TAG, "HTTP $code, url=$url, resp=${truncate(error)}")
                null
            }
        } catch (t: Throwable) {
            LogCollector.e(TAG, "网络请求失败, url=$url", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 截断超长文本用于日志，避免刷屏 */
    private fun truncate(raw: String): String =
        if (raw.length <= LOG_BODY_LIMIT) raw else raw.take(LOG_BODY_LIMIT) + "...[截断]"

    // ==================== URL 拼接 ====================

    /** OpenAI 兼容 API 路径（启用拼接时补全） */
    private const val OPENAI_PATH = "/v1/chat/completions"

    /** Anthropic 兼容 API 路径（启用拼接时补全） */
    private const val ANTHROPIC_PATH = "/v1/messages"

    /**
     * 根据「自动拼接 API 路径」开关决定最终请求地址：
     * - 开启：Base URL + API 路径（如 /v1/chat/completions）；
     * - 关闭：Base URL 视为完整请求地址，直接使用。
     */
    private fun resolveApiUrl(cfg: ConfigStore, baseUrl: String, apiPath: String): String {
        val base = baseUrl.trimEnd('/')
        return if (cfg.isAppendApiPath()) "$base$apiPath" else base
    }
}

// ==================== 序列化数据模型 ====================

/** 通用聊天消息：OpenAI/Anthropic 的 request/response 均复用此结构 */
@Serializable
private data class ChatMessage(
    val role: String, // "system" / "user" / "assistant"
    val content: String,
)

// ---------- OpenAI 兼容协议 ----------

/** DeepSeek 思考模式控制（旧 deepseek-reasoner 模型名已弃用，改由请求体控制） */
@Serializable
private data class OpenAiThinking(
    val type: String, // "enabled" / "disabled"
)

/** OpenAI 兼容请求体（stream 恒为 false；温度/top_p/top_k 未设置时省略；thinking 仅 DeepSeek 发送） */
@Serializable
private data class OpenAiRequestBody(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int,
    val stream: Boolean,
    @SerialName("top_k") val topK: Int? = null,
    val thinking: OpenAiThinking? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

/** OpenAI 响应中的 message（content 可能为 null，个别推理模型只回 reasoning_content） */
@Serializable
private data class OpenAiResponseMessage(
    val role: String = "assistant",
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
private data class OpenAiChoice(
    val message: OpenAiResponseMessage? = null,
    /** 结束原因：length 表示输出被 max_tokens 截断，答案不完整 */
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class OpenAiResponseBody(
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

/** OpenAI 返回的 token 用量统计 */
@Serializable
private data class OpenAiUsage(
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int = 0,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int = 0,
)

// ---------- Anthropic 协议 ----------

/** Anthropic 思考模式参数（budget_tokens 按配置 max_tokens 传入） */
@Serializable
private data class AnthropicThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int,
)

/** DeepSeek Anthropic 兼容端点的思考强度控制（effort: high / max） */
@Serializable
private data class AnthropicOutputConfig(
    val effort: String,
)

/** Anthropic 请求体（system 拆到顶层；温度/top_p/top_k 未设置时省略） */
@Serializable
private data class AnthropicRequestBody(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    val system: String? = null,
    val thinking: AnthropicThinking? = null,
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
)

/** Anthropic 响应中的内容块：type 为 "text"（回答）或 "thinking"（思考） */
@Serializable
private data class AnthropicContentBlock(
    val type: String = "text",
    val text: String? = null,
    val thinking: String? = null,
)

@Serializable
private data class AnthropicResponseBody(
    val content: List<AnthropicContentBlock> = emptyList(),
    val usage: AnthropicUsage? = null,
)

/** Anthropic 返回的 token 用量统计 */
@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)
