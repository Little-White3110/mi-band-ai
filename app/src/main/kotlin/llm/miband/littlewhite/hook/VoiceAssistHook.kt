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
     * 安装 fast 捕获：双路径汇入 FastXiaoaiEngine 聚合。
     * - n31.o0.H0(Instruction)：AIVS 入站分发（UI 之前），后台也能拿到云端回流；
     * - ic1.a.sendStreamData：RN bridge 渲染层（前台完整分片）。
     * 均只读放行，绝不改变原调用。
     */
    private fun installToastCapture() {
        installAivsInboundCapture()
        installSendStreamDataCapture()
    }

    private fun installAivsInboundCapture() {
        try {
            val o0 = classLoader.loadClass("n31.o0")
            val instr = classLoader.loadClass("com.xiaomi.ai.api.common.Instruction")
            val m = o0.getDeclaredMethod("H0", instr)
            module.hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain ->
                    try {
                        val ins = chain.getArgs()[0]
                        if (ins != null) {
                            val fullName = ins.javaClass.getMethod("getFullName").invoke(ins) as? String
                            if (fullName != null && fullName.startsWith("Template")) {
                                val dialogId = optionalGet(
                                    ins.javaClass.getMethod("getDialogId").invoke(ins),
                                )
                                val payload = ins.javaClass.getMethod("getPayload").invoke(ins)
                                val text = payload?.let {
                                    tryGetStr(it, "getMarkdownText") ?: tryGetStr(it, "getText")
                                }
                                FastXiaoaiEngine.onToast(fullName, dialogId, text)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    chain.proceed()
                })
            LogCollector.i(tag, "fast 入站捕获已安装(n31.o0.H0)")
        } catch (t: Throwable) {
            LogCollector.e(tag, "n31.o0.H0 捕获安装失败", t)
        }
    }

    private fun installSendStreamDataCapture() {
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
            LogCollector.i(tag, "fast 渲染层捕获已安装(ic1.a.sendStreamData)")
        } catch (t: Throwable) {
            LogCollector.e(tag, "ic1.a.sendStreamData 捕获安装失败", t)
        }
    }

    /** 反射取 com.xiaomi.common.Optional.get() 的字符串值 */
    private fun optionalGet(opt: Any?): String? = try {
        if (opt == null) null
        else opt.javaClass.getMethod("get").invoke(opt)?.toString()
    } catch (_: Throwable) {
        null
    }

    /** 反射取无参 String getter，失败返回 null */
    private fun tryGetStr(obj: Any, method: String): String? = try {
        obj.javaClass.getMethod(method).invoke(obj) as? String
    } catch (_: Throwable) {
        null
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
