# 环上LLM (mi-band-ai)

> 在小米运动健康 App 中拦截小爱 WebSocket 消息，将手环上的回答替换为 LLM（默认 DeepSeek）的回答。

一个纯 LSPosed（Xposed）Android 模块，注入小米运动健康 App（`com.mi.health`）进程内，让手环上的语音助手从"小爱同学"无缝切换为任意大模型——无需代理、无需证书、无需后台服务。

# 本项目由ai编写，使用本项目即代表你同意并接受存在的风险，使用过程中所产生的任何后果由使用者自行承担。

## ✨ 灵感来源

本项目思路源自酷安用户分享的方案：通过本地 MITM 代理拦截小爱明文流量并转发给 DeepSeek。

[🔗 原帖：手环接入大模型](https://www.coolapk.com/feed/72763393?s=ZDUzZTY1YzY0MGI3NDRnNmE5OGM3YWJ6a1661)

原方案（Termux + mitmproxy）操作繁琐、无法自启、影响全局流量。本模块以 LSPosed 方案在 App 进程内完成注入，开箱即用。

## 🎁 鸣谢

设置页 UI 基于小米 HyperOS 设计语言的 Miuix 构建

[🔗 compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)

## 🧠 工作原理

1. Hook 小米运动健康 App 的 WebSocket 消息层（多道容错：混淆的 LiteCryptWsClient 主文本层 + OkHttp RealWebSocket 兜底 + 非混淆 APIUtils 稳定兜底）；
2. 拦截小爱语音识别结果 `SpeechRecognizer/RecognizeResult`，缓存为待回答的提问；
3. 当小爱返回 `Template/Toast` 时，将提问转发给 LLM（默认 DeepSeek）获取回答；
4. 改写 Toast 消息的 `payload.text`——**手环上显示的是 LLM 的回答**，而非小爱原回答。

```
你说话 ──▶ 手环 ──▶ 小米运动健康(App) ──▶ 小爱WebSocket(被Hook拦截)
                                              │
                修改Toast回复 ◀── LLM(DeepSeek) ◀┘
```

## ✨ 特性

- **纯 LSPosed 注入**：无代理、无证书、无后台服务，不干扰宿主
- **双协议路由**：OpenAI 兼容（含 DeepSeek 思考模式）/ Anthropic 双支持，Base URL 可自定义
- **会话记忆**：60s 窗口内连续提问自动续接上下文，也可切换为独立会话
- **多路容错 Hook**：任一拦截层成功即可工作，异常一律放行原消息
- **Miuix 精致设置页**：主题模式 / Monet 动态取色 / 毛玻璃 / 悬浮导航栏，复刻 KernelSU Manager
- **日志脱敏**：API Key 与 Authorization 头自动脱敏，环形缓冲 + 文件导出
- **配置实时生效**：跨进程 Remote Preferences，改设置无需重启手环

## 📦 使用前提

| 条件      | 说明                                           |
| ------- | -------------------------------------------- |
| 系统      | 已 Root，且安装 LSPosed（现代 Xposed API ≥ 102）      |
| 手机 App  | 小米运动健康 `com.mi.health`（连接手环/手表的 App）         |
| 手环/手表   | 支持小爱语音助手                                     |
| LLM API | 任选一家 OpenAI 兼容 / Anthropic 兼容服务（默认 DeepSeek） |

## 🚀 安装使用

1. 在 [Releases](https://github.com/Little-White3110/mi-band-ai/releases) 下载最新 APK（或按下方指引自行构建）；
2. 安装后在 LSPosed 管理器中启用模块，勾选作用域 `小米运动健康`，重启该 App；
3. 打开"环上LLM"设置页，填入你的 API Key 与模型参数（默认为 DeepSeek）；
4. 对手环说"小爱同学 + 你的问题"，手环上即为 LLM 的回答。

## 🔧 自行构建

要求：JDK 17、Android SDK（platform 37）。

```bash
# Debug 构建（未混淆，可直接安装）
./gradlew assembleDebug

# Release 构建（开启混淆）
./gradlew assembleRelease

# 构建产物输出位置
# app/build/outputs/apk/{debug,release}/
```

### CI/CD

仓库已配置 GitHub Actions 流水线（`.github/workflows/build-apk.yml`）：

- 每次 push / PR 自动构建 Debug + Release APK 并上传为 Artifact；
- 推送 `v*` 标签（如 `v0.1.0`）自动发布 GitHub Release。

## 📁 项目结构

```
mi-band-ai/
├── app/
│   └── src/main/kotlin/llm/miband/littlewhite/
│       ├── MainModule.kt              # Xposed 模块入口
│       ├── SettingsActivity.kt        # 设置页 Compose Activity
│       ├── config/                    # 配置键/存储/预设/跨进程统计
│       ├── hook/                      # Hook 核心（LlmClient / MiHealthHook / WebSocketInterceptor）
│       ├── log/LogCollector.kt        # 日志收集（环形缓冲+文件，自动脱敏）
│       └── ui/                        # Miuix 设置页（4 Tab + 主题页）
├── docs/
│   ├── feasibility_report.md          # KernelSU/LSPosed 方案可行性分析
│   ├── lsposed-dev-guide.md           # LSPosed 模块开发指南
│   └── reverse-notes.md               # com.mi.health v3.58.0 逆向笔记
└── example/                           # 原 MITM 方案留存（仅参考）
```

## 📄 协议

本项目基于 [MIT License](LICENSE) 开源。

> ⚠️ 免责声明：本项目仅供学习研究，请勿用于任何可能违反小米服务条款的用途。使用造成的一切后果由使用者自行承担。

