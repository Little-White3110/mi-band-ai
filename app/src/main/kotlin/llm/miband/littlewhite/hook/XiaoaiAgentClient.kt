package llm.miband.littlewhite.hook

import llm.miband.littlewhite.log.LogCollector

/**
 * 手环侧（com.mi.health）向手机端小爱发起一次提问的封装。
 * 走 Bridge 的 localhost TCP；失败返回 null，由调用方降级（放行原始回答或回退 LlmClient）。
 */
object XiaoaiAgentClient {

    private const val TAG = "XiaoaiAgentClient"

    /**
     * @param query     用户识别文本
     * @param engine    回答引擎："miclaw"(osbot大模型) / "fast"(手机端传统云端)
     * @param timeoutMs 连接+读超时（含引擎生成时间）
     * @return 小爱回答；无回答/异常返回 null
     */
    fun ask(query: String, engine: String, timeoutMs: Int): String? {
        val answer = Bridge.requestAnswerWithEngine(engine, query, timeoutMs)?.takeIf { it.isNotBlank() }
        if (answer == null) LogCollector.w(TAG, "手端小爱[$engine]无回答")
        return answer
    }
}
