package llm.miband.littlewhite.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import llm.miband.littlewhite.log.LogCollector

/**
 * 跨进程统计接收通道（Hook 进程 → 模块 App 进程）。
 *
 * 由于 libxposed 的 RemotePreferences 在 Hook 进程端仅支持只读，Hook 进程（com.mi.health）
 * 产生的 LLM 调用统计无法直接写入模块 App 的 SharedPreferences。本 Provider 在模块 App 进程
 * 内提供一个 exposed 的 ContentProvider，Hook 进程每次调用后通过宿主 Context 的
 * ContentResolver.call() 把统计快照推送过来，Provider 校验调用方包名后写入 StatsStore。
 */
class StatsContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { StatsStore.init(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // 仅接受目标方法
        if (method != METHOD_PUSH) return null
        // 校验调用方包名，只允许小米运动健康进程写入统计
        val caller = callingPackage
        if (caller != ALLOWED_CALLER) {
            LogCollector.w(TAG, "拒绝非目标调用方的统计写入: ${caller ?: "unknown"}")
            return null
        }
        if (arg.isNullOrBlank()) return null
        StatsStore.importJson(arg)
        LogCollector.i(TAG, "已接收并写入 Hook 进程统计推送")
        return null
    }

    // ==================== 未使用的方法（必须实现，返回空值） ====================

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        /** Provider authority，与 AndroidManifest 中定义一致 */
        const val AUTHORITY = "llm.miband.littlewhite.stats"
        const val METHOD_PUSH = "push"
        private const val ALLOWED_CALLER = "com.mi.health"
        private const val TAG = "StatsProvider"
    }
}