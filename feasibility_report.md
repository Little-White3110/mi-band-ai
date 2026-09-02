# 小米手环/手表注入 DeepSeek — KernelSU + LSPosed 可行性分析报告

## 一、原方案概述

### 1.1 原帖核心思路

原帖作者（瓜子920）发现小米运动健康 App 中小爱同学的网络请求为明文传输，因此采用 **本地 MITM 代理** 方案实现注入：

| 组件 | 作用 |
|------|------|
| Termux + proot-distro Ubuntu | 在 Android 上运行 Linux 环境 |
| mitmproxy | 作为本地 HTTP/HTTPS 代理服务器 |
| xiaoai.py | mitmproxy 插件脚本，拦截小爱请求并转发至 DeepSeek API |
| WiFi 手动代理 | 将系统网络流量导向 mitmproxy (127.0.0.1:8080) |
| CA 证书 + Magisk TrustUserCerts 模块 | 解决 HTTPS 抓包证书信任问题 |

### 1.2 原方案操作步骤（用户视角）

1. 安装 Termux，搭建 proot-distro Ubuntu 环境（命令行操作繁琐）
2. 在 Ubuntu 内安装 Python 环境和 mitmproxy
3. 下载 xiaoai.py 脚本并传入 Ubuntu 环境
4. 配置 DeepSeek API Key 环境变量并运行 mitmdump
5. 进入 WiFi 设置手动配置 HTTP 代理为 127.0.0.1:8080
6. 安装 mitmproxy CA 证书（需借助 Magisk 模块 trustmealready 信任用户证书）
7. 每次使用需：打开 Termux → 启动 Ubuntu → 运行脚本 → 确保 WiFi 代理设置正确

### 1.3 原方案痛点

| 痛点 | 严重程度 | 说明 |
|------|----------|------|
| 操作复杂，门槛高 | ★★★★★ | 需手动安装配置 Linux 环境、Python、依赖 |
| 无法开机自启 | ★★★★☆ | 每次重启手机需重新手动启动脚本 |
| WiFi 代理全局生效 | ★★★☆☆ | 所有 App 流量都走代理，可能影响其他应用 |
| Termux 后台易被杀死 | ★★★☆☆ | 国产 ROM 电池优化可能杀后台 |
| 证书安装需额外模块 | ★★☆☆☆ | 已有 root 的情况下尚可，但步骤多 |
| 切换 WiFi 需重设代理 | ★★☆☆☆ | 切换网络时代理设置可能失效 |

---

## 二、技术方案对比分析

### 方案 A：纯 KernelSU 模块（系统级透明代理）

**核心思路：** 用 KernelSU 模块替代 Termux 环境，通过 iptables 透明代理 + 系统级证书注入实现无感拦截。

#### 实现架构

```
┌─────────────────────────────────────────────────┐
│                  Android 系统                     │
│  ┌───────────┐    ┌──────────────────────────┐  │
│  │ 小米运动健康│───▶│ iptables REDIRECT        │  │
│  │   App      │    │ (KernelSU service.sh)    │  │
│  └───────────┘    └─────┬────────────────────┘  │
│                         │ 8080                   │
│                         ▼                        │
│  ┌──────────────────────────────────────────┐   │
│  │  mitmproxy (精简二进制/内置Python运行时)  │   │
│  │  + xiaoai.py 逻辑 (集成/重写)             │   │
│  └──────────────────────────────────────────┘   │
│                         │                        │
│                         ▼                        │
│  ┌──────────────────────────────────────────┐   │
│  │         DeepSeek API (外部网络)           │   │
│  └──────────────────────────────────────────┘   │
│                                                   │
│  ┌──────────────────────────────────────────┐   │
│  │ 系统证书注入 (system/etc/security/cacerts)│   │
│  │ → mitmproxy CA 证书自动安装为系统证书      │   │
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

#### KernelSU 模块具体工作

| 模块组件 | 实现方式 |
|----------|----------|
| **自动启动 mitmproxy** | `service.sh` 中在 `boot_completed` 后启动 mitmproxy 守护进程 |
| **透明代理 iptables** | 在 `service.sh` 中执行 `iptables -t nat -A OUTPUT -p tcp --dport 80/443 -m owner --uid-owner <小米运动健康UID> -j REDIRECT --to-port 8080`，仅对目标 App 重定向 |
| **系统证书注入** | 通过 `system/` overlay 将 mitmproxy CA 证书放入 `/system/etc/security/cacerts/`，无需 trustmealready 模块 |
| **二进制打包** | 内置 mitmproxy 二进制（或用交叉编译的 Go 代理替代），避免 Termux 环境依赖 |
| **配置管理** | KernelSU 模块配置系统 + WebUI，让用户在 KernelSU 管理器中输入 API Key |
| **保活机制** | 通过 `service.sh` 循环检测 mitmproxy 进程，崩溃自动重启 |

#### 可行性评估

| 评估维度 | 评分 | 分析 |
|----------|------|------|
| 技术可行性 | ★★★★☆ | iptables 重定向 + 系统证书注入都是成熟方案，但 mitmproxy 依赖 Python 运行时，打包进模块体积较大（约 30-50MB），可用更轻量的 Go 代理替代 |
| 开发难度 | ★★★☆☆ | 需处理：二进制兼容性、iptables 规则管理、进程保活、SELinux 策略 |
| 用户体验提升 | ★★★★★ | 安装模块后重启即可，无需任何手动配置（API Key 通过 WebUI 配置一次） |
| 维护成本 | ★★★☆☆ | 小米运动健康 App 更新可能导致 API 变化，需更新脚本 |
| 风险点 | 中等 | iptables 规则与 VPN 类应用冲突；mitmproxy/Go 代理需正确处理 HTTP/2 |

---

### 方案 B：纯 LSPosed 模块（App 内 Hook 拦截）⭐ 推荐

**核心思路：** 用 LSPosed 直接 Hook 小米运动健康 App 的 HTTP 客户端，在 App 进程内完成请求拦截和替换，**完全无需代理**。

#### 实现架构

```
┌─────────────────────────────────────────────────┐
│            小米运动健康 App 进程空间               │
│                                                   │
│  ┌──────────────────────────────────────────┐   │
│  │  App 原始逻辑                              │   │
│  │  ┌─────────┐   ┌───────────────────┐     │   │
│  │  │ 手环/手表│──▶│ 小爱同学 HTTP 请求  │     │   │
│  │  │ 语音输入 │   │ (OkHttp/HttpURLConnection)│   │
│  │  └─────────┘   └────────┬──────────┘     │   │
│  └─────────────────────────┼────────────────┘   │
│                            │                     │
│                   ┌────────▼────────┐           │
│                   │  LSPosed Hook   │           │
│                   │ (Xposed 模块)    │           │
│                   │                  │           │
│                   │ 1. 拦截小爱请求  │           │
│                   │ 2. 本地构造 DeepSeek 请求    │
│                   │ 3. 同步/异步调用 API        │
│                   │ 4. 伪装成小爱响应返回给App  │
│                   └────────┬────────┘           │
│                            │                     │
│                   ┌────────▼────────┐           │
│                   │   DeepSeek API  │           │
│                   │  (直连, 无代理)  │           │
│                   └─────────────────┘           │
└─────────────────────────────────────────────────┘
```

#### LSPosed Hook 点分析

小米运动健康 App（com.mi.health）使用的 HTTP 客户端通常是以下之一：

| Hook 目标 | 方法 | 难度 |
|-----------|------|------|
| **OkHttp** (最可能) | Hook `OkHttpClient.newCall()` 或 `RealCall.execute()/enqueue()`，检查请求 URL/Host，匹配小爱接口时替换 | ★★★☆☆ |
| **Retrofit** | 若底层用 OkHttp 则同上 | ★★★☆☆ |
| **HttpURLConnection** | Hook `URL.openConnection()` 或 `HttpURLConnection.getInputStream()` | ★★★★☆ |
| **WebView** | Hook `WebViewClient.shouldInterceptRequest()`（不太可能用于小爱） | ★★☆☆☆ |

#### 关键技术点

1. **识别小爱 API 请求：** 原帖提到请求是明文的，通过 HttpCanary 可见。需要反编译小米运动健康 APK 确认小爱接口的 URL 特征（如 Host、Path、参数结构）。

2. **请求拦截与重建：** 在 Hook 回调中：
   - 读取原始请求体（语音识别后的文本问题）
   - 在后台线程调用 DeepSeek API（App 本身有网络权限，无需额外权限）
   - 将 DeepSeek 响应格式转换为小爱响应格式（关键！需适配 Response JSON 结构）
   - 构造假 Response 返回给 App

3. **异步处理：** 网络请求不能阻塞主线程，需要正确处理 OkHttp 的同步/异步调用链。

4. **作用域控制：** LSPosed 模块可精确设置作用域仅为小米运动健康 App，不影响其他任何应用。

#### 可行性评估

| 评估维度 | 评分 | 分析 |
|----------|------|------|
| 技术可行性 | ★★★★★ | LSPosed/Xposed Hook HTTP 客户端是非常成熟的技术，有大量先例（如去广告、翻译模块） |
| 开发难度 | ★★★★☆ | 需要反编译分析小米运动健康的网络代码；需适配小爱→DeepSeek 的请求/响应格式转换；难点是异步网络调用的正确 Hook |
| 用户体验提升 | ★★★★★ | 安装模块、勾选作用域、重启App即可使用，无需 Termux、无需代理设置、无需证书、无需后台服务 |
| 性能开销 | ★★★★★ | 几乎零额外开销（App进程内直接处理，无额外网络跳转） |
| 维护成本 | ★★★☆☆ | App 更新可能混淆代码导致 Hook 点失效；API 变化需适配 |
| 隐蔽性 | ★★★★★ | 完全在 App 进程内运行，无后台服务、无代理端口，最难被检测 |
| 风险点 | 中等 | 小米运动健康可能使用证书锁定(SSL Pinning)，但原帖说明请求是明文的，可能不走HTTPS或Pinning较弱；混淆可能增加逆向难度 |

---

### 方案 C：KernelSU + LSPosed 组合方案

**核心思路：** LSPosed 负责 App 内 Hook（核心功能），KernelSU 负责辅助能力。

| 层 | 组件 | 职责 |
|----|------|------|
| **LSPosed 层** | Xposed 模块 | Hook 小米运动健康 HTTP 客户端，执行 API 拦截和转发逻辑 |
| **KernelSU 层** | 辅助模块 | ① WebUI 配置界面（输入 API Key、模型选择）② 可选：若有 SSL Pinning 则自动绕过（如用 TrustUserCerts 注入或 frida-like hook）③ 模块配置持久化 |

#### 可行性评估

| 评估维度 | 评分 | 分析 |
|----------|------|------|
| 技术可行性 | ★★★★★ | 两个成熟技术的组合，无技术障碍 |
| 开发难度 | ★★★★☆ | 需分别开发 LSPosed 模块和 KernelSU 模块，并建立通信机制 |
| 用户体验 | ★★★★★ | 最佳体验：安装一个 KernelSU 模块（可包含 LSPosed 模块 APK 自动部署），在 WebUI 配置即可 |
| 复杂度 | ★★★☆☆ | 模块间通信增加一定复杂度，可用 `ksud module config` 或文件共享传递配置 |

---

## 三、综合可行性结论

### 3.1 推荐方案排序

| 排名 | 方案 | 推荐指数 | 一句话总结 |
|------|------|----------|------------|
| 🥇 | **方案 B：纯 LSPosed 模块** | ⭐⭐⭐⭐⭐ | 最优解！App 内 Hook，无代理、无证书、无后台、零干扰 |
| 🥈 | **方案 C：KernelSU + LSPosed** | ⭐⭐⭐⭐ | 体验更佳但开发量更大，适合追求完美的场景 |
| 🥉 | **方案 A：纯 KernelSU 模块** | ⭐⭐⭐ | 可行但不够优雅，适合无法使用 LSPosed 的场景 |
| ❌ | 原方案（Termux + mitmproxy）| ⭐⭐ | 能用但操作繁琐，仅适合临时测试 |

### 3.2 为什么 LSPosed 方案远优于原方案

| 对比项 | 原方案 (Termux + mitmproxy) | LSPosed 模块方案 |
|--------|------------------------------|-------------------|
| 是否需要 Linux 环境 | ✅ 需要 Termux + Ubuntu | ❌ 不需要 |
| 是否需要 Python 运行时 | ✅ 需要 | ❌ 不需要 |
| 是否需要手动设代理 | ✅ 每次换WiFi都要检查 | ❌ 完全不需要 |
| 是否需要安装 CA 证书 | ✅ 需要 + Magisk模块 | ❌ 不需要 |
| 是否影响其他App | ✅ 全局代理影响所有流量 | ❌ 仅作用于小米运动健康 |
| 是否需要保活后台 | ✅ Termux 易被杀 | ❌ 无额外后台进程 |
| 开机是否自启 | ❌ 需手动启动 | ✅ 自动生效 |
| 内存/电量开销 | 较高（mitmproxy常驻） | 极低（进程内处理） |
| 安装步骤数 | 6+ 步复杂操作 | 安装+勾选作用域+重启App |
| 隐蔽性/防检测 | 弱（代理端口可被检测） | 强（进程内运行无痕迹） |

### 3.3 开发路线图建议（以方案 B 为例）

#### 第一阶段：逆向分析（1-2天）
1. 下载小米运动健康 APK
2. 使用 JADX/MT 管理器反编译
3. 定位小爱同学相关代码：搜索 API 域名、关键词（`xiaoai`、`voice`、`assistant`、`chat` 等）
4. 确认 HTTP 客户端类型（OkHttp 版本）
5. 抓包确认完整的请求/响应格式（可参考原帖的 xiaoai.py 逻辑）

#### 第二阶段：LSPosed 模块开发（2-3天）
1. 创建 Android 项目，集成 Xposed API（`de.robv.android.xposed:api`）
2. 实现 Hook 入口，在 `handleLoadPackage` 中针对 `com.mi.health`
3. Hook OkHttp 的请求拦截机制（`Interceptor` 接口或 `RealCall`）
4. 实现请求匹配逻辑：识别小爱 API 请求
5. 实现 DeepSeek API 调用（直接用 `java.net.HttpURLConnection` 或 OkHttp）
6. 实现响应格式转换：DeepSeek Response → 小爱 Response 格式

#### 第三阶段：配置与 UI（1天）
1. 添加模块设置页（Xposed 的 `IXposedMod` 提供 UI 或用 LSPosed 的作用域偏好）
2. API Key 输入、模型选择（deepseek-chat / deepseek-reasoner）
3. 可选：开关功能、自定义系统 Prompt

#### 第四阶段：KernelSU 集成（可选，1天）
1. 创建 KernelSU 模块包装
2. 利用 WebUI 提供更美观的配置界面
3. 通过 `ksud module config` 存储 API Key 等配置
4. 实现自动安装/更新 LSPosed APK 的能力

#### 第五阶段：测试发布（1-2天）
1. 在真机上测试手环/手表语音交互
2. 测试各种小爱指令场景
3. 测试 App 更新后的兼容性
4. 处理边界情况（网络错误、API 限流、超时等）

**总开发周期预估：5-9 天**

### 3.4 潜在风险与应对

| 风险 | 概率 | 影响 | 应对措施 |
|------|------|------|----------|
| 小米运动健康使用 SSL Pinning | 中 | Hook HTTPS 请求失败 | 若原帖确认是明文HTTP则无此问题；如有Pinning可 Hook `CertificatePinner` 或 `TrustManager` 绕过 |
| App 混淆导致 Hook 点难找 | 中 | 开发时间增加 | 使用 JADX 搜索字符串常量（URL 关键词）定位；Hook 通用 HTTP 入口（如 OkHttpClient）而非具体业务方法 |
| 小爱 API 签名/加密 | 低 | 无法直接替换请求 | 原帖确认是明文请求，推测无额外签名；如有签名需逆向签名算法 |
| 小米运动健康更新导致 Hook 失效 | 高 | 模块需更新 | Hook 稳定的框架层（OkHttp 而非业务代码）；版本检测提示更新 |
| 响应格式不匹配 | 中 | 手环显示异常 | 抓包获取小爱完整响应结构，用 Gson 等库进行对象映射转换 |
| KernelSU + ZygiskNext 兼容性 | 低 | LSPosed 无法工作 | 明确要求用户安装 ZygiskNext；提供 Magisk 版本兼容支持 |

### 3.5 最终结论

**使用 KernelSU 模块 + LSPosed 模块来简化该操作是完全可行的，且方案 B（纯 LSPosed 模块）在技术可行性、用户体验、性能开销等各方面均大幅优于原 Termux + mitmproxy 方案。**

核心优势总结：
- **操作从 6+ 步简化为"安装模块 + 勾选作用域 + 配置 API Key"**
- **无需 Termux、无需 Linux 环境、无需代理设置、无需证书安装**
- **开机自动生效，无后台服务，不影响其他应用**
- **性能开销极低（进程内处理，无额外网络跳转层）**
- **可通过 KernelSU 模块 WebUI 提供优雅的配置体验**

建议优先开发纯 LSPosed 模块（方案 B），后续可视需求添加 KernelSU 模块增强（方案 C）。
