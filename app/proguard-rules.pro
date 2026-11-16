# [Hilt / Dagger]
-keep class dagger.hilt.** { *; }
-keep interface dagger.hilt.** { *; }

# [Compose]
-keep class androidx.compose.runtime.snapshots.** { *; }
-keep class androidx.compose.runtime.Recomposer { *; }

# [Coroutines]
-keep class kotlinx.coroutines.** { *; }

# [DataStore] 关键：防止 DataStore 内部类被混淆导致启动挂起
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }
-keepnames class androidx.datastore.preferences.protobuf.** { *; }

# [Crypto & Compression]
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class com.github.junrar.** { *; }

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
# JNI 通过固定符号 Java_<包名>_<类名>_<方法名> 查找函数：类名与方法名都不能混淆。
# 只钉住这一个入口，CryptoUtils 其余成员与整个 domain.util 包都交回 R8 处理。
-keepnames class com.dhhxfggg.pjm.domain.util.CryptoUtils
-keepclassmembers class com.dhhxfggg.pjm.domain.util.CryptoUtils {
    native <methods>;
    void transformBytesNative(byte[], int, int, byte[], long, int);
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
