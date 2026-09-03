@file:Suppress("unused")

package llm.miband.littlewhite.hook

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector

/**
 * 手机端小爱（com.miui.voiceassist）注入入口。
 * voiceassist 有 :core/:provider/:inputMethodService 等子进程，
 * ExternalAgentService 与 osbot 运行在主进程，故仅主进程启动桥服务端。
 * 主进程判定读 /proc/self/cmdline（PackageLoadedParam 不提供 processName）。
 */
class VoiceAssistHook(
    private val module: XposedModule,
    private val config: ConfigStore,
    private val classLoader: ClassLoader,
) {
    private val tag = "VoiceAssistHook"

    fun install() {
        // 子进程（进程名含 ':'）跳过，只在主进程起 server
        val proc = currentProcess()
        if (proc != null && proc.contains(":")) {
            LogCollector.i(tag, "子进程 $proc，跳过桥服务端启动")
            return
        }
        // 主进程：onPackageLoaded 触发时 Application 可能尚未就绪，
        // 后台轮询等待 currentApplication() 可用（最多 10s）再起 server。
        Thread({
            var ctx: Context? = null
            repeat(20) {
                ctx = hostContext()
                if (ctx != null) return@repeat
                try { Thread.sleep(500) } catch (_: Throwable) {}
            }
            val c = ctx
            if (c == null) {
                LogCollector.w(tag, "等待超时仍拿不到宿主 Context，跳过（不影响手环原始回答）")
                return@Thread
            }
            XiaoaiAgentServer.start(classLoader, c)
            FastXiaoaiEngine.init(classLoader, config)
            installToastCapture()
            LogCollector.i(tag, "voiceassist 注入完成：osbot 桥服务端 + fast 捕获已就绪")
        }, "VoiceAssistHookInit").apply { isDaemon = true }.start()
    }

    /**
     * hook 手机端 RN bridge ic1.a.sendStreamData(type, data)，捕获 Template.ToastStream 流式分片，
     * 交给 FastXiaoaiEngine 按 dialog_id 聚合。只读放行，绝不改变原调用。
     */
    private fun installToastCapture() {
        try {
            val bridge = classLoader.loadClass("ic1.a")
            val m = bridge.getDeclaredMethod(
                "sendStreamData", String::class.java, String::class.java,
            )
            module.hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain ->
                    try {
                        val args = chain.getArgs()
                        if (args.size >= 2 && args[0] == "instruction") {
                            FastXiaoaiEngine.onStreamData(args[1] as? String)
                        }
                    } catch (_: Throwable) {
                    }
                    chain.proceed()
                })
            LogCollector.i(tag, "fast 流式捕获 hook 已安装(ic1.a.sendStreamData)")
        } catch (t: Throwable) {
            LogCollector.e(tag, "fast 流式捕获 hook 安装失败(fast 档将降级)", t)
        }
    }

    /** 取当前进程名：优先 Application.getProcessName()，回退 /proc/self/cmdline；失败返回 null */
    private fun currentProcess(): String? {
        try {
            val m = android.app.Application::class.java.getDeclaredMethod("getProcessName")
            (m.invoke(null) as? String)?.let { return it }
        } catch (_: Throwable) {
        }
        return try {
            val raw = java.io.File("/proc/self/cmdline").readText()
            val nul = '\u0000'
            raw.substringBefore(nul).takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    /** 反射 ActivityThread.currentApplication 拿宿主 Context */
    private fun hostContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}
