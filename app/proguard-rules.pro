# [Hilt / Dagger]
-keep class dagger.hilt.** { *; }
-keep interface dagger.hilt.** { *; }

# [Compose]
-keep class androidx.compose.runtime.snapshots.** { *; }
-keep class androidx.compose.runtime.Recomposer { *; }

# [Coroutines] 仅保类名:官方 consumer rules 已处理反射,未用协程类交给 R8 裁剪
-keepnames class kotlinx.coroutines.**

# [DataStore] 仅保类名:consumer rules 已覆盖反射入口,允许裁剪未用类以减小包体
-keepnames class androidx.datastore.**
-keepnames class androidx.datastore.preferences.**
-keepnames class androidx.datastore.preferences.protobuf.**

# [Crypto & Compression] 仅保类名:压缩库按接口调用,consumer rules 已处理反射
-keepnames class org.apache.commons.compress.**
-keepnames class org.tukaani.xz.**
-keepnames class com.github.junrar.**

# [SLF4J]
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# [Commons Compress] 忽略可选依赖和缺失的 ASM 引用
-dontwarn org.apache.commons.compress.compressors.zstandard.**
-dontwarn org.apache.commons.compress.compressors.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.apache.commons.compress.harmony.pack200.**
-dontwarn org.objectweb.asm.**

# [PJM Core Utils]
# 核心修复：旧规则把整个核心包（CryptoUtils/VaultManager/IngestionEngine…）全量 keep，
# 与 isMinifyEnabled=true 直接矛盾：应用自身最大的一块既不混淆也不裁剪，
# 固定 XOR 密钥的 CryptoUtils 类名/成员名还被完整保留，主动降低了逆向成本。
# 只有 JNI 入口（native 方法名 + 调用它的类）必须保留；其余交给 R8 正常处理。
# JNI 通过固定符号 Java_<包名>_<类名>_<方法名> 查找函数：类名、方法名以及参数类型都必须钉住。
# -keepnames 只保类名/方法名，不保参数描述符——签名一旦被 R8 改写，符号就对不上导致 UnsatisfiedLinkError。
# includedescriptorclasses 把 native 方法签名里出现的类型也一起保留，确保 JNI 符号稳定；
# CryptoUtils 其余成员与整个 domain.util 包仍交回 R8 正常混淆/裁剪。
-keepclasseswithmembernames,includedescriptorclasses class com.dhhxfggg.pjm.domain.util.CryptoUtils {
    native <methods>;
}

# [PJM Shizuku/特权服务] 关键：FileServerMain 由 app_process 反射启动，
# 类名/入口绝不能混淆（否则 release 版特权服务无法启动）
-keep class com.dhhxfggg.pjm.domain.shizuku.** { *; }
-keepclassmembers class com.dhhxfggg.pjm.domain.shizuku.** { *; }
-keep class com.dhhxfggg.pjm.IFileBridgeService { *; }
-keep class com.dhhxfggg.pjm.IFileBridgeService$Stub { *; }

# [Models & Entities]
-keep class com.dhhxfggg.pjm.data.model.** { *; }

# [Android System / Support]
-keep class androidx.core.content.FileProvider { *; }
