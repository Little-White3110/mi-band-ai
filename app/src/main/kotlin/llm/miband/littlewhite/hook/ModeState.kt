package llm.miband.littlewhite.hook

import llm.miband.littlewhite.config.ConfigKeys
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector

/**
 * 环上LLM —— 回答模式内存状态机（Hook 进程单例）
 *
 * 实现「语音指令切换小爱 / LLM 回答」的核心状态：
 * - 默认模式（LLM 或小爱）存于 Remote Preferences（[ConfigStore] 可读写）；
 * - 当前是否处于临时切换（[active] / [currentMode] / [deadlineMs]）仅为内存态，
 *   宿主进程（com.mi.health）重启后自动回到默认模式。
 *
 * 关键约束：Hook 进程对 Remote Prefs 只读，运行时切换状态只能放本内存对象，
 * 而不可写入配置（setter 会被框架静默忽略）。
 */
object ModeState {

    private const val TAG = "ModeState"

    /** 配置读取器：由 [init] 注入 */
    private var config: ConfigStore? = null

    /** 是否有进行中的临时切换 */
    @Volatile
    private var active = false

    /** 进行中切换的目标模式（仅 [active] 时有意义） */
    @Volatile
    private var currentMode = AnswerMode.LLM

    /**
     * 进行中切换的到期时间戳（毫秒）：
     * 0 = 未切换；>0 = 临时（到期回默认）；Long.MAX_VALUE = 永久切换。
     */
    @Volatile
    private var deadlineMs = 0L

    /** 注入配置读取器（幂等；MiHealthHook 安装时调用） */
    fun init(config: ConfigStore) {
        if (this.config == null) this.config = config
    }

    /**
     * 返回当前应生效的回答模式（惰性做过期检查）。
     * 无临时切换、或临时切换已到期 → 回退配置的默认模式。
     */
    fun resolveMode(): AnswerMode {
        val cfg = config ?: return AnswerMode.LLM
        val now = System.currentTimeMillis()
        // 到期则清除切换（回默认）；仅在有进行中切换时判断
        if (active && deadlineMs in 1 until Long.MAX_VALUE && now > deadlineMs) {
            active = false
            deadlineMs = 0L
            LogCollector.i(TAG, "临时模式已到期，回默认模式（${cfg.getDefaultMode()}）")
        }
        return if (active) currentMode else cfg.toDefaultAnswerMode()
    }

    /**
     * 执行一次模式切换（由语音指令命中触发）。
     * 若目标模式 == 配置默认模式，则视为「切回默认」——取消临时切换、不消耗时长；
     * 否则开启临时切换，并按该模式对应的时长设置到期时间（0 = 永久）。
     */
    fun switchTo(mode: AnswerMode) {
        val cfg = config ?: return
        val defaultMode = cfg.toDefaultAnswerMode()
        val now = System.currentTimeMillis()

        if (mode == defaultMode) {
            active = false
            deadlineMs = 0L
            LogCollector.i(TAG, "指令命中「默认模式」，已取消临时切换（${mode.label()}）")
            return
        }

        active = true
        currentMode = mode
        val durationMs = when (mode) {
            AnswerMode.LLM -> cfg.getLlmModeMs()
            AnswerMode.XIAOAI -> cfg.getXiaoaiModeMs()
        }
        deadlineMs = if (durationMs <= 0) Long.MAX_VALUE else now + durationMs
        LogCollector.i(TAG, "已切换到${mode.label()}模式，时长=${if (durationMs <= 0) "永久" else "${durationMs}ms"}")
    }

    /** 生成固定确认文案（含时长后缀），供指令 Toast 替换显示 */
    fun buildConfirmation(mode: AnswerMode): String {
        val cfg = config
        val durationMs = when (mode) {
            AnswerMode.LLM -> cfg?.getLlmModeMs()
            AnswerMode.XIAOAI -> cfg?.getXiaoaiModeMs()
        } ?: 0L
        // 永久：显式 0，或已设置为 Long.MAX_VALUE（到期不自动回默认）
        val permanent = durationMs <= 0 ||
            (active && currentMode == mode && deadlineMs == Long.MAX_VALUE)
        val suffix = if (permanent) {
            "（长期有效）"
        } else {
            val minutes = (durationMs + 59_999L) / 60_000L // ceil 到分钟，避免 0 分钟
            "，${minutes}分钟后自动恢复"
        }
        return when (mode) {
            AnswerMode.LLM -> "已切换到 AI 模式$suffix"
            AnswerMode.XIAOAI -> "已切换到小爱模式$suffix"
        }
    }

    /** 把配置字符串解析为默认模式枚举；非法值回退 LLM */
    private fun ConfigStore.toDefaultAnswerMode(): AnswerMode =
        when (getDefaultMode().trim().lowercase()) {
            ConfigKeys.VALUE_MODE_XIAOAI -> AnswerMode.XIAOAI
            else -> AnswerMode.LLM
        }

    private fun AnswerMode.label(): String =
        when (this) {
            AnswerMode.LLM -> "LLM"
            AnswerMode.XIAOAI -> "小爱"
        }
}

/** 回答模式枚举 */
enum class AnswerMode {
    LLM, XIAOAI
}