package llm.miband.littlewhite.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * 环上LLM —— 日志收集器（单例 object）
 *
 * 使用流程：
 * 1. 进程启动时调用 [init] 传入可用 Context（App 进程传模块自身 Context，
 *    Hook 进程传宿主 com.mi.health 的 Context），确定日志文件落盘路径；
 * 2. 之后通过 [i]/[w]/[e] 记录日志，内容自动脱敏后写入内存缓冲 + 日志文件；
 * 3. 设置页通过 [getBufferedLogs] 实时展示、[exportLogFile] 导出分享。
 *
 * 设计要点：
 * - 单例 object，两个进程各自持有独立实例（App 进程 / Hook 宿主进程），
 *   日志文件路径由各自传入的 Context 决定，互不冲突；
 * - 内存环形缓冲：ArrayDeque 固定容量约 500 条，满则丢弃最旧；
 * - 文件追加写入：<cacheDir>/logs/llm.log（cacheDir 不可用时回退 <filesDir>/logs/llm.log）；
 * - 所有写入前都经过 [sanitize] 脱敏：sk- 开头的 API Key 与 Authorization/Bearer 头；
 * - 使用 synchronized 保证线程安全，不引入任何第三方依赖。
 */
object LogCollector {

    /** logcat 日志 TAG（与 MainModule 保持一致） */
    private const val TAG = "环上LLM"

    /** 内存环形缓冲容量上限（约 500 条，满则丢弃最旧） */
    private const val MAX_BUFFER = 500

    /** 日志子目录名 */
    private const val LOG_DIR_NAME = "logs"

    /** 日志文件名（固定单文件追加写入） */
    private const val LOG_FILE_NAME = "llm.log"

    /** 导出文件名前缀：llm_export_时间戳.log */
    private const val EXPORT_FILE_PREFIX = "llm_export_"

    /** 行内时间戳格式：yyyy-MM-dd HH:mm:ss.SSS（DateTimeFormatter 线程安全） */
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** 导出文件名时间戳格式 */
    private val EXPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /**
     * API Key 脱敏正则：sk- 开头 + 16 位以上 [A-Za-z0-9_-]，
     * 即整体 20~60 位的 token（OpenAI 等 sk- 密钥），统一替换为 sk-***。
     */
    private val API_KEY_REGEX = Regex("""sk-[A-Za-z0-9_\-]{16,}""")

    /**
     * Authorization / Bearer 头脱敏正则：
     * 保留关键字前缀，把后面的值整体替换为 ***；
     * 兼容 "Authorization: Bearer sk-xxx"、"Authorization: Basic xxx" 等常见形式。
     */
    private val AUTH_HEADER_REGEX =
        Regex("""(?i)(authorization\s*[:=]\s*(?:(?:bearer|basic|digest)\s+)?|bearer\s+)[^\s,;]+""")

    /** 内存环形缓冲：队尾追加、队头丢弃最旧 */
    private val buffer = ArrayDeque<String>(MAX_BUFFER)

    /** 保护内存缓冲的锁 */
    private val bufferLock = Any()

    /** 当前日志文件（由 [init] 设置）；未初始化时为 null（此时仅内存缓冲 + logcat） */
    @Volatile
    private var logFile: File? = null

    /** 保护文件选择与写入的锁：保证并发写入整批日志不交错 */
    private val fileLock = Any()

    /**
     * 初始化日志文件路径。重复调用幂等：路径未变化时直接返回。
     *
     * @param context 任意可用 Context；App 进程与 Hook 宿主进程各自传入自己的 Context，
     *                日志落在各自进程的私有目录，互不冲突。
     */
    fun init(context: Context) {
        // cacheDir 优先，不可用时回退 filesDir
        val dir = context.cacheDir ?: context.filesDir
        val candidate = File(dir, "$LOG_DIR_NAME/$LOG_FILE_NAME")
        synchronized(fileLock) {
            // 幂等：已初始化为同一路径则跳过，避免重复创建/重定向
            if (logFile == candidate) return
            logFile = try {
                val parent = candidate.parentFile
                if (parent != null && (parent.exists() || parent.mkdirs())) candidate else null
            } catch (_: Throwable) {
                null // 目录创建失败时仅保留内存缓冲
            }
        }
    }

    /** 记录一条 INFO 级别日志 */
    fun i(tag: String, msg: String) = log("I", tag, msg)

    /** 记录一条 WARN 级别日志 */
    fun w(tag: String, msg: String) = log("W", tag, msg)

    /** 记录一条 ERROR 级别日志 */
    fun e(tag: String, msg: String) = log("E", tag, msg)

    /** 记录一条 ERROR 级别日志，附带异常堆栈（脱敏后写入） */
    fun e(tag: String, msg: String, tr: Throwable) {
        // 把消息与完整堆栈拼接后统一走脱敏写入流程
        val stack = StringWriter().let { sw ->
            tr.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        val combined = if (msg.isBlank()) stack else "$msg\n$stack"
        log("E", tag, combined)
    }

    /** 统一写入入口：脱敏 -> 组装行 -> 内存缓冲 + 文件追加 + logcat 桥接 */
    private fun log(level: String, tag: String, msg: String) {
        val safe = sanitize(msg)
        val lines = buildLines(level, tag, safe)
        appendBuffer(lines)
        appendFile(lines)
        // logcat 桥接：便于开发期用 adb 过滤查看（内容已脱敏）
        when (level) {
            "I" -> lines.forEach { Log.i(TAG, it) }
            "W" -> lines.forEach { Log.w(TAG, it) }
            else -> lines.forEach { Log.e(TAG, it) }
        }
    }

    /**
     * 返回内存环形缓冲快照（时间从旧到新），供设置页实时展示。
     * 每条内容均已脱敏。
     */
    fun getBufferedLogs(): List<String> = synchronized(bufferLock) { buffer.toList() }

    /**
     * 导出日志文件：把内存日志 + 文件日志整理后写入新文件
     * <日志目录>/llm_export_时间戳.log 并返回，供设置页分享。
     * 未调用 [init]（无 Context / 文件路径未初始化）时返回 null。
     */
    fun exportLogFile(): File? {
        val parent = logFile?.parentFile ?: return null
        val export = File(parent, EXPORT_FILE_PREFIX + LocalDateTime.now().format(EXPORT_TIME_FORMAT) + ".log")
        return try {
            synchronized(fileLock) {
                FileWriter(export).use { writer ->
                    writer.write("===== 环上LLM 日志导出 @ ")
                    writer.write(LocalDateTime.now().format(TIME_FORMAT))
                    writer.write(" =====\n")

                    // 内存缓冲部分（全量）
                    val buffered = synchronized(bufferLock) { buffer.toList() }
                    writer.write("\n--- 内存缓冲（${buffered.size} 条） ---\n")
                    buffered.forEach { writer.write(it); writer.write("\n") }

                    // 文件日志部分
                    writer.write("\n--- 文件日志 ---\n")
                    val fileText = readSafely(logFile)
                    if (fileText.isNotEmpty()) writer.write(fileText)
                }
            }
            export
        } catch (_: Throwable) {
            null // 导出失败返回 null，由调用方兜底
        }
    }

    /**
     * 脱敏：把所有形如 sk- 开头的 API Key（20~60 位 token）替换为 sk-***，
     * 同时遮蔽常见 Authorization / Bearer 头（保留关键字，值替换为 ***）。
     * 设为公开：Hook 端自行拼装/转发日志时也可主动调用。
     */
    fun sanitize(input: String): String {
        // 先遮蔽 Authorization/Bearer 头（值整体替换，避免 key=value 形式残留）
        var out = AUTH_HEADER_REGEX.replace(input) { m -> m.groupValues[1] + "***" }
        // 再遮蔽独立出现的 sk- 密钥
        out = API_KEY_REGEX.replace(out, "sk-***")
        return out
    }

    // ---------------- 内部实现 ----------------

    /**
     * 组装日志行：格式 `yyyy-MM-dd HH:mm:ss.SSS [LEVEL] tag: message`。
     * 多行内容首行带前缀，后续行缩进对齐，保持单条日志可读性。
     */
    private fun buildLines(level: String, tag: String, body: String): List<String> {
        val prefix = "${LocalDateTime.now().format(TIME_FORMAT)} [$level] $tag: "
        val parts = body.split('\n')
        if (parts.size == 1) return listOf(prefix + parts[0])
        val lines = ArrayList<String>(parts.size)
        parts.forEachIndexed { index, part ->
            lines.add(if (index == 0) prefix + part else "        $part")
        }
        return lines
    }

    /** 追加到内存环形缓冲：超过容量上限时丢弃最旧（队头） */
    private fun appendBuffer(lines: List<String>) {
        synchronized(bufferLock) {
            for (line in lines) {
                buffer.addLast(line)
                while (buffer.size > MAX_BUFFER) buffer.pollFirst()
            }
        }
    }

    /** 追加到日志文件：一次加锁整体写入，保证并发下多行日志不交错 */
    private fun appendFile(lines: List<String>) {
        synchronized(fileLock) {
            val file = logFile ?: return
            try {
                FileWriter(file, true).use { writer ->
                    for (line in lines) {
                        writer.write(line)
                        writer.write("\n")
                    }
                }
            } catch (_: Throwable) {
                // 文件写入失败不影响主流程（吞掉）
            }
        }
    }

    /** 安全读取日志文件内容，读取失败返回空串 */
    private fun readSafely(file: File?): String {
        if (file == null || !file.isFile) return ""
        return try {
            file.readText()
        } catch (_: Throwable) {
            ""
        }
    }
}
