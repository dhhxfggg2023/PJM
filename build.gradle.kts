// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

buildscript {
    dependencies {
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.60.1")
    }
}

// 确保包含 Google 和 Maven Central 仓库
allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}