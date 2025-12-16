plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

android {
    namespace = "com.brycewg.asrkb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brycewg.asrkb"
        minSdk = 29
        targetSdk = 35
        versionCode = 134
        versionName = "3.9.3"

        // 仅构建 arm64-v8a 以减小包体体积
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // 从环境变量注入免费服务内置 API Key（GitHub Secrets）
        buildConfigField("String", "SF_FREE_API_KEY", "\"${System.getenv("SF_FREE_API_KEY") ?: ""}\"")

        // 从环境变量注入 PocketBase 地址
        val pbBaseUrl = System.getenv("POCKETBASE_BASE_URL") ?: ""
        resValue("string", "pocketbase_base_url", pbBaseUrl)
    }

    signingConfigs {
        create("release") {
            // 从环境变量读取签名配置
            storeFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // 统一开启代码压缩和资源收缩
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.findByName("release")?.takeIf {
                it.storeFile?.exists() == true
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 确保识别 AIDL 源目录
    sourceSets {
        getByName("main") {
            aidl.srcDirs("src/main/aidl")
        }
    }

    // 安装包不分语言 split，便于手动切换
    bundle {
        language {
            enableSplit = false
        }
    }
    packaging {
        jniLibs {
            excludes += listOf(
                "**/libonnxruntime4j_jni.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so"
            )
        }
        resources {
            excludes += listOf(
                "META-INF/services/lombok.*",
                "README.md",
                "META-INF/README.md"
            )
        }
    }
}

// Kotlin 编译配置，使用 JDK 21 工具链，目标 JVM 17
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Java 任务使用 JDK 21 编译，但源码/目标版本维持 17
val toolchainService = extensions.getByType(JavaToolchainService::class.java)
tasks.withType(JavaCompile::class.java).configureEach {
    javaCompiler.set(
        toolchainService.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.alibaba:dashscope-sdk-java:2.22.3")
    implementation("com.github.thegrizzlylabs:sardine-android:0.9") {
        // 避免 xpp3 中的 org.xmlpull.v1.XmlPullParser 与 Android SDK 冲突
        exclude(module = "xpp3")
    }

    // AAR 占位：sherpa-onnx Kotlin API AAR 放在 app/libs/ 会自动识别
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
