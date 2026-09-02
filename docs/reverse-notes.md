# 小米运动健康 App (com.mi.health v3.58.0) 逆向分析笔记

> 生成时间: 2026-09-02
> 目标: 定位 WebSocket 客户端实现，为 LSPosed Hook 做准备

## 一、包名与版本确认

| 项目 | 值 |
|------|-----|
| 包名 | `com.mi.health` |
| versionName | 3.58.0 |
| versionCode | 358000 |
| minSdkVersion | 26 |
| targetSdkVersion | 35 |
| compileSdkVersion | 35 |
| usesCleartextTraffic | true |

## 二、OkHttp 版本

**版本: 4.10.0**

- 确认方式: `strings` 搜索 classes4.dex 发现 `okhttp/4.10.0`
- OkHttp 完整打包在 APK 中（classes4.dex），非引用外部库
- 代码风格为 Kotlin 风格（`$Companion`、`$connect$1` 等），符合 OkHttp 4.x 特征
- 关键类路径: `okhttp3/internal/ws/RealWebSocket`、`okhttp3/OkHttpClient$Builder` 等

## 三、WebSocket 客户端实现

### 3.1 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                   App 层                                     │
│  BluetoothAivsService / HuamiAivsService                     │
│  (com.xiaomi.fitness.aivs.*)                                 │
│         │                                                    │
│         ▼                                                    │
│  Engine SDK (com.xiaomi.ai.android.core.Engine)              │
│         │                                                    │
│  ┌──────┴──────┐                                             │
│  │ ConnectionCapabilityImpl  │                                │
│  └──────┬──────┘                                             │
│         │                                                    │
│  ┌──────▼──────────────────────────────────────────────┐     │
│  │  defpackage.nav (WSChannel)  ← 继承 c84             │     │
│  │  - 管理连接生命周期                                  │     │
│  │  - 选择 ws/wss/xmd 协议                              │     │
│  │  - 处理认证头                                        │     │
│  └──────┬──────────────────────────────────────────────┘     │
│         │ 创建 oav 实例并调用 oav.d(url, headers)              │
│  ┌──────▼──────────────────────────────────────────────┐     │
│  │  defpackage.oav (LiteCryptWsClient)                 │     │
│  │  extends okhttp3.WebSocketListener                  │     │
│  │  - 实际的 WebSocket 连接管理                         │     │
│  │  - 消息加密/解密 (zwf 加密器)                         │     │
│  │  - 心跳和重连逻辑                                    │     │
│  └─────────────────────────────────────────────────────┘     │
│         │                                                    │
│         ▼                                                    │
│  okhttp3.OkHttpClient.newWebSocket()                         │
│  + okhttp3.internal.ws.RealWebSocket                        │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 混淆类名映射

| 混淆类名 | 日志标签 | 真实用途 | 所在 DEX |
|---------|---------|---------|---------|
| `defpackage.oav` | `LiteCryptWsClient` | WebSocket 客户端（继承 WebSocketListener） | classes8.dex |
| `defpackage.nav` | `WSChannel` | WebSocket 通道封装 | classes8.dex |
| `defpackage.pwu` | — | 服务端 URL 配置 | classes8.dex |
| `defpackage.c84` | — | 通道基类（nav 的父类） | classes8.dex |
| `defpackage.z11` | — | AivsConfig 配置类 | classes8.dex |
| `defpackage.zwf` | — | 加密器（LiteCrypt） | classes8.dex |
| `defpackage.jne` | — | Instruction + 原始 JSON 包装 | classes8.dex |

### 3.3 oav (LiteCryptWsClient) 关键方法签名

```java
// ---- WebSocketListener 回调 ----
public void onOpen(WebSocket webSocket, Response response)
    // 连接建立成功，发送初始化事件（GlobalConfig）
    // 通过 nav.getListener().onConnected(nav) 通知上层

public void onMessage(WebSocket webSocket, String str)
    // 收到文本消息（核心入口）
    // 1. 解密（如果 zwf 加密器存在）
    // 2. APIUtils.readInstruction(str) → 解析为 Instruction
    // 3. 包装为 jne(instruction, rawJson)
    // 4. nav.getListener().onInstruction(nav, jne) 分发

public void onMessage(WebSocket webSocket, ByteString byteString)
    // 收到二进制消息
    // 解密后 → nav.getListener().onBinaryMessage(nav, byteArray)

public void onClosed(WebSocket webSocket, int i, String str)
public void onClosing(WebSocket webSocket, int i, String str)
public void onFailure(WebSocket webSocket, Throwable th, Response response)

// ---- 发送方法 ----
public boolean c(String str)          // 发送文本消息（加密后发送）
public boolean f(byte[] bArr)         // 发送二进制消息
public boolean d(String str, Map)     // 连接（阻塞模式，带超时等待）
public void a()                       // 关闭连接
```

### 3.4 WebSocket URL 配置

**类**: `defpackage.pwu` 中的 `o()` 方法

```java
// 生产环境 (aivs.env == 0)
wss://speech.ai.xiaomi.com/speech/v1.0/longaccess

// 预览环境 (aivs.env == 1)
wss://speech-preview.ai.xiaomi.com/speech/v1.0/longaccess

// Staging 环境 (aivs.env == 2)
ws://speech-staging.ai.xiaomi.com/speech/v1.0/longaccess

// Preview4Test 环境 (aivs.env == 3)
wss://preview4test-access-speech.ai.xiaomi.com/speech/v1.0/longaccess

// 海外环境 (connection.enable_abroad_url == true)
wss://tw.speech.ai.xiaomi.com/speech/v1.0/longaccess

// 也支持自定义 external_connect_url 覆盖
```

**注意**: `buildConfig()` 方法中 `connection.enable_lite_crypt = false`，且 `buildConfig()` 中 `aivs.env` 默认为 0（生产环境），所以实际连接的是 `wss://speech.ai.xiaomi.com/speech/v1.0/longaccess`。

### 3.5 消息处理链

```
oav.onMessage(WebSocket, String)
  │
  ├─ 解密 (zwf.liteCrypt 解密 → l82.f(zwfVar.l(2, ...), 10))
  │
  ├─ APIUtils.readInstruction(str) → Instruction<?>
  │
  ├─ 包装为 jne(instruction, rawJson)
  │
  ├─ nav.getListener().onInstruction(nav, jne)
  │
  ├─ (通过 Engine SDK 分发到 InstructionCapability)
  │
  ├─ BluetoothInstructionCapabilityImpl (蓝牙设备)
  │   ├── processSpeechRecognizer(instruction)     ← RecognizeResult
  │   │   ├── SpeechRecognizer.RecognizeResult → payload.getResults()
  │   │   └── recognizeResultItem.getText() → 识别文本
  │   ├── processTemplateToast(instruction)        ← Toast 回答文本
  │   │   └── Template.Toast.getText() → 回答文本
  │   └── processTemplateGeneral(instruction)      ← General 回答文本
  │       └── Template.General.getText() → 回答文本
  │
  └── HuamiInstructionCapabilityImpl (华米设备)
      ├── processSpeechRecognizer(instruction)     ← RecognizeResult
      └── 内联处理 Template.Toast (line 398-406)
```

### 3.6 RecognizeResult 处理

**Bluetooth 实现** (`BluetoothInstructionCapabilityImpl.java`):
- 方法: `processSpeechRecognizer(Instruction<?> instruction)` (line 856)
- 从 `SpeechRecognizer.RecognizeResult` 获取 `results` 列表
- 遍历 `results` 获取 `recognizeResultItem.getText()` → 识别文本
- 检查 `recognizeResult.isFinal()` 区分最终/中间结果
- 最终结果通过 `sendAivsInstruction(m21Var)` 发送到设备

**Huami 实现** (`HuamiInstructionCapabilityImpl.java`):
- 方法: `processSpeechRecognizer(Instruction<?> instruction)` (line 166)
- 类似处理逻辑

### 3.7 Toast 回答文本处理

**Bluetooth 实现** (`BluetoothInstructionCapabilityImpl.java`):
- 方法: `processTemplateToast(Instruction<?> instruction)` (line 1274)
- `Template.Toast.getText()` → 回答文本
- 解码后 → `sendAivsInstruction(m21Var)` 发给设备

**Huami 实现** (`HuamiInstructionCapabilityImpl.java`):
- 在 `processTemplateGeneral` 中处理 (line 398-406)
- `Template.General.getText()` 结合 `Template.Toast.getText()` 作为回答文本
- 通过 `sendVoiceCaption(text)` 发送到设备

## 四、SSL Pinning 情况

### 4.1 network_security_config.xml

**文件**: `res/8GD.xml` (资源 ID: `0x7f190015`)

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <!-- 允许明文流量的域名白名单，包括: -->
        <domain includeSubdomains="true">access.speech.ai.xiaomi.com</domain>
        <domain includeSubdomains="true">preview4test.access.speech.ai.xiaomi.com</domain>
        <domain includeSubdomains="true">account.xiaomi.com</domain>
        <!-- ... 其他域名 ... -->
    </domain-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />    <!-- 调试模式下信任用户证书，可抓包 -->
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

**结论: 无证书 Pinning (SSL Pinning 未启用)**

- `network_security_config.xml` 中仅配置了 `trust-anchors`，没有 `pin-set`
- OkHttp 的 `CertificatePinner` 类存在（库自带），但 App 代码未使用
- debug-overrides 信任用户证书，可通过抓包工具（如 Charles/mitmproxy）拦截 HTTPS 流量

## 五、小爱语音模块相关类

### 5.1 核心类

| 类名 | 说明 |
|------|------|
| `com.xiaomi.fitness.aivs.base.BaseAivsService` | 语音服务抽象基类 |
| `com.xiaomi.fitness.aivs.bluetooth.BluetoothAivsService` | 蓝牙设备语音服务实现 |
| `com.xiaomi.fitness.aivs.huami.HuamiAivsService` | 华米设备语音服务实现 |
| `com.xiaomi.fitness.aivs.base.BaseAivsSpeechRecognizerManager` | 语音识别管理器基类 |
| `com.xiaomi.fitness.aivs.bluetooth.BluetoothSpeechRecognizerManager` | 蓝牙语音识别管理器 |
| `com.xiaomi.fitness.aivs.huami.HuamiSpeechRecognizerManager` | 华米语音识别管理器 |
| `com.xiaomi.fitness.aivs.base.BaseEventSession` | 音频事件会话（数据采集线程） |
| `com.xiaomi.fitness.aivs.capability.ConnectionCapabilityImpl` | 连接能力实现 |
| `com.xiaomi.fitness.aivs.capability.ErrorCapabilityImpl` | 错误处理能力实现 |
| `com.xiaomi.fitness.aivs.init.AivsComponent` | AIVS 组件初始化入口 |
| `com.xiaomi.fitness.aivs.bluetooth.BluetoothInstructionCapabilityImpl` | 蓝牙指令处理（含 RecognizeResult / Toast 处理） |
| `com.xiaomi.fitness.aivs.huami.HuamiInstructionCapabilityImpl` | 华米指令处理（含 RecognizeResult / Toast 处理） |
| `com.xiaomi.fitness.aivs.request.AivsRequest` | AIVS 网络请求 |
| `com.xiaomi.fitness.aivs.util.AivsPreferenceSupport` | AIVS 偏好存储 |

### 5.2 SDK 类

| 类名 | 说明 |
|------|------|
| `com.xiaomi.ai.android.core.Engine` | 小米 AI SDK 引擎核心 |
| `com.xiaomi.ai.api.SpeechRecognizer` | 语音识别 API（含 RecognizeResult、ExpectSpeech 等内部类） |
| `com.xiaomi.ai.api.Template` | 模板 API（含 Toast、General、PlayInfo 等内部类） |
| `com.xiaomi.ai.api.common.APIUtils` | 消息工具类（`readInstruction` 解析 JSON） |
| `com.xiaomi.ai.api.Settings` | 设置 API（含 ConnectionChallenge 等） |
| `com.xiaomi.ai.api.AIApiConstants` | API 常量定义 |

## 六、LSPosed Hook 建议

### 方案 A: Hook WebSocket 消息入口（推荐）

**目标**: `defpackage.oav` (LiteCryptWsClient)

```java
// Hook onMessage 文本消息
// 参数: (WebSocket, String)
// 在 onMessage 处理前获取原始 JSON 消息
// 注意: 消息可能经过 LiteCrypt 加密，需确认 connection.enable_lite_crypt 状态
```

**优点**: 在消息进入 Engine SDK 前拦截，可以看到所有消息
**缺点**: 消息可能被 LiteCrypt 加密; 需要处理解密逻辑

### 方案 B: Hook Instruction 处理层（推荐）

**目标**: `BluetoothInstructionCapabilityImpl` 的 `processSpeechRecognizer` 和 `processTemplateToast`

```java
// Hook RecognizeResult 处理
// 方法: processSpeechRecognizer(Instruction<?>)
// target: com.xiaomi.fitness.aivs.bluetooth.BluetoothInstructionCapabilityImpl
// 参数: Instruction → 从 payload 获取 RecognizeResult → results → getText()

// Hook Toast 回答文本处理
// 方法: processTemplateToast(Instruction<?>)
// target: com.xiaomi.fitness.aivs.bluetooth.BluetoothInstructionCapabilityImpl
// 参数: Instruction → payload → Template.Toast → getText()
```

**优点**: 消息已解密，直接获取结构化数据
**缺点**: 需要区分蓝牙/华米两种实现

### 方案 C: Hook APIUtils.readInstruction（通用）

**目标**: `com.xiaomi.ai.api.common.APIUtils.readInstruction(String)`

```java
// 参数: String json
// 返回值: Instruction<?>
// 在返回前检查 instruction.getFullName() 判断消息类型
```

**优点**: 单点 Hook，覆盖所有消息类型
**缺点**: 需要自行解析 JSON 判断类型

### 消息结构验证

**RecognizeResult 消息**:
```json
{
  "header": {
    "namespace": "SpeechRecognizer",
    "name": "RecognizeResult",
    "dialog_id": "..."
  },
  "payload": {
    "is_final": true,
    "results": [{"origin_text": "..."}]
  }
}
```

**Toast 消息**:
```json
{
  "header": {
    "namespace": "Template",
    "name": "Toast",
    "dialog_id": "..."
  },
  "payload": {
    "text": "回答内容"
  }
}
```

## 七、反编译命令记录

```bash
# 1. 解包 APK
jar xf device_mihealth.apk

# 2. 提取 DEX 字符串（用于快速搜索关键词）
dexdump -s classes.dex > classes.strings.txt

# 3. 使用 jadx 反编译特定 DEX（需设置 JADX_CONFIG_DIR 避免沙箱冲突）
set JADX_CONFIG_DIR=temp\jadx_config
set JADX_CACHE_DIR=temp\jadx_cache
jadx --no-res --threads-count 4 -d temp\jadx_classes8 classes8.dex

# 4. 查看 AndroidManifest 和资源文件
aapt2 dump badging device_mihealth.apk
aapt2 dump xmltree device_mihealth.apk --file AndroidManifest.xml

# 5. 查找 network_security_config
aapt2 dump resources device_mihealth.apk | findstr "network_security_config"
aapt2 dump xmltree device_mihealth.apk --file res/8GD.xml
```

## 八、注意事项

1. 所有关键类名都被混淆（`defpackage` 包），但日志标签揭示了真实名称
2. 蓝牙设备（如小米手表）使用 `BluetoothAivsService`，华米设备（如 Amazfit）使用 `HuamiAivsService`
3. WebSocket 使用 `wss://speech.ai.xiaomi.com/speech/v1.0/longaccess`（生产环境）
4. 加密模式 `connection.enable_lite_crypt` 默认为 `false`（明文传输）
5. 认证使用 OAuth 2.0 + client_id/client_secret 模式
6. 应用通过 `debug-overrides` 信任用户证书，HTTPS 抓包不需要额外配置