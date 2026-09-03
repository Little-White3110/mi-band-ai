# 联网搜索（本地 WebSearch 增强）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让环上 LLM（手环语音助手）在回答前自行调用搜索后端获取时效性资料，把「联网检索结果」注入 LLM 上下文，使手环显示基于实时信息的回答。

**Architecture:** 「本地支持」= 模块内自行完成「搜索 → 组装上下文 → 注入 LLM」，不依赖小爱云端。新增 `llm.miband.littlewhite.search` 包：可插拔 Provider 抽象（SearXNG 自托管 / Tavily / Brave，HTTP 走 `java.net.HttpURLConnection`，零新增运行时依赖）→ `SearchManager` 负责预取缓存与触发判定 → `WebSocketMessageProcessor` 在 `RecognizeResult` 时刻后台预取、在 `Toast` 时刻取回上下文 → `LlmClient.ask` 增加可选 `webContext` 参数拼入 system 提示。**关键时序设计**：预取发生在"小爱云端往返"窗口内（≈1-3s），正常路径下 Toast 到达时搜索已完成，增量延迟≈0；缓存缺失才回退同步搜索（预算 = `search_timeout_ms`，默认 4000ms），`MiHealthHook` 等待窗相应 +search 预算后 clamp 到 15s。

**Tech Stack:** Kotlin 2.4.10 / AGP 9.3.1 / kotlinx-serialization-json / java.net.HttpURLConnection（无 OkHttp）；测试 JUnit4；可选任务引入 jsoup 1.23.2。minSdk 26 无新增 API 门槛。

**调研结论（2026-09-03）：**
- GitHub 无可用 JVM/Kotlin 搜索 SDK（`searxng language:java` 5 仓库全 ≤2★；Tavily/Brave 官方仅 Python/TS SDK）→ 自写薄客户端是唯一合理方案，且契合 `AGENTS.md`「依赖克制/不用 OkHttp」约定。
- `jhy/jsoup`（11.4k★，MIT，无传递依赖）为唯一可选正文抽取库，最新 1.23.2（1.23.x 为唯一无已知 CVE 版本线）→ 仅放入**可选任务 T8**。
- API 事实：SearXNG `GET /search?q=&format=json`（json 需实例在 settings.yml 开放，否则 403）；Tavily `POST api.tavily.com/search`（Bearer，返回已清洗 `content`，无需抓正文）；Brave `GET api.search.brave.com/res/v1/web/search`（`X-Subscription-Token`，须带 `Cache-Control: no-cache` 否则 422；**新用户已无免费档，$5/1000 次**）。
- 评估过 `@erdium/pi-termux-web-tools`（socket.dev/npm 1.0.6）：它是 **Pi Coding Agent（Termux/Node.js）的插件**，功能=DuckDuckGo HTML 免 Key 搜索 + URL 读正文转 Markdown（Jina 优先）。**运行时（Node CLI 插件）与本项目（宿主进程内 Kotlin）不兼容、不能直接引用**；仅借鉴其「DDG-HTML 免 Key 兜底」「Jina Reader 正文」两个思路 → 落入**可选任务 T9/T10**。

---

## 文件结构

新建（全部在 `llm.miband.littlewhite.search` 包 + 测试）：

| 文件 | 职责 |
|---|---|
| `search/WebSearchProvider.kt` | Provider 接口 + `SearchResult` 数据类 |
| `search/SearchHttp.kt` | 薄 HTTP 助手：`get` / `postJson`（HttpURLConnection，内部对象 + UA 常量） |
| `search/SearxngProvider.kt` | SearXNG JSON 解析（`parse` 为 internal 便于单测） |
| `search/TavilyProvider.kt` | Tavily 请求/解析（internal `parse`） |
| `search/BraveProvider.kt` | Brave 请求/解析（internal `parse`） |
| `search/SearchQueryKit.kt` | 纯函数：触发词匹配/剥离、`buildContext` 组装注入块 |
| `search/SearchManager.kt` | object：`init`、预取线程池、dialogId 缓存、`contextFor`、`testSearch` |
| `app/src/test/kotlin/llm/miband/littlewhite/search/*Test.kt` | JUnit4 单测（触发逻辑、三个 Provider 解析 fixture） |

修改：

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 新增 `junit`（T1 需要）；T8 再增 `jsoup` |
| `app/build.gradle.kts` | `testImplementation(libs.junit)`；`testOptions { unitTests.isReturnDefaultValues = true }`；T8 加 `implementation(libs.jsoup)` |
| `config/ConfigKeys.kt` | 联网搜索键/值/默认值（单一来源） |
| `config/ConfigStore.kt` | `getSearchXxx()/setSearchXxx()`（API Key 复用 ApiKeyCipher） |
| `hook/LlmClient.kt` | `ask` 增加 `webContext: String? = null`；system 提示拼检索块 |
| `hook/WebSocketInterceptor.kt` | RecognizeResult 后 `SearchManager.maybePrefetch`；Toast 时取 `contextFor` 传入 ask |
| `hook/MiHealthHook.kt` | 等待窗 = LLM 超时 + 搜索预算，clamp 15s |
| `MainModule.kt` | `ensureInitialized` 补 `SearchManager.init(cfg)` |
| `ui/SettingsScreen.kt` | `ConfigTabContent` 生成参数后插「联网搜索」Card（含测试按钮） |
| `docs/websearch.md` | 新增使用文档；`AGENTS.md` 目录树/文档表同步 |

设计决策（边界纪律）：
- **触发模型三态**（单一开关，避免双开关混乱）：`off`（默认）/ `prefix`（指令词前缀触发，长词优先剥离）/ `always`（所有问题都搜）。
- **不做** LLM 自动路由判定（多一次调用，违背 15s 预算）；**不做** 预设（Preset）接入；**不做** 统计项扩展（LogCollector 记录足够）；**不做** OkHttp/Ktor。
- 中国大陆网络可达性差异（Tavily/Brave 需代理可达）与 **cleartext HTTP 限制**（模块运行在宿主 `com.mi.health` 进程内，宿主网络策略默认禁明文，模块 manifest 无法覆盖）→ 文档中给 HTTPS 反代指引，代码不特殊处理。
- `ApiKeyCipher` 保持 `private`，密钥读写收敛在 ConfigStore，不让 search 包触碰明文。

---

## Task 1: 测试依赖 + 配置层（ConfigKeys / ConfigStore）

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/llm/miband/littlewhite/config/ConfigKeys.kt`
- Modify: `app/src/main/kotlin/llm/miband/littlewhite/config/ConfigStore.kt`

- [ ] **Step 1: 版本目录加 JUnit**

`gradle/libs.versions.toml` 的 `[versions]` 末尾追加 `junit = "4.13.2"`；`[libraries]` 末尾追加：

```toml
# ---- 单元测试 ----
junit = { module = "junit:junit", version.ref = "junit" }
```

- [ ] **Step 2: app 模块挂测试依赖与默认返回值**

`app/build.gradle.kts` 中 `buildFeatures { compose = true }` 块后加：

```kotlin
    testOptions {
        // 本地单测中 android.jar 桩方法（如 android.util.Log）返回默认值而非抛异常
        unitTests.isReturnDefaultValues = true
    }
```

`dependencies` 末尾加：

```kotlin
    // ---- 单元测试 ----
    testImplementation(libs.junit)
```

- [ ] **Step 3: ConfigKeys 增加联网搜索键**

在 `ConfigKeys.kt` 中「---------- 回答模式键 ----------」的 `KEY_INTERCEPT_GENERAL` 之后、`DEFAULT_INTERCEPT_GENERAL` 默认值块附近插入键常量；在默认值区（`DEFAULT_INTERCEPT_GENERAL` 定义后、「---------- 视觉效果默认值」注释前）插入值/默认常量。完整待插入代码：

```kotlin
    // ---------- 联网搜索键 ----------
    const val KEY_SEARCH_MODE = "websearch_mode"
    const val KEY_SEARCH_PROVIDER = "websearch_provider"
    const val KEY_SEARCH_ENDPOINT = "websearch_endpoint"
    const val KEY_SEARCH_API_KEY = "websearch_api_key"
    const val KEY_SEARCH_MAX_RESULTS = "websearch_max_results"
    const val KEY_SEARCH_TIMEOUT_MS = "websearch_timeout_ms"
    const val KEY_SEARCH_TRIGGER_WORDS = "websearch_trigger_words"

    /** 搜索触发模式取值：关闭 / 指令词前缀触发 / 总是触发 */
    const val VALUE_SEARCH_MODE_OFF = "off"
    const val VALUE_SEARCH_MODE_PREFIX = "prefix"
    const val VALUE_SEARCH_MODE_ALWAYS = "always"

    /** 搜索 Provider 取值 */
    const val VALUE_SEARCH_PROVIDER_SEARXNG = "searxng"
    const val VALUE_SEARCH_PROVIDER_TAVILY = "tavily"
    const val VALUE_SEARCH_PROVIDER_BRAVE = "brave"

    // ---------- 联网搜索默认值 ----------
    /** 默认关闭：避免误触发产生延迟与外部请求 */
    const val DEFAULT_SEARCH_MODE = VALUE_SEARCH_MODE_OFF

    /** 默认自托管 SearXNG：无需 Key、可控、符合「本地支持」定位 */
    const val DEFAULT_SEARCH_PROVIDER = VALUE_SEARCH_PROVIDER_SEARXNG

    /** SearXNG 实例地址；留空表示未配置（Provider 请求前校验并提示） */
    const val DEFAULT_SEARCH_ENDPOINT = ""

    /** Tavily/Brave 的 API Key；存储加密，读取解密（复用 ApiKeyCipher） */
    const val DEFAULT_SEARCH_API_KEY = ""

    /** 单次搜索保留结果条数 */
    const val DEFAULT_SEARCH_MAX_RESULTS = 5

    /** 单次搜索超时（毫秒）：同步回退搜索的预算，需小于 LLM 总预算 */
    const val DEFAULT_SEARCH_TIMEOUT_MS = 4000

    /** 指令词触发模式的默认词库（换行分隔；长词优先匹配） */
    const val DEFAULT_SEARCH_TRIGGER_WORDS =
        "帮我搜索\n" +
        "帮我查一下\n" +
        "帮我查查\n" +
        "搜索\n" +
        "查一下\n" +
        "查查\n" +
        "搜一下\n" +
        "查一查\n" +
        "上网搜"
```

- [ ] **Step 4: ConfigStore 增加读取器与写入器**

读取器插到 `getInterceptGeneral()` 之后、`splitCommandWords` 之前：

```kotlin
    // ==================== 联网搜索 ====================

    /** 搜索触发模式："off" / "prefix" / "always"（trim+小写归一） */
    fun getSearchMode(): String =
        prefs.getString(ConfigKeys.KEY_SEARCH_MODE, ConfigKeys.DEFAULT_SEARCH_MODE)
            ?.trim()?.lowercase() ?: ConfigKeys.DEFAULT_SEARCH_MODE

    /** 搜索 Provider："searxng" / "tavily" / "brave" */
    fun getSearchProvider(): String =
        prefs.getString(ConfigKeys.KEY_SEARCH_PROVIDER, ConfigKeys.DEFAULT_SEARCH_PROVIDER)
            ?.trim()?.lowercase() ?: ConfigKeys.DEFAULT_SEARCH_PROVIDER

    /** SearXNG 实例地址（trim 尾斜杠）；空串表示未配置 */
    fun getSearchEndpoint(): String =
        prefs.getString(ConfigKeys.KEY_SEARCH_ENDPOINT, ConfigKeys.DEFAULT_SEARCH_ENDPOINT)
            ?.trim()?.trimEnd('/') ?: ""

    /** 搜索 API Key：读取时自动解密，解密失败返回空串 */
    fun getSearchApiKey(): String =
        ApiKeyCipher.decrypt(prefs.getString(ConfigKeys.KEY_SEARCH_API_KEY, "") ?: "")

    /** 单次搜索保留结果条数（1..10 收敛） */
    fun getSearchMaxResults(): Int =
        prefs.getInt(ConfigKeys.KEY_SEARCH_MAX_RESULTS, ConfigKeys.DEFAULT_SEARCH_MAX_RESULTS)
            .coerceIn(1, 10)

    /** 单次搜索超时（毫秒，1..10s 收敛） */
    fun getSearchTimeoutMs(): Long =
        prefs.getInt(ConfigKeys.KEY_SEARCH_TIMEOUT_MS, ConfigKeys.DEFAULT_SEARCH_TIMEOUT_MS)
            .toLong().coerceIn(1000L, 10_000L)

    /** 触发词库：按行拆分 trim 去空；空串回退默认词库 */
    fun getSearchTriggerWords(): List<String> {
        val raw = prefs.getString(ConfigKeys.KEY_SEARCH_TRIGGER_WORDS, "") ?: ""
        return splitCommandWords(raw).ifEmpty { splitCommandWords(ConfigKeys.DEFAULT_SEARCH_TRIGGER_WORDS) }
    }
```

写入器插到 `setInterceptGeneral` 之后：

```kotlin
    // ==================== 联网搜索写入器 ====================

    fun setSearchMode(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_SEARCH_MODE, v).apply()
    }

    fun setSearchProvider(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_SEARCH_PROVIDER, v).apply()
    }

    fun setSearchEndpoint(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_SEARCH_ENDPOINT, v.trim().trimEnd('/')).apply()
    }

    /** 搜索 API Key：写入加密，不落明文 */
    fun setSearchApiKey(v: String) {
        prefs.edit().putString(ConfigKeys.KEY_SEARCH_API_KEY, ApiKeyCipher.encrypt(v)).apply()
    }

    fun setSearchMaxResults(v: Int) {
        prefs.edit().putInt(ConfigKeys.KEY_SEARCH_MAX_RESULTS, v.coerceIn(1, 10)).apply()
    }

    fun setSearchTimeoutMs(v: Int) {
        prefs.edit().putInt(ConfigKeys.KEY_SEARCH_TIMEOUT_MS, v.coerceIn(1000, 10_000)).apply()
    }

    /** 触发词库（多行文本存储，自动过滤空行） */
    fun setSearchTriggerWords(words: List<String>) {
        prefs.edit().putString(ConfigKeys.KEY_SEARCH_TRIGGER_WORDS, words.joinToString("\n")).apply()
    }
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（纯新增，无行为变更）。

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/kotlin/llm/miband/littlewhite/config/ConfigKeys.kt app/src/main/kotlin/llm/miband/littlewhite/config/ConfigStore.kt
git commit -m "feat(websearch): add search config keys, defaults and store accessors"
```

---

## Task 2: 纯函数层（SearchQueryKit）+ TDD

**Files:**
- Create: `app/src/main/kotlin/llm/miband/littlewhite/search/WebSearchProvider.kt`（接口+模型，T3 复用）
- Create: `app/src/main/kotlin/llm/miband/littlewhite/search/SearchQueryKit.kt`
- Test: `app/src/test/kotlin/llm/miband/littlewhite/search/SearchQueryKitTest.kt`

- [ ] **Step 1: 先建接口/模型文件（无逻辑）**

`WebSearchProvider.kt`：

```kotlin
package llm.miband.littlewhite.search

/**
 * 联网搜索 Provider 抽象：不同后端（SearXNG/Tavily/Brave）提供各自实现。
 * 约定：search() 网络/HTTP 层失败返回 null；成功但零命中返回空列表。
 */
interface WebSearchProvider {
    val name: String

    /**
     * 同步执行一次搜索（调用方须在后台线程执行）。
     * @param maxResults 期望结果条数上限
     * @param timeoutMs  连接与读超时
     * @return 命中结果；HTTP/网络失败返回 null
     */
    fun search(query: String, maxResults: Int, timeoutMs: Long): List<SearchResult>?
}

/** 单条搜索结果（snippet 已足够支撑手环 80 字内回答） */
data class SearchResult(
    val title: String,
    val url: String,
    val content: String,
)
```

- [ ] **Step 2: 写失败测试**

`SearchQueryKitTest.kt`：

```kotlin
package llm.miband.littlewhite.search

import llm.miband.littlewhite.config.ConfigKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryKitTest {

    private val words = listOf("搜索", "查一下", "帮我搜索", "帮我查一下")

    @Test
    fun `prefix mode strips longest matching trigger word`() {
        assertEquals(
            "今天天气怎么样",
            SearchQueryKit.searchQueryOf("帮我查一下今天天气怎么样", ConfigKeys.VALUE_SEARCH_MODE_PREFIX, words),
        )
    }

    @Test
    fun `prefix mode without trigger returns null`() {
        assertNull(
            SearchQueryKit.searchQueryOf("今天天气怎么样", ConfigKeys.VALUE_SEARCH_MODE_PREFIX, words),
        )
    }

    @Test
    fun `prefix mode returns null when query equals trigger word only`() {
        assertNull(SearchQueryKit.searchQueryOf("搜索", ConfigKeys.VALUE_SEARCH_MODE_PREFIX, words))
    }

    @Test
    fun `always mode keeps whole query`() {
        assertEquals(
            "今天天气",
            SearchQueryKit.searchQueryOf("  今天天气  ", ConfigKeys.VALUE_SEARCH_MODE_ALWAYS, words),
        )
    }

    @Test
    fun `off mode returns null`() {
        assertNull(SearchQueryKit.searchQueryOf("今天天气", ConfigKeys.VALUE_SEARCH_MODE_OFF, words))
    }

    @Test
    fun `blank text returns null`() {
        assertNull(SearchQueryKit.searchQueryOf("   ", ConfigKeys.VALUE_SEARCH_MODE_ALWAYS, words))
    }

    @Test
    fun `buildContext formats numbered sources and truncates`() {
        val results = listOf(
            SearchResult("标题一", "https://a.example/x", "内容一".repeat(200)),
            SearchResult("标题二", "https://b.example/y", "内容二"),
        )
        val block = SearchQueryKit.buildContext("问题", "searxng", results, maxSnippetChars = 20, maxTotalChars = 120)
        assertTrue(block != null && block!!.contains("[1] 标题一"))
        assertTrue(block!!.contains("[2] 标题二"))
        assertTrue(block.length <= 140) // 内部还会追加截断标记
        assertTrue(!block.contains("内容一".repeat(200)))
    }

    @Test
    fun `buildContext with empty results returns null`() {
        assertNull(SearchQueryKit.buildContext("q", "searxng", emptyList()))
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "llm.miband.littlewhite.search.SearchQueryKitTest"`
Expected: FAIL（编译错误：SearchQueryKit 不存在）。

- [ ] **Step 4: 实现 SearchQueryKit**

```kotlin
package llm.miband.littlewhite.search

import llm.miband.littlewhite.config.ConfigKeys

/**
 * 联网搜索纯函数工具（无 Android 依赖，可直接 JVM 单测）。
 *
 * 触发语义：
 * - [ConfigKeys.VALUE_SEARCH_MODE_ALWAYS]：整句作为搜索词；
 * - [ConfigKeys.VALUE_SEARCH_MODE_PREFIX]：识别文本必须以某触发词开头且其后仍有内容，
 *   剥离触发词后作为搜索词；触发词按长度降序匹配（避免"帮我查一下…"被短词截错）。
 */
object SearchQueryKit {

    /** 组装注入 system 的检索块；空结果返回 null */
    fun buildContext(
        query: String,
        provider: String,
        results: List<SearchResult>,
        maxSnippetChars: Int = 300,
        maxTotalChars: Int = 2000,
    ): String? {
        if (results.isEmpty()) return null
        val sb = StringBuilder("[联网搜索结果] 搜索词:$query 来源:$provider\n")
        var index = 1
        for (r in results) {
            if (sb.length >= maxTotalChars) break
            sb.append("[${index++}] ").append(r.title.ifBlank { "(无标题)" }).append('\n')
                .append(r.url).append('\n')
                .append(snippet(r.content, maxSnippetChars)).append('\n')
        }
        val text = sb.toString().trimEnd('\n')
        if (text.isBlank()) return null
        // 总长收敛：保留前 maxTotalChars 字符，缺失尾部补截断标记，防止上下文撑爆预算
        return if (text.length <= maxTotalChars) text else text.take(maxTotalChars) + "\n…[截断]"
    }

    /** 由识别文本 + 触发模式得到真正搜索词；不应触发返回 null */
    fun searchQueryOf(text: String, mode: String, triggerWords: List<String>): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return when (mode.trim().lowercase()) {
            ConfigKeys.VALUE_SEARCH_MODE_ALWAYS -> trimmed
            ConfigKeys.VALUE_SEARCH_MODE_PREFIX -> stripTrigger(trimmed, triggerWords)
            else -> null // off 及未知值一律不触发
        }
    }

    /** 前缀触发词剥离；未命中返回 null（触发词必须位于开头且后跟内容） */
    fun stripTrigger(text: String, triggerWords: List<String>): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val sorted = triggerWords.map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
        for (word in sorted) {
            if (trimmed.length > word.length && trimmed.startsWith(word)) {
                val rest = trimmed.removePrefix(word).trim()
                if (rest.isNotEmpty()) return rest
            }
        }
        return null
    }

    /** 截断单条正文到 maxChars，超长补省略号 */
    private fun snippet(content: String, maxChars: Int): String {
        val c = content.replace(Regex("\\s+"), " ").trim()
        return if (c.length <= maxChars) c else c.take(maxChars) + "…"
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "llm.miband.littlewhite.search.SearchQueryKitTest"`
Expected: PASS（7 tests）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/search/WebSearchProvider.kt app/src/main/kotlin/llm/miband/littlewhite/search/SearchQueryKit.kt app/src/test/kotlin/llm/miband/littlewhite/search/SearchQueryKitTest.kt
git commit -m "feat(websearch): add pure search trigger/query-kit with unit tests"
```

---

## Task 3: HTTP 助手 + 三个 Provider（解析 TDD）

**Files:**
- Create: `app/src/main/kotlin/llm/miband/littlewhite/search/SearchHttp.kt`
- Create: `app/src/main/kotlin/llm/miband/littlewhite/search/SearxngProvider.kt`
- Create: `app/src/main/kotlin/llm/miband/littlewhite/search/TavilyProvider.kt`
- Create: `app/src/main/kotlin/llm/miband/littlewhite/search/BraveProvider.kt`
- Test: `app/src/test/kotlin/llm/miband/littlewhite/search/ProviderParseTest.kt`

- [ ] **Step 1: HTTP 助手**

`SearchHttp.kt`：

```kotlin
package llm.miband.littlewhite.search

import llm.miband.littlewhite.log.LogCollector
import java.net.HttpURLConnection
import java.net.URL

/**
 * 搜索用薄 HTTP 助手（HttpURLConnection 直连，延续 LlmClient 同款风格）。
 * 网络异常/非 2xx 统一返回 null 并记日志（调用方负责判空），绝不上抛。
 */
internal object SearchHttp {

    private const val TAG = "SearchHttp"

    /** 各家搜索后端对空 UA 可能拒绝/限流，固定声明来源 */
    val UA = "RingOnLLM-WebSearch/0.1 (Android; +https://github.com/Little-White3110/mi-band-ai)"

    fun get(url: String, headers: Map<String, String>, timeoutMs: Long): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            applyBase(conn, headers, timeoutMs)
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
            } else {
                val err = conn.errorStream?.use { it.bufferedReader(Charsets.UTF_8).readText() } ?: ""
                LogCollector.e(TAG, "GET HTTP $code url=${shorten(url)} resp=${shorten(err)}")
                null
            }
        } catch (t: Throwable) {
            LogCollector.e(TAG, "GET 请求失败 url=${shorten(url)}", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            applyBase(conn, headers + ("Content-Type" to "application/json"), timeoutMs)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
            } else {
                val err = conn.errorStream?.use { it.bufferedReader(Charsets.UTF_8).readText() } ?: ""
                LogCollector.e(TAG, "POST HTTP $code url=${shorten(url)} resp=${shorten(err)}")
                null
            }
        } catch (t: Throwable) {
            LogCollector.e(TAG, "POST 请求失败 url=${shorten(url)}", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun applyBase(conn: HttpURLConnection, headers: Map<String, String>, timeoutMs: Long) {
        val timeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", UA)
        for ((k, v) in headers) conn.setRequestProperty(k, v)
    }

    private fun shorten(s: String): String = if (s.length <= 200) s else s.take(200) + "…"
}
```

- [ ] **Step 2: 写解析失败测试（fixtures 来自官方文档样张）**

`ProviderParseTest.kt`：

```kotlin
package llm.miband.littlewhite.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderParseTest {

    // SearXNG /search?format=json 返回结构（url/title/content 可能缺失）
    private val searxngJson = """
        {
          "query": "searxng",
          "number_of_results": 100,
          "results": [
            {"url": "https://example.com/a", "title": "A", "content": "snippet-a", "engine": "google", "score": 5.0},
            {"url": "https://example.com/b", "title": "B", "content": "", "engine": "bing"},
            {"url": "", "title": "", "content": "no url item should be dropped", "engine": "duckduckgo"}
          ]
        }
    """.trimIndent()

    // Tavily /search 返回结构（content 为清洗后片段）
    private val tavilyJson = """
        {
          "query": "q",
          "results": [
            {"title": "T1", "url": "https://t.example/1", "content": "c1", "score": 0.8},
            {"title": "T2", "url": "https://t.example/2", "content": "c2", "score": 0.5}
          ]
        }
    """.trimIndent()

    // Brave web.results（web 可能缺省）
    private val braveJson = """
        {
          "query": {"original": "q"},
          "web": {
            "results": [
              {"title": "B1", "url": "https://b.example/1", "description": "d1"},
              {"title": "B2", "url": "https://b.example/2", "description": null}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun searxng_parses_and_drops_invalid_items() {
        val items = SearxngProvider("https://searx.invalid").parse(searxngJson)
        assertEquals(2, items.size)
        assertEquals("A", items[0].title)
        assertEquals("snippet-a", items[0].content)
        assertEquals("https://example.com/b", items[1].url)
    }

    @Test
    fun tavily_parses_content_snippets() {
        val items = TavilyProvider("tvly-test").parse(tavilyJson)
        assertEquals(2, items.size)
        assertEquals("c2", items[1].content)
    }

    @Test
    fun brave_parses_web_results_and_handles_missing_web() {
        val items = BraveProvider("brave-test").parse(braveJson)
        assertEquals(2, items.size)
        assertEquals("d1", items[0].content)
        assertTrue(BraveProvider("brave-test").parse("{}").isEmpty())
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "llm.miband.littlewhite.search.ProviderParseTest"`
Expected: FAIL（编译错误：三个 Provider 不存在）。

- [ ] **Step 4: 实现三个 Provider**

`SearxngProvider.kt`：

```kotlin
package llm.miband.littlewhite.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import llm.miband.littlewhite.log.LogCollector
import java.net.URLEncoder

/**
 * SearXNG（自托管元搜索）Provider —— 默认推荐后端。
 * GET {endpoint}/search?q=&format=json；需实例在 settings.yml 开放 json 格式（否则 403）。
 */
class SearxngProvider(private val endpoint: String) : WebSearchProvider {

    override val name: String = "searxng"

    private val json = Json { ignoreUnknownKeys = true }

    override fun search(query: String, maxResults: Int, timeoutMs: Long): List<SearchResult>? {
        val url = buildString {
            append(endpoint.trimEnd('/')).append("/search?q=")
            append(URLEncoder.encode(query, Charsets.UTF_8.name()))
            append("&format=json&safesearch=1")
        }
        val body = SearchHttp.get(
            url,
            headers = mapOf("Accept" to "application/json"),
            timeoutMs = timeoutMs,
        ) ?: return null
        return try {
            parse(body).take(maxResults)
        } catch (t: Throwable) {
            LogCollector.w(TAG, "SearXNG 响应解析失败: ${t.message}")
            null
        }
    }

    /** 纯解析（internal 便于 fixture 单测；不依赖 Android/网络） */
    internal fun parse(body: String): List<SearchResult> {
        val resp = json.decodeFromString(SearxngResponse.serializer(), body)
        return resp.results.mapNotNull { r ->
            val url = r.url?.trim().orEmpty()
            val title = r.title?.trim().orEmpty()
            if (url.isEmpty() || title.isEmpty()) return@mapNotNull null
            SearchResult(title = title, url = url, content = r.content?.trim().orEmpty())
        }
    }

    @Serializable
    private data class SearxngItem(val url: String? = null, val title: String? = null, val content: String? = null)

    @Serializable
    private data class SearxngResponse(val results: List<SearxngItem> = emptyList())

    private companion object { const val TAG = "SearxngProvider" }
}
```

`TavilyProvider.kt`：

```kotlin
package llm.miband.littlewhite.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import llm.miband.littlewhite.log.LogCollector

/**
 * Tavily Provider：专为 LLM 场景设计的搜索 API，返回已清洗的 content 片段，
 * 无需二次抓取正文；单次 basic 搜索消耗 1 credit。
 */
class TavilyProvider(private val apiKey: String) : WebSearchProvider {

    override val name: String = "tavily"

    private val json = Json { ignoreUnknownKeys = true }

    override fun search(query: String, maxResults: Int, timeoutMs: Long): List<SearchResult>? {
        val body = json.encodeToString(
            TavilyRequest.serializer(),
            TavilyRequest(query = query, maxResults = maxResults),
        )
        val resp = SearchHttp.postJson(
            url = ENDPOINT,
            headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
            body = body,
            timeoutMs = timeoutMs,
        ) ?: return null
        return try {
            parse(resp).take(maxResults)
        } catch (t: Throwable) {
            LogCollector.w(TAG, "Tavily 响应解析失败: ${t.message}")
            null
        }
    }

    internal fun parse(body: String): List<SearchResult> {
        val resp = json.decodeFromString(TavilyResponse.serializer(), body)
        return resp.results.mapNotNull { r ->
            val url = r.url?.trim().orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            SearchResult(title = r.title?.trim().orEmpty(), url = url, content = r.content?.trim().orEmpty())
        }
    }

    @Serializable
    private data class TavilyRequest(
        val query: String,
        @SerialName("max_results") val maxResults: Int,
        @SerialName("search_depth") val searchDepth: String = "basic",
        @SerialName("include_answer") val includeAnswer: Boolean = false,
    )

    @Serializable
    private data class TavilyItem(
        val title: String? = null,
        val url: String? = null,
        val content: String? = null,
    )

    @Serializable
    private data class TavilyResponse(val results: List<TavilyItem> = emptyList())

    private companion object {
        const val TAG = "TavilyProvider"
        const val ENDPOINT = "https://api.tavily.com/search"
    }
}
```

`BraveProvider.kt`：

```kotlin
package llm.miband.littlewhite.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import llm.miband.littlewhite.log.LogCollector
import java.net.URLEncoder

/**
 * Brave Web Search Provider。
 * 注意：Brave 强制要求 Cache-Control: no-cache（缺失返回 422）；新用户已无免费档。
 */
class BraveProvider(private val apiKey: String) : WebSearchProvider {

    override val name: String = "brave"

    private val json = Json { ignoreUnknownKeys = true }

    override fun search(query: String, maxResults: Int, timeoutMs: Long): List<SearchResult>? {
        val count = maxResults.coerceIn(1, 20)
        val url = buildString {
            append(ENDPOINT).append("?q=")
            append(URLEncoder.encode(query, Charsets.UTF_8.name()))
            append("&count=").append(count)
        }
        val resp = SearchHttp.get(
            url,
            headers = mapOf(
                "X-Subscription-Token" to apiKey,
                "Accept" to "application/json",
                "Cache-Control" to "no-cache",
            ),
            timeoutMs = timeoutMs,
        ) ?: return null
        return try {
            parse(resp).take(maxResults)
        } catch (t: Throwable) {
            LogCollector.w(TAG, "Brave 响应解析失败: ${t.message}")
            null
        }
    }

    internal fun parse(body: String): List<SearchResult> {
        val resp = json.decodeFromString(BraveResponse.serializer(), body)
        val web = resp.web ?: return emptyList()
        return web.results.mapNotNull { r ->
            val url = r.url?.trim().orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            SearchResult(title = r.title?.trim().orEmpty(), url = url, content = r.description?.trim().orEmpty())
        }
    }

    @Serializable
    private data class BraveItem(
        val title: String? = null,
        val url: String? = null,
        val description: String? = null,
    )

    @Serializable
    private data class BraveWeb(val results: List<BraveItem> = emptyList())

    @Serializable
    private data class BraveResponse(val web: BraveWeb? = null)

    private companion object {
        const val TAG = "BraveProvider"
        const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "llm.miband.littlewhite.search.ProviderParseTest"`
Expected: PASS（3 tests）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/search/ app/src/test/kotlin/llm/miband/littlewhite/search/ProviderParseTest.kt
git commit -m "feat(websearch): add searxng/tavily/brave providers with parse tests"
```

---

## Task 4: SearchManager 编排（预取缓存 + 上下文取回）

**Files:**
- Create: `app/src/main/kotlin/llm/miband/littlewhite/search/SearchManager.kt`
- Modify: `app/src/main/kotlin/llm/miband/littlewhite/MainModule.kt`

- [ ] **Step 1: 实现 SearchManager**

设计要点：缓存键 = `dialogId`（与 LLM 会话同生命周期语义），TTL 30s；`maybePrefetch` 供 RecognizeResult 时刻调用（fire-and-forget，绝不阻塞 WebSocket 线程）；`contextFor` 供 Toast 时刻调用（缓存命中零延迟，未命中以搜索预算同步兜底）。

```kotlin
package llm.miband.littlewhite.search

import llm.miband.littlewhite.config.ConfigKeys
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 联网搜索编排（object，运行于 Hook 宿主进程）。
 *
 * 时序设计：语音识别结果（RecognizeResult）到达时立即后台预取，利用「小爱云端往返」
 * 窗口（≈1-3s）把搜索做完缓存；小爱 Toast 到达、需要拼 LLM 上下文时直接命中缓存，
 * 正常路径增量延迟≈0。缓存缺失（超窗/预取未完成）才回退同步搜索，预算 = search_timeout_ms。
 *
 * 异常纪律：一切异常不外抛、不阻塞调用线程（预取提交后立即返回）；
 * 未调用 [init] 时所有方法静默降级返回 null/不动作。
 */
object SearchManager {

    private const val TAG = "SearchManager"
    private const val CACHE_TTL_MS = 30_000L
    private const val MAX_CACHE_ENTRIES = 64

    /** 配置读取器：由 [init] 注入（模块进程与 App 进程各自注入一次） */
    @Volatile
    private var config: ConfigStore? = null

    /** 搜索专用线程池：预取与回退搜索不占用 WsProcessor 单线程池，避免与 LLM 串行排队 */
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "RingOnLLM-Search").apply { isDaemon = true }
    }

    private data class CacheEntry(
        val query: String,
        val results: List<SearchResult>,
        val provider: String,
        val atMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 初始化：注入配置读取器（MainModule.ensureInitialized / 设置页测试按钮前调用） */
    fun init(cfg: ConfigStore) {
        if (config == null) config = cfg
    }

    /**
     * RecognizeResult 时刻调用：命中触发条件则后台预取并缓存。
     * 纯 fire-and-forget：内部 try-catch，绝不抛给 WebSocket 线程。
     */
    fun maybePrefetch(dialogId: String, rawText: String) {
        try {
            val cfg = config ?: return
            val cleaned = resolveQuery(cfg, rawText) ?: return
            executor.execute {
                doSearchAndCache(cfg, dialogId, cleaned)
            }
        } catch (t: Throwable) {
            LogCollector.w(TAG, "预取搜索异常（忽略）: ${t.message}")
        }
    }

    /**
     * Toast 时刻调用：返回注入 LLM system 的检索上下文块。
     * 优先命中 dialogId 新鲜缓存；否则同步搜索兜底（预算 = cfg.getSearchTimeoutMs()）。
     */
    fun contextFor(dialogId: String, rawText: String): String? {
        val cfg = config ?: return null
        val cleaned = resolveQuery(cfg, rawText) ?: return null
        val now = System.currentTimeMillis()
        val hit = cache[dialogId]
        if (hit != null && hit.query == cleaned && now - hit.atMs < CACHE_TTL_MS) {
            if (hit.results.isEmpty()) return null
            LogCollector.i(TAG, "命中搜索缓存 dialogId=${dialogId.take(12)} query=$cleaned")
            return SearchQueryKit.buildContext(cleaned, hit.provider, hit.results)
        }
        return doSearchAndCache(cfg, dialogId, cleaned)
            ?.takeIf { it.isNotEmpty() }
            ?.let { SearchQueryKit.buildContext(cleaned, cfg.getSearchProvider(), it) }
    }

    /** 设置页"测试搜索"用：返回一句话结果摘要；失败给可操作的提示文本 */
    fun testSearch(query: String): String {
        val cfg = config ?: return "失败：SearchManager 未初始化"
        val problem = validate(cfg)
        if (problem != null) return problem
        val t0 = System.currentTimeMillis()
        val results = runSearch(cfg, query.trim())
        val elapsed = System.currentTimeMillis() - t0
        return when {
            results == null -> "搜索失败（HTTP/网络异常），请检查地址与网络后查看日志"
            results.isEmpty() -> "搜索成功但无结果（$elapsed ms）"
            else -> "搜索成功：${cfg.getSearchProvider()} | ${results.size} 条 | ${elapsed} ms"
        }
    }

    // ==================== 内部实现 ====================

    /** 触发判定 + 剥离触发词得到搜索词；不应触发返回 null */
    private fun resolveQuery(cfg: ConfigStore, rawText: String): String? =
        SearchQueryKit.searchQueryOf(rawText, cfg.getSearchMode(), cfg.getSearchTriggerWords())

    /** 前置校验：返回 null 表示可发起请求 */
    private fun validate(cfg: ConfigStore): String? = when (cfg.getSearchProvider()) {
        ConfigKeys.VALUE_SEARCH_PROVIDER_SEARXNG ->
            if (cfg.getSearchEndpoint().isBlank()) "失败：请先填写 SearXNG 实例地址" else null
        ConfigKeys.VALUE_SEARCH_PROVIDER_TAVILY ->
            if (cfg.getSearchApiKey().isBlank()) "失败：请先填写 Tavily API Key" else null
        ConfigKeys.VALUE_SEARCH_PROVIDER_BRAVE ->
            if (cfg.getSearchApiKey().isBlank()) "失败：请先填写 Brave API Key" else null
        else -> "失败：未知 Provider"
    }

    /** 执行搜索并缓存结果（供预取线程与同步回退共用）；网络失败返回 null */
    private fun doSearchAndCache(cfg: ConfigStore, dialogId: String, query: String): List<SearchResult>? {
        val results = runSearch(cfg, query)
        if (results != null) {
            cache[dialogId] = CacheEntry(query, results, cfg.getSearchProvider(), System.currentTimeMillis())
            trimCache()
        }
        return results
    }

    /** 按配置构建 Provider 并执行一次搜索；日志记录查询/命中/耗时 */
    private fun runSearch(cfg: ConfigStore, query: String): List<SearchResult>? {
        val provider = createProvider(cfg) ?: return null
        val t0 = System.currentTimeMillis()
        val results = provider.search(query, cfg.getSearchMaxResults(), cfg.getSearchTimeoutMs())
        val elapsed = System.currentTimeMillis() - t0
        LogCollector.i(
            TAG,
            "搜索 ${provider.name} query=${query.take(40)} 命中=${results?.size ?: "失败"} 耗时=${elapsed}ms",
        )
        return results
    }

    private fun createProvider(cfg: ConfigStore): WebSearchProvider? {
        val problem = validate(cfg)
        if (problem != null) {
            LogCollector.w(TAG, problem.removePrefix("失败："))
            return null
        }
        return when (cfg.getSearchProvider()) {
            ConfigKeys.VALUE_SEARCH_PROVIDER_SEARXNG -> SearxngProvider(cfg.getSearchEndpoint())
            ConfigKeys.VALUE_SEARCH_PROVIDER_TAVILY -> TavilyProvider(cfg.getSearchApiKey())
            ConfigKeys.VALUE_SEARCH_PROVIDER_BRAVE -> BraveProvider(cfg.getSearchApiKey())
            else -> null
        }
    }

    private fun trimCache() {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        val now = System.currentTimeMillis()
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.atMs > CACHE_TTL_MS || cache.size > MAX_CACHE_ENTRIES) it.remove()
        }
    }
}
```

- [ ] **Step 2: MainModule 装配**

`MainModule.kt` 增加 import 与 init 调用（`ensureInitialized` 内 `LlmClient.init(cfg, hostContext())` 之后）：

```kotlin
import llm.miband.littlewhite.search.SearchManager
```

```kotlin
                LlmClient.init(cfg, hostContext())
                SearchManager.init(cfg)
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/search/SearchManager.kt app/src/main/kotlin/llm/miband/littlewhite/MainModule.kt
git commit -m "feat(websearch): add SearchManager orchestration with prefetch cache"
```

---

## Task 5: LLM 上下文注入（LlmClient）+ 处理器接线

**Files:**
- Modify: `app/src/main/kotlin/llm/miband/littlewhite/hook/LlmClient.kt:282-372`
- Modify: `app/src/main/kotlin/llm/miband/littlewhite/hook/WebSocketInterceptor.kt:211-322`
- Modify: `app/src/main/kotlin/llm/miband/littlewhite/hook/MiHealthHook.kt:344-358`

- [ ] **Step 1: LlmClient 增加 webContext 参数并在 system 提示中注入**

改 `ask` 签名与 Javadoc：

```kotlin
    /**
     * 主调用入口：根据用户识别文本调用 LLM 获取回答。
     *
     * @param dialogId WebSocket 消息的 dialog_id，用于会话关联
     * @param queryText 用户语音识别文本
     * @param webContext 联网检索上下文块（可空）；非空时附加到 system 提示，引导模型
     *                   优先依据检索资料回答。不计入会话历史。
     * @return 回答文本；失败/超时/未初始化返回 null
     */
    fun ask(dialogId: String, queryText: String, webContext: String? = null): String? {
```

`askLocked` 内组装 system 处（原文 `if (systemPrompt.isNotBlank()) messages.add(ChatMessage("system", systemPrompt))`）改为：

```kotlin
        val systemPrompt = buildSystemPrompt(cfg, webContext)
        if (systemPrompt.isNotBlank()) messages.add(ChatMessage("system", systemPrompt))
```

并在类内新增私有函数（放在 askLocked 之后）：

```kotlin
    /**
     * 组装 system 提示：基础提示词 + （可选）联网检索块。
     * 检索块附使用约束（只作参考、无关可忽略），保证手环 80 字内简洁回答不被破坏。
     */
    private fun buildSystemPrompt(cfg: ConfigStore, webContext: String?): String {
        val base = cfg.getSystemPrompt()
        val ctx = webContext?.trim().orEmpty()
        if (ctx.isEmpty()) return base
        return if (base.isBlank()) {
            "以下是联网检索到的资料：\n$ctx\n请依据资料简洁回答，80字以内。"
        } else {
            "$base\n\n以下是联网检索到的资料（仅作参考，与问题无关可忽略）：\n$ctx"
        }
    }
```

- [ ] **Step 2: WsProcessor RecognizeResult 预取 + Toast 取上下文**

`handleRecognizeResult` 中 `pendingQueries[dialogId] = text` 行之后插入（import `llm.miband.littlewhite.search.SearchManager`）：

```kotlin
        pendingQueries[dialogId] = text
        LogCollector.i(tag, "记录识别文本 dialogId=$dialogId text=${text.take(60)}")
        trimPendingQueries()

        // —— 联网搜索预取：利用小爱云端往返窗口并行搜索，Toast 到达时直接命中缓存 ——
        if (config.getSearchMode() != llm.miband.littlewhite.config.ConfigKeys.VALUE_SEARCH_MODE_OFF) {
            SearchManager.maybePrefetch(dialogId, text)
        }
```

`handleToast` 内调用 LLM 处（`val answer = LlmClient.ask(dialogId, queryText)`）改为：

```kotlin
                LogCollector.i(tag, "调用 LLM 替换回答 dialogId=$dialogId query=${queryText.take(60)}")
                val webContext = SearchManager.contextFor(dialogId, queryText)
                val answer = LlmClient.ask(dialogId, queryText, webContext)
```

- [ ] **Step 3: MiHealthHook 等待窗计入搜索预算**

`replaceToastBlocking` 内等待时长计算（原文 `val waitMs = if (config.isThinkingMode()) {...}.coerceIn(...)`）替换为：

```kotlin
            // LLM 侧等待窗 + 联网搜索预算（仅搜索开启时追加）：思考模式或搜索的同步兜底
            // 都更耗时，须保证 CountDownLatch 不会在 LLM 结果返回前提前超时丢弃替换；
            // 上限 MAX_WAIT_MS 保护 WebSocket 读取线程不被拖垮。
            val llmWaitMs = if (config.isThinkingMode()) {
                config.getThinkingTimeoutMs()
            } else {
                config.getTimeoutMs()
            }
            val searchBudgetMs = if (config.getSearchMode() == llm.miband.littlewhite.config.ConfigKeys.VALUE_SEARCH_MODE_OFF) {
                0L
            } else {
                config.getSearchTimeoutMs()
            }
            val waitMs = (llmWaitMs + searchBudgetMs).coerceIn(1000L, MAX_WAIT_MS)
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`SettingsScreen.kt:1310` 的既有 `ask("settings-connection-test", ...)` 调用因默认参数不受影响）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/hook/LlmClient.kt app/src/main/kotlin/llm/miband/littlewhite/hook/WebSocketInterceptor.kt app/src/main/kotlin/llm/miband/littlewhite/hook/MiHealthHook.kt
git commit -m "feat(websearch): inject search context into LLM system prompt and prefetch on recognition"
```

---

## Task 6: 设置页「联网搜索」配置 Card + 测试按钮

**Files:**
- Modify: `app/src/main/kotlin/llm/miband/littlewhite/ui/SettingsScreen.kt`

- [ ] **Step 1: 插入「联网搜索」分组**

`ConfigTabContent` 的 LazyColumn 内、`item(key = "generation")` 块结束（`PresetSection(CATEGORY_GENERATION ...)` 后）与 `// ---------- 分组 3：会话设置 ----------` 之间插入两个 item：

```kotlin
            // ---------- 联网搜索 ----------
            item(key = "websearchTitle") {
                SmallTitle("联网搜索")
            }
            item(key = "websearch") {
                var refreshTick by remember { mutableStateOf(0) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    key(refreshTick) {
                        // 受控组件用本地状态驱动 UI，避免 RemotePreferences 非响应式导致界面不更新
                        var searchMode by remember { mutableStateOf(config.getSearchMode()) }
                        val modeLabels = listOf("关闭", "指令词触发", "总是联网")
                        val modeValues = listOf("off", "prefix", "always")
                        OverlayDropdownPreference(
                            title = "搜索触发",
                            summary = "指令词触发：以「搜索/查一下」等开头的提问才联网；总是：每个问题都先搜索",
                            items = modeLabels,
                            selectedIndex = modeValues.indexOf(searchMode).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                searchMode = modeValues[index]
                                config.setSearchMode(searchMode)
                            },
                        )
                        var provider by remember { mutableStateOf(config.getSearchProvider()) }
                        val providerLabels = listOf("SearXNG（自托管）", "Tavily", "Brave")
                        val providerValues = listOf("searxng", "tavily", "brave")
                        OverlayDropdownPreference(
                            title = "搜索服务",
                            summary = "SearXNG 无需 Key；Tavily/Brave 需填写 API Key（Tavily 返回已清洗正文）",
                            items = providerLabels,
                            selectedIndex = providerValues.indexOf(provider).coerceAtLeast(0),
                            onSelectedIndexChange = { index ->
                                provider = providerValues[index]
                                config.setSearchProvider(provider)
                            },
                        )
                        TextInputField(
                            initialValue = config.getSearchEndpoint(),
                            label = "SearXNG 实例地址",
                            placeholder = "https://searx.example.org",
                            onValueChange = { config.setSearchEndpoint(it) },
                        )
                        ApiKeyField(
                            initialValue = config.getSearchApiKey(),
                            onValueChange = { config.setSearchApiKey(it) },
                        )
                        TextInputField(
                            initialValue = config.getSearchTriggerWords().joinToString("\n"),
                            label = "指令词（每行一个）",
                            singleLine = false,
                            placeholder = "搜索 / 查一下 / 帮我查一下",
                            onValueChange = { text ->
                                config.setSearchTriggerWords(
                                    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                                )
                            },
                        )
                        NumberInputField(
                            label = "结果条数（1-10）",
                            initialValue = config.getSearchMaxResults(),
                            onValueChange = { config.setSearchMaxResults(it) },
                        )
                        NumberInputField(
                            label = "搜索超时（毫秒）",
                            initialValue = config.getSearchTimeoutMs().toInt(),
                            onValueChange = { config.setSearchTimeoutMs(it) },
                        )
                        var testing by remember { mutableStateOf(false) }
                        ArrowPreference(
                            title = "测试搜索",
                            summary = "用当前配置请求一次，验证实例地址/API Key 是否有效",
                            enabled = !testing,
                            onClick = {
                                testing = true
                                scope.launch(Dispatchers.IO) {
                                    SearchManager.init(config)
                                    val msg = SearchManager.testSearch("今天天气")
                                    withContext(Dispatchers.Main) {
                                        testing = false
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        )
                    }
                }
            }
```

补充 import（文件顶部 import 区）：

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import llm.miband.littlewhite.search.SearchManager
```

注：`Toast`、`scope`、`context`、`ArrowPreference`、`OverlayDropdownPreference`、`TextInputField`、`NumberInputField`、`ApiKeyField`、`Card`、`SmallTitle` 均已在文件内使用（Task 内仅复用既有符号）。若 `Dispatchers`/`withContext` 已 import（本文件 About Tab 已有同款用法，通常已存在），则跳过对应 import 行。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 手工验证（App 进程设置页）**

Install debug 包并打开设置 → 配置 Tab →「联网搜索」：切换触发/服务不崩溃；填写 SearXNG 地址后点「测试搜索」出现 Toast 结果或失败提示（此时可先用任意公网 HTTPS 实例临时验证连通性，如 `https://searx.be`，最终以自建实例为准）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/llm/miband/littlewhite/ui/SettingsScreen.kt
git commit -m "feat(websearch): add web search settings card with test button"
```

---

## Task 7: 文档 + AGENTS.md 同步 + 全量构建

**Files:**
- Create: `docs/websearch.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: 撰写使用文档 `docs/websearch.md`**

内容必须覆盖：功能定位与触发模式表；三个 Provider 配置速查（SearXNG 需在 `settings.yml` 的 `search.formats` 加入 `json` 并重启；Tavily 免费额度与 basic=1credit；Brave 免费档已停发）；**cleartext 限制**（模块跑在宿主 `com.mi.health` 进程内，明文 HTTP 被宿主网络策略拦截，需给自建实例配 HTTPS 反代，或经 Cloudflare Tunnel/Tailscale HTTPS）；延迟预算说明（预取命中≈0 增量，同步兜底受 `search_timeout_ms` 约束，总等待窗 clamp 15s）；隐私说明（只有触发词命中的提问才会上送外部搜索服务）。文档顶部使用「配置速查表 + 已知限制」结构。

- [ ] **Step 2: 更新 AGENTS.md**

目录结构新增：

```text
│           ├── search/               # 联网搜索（Provider 抽象 + SearchManager 预取缓存）
│           │   ├── WebSearchProvider.kt
│           │   ├── SearchQueryKit.kt
│           │   ├── SearchHttp.kt
│           │   ├── SearxngProvider.kt
│           │   ├── TavilyProvider.kt
│           │   ├── BraveProvider.kt
│           │   └── SearchManager.kt
```

「相关文档」表新增一行 `| docs/websearch.md | 联网搜索配置与已知限制（cleartext/实例 json 格式/延迟预算） |`；「开发约定」第 2 条追加「联网搜索同样只用 HttpURLConnection，零运行时新增依赖」。

- [ ] **Step 3: 全量构建 + 全量单测**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: 全部 PASS 且 BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add docs/websearch.md AGENTS.md
git commit -m "docs(websearch): add usage doc and sync AGENTS.md"
```

---

## Task 8（可选）: jsoup 抓取正文增强

> 跳过本任务不影响主功能：三个 Provider 的 snippet 已足以支撑手环 80 字内回答（Tavily 尤甚）。需要「要点更全的长回答」或「SearXNG 空 content 时兜底」再启用。

**Files:**
- Modify: `gradle/libs.versions.toml` / `app/build.gradle.kts`
- Create: `app/src/main/kotlin/llm/miband/littlewhite/search/PageFetcher.kt`
- Modify: `search/SearchManager.kt` / `config/ConfigKeys.kt` / `config/ConfigStore.kt` / `ui/SettingsScreen.kt`

- [ ] **Step 1: 引依赖（jsoup 1.23.2，唯一无 CVE 标记的稳定版本线）**

`libs.versions.toml` `[versions]` 加 `jsoup = "1.23.2"`，`[libraries]` 加 `jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }`；`app/build.gradle.kts` dependencies 加 `implementation(libs.jsoup)`。

- [ ] **Step 2: PageFetcher**

`PageFetcher.kt`：`fetchText(url, timeoutMs, maxChars=1500): String?` —— 用 `Jsoup.connect(url).timeout(timeoutMs.toInt()).userAgent(SearchHttp.UA).maxBodySize(512*1024).get()`，选择器顺序取正文 `article, main, .post-content, .article-content, [itemprop=articleBody]`，回退 `body`；`doc.text()` 后压缩空白截断。全部异常 try-catch 返回 null 记日志（延续「绝不干扰宿主」纪律）。

- [ ] **Step 3: 接线（每处均为小改）**

- `ConfigKeys`：新增 `KEY_SEARCH_FETCH_TOP_N` / `DEFAULT_SEARCH_FETCH_TOP_N = 2`（0=关闭）。
- `ConfigStore`：`getSearchFetchTopN(): Int = prefs.getInt(...).coerceIn(0, 3)` + setter。
- `SearchManager.contextFor`：缓存命中后、`buildContext` 前，对前 `fetchTopN` 条结果尝试 `PageFetcher.fetchText(url, timeout=search_timeout_ms/2)` 追加进该条 `content`（成功则替换 snippet）。预取路径同样生效（doSearchAndCache 内先 fetch 再入缓存）。
- `SettingsScreen` 联网搜索 Card：加 `NumberInputField("抓取正文条数（0=仅摘要）", ...)`。

- [ ] **Step 4: 验证**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL；真机用低 content 命中率关键词（如冷门名词）对照开/关抓取的回答质量。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(websearch): optionally fetch full page text via jsoup"
```

---

## Task 9（可选）: DuckDuckGo HTML 免 Key Provider

> **缘起（2026-09-03 评估 `@erdium/pi-termux-web-tools`）**：该包以「DuckDuckGo HTML 端点搜索 + SearXNG 兜底」实现免 Key 搜索，证明 DDG-HTML 可作为零配置兜底。但它本身是 Pi Coding Agent（Termux/Node）插件，运行时与本项目（进程内 Kotlin）不兼容，只借鉴其 Provider 思路。作为「无自建 SearXNG、又不想配商业 Key」的兜底加入；主链不依赖它。

- [ ] **Step 1: 不引任何依赖**——纯 HttpURLConnection 抓 `https://html.duckduckgo.com/html/?q=` 结果页，用正则提取结果块。

- [ ] **Step 2: 实现 `DuckDuckGoProvider.kt`**

```kotlin
package llm.miband.littlewhite.search

import llm.miband.littlewhite.log.LogCollector
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * DuckDuckGo HTML 端点（免 Key 兜底）。
 * 注意：非官方 HTML 抓取，结构变更/限流可能失效；仅作 SearXNG/商业 Key 之外的
 * 无配置兜底。失败返回 null（上层不降级其它 Provider 行为）。
 */
class DuckDuckGoProvider : WebSearchProvider {

    override val name: String = "ddg"

    override fun search(query: String, maxResults: Int, timeoutMs: Long): List<SearchResult>? {
        val url = buildString {
            append("https://html.duckduckgo.com/html/?q=")
            append(URLEncoder.encode(query, Charsets.UTF_8.name()))
            append("&kl=cn-zh&safesearch=moderate")
        }
        val html = SearchHttp.get(url, mapOf("Accept" to "text/html"), timeoutMs) ?: return null
        return parse(html).take(maxResults).ifEmpty { null } // 抓取成功但零命中视为失败
    }

    /** 提取 result__a（标题/链接）与 result__snippet（摘要）；internal 便于 fixture 单测 */
    internal fun parse(html: String): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        // 每条结果一个 block；再取块内标题与摘要
        for (block in BLOCK.split(html)) {
            val mTitle = TITLE.matcher(block)
            if (!mTitle.find()) continue
            val title = htmlDecode(mTitle.group(1).trim())
            val href = mTitle.group(2).trim().ifEmpty { continue }
            // 跳过官方跳转外的辅助条目
            val snippet = SNIPPET.matcher(block).let { if (it.find()) htmlDecode(it.group(1).trim()) else "" }
            if (title.isNotEmpty() && href.isNotEmpty()) {
                out += SearchResult(title = title, url = href, content = snippet)
            }
        }
        return out
    }

    private fun htmlDecode(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private companion object {
        private val BLOCK = Pattern.compile("(?i)<div[^>]*class=\"[^\"]*result[^\"]*\"[^>]*>.*?(?=<div[^>]*class=\"[^\"]*result)|$", Pattern.DOTALL)
        private val TITLE = Pattern.compile("<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL)
        private val SNIPPET = Pattern.compile("<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL)
    }
}
```

- [ ] **Step 3: 接线**

- `ConfigKeys`：`KEY_SEARCH_PROVIDER` 取值新增 `VALUE_SEARCH_PROVIDER_DDG = "ddg"`。
- `SearchManager.validate`：`"ddg" -> null`（无需端点/Key）。
- `SearchManager.createProvider`：`"ddg" -> DuckDuckGoProvider()`。
- UI Provider 下拉 items 追加 `"DuckDuckGo（免 Key）"` / `"ddg"`。
- 测试搜索 UI 已覆盖（`testSearch` 走统一入口）。

- [ ] **Step 4: 真机验证**——SearXNG/Tavily/Brave 全停用时选 ddg，语音触发「查一下 xx」Toast 有内容；连打 3 次间隔 >15s 确认未被限流。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(websearch): add keyless duckduckgo html provider as fallback"
```

---

## Task 10（可选）: Jina Reader 正文路由

> 与 Task 8（jsoup 本地抓取）互补：`https://r.jina.ai/<url>` 免 Key 返回页面清洗后的 Markdown/纯文本（限流较严），把「正文获取」抽象成可切换的 Reader 通道，jsoup 与 Jina 二选一或先后兜底。仅当需要 Task 8 的正文增强时启用。

- [ ] **Step 1: 引依赖**——复用 Task 8 的 `PageFetcher` 抽象，不新增依赖。

- [ ] **Step 2: 改 `PageFetcher.kt` 支持双通道**

`fetchText(url, timeoutMs, maxChars): String?` 内部：先走 Jina（`SearchHttp.get("https://r.jina.ai/" + url, timeout = timeoutMs/2)`，非空返回），失败/空回退 jsoup 直连；两通道都失败返回 null。

- [ ] **Step 3: 复用 Task 8 的接线**——`fetchTopN` 配置项与 `SearchManager` fetch 挂载点不变，仅内部多一层路由。

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(websearch): route full-text fetch through jina reader with jsoup fallback"
```

---

## 实施顺序与依赖关系

```
T1（配置/依赖） → T2（纯函数） → T3（Provider）
                  T2、T3 完成 → T4（SearchManager）→ T5（LLM/处理器接线）
T1 → T6（UI，依赖 T1 配置读写）
T7（文档，可随时）｜T8/T9/T10（均可选、互不依赖，可独立启用，建议全部放在主链验收之后）
```

T1-T3 可并行（互不依赖）；T4 依赖 T2+T3；T5 依赖 T4；T6 依赖 T1；T8 依赖 T4（新增 jsoup 依赖与 fetch 挂载）；T9 依赖 T3 的 Provider 接线模式；T10 依赖 T8。

## 验证清单（端到端真机）

1. 设置页「测试搜索」返回「搜索成功：searxng | N 条 | xms」或明确失败提示；
2. 语音「查一下今天的新闻」→ 手环 Toast 在 ≤15s 内显示含时效性内容的 LLM 回答；
3. 语音「播放音乐」→ 不触发搜索（prefix 模式未命中），回答与改动前一致；
4. 关闭搜索（off）后 logcat 无任何对外搜索请求日志；
5. 宿主机断网时 LLM 回答路径与改动前一致（搜索失败静默降级，不丢原始 Toast）。

## 已知风险与对策

| 风险 | 对策 |
|---|---|
| 预取浪费（无 Toast 或走小爱模式） | 缓存 TTL 30s + MAX 64 条目裁剪；prefix 触发天然收敛请求量 |
| 同步兜底搜索 + LLM 超总窗 | MiHealthHook 等待窗 +search 预算后 clamp 15s；兜底仅发生在缓存缺失的少见路径 |
| SearXNG 公网实例不稳/关闭 json | 文档强调自建实例 + HTTPS；Provider 对 403 记日志并降级 |
| 明文 HTTP 被宿主策略拦截 | 文档指引 HTTPS 反代；代码不改宿主网络策略（超出模块职责） |
| Provider Key/端点脏数据 | ConfigStore 收敛 trim/加密/空值回退；UI 测试按钮即时反馈 |
