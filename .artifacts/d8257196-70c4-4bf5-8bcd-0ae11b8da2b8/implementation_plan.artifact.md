# 自动化修复项目模块未指定及 JDK 冲突方案

由于 Android Studio 2026 默认使用 Java 25，而当前项目 Gradle 版本 (8.13) 不支持该版本，导致同步失败。本方案将通过升级项目核心组件，使其适配当前的 IDE 环境。

## 待处理问题
- [ ] 模块未指定（由于同步失败）
- [ ] Gradle 8.13 不支持 Java 25 冲突
- [ ] 潜在的插件版本不兼容（AGP 8.4.0 与 Gradle 9.1.0）

## 提议的更改

### 1. [Component] Gradle Wrapper
#### [MODIFY] [gradle-wrapper.properties](file:///D:/Android/project/PJM/gradle/wrapper/gradle-wrapper.properties)
将 `distributionUrl` 更新为 Gradle 9.1.0，以支持 Java 25。

### 2. [Component] Build Configuration
#### [MODIFY] [build.gradle.kts (Root)](file:///D:/Android/project/PJM/build.gradle.kts)
- 升级 Android Gradle Plugin (AGP) 从 `8.4.0` 到 `8.7.0`（或更高，以适配 Gradle 9）。
- 升级 Kotlin 插件版本从 `1.9.23` 到 `2.0.20`（现代 Android 开发推荐版本）。
- 升级 KSP 插件版本以匹配 Kotlin 2.0.20。

#### [MODIFY] [app/build.gradle.kts](file:///D:/Android/project/PJM/app/build.gradle.kts)
- 更新 `composeOptions`（Kotlin 2.0+ 后不再需要手动指定 `kotlinCompilerExtensionVersion`）。

## 验证计划

### 自动化测试
- 执行 `gradle sync` 验证配置是否正确。
- 尝试运行 `assembleDebug` 编译任务。

### 手动验证
- 确认 Android Studio 的“运行”按钮不再是灰色，且能看到 `app` 模块。
