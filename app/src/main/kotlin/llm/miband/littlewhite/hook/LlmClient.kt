package llm.miband.littlewhite.hook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

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

    /** 配置读取器：由 [init] 注入，未初始化前 [ask] 直接返回 null */
    private var config: ConfigStore? = null

    /** 会话缓存：dialogId -> 消息历史 + 最后访问时间戳 */
    private val sessions = ConcurrentHashMap<String, SessionState>()

    /** 全局 Json 实例：忽略响应中的未知字段，兼容不同厂商的扩展字段 */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 初始化：注入配置读取器。
     * 应在 Hook 进程启动（模块加载）时调用一次。
     */
    fun init(config: ConfigStore) {
        this.config = config
        LogCollector.i(TAG, "LlmClient 已初始化")
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

        val session = sessions.getOrPut(dialogId) { SessionState() }
        // 同一 dialogId 串行执行（含网络 I/O），避免并发改写历史导致消息乱序
        return synchronized(session) { askLocked(cfg, session, text) }
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
    private fun askLocked(cfg: ConfigStore, session: SessionState, text: String): String? {
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
        messages.addAll(history)
        messages.add(ChatMessage("user", text))

        // —— 按 API 类型路由（默认 OpenAI 兼容）——
        val answer = when (cfg.getApiType().trim().lowercase()) {
            "anthropic" -> requestAnthropic(cfg, messages)
            else -> requestOpenAi(cfg, messages)
        }

        // 收尾：更新最后访问时间；成功后把本轮 user/assistant 写入历史并裁剪
        session.lastAccessMs = System.currentTimeMillis()
        if (answer != null) {
            session.messages.add(ChatMessage("user", text))
            session.messages.add(ChatMessage("assistant", answer.trim()))
            trimSession(session, ctxLen)
        }
        return answer
    }

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
    private fun requestOpenAi(cfg: ConfigStore, messages: List<ChatMessage>): String? {
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
        val url = "$baseUrl/v1/chat/completions"

        // 温度 / top_p / top_k 允许未设置（null）——留空时省略该字段，使用 API 默认值
        val body = OpenAiRequestBody(
            model = cfg.getModel(),
            messages = messages,
            temperature = cfg.getTemperature(),
            topP = cfg.getTopP(),
            maxTokens = cfg.getMaxTokens().coerceAtLeast(1),
            stream = false, // 非流式，一次取回完整回答
            topK = cfg.getTopK()?.takeIf { it > 0 }, // 未设置或 <=0 则省略 top_k
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
            timeoutMs = cfg.getTimeoutMs(),
        ) ?: return null

        return try {
            val resp = json.decodeFromString(OpenAiResponseBody.serializer(), response)
            val message = resp.choices.firstOrNull()?.message
            if (message == null) {
                LogCollector.e(TAG, "OpenAI 响应缺少 choices[0].message，原文=${truncate(response)}")
                return null
            }
            // 思考模式开启且模型为 deepseek-reasoner 时，记录推理过程（非标准字段，按 API 支持情况）
            val reasoningEnabled = cfg.isThinkingMode() &&
                cfg.getModel().contains("deepseek-reasoner", ignoreCase = true)
            if (reasoningEnabled && !message.reasoningContent.isNullOrBlank()) {
                LogCollector.i(TAG, "reasoning_content: ${truncate(message.reasoningContent!!)}")
            }

            val content = message.content?.trim()
            val reasoning = message.reasoningContent?.trim()
            when {
                // 正常返回最终回答
                !content.isNullOrEmpty() -> content
                // 个别推理模型只回 reasoning_content（content 为空）时兜底
                !reasoning.isNullOrEmpty() -> {
                    LogCollector.w(TAG, "OpenAI 响应 content 为空，回退使用 reasoning_content")
                    reasoning
                }
                else -> {
                    LogCollector.e(TAG, "OpenAI 响应 message 无可用文本，原文=${truncate(response)}")
                    null
                }
            }
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
    private fun requestAnthropic(cfg: ConfigStore, messages: List<ChatMessage>): String? {
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
        val url = "$baseUrl/v1/messages"

        // Anthropic 官方 API 不接受 messages 内出现 system 角色，需拆到顶层 system 字段
        val system = messages.firstOrNull { it.role == "system" }?.content
        val chatMessages = messages.filterNot { it.role == "system" }
        val maxTokens = cfg.getMaxTokens().coerceAtLeast(1)

        val body = AnthropicRequestBody(
            model = cfg.getModel(),
            messages = chatMessages,
            maxTokens = maxTokens,
            temperature = cfg.getTemperature(),
            topP = cfg.getTopP(),
            topK = cfg.getTopK()?.takeIf { it > 0 }, // 未设置或 <=0 则省略 top_k
            system = system?.takeIf { it.isNotBlank() },
            thinking = if (cfg.isThinkingMode()) AnthropicThinking(budgetTokens = maxTokens) else null,
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
            timeoutMs = cfg.getTimeoutMs(),
        ) ?: return null

        return try {
            val resp = json.decodeFromString(AnthropicResponseBody.serializer(), response)
            // content 为块数组：可能是 [thinking, text]，取第一个 text 块作为回答
            for (block in resp.content) {
                when (block.type) {
                    "thinking" -> if (!block.thinking.isNullOrBlank()) {
                        LogCollector.i(TAG, "thinking: ${truncate(block.thinking!!)}")
                    }
                    "text" -> {
                        val text = block.text?.trim()
                        if (!text.isNullOrEmpty()) return text
                    }
                    // 其他类型块忽略
                }
            }
            LogCollector.e(TAG, "Anthropic 响应 content 中无 text 块，原文=${truncate(response)}")
            null
        } catch (t: Throwable) {
            LogCollector.e(TAG, "Anthropic 响应解析失败，原文=${truncate(response)}", t)
            null
        }
    }

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
}

// ==================== 序列化数据模型 ====================

/** 通用聊天消息：OpenAI/Anthropic 的 request/response 均复用此结构 */
@Serializable
private data class ChatMessage(
    val role: String, // "system" / "user" / "assistant"
    val content: String,
)

// ---------- OpenAI 兼容协议 ----------

/** OpenAI 兼容请求体（stream 恒为 false；温度/top_p/top_k 未设置时省略，使用 API 默认值） */
@Serializable
private data class OpenAiRequestBody(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int,
    val stream: Boolean,
    @SerialName("top_k") val topK: Int? = null,
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
)

@Serializable
private data class OpenAiResponseBody(
    val choices: List<OpenAiChoice> = emptyList(),
)

// ---------- Anthropic 协议 ----------

/** Anthropic 思考模式参数（budget_tokens 按配置 max_tokens 传入） */
@Serializable
private data class AnthropicThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int,
)

/** Anthropic 请求体（system 拆到顶层，thinking 未开启时省略；温度/top_p/top_k 未设置时省略） */
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
)
