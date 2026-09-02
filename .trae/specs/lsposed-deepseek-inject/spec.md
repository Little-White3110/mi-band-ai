# LSPosed 环上LLM 注入模块 — 规格说明

## Why

原方案（Termux + mitmproxy）操作繁琐、无法自启、影响全局流量。本模块通过 LSPosed 在小米运动健康 App 进程内 Hook WebSocket 消息，将小爱同学的语音回复替换为 LLM（默认 DeepSeek）的回答，实现无感注入，支持多 API 兼容与可配置的会话管理。

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                   小米运动健康 App 进程                        │
│                                                             │
│  ┌──────────┐   ┌────────────────┐   ┌──────────────────┐  │
│  │ 手环/手表 │──▶│ 小爱 WebSocket  │──▶│ WebSocket 消息    │  │
│  │ 语音输入  │   │ (wss://speech   │   │ 回调 (onMessage)  │  │
│  │           │   │  .ai.xiaomi    │   │                  │  │
│  │           │   │  .com)         │   └────────┬─────────┘  │
│  └──────────┘   └────────────────┘            │             │
│                                                ▼             │
│                          ┌───────────────────────────────┐   │
│                          │      LSPosed Hook 层           │   │
│                          │  RecognizeResult: 记录识别文本  │   │
│                          │  Toast: 调用 LLM 替换回答文本    │   │
│                          │  多 API 路由(OpenAI/Anthropic) │   │
│                          └───────┬───────────────────────┘   │
│                                  │                          │
│                          ┌───────▼───────────────────────┐   │
│                          │   LLM API (直连 HTTPS)          │   │
│                          │   默认 api.deepseek.com         │   │
│                          └───────┬───────────────────────┘   │
│                                  │                          │
│                          ┌───────▼───────────────────────┐   │
│                          │   修改后的 Toast 消息继续传递    │   │
│                          │   → 手环显示 LLM 回答          │   │
│                          └───────────────────────────────┘   │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │    配置通道 (Remote Preferences)                      │   │
│  │    LSPosed 数据库 ⟷ 模块 App 设置页面                  │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
                        ▲
                        │ LSPosed 注入
                        │ (Zygisk)
                        │
┌─────────────────────────────────────────────────────────────┐
│                  LSPosed 模块 App (本模块)                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  SettingsActivity (Compose + Miuix)                   │   │
│  │  ┌───────────────┐  ┌────────────────────────────┐   │   │
│  │  │ 启用模块开关    │  │ API Key 输入 (密码保护)    │   │   │
│  │  │ 模型选择       │  │ 自定义 System Prompt       │   │   │
│  │  │ 超时设置       │  │ 测试连接按钮               │   │   │
│  │  └───────────────┘  └────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  libxposed/service → XposedService                   │   │
│  │  → getRemotePreferences("config") → Remote Prefs     │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## What Changes

### 新增：LSPosed 模块项目

**包名**: `llm.miband.littlewhite` | **模块名称**: "环上LLM"

| 组件           | 文件                        | 职责                                                    |
| ------------ | ------------------------- | ----------------------------------------------------- |
| 模块入口         | `MainModule.kt`           | XposedModule 入口，注册 Hook                               |
| 包加载处理        | `MiHealthHook.kt`         | 识别 `com.mi.health`，安装 WebSocket 拦截器                   |
| WebSocket 拦截 | `WebSocketInterceptor.kt` | Hook OkHttp WebSocket 消息回调，拦截 RecognizeResult 和 Toast |
| LLM 客户端       | `LlmClient.kt`            | 多 API 兼容调用（OpenAI / Anthropic），默认 DeepSeek 地址        |
| 配置存储         | `ConfigStore.kt`          | 封装 Remote Preferences 读写                              |
| 日志搜集         | `LogCollector.kt`         | 搜集模块运行日志，支持导出                                    |
| 设置页面         | `SettingsActivity.kt`     | Compose Activity，Miuix 主题的设置界面                        |
| 设置主题         | `Theme.kt`                | MiuixTheme 配置                                         |
| 设置界面         | `SettingsScreen.kt`       | Miuix 组件构建的设置页                                        |

### 新增：配置项

| 配置键             | 类型      | 默认值                | 说明                                       |
| --------------- | ------- | ------------------ | ---------------------------------------- |
| `enabled`       | Boolean | `true`             | 模块启用开关                                   |
| `api_type`      | String  | `"openai"`         | API 类型 (`openai` / `anthropic`)          |
| `base_url`      | String  | `"https://api.deepseek.com"` | 自定义请求地址，自动补全 `/v1/chat/completions` |
| `api_key`       | String  | `""`               | API Key（本地加密存储，不暴露在代码/日志中）              |
| `model`         | String  | `"deepseek-chat"`  | 模型选择（deepseek-chat / deepseek-reasoner 或自定义） |
| `temperature`   | Float   | `0.7`              | 采样温度                                    |
| `top_p`         | Float   | `1.0`              | 核采样 top_p                               |
| `top_k`         | Int     | `-1`               | top_k（-1 表示不启用，按 API 支持情况透传）           |
| `thinking_mode` | Boolean | `false`            | 思考模式开关（OpenAI: `deepseek-reasoner` 模型 / Anthropic: `thinking`） |
| `system_prompt` | String  | 见下方               | 自定义系统指令                                  |
| `context_mode`  | String  | `"single"`         | 会话模式 (`single` 连续调用携带上下文 / `independent` 独立会话无上下文) |
| `context_window_ms` | Int | `60000`         | 会话窗口时长 (ms)：窗口内连续 Toast 视为同一会话携带上下文，超时开启新会话 |
| `context_length` | Int    | `10`               | 单次请求携带的最大上下文消息条数（历史消息裁剪）               |
| `timeout_ms`    | Int     | `8000`             | LLM 超时时间 (ms)                           |
| `max_tokens`    | Int     | `200`              | 最大生成 Token 数                             |

**默认 System Prompt:**

```
你是一个语音助手，通过小米手环回答用户问题。
由于用户是语音输入，可能会有些许错别字。
回答要简洁，尽量控制在80字以内，不要使用markdown格式。
```

### 新增：文件变更

```
app/
├── build.gradle.kts                    # AGP + Compose + Miuix + libxposed
├── proguard-rules.pro                  # Xposed 入口保留规则
├── src/main/
│   ├── AndroidManifest.xml
│   ├── kotlin/llm/miband/littlewhite/
│   │   ├── MainModule.kt
│   │   ├── hook/
│   │   │   ├── MiHealthHook.kt
│   │   │   ├── WebSocketInterceptor.kt
│   │   │   └── LlmClient.kt
│   │   ├── config/
│   │   │   ├── ConfigStore.kt
│   │   │   └── ConfigKeys.kt
│   │   ├── log/
│   │   │   └── LogCollector.kt
│   │   └── ui/
│   │       ├── SettingsActivity.kt
│   │       ├── Theme.kt
│   │       └── SettingsScreen.kt
│   └── resources/META-INF/xposed/
│       ├── module.prop
│       ├── java_init.list
│       └── scope.list
```

## Impact

### 依赖影响

| 依赖                                                 | 类型             | 版本           | 说明                |
| -------------------------------------------------- | -------------- | ------------ | ----------------- |
| `io.github.libxposed:api`                          | compileOnly    | `102.0.0`    | Modern Xposed API |
| `io.github.libxposed:service`                      | implementation | `102.0.0`    | 框架通信（配置写入）        |
| `top.yukonga.miuix.kmp:miuix-ui-android`           | implementation | `0.9.4-rc01` | Miuix 核心组件        |
| `top.yukonga.miuix.kmp:miuix-preference-android`   | implementation | `0.9.4-rc01` | Miuix 偏好组件        |
| `top.yukonga.miuix.kmp:miuix-icons-android`        | implementation | `0.9.4-rc01` | Miuix 图标          |
| `androidx.activity:activity-compose`               | implementation | `1.9.0+`     | Compose Activity  |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | implementation | —            | JSON 解析           |

### 受影响的系统/能力

* **LSPosed 框架**: 模块需要 LSPosed v1.9.0+（支持 API 102）

* **小米运动健康 App**: 需要 `com.mi.health` 包名，作用域仅此 App

* **WebSocket 协议**: 依赖 `speech.ai.xiaomi.com` 的 WebSocket 消息结构（由逆向分析确认）

## 关键技术细节

### WebSocket 消息结构（来自逆向已知）

**RecognizeResult 消息**（服务端→客户端，记录识别文本）:

```json
{
  "header": {
    "namespace": "SpeechRecognizer",
    "name": "RecognizeResult",
    "dialog_id": "xxx"
  },
  "payload": {
    "is_final": true,
    "results": [{"origin_text": "用户说的话"}]
  }
}
```

**Toast 消息**（服务端→客户端，显示回答）:

```json
{
  "header": {
    "namespace": "Template",
    "name": "Toast",
    "dialog_id": "xxx"
  },
  "payload": {
    "text": "小爱同学的回答"
  }
}
```

### Hook 策略

**主要目标**: 拦截 OkHttp WebSocket 的 `onMessage` 回调。

由于无法在 spec 阶段确定具体的类名和方法名（需逆向分析 APK），Hook 模块设计为**可插拔拦截器**模式：

```
WebSocketInterceptor (接口)
  ├── OkHttpWebSocketInterceptor (尝试 OkHttp 的 WebSocket 回调)
  ├── (备用) 其他 WebSocket 实现的拦截器
```

每个拦截器实现 `onMessage(flowData)` 回调，由 `WebSocketMessageProcessor` 统一处理：

1. 解析 JSON 消息
2. 匹配 `SpeechRecognizer/RecognizeResult` → 存入 `pendingQueries[dialog_id]`
3. 匹配 `Template/Toast` → 从 `pendingQueries` 获取查询文本，调用 LLM，修改消息
4. 其他消息 → 透传

### 异步处理

* `Toast` 消息到达时，在后台线程（`Executors.newSingleThreadExecutor`）调用 `LlmClient`（根据 `api_type` 路由到对应 API 实现）

* 使用 `java.net.HttpURLConnection` 同步请求，由线程池管理异步

* 超时兜底：超过 `timeout_ms`（默认 8s）仍无响应，**放行原始 Toast 消息**

* 会话管理：`context_mode == "single"` 时，`LlmClient` 维护 `dialog_id → messageHistory[]` 映射，在 `context_window_ms` 窗口内连续追加消息；超时或 `context_mode == "independent"` 时仅携带当前查询

* 成功：修改消息 JSON 中的 `payload.text` 字段，放行修改后的消息

### 配置共享

使用 Modern Xposed API 的 Remote Preferences：

```
写入端（模块 App 设置页面）:
  XposedService.getRemotePreferences("config")
  → 返回 SharedPreferences 实例，直接调用 edit().putXxx().apply()

读取端（Hook 进程）:
  XposedModule.getRemotePreferences("config")
  → 返回只读 SharedPreferences 实例
  → 支持 OnSharedPreferenceChangeListener 监听变更
```

## 要求

### 要求：模块基础结构

系统 SHALL 提供一个完整的 LSPosed 模块，使用 Modern Xposed API 102。

#### 场景：模块加载

* **WHEN** LSPosed 框架加载模块

* **THEN** 模块入口的 `onModuleLoaded()` 被调用，记录日志

#### 场景：目标进程 Hook

* **WHEN** 小米运动健康 App 进程启动

* **THEN** `onPackageLoaded()` 被调用，检测包名为 `com.mi.health`，注册 WebSocket 拦截器

### 要求：WebSocket 消息拦截

系统 SHALL 拦截小米运动健康与 `speech.ai.xiaomi.com` 之间的 WebSocket 消息。

#### 场景：录制识别文本

* **WHEN** 收到 `SpeechRecognizer/RecognizeResult` 消息且 `is_final == true`

* **THEN** 将 `dialog_id` → `origin_text` 存入内存缓存

#### 场景：替换回答文本

* **WHEN** 收到 `Template/Toast` 消息且 `dialog_id` 在缓存中

* **THEN** 用 `origin_text` 调用 DeepSeek API，将返回文本替换 `payload.text`

* **AND** 若配置 `enabled == false`，直接放行

* **AND** 若无对应 `dialog_id` 缓存，放行

* **AND** 若 DeepSeek 超时/报错，放行（保留原始回答）

### 要求：LLM API 调用

系统 SHALL 调用 LLM API 获取 AI 回答。
默认使用 Deepseek API
API Key 本地加密存储，不暴露在代码中
支持自定义请求地址，同时支持自动补全 `/v1/chat/completions`
支持兼容 OpenAI chat completion API 或 Anthropic API
支持自定义模型
支持自定义温度 top_p top_k
支持自定义上下文长度
支持自定义单位时间内连续调用时为单一会话内连续调用，携带上下文或独立会话调用无上下文
调用支持选择开启或关闭思考模式
支持自定义 System Prompt
支持自定义超时时间
支持自定义最大 Token 数

#### 场景：成功调用（OpenAI 兼容模式）

- **WHEN** LLM 类型为 `openai`，API 返回 200
- **THEN** 提取 `choices[0].message.content` 作为回答文本

#### 场景：成功调用（Anthropic 模式）

- **WHEN** LLM 类型为 `anthropic`，API 返回 200
- **THEN** 提取 `content[0].text` 作为回答文本

#### 场景：思考模式（OpenAI）

- **WHEN** `thinking_mode == true` 且 `api_type == "openai"`
- **THEN** 使用 `deepseek-reasoner` 模型，响应中提取 `reasoning_content` 和 `content`

#### 场景：思考模式（Anthropic）

- **WHEN** `thinking_mode == true` 且 `api_type == "anthropic"`
- **THEN** 请求体中添加 `thinking: {type: "enabled", budget_tokens: max_tokens}`，响应中提取 `thinking` 和 `content`

#### 场景：API 错误

- **WHEN** LLM API 返回非 200 或网络异常
- **THEN** 捕获异常，日志记录，放行原始消息

#### 场景：会话管理

- **WHEN** `context_mode == "single"` 且连续 Toast 到达时间间隔在 `context_window_ms` 内
- **THEN** 视为同一会话，请求携带历史消息（最多 `context_length` 条）
- **WHEN** `context_mode == "independent"` 或间隔超时
- **THEN** 视为独立会话，只携带当前查询和 system_prompt

### 要求：配置管理

系统 SHALL 提供用户可配置的设置项。

#### 场景：API Key 配置

* **WHEN** 用户在设置页面输入并保存 API Key

* **THEN** 写入 Remote Preferences，Hook 进程立即读取新值

#### 场景：模块开关

* **WHEN** 用户关闭模块开关

* **THEN** Hook 进程不进行任何拦截，所有消息透传

### 要求：Miuix 设置页面

系统 SHALL 提供 Miuix (HyperOS 设计语言) 风格的设置界面。

### 要求：日志搜集导出

系统 SHALL 提供日志搜集导出功能，用户可以在设置页面导出模块运行日志。

#### 场景：搜集运行日志

* **WHEN** Hook 进程产生日志（模块加载、拦截器注册、RecognizeResult 记录、LLM 调用、错误异常）

* **THEN** 日志写入内存环形缓冲区与日志文件，日志内容对 API Key 做脱敏遮蔽

#### 场景：导出日志

* **WHEN** 用户点击设置页"导出日志"按钮

* **THEN** 生成日志文件并通过系统分享面板导出（支持复制到剪贴板或保存为文件）


#### 场景：设置页布局

* **WHEN** 用户打开设置页面

* **THEN** 显示 Miuix 主题的设置界面，包含：

  * TopAppBar 标题 "环上LLM"

  * Card 分组：基本设置（启用开关、API 类型选择、Base URL、API Key 输入、模型选择）

  * Card 分组：生成参数（温度、top_p、top_k、思考模式开关、System Prompt、超时时间、最大 Token）

  * Card 分组：会话设置（会话模式选择、会话窗口时长、上下文长度）

  * Card 分组：日志（导出日志）

  * Card 分组：关于（测试连接、版本信息）

#### 场景：切换主题模式

* **WHEN** 系统切换深色/浅色模式

* **THEN** 设置界面跟随系统主题自动切换

## 未解决问题（待逆向确认）

| 问题                                                  | 影响                                            | 确认方式                                                     |
| --------------------------------------------------- | --------------------------------------------- | -------------------------------------------------------- |
| 小米运动健康使用的 WebSocket 客户端实现                           | 决定 Hook 目标和具体类名/方法名                           | 反编译 APK，搜索 `websocket`、`WebSocket`、`okhttp3.internal.ws` |
| 包名确认 (`com.mi.health` vs `com.xiaomi.huami.health`) | 决定 `scope.list` 和 Hook 过滤                     | 查看已安装的 App 包名                                            |
| 是否存在 SSL Pinning                                    | 不影响 WebSocket Hook（应用内拦截），但影响 DeepSeek API 调用 | 检查 `network_security_config.xml` 和 OkHttp 配置             |
| 消息 JSON 格式是否与抓包一致                                   | 决定解析逻辑                                        | 对比抓包数据与模块解析结果                                            |
| 小米运动健康使用的 OkHttp 版本                                 | 影响 `RealWebSocket` 内部类路径                      | 反编译 APK 检查 OkHttp 版本号                                    |

