# PJM — Private Vault Manager

> ## ⚠️ 完全由 AI 生成
>
> **本项目的全部内容 —— 包括但不限于全部 Kotlin / C++ 源码、Gradle 构建脚本、资源文件、数据库 schema、测试用例、CI 配置、提交信息以及本 README —— 均由人工智能（AI）生成，未经人工逐行编写或审查。**
>
> 人类作者只提出需求与方向，代码由 AI 产出。因此：
> - 可能存在 AI 未能察觉的逻辑错误、安全缺陷或平台兼容性问题；
> - 代码风格与架构决策反映的是 AI 的取舍，不一定符合最佳实践；
> - **请勿在未自行审查的情况下将其用于任何对可靠性或安全性有要求的场景。**
>
> 使用本项目的风险由使用者自行承担。

---

## 这是什么

PJM 是一个 Android 本地文件保险库应用。它把图片、视频、音频、压缩包等文件收进应用的私有目录统一管理，支持分类浏览、批量操作、内容去重，并能以自定义的 `.pjm` 容器格式打包文件用于对外分享。

**它解决的问题**：把散落在各处（尤其是 `Android/data` 等受限目录）的媒体文件集中到一处管理，并在需要发给别人时换成一种不会被社交平台自动审核识别的字节序列。

**它不解决的问题**：它不是加密软件。详见下方 [安全模型](#安全模型必读)。

---

## 安全模型（必读）

这是使用本项目前**最需要理解**的一节。

### `.pjm` 容器是「混淆」，不是「加密」

`.pjm` 的字节变换采用**固定密钥的异或（XOR）**，密钥硬编码在应用源码里。它的设计目标是：

| 目标 | 是否达成 |
|---|---|
| 让文件的字节序列不再匹配平台的内容哈希库 | ✅ 达成 |
| 让平台的图像/视频分类器无法直接从字节中提取内容 | ✅ 达成 |
| 防止他人查看文件内容 | ❌ **完全无效** |

**固定密钥异或属于「重复密钥异或」（维吉尼亚密码一类），在密码学上早已被攻破**：

- 容器内部是标准 ZIP，含有大量**已知明文**（`PK\x03\x04` 头、结尾的中央目录、成片的 `0x00` 字段）。把已知明文与密文异或即可还原密钥流，进而解开整个文件。
- 因此**密钥再长也没用** —— 攻击者不会去暴力枚举，而会走已知明文这条路。
- 密钥就写在源码里，任何人拿到应用或源码都能直接解密。

**结论**：`.pjm` 挡得住机器，挡不住人。**不要用它保护任何真正机密的数据。** 需要真加密请用 7-Zip（AES-256）、Cryptomator、age 等成熟工具。

### 容器格式版本沿革（诚实记录）

| 版本 | 布局 | 密钥流取字节方式 | 说明 |
|---|---|---|---|
| 无版本字节 | `magic(4)` + ZIP | `pos & 33` | 历史格式。**有缺陷**：`33` 不是 2 的幂，`x & 33` 并不等于 `x % 34`，导致 34 字节密钥中只有 3 个不同字符真正参与运算，密钥强度被削到 3 字节 |
| `0x02` | `magic(4)` + `ver(1)` + ZIP | `pos & 33` | 仅预留，从未实际产出 |
| `0x03` | `magic(4)` + `ver(1)` + ZIP | `pos % 34` | **当前格式**。34 个密钥字节全部参与 |

读端**同时兼容全部历史版本**（按容器自带的版本字节/魔数自动判别），历史容器不会失效。

> ⚠️ 但请注意：**`0x03` 容器无法被 v1.9.3 及更早的版本打开**（算法已不同）。升级不会丢数据（老容器在新版里照样能解），但把新容器发给使用老版本的人，对方打不开。
>
> 另外，修正 `pos & 33` 只是让密钥从 3 字节变回 34 字节 —— **重复密钥异或本身的缺陷依然存在**，不要误以为它因此变得安全了。

### 其他安全相关事实

- 保险库里的文件**以明文存放在应用私有目录**（`/data/user/0/com.dhhxfggg.pjm/files/pjm_vault/`）。只有 `.pjm` 容器经过变换。应用私有目录由 Android 沙箱保护，但 root、可调试包（debuggable）或备份都可以读到。
- `allowBackup` 已关闭，且备份规则排除了保险库目录，避免系统云备份把明文带走。
- 仓库不包含任何密钥、签名文件或令牌（`local.properties`、`*.keystore`、`*.jks` 已被 `.gitignore` 排除）。
- 更新检查会校验下载地址的域名白名单，并比对 APK 签名与已安装版本一致，防止中间人替换安装包。

---

## 功能

### 保险库与容器
- **`.pjm` 容器**：文件打包为 ZIP 后经流式字节变换，支持大于 2GB 的文件（64 位偏移）。
- **分卷**：`.pjm.N` 多卷格式，每一卷都是独立完整的容器，可单独解开、互不依赖。
- **原生加速**：核心字节变换由 C++（JNI）实现，采用密钥流铺块 + 64 位整字异或；原生库不可用时自动回退到等价的 Kotlin 实现。
- **原子写入**：容器先写临时文件，`fsync` 落盘后再原子改名；断电或进程被杀不会产生「看起来完整但解不开」的半成品。
- **一键分享**：多选图片/视频打包为容器后直接调起系统分享，MIME 由真实内容推断。

### 资产管理
- **分类**：图片 / 视频 / 音频 / B站视频 / `.pjm` 容器 / 其他。
- **导入**：接收系统 `SEND` / `VIEW` 意图直接入库；支持压缩包自动解包与多卷归档合并；B站视频可从用户授权的任意目录（`ACTION_OPEN_DOCUMENT_TREE`）批量导入。
- **浏览**：按日期分桶、搜索、批量选择、系统工具打开。
- **导出**：通过 MediaStore 将单个文件导出到公共目录（`Pictures` / `Movies`），也可调起系统删除请求。
- **缩略图**：图片采样缩略与视频关键帧抽取，落盘为持久缓存，启动时后台补齐缺失项。
- **内容去重**：
  - **精确查重**：按文件大小分组后做内容哈希，字节级一致才归组；
  - **相似图片查重**：dHash 感知哈希粗筛 → 宽高比/面积预过滤 → 32×32 灰度预筛 → 64 宽灰度像素验证，四级过滤后在内存中完成判定；
  - 视频使用「时长 + 分辨率 + 关键帧 dHash」的感知指纹，能识别同源重封装。
- **完整性检查**：区分「文件丢失」与「内容损坏」，损坏判定只对确定是内容哈希的记录生效（感知指纹不参与，避免误报）。

### 发现页
- 图片全屏缩放（双击 / 双指），视频播放（拖动快进快退）。
- ExoPlayer 实例池化复用，避免滚动时反复创建解码器。
- 全量打乱队列，一轮内不重复浏览；已看过的优先靠后。

### 受限目录与特权访问
- **Shizuku**：以 shell 身份访问 `Android/data` 等受保护目录。
- **内置特权文件服务**：AIDL + 嵌入式进程，配合 `start.sh` 可在不安装 Shizuku 的情况下通过 adb 启动。

### 其他
- 前台服务执行后台任务（带进度通知，支持取消与超时）。
- 数据库每日自动备份（备份前执行 WAL checkpoint 以保证完整性）。
- 冷启动清理孤儿缓存、残留临时文件与失效的浏览历史。

---

## 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Kotlin + C++（JNI） |
| UI | Jetpack Compose（Material 3）、Navigation Compose、Coil 3、Media3 ExoPlayer |
| 架构 | MVVM + Repository + Hilt + Room |
| 数据 | Room（WAL、显式 Migration、导出 schema）、DataStore Preferences |
| 权限 | Shizuku、Scoped Storage 兼容、所有文件访问 |
| 压缩 | Apache Commons Compress、junrar、XZ |
| 构建 | AGP 9.x、KSP、NDK + CMake |
| 质量 | ktlint、JUnit + Robolectric、GitHub Actions |

---

## 构建

**环境要求**：JDK 17、Android SDK（compileSdk 37）、NDK 28.x、CMake 3.22+。

```bash
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包
```

常用任务：

```bash
./gradlew :app:testDebugUnitTest  # 单元测试
./gradlew ktlintCheck             # 代码风格检查
```

**运行要求**：Android 7.0+（minSdk 24），targetSdk 35。需要存储权限；在 Android 11+ 上建议授予「所有文件访问」，或使用 Shizuku 以获得完整功能。

---

## 项目结构

```
app/src/main/java/com/dhhxfggg/pjm/
├── data/                  # 数据层
│   ├── db/                #   AppDatabase（显式迁移）+ DAO
│   ├── model/             #   实体
│   └── repository/        #   仓库实现
├── domain/                # 业务逻辑
│   ├── util/              #   加密容器、扫描查重、缩略图、命名、分享等
│   ├── service/           #   前台服务
│   └── shizuku/           #   Shizuku 桥接与特权文件服务
├── di/                    # Hilt 模块
└── ui/                    # Compose UI（screen / viewmodel / component / navigation / theme）
app/src/main/cpp/          # JNI 字节变换实现
app/src/test/              # 单元测试（Robolectric）
app/schemas/               # Room schema JSON（入库，供迁移测试使用）
```

---

## 备份与恢复

保险库位于应用**私有目录**，普通文件管理器和 adb 都无法直接读写它。因此备份/恢复需要借助 `run-as`，而 `run-as` 只对 `android:debuggable="true"` 的包生效。

**备份思路**（在电脑上执行）：

```bash
# 1. 安装一个可调试包（与应用同签名即可覆盖安装，数据保留）
adb install -r app-debug.apk

# 2. 逐块打包并取回（分块是为了单块失败不影响整体）
adb shell "run-as com.dhhxfggg.pjm sh -c 'cd files/pjm_vault/images; tar cf - [0-3]*' > /data/local/tmp/chunk.tar"
adb pull /data/local/tmp/chunk.tar ./images_a.tar
adb shell "rm -f /data/local/tmp/chunk.tar"

# 3. 数据库与设置
adb shell "am force-stop com.dhhxfggg.pjm"
adb shell "run-as com.dhhxfggg.pjm tar cf - databases shared_prefs files/datastore > /data/local/tmp/meta.tar"
adb pull /data/local/tmp/meta.tar ./meta.tar

# 4. 装回正式包，关闭调试访问
adb install -r app-release.apk
adb shell run-as com.dhhxfggg.pjm id   # 应报 "not debuggable"
```

**恢复**即反向操作：装可调试包 → `adb push` + `run-as ... tar xf` 到对应目录 → 恢复数据库 → 装回正式包。

**校验**（恢复后应一致）：

```bash
adb shell "run-as com.dhhxfggg.pjm sh -c 'find files/pjm_vault -type f | wc -l'"
adb shell "run-as com.dhhxfggg.pjm sh -c 'find files/pjm_vault -type f -exec du -cb {} + | tail -1'"
```

> 备份出来的是**明文**。请存放在可信介质上，不要放同步盘或共享目录。

**注意**：应用内**没有「整库导出／导入」功能** —— 现有的 SAF 入口只用于「从授权目录导入 B 站视频」，导出也只支持单个文件到系统相册。因此整库的备份与恢复必须借助电脑和 adb。这是当前架构的一个已知缺口。

---

## 已知限制

- **不是加密软件**，见 [安全模型](#安全模型必读)。
- **应用内没有导入/导出功能**，备份恢复必须借助 adb + 可调试包。
- **`0x03` 容器与 v1.9.3 及更早版本不兼容**（新包发不出去给老版本用户）。
- 相似图片查重为 O(n²) 粗筛，在数万张量级下首次运行仍需较长时间（指纹需逐张解码）；之后有半永久缓存，增量运行很快。
- 保险库使用**扁平目录**存储（单目录可达数万文件），在十万级规模下目录遍历会变慢。
- 仅在 Android 15（API 35）的真机与模拟器上做过实际验证，其余版本未系统测试。

---

## 版本与更新

应用内更新检查读取本仓库的 [Releases](https://github.com/dhhxfggg2023/PJM/releases/latest)，比较 tag 与当前版本号，并从 Release 附件中取第一个 `.apk` 下载安装。因此发布新版本时需要：更新 `versionCode` / `versionName` → 构建 → 打 tag → 创建 Release 并上传 APK。

主要版本变化：

| 版本 | 变化 |
|---|---|
| 1.9.4 | 修正密钥流算法（`pos & 33` → `pos % 34`，容器版本 `0x03`）；查重三次解码降为一次并新增验证签名缓存；重建索引保留内容哈希；清理废弃表（DB v11） |
| 1.9.3 | 发现页「未看过优先」；分享链路统一（多选图片分享不再以文件形式送达） |
| 1.9.2 | 拆分 BiliBridge、崩溃日志、随机封面回归测试 |

---

## 许可

版权所有 © dhhxfggg2023，保留所有权利。未经授权请勿复制、分发或用于商业用途。
