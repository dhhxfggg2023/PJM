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

// 签名策略（2026-11-18 调整）：debug 与 release 使用【两套独立证书】。
// - debug：Android 工具链自动生成的调试证书（~/.android/debug.keystore），只用于本机调试；
// - release：必须使用 keystore.properties 指向的正式证书。缺配置时构建【直接失败】，
//   不再回退 debug 签名 —— 旧行为会让"正式版"带着随构建环境变化的调试证书发出去，
//   换机重装、应用内升级都会因为签名不一致失败。
// keystore.properties 与 *.jks 都在 .gitignore 里，仓库只保留读取逻辑、不含任何密钥材料；
// 证书本体与密码请离线保管在仓库之外（U 盘/加密盘/密码管理器）。
// 文件格式：storeFile=<证书绝对路径或相对项目根目录的路径>, storePassword=..., keyAlias=..., keyPassword=...
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        // 必须按 UTF-8 读：Properties.load(InputStream) 用的是 ISO-8859-1，
        // 证书路径里若带非 ASCII 字符（例如中文目录名）会被解成乱码，变成"文件不存在"。
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.reader(Charsets.UTF_8).use { load(it) }
        }
    }

// storeFile 允许写绝对路径（本机证书放在备份盘上）或写相对项目根目录的路径。
// 文件不存在时视为未配置：宁可让 release 构建失败，也不要签出一个别人装不上的包。
val releaseKeystoreFile =
    keystoreProperties
        .getProperty("storeFile")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw -> rootProject.file(raw).takeIf { it.isFile } }

android {
    namespace = "com.dhhxfggg.pjm"
    compileSdk = 37

    // 显式指定你安装的 NDK 版本，防止 Gradle 寻找旧版本
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.dhhxfggg.pjm"
        minSdk = 24
        targetSdk = 35
        versionCode = 56
        versionName = "2.1.9"

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
            // 调试证书由 Android 工具链自动生成（~/.android/debug.keystore），与正式证书互相独立。
            // 开启所有签名版本，确保在 Android 15 上能直接安装
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
        // 正式证书只在 keystore.properties 与证书文件都存在时创建；缺失时 release 保持未签名，
        // 由 requireReleaseSigning 门禁在打包前报错（不回退 debug 证书）。
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
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
            // 只认正式证书；缺配置时留空，由 requireReleaseSigning 在打包前拦截
            // （见文件头签名策略）。
            signingConfig = signingConfigs.findByName("release")
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

// 门禁：release 构建必须有正式签名。缺 keystore.properties / 证书文件时在打包前失败，
// 而不是产出一个签名不对、别人装不上的"正式包"（debug 构建不受影响）。
gradle.taskGraph.whenReady {
    val packagingRelease =
        allTasks.any { task ->
            task.project.name == "app" &&
                task.name.contains("Release", ignoreCase = true) &&
                (task.name.startsWith("package") || task.name.startsWith("bundle") || task.name.startsWith("assemble"))
        }
    if (packagingRelease && releaseKeystoreFile == null) {
        throw GradleException(
            """
            release 构建需要正式签名，但没有找到可用的证书。
            请在项目根目录创建 keystore.properties（已被 .gitignore 排除）：
              storeFile=<证书绝对路径，如 /path/to/pjm-release.jks>
              storePassword=...
              keyAlias=pjm
              keyPassword=...
            debug 与 release 是两套独立证书，本工程不再回退 debug 签名。规则说明见 README「构建」一节。
            """.trimIndent(),
        )
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
    implementation(libs.androidx.compose.foundation)

    // AndroidX 核心
    implementation(libs.androidx.core.ktx)
    // Baseline Profile 安装器：把随包发布的 baseline profile 落到 ART，装完即 AOT。
    // Android 12+ 系统会在安装时自己装；12 以下靠这个库在后台装（minSdk 24 必须带上）。
    implementation(libs.androidx.profileinstaller)
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
