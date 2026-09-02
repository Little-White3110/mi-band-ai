package llm.miband.littlewhite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector
import llm.miband.littlewhite.ui.AppTheme
import llm.miband.littlewhite.ui.SettingsScreen

/**
 * 环上LLM 设置页入口。
 *
 * 通过 [XposedServiceHelper.registerListener] 监听框架 Service 的绑定状态，
 * 绑定成功后用返回的 [XposedService] 创建可写 [ConfigStore] 并传给设置页。
 * 若框架未安装 / 模块未启用导致永不绑定，界面显示"未检测到 LSPosed/Service"。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化日志收集器，供"导出日志"使用
        LogCollector.init(applicationContext)

        setContent {
            AppTheme {
                // 响应式获取可写 ConfigStore；Service 断开 / 未绑定时为 null
                val config = rememberServiceConfigStore()
                SettingsScreen(config = config)
            }
        }
    }
}

/**
 * 注册 Xposed Service 监听，把绑定得到的 [ConfigStore] 存进 Compose 状态。
 * 注意：实际回调名为 [XposedServiceHelper.OnServiceListener.onServiceDied]
 * （并非 onServiceDisconnected），表示框架 Service 断开。
 */
@Composable
private fun rememberServiceConfigStore(): ConfigStore? {
    // 保存当前可用的配置存储；未绑定到 Service 时为 null
    var configStore by remember { mutableStateOf<ConfigStore?>(null) }

    DisposableEffect(Unit) {
        val listener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                // 框架 Service 绑定成功，拿到可写 ConfigStore
                configStore = ConfigStore.fromService(service)
                LogCollector.i("Settings", "XposedService 已绑定，配置可写")
            }

            override fun onServiceDied(service: XposedService) {
                // 框架 Service 断开，清空配置（界面回到"未检测到 Service"状态）
                configStore = null
                LogCollector.i("Settings", "XposedService 断开")
            }
        }
        XposedServiceHelper.registerListener(listener)
        onDispose { }
    }

    return configStore
}