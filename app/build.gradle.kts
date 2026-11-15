import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

// 可选：根目录 keystore.properties 存在时使用正式签名；否则回退 debug 签名（保持现有行为）。
// 文件格式：storeFile=/绝对路径/release.jks, storePassword=..., keyAlias=..., keyPassword=...
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }

android {
    namespace = "com.dhhxfggg.pjm"
    compileSdk = 37

    // 显式指定你安装的 NDK 版本，防止 Gradle 寻找旧版本
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.dhhxfggg.pjm"
        minSdk = 24
        targetSdk = 35
        versionCode = 52
        versionName = "2.1.5"

        ndk {
            // 只留真机架构：x86/x86_64 只有模拟器用得上，留在包里等于让每个用户
            // 多下载两个用不到的原生库
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    // 重新启用 C++ 构建链接
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        getByName("debug") {
            // 开启所有签名版本，确保在 Android 15 上能直接安装
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig =
                if (keystorePropertiesFile.exists()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    lint {
        // 门禁：lint 报 error 就中断构建。之前这里两项都是 false，
        // 于是「导出的组件没有权限保护」这类 error 一路跟着包发出去，只能靠人肉审。
        abortOnError = true
        // release 构建前也跑一次；不仅检查 fatal 项
        checkReleaseBuilds = true
        // warning 只打印，不拦构建（否则历史遗留的一条 warning 就能卡住发布）
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Room 迁移测试需要把导出的 schema JSON 作为 androidTest assets（MigrationTestHelper 读取）
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Room：导出数据库 schema JSON 到 app/schemas（纳入版本控制，用于编写/测试显式迁移）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose (BOM 统一版本)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)

    // AndroidX 核心
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 压缩 / 归档
    implementation(libs.apache.commons.compress)
    implementation(libs.apache.commons.lang3)
    implementation(libs.tukaani.xz)
    implementation(libs.junrar)
    implementation(libs.slf4j.android)

    // 导航 / 持久化 / DI
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    // 图标
    implementation(libs.icons.lucide)

    // 图片/视频加载与播放 (Coil 3)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.network.okhttp)

    // 图片缩放/手势（Telephoto：双指缩放、双击、惯性、与 pager 的嵌套滚动）
    implementation(libs.telephoto.zoomable)
    // 官方 zoomable 图片组件（自带 Fit 内容边界计算 + 大图分块采样，避免手搓 contentLocation 出错）
    implementation(libs.telephoto.zoomable.image.coil3)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    // 日志
    implementation(libs.timber)

    // Shizuku：以 shell/root 身份访问 Android/data（突破 Android 14 SAF 限制）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)

    // 仪器化测试（ExampleInstrumentedTest 需要 AndroidJUnit4 + InstrumentationRegistry）
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

// ktlint 静态检查（与 CI 一致）
ktlint {
    // 版本默认取插件内置；Android 项目无需额外规则集
}
