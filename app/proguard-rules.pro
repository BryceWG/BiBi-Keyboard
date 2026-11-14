# ==========================================
# 开源版 ProGuard 规则（main 源集）
# ==========================================

# 保留必要的兼容约束；release 由 R8 负责缩减、优化和混淆
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*
-optimizationpasses 3

# 精简日志
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# 输出报告
-verbose
-printconfiguration build/outputs/proguard/configuration.txt
-printusage build/outputs/proguard/unused.txt

# 保留注解与源码信息
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Parcelable
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ==========================================
# kotlinx.serialization
# ==========================================

-keepattributes InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.brycewg.asrkb.**$$serializer { *; }
-keepclassmembers class com.brycewg.asrkb.** {
    *** Companion;
}
-keepclasseswithmembers class com.brycewg.asrkb.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 序列化数据类（main 源集）
-keep class com.brycewg.asrkb.store.PromptPreset { *; }
-keep class com.brycewg.asrkb.store.SpeechPreset { *; }
-keep class com.brycewg.asrkb.store.AsrHistoryStore$* { *; }

# ==========================================
# 依赖库
# ==========================================

# Sherpa-ONNX JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# DashScope SDK
-keep class com.alibaba.dashscope.** { *; }
-dontwarn com.alibaba.dashscope.**

# Lombok
-dontwarn lombok.**
-dontwarn org.projectlombok.**

# OkHttp & WebSocket
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin 协程/元数据
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.Metadata *;
}

# ==========================================
# 项目核心入口（main 源集）
# ==========================================

-keep class com.brycewg.asrkb.ime.AsrKeyboardService { *; }
-keep class com.brycewg.asrkb.ui.floating.FloatingAsrService { *; }

# BuildConfig
-keep class com.brycewg.asrkb.BuildConfig { *; }

# RootService 由独立 root 进程按类名构造。
-keep class com.brycewg.asrkb.ui.floating.RootKeepAliveService { public <init>(); }
