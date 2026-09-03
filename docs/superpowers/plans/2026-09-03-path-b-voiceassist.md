# 手环请求接入手机端小爱处理（路径 B · osbot 复用版）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让小米手环的语音提问由\*\*手机端超级小爱（`com.miui.voiceassist` 内的 osbot/miclaw Agent）\*\*处理并把回答回投手环显示，**不使用用户自配的 DeepSeek/OpenAI API**。

**Architecture:** 模块同时注入两个宿主进程。`com.mi.health` 侧沿用现有 AIVS WebSocket Hook 拿到「用户识别文本」，但把「生成回答」这一步从 `LlmClient` 改为通过 **localhost TCP 桥**请求 `com.miui.voiceassist` 主进程；voiceassist 侧的注入代码以**同 UID**反射调用宿主自带的 `com.aios.apptoolsdk.ExternalAgentClient`（`agentId = osbot.main`），拿到小爱 Agent 的完整回答后经 TCP 回传，`com.mi.health` 侧再用既有的 `replaceToastText` 改写 `Template/Toast.payload.text` 投回手环。全程零外部 API、零计费配置。

**Tech Stack:** Kotlin / Modern Xposed API 102（`io.github.libxposed.api`）/ `java.net.ServerSocket`+`Socket`（localhost 桥）/ 反射宿主 `ExternalAgentClient`（`java.lang.reflect.Proxy` 实现其回调接口）/ kotlinx-serialization（沿用）/ 真机 Root+LSPosed 验证。

**背景依据（`docs/xiaoai_phone_integration_feasibility.md`）：**

- voiceassist 业务类 `com.aios.apptoolsdk.*`、`com.aios.osbot.*` **未混淆、未加壳**，可 `loadClass` + 反射；

- `ExternalAgentService` 有**同 UID 旁路**：`Binder.getCallingUid() == Process.myUid()` 时 `callerPkg = getPackageName()`、`agentPkg = AppMeta.targetPackage`，故注入进程内 `bindService` 自身可过 `CallerVerifier`（voiceassist 为 `FLAG_SYSTEM` + platform 签名）；

- `agentId = buildAgentId(agentPackage, tag)`，传 `targetPackage="osbot.main"`、`tag=""` ⇒ `agentId="osbot.main"`，命中主对话 Agent（`getAgentMeta` 非空）；

- `submit` 仅校验 `bizId/featureId` **非空**（客户端侧），填固定串即可过本地检查（服务端二次校验待真机验证）。

***

## 端到端数据流

```
[手环] 语音提问
   │ 蓝牙 RCSP
   ▼
[com.mi.health] AIVS WebSocket
   │  oav.onMessage / APIUtils.readInstruction  ← 现有 Hook
   ├─ RecognizeResult(is_final) → 缓存 pendingQueries[dialogId] = 用户文本   （现有）
   └─ Template/Toast 到达 → replaceToastBlocking()                          （现有）
          │
          │  ★新增分支：usePhoneXiaoai == true
          ▼
   XiaoaiAgentClient.ask(query)  ── TCP 127.0.0.1:43997 ──┐
          ▲                                                │
          │  answer                                        ▼
          └────────────── TCP ──────────────  [com.miui.voiceassist 主进程]
                                                        XiaoaiAgentServer
                                                          │ 反射 ExternalAgentClient
                                                          │  connect() → openSession(
                                                          │    AppMeta{targetPackage="osbot.main",
                                                          │            bizId="ringonllm",
                                                          │            featureId="chat"})
                                                          │  submit(sessionId, query, cb)
                                                          │  cb.onComplete → answer
                                                          ▼
                                                   osbot.main（MiMo 模型 + 工具 + 记忆）
   │
   ▼
replaceToastText(raw, answer) → chain.proceed(改写后的 JSON) → 手环显示小爱 Agent 回答
```

**降级策略：** 任一环节失败（voiceassist 未装/未起 server/连接超时/osbot 报错）→ `ask` 返回 null → 放行小爱原始 Toast 回答（或按现有配置回退 `LlmClient`），**绝不让手环无回答**。

***

## 文件结构（本计划新增 / 修改）

| 文件                                                                        | 责任                                                | 操作 |
| ------------------------------------------------------------------------- | ------------------------------------------------- | -- |
| `app/src/main/resources/META-INF/xposed/scope.list`                       | 追加 `com.miui.voiceassist` 作用域                     | 修改 |
| `app/src/main/kotlin/llm/miband/littlewhite/MainModule.kt`                | 多宿主分发（mi.health + voiceassist）                    | 修改 |
| `app/src/main/kotlin/llm/miband/littlewhite/config/ConfigKeys.kt`         | 新增 `KEY_USE_PHONE_XIAOAI` 键与默认值                   | 修改 |
| `app/src/main/kotlin/llm/miband/littlewhite/config/ConfigStore.kt`        | 新增该开关读写器                                          | 修改 |
| `app/src/main/kotlin/llm/miband/littlewhite/hook/Bridge.kt`               | TCP 桥协议常量 + 帧读写工具（两端共用）                           | 新增 |
| `app/src/main/kotlin/llm/miband/littlewhite/hook/XiaoaiAgentClient.kt`    | mi.health 侧：socket 客户端 `ask(query): String?`      | 新增 |
| `app/src/main/kotlin/llm/miband/littlewhite/hook/XiaoaiAgentServer.kt`    | voiceassist 侧：socket 服务端 + 反射 ExternalAgentClient | 新增 |
| `app/src/main/kotlin/llm/miband/littlewhite/hook/VoiceAssistHook.kt`      | voiceassist 侧入口：仅主进程启动 server                     | 新增 |
| `app/src/main/kotlin/llm/miband/littlewhite/hook/WebSocketInterceptor.kt` | `handleToast` 增加 phone 分支                         | 修改 |
| `app/src/main/kotlin/llm/miband/littlewhite/hook/MiHealthHook.kt`         | `replaceToastBlocking` 在 phone 模式跳过 apiKey 前置检查   | 修改 |
| `app/src/main/kotlin/llm/miband/littlewhite/ui/SettingsScreen.kt`         | 新增「用手端小爱回答」开关                                     | 修改 |
| `app/proguard-rules.pro`                                                  | 保留新增 Hook 类                                       | 修改 |

**决策说明：**

- **复用现有** **`pendingQueries`** **/** **`replaceToastText`** **/** **`CountDownLatch`** **机制**，只把「回答来源」从 `LlmClient.ask` 换成 `XiaoaiAgentClient.ask`，改动面最小（DRY）。

- **桥选用 localhost TCP** 而非 exported 组件：`com.mi.health` 无 platform 签名，无法直接 bind voiceassist 的 `ExternalAgentService`（会被 `CallerVerifier` 拒），但注入代码在 voiceassist 进程内以同 UID 调用即可；两进程同机同用户，127.0.0.1 互通，无需任何权限。

- **反射宿主** **`ExternalAgentClient`** 而非自写 AIDL 桩：宿主该类未混淆、契约由小米维护，反射 + `Proxy` 回调代码量最小；若后续宿主混淆该类，再退到自写 `IExternalAgentService` transact 桩（见风险）。

***

### Task 0: 环境与基线确认

**Files:** 无

- [ ] **Step 1: 基线构建通过**

```bash
./gradlew assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 2: 确认待改文件存在**

```bash
Test-Path "app\src\main\resources\META-INF\xposed\scope.list"
Test-Path "app\src\main\kotlin\llm\miband\littlewhite\MainModule.kt"
Test-Path "app\src\main\kotlin\llm\miband\littlewhite\hook\WebSocketInterceptor.kt"
Test-Path "app\src\main\kotlin\llm\miband\littlewhite\hook\MiHealthHook.kt"
```

预期：全部 `True`。

- [ ] **Step 3: 确认宿主反射契约未随版本变化（只读反编译产物）**

```bash
Select-String -Path ".pentest\static\decompiled\sources\com\aios\apptoolsdk\ExternalAgentClient.java" -Pattern "public .*connect\(|public String openSession|public void submit|public static ExternalAgentClient create" | Select-Object -First 8
```

预期：命中 `create`、`connect`、`openSession`、`submit` 四个方法签名。若缺失，暂停并重新定位。

- [ ] **Step 4: 确认 AppMeta.Builder 方法名**

```bash
Select-String -Path ".pentest\static\decompiled\sources\com\aios\apptoolsdk\AppMeta.java" -Pattern "public Builder (targetPackage|bizId|featureId|appName)|public AppMeta build" | Select-Object -First 8
```

预期：命中 `targetPackage/bizId/featureId/appName/build`。

***

### Task 1: 扩展作用域与主模块多宿主分发

**Files:**

- Modify: `app/src/main/resources/META-INF/xposed/scope.list`

- Modify: `app/src/main/kotlin/llm/miband/littlewhite/MainModule.kt`

- [ ] **Step 1: 追加小爱作用域**

`scope.list` 最终内容：

```
com.mi.health
com.miui.voiceassist
```

- [ ] **Step 2: 重写** **`onPackageLoaded`** **为按包名分发**

将 `MainModule.kt` 现有 `onPackageLoaded`（第 40-64 行）**整体替换**为：

```kotlin
    /**
     * 包加载回调：按宿主包名分发安装对应 Hook。
     * - com.mi.health        -> MiHealthHook（手环 AIVS 拦截 + 回答替换，既有）
     * - com.miui.voiceassist -> VoiceAssistHook（作为手机端小爱回答引擎，本次新增）
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_MI_HEALTH && param.packageName != TARGET_VOICE_ASSIST) return
        log(Log.INFO, TAG, "目标包已加载: ${param.packageName}, process=${param.processName}")

        ensureInitialized()
        hostContext()?.let { LogCollector.init(it) }
        LlmClient.setHostContext(hostContext())

        val cfg = config
        if (cfg == null) {
            log(Log.ERROR, TAG, "配置初始化失败，无法安装 Hook")
            return
        }
        try {
            val classLoader = param.getDefaultClassLoader()
            when (param.packageName) {
                TARGET_MI_HEALTH -> MiHealthHook(this, cfg, classLoader).install()
                TARGET_VOICE_ASSIST -> VoiceAssistHook(this, cfg, classLoader, param.processName).install()
            }
            log(Log.INFO, TAG, "${param.packageName} Hook 安装流程已触发")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "${param.packageName} Hook 安装异常", t)
        }
    }
```

- [ ] **Step 3: 更新 companion 常量**

将 `MainModule.kt` 的 `companion object`（第 106-109 行）替换为：

```kotlin
    private companion object {
        const val TAG = "环上LLM"
        /** 小米运动健康（手环 AIVS 宿主） */
        const val TARGET_MI_HEALTH = "com.mi.health"
        /** 超级小爱（手机端小爱回答引擎宿主） */
        const val TARGET_VOICE_ASSIST = "com.miui.voiceassist"
    }
```

- [ ] **Step 4: 新增 import**

`MainModule.kt` 顶部 import 区追加：

```kotlin
import llm.miband.littlewhite.hook.VoiceAssistHook
```

**注意：** 此时 `VoiceAssistHook` 尚未创建，编译报 `unresolved reference` 属预期，Task 5 创建后消除。Task 1 与 Task 5 合并提交。

- [ ] **Step 5: 验证分发逻辑（允许缺失类报错）**

```bash
./gradlew compileDebugKotlin 2>&1 | Select-String "MainModule.kt|error:" | Select-Object -First 20
```

预期：错误仅关联 `VoiceAssistHook`，`MainModule` 本身无语法错误。

- [ ] **Step 6: Commit（与 Task 5 合并，见该任务末尾）**

***

### Task 2: 新增「用手端小爱回答」配置开关

**Files:**

- Modify: `app/src/main/kotlin/llm/miband/littlewhite/config/ConfigKeys.kt`

- Modify: `app/src/main/kotlin/llm/miband/littlewhite/config/ConfigStore.kt`

- Modify: `app/src/main/kotlin/llm/miband/littlewhite/ui/SettingsScreen.kt`

- [ ] **Step 1: ConfigKeys.kt 新增键与默认值**

在 `KEY_INTERCEPT_GENERAL` 声明之后追加键名：

```kotlin
    /** 是否把手环提问交给手机端超级小爱（osbot）处理；开启后不再使用自配 API */
    const val KEY_USE_PHONE_XIAOAI = "use_phone_xiaoai"
```

在 `DEFAULT_INTERCEPT_GENERAL` 之后追加默认值：

```kotlin
    /** 用手端小爱回答：默认关闭（保持现有 DeepSeek 行为，用户显式开启才走小爱） */
    const val DEFAULT_USE_PHONE_XIAOAI = false
```

- [ ] **Step 2: ConfigStore.kt 新增读取器与写入器**

在 `getInterceptGeneral()` 之后追加读取器：

```kotlin
    /** 是否把手环提问交给手机端超级小爱（osbot）处理 */
    fun getUsePhoneXiaoai(): Boolean =
        prefs.getBoolean(ConfigKeys.KEY_USE_PHONE_XIAOAI, ConfigKeys.DEFAULT_USE_PHONE_XIAOAI)
```

在 `setInterceptGeneral()` 之后追加写入器：

```kotlin
    /** 设置是否用手端小爱回答 */
    fun setUsePhoneXiaoai(v: Boolean) {
        prefs.edit().putBoolean(ConfigKeys.KEY_USE_PHONE_XIAOAI, v).apply()
    }
```

- [ ] **Step 3: SettingsScreen.kt 新增开关（位置：ConfigTabContent 分组 1，紧随「启用模块」开关块之后、`var apiTypeIndex`** **之前）**

```kotlin
                        var usePhoneXiaoai by remember { mutableStateOf(config.getUsePhoneXiaoai()) }
                        SwitchPreference(
                            title = "用手端小爱回答",
                            summary = "开启后手环提问转交手机端超级小爱(osbot)处理，无需配置 API；关闭则用上方 Base URL/模型",
                            checked = usePhoneXiaoai,
                            onCheckedChange = {
                                usePhoneXiaoai = it
                                config.setUsePhoneXiaoai(it)
                            },
                        )
```

- [ ] **Step 4: 验证编译（允许 VoiceAssistHook 缺失报错）**

```bash
./gradlew compileDebugKotlin 2>&1 | Select-String "error:" | Select-Object -First 20
```

预期：除 `VoiceAssistHook` 缺失外无新增错误。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/config/ConfigKeys.kt \
        app/src/main/kotlin/llm/miband/littlewhite/config/ConfigStore.kt \
        app/src/main/kotlin/llm/miband/littlewhite/ui/SettingsScreen.kt
git commit -m "feat(config): 新增用手端小爱回答开关 (路径B-osbot)"
```

***

### Task 3: 实现 TCP 桥协议工具 Bridge.kt（两端共用）

**Files:**

- Create: `app/src/main/kotlin/llm/miband/littlewhite/hook/Bridge.kt`

- [ ] **Step 1: 编写 Bridge.kt**

新建 `hook/Bridge.kt`：

```kotlin
package llm.miband.littlewhite.hook

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 手环(mi.health) <-> 手机端小爱(voiceassist) 的 localhost TCP 桥协议。
 * 帧格式：先写 Int(字节数)，再写该长度的 UTF-8 字节；双向同构。
 * 握手：客户端先发 magic 帧，服务端校验后才接收 query，防本机其它进程误连。
 */
object Bridge {
    /** 监听端口（高位端口，避开常见占用） */
    const val PORT = 43997

    /** 握手口令（两端注入代码内置同一常量即可，非安全边界，仅防误连） */
    const val MAGIC = "R1ng0nLLM-XIAOAI-BRIDGE-v1"

    /** 写一帧：长度前缀 + UTF-8 内容 */
    fun writeFrame(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** 读一帧：长度前缀 + UTF-8 内容 */
    fun readFrame(input: DataInputStream): String {
        val len = input.readInt()
        require(len in 0..(4 * 1024 * 1024)) { "帧长度非法: $len" }
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 客户端一次请求：握手 -> 发 query -> 收 answer。
     * @param timeoutMs 连接与读超时
     * @return 小爱回答文本；任何异常返回 null（调用方据此降级）
     */
    fun requestAnswer(query: String, timeoutMs: Int): String? = try {
        Socket().use { socket ->
            val t = timeoutMs.coerceAtLeast(1000)
            socket.connect(InetSocketAddress("127.0.0.1", PORT), t)
            socket.soTimeout = t
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            writeFrame(out, MAGIC)
            if (readFrame(input) != "READY") return null
            writeFrame(out, query)
            readFrame(input)
        }
    } catch (_: Throwable) {
        null
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin 2>&1 | Select-String "Bridge.kt|error:" | Select-Object -First 15
```

预期：`Bridge.kt` 无错误（`VoiceAssistHook` 缺失报错仍在，属正常）。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/hook/Bridge.kt
git commit -m "feat(hook): 新增手环-手端小爱 TCP 桥协议 Bridge"
```

***

### Task 4: 实现 XiaoaiAgentServer —— voiceassist 侧反射 osbot（回答引擎）

**Files:**

- Create: `app/src/main/kotlin/llm/miband/littlewhite/hook/XiaoaiAgentServer.kt`

- [ ] **Step 1: 编写 XiaoaiAgentServer.kt**

新建 `hook/XiaoaiAgentServer.kt`。它在 voiceassist 主进程内起 TCP server，收到 query 后以同 UID 反射宿主 `ExternalAgentClient` 走 `osbot.main` 会话拿回答：

```kotlin
@file:Suppress("unused")

package llm.miband.littlewhite.hook

import android.content.Context
import llm.miband.littlewhite.log.LogCollector
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * voiceassist 主进程内的回答引擎服务端：
 * 1) 启动时反射宿主 ExternalAgentClient 并 connect（同 UID，可过 CallerVerifier）；
 * 2) 起 localhost TCP server，收到 query 后 openSession(osbot.main)+submit，
 *    等 onComplete 拿小爱 Agent 完整回答回传。
 * 全程异常隔离，失败回传空串由客户端降级。
 */
object XiaoaiAgentServer {

    private const val TAG = "XiaoaiAgentServer"
    private const val CLIENT_CLASS = "com.aios.apptoolsdk.ExternalAgentClient"
    private const val APPMETA_CLASS = "com.aios.apptoolsdk.AppMeta"
    /** 主对话 Agent：targetPackage=osbot.main + tag 空 => agentId="osbot.main" */
    private const val AGENT_TARGET = "osbot.main"
    private const val BIZ_ID = "ringonllm"
    private const val FEATURE_ID = "chat"
    private const val APP_NAME = "环上LLM"
    /** 单次 osbot 生成等待上限（Agent 可能多轮工具调用，给足时间） */
    private const val OSBOT_WAIT_MS = 25_000L

    @Volatile
    private var started = false

    private var clientClass: Class<*>? = null
    private var appMetaClass: Class<*>? = null
    private var client: Any? = null

    fun start(classLoader: ClassLoader, context: Context) {
        if (started) return
        started = true
        try {
            clientClass = classLoader.loadClass(CLIENT_CLASS)
            appMetaClass = classLoader.loadClass(APPMETA_CLASS)
            connectClient(classLoader, context)
            Thread({ acceptLoop() }, "XiaoaiBridgeServer").apply { isDaemon = true }.start()
            LogCollector.i(TAG, "TCP 桥服务端已启动，osbot 客户端连接=${client != null}")
        } catch (t: Throwable) {
            LogCollector.e(TAG, "启动失败（不影响手环原始回答）", t)
        }
    }

    /** 反射创建并连接 ExternalAgentClient，等待 onConnected */
    private fun connectClient(classLoader: ClassLoader, context: Context) {
        val cc = clientClass ?: return
        val instance = cc.getMethod("create", Context::class.java).invoke(null, context)
        val listenerIface = cc.declaredClasses.first { it.simpleName == "ConnectionListener" }
        val connected = CountDownLatch(1)
        val listener = Proxy.newProxyInstance(
            classLoader, arrayOf(listenerIface),
            InvocationHandler { _, method, _ ->
                if (method.name == "onConnected") connected.countDown()
                null
            },
        )
        cc.getMethod("setConnectionListener", listenerIface).invoke(instance, listener)
        cc.getMethod("connect").invoke(instance)
        val ok = connected.await(5, TimeUnit.SECONDS)
        client = if (ok) instance else null
        if (!ok) LogCollector.w(TAG, "ExternalAgentClient 连接超时，osbot 通道不可用")
    }

    private fun acceptLoop() {
        try {
            ServerSocket(Bridge.PORT, 10, InetAddress.getByName("127.0.0.1")).use { server ->
                while (true) {
                    val socket = try { server.accept() } catch (_: Throwable) { continue }
                    Thread({ handle(socket) }, "XiaoaiBridgeConn").apply { isDaemon = true }.start()
                }
            }
        } catch (t: Throwable) {
            LogCollector.e(TAG, "accept 循环异常退出", t)
        }
    }

    /** 单连接：握手 -> 读 query -> 问 osbot -> 写 answer */
    private fun handle(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = (OSBOT_WAIT_MS + 5_000).toInt()
                val input = DataInputStream(socket.getInputStream())
                val out = DataOutputStream(socket.getOutputStream())
                if (Bridge.readFrame(input) != Bridge.MAGIC) return
                Bridge.writeFrame(out, "READY")
                val query = Bridge.readFrame(input)
                val answer = askOsbot(query) ?: ""
                Bridge.writeFrame(out, answer)
            }
        } catch (t: Throwable) {
            LogCollector.w(TAG, "连接处理异常: ${t.message}")
        }
    }

    /** 反射走 ExternalAgentClient 的 openSession + submit，阻塞取 onComplete 文本 */
    private fun askOsbot(query: String): String? {
        val cc = clientClass ?: return null
        val c = client ?: return null
        val metaClass = appMetaClass ?: return null
        if (query.isBlank()) return null
        try {
            val builderClass = metaClass.declaredClasses.first { it.simpleName == "Builder" }
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("appName", String::class.java).invoke(builder, APP_NAME)
            builderClass.getMethod("targetPackage", String::class.java).invoke(builder, AGENT_TARGET)
            builderClass.getMethod("bizId", String::class.java).invoke(builder, BIZ_ID)
            builderClass.getMethod("featureId", String::class.java).invoke(builder, FEATURE_ID)
            val meta = builderClass.getMethod("build").invoke(builder)

            val sessionId = cc.getMethod(
                "openSession", metaClass, Boolean::class.javaPrimitiveType,
            ).invoke(c, meta, false) as? String
            if (sessionId.isNullOrEmpty() || sessionId.startsWith("error:")) {
                LogCollector.w(TAG, "openSession 失败: $sessionId")
                return null
            }

            val callbackIface = cc.declaredClasses.first { it.simpleName == "Callback" }
            val latch = CountDownLatch(1)
            val answerRef = AtomicReference<String?>(null)
            val cb = Proxy.newProxyInstance(
                cc.classLoader, arrayOf(callbackIface),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onComplete" -> { answerRef.set(args?.getOrNull(1) as? String); latch.countDown() }
                        "onError" -> { answerRef.set(null); latch.countDown() }
                    }
                    null
                },
            )
            cc.getMethod("submit", String::class.java, String::class.java, callbackIface)
                .invoke(c, sessionId, query, cb)
            val done = latch.await(OSBOT_WAIT_MS, TimeUnit.MILLISECONDS)
            try {
                cc.getMethod("closeSession", String::class.java).invoke(c, sessionId)
            } catch (_: Throwable) {
            }
            if (!done) LogCollector.w(TAG, "osbot 生成超时")
            return answerRef.get()?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            LogCollector.e(TAG, "askOsbot 异常", t)
            return null
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin 2>&1 | Select-String "XiaoaiAgentServer.kt|error:" | Select-Object -First 15
```

预期：`XiaoaiAgentServer.kt` 无错误。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/hook/XiaoaiAgentServer.kt
git commit -m "feat(hook): voiceassist 侧反射 osbot.main 的 TCP 桥服务端"
```

***

### Task 5: 实现 VoiceAssistHook 入口与 XiaoaiAgentClient

**Files:**

- Create: `app/src/main/kotlin/llm/miband/littlewhite/hook/VoiceAssistHook.kt`

- Create: `app/src/main/kotlin/llm/miband/littlewhite/hook/XiaoaiAgentClient.kt`

- [ ] **Step 1: 编写 VoiceAssistHook.kt（voiceassist 侧入口，仅主进程起 server）**

```kotlin
@file:Suppress("unused")

package llm.miband.littlewhite.hook

import android.content.Context
import io.github.libxposed.api.XposedModule
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector

/**
 * 手机端小爱（com.miui.voiceassist）注入入口。
 * voiceassist 有 :core/:provider/:inputMethodService 等子进程，
 * ExternalAgentService 与 osbot 运行在主进程，故仅主进程（processName == 包名）启动桥服务端。
 */
class VoiceAssistHook(
    private val module: XposedModule,
    private val config: ConfigStore,
    private val classLoader: ClassLoader,
    private val processName: String?,
) {
    private val tag = "VoiceAssistHook"

    fun install() {
        // 子进程（processName 含 ':'）跳过，只在主进程起 server
        if (processName != null && processName.contains(":")) {
            LogCollector.i(tag, "子进程 $processName，跳过桥服务端启动")
            return
        }
        val ctx = hostContext()
        if (ctx == null) {
            LogCollector.w(tag, "拿不到宿主 Context，跳过（不影响手环原始回答）")
            return
        }
        XiaoaiAgentServer.start(classLoader, ctx)
        LogCollector.i(tag, "voiceassist 注入完成，已请求启动 osbot 桥服务端")
    }

    /** 反射 ActivityThread.currentApplication 拿宿主 Context */
    private fun hostContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}
```

- [ ] **Step 2: 编写 XiaoaiAgentClient.kt（mi.health 侧调用封装）**

```kotlin
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
```

- [ ] **Step 3: 全量编译（此时 VoiceAssistHook 已存在，Task 1 缺失报错应消除）**

```bash
./gradlew assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: Commit（含 Task 1 的 scope.list / MainModule 变更）**

```bash
git add app/src/main/resources/META-INF/xposed/scope.list \
        app/src/main/kotlin/llm/miband/littlewhite/MainModule.kt \
        app/src/main/kotlin/llm/miband/littlewhite/hook/VoiceAssistHook.kt \
        app/src/main/kotlin/llm/miband/littlewhite/hook/XiaoaiAgentClient.kt
git commit -m "feat(hook): voiceassist 注入入口 + 手环侧 osbot 客户端 (路径B-osbot)"
```

***

### Task 6: 接入 mi.health 侧回答来源（processor + MiHealthHook）

**Files:**

- Modify: `app/src/main/kotlin/llm/miband/littlewhite/hook/WebSocketInterceptor.kt`（`handleToast`）

- Modify: `app/src/main/kotlin/llm/miband/littlewhite/hook/MiHealthHook.kt`（`replaceToastBlocking`）

- [ ] **Step 1: handleToast 的 apiKey 前置检查改为「非 phone 模式才要求 Key」**

`WebSocketInterceptor.kt` 中 `handleToast` 现有：

```kotlin
        if (config.getApiKey().isBlank()) {
            LogCollector.w(tag, "API Key 为空，Toast 透传 dialogId=$dialogId")
            return
        }
```

替换为：

```kotlin
        // 用手端小爱回答时无需自配 API Key；仅 DeepSeek 模式要求 Key
        if (!config.getUsePhoneXiaoai() && config.getApiKey().isBlank()) {
            LogCollector.w(tag, "API Key 为空，Toast 透传 dialogId=$dialogId")
            return
        }
```

- [ ] **Step 2: handleToast 的后台任务里按开关选择回答来源**

`WebSocketInterceptor.kt` 中 `llmExecutor.execute { ... }` 内现有：

```kotlin
                val answer = LlmClient.ask(dialogId, queryText)
```

替换为：

```kotlin
                // 回答来源二选一：手端小爱(osbot) 或 自配 LLM(DeepSeek)
                val answer = if (config.getUsePhoneXiaoai()) {
                    val timeout = config.getTimeoutMs().toInt().coerceIn(3_000, 20_000)
                    XiaoaiAgentClient.ask(queryText, timeout)
                } else {
                    LlmClient.ask(dialogId, queryText)
                }
```

- [ ] **Step 3: MiHealthHook.replaceToastBlocking 的 apiKey 前置检查同样放行 phone 模式**

`MiHealthHook.kt` 中现有：

```kotlin
        if (!config.isEnabled()) return null
        if (config.getApiKey().isBlank()) return null
        if (processor.getPendingQuery(dialogId).isNullOrBlank()) return null
```

替换为：

```kotlin
        if (!config.isEnabled()) return null
        // 手端小爱模式无需 API Key
        if (!config.getUsePhoneXiaoai() && config.getApiKey().isBlank()) return null
        if (processor.getPendingQuery(dialogId).isNullOrBlank()) return null
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/hook/WebSocketInterceptor.kt \
        app/src/main/kotlin/llm/miband/littlewhite/hook/MiHealthHook.kt
git commit -m "feat(hook): 手环回答来源支持手端小爱(osbot)分支"
```

***

### Task 7: 真机端到端验证（核心验收）

**Files:** 无（安装 + 观测）

- [ ] **Step 1: 安装并激活作用域**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

在 LSPosed 管理器中：启用本模块，作用域**同时勾选** `com.mi.health` 与 `com.miui.voiceassist`，然后重启两 App（或重启手机）。

- [ ] **Step 2: 确认 voiceassist 侧 server 起来**

```bash
adb logcat -s RingOnLLM:V 2>&1 | Select-String "VoiceAssistHook|XiaoaiAgentServer"
```

操作：打开手机端超级小爱一次（触发注入主进程）。
预期：`voiceassist 注入完成，已请求启动 osbot 桥服务端` + `TCP 桥服务端已启动，osbot 客户端连接=true`。

- 若 `连接=false`：说明同 UID bind 未过（可能 CTA 未同意）→ 在小爱内完成 miclaw 用户协议后重试。

- 若 `子进程 ... 跳过`：正常，说明命中的是 :core/:provider，等主进程加载。

- [ ] **Step 3: 打开「用手端小爱回答」开关**

模块设置页 → 配置 Tab → 打开「用手端小爱回答」。

- [ ] **Step 4: 手环发起提问并验证回答来源**

```bash
adb logcat -s RingOnLLM:V 2>&1 | Select-String "XiaoaiAgentClient|askOsbot|openSession|记录识别文本|回答已替换"
```

对手环说"帮我写一句鼓励学习的话"。
预期：

- `记录识别文本 dialogId=...`（现有）

- 无 `API Key 为空` 拦截（因 phone 模式放行）

- `Template/Toast 回答已替换 dialogId=...`

- 手环显示的回答是**手机端小爱 osbot 生成**（风格为 MiMo/带工具能力，非 DeepSeek）。

- [ ] **Step 5: 判定与排查表**

| 现象                                        | 根因                   | 处置                                         |
| ----------------------------------------- | -------------------- | ------------------------------------------ |
| 手环显示原始小爱 Toast（未替换）                       | server 未起 / 连接 false | 回 Step 2，检查 CTA / 作用域                      |
| `openSession 失败: error:CTA_NOT_ACCEPTED`  | 用户协议未同意              | 小爱内同意 miclaw 协议                            |
| `openSession 失败: error:PERMISSION_DENIED` | 同 UID 未命中（bind 到子进程） | 确认 server 在主进程；重启                          |
| `osbot 生成超时`                              | Agent 多轮耗时 > 25s     | 调大 `OSBOT_WAIT_MS` 或简化提问                   |
| 回答为空但无报错                                  | osbot 返回空 text       | 检查 `onComplete` args\[1]；看 voiceassist 侧日志 |
| 完全无 `XiaoaiAgentClient` 日志                | 开关未生效 / 未走 phone 分支  | 确认 `getUsePhoneXiaoai()` 读到 true           |

- [ ] **Step 6: 降级验证（拔链安全性）**

关闭手机端小爱（`adb shell am force-stop com.miui.voiceassist`）后，用手环提问。
预期：`XiaoaiAgentClient` 打印「手端小爱无回答，触发降级」，手环显示**原始小爱 Toast 回答**（`com.mi.health` 自身通道仍在工作），**不崩溃、不空白**。

***

### Task 8: proguard 保留与 Release 回归

**Files:**

- Modify: `app/proguard-rules.pro`

- [ ] **Step 1: 追加保留规则**

在 `proguard-rules.pro` 末尾追加：

```
# 保留路径B(osbot) 新增 Hook 类，避免 R8 裁剪反射入口
-keep class llm.miband.littlewhite.hook.VoiceAssistHook { *; }
-keep class llm.miband.littlewhite.hook.XiaoaiAgentServer { *; }
-keep class llm.miband.littlewhite.hook.XiaoaiAgentClient { *; }
-keep class llm.miband.littlewhite.hook.Bridge { *; }
```

- [ ] **Step 2: Release 构建**

```bash
./gradlew assembleRelease
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 回归手环 DeepSeek 模式（开关关闭时不退化）**

安装 release，关闭「用手端小爱回答」，配置好 DeepSeek，手环提问。
预期：仍走 `LlmClient`（DeepSeek）回答，与改动前一致。

- [ ] **Step 4: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "build(proguard): 保留路径B(osbot) Hook 类"
```

***

## Self-Review（作者自查清单）

**1. Spec 覆盖**（对照用户新需求「手环请求 → 手机端小爱处理 → 回投手环，不用配置 API」）：

- ✅ 手环提问转交手机端小爱：Task 6 的 `handleToast` phone 分支 + Task 5 `XiaoaiAgentClient`；

- ✅ 手机端小爱 osbot 处理：Task 4 `XiaoaiAgentServer` 反射 `ExternalAgentClient`（`osbot.main`）；

- ✅ 回答回投手环显示：复用现有 `replaceToastBlocking` + `replaceToastText`（Task 6 仅改来源，不改回投）；

- ✅ 不使用配置 API：Task 2 开关 + Task 6 两处 apiKey 前置检查放行；

- ✅ 跨进程桥接：Task 3 `Bridge`（localhost TCP）；

- ✅ 降级安全：Task 4/5 全异常隔离 + Task 7 Step 6 验证。

**2. Placeholder 扫描** —— 通过。`Bridge.kt`/`XiaoaiAgentServer.kt`/`VoiceAssistHook.kt`/`XiaoaiAgentClient.kt` 均给出完整实现；Task 6 给出替换前后完整代码块；无 "TBD/稍后"。

**3. 类型一致性** —— 通过：

- `ConfigStore.getUsePhoneXiaoai()/setUsePhoneXiaoai()`（Task 2 定义，Task 6 使用）；

- `Bridge.PORT/MAGIC/writeFrame/readFrame/requestAnswer`（Task 3 定义，Task 4/5 使用）；

- `XiaoaiAgentServer.start(classLoader, context)`（Task 4 定义，Task 5 调用）；

- `XiaoaiAgentClient.ask(query, timeoutMs)`（Task 5 定义，Task 6 调用）；

- `VoiceAssistHook(module, config, classLoader, processName)`（Task 5 定义，Task 1 调用）；

- `ConfigStore.getTimeoutMs()`（既有）在 Task 6 复用。

***

## 验收路径总览

1. Task 0 基线 + 反射契约确认。
2. Task 1+5 合并提交后 `assembleDebug` 通过。
3. Task 2/3/4/6 各自编译通过并独立提交。
4. Task 7 真机：`osbot 客户端连接=true` → 手环提问 → 回答来自手机端小爱 → 拔链降级不崩。
5. Task 8 release 通过且 DeepSeek 模式回归正常。

**风险与回退：**

- **宿主混淆** **`ExternalAgentClient`**：`loadClass`/`getMethod` 抛异常 → `XiaoaiAgentServer` 整体降级（server 起但 ask 返回空）→ 手环走原始回答。恢复：改用自写 `IExternalAgentService` transact 桩（不依赖类名）。

- **服务端二次校验** **`bizId/featureId`/白名单**：`submit` 返回 `20003`/`onError` → 降级。需真机确认 `ringonllm/chat` 是否被服务端接受；若拒绝，考虑复用某个已注册生态的 bizId（需进一步逆向）。

- **CTA 未同意**：`openSession` 返回 `error:CTA_NOT_ACCEPTED` → 引导用户在小爱内同意协议。

- **osbot 延迟高**：Agent 多轮工具调用可能 > 手环 Toast 展示窗口 → 现有 `MAX_WAIT_MS`（15s）会先放行原始回答；可调 `OSBOT_WAIT_MS` 与 `getTimeoutMs` 权衡。

- **被 Xposed 检测**：voiceassist Manifest 探测 Xposed；本方案仅反射调用官方 SDK、不改控制流、异常隔离，行为面小；若被风控降级，关闭「用手端小爱回答」开关即恢复原厂。

- **一键回退**：关闭设置页「用手端小爱回答」→ 立即回到现有 DeepSeek 行为；关闭「启用模块」→ 完全不干预。

