# PJM — Private Vault Manager

> **加密资产保险库 · Android 工业级本地文件管理**

> [!IMPORTANT]
> ## 🤖 本项目为 100% 纯 AI 生成
>
> 本项目的**全部代码、配置、文档均由人工智能（AI）自动生成**，
> 未经人类开发者逐行手写或人工审查。包括但不限于：
>
> - 全部 Kotlin / C++ / AIDL 源码
> - Gradle 构建脚本与依赖配置
> - Room 数据库 schema 与迁移
> - UI 布局、主题、资源文件
> - 本 README 文档
>
> **使用风险自负**：AI 生成的代码可能存在未知缺陷、性能问题、
> 安全隐患或不符合最佳实践之处，请在充分测试后再考虑实际使用。
>
> —— 本项目是 AI 编程能力的实验性产物 ——

PJM 是一个面向 Android 的**本地加密资产管理器**：将图片、视频、音频、压缩包等文件以专有 `.pjm` 加密容器形式收纳入私有保险库，支持分类浏览、发现页沉浸式播放、批量管理、去重、以及通过 Shizuku 突破 Android/data 访问限制。

> ⚠️ 本仓库为**私有仓库**，包含加密实现与特权访问逻辑，请勿公开分发。

---

## ✨ 功能特性

### 🔐 加密保险库
- **`.pjm` 专有容器格式**：文件打包为 ZIP 后经 XOR 流变换加密，支持大文件（>2GB，64 位偏移）
- **分卷加密**：`.pjm.N` 多卷格式，每卷独立完整容器，缺卷不影响其他卷解密
- **原生加速**：C++ JNI 字节流变换，显著提升吞吐、降低功耗（无原生库时自动回退 Kotlin 实现）
- **严格数据库迁移**：Room schema 变更必须显式注册 Migration，杜绝静默清库（fail-fast 保护用户数据）

### 📂 资产管理
- **分类存储**：图片 / 视频 / 音频 / B站视频 / PJM 容器 / 其他
- **万级文件优化**：复合索引、游标分页、快速封面查询（O(log n)）
- **半永久缩略图**：图片采样缩略 + 视频关键帧抽取落盘，滚动流畅且省内存
- **内容指纹去重**：延迟计算 MD5，手动触发清除重复内容
- **系统分享/打开**：接收 SEND / VIEW 意图直接入库；导出到相册（Pictures/Movies/PJM）

### 🎬 发现页
- **沉浸式浏览**：图片全屏缩放（双击/双指）、视频播放（快进快退手势）
- **ExoPlayer 复用池**：避免滑动时反复创建/销毁 MediaCodec，省电流畅
- **全量打乱队列**：一轮内不重复、不遗漏（替代旧版"随机+排除"的 800 张限制）

### 🛡️ 特权访问（Shizuku）
- **突破 Android 14 SAF 限制**：通过 Shizuku / shell 身份直接访问 `Android/data`
- **内置特权文件桥接服务**：AIDL + 嵌入式 shell 服务（`start.sh`），shell 可读 app 私有目录
- **Bilibili 缓存收割**：扫描 B站 App 缓存目录，分离视频/音频 m4s 流并合并入库

### ⚙️ 其他
- 前台服务（`dataSync`）执行后台任务；通知节流
- 定时自动备份数据库（WAL checkpoint 后备份，保证完整性）
- 军规级删除（多次覆写 + 名称随机化）；`shredFile` 安全清理
- 每日自动清理孤儿缩略图、自动补齐缺失缩略图（后台空闲对齐）

---

## 🏗️ 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Kotlin 2.x + 少量 C++ (JNI) |
| UI | Jetpack Compose (Material 3)、Navigation Compose、Coil 3、Media3 ExoPlayer |
| 架构 | MVVM + Repository + Hilt (DI) + Room |
| 数据 | Room (WAL, 显式 Migration)、DataStore Preferences |
| 权限 | Shizuku (API 13.x)、Scoped Storage 兼容 |
| 压缩 | Apache Commons Compress、junrar、XZ（7z 兼容层可选） |
| 构建 | AGP 9.x、KSP、NDK + CMake |

---

## 🚀 构建

```bash
# 需要：JDK 17、Android SDK (compileSdk 37)、NDK 28.x、CMake 3.22+
./gradlew :app:assembleDebug
```

### 运行要求
- **minSdk 24 / targetSdk 35**
- 需要存储权限；高版本 Android 需授权「所有文件访问」或 Shizuku 以使用完整功能

---

## 📁 项目结构

```
app/src/main/java/com/dhhxfggg/pjm/
├── data/            # Room 数据库、实体、Repository
│   ├── db/          #   AppDatabase (v9, 显式迁移) + FileDao
│   └── model/       #   FileEntity（复合索引）
├── domain/          # 核心业务逻辑
│   ├── util/        #   VaultManager(核心)、CryptoUtils、IngestionEngine、BiliBridge、ThumbnailCache...
│   ├── service/     #   VaultService（前台服务）
│   └── shizuku/     #   Shizuku 桥接 + 特权文件服务
├── di/              # Hilt 模块
└── ui/              # Compose UI (screen / viewmodel / component / navigation / theme)
```

> Room schema 导出到 `app/schemas/`（纳入版本控制，用于编写/测试显式迁移）。

---

## 🔒 安全说明

- 加密基于 XOR 流变换 + 自定义容器格式，**定位为"防君子不防小人"的本地私密存储**，请勿将其作为强加密方案用于高价值机密。
- 仓库中**不含**任何 API 密钥或签名文件（`local.properties`、`*.jks` 已 gitignore）。

---

## 📄 许可

私有仓库，版权所有 © dhhxfggg2023。未授权请勿复制、分发或用于商业用途。
