@file:Suppress("unused")

package llm.miband.littlewhite.config

import android.content.SharedPreferences
import android.util.Base64
import io.github.libxposed.api.XposedModule
import io.github.libxposed.service.XposedService

/**
 * 环上LLM 配置存储 —— 封装 Remote Preferences 的读写入口。
 *
 * 同一份数据通过 [fromModule]（Hook 进程只读）和 [fromService]（模块 App 进程可写）
 * 获取对应的 [ConfigStore] 实例，两者底层指向同一个 Remote Preferences group，
 * 因此 Hook 端能实时读到 App 端写入的配置变更。
 */
class ConfigStore private constructor(private val prefs: SharedPreferences) {

    companion object {
        /** Remote Preferences 的 group 名称，两端必须一致 */
        private const val PREFS_GROUP = "config"

        /**
         * 从 Hook 进程端创建只读 [ConfigStore]。
         * XposedModule.getRemotePreferences() 返回的 SharedPreferences 在 Hook 进程中为只读，
         * setter 调用 prefs.edit().apply() 会被框架安全忽略，不会抛出异常。
         */
        @JvmStatic
        fun fromModule(module: XposedModule): ConfigStore =
            ConfigStore(module.getRemotePreferences(PREFS_GROUP))

        /**
         * 从模块 App 进程端创建可写 [ConfigStore]。
         * XposedService.getRemotePreferences() 返回的 SharedPreferences 支持读写。
         */
        @JvmStatic
        fun fromService(service: XposedService): ConfigStore =
            ConfigStore(service.getRemotePreferences(PREFS_GROUP))
    }

    // ==================== 读取器 ====================

    /** 模块启用开关 */
    fun isEnabled(): Boolean =
        prefs.getBoolean(ConfigKeys.KEY_ENABLED, ConfigKeys.DEFAULT_ENABLED)

    /** API 类型："openai"（OpenAI 兼容）/"anthropic" */
    fun getApiType(): String =
        prefs.getString(ConfigKeys.KEY_API_TYPE, ConfigKeys.DEFAULT_API_TYPE)
            ?: ConfigKeys.DEFAULT_API_TYPE

    /** 自定义请求地址：空白或非法时回退默认值（避免残留脏值导致请求失败） */
    fun getBaseUrl(): String {
        val v = prefs.getString(ConfigKeys.KEY_BASE_URL, ConfigKeys.DEFAULT_BASE_URL)
            ?.trim()?.trimEnd('/')
        return if (v.isNullOrEmpty()) ConfigKeys.DEFAULT_BASE_URL else v
    }

    /**
     * 是否在 Base URL 后自动拼接 API 路径：
     * true -> 补全 /v1/chat/completions（OpenAI）或 /v1/messages（Anthropic）；
     * false -> Base URL 视为完整请求地址直接使用。
     */
    fun isAppendApiPath(): Boolean =
        prefs.getBoolean(ConfigKeys.KEY_APPEND_API_PATH, ConfigKeys.DEFAULT_APPEND_API_PATH)

    /** API Key：读取时自动解密，解密失败返回空串 */
    fun getApiKey(): String =
        ApiKeyCipher.decrypt(prefs.getString(ConfigKeys.KEY_API_KEY, "") ?: "")

    /** 模型名称 */
    fun getModel(): String =
        prefs.getString(ConfigKeys.KEY_MODEL, ConfigKeys.DEFAULT_MODEL)
            ?: ConfigKeys.DEFAULT_MODEL

    /**
     * 采样温度：返回 null 表示未设置（使用 API 默认值）。
     * 存储为 String（如 "0.7"），空串或解析失败视为未设置。
     */
    fun getTemperature(): Float? =
        prefs.getString(ConfigKeys.KEY_TEMPERATURE, ConfigKeys.DEFAULT_TEMPERATURE)
            ?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()

    /** 核采样 top_p：返回 null 表示未设置（使用 API 默认值） */
    fun getTopP(): Float? =
        prefs.getString(ConfigKeys.KEY_TOP_P, ConfigKeys.DEFAULT_TOP_P)
            ?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()

    /** top_k：返回 null 表示未设置（使用 API 默认值）；>0 才传给 API */
    fun getTopK(): Int? =
        prefs.getString(ConfigKeys.KEY_TOP_K, ConfigKeys.DEFAULT_TOP_K)
            ?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** 思考模式开关 */
    fun isThinkingMode(): Boolean =
        prefs.getBoolean(ConfigKeys.KEY_THINKING_MODE, ConfigKeys.DEFAULT_THINKING_MODE)

    /** 思考强度（high / max），仅思考模式下生效 */
    fun getReasoningEffort(): String =
        prefs.getString(ConfigKeys.KEY_REASONING_EFFORT, ConfigKeys.DEFAULT_REASONING_EFFORT)
            ?: ConfigKeys.DEFAULT_REASONING_EFFORT

    /** 系统提示词：若用户未设置（空字符串），返回默认值 */
    fun getSystemPrompt(): String {
        val v = prefs.getString(ConfigKeys.KEY_SYSTEM_PROMPT, "") ?: ""
        return if (v.isBlank()) ConfigKeys.DEFAULT_SYSTEM_PROMPT else v
    }

    /** 会话模式："single"（连续上下文）/ "independent"（独立会话） */
    fun getContextMode(): String =
        prefs.getString(ConfigKeys.KEY_CONTEXT_MODE, ConfigKeys.DEFAULT_CONTEXT_MODE)
            ?: ConfigKeys.DEFAULT_CONTEXT_MODE

    /** 会话窗口时长（毫秒），返回 Long 兼容上层时间计算 */
    fun getContextWindowMs(): Long =
        prefs.getInt(ConfigKeys.KEY_CONTEXT_WINDOW_MS, ConfigKeys.DEFAULT_CONTEXT_WINDOW_MS).toLong()

    /** 单次请求携带的最大上下文消息条数 */
    fun getContextLength(): Int =
        prefs.getInt(ConfigKeys.KEY_CONTEXT_LENGTH, ConfigKeys.DEFAULT_CONTEXT_LENGTH)

    /** LLM 请求超时（毫秒），返回 Long 兼容上层时间计算 */
    fun getTimeoutMs(): Long =
        prefs.getInt(ConfigKeys.KEY_TIMEOUT_MS, ConfigKeys.DEFAULT_TIMEOUT_MS).toLong()

    /** 最大生成 Token 数 */
    fun getMaxTokens(): Int =
        prefs.getInt(ConfigKeys.KEY_MAX_TOKENS, ConfigKeys.DEFAULT_MAX_TOKENS)

    // ==================== 写入器 ====================

    fun setEnabled(v: Boolean) {
        prefs.edit().putBoolean(ConfigKeys.KEY_ENABLED, v).apply()
    }

    fun setApiType(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_API_TYPE, v).apply()
    }

    fun setBaseUrl(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_BASE_URL, v).apply()
    }

    /** 写入是否自动拼接 API 路径（true 补全 /v1/chat/completions 等，false 直接用 Base URL） */
    fun setAppendApiPath(v: Boolean) {
        prefs.edit().putBoolean(ConfigKeys.KEY_APPEND_API_PATH, v).apply()
    }

    /** API Key：写入时自动加密，不将明文存入 SharedPreferences 或日志 */
    fun setApiKey(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_API_KEY, ApiKeyCipher.encrypt(v)).apply()
    }

    fun setModel(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_MODEL, v).apply()
    }

    /** 写入温度：null 或空串表示未设置（使用 API 默认值），以 String 存储 */
    fun setTemperature(v: Float?) {
        prefs.edit().putString(ConfigKeys.KEY_TEMPERATURE, v?.toString() ?: "").apply()
    }

    /** 写入 top_p：null 或空串表示未设置（使用 API 默认值） */
    fun setTopP(v: Float?) {
        prefs.edit().putString(ConfigKeys.KEY_TOP_P, v?.toString() ?: "").apply()
    }

    /** 写入 top_k：null 或空串表示未设置（使用 API 默认值） */
    fun setTopK(v: Int?) {
        prefs.edit().putString(ConfigKeys.KEY_TOP_K, v?.toString() ?: "").apply()
    }

    fun setThinkingMode(v: Boolean) {
        prefs.edit().putBoolean(ConfigKeys.KEY_THINKING_MODE, v).apply()
    }

    /** 写入思考强度（high / max），仅思考模式下生效 */
    fun setReasoningEffort(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_REASONING_EFFORT, v).apply()
    }

    fun setSystemPrompt(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_SYSTEM_PROMPT, v).apply()
    }

    fun setContextMode(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_CONTEXT_MODE, v).apply()
    }

    fun setContextWindowMs(v: Int) {
        prefs.edit().putInt(ConfigKeys.KEY_CONTEXT_WINDOW_MS, v).apply()
    }

    fun setContextLength(v: Int) {
        prefs.edit().putInt(ConfigKeys.KEY_CONTEXT_LENGTH, v).apply()
    }

    fun setTimeoutMs(v: Int) {
        prefs.edit().putInt(ConfigKeys.KEY_TIMEOUT_MS, v).apply()
    }

    fun setMaxTokens(v: Int) {
        prefs.edit().putInt(ConfigKeys.KEY_MAX_TOKENS, v).apply()
    }

    // ==================== 变更监听 ====================

    /**
     * 注册配置变更监听器。
     * 直接委托给 [SharedPreferences.registerOnSharedPreferenceChangeListener]。
     * Hook 进程可通过此监听器实时感知 App 端写入的配置变化。
     */
    fun registerOnChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(l)
    }
}

// ====================================================================
// API Key 简单对称加密（XOR + Base64）
// 仅用于防误读（如日志、明文文件），非高安全方案。
// 加密密钥写死在代码内是已知的权衡 —— 避免引入额外加密依赖。
// 解密失败时静默返回空串，不抛出异常。
// ====================================================================
private object ApiKeyCipher {

    // 固定混淆密钥（长度 16 字节，与 Base64 输出无直接关联）
    private const val SALT = "R1ng0nLLM!2026*"

    /**
     * 加密：XOR 异或 + Base64 编码。
     * 输入空串时返回空串。
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val data = plain.toByteArray(Charsets.UTF_8)
        val salt = SALT.toByteArray(Charsets.UTF_8)
        for (i in data.indices) {
            data[i] = (data[i].toInt() xor salt[i % salt.size].toInt()).toByte()
        }
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /**
     * 解密：Base64 解码 + XOR 异或。
     * 输入空串或解密失败均返回空串。
     */
    fun decrypt(cipher: String): String {
        if (cipher.isEmpty()) return ""
        return try {
            val data = Base64.decode(cipher, Base64.NO_WRAP)
            val salt = SALT.toByteArray(Charsets.UTF_8)
            for (i in data.indices) {
                data[i] = (data[i].toInt() xor salt[i % salt.size].toInt()).toByte()
            }
            String(data, Charsets.UTF_8)
        } catch (_: Exception) {
            // 数据损坏或密钥不匹配时静默降级
            ""
        }
    }
}