package llm.miband.littlewhite

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.hook.LlmClient
import llm.miband.littlewhite.hook.MiHealthHook
import llm.miband.littlewhite.hook.VoiceAssistHook
import llm.miband.littlewhite.log.LogCollector

/**
 * 环上LLM —— Xposed 模块入口类
 *
 * 注意：Modern Xposed API 102 与旧版（101 及以前）不同，
 * 入口类【不再】在构造函数中接收 XposedModuleBase，而是继承无参构造的 XposedModule，
 * 框架实例化后会自动调用 attachFramework() 注入底层接口。
 * 因此本类必须在清单/资源中保持无参可实例化（混淆规则中已保留）。
 *
 * 职责：
 * - [onModuleLoaded]：早于一切包回调触发，幂等初始化 ConfigStore 与 LlmClient；
 * - [onPackageLoaded]：包名匹配 com.mi.health 时装配 [MiHealthHook]，
 *   由它在宿主进程内安装 WebSocket 消息拦截层（方案 A 文本层 + 方案 C 稳定兜底）。
 */
class MainModule : XposedModule() {

    /** 只读配置实例：延迟到首个生命周期回调中初始化（线程安全、幂等） */
    @Volatile
    private var config: ConfigStore? = null

    /** 保护 [initialized] 的锁，避免两个回调并发重复初始化 */
    private val initLock = Any()
    private var initialized = false

    /**
     * 包加载回调：按宿主包名分发安装对应 Hook（API 29+ 触发）。
     * - com.mi.health        -> MiHealthHook（手环 AIVS 拦截 + 回答替换，既有）
     * - com.miui.voiceassist -> VoiceAssistHook（作为手机端小爱回答引擎，本次新增）
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_MI_HEALTH && param.packageName != TARGET_VOICE_ASSIST) return
        log(Log.INFO, TAG, "目标包已加载: ${param.packageName}")

        // 确保配置/LlmClient 就绪（onModuleLoaded 若未先触发则在此补齐）
        ensureInitialized()

        // 尽量拿宿主 Context 落盘日志；拿不到时 LogCollector 退回内存+logcat，不影响主流程
        hostContext()?.let { LogCollector.init(it) }
        // 延迟补偿宿主 Context 注入
        LlmClient.setHostContext(hostContext())

        val cfg = config
        if (cfg == null) {
            log(Log.ERROR, TAG, "配置初始化失败，无法安装 Hook")
            return
        }
        try {
            val classLoader = param.getDefaultClassLoader()
            when (param.packageName) {
                TARGET_MI_HEALTH -> MiHealthHook(this, cfg, classLoader).install()
                TARGET_VOICE_ASSIST -> VoiceAssistHook(this, cfg, classLoader).install()
            }
            log(Log.INFO, TAG, "${param.packageName} Hook 安装流程已触发")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "${param.packageName} Hook 安装异常", t)
        }
    }

    /**
     * 模块加载回调：模块被注入目标进程后调用一次，直接执行注入前的初始化。
     */
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "模块已加载, processName=${param.processName}, isSystemServer=${param.isSystemServer}")
        ensureInitialized()
    }

    /**
     * 幂等初始化：ConfigStore(fromModule) + LlmClient.init。
     * 仅执行一次；[onModuleLoaded] 与 [onPackageLoaded] 谁先到谁完成它。
     */
    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            initialized = true
            try {
                val cfg = ConfigStore.fromModule(this)
                config = cfg
                LlmClient.init(cfg, hostContext())
                log(Log.INFO, TAG, "配置与 LlmClient 初始化完成")
            } catch (t: Throwable) {
                // 初始化失败则放行后续逻辑（config 维持 null，由调用方兜底跳过）
                log(Log.ERROR, TAG, "配置/LlmClient 初始化异常", t)
            }
        }
    }

    /**
     * 尝试通过 ActivityThread 反射获取宿主进程的 application Context。
     * 用于 [LogCollector.init] 让日志落盘；失败返回 null（不影响主流程）。
     */
    private fun hostContext(): Context? = try {
        val thread = Class.forName("android.app.ActivityThread")
        thread.getDeclaredMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val TAG = "环上LLM"
        /** 小米运动健康（手环 AIVS 宿主） */
        const val TARGET_MI_HEALTH = "com.mi.health"
        /** 超级小爱（手机端小爱回答引擎宿主） */
        const val TARGET_VOICE_ASSIST = "com.miui.voiceassist"
    }
}