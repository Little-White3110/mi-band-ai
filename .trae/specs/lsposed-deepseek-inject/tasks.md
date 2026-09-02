# 任务分解

- [x] Task 1: 逆向分析小米运动健康 App，确认 Hook 目标
  - [x] SubTask 1.1: 确认实际安装的 App 包名（`com.mi.health` 或 `com.xiaomi.huami.health`），更新 `scope.list` 和 Hook 过滤条件
  - [x] SubTask 1.2: 反编译 APK，定位 WebSocket 客户端实现（搜索 `okhttp3.internal.ws`、`WebSocket`、`speech.ai.xiaomi.com`），确认类名与方法签名
  - [x] SubTask 1.3: 确认 OkHttp 版本，确定 `RealWebSocket` 内部类路径
  - [x] SubTask 1.4: 对比抓包数据，确认 WebSocket 消息 JSON 结构与 spec.md 中定义一致
  - [x] SubTask 1.5: 将逆向结论记录到 `docs/reverse-notes.md`，作为 Task 3 的实现依据

- [x] Task 2: 搭建 LSPosed 模块工程骨架
  - [x] SubTask 2.1: 创建 Gradle 工程（`settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml`），配置 AGP + Compose + Miuix + libxposed 依赖，包名 `llm.miband.littlewhite`
  - [x] SubTask 2.2: 编写 `app/build.gradle.kts`，配置 `compileOnly io.github.libxposed:api:102.0.0`、`implementation io.github.libxposed:service:102.0.0`、Miuix 系列依赖
  - [x] SubTask 2.3: 编写 `AndroidManifest.xml`（application、Xposed 入口 meta-data、SettingsActivity 声明）
  - [x] SubTask 2.4: 编写 `META-INF/xposed/module.prop`（模块名"环上LLM"）、`java_init.list`、`scope.list`
  - [x] SubTask 2.5: 编写 `proguard-rules.pro` 保留 Xposed 入口类
  - [x] 验证: `./gradlew :app:assembleDebug` 构建通过，APK 内包含 META-INF/xposed 配置文件

- [x] Task 3: 实现配置管理模块（config 包）
  - [x] SubTask 3.1: 实现 `ConfigKeys.kt`，定义所有配置键常量（enabled / api_type / base_url / api_key / model / temperature / top_p / top_k / thinking_mode / system_prompt / context_mode / context_window_ms / context_length / timeout_ms / max_tokens）
  - [x] SubTask 3.2: 实现 `ConfigStore.kt`，封装 XposedModule 侧只读读取 + XposedService 侧写入
  - [x] SubTask 3.3: API Key 加密存储（使用 Android 加密库或简单 XOR 编码防止明文暴露）
  - [x] SubTask 3.4: 支持 `OnSharedPreferenceChangeListener` 变更监听（Hook 进程热更新配置）
  - [x] 验证: 模块 App 写入配置后，Hook 进程能读取到新值；API Key 在日志中脱敏

- [x] Task 4: 实现 LLM 客户端（多 API 兼容，hook 包）
  - [x] SubTask 4.1: 实现 `LlmClient.kt`，使用 `java.net.HttpURLConnection`，支持两套 API 路由：
    - OpenAI 兼容：`{base_url}/v1/chat/completions`，请求体含 model / messages / temperature / top_p / max_tokens
    - Anthropic：`{base_url}/v1/messages`，请求体含 model / messages / temperature / top_p / max_tokens / thinking
  - [x] SubTask 4.2: 请求体构建：system_prompt + 用户识别文本；`context_mode == "single"` 时追加历史消息（最多 `context_length` 条）
  - [x] SubTask 4.3: 响应解析：
    - OpenAI 模式：`choices[0].message.content`；思考模式时额外提取 `reasoning_content`
    - Anthropic 模式：`content[0].text`；思考模式时额外提取 `thinking`
  - [x] SubTask 4.4: 超时控制（默认 8000ms），异常与非 200 统一捕获返回 null
  - [x] SubTask 4.5: 会话管理：维护 `dialog_id → messageHistory[]` 映射，在 `context_window_ms` 窗口内连续追加；超时或 `context_mode == "independent"` 时仅携带当前查询
  - [~] 验证: 用有效 API Key 测试 OpenAI 和 Anthropic 两种模式；验证会话上下文传递正确（需真机+有效 Key，构建已验证）

- [x] Task 5: 实现 WebSocket 消息拦截层（hook 包）
  - [x] SubTask 5.1: 定义 `WebSocketInterceptor` 接口与 `WebSocketMessageProcessor`（解析 JSON、识别 RecognizeResult/Toast、pendingQueries 缓存、异步调用 LlmClient）
  - [x] SubTask 5.2: 实现基于 Task 1 逆向结论的拦截器（如 OkHttp `RealWebSocket` 的 `onMessage` 回调 Hook）
  - [x] SubTask 5.3: 实现 `MiHealthHook.kt`，在包加载时检测目标包名并安装拦截器
  - [x] SubTask 5.4: 实现 `MainModule.kt`（XposedModule 入口），注册 Hook、初始化配置读取
  - [~] 验证: 开启模块后在目标 App 进程内注入，日志显示拦截器已注册、RecognizeResult 被记录、Toast 被替换（需真机 LSPosed 环境验证）

- [x] Task 6: 实现日志搜集模块（log 包）
  - [x] SubTask 6.1: 实现 `LogCollector.kt`，使用内存环形缓冲区 + 日志文件双向记录
  - [x] SubTask 6.2: 日志内容对 API Key 做脱敏遮蔽（正则替换 `sk-` 开头的 token）
  - [x] SubTask 6.3: 搜集范围：模块加载、拦截器注册、RecognizeResult 记录、LLM 调用、错误异常
  - [~] 验证: 模块运行后日志文件生成，API Key 被遮蔽，通过导出功能可正常查看（需真机环境验证）

- [x] Task 7: 实现 Miuix 设置界面（ui 包）
  - [x] SubTask 7.1: 实现 `Theme.kt`，使用 `ThemeController(ColorSchemeMode.System)` + `MiuixTheme` 跟随系统深浅色
  - [x] SubTask 7.2: 实现 `SettingsScreen.kt`，Miuix 组件构建设置页，分组 Card：
    - 基本设置：启用开关、API 类型下拉选择、Base URL 输入、API Key 密码输入、模型输入
    - 生成参数：温度滑块、top_p 滑块、top_k 输入、思考模式开关、System Prompt 多行输入、超时输入、Max Token 输入
    - 会话设置：会话模式下拉选择、会话窗口时长输入、上下文长度输入
    - 日志：导出日志按钮
    - 关于：测试连接按钮、版本信息
  - [x] SubTask 7.3: 实现 `SettingsActivity.kt`，Compose Activity 入口，配置写入 XposedService Remote Preferences
  - [x] SubTask 7.4: "测试连接"按钮调用 `LlmClient` 校验 API Key 有效性并回显结果
  - [~] 验证: 安装模块 App 打开设置页，UI 风格与 miuix-main 示例一致；所有配置项可读写（需真机打开验证）

- [ ] Task 8: 端到端验证与发布准备
  - [ ] SubTask 8.1: 在 LSPosed 中启用模块，重启目标 App，实际触发手环语音问答验证注入生效（需真机 + LSPosed，设备当前脱机）
  - [ ] SubTask 8.2: 验证关闭开关后消息透传、无副作用（需真机）
  - [ ] SubTask 8.3: 验证超时场景（LLM 无响应时放行原始回答）（需真机）
  - [ ] SubTask 8.4: 测试日志导出功能正常（需真机）
  - [x] SubTask 8.5: 配置 ProGuard/R8 混淆规则，确保 release 构建可用
  - [~] 验证: `./gradlew :app:assembleRelease` 构建通过（已验证通过，LSPosed 正式环境端到端需真机确认）

# 任务依赖关系

- [Task 1] 无依赖（逆向分析先行）
- [Task 2] 依赖 [Task 1]（scope.list 需确认包名）
- [Task 3] 依赖 [Task 2]（工程骨架）
- [Task 4] 依赖 [Task 2]、[Task 3]
- [Task 5] 依赖 [Task 1]、[Task 3]、[Task 4]
- [Task 6] 依赖 [Task 2]（日志模块独立，可与其他任务并行）
- [Task 7] 依赖 [Task 2]、[Task 3]、[Task 4]（测试连接复用 LlmClient）
- [Task 8] 依赖 [Task 5]、[Task 6]、[Task 7]

# 可并行任务

- [Task 3]、[Task 4]、[Task 6] 可并行（均依赖 Task 2）
- [Task 5] 与 [Task 7] 在 Task 3/4 完成后可并行