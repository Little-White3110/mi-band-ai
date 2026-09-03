@file:Suppress("unused")

package llm.miband.littlewhite.hook

import android.content.Context
import llm.miband.littlewhite.log.LogCollector
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * voiceassist 主进程内的回答引擎服务端：
 * 1) 启动时反射宿主 ExternalAgentClient 并 connect（同 UID，可过 CallerVerifier）；
 * 2) 起 localhost TCP server，收到 query 后 openSession+submit，
 *    等 onComplete 拿小爱 Agent 完整回答回传。
 * 全程异常隔离，失败回传空串由客户端降级。
 *
 * 计费标识复用小米为穿戴设备预设的官方组合（见宿主 LyraAgentService）：
 * agentId="com.xiaomi.lyrabridge-watch"（手表语音助手）+ bizId="miwear" + featureId="miclaw"，
 * 否则 osbot 调 LLM 网关会返回 400 code=20003 "Invalid bizId or featureId"。
 */
object XiaoaiAgentServer {

    private const val TAG = "XiaoaiAgentServer"
    private const val CLIENT_CLASS = "com.aios.apptoolsdk.ExternalAgentClient"
    private const val APPMETA_CLASS = "com.aios.apptoolsdk.AppMeta"
    /** 官方穿戴 agent：targetPackage + tag => agentId="com.xiaomi.lyrabridge-watch" */
    private const val AGENT_TARGET_PACKAGE = "com.xiaomi.lyrabridge"
    private const val AGENT_TAG = "watch"
    /** 小米为穿戴接入 osbot 预设的合法计费标识 */
    private const val BIZ_ID = "miwear"
    private const val FEATURE_ID = "miclaw"
    private const val APP_NAME = "环上LLM"
    /** 单次 osbot 生成等待上限（Agent 可能多轮工具调用，给足时间） */
    private const val OSBOT_WAIT_MS = 25_000L

    @Volatile
    private var started = false

    private var clientClass: Class<*>? = null
    private var appMetaClass: Class<*>? = null
    private var client: Any? = null

    fun start(classLoader: ClassLoader, context: Context) {
        if (started) return
        started = true
        try {
            clientClass = classLoader.loadClass(CLIENT_CLASS)
            appMetaClass = classLoader.loadClass(APPMETA_CLASS)
            connectClient(classLoader, context)
            Thread({ acceptLoop() }, "XiaoaiBridgeServer").apply { isDaemon = true }.start()
            LogCollector.i(TAG, "TCP 桥服务端已启动，osbot 客户端连接=${client != null}")
        } catch (t: Throwable) {
            LogCollector.e(TAG, "启动失败（不影响手环原始回答）", t)
        }
    }

    /** 反射创建并连接 ExternalAgentClient，等待 onConnected */
    private fun connectClient(classLoader: ClassLoader, context: Context) {
        val cc = clientClass ?: return
        val instance = cc.getMethod("create", Context::class.java).invoke(null, context)
        val listenerIface = cc.declaredClasses.first { it.simpleName == "ConnectionListener" }
        val connected = CountDownLatch(1)
        val listener = Proxy.newProxyInstance(
            classLoader, arrayOf(listenerIface),
            InvocationHandler { _, method, _ ->
                if (method.name == "onConnected") connected.countDown()
                null
            },
        )
        cc.getMethod("setConnectionListener", listenerIface).invoke(instance, listener)
        cc.getMethod("connect").invoke(instance)
        val ok = connected.await(5, TimeUnit.SECONDS)
        client = if (ok) instance else null
        if (!ok) LogCollector.w(TAG, "ExternalAgentClient 连接超时，osbot 通道不可用")
    }

    private fun acceptLoop() {
        try {
            ServerSocket(Bridge.PORT, 10, InetAddress.getByName("127.0.0.1")).use { server ->
                while (true) {
                    val socket = try { server.accept() } catch (_: Throwable) { continue }
                    Thread({ handle(socket) }, "XiaoaiBridgeConn").apply { isDaemon = true }.start()
                }
            }
        } catch (t: Throwable) {
            LogCollector.e(TAG, "accept 循环异常退出", t)
        }
    }

    /** 单连接：握手 -> 读 query -> 问 osbot（或回状态）-> 写 answer */
    private fun handle(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = (OSBOT_WAIT_MS + 5_000).toInt()
                val input = DataInputStream(socket.getInputStream())
                val out = DataOutputStream(socket.getOutputStream())
                if (Bridge.readFrame(input) != Bridge.MAGIC) return
                Bridge.writeFrame(out, "READY")
                val raw = Bridge.readFrame(input)
                // 状态查询：回服务端运行状态；否则按 engine 前缀分派 miclaw/fast
                val answer = if (raw == Bridge.STATUS_CMD) {
                    "STATUS|started=true|connected=${client != null}|agent=$AGENT_TARGET_PACKAGE-$AGENT_TAG|fastReady=${FastXiaoaiEngine.INJECTION_READY}"
                } else {
                    val (engine, q) = splitEngine(raw)
                    when (engine) {
                        "fast" -> FastXiaoaiEngine.ask(q) ?: ""
                        else -> askOsbot(q) ?: ""
                    }
                }
                Bridge.writeFrame(out, answer)
            }
        } catch (t: Throwable) {
            LogCollector.w(TAG, "连接处理异常: ${t.message}")
        }
    }

    /** 拆分 "$engine\n$query" 帧；无前缀或未知引擎按 miclaw */
    private fun splitEngine(raw: String): Pair<String, String> {
        val nl = raw.indexOf('\n')
        if (nl > 0 && nl < raw.length - 1) {
            val e = raw.substring(0, nl)
            if (e == "miclaw" || e == "fast") return e to raw.substring(nl + 1)
        }
        return "miclaw" to raw
    }

    /** 反射走 ExternalAgentClient 的 openSession + submit，阻塞取 onComplete 文本 */
    private fun askOsbot(query: String): String? {
        val cc = clientClass ?: return null
        val c = client ?: return null
        val metaClass = appMetaClass ?: return null
        if (query.isBlank()) return null
        try {
            val builderClass = metaClass.declaredClasses.first { it.simpleName == "Builder" }
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("appName", String::class.java).invoke(builder, APP_NAME)
            builderClass.getMethod("targetPackage", String::class.java).invoke(builder, AGENT_TARGET_PACKAGE)
            builderClass.getMethod("tag", String::class.java).invoke(builder, AGENT_TAG)
            builderClass.getMethod("bizId", String::class.java).invoke(builder, BIZ_ID)
            builderClass.getMethod("featureId", String::class.java).invoke(builder, FEATURE_ID)
            val meta = builderClass.getMethod("build").invoke(builder)

            val sessionId = cc.getMethod(
                "openSession", metaClass, Boolean::class.javaPrimitiveType,
            ).invoke(c, meta, false) as? String
            if (sessionId.isNullOrEmpty() || sessionId.startsWith("error:")) {
                LogCollector.w(TAG, "openSession 失败: $sessionId")
                return null
            }

            val callbackIface = cc.declaredClasses.first { it.simpleName == "Callback" }
            val latch = CountDownLatch(1)
            val answerRef = AtomicReference<String?>(null)
            val cb = Proxy.newProxyInstance(
                cc.classLoader, arrayOf(callbackIface),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onComplete" -> { answerRef.set(args?.getOrNull(1) as? String); latch.countDown() }
                        "onError" -> { answerRef.set(null); latch.countDown() }
                    }
                    null
                },
            )
            cc.getMethod("submit", String::class.java, String::class.java, callbackIface)
                .invoke(c, sessionId, query, cb)
            val done = latch.await(OSBOT_WAIT_MS, TimeUnit.MILLISECONDS)
            try {
                cc.getMethod("closeSession", String::class.java).invoke(c, sessionId)
            } catch (_: Throwable) {
            }
            if (!done) LogCollector.w(TAG, "osbot 生成超时")
            return answerRef.get()?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            LogCollector.e(TAG, "askOsbot 异常", t)
            return null
        }
    }
}
