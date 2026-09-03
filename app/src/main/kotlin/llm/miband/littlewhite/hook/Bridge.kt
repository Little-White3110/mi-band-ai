package llm.miband.littlewhite.hook

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 手环(mi.health) <-> 手机端小爱(voiceassist) 的 localhost TCP 桥协议。
 * 帧格式：先写 Int(字节数)，再写该长度的 UTF-8 字节；双向同构。
 * 握手：客户端先发 magic 帧，服务端校验后才接收 query，防本机其它进程误连。
 */
object Bridge {
    /** 监听端口（高位端口，避开常见占用） */
    const val PORT = 43997

    /** 握手口令（两端注入代码内置同一常量即可，非安全边界，仅防误连） */
    const val MAGIC = "R1ng0nLLM-XIAOAI-BRIDGE-v1"

    /** 状态查询命令：客户端发给服务端，返回运行状态行而非 osbot 回答 */
    const val STATUS_CMD = "__STATUS__"

    /** 写一帧：长度前缀 + UTF-8 内容 */
    fun writeFrame(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** 读一帧：长度前缀 + UTF-8 内容 */
    fun readFrame(input: DataInputStream): String {
        val len = input.readInt()
        require(len in 0..(4 * 1024 * 1024)) { "帧长度非法: $len" }
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 客户端一次请求：握手 -> 发 query -> 收 answer。
     * @param timeoutMs 连接与读超时
     * @return 小爱回答文本；任何异常返回 null（调用方据此降级）
     */
    fun requestAnswer(query: String, timeoutMs: Int): String? = try {
        Socket().use { socket ->
            val t = timeoutMs.coerceAtLeast(1000)
            socket.connect(InetSocketAddress("127.0.0.1", PORT), t)
            socket.soTimeout = t
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            writeFrame(out, MAGIC)
            if (readFrame(input) != "READY") return null
            writeFrame(out, query)
            readFrame(input)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 查询 voiceassist 侧桥服务端运行状态。
     * @return 状态行（形如 "STATUS|started=true|connected=true|agent=..."）；
     *         连不上返回 null（=未运行 / 进程被系统回收）。
     */
    fun requestStatus(timeoutMs: Int = 2000): String? = requestAnswer(STATUS_CMD, timeoutMs)
}
