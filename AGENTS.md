# agents.md — mi-band-ai（环上LLM）

本文件为 AI 智能体（Agent）提供本仓库的完整上下文，帮助你快速理解代码库结构、构建方式与开发约定。

## 项目概览

**项目名称**：环上LLM（包名 `llm.miband.littlewhite`，模块 ID `llm.miband.littlewhite`）

**项目定位**：一个纯 LSPosed（Xposed）Android 模块，注入小米运动健康 App（`com.mi.health`，即连接小米手环/手表的 App）进程内：

1. 拦截小爱同学的 WebSocket 语音消息（`SpeechRecognizer/RecognizeResult`，即用户语音识别文本）；
2. 当小爱返回 `Template/Toast` 回答时，将语音识别文本转发给「回答来源」获取回答；
3. 用回答改写 Toast 消息的 `payload.text`，使**手环上显示的是新回答**而非小爱原回答。

**回答来源三选一**（设置页开关控制）：

- **外部 LLM**（默认，DeepSeek）：仅注入 `com.mi.health`，走 `LlmClient`；

- **手机端小爱 · miclaw 大模型**：额外注入 `com.miui.voiceassist`，经同 UID 官方 `ExternalAgentClient` 调 osbot 大模型（`com.xiaomi.lyrabridge-watch`），零外部 API，但依赖小米账号登录态（miclaw 网关鉴权）；

- **手机端小爱 · fast 快速模式**：反射手机端传统对话管线 `sendNlpRequest` 注入 + 流式捕获，零外部 API，**已真机验证后台可用**。

**历史背景**：该方案替代了原 Termux + mitmproxy 本地代理方案（见 `example/xiaoai.py`），后者操作繁琐、无法自启、影响全局流量。LSPosed 方案在 App 进程内完成注入，无代理、无证书、无后台服务（方案对比详见 `docs/feasibility_report.md`）。

## 目录结构

```
mi-band-ai/
├── .gitignore                        # 忽略构建产物/逆向临时目录/.trae 等
├── build.gradle.kts                  # 根构建脚本（仅声明插件版本，apply false）
├── settings.gradle.kts               # 仓库配置，rootProject.name = "mi-band-ai"，include(":app")
├── gradle.properties                 # JVM 参数 / AndroidX / Kotlin 风格
├── gradlew / gradlew.bat             # Gradle Wrapper
├── icon.png                          # 模块图标
├── gradle/
│   ├── wrapper/gradle-wrapper.properties  # Gradle 9.6.1
│   └── libs.versions.toml            # 版本目录（Version Catalog）
├── app/
│   ├── build.gradle.kts              # 模块构建脚本（AGP 9.x compileSdk 表达式 DSL）
│   ├── proguard-rules.pro            # 混淆规则（保留 Xposed 入口/Miuix/serialization）
│   └── src/main/
│       ├── AndroidManifest.xml       # Xposed 声明 + SettingsActivity + StatsContentProvider + FileProvider
│       ├── resources/META-INF/xposed/  # Xposed 三件套（java_init.list / module.prop / scope.list）
│       └── kotlin/llm/miband/littlewhite/
│           ├── MainModule.kt         # XposedModule 入口（按包名分发 com.mi.health / com.miui.voiceassist）
│           ├── SettingsActivity.kt   # 设置页 Compose Activity
│           ├── config/               # 配置键/存储/预设/跨进程统计
│           │   ├── ConfigKeys.kt
│           │   ├── ConfigStore.kt
│           │   ├── PresetManager.kt
│           │   ├── StatsContentProvider.kt
│           │   └── StatsStore.kt
│           ├── hook/                 # Hook 核心
│           │   ├── LlmClient.kt      # 外部 LLM 客户端（OpenAI/Anthropic 双路由）
│           │   ├── MiHealthHook.kt   # com.mi.health WebSocket Hook 安装（方案A+方案C）
│           │   ├── WebSocketInterceptor.kt  # WsMessage 模型 + 消息处理器 + 回答来源回退链
│           │   ├── Bridge.kt         # 跨进程 localhost TCP 桥协议（握手 + 长度帧，engine 前缀）
│           │   ├── VoiceAssistHook.kt # com.miui.voiceassist 注入入口（主进程起 server + fast 捕获 hook）
│           │   ├── XiaoaiAgentServer.kt # voiceassist 侧：miclaw(osbot) 会话 + fast 分派 + TCP server
│           │   ├── XiaoaiAgentClient.kt # com.mi.health 侧：经桥向 voiceassist 请求回答
│           │   └── FastXiaoaiEngine.kt # fast 档：sendNlpRequest 注入 + 流式聚合 + 回显过滤 + 清洗
│           ├── log/LogCollector.kt   # 日志收集（环形缓冲+文件，自动脱敏）
│           └── ui/                   # Miuix 设置页
│               ├── SettingsScreen.kt          # 主设置页（4 Tab，含「用手端小爱」开关+引擎下拉+状态卡片）
│               ├── ThemeSettingsScreen.kt     # 独立主题设置页（复刻 KernelSU）
│               ├── Theme.kt                   # Miuix AppTheme 封装
│               ├── VisualPrefs.kt             # 视觉效果偏好
│               ├── BlurExt.kt                 # 毛玻璃 backdrop 工具
│               └── component/FloatingBottomBar.kt  # 悬浮胶囊底部导航栏（移植 KSU）
├── docs/
│   ├── feasibility_report.md         # KernelSU/LSPosed 三种方案可行性分析
│   ├── lsposed-dev-guide.md          # LSPosed 模块开发完整指南
│   ├── reverse-notes.md              # com.mi.health v3.58.0 逆向分析笔记
│   └── xiaoai_phone_integration_feasibility.md  # 接入手机端小爱（com.miui.voiceassist）可行性分析
└── example/                          # 原 MITM 方案留存（非工程依赖）
    ├── xiaoai.py                     # mitmproxy 拦截脚本（DeepSeek 替换）
    └── 小爱.yaml                     # Clash Meta (FlClash) 代理配置
```

## 技术栈

| 类别       | 选型                                             | 版本                                                |
| -------- | ---------------------------------------------- | ------------------------------------------------- |
| 构建       | Gradle / AGP / Kotlin                          | 9.6.1 / 9.3.1 / 2.4.10                            |
| Hook 框架  | Modern Xposed API（`io.github.libxposed`）       | 102.0.0（compileOnly api + implementation service） |
| UI       | JetBrains Compose Multiplatform                | 1.12.0-rc01                                       |
| UI 库     | Miuix（小米 HyperOS 设计语言，`top.yukonga.miuix.kmp`） | 0.9.4-rc01（ui/preference/icons/blur 四件套）          |
| 序列化      | kotlinx-serialization-json                     | 1.7.0                                             |
| AndroidX | activity-compose                               | 1.13.0                                            |
| SDK      | minSdk 26 / targetSdk 35 / compileSdk 37       | Java 17                                           |
| HTTP     | `java.net.HttpURLConnection`                   | 刻意不引入 OkHttp，避免与宿主冲突                              |

> 注意：版本组合参考 Miuix 官方构建配置，需同代 Kotlin 避免元数据不兼容；libxposed 102 要求 compileSdk >= 37。

## 核心架构

### 多进程模型

- **Hook 进程 A** = 宿主 `com.mi.health`：执行 WebSocket 拦截 + 回答来源调用（外部 LLM 或经桥请求 voiceassist）；

- **Hook 进程 B** = 宿主 `com.miui.voiceassist`（手机端超级小爱，仅主进程）：起 TCP server，承载 miclaw/fast 两种"手机端小爱回答"引擎；

- **模块 App 进程** = 设置页（`SettingsActivity`）。

配置共享通过 libxposed **Remote Preferences**（group = `"config"`）：Hook 侧 `XposedModule.getRemotePreferences()` 只读，App 侧 `XposedService.getRemotePreferences()` 可写，变更通过 `OnSharedPreferenceChangeListener` 实时生效。

统计跨进程：Hook 进程因 Remote Prefs 只读，改用 `StatsContentProvider`（exposed ContentProvider，authority `llm.miband.littlewhite.stats`）把统计快照推到模块 App 落盘（SharedPreferences `"llm_stats"`）。

### Hook 链路（com.mi.health，多道容错，任一成功即可工作）

1. **方案 A（主文本层）**：`defpackage.oav#onMessage(WebSocket, String)`（混淆类 LiteCryptWsClient，动态定位），兜底 Hook OkHttp 非混淆内部类 `okhttp3.internal.ws.RealWebSocket#onReadMessage(String)`；
2. **方案 C（稳定兜底）**：非混淆类 `com.xiaomi.ai.api.common.APIUtils#readInstruction(String)`。

关键实现细节：

- 拦截器用 `chain.proceed(newArgs)` 以"改写字符串参数重新执行原方法"的方式注入（getArgs 返回不可变列表无法原地修改）；

- Toast 替换用 `CountDownLatch` 阻塞 WebSocket 线程等待回答（上限 15s，`MAX_WAIT_MS`），成功则改写 JSON `payload.text`；

- 主层与方案 C 命中同一条 Toast 时用 2s 时间窗去重。

### 消息处理（WebSocketInterceptor.kt）

- `WsMessage`：解析 header 的 `dialog_id/namespace/name`；

- `RecognizeResult`：仅处理 `is_final=true`，从 `results[0]` 依次取 `origin_text/getText/text` 写入 `pendingQueries[dialogId]` 缓存；

- `Template/Toast`：触发后台单线程池，按「回答来源」回退链取回答（见下）；

- 一切异常放行原消息，绝不干扰宿主。

### 回答来源回退链（WebSocketInterceptor.handleToast）

开关 `use_phone_xiaoai` 关闭 → 走 `LlmClient.ask`（外部 LLM）。开启 → 按 `xiaoai_engine`：

- `miclaw`：`XiaoaiAgentClient.ask(query, "miclaw")`，失败再回退 `fast`；

- `fast`：`XiaoaiAgentClient.ask(query, "fast")`；

- 两者皆 null → 放行原始 Toast（不崩、有响应）。

`XiaoaiAgentClient` 经 `Bridge`（localhost TCP，端口 43997，握手 + 长度帧，帧体 `engine\nquery`）把请求转到 voiceassist 主进程的 `XiaoaiAgentServer`。

### 手机端小爱接入（voiceassist 侧，XiaoaiAgentServer / FastXiaoaiEngine）

- **miclaw 档**：反射宿主 `ExternalAgentClient`，`buildAgentId("osbot.main", ...)` 命中主对话 Agent，`openSession/submit` 走 osbot 大模型（`com.xiaomi.lyrabridge-watch`，`mimo-omni`），经 `api.miclaw.xiaomi.net` 网关——**依赖小米账号登录态**（登录过期返回 `40001 Access deny`）。同 UID 调用可绕过 `CallerVerifier` 签名鉴权。

- **fast 档（已真机验证后台可用）**：

  - 注入：反射 `v51.m0.sendNlpRequest(m0.d)`，builder `setQuery(系统提示词 + "\n用户问题：" + query)`、`setIsAddQueryCard(false)`（小爱 `Nlp.Request` 无 system\_prompt 字段，故并入文本）；

  - 捕获（双路径，取先拿到内容者）：`n31.o0.H0(Instruction)`（AIVS 入站分发，**UI 之前，后台也能收到云端回流**）+ `ic1.a.sendStreamData`（RN bridge 渲染层，前台完整分片）；

  - 聚合：按注入后首个 `dialog_id` 累积 `markdown_text`；**跳过回显首片**（手机端会把注入的 query 作为首个分片回显，判据=首片包含原始问题）；遇 `<FINAL>`、或累积 ≥100 字、或 ≥24 字且以句末标点（。！？）收尾即完成；

  - 清洗：剥离 HTML 标签（`<[^>]*>`）与 markdown 符号、折叠空白、截断 100 字，保证手环小屏显示。

- **进程存活前提**：voiceassist 主进程需存活才会起 server（省电会被回收）；手环提问若恰逢其未运行，桥连接失败 → 自动降级放行原始。

### LLM 客户端（LlmClient.kt，外部 LLM 档）

- **双路由**：OpenAI 兼容 `{base_url}/v1/chat/completions`（含 DeepSeek 思考模式 `thinking` 块、`reasoning_content` 提取）；Anthropic `{base_url}/v1/messages`（system 拆顶层、thinking 块、`output_config.effort`）；

- **会话管理**：`dialogId → SessionState`（内存 ConcurrentHashMap），`single` 模式 + 60s 窗口内续接历史，超时/`independent` 模式开新会话；历史按 `context_length` 裁剪且保证 user/assistant 交替（兼容 Anthropic）；

- 统计：内存环形缓冲 + 累计量，成功后异步推送；异常一律吞掉返回 null。

### 默认配置（ConfigKeys.kt，单一来源）

- `base_url=https://api.deepseek.com`、`model=deepseek-v4-flash`、`api_type=openai`、`thinking_mode=false`；

- 系统提示词："回答要简洁，尽量控制在80字以内，不要使用markdown格式"；

- 超时 8000ms、`max_tokens=200`；

- `use_phone_xiaoai=false`（是否用手端小爱回答）、`xiaoai_engine=miclaw`（`miclaw`/`fast`）；

- API Key 使用 `ApiKeyCipher`（XOR 固定盐 `"R1ng0nLLM!2026*"` + Base64，防误读用、非高安全）加密存储。

## 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（开启混淆）
./gradlew assembleRelease

# 构建产物输出位置
# app/build/outputs/apk/{debug,release}/
```

## 开发约定与注意事项

1. **Hook 安全性**：所有 Hook 必须异常隔离（`ExceptionMode.PROTECTIVE` + try-catch），任何异常不得透传干扰宿主；日志自动脱敏（`sk-` 密钥与 Authorization/Bearer 头）；
2. **依赖克制**：不引入 OkHttp 等可能与宿主冲突的依赖，HTTP 使用 `java.net.HttpURLConnection`；
3. **配置文件**：`scope.list` 含 `com.mi.health` 与 `com.miui.voiceassist`；`java_init.list` 入口为 `llm.miband.littlewhite.MainModule`；`module.prop` 要求 minApi=102；
4. **Proguard**：`proguard-rules.pro` 保留 Xposed 入口、Miuix/Compose/kotlinx.serialization 生成类，并 `-adaptresourcefilecontents META-INF/xposed/java_init.list`；
5. **逆向资料**：`com.mi.health` v3.58.0 的完整逆向笔记在 `docs/reverse-notes.md`；手机端 `com.miui.voiceassist`（超级小爱）逆向与可行性在 `docs/xiaoai_phone_integration_feasibility.md`；fast 档注入点 `v51.m0.sendNlpRequest`、捕获点 `n31.o0.H0`/`ic1.a.sendStreamData` 均为**混淆类名，随小爱 OTA 可能变化**，失效时自动降级放行原始；
6. **UI 风格**：设置页使用 Miuix 设计语言，主题页移植自 KernelSU Manager（`FloatingBottomBar` 源码来自 compose-miuix-ui example → KernelSU，Apache-2.0 许可）。

## 相关文档

| 文档                                             | 用途                                                 |
| ---------------------------------------------- | -------------------------------------------------- |
| `docs/feasibility_report.md`                   | 三方案对比（KernelSU / LSPosed / 组合），选定"纯 LSPosed"的原因    |
| `docs/lsposed-dev-guide.md`                    | Modern Xposed API 102 开发指南（Hook 模型/拦截器链/作用域/数据共享）  |
| `docs/reverse-notes.md`                        | com.mi.health v3.58.0 逆向分析笔记（Hook 实现依据）            |
| `docs/xiaoai_phone_integration_feasibility.md` | 接入手机端小爱（com.miui.voiceassist）可行性分析（miclaw/fast 依据） |
| `example/xiaoai.py`                            | 已被替代的 mitmproxy 方案，仅作参考                            |

## 当前状态

- 外部 LLM 档：功能全部实现，`assembleRelease` 通过，端到端真机验证通过；

- 手机端小爱接入档（新增）：`com.mi.health ↔ com.miui.voiceassist` 跨进程桥 + miclaw/fast 双引擎已实现并编译通过；

  - **fast 档已真机端到端验证通过**：手环提问 → 后台注入手机端小爱快速模式 → 流式捕获聚合 → 回投手环显示真回答（不依赖手机端前台）；

  - miclaw 档链路正确但受小米账号登录态限制（当前测试机登录过期返回 `40001`，重登后可用）；

- 已知约束：fast 依赖混淆类名（OTA 脆弱，失效自动回退）；voiceassist 主进程需存活（省电回收后需唤醒）；同一 query 偶发被双 hook 重复注入（单槽 waiter，句末完成已缓解）。

