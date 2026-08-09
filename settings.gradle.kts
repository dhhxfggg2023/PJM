pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 【添加】JitPack 仓库，用于引入针对 Android 优化的 SevenZipJBinding
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PJM"
include(":app")
