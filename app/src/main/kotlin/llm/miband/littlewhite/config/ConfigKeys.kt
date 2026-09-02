package llm.miband.littlewhite.config

/**
 * 环上LLM 全部配置项的键名与默认值定义（单一来源）。
 *
 * 键名与默认值同时被 Hook 进程（XposedModule 只读侧）和
 * 模块 App 进程（XposedService 读写侧）引用，两处必须保持一致。
 */
object ConfigKeys {

    // ---------- 键名 ----------
    const val KEY_ENABLED = "enabled"
    const val KEY_API_TYPE = "api_type"
    const val KEY_BASE_URL = "base_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_MODEL = "model"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_TOP_P = "top_p"
    const val KEY_TOP_K = "top_k"
    const val KEY_THINKING_MODE = "thinking_mode"
    const val KEY_SYSTEM_PROMPT = "system_prompt"
    const val KEY_CONTEXT_MODE = "context_mode"
    const val KEY_CONTEXT_WINDOW_MS = "context_window_ms"
    const val KEY_CONTEXT_LENGTH = "context_length"
    const val KEY_TIMEOUT_MS = "timeout_ms"
    const val KEY_MAX_TOKENS = "max_tokens"

    // ---------- 默认值 ----------
    const val DEFAULT_ENABLED = true

    /** API 类型："openai"（OpenAI 兼容，默认）/"anthropic" */
    const val DEFAULT_API_TYPE = "openai"

    /** 默认 DeepSeek 请求地址，调用时自动补全 /v1/chat/completions */
    const val DEFAULT_BASE_URL = "https://api.deepseek.com"

    /** API Key 明文默认空；存储时加密，读取时解密 */
    const val DEFAULT_API_KEY = ""

    const val DEFAULT_MODEL = "deepseek-chat"

    // 温度 / top_p / top_k 采用「空字符串 = 未设置」约定：
    // 留空时不传该参数给 API，使用 API 自身默认值。
    // 存储统一用 String（如 "0.7"），读取时解析为 Float/Int，空则视为 null。
    const val DEFAULT_TEMPERATURE = "" // 空 = 使用 API 默认温度
    const val DEFAULT_TOP_P = ""       // 空 = 使用 API 默认 top_p
    const val DEFAULT_TOP_K = ""       // 空 = 使用 API 默认 top_k

    /** 思考模式开关（OpenAI: deepseek-reasoner / Anthropic: thinking） */
    const val DEFAULT_THINKING_MODE = false

    /** 默认系统提示词：面向手环语音场景的简洁回答约束 */
    const val DEFAULT_SYSTEM_PROMPT =
        "你是一个语音助手，通过小米手环回答用户问题。\n" +
        "由于用户是语音输入，可能会有些许错别字。\n" +
        "回答要简洁，尽量控制在80字以内，不要使用markdown格式。"

    /** 会话模式："single"（连续调用携带上下文，默认）/"independent"（独立会话无上下文） */
    const val DEFAULT_CONTEXT_MODE = "single"

    /** 会话窗口时长（毫秒）：窗口内连续 Toast 视为同一会话 */
    const val DEFAULT_CONTEXT_WINDOW_MS = 60000

    /** 单次请求携带的最大上下文消息条数 */
    const val DEFAULT_CONTEXT_LENGTH = 10

    /** LLM 请求超时（毫秒） */
    const val DEFAULT_TIMEOUT_MS = 8000

    /** 最大生成 Token 数 */
    const val DEFAULT_MAX_TOKENS = 200
}
