package llm.miband.littlewhite.hook

import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机端小爱「快速模式」回答引擎（voiceassist 主进程内，A 方案真注入 + 流式捕获）。
 *
 * 注入：把系统提示词拼进 query 前缀（小爱 Nlp.Request 无 system_prompt 字段，只能并入文本），
 *       反射 v51.m0.sendNlpRequest(m0.e().setQuery(prefixed).setIsAddQueryCard(false).build())。
 * 捕获：hook ic1.a.sendStreamData，按 dialog_id 聚合流式 markdown_text，遇 <FINAL> 完成；
 *       结果做 markdown 清洗 + 长度截断，保证手环小屏能显示。
 *
 * 手环提问串行，单等待槽。任一环节失败返回 null，由 mi.health 侧回退放行原始 Toast。
 */
object FastXiaoaiEngine {

    private const val TAG = "FastXiaoaiEngine"

    const val INJECTION_READY = true

    private const val WAIT_MS = 15_000L

    /** 手环小屏可显示的回答长度上限（超出截断） */
    private const val MAX_ANSWER_LEN = 100

    private var classLoader: ClassLoader? = null
    private var config: ConfigStore? = null

    private class Waiter {
        val latch = CountDownLatch(1)
        val dialog = AtomicReference<String?>(null)
        val sb = StringBuilder()
        val result = AtomicReference<String?>(null)
    }

    private val waiter = AtomicReference<Waiter?>(null)

    fun init(cl: ClassLoader, cfg: ConfigStore) {
        classLoader = cl
        config = cfg
    }

    /** 由 sendStreamData hook 调用：解析 ToastStream 分片并按 dialog_id 聚合 */
    fun onStreamData(json: String?) {
        if (json.isNullOrBlank() || !json.contains("ToastStream")) return
        val w = waiter.get() ?: return
        try {
            val root = org.json.JSONObject(json)
            val header = root.optJSONObject("header") ?: return
            val dialogId = header.optString("dialog_id", "")
            val payload = root.optJSONObject("payload") ?: return
            val md = payload.optString("markdown_text", "")
            val cur = w.dialog.get()
            if (cur == null) {
                if (dialogId.isEmpty()) return
                w.dialog.set(dialogId)
            } else if (cur != dialogId) {
                return
            }
            when {
                md == "<FINAL>" -> finish(w)
                md.isNotEmpty() -> {
                    w.sb.append(md)
                    // 累积足够长即提前完成，避免长回答拖慢与刷屏
                    if (w.sb.length >= MAX_ANSWER_LEN) finish(w)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun finish(w: Waiter) {
        if (w.result.get() != null) return
        w.result.set(cleanAndTruncate(w.sb.toString()))
        w.latch.countDown()
    }

    /** 去 HTML 标签与 markdown 符号、折叠空白、截断到手环可显示长度 */
    private fun cleanAndTruncate(raw: String): String? {
        var s = raw.replace(Regex("<[^>]*>"), " ")
        s = s.replace(Regex("<[^>]*$"), "")
        s = s.replace(Regex("[#*`>|_]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > MAX_ANSWER_LEN) {
            s = s.substring(0, MAX_ANSWER_LEN).trimEnd() + "…"
        }
        return s.takeIf { it.isNotBlank() }
    }

    fun ask(query: String): String? {
        val cl = classLoader ?: return null
        if (query.isBlank()) return null
        val w = Waiter()
        waiter.set(w)
        try {
            if (!inject(cl, query)) {
                waiter.set(null)
                return null
            }
            val ok = w.latch.await(WAIT_MS, TimeUnit.MILLISECONDS)
            val ans = if (ok) w.result.get() else null
            if (ans == null) LogCollector.w(TAG, "fast 注入后未聚合到流式回答")
            return ans
        } catch (t: Throwable) {
            LogCollector.e(TAG, "ask 异常", t)
            return null
        } finally {
            waiter.compareAndSet(w, null)
        }
    }

    /** 把系统提示词并入 query 前缀（小爱无 system_prompt 字段），再反射 sendNlpRequest 注入 */
    private fun inject(cl: ClassLoader, query: String): Boolean = try {
        val sys = config?.getSystemPrompt()?.trim().orEmpty()
        val prefixed = if (sys.isEmpty()) query else "$sys\n用户问题：$query"
        val m0 = cl.loadClass("v51.m0")
        val builderCls = cl.loadClass("v51.m0\$e")
        val dCls = cl.loadClass("v51.m0\$d")
        val builder = builderCls.getDeclaredConstructor().newInstance()
        builderCls.getMethod("setQuery", String::class.java).invoke(builder, prefixed)
        try {
            builderCls.getMethod("setIsAddQueryCard", Boolean::class.javaPrimitiveType)
                .invoke(builder, false)
        } catch (_: Throwable) {
        }
        val params = builderCls.getMethod("build").invoke(builder)
        m0.getMethod("sendNlpRequest", dCls).invoke(null, params)
        LogCollector.i(TAG, "fast 注入 sendNlpRequest 成功 query=${query.take(30)}")
        true
    } catch (t: Throwable) {
        LogCollector.e(TAG, "fast 注入失败(v51.m0 类名可能随版本变)", t)
        false
    }
}
