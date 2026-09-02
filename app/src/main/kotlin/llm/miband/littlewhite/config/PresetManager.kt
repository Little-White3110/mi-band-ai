@file:Suppress("unused")

package llm.miband.littlewhite.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 环上LLM —— 配置预设管理（按分组独立保存/删除/应用）
 *
 * 分组（category）：
 * - "api"        ：API 相关（api_type / base_url / api_key / model）
 * - "generation" ：生成参数（temperature / top_p / top_k / thinking_mode /
 *                   reasoning_effort / system_prompt / timeout_ms / max_tokens）
 * - "session"    ：会话设置（context_mode / context_window_ms / context_length）
 *
 * 预设只保存在模块 App 本地（SharedPreferences "llm_presets"），
 * Hook 进程不读取预设，只读取 [ConfigStore] 中实际生效的配置 ——
 * 因此预设的保存/应用完全发生在模块 App 进程（设置页）。
 */
object PresetManager {

    private const val PREFS_NAME = "llm_presets"
    private const val KEY_PREFIX = "preset_"

    /** 分组常量，供 UI 与键映射引用 */
    const val CATEGORY_API = "api"
    const val CATEGORY_GENERATION = "generation"
    const val CATEGORY_SESSION = "session"

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    /** 初始化：必须传入模块 App 的 Context（SettingsActivity 内调用一次） */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 单个预设：名称 + 分组 + 配置键值映射（值统一为字符串，应用时按类型解析） */
    @Serializable
    data class ConfigPreset(
        val name: String,
        val category: String,
        val values: Map<String, String>,
    )

    // ==================== 增删查 ====================

    private fun keyOf(category: String, name: String): String = "$KEY_PREFIX$category:$name"

    /**
     * 保存预设。若同名已存在则覆盖（返回 true）；名称为空或未初始化返回 false。
     */
    fun savePreset(category: String, name: String, values: Map<String, String>): Boolean {
        val p = prefs ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val preset = ConfigPreset(trimmed, category, values)
        p.edit()
            .putString(keyOf(category, trimmed), json.encodeToString(ConfigPreset.serializer(), preset))
            .apply()
        return true
    }

    /** 删除指定预设。 */
    fun deletePreset(category: String, name: String) {
        val p = prefs ?: return
        p.edit().remove(keyOf(category, name.trim())).apply()
    }

    /** 列出某分组下全部预设名称（按名称排序）。 */
    fun listPresets(category: String): List<String> {
        val p = prefs ?: return emptyList()
        val prefix = "$KEY_PREFIX$category:"
        return p.all.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
    }

    /** 加载某预设的配置键值映射；不存在返回 null。 */
    fun loadPreset(category: String, name: String): Map<String, String>? {
        val p = prefs ?: return null
        val raw = p.getString(keyOf(category, name.trim()), null) ?: return null
        return try {
            json.decodeFromString(ConfigPreset.serializer(), raw).values
        } catch (_: Throwable) {
            null
        }
    }

    /** 判断某名称预设是否已存在。 */
    fun presetExists(category: String, name: String): Boolean {
        val p = prefs ?: return false
        return p.contains(keyOf(category, name.trim()))
    }

    /**
     * 导出某分组当前配置为「键 -> 字符串值」映射（供保存预设）。
     * 各键值取自 [ConfigStore] 的类型化 getter，统一字符串化：
     * 空/未设置的数值型（temperature/top_p/top_k）导出为空串，应用时解析为 null。
     */
    fun exportValues(config: ConfigStore, category: String): Map<String, String> = when (category) {
        CATEGORY_API -> linkedMapOf(
            ConfigKeys.KEY_API_TYPE to config.getApiType(),
            ConfigKeys.KEY_BASE_URL to config.getBaseUrl(),
            ConfigKeys.KEY_API_KEY to config.getApiKey(),
            ConfigKeys.KEY_MODEL to config.getModel(),
            ConfigKeys.KEY_APPEND_API_PATH to config.isAppendApiPath().toString(),
        )
        CATEGORY_GENERATION -> linkedMapOf(
            ConfigKeys.KEY_TEMPERATURE to (config.getTemperature()?.toString() ?: ""),
            ConfigKeys.KEY_TOP_P to (config.getTopP()?.toString() ?: ""),
            ConfigKeys.KEY_TOP_K to (config.getTopK()?.toString() ?: ""),
            ConfigKeys.KEY_THINKING_MODE to config.isThinkingMode().toString(),
            ConfigKeys.KEY_REASONING_EFFORT to config.getReasoningEffort(),
            ConfigKeys.KEY_SYSTEM_PROMPT to config.getSystemPrompt(),
            ConfigKeys.KEY_TIMEOUT_MS to config.getTimeoutMs().toString(),
            ConfigKeys.KEY_MAX_TOKENS to config.getMaxTokens().toString(),
        )
        CATEGORY_SESSION -> linkedMapOf(
            ConfigKeys.KEY_CONTEXT_MODE to config.getContextMode(),
            ConfigKeys.KEY_CONTEXT_WINDOW_MS to config.getContextWindowMs().toString(),
            ConfigKeys.KEY_CONTEXT_LENGTH to config.getContextLength().toString(),
        )
        else -> emptyMap()
    }

    // ==================== 应用预设到 ConfigStore ====================

    /**
     * 把一个预设的值映射应用回 [ConfigStore]。
     * 值统一为字符串，这里按各配置键的语义解析并调用对应 setter；
     * 解析失败的安全降级为不写该键（保留当前值）。
     */
    fun applyPreset(config: ConfigStore, values: Map<String, String>) {
        values.forEach { (key, value) ->
            when (key) {
                ConfigKeys.KEY_API_TYPE -> config.setApiType(value)
                ConfigKeys.KEY_BASE_URL -> config.setBaseUrl(value)
                ConfigKeys.KEY_API_KEY -> config.setApiKey(value)
                ConfigKeys.KEY_MODEL -> config.setModel(value)
                ConfigKeys.KEY_APPEND_API_PATH -> config.setAppendApiPath(value == "true")
                ConfigKeys.KEY_TEMPERATURE -> config.setTemperature(value.toFloatOrNull())
                ConfigKeys.KEY_TOP_P -> config.setTopP(value.toFloatOrNull())
                ConfigKeys.KEY_TOP_K -> config.setTopK(value.toIntOrNull())
                ConfigKeys.KEY_THINKING_MODE -> config.setThinkingMode(value == "true")
                ConfigKeys.KEY_REASONING_EFFORT -> config.setReasoningEffort(value)
                ConfigKeys.KEY_SYSTEM_PROMPT -> config.setSystemPrompt(value)
                ConfigKeys.KEY_TIMEOUT_MS -> config.setTimeoutMs(value.toIntOrNull() ?: ConfigKeys.DEFAULT_TIMEOUT_MS)
                ConfigKeys.KEY_MAX_TOKENS -> config.setMaxTokens(value.toIntOrNull() ?: ConfigKeys.DEFAULT_MAX_TOKENS)
                ConfigKeys.KEY_CONTEXT_MODE -> config.setContextMode(value)
                ConfigKeys.KEY_CONTEXT_WINDOW_MS ->
                    config.setContextWindowMs(value.toIntOrNull() ?: ConfigKeys.DEFAULT_CONTEXT_WINDOW_MS)
                ConfigKeys.KEY_CONTEXT_LENGTH ->
                    config.setContextLength(value.toIntOrNull() ?: ConfigKeys.DEFAULT_CONTEXT_LENGTH)
                // 其他键（如 enabled）不参与预设
            }
        }
    }
}
