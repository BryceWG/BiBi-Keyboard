# ==========================================
# 通用规则（OSS 和 Pro 共享）
# 注意：仅包含 main 源集和公共库的规则
# ==========================================

# 保持注解
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保持行号（便于崩溃追踪）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==========================================
# Android 基础组件
# ==========================================

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

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
# kotlinx.serialization（必须保持）
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

# 显式保持序列化数据类（main 源集）
-keep class com.brycewg.asrkb.store.PromptPreset { *; }
-keep class com.brycewg.asrkb.store.SpeechPreset { *; }
-keep class com.brycewg.asrkb.store.AsrHistoryStore$* { *; }

# ==========================================
# 第三方库
# ==========================================

# Sherpa-ONNX（本地 ASR 模型，JNI）
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# DashScope SDK
-keep class com.alibaba.dashscope.** { *; }
-dontwarn com.alibaba.dashscope.**

# Lombok（仅编译期使用；运行时不需要）
-dontwarn lombok.**
-dontwarn org.projectlombok.**

# OkHttp & WebSocket
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin 协程
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin 反射
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.Metadata *;
}

# ==========================================
# 项目核心组件（仅 main 源集中的类）
# ==========================================

# InputMethodService
-keep class com.brycewg.asrkb.ime.AsrKeyboardService { *; }

# AccessibilityService
-keep class com.brycewg.asrkb.ui.FloatingAsrService { *; }
-keep class com.brycewg.asrkb.ui.floatingball.FloatingAsrService { *; }

# ASR 引擎接口（main 源集）
-keep interface com.brycewg.asrkb.asr.AsrEngine { *; }
-keep interface com.brycewg.asrkb.asr.StreamingAsrEngine { *; }
-keep interface com.brycewg.asrkb.asr.StreamingAsrEngine$Listener { *; }

# ASR 引擎实现类（main 源集）
-keep class * implements com.brycewg.asrkb.asr.AsrEngine {
    public <init>(...);
    public *;
}

# ⚠️ 重要：ProUiInjector 在 main 源集，保留
-keep class com.brycewg.asrkb.ProUiInjector {
    public *;
}

# ⚠️ 重要：ProAsrHelper 虽然在 oss/pro 双实现，但类名存在于两个源集
# 需要保留类名和方法签名，否则 main 调用会失败
-keep class com.brycewg.asrkb.asr.ProAsrHelper {
    public *;
}

# Pro 正则后处理门面（oss/pro 双实现），保持类与方法签名稳定
-keep class com.brycewg.asrkb.util.ProRegexFacade {
    public *;
}

# BuildConfig
-keep class com.brycewg.asrkb.BuildConfig { *; }

# Edition 门面类（main 源集）
-keep class com.brycewg.asrkb.Edition { *; }
