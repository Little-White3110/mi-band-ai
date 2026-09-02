package llm.miband.littlewhite.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import llm.miband.littlewhite.hook.LlmClient

/**
 * 持久化存储 API 调用统计（设置页进程内有效）。
 * 序列化存储到模块 App 的 SharedPreferences "llm_stats"。
 * Hook 进程的统计由日志记录，可通过导出日志查看。
 */
object StatsStore {
    private const val PREFS = "llm_stats"
    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null

    fun init(context: Context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Serializable data class PersistentStats(
        val totalCalls: Int = 0,
        val totalFailures: Int = 0,
        val totalPromptTokens: Long = 0,
        val totalCompletionTokens: Long = 0,
        val recentCalls: List<PersistentCallRecord> = emptyList(),
    )
    @Serializable data class PersistentCallRecord(
        val timestamp: Long = 0, val apiType: String = "", val model: String = "",
        val querySummary: String = "", val promptTokens: Int = 0, val completionTokens: Int = 0,
        val durationMs: Long = 0, val success: Boolean = false,
    )

    fun read(): PersistentStats {
        val p = prefs ?: return PersistentStats()
        val raw = p.getString("stats", null) ?: return PersistentStats()
        return try { json.decodeFromString(PersistentStats.serializer(), raw) } catch (_: Throwable) { PersistentStats() }
    }

    fun write(stats: PersistentStats) {
        prefs?.edit()?.putString("stats", json.encodeToString(PersistentStats.serializer(), stats))?.apply()
    }

    /** 从 LlmClient.CallStats 导入到持久化存储 */
    fun importFromMemory(stats: LlmClient.CallStats) {
        write(PersistentStats(
            totalCalls = stats.totalCalls, totalFailures = stats.totalFailures,
            totalPromptTokens = stats.totalPromptTokens, totalCompletionTokens = stats.totalCompletionTokens,
            recentCalls = stats.recentCalls.map { c -> PersistentCallRecord(
                timestamp = c.timestamp, apiType = c.apiType, model = c.model,
                querySummary = c.querySummary, promptTokens = c.promptTokens,
                completionTokens = c.completionTokens, durationMs = c.durationMs, success = c.success,
            )}
        ))
    }

    /** 将 CallStats 序列化为 JSON 字符串（供 Hook 进程跨进程推送到模块 App） */
    fun encode(stats: LlmClient.CallStats): String {
        val ps = PersistentStats(
            totalCalls = stats.totalCalls, totalFailures = stats.totalFailures,
            totalPromptTokens = stats.totalPromptTokens, totalCompletionTokens = stats.totalCompletionTokens,
            recentCalls = stats.recentCalls.map { c -> PersistentCallRecord(
                timestamp = c.timestamp, apiType = c.apiType, model = c.model,
                querySummary = c.querySummary, promptTokens = c.promptTokens,
                completionTokens = c.completionTokens, durationMs = c.durationMs, success = c.success,
            )},
        )
        return json.encodeToString(PersistentStats.serializer(), ps)
    }

    /** 从 JSON 恢复持久化统计并写入（供 ContentProvider 接收 Hook 进程推送时调用） */
    fun importJson(raw: String) {
        val ps = try {
            json.decodeFromString(PersistentStats.serializer(), raw)
        } catch (_: Throwable) { return }
        write(ps)
    }

    /** 读取持久化统计并转换为 LlmClient.CallStats（供设置页 UI 直接使用） */
    fun readCallStats(): LlmClient.CallStats {
        val s = read()
        return LlmClient.CallStats(
            totalCalls = s.totalCalls,
            totalFailures = s.totalFailures,
            totalPromptTokens = s.totalPromptTokens,
            totalCompletionTokens = s.totalCompletionTokens,
            recentCalls = s.recentCalls.map { c -> LlmClient.ApiCallRecord(
                timestamp = c.timestamp, apiType = c.apiType, model = c.model,
                querySummary = c.querySummary, promptTokens = c.promptTokens,
                completionTokens = c.completionTokens,
                totalTokens = c.promptTokens + c.completionTokens,
                durationMs = c.durationMs, success = c.success,
            )},
        )
    }

    fun clear() { prefs?.edit()?.remove("stats")?.apply() }
}
