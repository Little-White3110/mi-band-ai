# ---- 环上LLM 混淆规则（LSPosed 模块）----

# Modern Xposed API 官方推荐的保留规则：
# 1. 模块入口类必须保留，且不能被混淆（框架通过 java_init.list 反射实例化）
# 2. java_init.list 在混淆后需要重写为混淆后的类名
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 保留 Miuix / Compose 相关类（Compose 运行时本身有自己的混淆规则，通常无需手动处理，
# 但避免过度混淆导致运行时反射失效，此处保留 Miuix 包）
-keep class top.yukonga.miuix.** { *; }
-keep class androidx.compose.** { *; }
-dontwarn top.yukonga.miuix.**

# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
