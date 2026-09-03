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
     * @param timeoutMs 连接+读超时（含 osbot 生成时间）
     * @return 小爱 Agent 回答；无回答/异常返回 null
     */
    fun ask(query: String, timeoutMs: Int): String? {
        val answer = Bridge.requestAnswer(query, timeoutMs)?.takeIf { it.isNotBlank() }
        if (answer == null) LogCollector.w(TAG, "手端小爱无回答，触发降级")
        return answer
    }
}
