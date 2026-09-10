# PJM — Private Vault Manager

PJM 是一个面向 Android 的本地加密资产管理应用：将图片、视频、音频、压缩包等文件以专有的 `.pjm` 加密容器形式收纳入本地保险库，支持分类浏览、批量管理、内容去重，并可通过 Shizuku 或特权服务访问受限目录。

> **AI 生成声明**：本项目的全部代码、构建脚本与文档均由人工智能生成，未经人工逐行审查。使用前请自行评估风险。

---

## 功能

### 加密保险库
- **`.pjm` 容器格式**：文件打包为 ZIP 后经 XOR 流变换加密，支持大于 2GB 的文件（64 位偏移）。
- **分卷存储**：`.pjm.N` 多卷格式，每个分卷是独立完整的容器，单个分卷即可独立解密。
- **原生加速**：核心字节变换由 C++ (JNI) 实现；原生库不可用时自动回退到 Kotlin 实现。
- **数据库迁移安全**：Room schema 变更必须显式注册 Migration，版本不一致时直接失败，不会静默清库。

### 资产管理
- **分类存储**：图片 / 视频 / 音频 / B站视频 / `.pjm` 容器 / 其他。
- **大数据量优化**：复合索引、游标分页、索引化随机取样。
- **缩略图缓存**：图片采样缩略与视频关键帧抽取后落盘为持久缓存。
- **内容去重**：基于 MD5 的精确查重，以及基于感知哈希（dHash + 灰度比对）的相似图片查重。
- **系统集成**：接收 SEND / VIEW 意图直接入库；支持导出到系统相册。

### 发现页
- 图片全屏缩放（双击/双指）、视频播放（拖动快进快退）。
- ExoPlayer 实例池化复用，避免滚动时反复创建/销毁解码器。
- 全量打乱队列，一轮内不重复浏览。

### 受限目录访问
- **Shizuku 支持**：以 shell 身份访问 `Android/data` 等受保护目录。
- **内置特权文件服务**：AIDL + 嵌入式进程，配合 `start.sh` 在不安装 Shizuku 的情况下通过 adb 启动。

### 其他
- 前台服务执行后台任务。
- 定时备份数据库（先执行 WAL checkpoint 以保证备份完整）。
- 启动时后台补齐缺失缩略图并清理孤儿缓存。

---

## 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Kotlin + C++ (JNI) |
| UI | Jetpack Compose (Material 3)、Navigation Compose、Coil 3、Media3 ExoPlayer |
| 架构 | MVVM + Repository + Hilt + Room |
| 数据 | Room（WAL、显式 Migration）、DataStore Preferences |
| 权限 | Shizuku、Scoped Storage 兼容 |
| 压缩 | Apache Commons Compress、junrar、XZ |
| 构建 | AGP 9.x、KSP、NDK + CMake |
| 质量 | ktlint、JUnit、GitHub Actions |

---

## 构建

环境要求：JDK 17、Android SDK（compileSdk 37）、NDK 28.x、CMake 3.22+。

```bash
./gradlew :app:assembleDebug
```

常用任务：

```bash
./gradlew :app:testDebugUnitTest   # 单元测试
./gradlew ktlintCheck              # 代码风格检查
./gradlew :app:assembleRelease     # 构建发布包
```

### 运行要求
- minSdk 24 / targetSdk 35。
- 需要存储权限；在 Android 11+ 上需授予「所有文件访问」，或使用 Shizuku 以获得完整功能。

---

## 项目结构

```
app/src/main/java/com/dhhxfggg/pjm/
├── data/            # 数据层：Room 数据库、实体、Repository
│   ├── db/          #   AppDatabase（显式迁移）+ FileDao
│   └── model/       #   FileEntity 等实体
├── domain/          # 业务逻辑
│   ├── util/        #   加密、容器、扫描、缩略图、命名等工具
│   ├── service/     #   前台服务
│   └── shizuku/     #   Shizuku 桥接与特权文件服务
├── di/              # Hilt 模块
└── ui/              # Compose UI（screen / viewmodel / component / navigation / theme）
```

Room schema 导出至 `app/schemas/`，纳入版本控制，用于编写与测试显式迁移。

---

## 安全说明

- 加密基于 XOR 流变换与自定义容器格式，适用于本地私密存储，**不适合作为高价值机密数据的安全方案**。
- 仓库不包含任何 API 密钥或签名文件（`local.properties`、`*.jks` 等已由 `.gitignore` 排除）。

---

## 许可

版权所有 © dhhxfggg2023，保留所有权利。未经授权请勿复制、分发或用于商业用途。
