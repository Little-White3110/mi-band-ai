# 将对话接入手机端小爱同学 —— 可行性分析报告

> **分析对象**：`apk/超级小爱-8.2.3.1619(508002003).apk`（`com.miui.voiceassist`，242 MiB）
> **生成时间**：2026-09-03
> **分析方法**：纯静态（jadx 1.5.6 反编译 + apktool 2.11.1 解包 + Manifest/资产/字符串审阅 + DNS 归属）
> **范围声明**：本报告**未进行任何动态抓包、未安装运行、未绕过任何鉴权**。所有结论来自静态代码与配置证据，需真机验证的条目见第 10 章。
> **关联文档**：`docs/reverse-notes.md`（com.mi.health 侧逆向）、`docs/feasibility_report.md`（方案选型）

***

## 一、摘要（结论先行）

**总判定：技术上可行，但"接入手机端小爱"存在两条完全不同的含义，可行性差异极大。**

| 目标含义                                                   | 可行性        | 说明                                                                                                   |
| ------------------------------------------------------ | ---------- | ---------------------------------------------------------------------------------------------------- |
| **A. 以第三方 App 身份，官方 SDK 直连小爱对话服务**                     | ❌ **不可行**  | 被 `privileged\|signature` 权限 + platform 签名白名单 + Agent 目录预设 + 计费 ID 四重门禁封死                            |
| **B. LSPosed 注入** **`com.miui.voiceassist`，进程内接管对话链路** | ✅ **高度可行** | 同 UID 分支天然绕过调用方鉴权；Agent 定义可从**应用数据目录**加载（非只读 assets）；LLM 出口 `ModelConfig` 支持完全自定义 base\_url/api\_key |
| **C. 维持现有** **`com.mi.health`** **AIVS 通道 Hook**       | ✅ **已实现**  | 但存在被小爱新架构**架空**的现实风险（见第七章）                                                                           |

**三个最重要的发现：**

1. **小爱已不是"语音助手"，而是一个完整的 LLM Agent 运行时**（`com.aios.osbot.*` / 内部代号 **miclaw**）。它自带 Agent 定义体系、MCP 客户端、40+ 工具、模型路由、记忆系统、A2A 跨设备协议。本项目此前面对的"小爱返回一句 Toast 文本"只是它最表层的兼容出口。

2. **小爱对外暴露了正式的对话接入契约** —— `com.aios.apptoolsdk.ExternalAgentClient`，提供 `openSession / submit / closeSession` 与**流式回调**（`onTextDelta` / `onReasoningDelta` / `onToolEvent` / `onTtsEvent`）。这比本项目现在的"阻塞改写 Toast 的 `payload.text`"在能力上高一个数量级：支持流式、思考过程、工具事件、TTS 事件、大结果 FD 传输（>256 KB）。

3. **本项目当前方案有失效风险**：超级小爱内置了 `com.xiaomi.aivsbluetoothsdk`（含杰理/智米/OnePlus 等厂商蓝牙指令集），意味着**手机端小爱已具备直连手环/耳机的能力**。一旦小米把手环语音链路从"小米运动健康代理 AIVS"迁到"小爱直连"，本项目 Hook 的 `com.mi.health` 通道将不再有流量。

**建议路线**：短期加固现有方案（C）+ 立即验证链路归属；中期以 LSPosed 注入 `com.miui.voiceassist`（B）作为第二作用域，优先做 **LLM 出口改道**（成本最低、收益最大），而非重建对话通道。

***

## 二、分析对象基础信息

| 项目         | 值                                                                                                                                                                                                          | 证据                                                                                         |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| 包名         | `com.miui.voiceassist`                                                                                                                                                                                     | `AndroidManifest.xml:2`                                                                    |
| 应用名        | 超级小爱                                                                                                                                                                                                       | `assets/agents/osbot.main/config.json` 中 `"name": "超级小爱"`                                  |
| 版本         | 8.2.3.1619 (508002003)                                                                                                                                                                                     | 文件名                                                                                        |
| 内部代号       | **miclaw** / **osbot** / **aios**                                                                                                                                                                          | 域名 `miclaw.security.xiaomi.net`、包 `com.aios.osbot.*`、`assets/miclaw-cap-rsa-v1-public.pem` |
| compileSdk | 36 (Android 16)                                                                                                                                                                                            | `AndroidManifest.xml:2` `platformBuildVersionName="16"`                                    |
| 体积 / DEX   | 242 MiB / 32 个 dex                                                                                                                                                                                         | 7z 清单                                                                                      |
| 反编译产物      | 57,200 个 `.java`（1090 处方法级反编译失败）                                                                                                                                                                           | `.pentest/static/decompiled/`                                                              |
| **签名**     | **MIUI platform key**（`PLATFORM.RSA` / `PLATFORM.SF`）`CN=MIUI, OU=MIUI, O=Xiaomi, L=Beijing, C=CN`SHA256 `C9:00:9D:01:EB:F9:F5:D0:30:2B:C7:1B:2F:E9:AA:9A:47:A4:32:BB:A1:73:08:A3:11:1B:75:D7:B2:14:90:25` | `keytool -printcert -jarfile`                                                              |
| **加固**     | **无**（未加壳，jadx 可直接反编译全部 dex；无 `jiagu`/`DexHelper`/乐固/爱加密特征 so）                                                                                                                                             | 7z `*.so` 过滤无命中                                                                            |
| 混淆         | 业务包（`com.aios.osbot.*`、`com.xiaomi.voiceassistant.*`）**类名/方法名基本未混淆**；仅 Kotlin 生成内部类与第三方库被混淆为 `aa1.f`、`bk.q` 等                                                                                              | 反编译结果                                                                                      |
| 多进程        | 主进程 + `:provider` + `:core` + `:inputMethodService` + headless                                                                                                                                             | Manifest `android:process`                                                                 |

> **关键含义**：签名是 platform key ⇒ 该 App 与 `android` framework 签名一致 ⇒ 它持有的 `signature` 级权限，普通 App 永远拿不到。这直接决定第六章的门禁判定。

***

## 三、架构：小爱内部是"双引擎"

静态证据显示 `com.miui.voiceassist` 同时承载两套并行的语音/对话栈：

```
┌──────────────────────────────────────────────────────────────────────────┐
│          com.miui.voiceassist (platform 签名，系统应用)                     │
│                                                                          │
│  ┌────────────────────────────┐      ┌─────────────────────────────────┐ │
│  │  引擎①：传统小爱 / AIVS      │      │  引擎②：osbot / miclaw Agent     │ │
│  │  com.xiaomi.voiceassist.*   │      │  com.aios.osbot.*               │ │
│  │  com.xiaomi.ai.*            │      │                                 │ │
│  │                             │      │  ┌───────────────────────────┐  │ │
│  │  SpeechService              │      │  │ Agent 运行时               │  │ │
│  │  ASR 引擎 (com.xiaomi.asr)   │      │  │ assets/agents/*.json      │  │ │
│  │  唤醒 (libflexkws.so)        │◄────►│  │  osbot.main = 主对话       │  │ │
│  │  TTS (com.xiaomi.speech.tts)│      │  │  可被 appDataDir 覆盖      │  │ │
│  │                             │      │  └─────────────┬─────────────┘  │ │
│  │  WebSocket:                 │      │                │                │ │
│  │  wss://speech.ai.xiaomi.com │      │  ┌─────────────▼─────────────┐  │ │
│  │   /speech/v1.0/longaccess   │      │  │ LLM 路由 ModelConfig       │  │ │
│  │  ★ 本项目当前 Hook 点 ★      │      │  │ provider/base_url/api_key  │  │ │
│  └────────────────────────────┘      │  │ 云端 api.miclaw.xiaomi.net │  │ │
│                                      │  │ /osbot/api/intent/v2/...   │  │ │
│                                      │  └─────────────┬─────────────┘  │ │
│                                      │                │                │ │
│                                      │  ┌─────────────▼─────────────┐  │ │
│                                      │  │ 工具层 ToolRegistry (40+)   │  │ │
│                                      │  │ bash/cli/mcp/skill/agent   │  │ │
│                                      │  │ mihealth/calendar/phone... │  │ │
│                                      │  └───────────────────────────┘  │ │
│                                      │  MCP 客户端 / 记忆系统 / A2A     │ │
│                                      └─────────────────────────────────┘ │
│                                          ▲                                │
│  ┌───────────────────────────────────────┴──────────────────────────────┐ │
│  │  对外 IPC 层：com.aios.osbot.external.*  +  com.aios.apptoolsdk.*      │ │
│  │  ExternalAgentService / CliCommandService / ExternalAsrService / ...   │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
            ▲                                        ▲
            │ AIVS WebSocket（RecognizeResult/Toast）  │ bindService + AIDL（流式）
   ┌────────┴─────────┐                    ┌──────────┴───────────┐
   │  com.mi.health   │                    │  外部 App（需特权签名） │
   │  （小米运动健康）  │                    │  支付宝/高德/米家 等     │
   │  ★ 手环语音代理   │                    └──────────────────────┘
   └────────┬─────────┘
            │ 蓝牙 RCSP
      ┌─────▼─────┐
      │  小米手环  │
      └───────────┘
```

**证据锚点**：

- 引擎①：`com/xiaomi/speech/framework/service/SpeechService.java`、`com/xiaomi/asr/engine/jni/*`、`assets/wakeupModel`、`assets/flexkws`、`lib/arm64-v8a/libflexkws.so`

- 引擎②：`com/aios/osbot/agent/unified/AgentDefinition.java`、`com/aios/osbot/llm/router/ModelConfig.java`、`com/aios/osbot/router/d.java:35`

- 工具层：`com/aios/osbot/tools/` 下 42 个子目录（agent/app/bash/browser/card/cloud/device/history/mcp/media/memory/mihealth/notification/phone/sandbox/script/skill/system/web 等）

***

## 四、osbot Agent 引擎细节（决定接入方式）

### 4.1 Agent 定义体系

`assets/agents/` 下每个目录是一个 Agent，含 `config.json` + `prompt.md` + `skills/`：

```
agents/
├── osbot.main/                    # 超级小爱主对话（execution_mode=main）
├── osbot.chat/                    # 好友消息助手（sub）
├── osbot.group_chat/
├── osbot.overlayassistant/
├── osbot.content_assistant-*/     # 8 个内容助手子 Agent
├── osbot.island_formatter/        # 灵动岛排版
├── osbot.taiyi/
├── com.xiaomi.type*               # 输入法改写/翻译/回复
└── com.android.camera/            # 相机智能体
```

`osbot.main/config.json` 关键字段（完整读取自资产文件）：

```json
{
  "id": "osbot.main", "name": "超级小爱", "execution_mode": "main",
  "capabilities": ["enable_sub_agent","enable_island_notification",
                   "enable_standalone_page","enable_multi_chat"],
  "max_agent_depth": 3,
  "tools_allowlist": ["*"],
  "preload_tools": ["skill","personaldata_search","web_search","url_fetch",
                    "read_file","write_file","cloud_sandbox","remote_file",
                    "get_current_time","search_history","load_message",
                    "background_task","cli","mcp","agent","miot_ask_assistant","var"],
  "max_iterations": 30
}
```

> **注意** **`preload_tools`** **含** **`cloud_sandbox`、`cli`、`mcp`、`agent`** —— 这是一个具备代码执行与外部工具编排能力的 Agent，不是简单问答机器人。

### 4.2 ★ Agent 定义可从「可写数据目录」加载（关键可利用点）

`yr/d.java:71-74`：

```java
public final String a(a0 a0Var, String str, File file, String str2, boolean z13) {
    File file2 = file != null ? new File(file, str2) : null;   // file = appDataDir
    return yr.a.C4747a.readFileOrAsset$default(..., a0Var, file2,
        "agents/" + str + "/" + str2, null, z13, 8, null);      // 回退 assets
}
```

以及 `nt/i.java:1695-1719` 的 `readFileOrAsset` 语义：**先查** **`appDataDir/agents/<agentId>/<file>`** **是否存在，存在则用文件版，否则回退** **`assets/agents/...`**。

⇒ 结论：Agent 目录**不是**只读烧死的。在 `com.miui.voiceassist` 的 `files/` 下放置 `agents/<agentId>/config.json` 即可注册自定义 Agent。这是路径 B 的核心技术支点（需 Root 或进程内写权限）。

### 4.3 LLM 路由：与本项目 `LlmClient` 完全同构

`ModelConfig.java` 序列化字段：

```
id / provider / model_name / api_key / base_url / model_provider_id
priority / tags / temperature / enable_thinking / context_window
```

默认值与端点（`bk/q.java`、`ab1/o.java:68`）：

| 项                 | 值                                                                    |
| ----------------- | -------------------------------------------------------------------- |
| 默认模型              | `xiaomi/mimo`，provider=`openai`，base\_url=`getMifyLlmBaseUrl()`      |
| OpenAI 默认 base    | `https://api.openai.com/v1`                                          |
| Anthropic 默认 base | `https://api.anthropic.com/v1`                                       |
| 意图/路由端点           | `https://api.miclaw.xiaomi.net/osbot/api/intent/v2/chat/completions` |
| TTS               | `https://api.mify.mioffice.cn/v1/chat/completions`                   |
| 思考模式              | `enable_thinking` 字段（与本项目 `thinking_mode` 对应）                        |

**并且小爱自带可视化 LLM 设置页**：`com/aios/osbot/ui/settings/xa.java`（日志 tag `LlmSettingsScreen`）、`eg.java`（`SettingsViewModel`）暴露 `setOpenAIBaseUrl`、`setLlmProvider("openai")`、`overwriteProfile(profileId, llmProvider, apiKey, modelName, openaiBaseUrl, anthropicBaseUrl, modelProviderId, temperature)`，且代码内已针对 `minimax` / `glm` / `kimi` 做特判。

⇒ **含义**：小米自己就把"换第三方 LLM"做成了产品功能。本项目若要"接入手机端小爱"，最省事的做法不是重建通道，而是**驱动/复用这个既有配置面**（或 Hook 其读取点）。

### 4.4 MCP 与 Skill 体系

`assets/mcp/README.md` 显示 osbot 支持用户自行添加 MCP 服务：

```json
{ "servers": [ { "name":"feishu", "url":"https://mcp.feishu.cn/mcp/xxx",
                 "enabled":true, "headers":{"Authorization":"Bearer ..."} } ] }
```

- 支持 Streamable HTTP 与 SSE；配置路径 `mcp/mcp_servers.json`（**应用数据目录，可写**）

- 热更新：`reload_mcp_config`；企业 MCP 只读预置

- UI 入口：设置 → MCP 服务

- 明确安全规则：MCP 返回内容视为**外部不可信数据**，禁止据此执行读短信/通讯录/位置/发网络请求

⇒ **这是本项目最被低估的接入机会**：一个标准 MCP Server 就能把 DeepSeek 能力以"工具"形式挂进小爱主 Agent，**完全不需要逆向私有协议、不需要绕过签名门禁**（详见第八章路径 E）。

***

## 五、对外 IPC 接口契约（"接入小爱"的官方通道）

### 5.1 暴露的 exported 组件清单

| 组件                                                           | Action / Authority                     | 权限要求                                               | 保护级别                      |
| ------------------------------------------------------------ | -------------------------------------- | -------------------------------------------------- | ------------------------- |
| `com.aios.osbot.external.ExternalAgentService`               | `com.aios.osbot.action.EXTERNAL_AGENT` | `com.aios.osbot.permission.EXTERNAL_AGENT`         | **privileged\|signature** |
| `com.aios.osbot.external.CliCommandService`                  | `...action.CLI_COMMAND`                | 同上                                                 | privileged\|signature     |
| `com.aios.osbot.external.PendingIntentJumpService`           | `...action.PENDING_INTENT_JUMP`        | 同上                                                 | privileged\|signature     |
| `com.aios.osbot.external.ExternalAsrService`                 | `...action.EXTERNAL_ASR`               | 同上                                                 | privileged\|signature     |
| `com.aios.osbot.external.OSbotSpeechService`                 | `com.xiaomi.speech.action.SPEECH`      | `com.xiaomi.speech.permission.BIND_SPEECH_SERVICE` | privileged\|signature     |
| `com.aios.osbot.event.OSbotMessengerService`                 | `...action.MESSENGER`                  | **Manifest 未声明 permission**                        | 代码内 CallerVerifier 兜底     |
| `com.xiaomi.voiceassistant.osbot.SidekickOsbotStartReceiver` | `com.aios.osbot.headless.action.START` | **无**                                              | exported=true             |
| `com.xiaomi.voiceassistant.UIAgentServiceForOSBot`           | `...action.APP_TOOL_PROVIDER`          | `com.aios.osbot.permission.BIND_APP_TOOL`          | signature                 |
| `com.xiaomi.SpeechProvider.theme.PublicAsrProvider`          | `com.miui.voiceassist.speech.api`      | `miui.permission.USE_INTERNAL_GENERAL_API`         | 特权                        |
| `com.xiaomi.SpeechProvider.manager.XiaoaiManagerProvider`    | `...xiaoai.manager.provider`           | `...WRITE_XIAOAI_MANAGER_PROVIDER`                 | signature                 |
| `com.aios.osbot.fileexchange.FileExchangeService`            | `...action.FILE_EXCHANGE`              | exported=true                                      | —                         |

### 5.2 ExternalAgentClient 完整调用契约

`com/aios/apptoolsdk/ExternalAgentClient.java` + `aidl/IExternalAgentService.java`：

```java
// 1) 绑定
Intent i = new Intent("com.aios.osbot.action.EXTERNAL_AGENT");
i.setPackage("com.miui.voiceassist");
context.bindService(i, conn, BIND_AUTO_CREATE);   // → IExternalAgentService

// 2) 开会话
String sessionId = svc.openSession(appMeta.toJson(), /*persistent=*/false);
//   失败返回 "error:PERMISSION_DENIED" / "error:CTA_NOT_ACCEPTED" / "error:INTERNAL_ERROR"

// 3) 提交（请求体是 JSON 字符串）
svc.submit(sessionId, "{\"type\":\"message\",\"text\":\"用户问题\"}",
           attachments, new IExternalAgentCallback.Stub() {
      onTextDelta(sessionId, delta)       // 流式正文
      onReasoningDelta(sessionId, delta)  // 流式思考过程
      onToolEvent(sessionId, json)        // 工具调用事件
      onTtsEvent(sessionId, json)         // {"type":"started"|"completed"|"stopped"|"error"}
      onComplete(sessionId, resultJson, attachments)
      onError(sessionId, errJson)         // {"code":..,"message":..,"retryable":..}
});

// 4) 关会话
svc.closeSession(sessionId);
```

`AppMeta` 字段：`appName / locale / context / tools[] / tag / targetPackage / chatId / bizId / featureId`

协议细节：

- 请求：`{"type":"message","text":"..."}`；可选 `"tts":"on"|"off"|"auto"`（`ExternalAgentService.java:894-943`）

- 响应：`{"text":"...","tool_calls_summary":[...],"tokens_used":N}`

- 大结果：>256 KB 走 `ParcelFileDescriptor` 附件回传（`Attachment.fromFd("result.json","application/json",fd)`），客户端上限 4 MB

- 附件：入站上限 10 MB/个；FD 读超时按大小动态 5–30 s

- 会话：UUID 前 16 位；**30 分钟无活动驱逐**（`SESSION_EVICT_TIMEOUT_MS=1800000`，每 5 分钟扫描）

- 执行超时：`EXTERNAL_EXECUTION_TIMEOUT_MS = 300000`（5 分钟）

- `context` 与 `appName` 会被拼进 system prompt（`buildAugmentedSystemPrompt:555-568`）⇒ **可注入场景描述**

### 5.3 外部 ASR 通道

`aidl/IExternalAsrService.java`：`startAsr(AsrAudioConfig, IAsrCallback)→asrId` / `feedAudio(asrId, byte[])` / `stopAsr` / `cancelAsr`，回调 `onPartialResult` / `onSentenceEnd` / `onFinalResult`。

⇒ 意味着可以**只借用小爱的 ASR 能力**（喂 PCM 拿文本），不必走完整对话。对本项目"手环录音 → 文本"这一环是潜在替代方案。

***

## 六、四道门禁逐条判定（可行性核心）

### 门禁 1：bindService 层调用方校验

`hs/a.java:35-58`（`CallerVerifier.isCallerAllowed`）：

```java
if ((context.getApplicationInfo().flags & 2) != 0) return true;   // 自身 debuggable → 放行
int uid = Binder.getCallingUid();
if (uid == 2000) { 拒绝("ADB shell"); return false; }              // ★ 显式拒绝 adb shell
String pkg = pm.getNameForUid(uid);
if (pkg == null) { 拒绝; return false; }
// 放行条件：系统应用(FLAG_SYSTEM) 或 与 "android" 平台签名一致
if ((pm.getApplicationInfo(pkg,0).flags & 1) != 0
        || pm.checkSignatures(pkg, "android") == 0) return true;
拒绝("非系统签名应用"); return false;
```

调用点：`ExternalAgentService.onBind()`（L1183-1190）。

**判定**：

- ❌ 普通第三方 App（含本项目独立 APK）→ `bindService` 直接返回 `null`，连不上。

- ❌ `adb shell`（uid 2000）被**显式点名拒绝** → 无法用 `am`/`content` 命令从 shell 侧调通。

- ✅ **LSPosed 注入 voiceassist 自身进程** → `Binder.getCallingUid() == Process.myUid()`，走同 UID 快速分支（见门禁 2）。

- ✅ 注入**任意 platform 签名系统应用**（如 `com.miui.personalassistant`、systemui）后发起 bind，同样通过。

### 门禁 2：权限 + 同 UID 旁路

Manifest：`EXTERNAL_AGENT` = `privileged|signature` ⇒ 只有 platform 签名 App 或 `/system/priv-app` 且在 `privapp-permissions` 白名单内的 App 能持有。

但服务端有**同 UID 旁路**（`ExternalAgentService.java:863-865` 与 `binder$1.openSession:80-94`）：

```java
// resolveCallerOwner()
return Binder.getCallingUid() == Process.myUid()
     ? getPackageName()          // ★ 同 UID：直接返回自身包名，完全跳过 checkPermission
     : callerPackageName();      // 否则才 checkPermission(EXTERNAL_AGENT)

// openSession() 同 UID 分支
if (Binder.getCallingUid() == Process.myUid()) {
    callerPkg = getPackageName();
    agentPkg  = isBlank(meta.targetPackage) ? getPackageName() : meta.targetPackage;
} else {
    callerPkg = callerPackageName();
    if (callerPkg == null) return "error:PERMISSION_DENIED";
    agentPkg  = resolveAgentPackage(callerPkg, meta);   // 跨包重定向需同 UID 校验
}
```

**判定**：✅ 注入 voiceassist 进程内调用 ⇒ 门禁 1、2 同时失效。这是**技术上最干净的突破口**。

### 门禁 3：CTA（用户协议）门控

`ctaGate.isAccepted()` 未通过时：`openSession` 返回 `error:CTA_NOT_ACCEPTED`；`submit` 返回 `{"code":"CTA_NOT_ACCEPTED","message":"用户授权弹窗已拉起，请稍后重试"}`。

**判定**：⚠️ 软门禁。用户在小爱内同意一次 miclaw 协议即长期通过（`CtaGate` 落盘持久化）。真机验证时先手动同意即可。

### 门禁 4：Agent 目录 + 计费 ID（最硬的一关）

`binder$1.submit:156-169`：

```java
// agentId = buildAgentId(agentPackage, tag) = tag 为空 ? agentPackage : agentPackage + "-" + tag
a agentMeta = getAgentExecutor().getAgentMeta(session.getAgentId());
if (agentMeta == null)
    → onError("AGENT_NOT_FOUND", "Agent '<id>' 未在 miclaw 中配置，请联系 OSbot 团队预设 Agent 目录")

if (isBlank(meta.getBizId()) || isBlank(meta.getFeatureId()))
    → onError("20003", "Invalid biz feature")     // ★ 强制计费标识
```

`getAgentMeta` 实现（`xk/c.java:74-81`）从 `AgentManager i0` 内存表查，而该表按 §4.2 从 **appDataDir 优先、assets 兜底** 加载。

**判定**：

- `bizId` / `featureId`：客户端侧**仅校验非空**（`isBlank`），填任意非空串即可过本地检查。但服务端 `getAccessCheckEndpoint() = https://api.miclaw.xiaomi.net/osbot/api/user/v2/check-whitelist` 与 `getLlmControlEndpoint() = /osbot/api/client/v2/user/control` 可能二次校验 ⇒ **必须真机验证**。

- `agentId` 命中：同 UID 调用时 `agentId = "com.miui.voiceassist"`（未注册）⇒ 需二选一：

  - (a) 传 `AppMeta.tag` 使 `agentId` 拼成已注册项（如 `targetPackage="osbot", tag="main"` → `osbot-main`，需实测命名）；

  - (b) **在** **`files/agents/com.miui.voiceassist/config.json`** **落一个自定义 Agent**（§4.2 证明可行）⇒ 完全自主可控，且可指定 `llm` override。

***

## 七、★ 紧迫风险：现有方案可能被架构迁移架空

本项目 Hook 的是 `com.mi.health` 内的 AIVS 客户端（`wss://speech.ai.xiaomi.com/speech/v1.0/longaccess`）。但超级小爱 APK 内**自带了完整的蓝牙设备语音 SDK**：

```
com/xiaomi/aivsbluetoothsdk/
├── impl/BluetoothEngine.java / BluetoothAuth.java / BluetoothConfig.java
├── constant/{RCSP, Command, VendorJieLiCmd, VendorJLS18Cmd,
│             VendorZiMiCmd, VendorOneMoreCmd, VendorJieliAnbeiCmd}.java
├── db/{BleScanMessage, ScanConfig, BluetoothDeviceExt, OtherDeviceInfo}.java
└── interfaces/CommandCallback.java
```

另有 `lib/arm64-v8a/libaivs_jni.so`、`libaivsopus.so`、`libjlspeex.so`（杰理 Speex）、`libmicontinuity_sdk.so`（跨端连续性）。

**含义**：手机端小爱已具备**直接与手环/耳机建立 RCSP 会话并处理语音**的能力（覆盖杰理、智米、OnePlus 等主流手环方案芯片）。一旦小米把"手环语音"从 `com.mi.health 代理 AIVS` 迁到 `voiceassist 直连`：

- 本项目 Hook 的 `oav.onMessage` / `APIUtils.readInstruction` 将**不再有流量**，功能静默失效；

- 手环上的回答将来自 osbot Agent（含工具调用、富卡片），不再是单条 `Template/Toast`。

⇒ **这是本项目最高优先级的验证项**（见第十章 Task 1）。

同时注意：Manifest `<queries>` 显式探测 **`de.robv.android.xposed.installer`** 与 **`com.saurik.substrate`**（`AndroidManifest.xml:270-271`），且内置 `assets/security/guardrail/`、`assets/security/desensitization/`、`miclaw-cap-rsa-v1-public.pem` 签名校验。⇒ **小爱具备 Xposed 检测与内容风控能力**，注入方案需评估被检测风险。

***

## 八、技术路径可行性评级

### 路径 A：第三方 App 官方 SDK 直连 —— ❌ 不可行

门禁 1+2 要求 platform 签名；门禁 4 要求小米预设 Agent 目录并分配 `bizId/featureId`。该通道面向小米生态伙伴（Manifest 可见 `com.alipay.mobile.wallet.agent`、高德、米家等对接痕迹）。**不建议投入。**

### 路径 B：LSPosed 注入 voiceassist，进程内接管 —— ✅ 高度可行（推荐主攻）

| 子路径             | 做法                                                                                               | 成本 | 收益                                | 风险                      |
| --------------- | ------------------------------------------------------------------------------------------------ | -- | --------------------------------- | ----------------------- |
| **B1 LLM 出口改道** | Hook `bk.b.getMIFY_LLM_BASE_URL()` / `ModelConfig.getBaseUrl()`，把小爱全部 LLM 请求指向 DeepSeek          | 低  | **覆盖手机端所有小爱入口**（语音/输入法/侧边栏/米家/手环） | 混淆名变更即失效；OTA 风险         |
| **B2 Agent 注入** | 在 `files/agents/<id>/config.json` 落自定义 Agent（含 `llm` override + `prompt.md`）                     | 中  | 完全自主可控的 Agent 行为                  | 可能被 guardrail / 包签名校验拦截 |
| **B3 IPC 内调**   | 进程内以同 UID 走 `ExternalAgentService` binder，或在 `com.aios.osbot.facade.b`（`xk.c`）层拦截，拿流式 token 回投手环 | 高  | 获得流式 + 工具事件 + TTS 事件              | 改动系统核心 App，稳定性要求最高      |

### 路径 C：维持 `com.mi.health` AIVS Hook —— ✅ 已实现，需加固

现有方案（方案 A + 方案 C 双 Hook 点、Toast 阻塞改写、去重、指令切换）保持不变。**但必须先完成 Task 1 的链路归属验证**，否则可能在某次 OTA 后静默失效。

### 路径 D：借用小爱 ASR —— ⚠️ 可行但收益有限

`ExternalAsrService` 同样受门禁 1+2 限制；进程内（路径 B）可直调。手环录音上云 ASR 已由 `com.mi.health` AIVS 完成，替换价值不大。

### 路径 E：★ MCP Server 挂载 —— ✅ 可行且合规成本最低（新发现，强烈建议评估）

`assets/mcp/README.md` 证实 osbot 支持**用户自行添加个人 MCP 服务**（Streamable HTTP / SSE），配置在可写的 `mcp/mcp_servers.json`，支持 `reload_mcp_config` 热更新，UI 有"设置 → MCP 服务"入口。

⇒ 写一个把 DeepSeek 包装成 MCP tool 的 HTTP 服务，让用户在小爱设置里添加，**主 Agent** **`osbot.main`** **的** **`preload_tools`** **含** **`mcp`**，即可让小爱在对话中调用我们的 LLM。

| 维度               | 路径 B1       | 路径 E（MCP）          |
| ---------------- | ----------- | ------------------ |
| 是否需 Root/LSPosed | 需要          | **不需要**            |
| 是否绕过签名门禁         | 是（同 UID）    | **完全不需要**          |
| 被 Xposed 检测风险    | 有           | **无**              |
| OTA 存活率          | 低（混淆/重构即失效） | **高（官方契约）**        |
| 能力               | 替换全部回答      | 小爱**主动调用**我们的工具    |
| 局限               | —           | 由小爱决定是否调用该工具，非强制接管 |

***

## 九、SDK / 第三方依赖与安全评估（摘要）

### 9.1 主要第三方依赖（按包路径 + so 指纹）

| 类别     | 命中                                                                                                                           | 证据                                                          |
| ------ | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| 跨端渲染   | **React Native + Hermes**（`libhermes.so`、`libjsi.so`、`libreact_render_*`、folly、glog）                                         | `lib/arm64-v8a/`、`assets/*.bundle`（home/stream/memorynew 等） |
| 轻量 JS  | **QuickJS**（`libqjsruntime.so`、`libquickjs_android.so`）、`assets/bash-scripts/*.js`、`card-scripts/dist/*`                     | 卡片/脚本引擎                                                     |
| 端侧 AI  | **阿里 MNN**（`libMNN.so`、`libCVLM-DLA.so`、`assets/model/CVLM-DLA.mnn`）、`libowl.so`、`libratex_ffi.so`（LaTeX）                    | 视觉/公式渲染                                                     |
| 存储     | **腾讯 MMKV**（`libmmkv.so`；`PublicAsrProvider.onCreate` 调 `MMKV.initialize`）                                                   | —                                                           |
| 网络     | OkHttp3 + Retrofit2 + Gson（`com.squareup`）                                                                                   | 顶层包                                                         |
| 图像     | Facebook Fresco（`libimagepipeline.so`、`libgifimage.so`）、Glide（`uz.m` 即 Glide Downsampler）                                    | —                                                           |
| 动画     | **腾讯 PAG**（`libpag.so`、`assets/PAG/*` 数十个动效）、Lottie                                                                          | —                                                           |
| 地图     | 高德（`com.amap`、`com.autonavi`）、`libliteca.so`                                                                                 | —                                                           |
| 广告/统计  | **字节穿山甲 Pangle**（`libpangleflipped.so`、`com.bytedance`、`com.ss.android`）、抖音开放 SDK（`AwemeOpenSdkWrapper`）、`com.miui.onetrack` | —                                                           |
| Hook 库 | **字节 bytehook**（`libbytehook.so`，PLT hook）、xCrash                                                                            | —                                                           |
| 语音     | `libafe_sdk.so`、`libidmtrans.so`（翻译）、`libmp3lame.so`、`libaivsopus.so`                                                        | —                                                           |
| 支付     | 支付宝（`assets/alipay`、`com.alipay.mobile.wallet.agent` IPC）、`assets/authorization`                                             | —                                                           |
| 快应用    | HAP（`assets/hap`、`libhapbridge.so`、`org.hapjs`）                                                                              | —                                                           |
| UI     | **Miuix**（`miuix.*` 40+ 模块，与本项目同源）、Compose Material3                                                                         | `META-INF/miuix.*.version`                                  |
| 其他     | 阿里云、百度、火山引擎、酷狗、流利说、jlatexmath（公式）、xcrash                                                                                     | 顶层包                                                         |

> **附带观察**：小米自己在用 `libbytehook.so`（字节 PLT hook 库）做 native 插桩 —— 说明该 App 对 hook 技术并不陌生，检测能力值得警惕。

### 9.2 安全观察（与本项目的关联）

| 严重度 | 发现                                                                                                                                                                  | 对本项目影响                                                     |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| 高   | **无 SSL Pinning 迹象**（未见 `pin-set`；OkHttp 内置 `CertificatePinner` 但业务未见调用）                                                                                            | 便于抓包验证链路归属（Task 1）                                         |
| 高   | **`<queries>`** **探测 Xposed Installer / Cydia Substrate**                                                                                                           | 注入方案存在被上报/降级风险                                             |
| 中   | 内置 `guardrail` + `desensitization` + `miclaw-cap-rsa-v1-public.pem` 内容/包校验                                                                                          | 自定义 Agent / MCP 可能被内容策略拦截                                  |
| 中   | `SidekickOsbotStartReceiver` exported 且**无权限**（`headless.action.START`）                                                                                             | 潜在低权限触发入口（需动态验证语义）                                         |
| 中   | `OSbotMessengerService` exported 且 Manifest 无 permission                                                                                                            | 依赖代码层 CallerVerifier                                       |
| 中   | osbot 通过 **OAuth** 正式集成小米运动健康数据（`com.aios.osbot.tools.mihealth.*`，端点 `account.ai.xiaomi.com/miot/auth/access_token?client_id=2882303761517619340`）                  | 两 App 已有官方数据通道，非仅本项目单向 Hook                                |
| 信息  | 权限极激进（`READ_SMS`/`WRITE_SMS`/`READ_IMEI`/`INSTALL_PACKAGES`/`CAPTURE_SECURE_VIDEO_OUTPUT`/`MANAGE_ACTIVITY_TASKS`/`TETHER_PRIVILEGED`/`ACCESS_BACKGROUND_LOCATION`） | 系统应用特权，非缺陷                                                 |
| 信息  | 未加壳、业务包名未混淆                                                                                                                                                         | **大幅降低本项目 Hook 定位成本**（对比 com.mi.health 的 `defpackage.oav`） |

### 9.3 网络资产归属（阶段 3）

| 域名                                  | IP（解析）                                                                                                               | CNAME                               | 归属                  |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------- | ----------------------------------- | ------------------- |
| `api.miclaw.xiaomi.net`             | 220.181.104.181 / 202.69.4.61                                                                                        | —                                   | 小米自建（北京）            |
| `miclaw.security.xiaomi.net`        | 220.181.52.116 / 202.69.4.24                                                                                         | `matrix-pub-c3-c4.alb.xiaomi.com`   | 小米云 ALB             |
| `audio.miclaw.xiaomi.net`           | 220.181.104.215 / 106.38.242.92                                                                                      | `mife-pub-prod.alb.xiaomi.com`      | 小米云 ALB             |
| `api.mify.mioffice.cn`              | 220.181.104.192 / 202.69.4.22                                                                                        | `mimo-pri-prod.alb.xiaomi.com`      | 小米云 ALB（MiMo 模型）    |
| `miclaw-ecology.developer.miui.com` | 220.181.104.253                                                                                                      | `appmarket-pri-prod.alb.xiaomi.com` | 小米云 ALB             |
| `hyperos.developer.xiaomi.com`      | 220.181.104.253                                                                                                      | `appmarket-pri-prod.alb.xiaomi.com` | 小米云 ALB             |
| `speech.ai.xiaomi.com`              | 119.147.123.239                                                                                                      | —                                   | 腾讯云 IDC（深圳）★本项目现用通道 |
| `account.ai.xiaomi.com`             | 39.102.218.21 / 124.251.100.45                                                                                       | —                                   | 阿里云 + 腾讯云           |
| `file.ai.xiaomi.com`                | 202.69.4.71 / 220.181.106.158                                                                                        | —                                   | 小米自建                |
| `cloudkit.micloud.xiaomi.net`       | 220.181.52.214                                                                                                       | `cname.micloud.xiaomi.net`          | 小米云                 |
| `resolver.mi.xiaomi.com`            | 110.43.0.170                                                                                                         | `...mgslb.com`                      | 小米 GSLB             |
| 区域路由                                | CN=`api.miclaw.xiaomi.net`、EU=`eu.api.miclaw.xiaomi.net`、IN=`in.api.miclaw.xiaomi.net`、US=`us.api.miclaw.xiaomi.net` | <br />                              | `bk/s.java:29-41`   |

***

## 十、验证计划（Implementation Plan）

> **执行约定**：按 checkbox 逐任务执行，每个 Task 独立可验证，失败即回滚。
> **前置环境**：已 Root + LSPosed 的小米/Redmi 真机；已安装 `com.mi.health` 与超级小爱 8.2.3.1619；已绑定手环；`adb` 在 PATH。
> **红线**：仅在本机授权设备上验证；不做服务端探测、不批量请求小米接口、不传播绕过手段。

**Goal:** 用最小代价确认「手环语音链路归属」与「voiceassist 进程内 LLM 出口可改道性」，据此决定第二作用域投入方向。

**Architecture:** 先验证（Task 1–3）→ 再选择 B1/B2/E 之一实现（Task 4–6）→ 最后回归（Task 7）。

**Tech Stack:** adb / LSPosed（libxposed api 102）/ Kotlin / mitmproxy（可选，仅本机回路）。

***

### Task 1: 确认手环语音链路归属（最高优先级，决定现有方案生死）

**Files:**

- Modify: `app/src/main/kotlin/llm/miband/littlewhite/hook/MiHealthHook.kt`（临时诊断日志，验证后回滚）

- [ ] **Step 1: 在拦截器入口加"命中"日志**

在 `onMessageIntercepted` 中 `val msg = WsMessage.parse(raw)` 之后插入：

```kotlin
// 诊断：记录每条经过本 Hook 的消息类型，用于确认手环语音是否仍走 com.mi.health AIVS
LogCollector.i(tag, "DIAG-AIVS ns=${msg.namespace} name=${msg.name} dlg=${msg.dialogId}")
```

- [ ] **Step 2: 构建并安装**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

预期：`Performing Streamed Install` → `Success`

- [ ] **Step 3: 手环发起一次语音提问并抓日志**

```bash
adb logcat -s RingOnLLM:V MiHealthHook:V | Select-String "DIAG-AIVS"
```

预期（链路仍归 com.mi.health）：看到 `DIAG-AIVS ns=SpeechRecognizer name=RecognizeResult` 与 `DIAG-AIVS ns=Template name=Toast`。

- [ ] **Step 4: 判定分支**

| 观察                       | 结论                | 下一步                      |
| ------------------------ | ----------------- | ------------------------ |
| 有 `DIAG-AIVS` 且手环显示回答    | 路径 C 仍有效          | 进入 Task 2                |
| **无** `DIAG-AIVS`，但手环有回答 | 链路已迁至 voiceassist | **直接跳 Task 3**，路径 C 判定失效 |
| 无 `DIAG-AIVS`、手环无回答      | 手环未联网/未绑定         | 排除环境问题后重试                |

- [ ] **Step 5: 回滚诊断日志**

删除 Step 1 代码，`./gradlew assembleDebug` 确认编译通过。

***

### Task 2: 确认 voiceassist 是否参与手环会话

**Files:** 无（纯观测）

- [ ] **Step 1: 记录提问前后的进程活跃度**

```bash
adb shell ps -A | Select-String "voiceassist|mi.health"
adb shell dumpsys activity broadcasts history | Select-String "osbot" | Select-Object -First 20
```

预期：确认 `com.miui.voiceassist` 及其 `:core`/`:provider` 子进程是否在手环提问瞬间活跃。

- [ ] **Step 2: 检查** **`speech.ai.xiaomi.com`** **连接归属**

```bash
adb shell "for p in $(pidof com.mi.health com.miui.voiceassist); do echo PID=$p; ss -tnp 2>/dev/null | grep $p | grep -E '443|speech'; done"
```

预期：明确 `speech.ai.xiaomi.com:443` 由哪个进程持有。若为 voiceassist，则 Task 1 的"链路迁移"分支成立。

- [ ] **Step 3: 归档结论**

把 Step 1–2 输出写入 `docs/reverse-notes.md` 新增章节「手环语音链路归属验证（日期）」，注明机型/ROM/两 App 版本。

***

### Task 3: 验证 LSPosed 注入 voiceassist 的可行性与稳定性

**Files:**

- Modify: `app/src/main/resources/META-INF/xposed/scope.list`（追加 `com.miui.voiceassist`）

- Create: `app/src/main/kotlin/llm/miband/littlewhite/hook/VoiceAssistProbe.kt`

- Modify: `app/src/main/kotlin/llm/miband/littlewhite/MainModule.kt`（按宿主分派）

- [ ] **Step 1: 扩展作用域** —— `scope.list` 追加一行 `com.miui.voiceassist`

- [ ] **Step 2: 写最小探针 Hook（只读日志，不改行为）**

```kotlin
package llm.miband.littlewhite.hook

import io.github.libxposed.api.XposedModule
import llm.miband.littlewhite.log.LogCollector

/**
 * 超级小爱（com.miui.voiceassist）探针：仅确认注入成功与 osbot 类可达，不修改任何行为。
 * 用于评估路径 B 的可行性；这些类名静态已确认未混淆，若 MISS 说明被 R8 改名。
 */
class VoiceAssistProbe(private val module: XposedModule, classLoader: ClassLoader) {
    private val tag = "VAProbe"
    fun install() {
        for (name in listOf(
            "com.aios.osbot.external.ExternalAgentService",
            "com.aios.osbot.llm.router.ModelConfig",
            "com.aios.osbot.agent.unified.AgentDefinition",
            "com.aios.apptoolsdk.ExternalAgentClient",
        )) {
            try { classLoader.loadClass(name); LogCollector.i(tag, "OK  $name") }
            catch (_: Throwable) { LogCollector.w(tag, "MISS $name") }
        }
    }
}
```

- [ ] **Step 3: 在** **`MainModule`** **中按宿主分派探针** —— 增加 `com.miui.voiceassist` 分支调用 `VoiceAssistProbe(this, classLoader).install()`

- [ ] **Step 4: 构建、安装、触发小爱一次，读日志**

```bash
./gradlew assembleDebug; adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c; adb logcat -s RingOnLLM:V | Select-String "VAProbe"
```

预期：4 行 `OK`。若出现 `MISS`，需按新类名重新定位。

- [ ] **Step 5: 稳定性观察** —— 连续触发小爱 20 次，并 `adb logcat -b crash | Select-String "voiceassist"`。预期无新增崩溃；**若崩溃立即从** **`scope.list`** **移除该作用域**并记录原因。

***

### Task 4: 实现路径 B1 —— LLM 出口改道（推荐首个落地功能）

**Files:**

- Create: `app/src/main/kotlin/llm/miband/littlewhite/hook/VoiceAssistHook.kt`

- Modify: `MainModule.kt`、`config/ConfigKeys.kt`（新增 `intercept_phone_xiaoai` 开关）

- [ ] **Step 1: 定位 LLM base\_url 读取点**

静态候选（Task 3 确认可加载后）：`bk.b.getMIFY_LLM_BASE_URL()`、`bk.b.getOPENAI_BASE_URL()`、`bk.b.getANTHROPIC_BASE_URL()`、`com.aios.osbot.llm.router.ModelConfig.getBaseUrl()`。

- [ ] **Step 2: Hook** **`ModelConfig.getBaseUrl()`** **返回我们配置的 base\_url**

```kotlin
// 把小爱手机端 LLM 请求改道到用户配置的 OpenAI 兼容端点
module.hook(getBaseUrl)
    .setPriority(XposedInterface.PRIORITY_DEFAULT)
    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
    .intercept(XposedInterface.Hooker { chain ->
        val url = config.getBaseUrl()          // 复用现有配置项
        if (config.isEnabled() && url.isNotBlank()) url else chain.proceed()
    })
```

- [ ] **Step 3: 同步 Hook** **`getApiKey()`** **与** **`getModelName()`**

与 `ConfigKeys.kt` 中 `model` / `api_key` 保持一致；`api_key` 经 `ApiKeyCipher` 解密后返回。

- [ ] **Step 4: 真机验证**

对小爱说"今天天气怎么样"。为可辨识，临时在系统提示词末尾加"回答最后追加 \[R1ng]"。
预期：手机端小爱回答带 `[R1ng]` ⇒ 改道成功。

- [ ] **Step 5: 异常隔离回归**

关闭模块总开关后重复 Step 4。预期：小爱恢复小米原始回答，无残留影响。

***

### Task 5: 验证路径 E —— MCP Server 挂载（零 Root 方案）

**Files:**

- Create: `example/mcp-deepseek-server/server.py`（独立最小 MCP 服务，非模块依赖）

- [ ] **Step 1: 实现暴露** **`ask_llm`** **工具的 MCP Streamable HTTP 服务**

工具契约：`{name:"ask_llm", inputSchema:{query:string}}` → 返回 DeepSeek 回答文本。

- [ ] **Step 2: 局域网内启动并自测**

```bash
python example/mcp-deepseek-server/server.py --port 8765
```

预期：`curl http://<lan-ip>:8765/mcp` 返回 MCP initialize 响应。

- [ ] **Step 3: 在手机小爱「设置 → MCP 服务」添加该 URL**

- [ ] **Step 4: 对小爱说"用 ask\_llm 工具问一下 1+1 等于几"**

预期：`onToolEvent` 路径被触发，回答来自 DeepSeek。

- [ ] **Step 5: 记录局限**

补充说明：小爱是否**稳定**选择调用该工具（非强制接管），据此判断是否满足产品需求。

***

### Task 6: 验证路径 B2 —— 自定义 Agent 落盘（可选进阶）

- [ ] **Step 1: 确认数据目录结构**

```bash
adb shell "su -c 'ls /data/data/com.miui.voiceassist/files'"
```

预期：看到 `agents/`、`mcp/`、`prompts/` 等目录（不存在则说明按需创建）。

- [ ] **Step 2: 放置最小 Agent 定义**

写入 `/data/data/com.miui.voiceassist/files/agents/com.mi.health/config.json`：

```json
{ "id":"com.mi.health", "name":"环上LLM", "enabled":true,
  "type":"builtin", "execution_mode":"main",
  "prompt_file":"prompt.md", "tools_allowlist":[], "preload_tools":[],
  "max_iterations":3, "version":"0.0.1" }
```

并放置同目录 `prompt.md`（含可识别的标记语句）。

- [ ] **Step 3: 重启小爱进程并验证 Agent 是否被枚举**

```bash
adb shell am force-stop com.miui.voiceassist
```

在 Agent 管理界面或日志中确认 `com.mi.health` 出现在目录中。

- [ ] **Step 4: 以同 UID 走 openSession 验证** **`agentId`** **命中**

在 `VoiceAssistHook` 内反射构造 `AppMeta{targetPackage="com.mi.health", bizId="ringonllm", featureId="chat"}` 并调用 `ExternalSessionManager.openSession`，观察是否仍返回 `AGENT_NOT_FOUND` / `20003`。

- [ ] **Step 5: 结论归档**

把 Step 4 的实际返回码写入本报告第六章「门禁 4」表格，标注"已真机验证 / 未通过"。

***

### Task 7: 回归与文档收口

- [ ] **Step 1: 全量回归**

```bash
./gradlew assembleRelease
```

预期：`BUILD SUCCESSFUL`。若新增 Hook 类，需同步 `app/proguard-rules.pro` 保留入口。

- [ ] **Step 2: 更新** **`AGENTS.md`** **的「当前状态」**

标注：是否已支持 voiceassist 作用域、路径 B1 是否可用。

- [ ] **Step 3: 更新** **`docs/reverse-notes.md`**

把本报告第四至六章的 voiceassist 侧结论以「混淆类名映射表」形式补录：

| 混淆名     | 真实身份                                                               | 依据                          |
| ------- | ------------------------------------------------------------------ | --------------------------- |
| `bk.b`  | GlobalConfig（所有 base\_url/host 的 setter+getter）                    | `getMIFY_LLM_BASE_URL` 等方法名 |
| `bk.q`  | `RegionConfig`（源码 SMAP 保留 `com/aios/osbot/config/RegionConfig.kt`） | `SourceDebugExtension`      |
| `bk.s`  | `RomRegionPolicy`（同上，SMAP 保留）                                      | `SourceDebugExtension`      |
| `hs.a`  | `CallerVerifier`（日志字面量 `CallerVerifier: 拒绝...`）                    | 日志字符串                       |
| `xk.c`  | osbot Agent Facade（实现 `com.aios.osbot.facade.b`）                   | `implements` 声明             |
| `rj.i0` | AgentManager（agent 目录内存表）                                          | `xk.c` 字段类型                 |
| `q92.b` | kotlinx-serialization `Json` 封装                                    | `getSerializersModule()`    |
| `s82.*` | kotlinx-coroutines（`k.launch`、`i.withContext`、`a1.delay`）          | 调用形态                        |

***

## 十一、结论与建议

1. **"接入手机端小爱"在技术上可行，但官方通道（路径 A）对第三方关闭** —— 四道门禁中，platform 签名与 Agent 目录预设是硬墙，且 `adb shell` 被显式拒绝。

2. **推荐主攻路径 B1（LLM 出口改道）**：小爱业务代码**未混淆**、**未加固**，`ModelConfig` 的 `provider/base_url/api_key/model_name/enable_thinking` 与本项目 `LlmClient` 完全同构，Hook 成本远低于当初在 `com.mi.health` 里定位 `defpackage.oav`。一次改道即可覆盖手机端全部小爱入口，收益远大于"只改手环 Toast"。

3. **强烈建议并行评估路径 E（MCP）**：这是**唯一不需要 Root、不需要绕过签名、不受 OTA 破坏**的官方扩展点。若产品诉求是"让小爱能用上我们的 LLM"而非"强制接管每一句回答"，MCP 是工程与合规双重最优解。

4. **立即执行 Task 1**：`com.xiaomi.aivsbluetoothsdk` 的存在表明小爱已具备直连手环能力，现有方案存在**静默失效风险**。这是本报告中最需要尽快证实/证伪的一条。

5. **风险提示**：Manifest 显式探测 Xposed/Substrate，且内置 guardrail 与包签名校验。注入方案应严格保持"异常隔离 + 只读优先 + 可一键停用"（沿用本项目现有 `ExceptionMode.PROTECTIVE` 与 `isEnabled()` 短路设计），避免影响系统核心语音组件稳定性。

***

## 十二、附录

### 12.1 工具版本

| 工具                           | 版本              | 用途                                          |
| ---------------------------- | --------------- | ------------------------------------------- |
| jadx                         | 1.5.6           | dex → java 反编译（`--no-res -j 8`，1090 处方法级失败） |
| apktool                      | 2.11.1          | Manifest/资源解码、smali                         |
| 7-Zip                        | 24.09           | 资产抽取                                        |
| keytool                      | JDK 25.0.3      | 签名证书指纹                                      |
| PowerShell `Resolve-DnsName` | Windows 11      | 域名 → IP/CNAME                               |
| androguard                   | 安装失败（本机 pip 缺失） | 未使用，由 jadx 结果替代                             |

### 12.2 产物路径

```
.pentest/
├── tools/{jadx/, apktool.jar}
├── static/
│   ├── decompiled/sources/     # 57,200 个 java
│   ├── apktool/AndroidManifest.xml
│   ├── raw/assets/             # agents/mcp/skills/prompts/tools/router/...
│   └── sig/META-INF/           # PLATFORM.RSA / PLATFORM.SF
├── jadx.log
└── apktool.log
```

### 12.3 未覆盖范围（诚实声明）

- ❌ **未做动态验证**：未安装、未运行、未抓包、未 Frida。所有"可绕过/可改道"结论均为**静态推导**，须以第十章 Task 实测为准。

- ❌ **未验证服务端二次校验**：`check-whitelist`、`user/control`、`bizId/featureId` 的真实计费校验在服务侧，静态无法判定。

- ❌ **未覆盖 1090 处 jadx 反编译失败方法**，其中可能含关键鉴权分支（如 `ExternalAgentService.executeSubmission`、`readAttachments` 未能完整还原为 Java）。

- ❌ **未分析 native so 内部逻辑**（`libmicontinuity_sdk.so`、`libowl.so`、`libafe_sdk.so` 等）。

- ❌ **未确认该 APK 的实际分发形态**（系统预装 vs 用户可更新），影响 OTA 存活率评估。

- ❌ **未测试** **`com.mi.health`** **与** **`com.miui.voiceassist`** **的运行时协作**（Task 1/2 待办）。

- ⚠️ 本报告为**技术可行性分析**，不构成法律或平台合规意见。对系统应用进行 Hook 可能违反厂商用户协议，请自行评估。

