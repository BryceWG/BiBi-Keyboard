plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}

val packagedAbiFilters = (findProperty("abiFilters") as String?)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

configurations.matching { it.name == "composeMappingProducerClasspath" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "compose-group-mapping") {
            useVersion("2.3.21")
            because("AGP 9.2.1 requests 2.2.10, but compose-group-mapping is published from Kotlin 2.3.0.")
        }
    }
}

android {
    namespace = "com.brycewg.asrkb"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.brycewg.asrkb"
        minSdk = 26
        targetSdk = 36
        versionCode = 173
        versionName = "4.4.5"

        // 默认仅构建 arm64-v8a 以减小包体体积；CI 可通过 -PabiFilters=armeabi-v7a 单独出包。
        ndk {
            abiFilters += packagedAbiFilters
        }

        // 从环境变量注入免费服务内置 API Key（GitHub Secrets）
        buildConfigField(
            "String",
            "SF_FREE_API_KEY",
            "\"${System.getenv("SF_FREE_API_KEY") ?: ""}\""
        )

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
        aidl = true
        viewBinding = true
        buildConfig = true
        compose = true
        resValues = true
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.alibaba:dashscope-sdk-java:2.22.27")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")

    // Shizuku：用于在已授权时执行部分 shell 级操作（增强后台保活）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // AAR 占位：sherpa-onnx Kotlin API AAR 放在 app/libs/ 会自动识别
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
