# LSPosed 模块开发完全指南

> 基于 Modern Xposed API 102 — 从零开始构建 LSPosed 模块
>
> 版本：API 102 | 更新日期：2026年7月 | 适用框架：LSPosed v1.9.0+

---

## 目录

1. [LSPosed 概述](#1-lsposed-概述)
2. [环境准备](#2-环境准备)
3. [项目搭建](#3-项目搭建)
4. [模块配置详解](#4-模块配置详解)
5. [核心 API 详解 (API 102)](#5-核心-api-详解-api-102)
6. [Hook 模型与拦截器链](#6-hook-模型与拦截器链)
7. [模块生命周期](#7-模块生命周期)
8. [作用域 (Scope) 管理](#8-作用域-scope-管理)
9. [Native Hook](#9-native-hook)
10. [数据共享与通信](#10-数据共享与通信)
11. [传统 API vs 现代 API 对比](#11-传统-api-vs-现代-api-对比)
12. [完整示例](#12-完整示例)
13. [常见问题与调试](#13-常见问题与调试)

---

## 1. LSPosed 概述

LSPosed 是一个基于 **Riru / Zygisk** 的 ART Hook 框架，提供与原版 Xposed 相同的 API，使用 **LSPlant** 进行方法 Hook。它支持 Android 8.1 ~ 14+，并且极难被目标应用检测到。[^1]

与原始 Xposed 框架相比，LSPosed 的核心优势：

- **精准注入**：仅注入被勾选的应用，其他应用运行在干净环境中
- **无需重启**：对于不需要注入系统服务的模块，重启目标应用即可激活
- **反检测能力强**：文件系统不留可疑痕迹，不需要独立管理器应用
- **双重注入支持**：同时支持 Riru 和 Zygisk 两种注入方式
- **内存优化**：采用动态生成钩子类技术，比传统 YAHFA 框架节省约 40% 内存

> **API 版本说明：** LSPosed 支持两套 API — 传统的 `de.robv.android.xposed` API（兼容原版 Xposed）和现代的 `io.github.libxposed` API（本文重点）。API 102 是最新版本（2026年6月发布），引入了热重载（Hot Reload）和生命周期回调分离等新特性。[^2]

---

## 2. 环境准备

### 2.1 开发环境要求

| 组件 | 最低版本 | 推荐版本 |
|------|----------|----------|
| Android Studio | Arctic Fox (2020.3.1) | Ladybug (2024.2.1+) |
| Gradle | 7.0 | 8.7+ |
| Android Gradle Plugin (AGP) | 7.0 | 8.5+ |
| JDK | 11 | 17 |
| Kotlin（可选） | 1.6 | 2.0+ |
| compileSdk / targetSdk | 27 (Android 8.1) | 34+ |

### 2.2 设备环境要求

- Android 8.1 ~ 14+ 设备（已 Root）
- Magisk ≥ 24.0（推荐使用 Zygisk 模式）
- 已安装 LSPosed 模块（通过 Magisk 刷入）

> **开发提示：** 推荐使用 Android 模拟器（如带有 Magisk 的雷电模拟器 / 夜神模拟器）进行开发调试，可以避免频繁重启真机。

---

## 3. 项目搭建

### 3.1 创建 Android 项目

在 Android Studio 中创建一个空项目。模块本身是一个普通 Android App，**不需要 Activity**（除非你需要设置界面）。

### 3.2 添加依赖

在模块的 `build.gradle.kts` 中添加 Modern Xposed API 依赖：[^2]

```kotlin
// app/build.gradle.kts
dependencies {
    // Modern Xposed API（仅编译时需要，框架运行时提供）
    compileOnly("io.github.libxposed:api:102.0.0")

    // 可选：框架通信服务（需要在模块 App 中与框架通信时使用）
    implementation("io.github.libxposed:service:102.0.0")

    // 可选：辅助开发库，提供更友好的 API 封装
    // implementation("io.github.libxposed:helper:0.0.1")
}
```

### 3.3 ProGuard / R8 配置

如果启用了代码混淆，需要在 `proguard-rules.pro` 中添加：

```
# 保留模块入口类不被移除
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```

---

## 4. 模块配置详解

Modern Xposed API 不再使用 `AndroidManifest.xml` 中的 `meta-data` 标签。模块信息通过以下方式声明：[^1]

### 4.1 AndroidManifest.xml

模块名称使用 `android:label`，模块描述使用 `android:description`：

```xml
<application
    android:label="我的 LSPosed 模块"
    android:description="这是一个示例模块的描述"
    android:allowBackup="true"
    ... >
</application>
```

### 4.2 module.prop — 模块属性文件

在 `src/main/resources/META-INF/xposed/module.prop` 中创建：

```properties
# META-INF/xposed/module.prop（Java Properties 格式）
minApiVersion=101
targetApiVersion=102
staticScope=true
exceptionMode=protective
autoHotReload=true
```

| 属性 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `minApiVersion` | int | 是 | 模块要求的最低 Xposed API 版本 |
| `targetApiVersion` | int | 是 | 模块目标 Xposed API 版本 |
| `staticScope` | boolean | 否 | 是否禁止用户在作用域外应用模块 |
| `exceptionMode` | string | 否 | 异常模式：`protective`（默认）或 `passthrough` |
| `autoHotReload` | boolean | 否 | **API 102** 应用更新时是否自动热重载 |

### 4.3 java_init.list — 入口类声明

在 `src/main/resources/META-INF/xposed/java_init.list` 中声明入口类（每行一个完整类名）：

```
com.example.mymodule.MyXposedModule
```

### 4.4 scope.list — 作用域声明

在 `src/main/resources/META-INF/xposed/scope.list` 中声明作用域（每行一个包名）：

```
com.android.systemui
com.example.targetapp
```

> **关于 system 作用域：** 对于声明了 `android:process="system"` 且 `android:sharedUserId="android.uid.system"` 的包，需要使用虚拟包名 `system` 作为作用域来 Hook 系统服务进程。`android` 包名仍然有效，因为其部分组件运行在 `:ui` 进程中。[^2]

---

## 5. 核心 API 详解 (API 102)

### 5.1 XposedModule — 模块基类

所有模块入口类必须继承 `XposedModule`。[^2] 框架会自动调用内部的 `attachFramework()` 桥接方法，**模块不应在 `onModuleLoaded()` 被调用之前执行初始化工作**。

```java
package com.example.mymodule;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class MyXposedModule extends XposedModule {

    public MyXposedModule() {
        // 构造函数保持为空或做最小初始化
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        // 模块被加载到目标进程时调用，在这里进行 Hook 初始化
        log(Log.INFO, "MyModule", "模块已加载到进程");
    }
}
```

### 5.2 XposedInterface — 核心接口

通过继承 `XposedModule`，你可以直接使用以下核心方法：

| 方法 | 说明 |
|------|------|
| `hook(Executable)` | Hook 一个方法或构造函数，返回 `HookBuilder` |
| `hookClassInitializer(Class)` | Hook 类的静态初始化器 \<clinit\> |
| `deoptimize(Executable)` | 反优化方法以防止内联导致 Hook 失效 |
| `getInvoker(Method)` | 获取方法调用器，绕过访问检查 |
| `getInvoker(Constructor)` | 获取构造函数调用器 |
| `detach()` | **API 102** 停止接收后续生命周期回调 |
| `log(priority, tag, msg)` | 写入 Xposed 日志 |
| `getApiVersion()` | 获取运行时 Xposed API 版本 |
| `getFrameworkName()` | 获取框架名称 |
| `getFrameworkProperties()` | 获取框架属性（如是否支持 remote/system） |
| `getModuleApplicationInfo()` | 获取模块的 ApplicationInfo |
| `getRemotePreferences(group)` | 获取远程 SharedPreferences（只读） |
| `openRemoteFile(name)` | 打开模块共享数据目录中的文件 |
| `listRemoteFiles()` | 列出模块共享数据目录中的所有文件 |

### 5.3 API 102 新特性

- **热重载 (Hot Reload)**：模块更新后无需重启进程即可生效
- **detach() 方法**：模块入口可以主动停止接收后续生命周期回调
- **原子替换 Hook**：通过 API 或相同 ID 可以原子性地替换 Hook
- **隔离 Legacy API**：targetApiVersion 为 102 的模块不能调用 `de.robv.android.xposed` 遗留 API

---

## 6. Hook 模型与拦截器链

Modern Xposed API 采用 **OkHttp 风格的拦截器链模型**。[^1] 模块实现 `Hooker` 接口的 `intercept(Chain)` 方法，Hook 通过 Builder 模式配置优先级和异常模式。

### 6.1 基本 Hook 示例

```java
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

public class MyXposedModule extends XposedModule {

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // 仅在目标包加载时进行 Hook
        if (!param.getPackageName().equals("com.example.target")) return;

        try {
            // 获取目标类和方法
            Class<?> targetClass = param.getClassLoader()
                .loadClass("com.example.target.MainActivity");
            Method targetMethod = targetClass.getDeclaredMethod("onCreate",
                android.os.Bundle.class);

            // 使用 Builder 模式配置 Hook
            hook(targetMethod)
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // 前置处理：修改参数
                    log(Log.INFO, "Hook", "onCreate 被调用");
                    // 调用原始方法（或链中的下一个 Hook）
                    Object result = chain.proceed();
                    // 后置处理：修改返回值
                    log(Log.INFO, "Hook", "onCreate 执行完毕");
                    return result;
                });

        } catch (Exception e) {
            log(Log.ERROR, "Hook", "Hook 失败", e);
        }
    }
}
```

### 6.2 HookBuilder 配置选项

| 方法 | 说明 |
|------|------|
| `setPriority(int)` | 设置 Hook 优先级：`PRIORITY_HIGHEST`、`PRIORITY_DEFAULT`、`PRIORITY_LOWEST` 或自定义数值（越大越优先） |
| `setExceptionMode(ExceptionMode)` | `PROTECTIVE`（默认，捕获异常）或 `PASSTHROUGH`（透传异常） |
| `intercept(Hooker)` | 设置拦截器实现，返回 `HookHandle` |

### 6.3 Chain 接口

| 方法 | 说明 |
|------|------|
| `proceed()` | 调用链中的下一个拦截器或原始方法 |
| `getExecutable()` | 获取被 Hook 的 Method 或 Constructor 对象 |
| `getThisObject()` | 获取方法调用的 this 对象（静态方法为 null） |
| `getArgs()` | 获取方法参数列表（不可修改） |

### 6.4 Hook 静态初始化器

```java
// Hook 类的 <clinit> 方法
hookClassInitializer(SomeClass.class)
    .intercept(chain -> {
        log(Log.INFO, "Hook", "类初始化前");
        Object result = chain.proceed(); // 始终返回 null
        log(Log.INFO, "Hook", "类初始化后");
        return result;
    });
```

### 6.5 反优化 (Deoptimize)

当被 Hook 的短方法被调用方内联时，Hook 回调可能不会被触发。使用 `deoptimize()` 可以强制调用方回退到非内联调用：[^2]

```java
// 假设方法 A 内联了我们的 Hook 目标方法 B
Method callerA = CallerClass.class.getDeclaredMethod("methodA");
deoptimize(callerA); // 反优化 A，使其调用 Hook 过的 B
```

> **注意：** 需要找到所有调用方并逐一反优化。可以使用 [DexKit](https://github.com/LuckyPray/DexKit) 来搜索所有调用方。另一种方式是直接卸载后重装应用（不卸载直接重装），这也会触发全局反优化。

### 6.6 Invoker — 调用器系统

通过 `getInvoker()` 获取调用器，可以绕过访问检查调用原始方法或构造函数：

```java
// 获取方法调用器并调用原始方法
Method privateMethod = SomeClass.class.getDeclaredMethod("privateMethod");
Invoker<?, Method> invoker = getInvoker(privateMethod);
Object result = invoker.invokeSpecial(instance, arg1, arg2);

// 获取构造函数调用器
Constructor<MyClass> ctor = MyClass.class.getDeclaredConstructor(String.class);
CtorInvoker<MyClass> ctorInvoker = getInvoker(ctor);
MyClass obj = ctorInvoker.newInstanceSpecial("param");
```

---

## 7. 模块生命周期

Modern Xposed API 提供了清晰的生命周期回调，模块通过重写 `XposedModule` 的方法来响应各阶段事件：[^2]

| 回调方法 | 调用时机 | 说明 |
|----------|----------|------|
| `onModuleLoaded()` | 模块被加载到目标进程时 | 最早的回调，在此进行模块级别初始化 |
| `onPackageLoaded()` | 默认 ClassLoader 就绪后，AppComponentFactory 实例化前 | 需要 API 29+；适合在此注册 Hook |
| `onPackageReady()` | 应用 ClassLoader 创建完成后 | 此时应用的所有类可被加载 |
| `onSystemServerStarting()` | 系统服务进程启动时 | 仅在作用域包含 `system` 时触发 |
| `onHotReloading()` | **API 102** 热重载前，旧代码中 | 用于清理旧 Hook 状态 |
| `onHotReloaded()` | **API 102** 热重载后，新代码中 | 用于重新注册 Hook |

```java
public class MyXposedModule extends XposedModule {

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, "Lifecycle", "模块已加载");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkgName = param.getPackageName();
        log(Log.INFO, "Lifecycle", "包已加载: " + pkgName);
        // 在此注册 Hook 是最佳实践
        if (pkgName.equals("com.example.target")) {
            setupHooks(param.getClassLoader());
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        log(Log.INFO, "Lifecycle", "系统服务启动中");
        // Hook 系统服务
    }
}
```

> **热重载限制：** 只有声明了恰好一个 Java 入口类的模块才支持热重载。零个或多个入口类的模块不支持热重载。[^2]

---

## 8. 作用域 (Scope) 管理

LSPosed 要求模块明确声明其作用域（需要 Hook 哪些应用）。[^3]

### 8.1 声明作用域

通过 `META-INF/xposed/scope.list` 文件声明：

```
com.android.systemui
com.tencent.mm
com.example.target
```

### 8.2 传统 API 方式（兼容旧版）

如果使用传统 Xposed API，可以在 `AndroidManifest.xml` 中声明：

```xml
<meta-data
    android:name="xposedscope"
    android:resource="@array/example_scope" />

<!-- res/values/array.xml -->
<string-array name="example_scope">
    <item>com.example.a</item>
    <item>com.example.b</item>
</string-array>
```

### 8.3 动态请求作用域

通过 `libxposed/service` 库，模块可以在运行时动态请求扩展作用域：

```kotlin
// 在模块 App 端（非 Hook 进程）
dependencies {
    implementation("io.github.libxposed:service:102.0.0")
}
```

> **重要：** 模块 App 自身不会被 Hook。这是 Modern Xposed API 的设计原则 — 模块进程和 Hook 目标进程是分离的。

---

## 9. Native Hook

LSPosed 支持在 Native 层进行函数 Hook，当目标应用加载新的 Native 库时，框架会调用模块的 Native 回调。[^4]

### 9.1 头文件定义

```c
// native_hook.h
typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);
```

### 9.2 Native 入口函数

```cpp
// example.cc
static HookFunType hook_func = nullptr;

// 原始函数备份
int (*original_target)();

// 伪造函数
int fake_target() {
    return original_target() + 1;
}

// 库加载回调
void on_library_loaded(const char *name, void *handle) {
    // 只 Hook 特定库
    if (std::string(name).find("libtarget.so") != std::string::npos) {
        void *target = dlsym(handle, "target_function");
        hook_func(target, (void *)fake_target, (void **)&original_target);
    }
}

// 必须导出此函数
extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    hook_func = entries->hook_func;
    // 可以在此 Hook 系统库函数
    return on_library_loaded;
}
```

### 9.3 声明 Native 入口

在 `src/main/resources/META-INF/xposed/native_init.list` 中声明：

```
libexample.so
```

在 Java 代码中手动加载 Native 库：

```java
if (lpparam.packageName.equals("org.lsposed.target")) {
    try {
        System.loadLibrary("example");
    } catch (Throwable e) {
        log(Log.ERROR, "Native", "加载失败", e);
    }
}
```

### 9.4 JNIEnv Hook

可以通过 `JNI_OnLoad` 获取 `JNIEnv` 来 Hook JNI 函数：

```cpp
extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint JNI_OnLoad(JavaVM *jvm, void*) {
    JNIEnv *env = nullptr;
    jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    // Hook FindClass 等 JNI 函数
    hook_func((void *)env->functions->FindClass,
              (void *)fake_FindClass,
              (void **)&original_FindClass);
    return JNI_VERSION_1_6;
}
```

---

## 10. 数据共享与通信

### 10.1 内容共享方案对比

| 方案 | API 类型 | 存储位置 | 变更监听 | 大文件 |
|------|----------|----------|----------|--------|
| New XSharedPreferences | Legacy (扩展) | /data/misc/\<random\>/prefs/\<module\> | ❌ | ❌ |
| XSharedPreferences | Legacy | 模块 App 内部存储 | ❌ | ❌ |
| Remote Preferences | Modern | LSPosed 数据库 | ✅ | ❌ |
| Remote Files | Modern | /data/adb/lspd/modules/\<user\>/\<module\> | ❌ | ✅ |

### 10.2 Remote Preferences（现代 API）

```java
// 在 Hook 进程中读取（只读）
SharedPreferences prefs = getRemotePreferences("my_settings");
String value = prefs.getString("key", "default");

// 在模块 App 中写入（通过 libxposed/service）
// 见 libxposed/service 文档
```

### 10.3 Remote Files

```java
// 在 Hook 进程中读取
String[] files = listRemoteFiles();
ParcelFileDescriptor pfd = openRemoteFile("config.json");
// 读取文件内容...
```

### 10.4 传统 XSharedPreferences（兼容方案）

```java
// 在模块 App 中存储
SharedPreferences pref = context.getSharedPreferences(
    "my_pref", Context.MODE_WORLD_READABLE);

// 在 Hook 进程中读取
XSharedPreferences xsp = new XSharedPreferences(
    BuildConfig.APPLICATION_ID, "my_pref");
if (xsp.getFile().canRead()) {
    String value = xsp.getString("key", "default");
}
```

---

## 11. 传统 API vs 现代 API 对比

以下对比帮助你将传统的 XposedBridge API 迁移到 Modern Xposed API：[^1]

| 特性 | 传统 API (Legacy) | 现代 API (Modern) |
|------|-------------------|-------------------|
| 入口文件 | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| Native 入口 | `assets/native_init` | `META-INF/xposed/native_init.list` |
| 模块元数据 | `AndroidManifest.xml` meta-data | `android:label` + `module.prop` |
| 作用域 | `xposedscope` meta-data | `META-INF/xposed/scope.list` |
| 入口接口 | `IXposedHookLoadPackage` 等 | 继承 `XposedModule` |
| Hook 模型 | `beforeHookedMethod` / `afterHookedMethod` | 拦截器链：`Hooker.intercept(Chain)` |
| Helper 类 | `XposedHelpers` / `XposedBridge` | 移至 `libxposed/helper` 独立库 |
| 资源 Hook | ✅ 支持 | ❌ 已移除（维护成本高） |
| 框架通信 | ❌ 不支持 | ✅ 通过 `libxposed/service` |
| 热重载 | ❌ 不支持 | **API 102** ✅ 支持 |
| 模块自身 Hook | ✅ 模块会被自身 Hook | ❌ 模块 App 不再被自身 Hook |

### 11.1 迁移示例：从 Legacy 到 Modern

```java
// ===== 传统 API 写法 =====
public class LegacyModule implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.example.target")) return;

        findAndHookMethod("com.example.target.MainActivity",
            lpparam.classLoader, "onCreate", Bundle.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log("onCreate 被调用");
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    XposedBridge.log("onCreate 执行完毕");
                }
            });
    }
}

// ===== 现代 API 写法 =====
public class ModernModule extends XposedModule {
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.getPackageName().equals("com.example.target")) return;

        try {
            Class<?> cls = param.getClassLoader()
                .loadClass("com.example.target.MainActivity");
            Method method = cls.getDeclaredMethod("onCreate", Bundle.class);

            hook(method)
                .setPriority(PRIORITY_DEFAULT)
                .intercept(chain -> {
                    log(Log.INFO, "Hook", "onCreate 被调用");
                    Object result = chain.proceed();
                    log(Log.INFO, "Hook", "onCreate 执行完毕");
                    return result;
                });
        } catch (Exception e) {
            log(Log.ERROR, "Hook", "Hook 失败", e);
        }
    }
}
```

---

## 12. 完整示例

以下是一个完整的 LSPosed 模块示例，展示如何 Hook 目标应用的 Activity 生命周期：

### 12.1 项目结构

```
app/
├── build.gradle.kts
├── src/
│   └── main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/demo/
│       │   └── DemoModule.java
│       └── resources/
│           └── META-INF/
│               └── xposed/
│                   ├── module.prop
│                   ├── java_init.list
│                   └── scope.list
```

### 12.2 build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.demo"
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
```

### 12.3 module.prop

```properties
minApiVersion=101
targetApiVersion=102
staticScope=false
```

### 12.4 java_init.list

```
com.example.demo.DemoModule
```

### 12.5 scope.list

```
com.example.targetapp
```

### 12.6 DemoModule.java

```java
package com.example.demo;

import android.util.Log;
import android.os.Bundle;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;

public class DemoModule extends XposedModule {

    private static final String TAG = "DemoModule";
    private static final String TARGET_PKG = "com.example.targetapp";

    public DemoModule() {
        // 保持构造函数为空
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "模块已加载");
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // 只处理目标应用
        if (!param.getPackageName().equals(TARGET_PKG)) return;

        log(Log.INFO, TAG, "目标包已加载: " + param.getPackageName());

        try {
            // Hook Activity.onCreate()
            Class<?> activityClass = param.getClassLoader()
                .loadClass("android.app.Activity");
            Method onCreateMethod = activityClass.getDeclaredMethod(
                "onCreate", Bundle.class);

            hook(onCreateMethod)
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // 获取 this 对象（即 Activity 实例）
                    Object activity = chain.getThisObject();
                    log(Log.INFO, TAG, "Activity onCreate: " +
                        activity.getClass().getName());

                    // 前置处理
                    // ... 可以在此修改参数等

                    // 调用原始方法
                    Object result = chain.proceed();

                    // 后置处理
                    // ... 可以在此修改返回值等

                    return result;
                });

            log(Log.INFO, TAG, "Hook 注册成功");

        } catch (Exception e) {
            log(Log.ERROR, TAG, "Hook 注册失败", e);
        }
    }
}
```

### 12.7 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="Demo Xposed Module"
        android:description="一个演示用的 LSPosed 模块"
        android:allowBackup="true"
        android:supportsRtl="true">
    </application>

</manifest>
```

---

## 13. 常见问题与调试

### 13.1 Hook 不生效排查

1. **检查模块是否启用**：在 LSPosed 管理器中确认模块已勾选并作用于目标应用
2. **检查作用域**：确认目标包名在 `scope.list` 中
3. **检查 ClassLoader**：确保使用正确的 ClassLoader（`param.getClassLoader()` 而非 `Class.forName()`）
4. **检查方法签名**：确认方法名和参数类型完全匹配
5. **检查内联**：短方法可能被内联，使用 `deoptimize()` 解决
6. **检查日志**：使用 `adb logcat -s LSPosed-Bridge` 查看框架日志

### 13.2 调试技巧

```bash
# 过滤 LSPosed 相关日志
adb logcat -s LSPosed-Bridge:L MyModule:L *:S

# 查看模块是否被加载
adb logcat | grep "Loading modules"

# 查看 Hook 是否注册成功
adb logcat | grep "Hook 注册"
```

### 13.3 常见错误

| 错误 | 原因 | 解决方案 |
|------|------|----------|
| `ClassNotFoundException` | 使用了错误的 ClassLoader | 使用 `param.getClassLoader().loadClass()` |
| `NoSuchMethodException` | 方法签名不匹配 | 确认方法名和参数类型与目标完全一致 |
| Hook 回调未触发 | 方法被内联 | 使用 `deoptimize()` 反优化调用方 |
| 模块不显示在管理器中 | 入口文件路径错误 | 确认 `java_init.list` 在 `META-INF/xposed/` 下 |

### 13.4 性能优化建议

- **延迟注册**：避免在 `handleLoadPackage` 中注册不必要的 Hook
- **结果缓存**：对频繁调用的 Hook 结果进行缓存
- **日志分级**：仅在 Debug 模式下输出详细日志
- **进程过滤**：使用 `param.getProcessName()` 仅在主进程进行 Hook

---

## 参考来源

[^1]: [LSPosed Official Wiki — Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
[^2]: [libxposed/api — Modern Xposed Module API (Javadoc), API 102](https://libxposed.github.io/api/)
[^3]: [LSPosed Official Wiki — Module Scope](https://github.com/LSPosed/LSPosed/wiki/Module-Scope)
[^4]: [LSPosed Official Wiki — Native Hook](https://github.com/LSPosed/LSPosed/wiki/Native-Hook)
[^5]: [LSPosed Official Wiki — New XSharedPreferences](https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences)
[^6]: [rovo89 — XposedBridge Development Tutorial (Original Xposed framework)](https://github.com/rovo89/XposedBridge/wiki/Development-tutorial)
[^7]: [libxposed/example — Official example module using modern Xposed API](https://github.com/libxposed/example)
[^8]: [libxposed/service — Framework communication service for modern Xposed API](https://github.com/libxposed/service)
[^9]: [libxposed/helper — Friendly development kit library for modern Xposed API](https://github.com/libxposed/helper)
[^10]: [从 Xposed 到 LSPosed：API 无缝迁移实战指南 — CSDN](https://blog.csdn.net/gitblog_00171/article/details/152709244)