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
-keep class com.dhhxfggg.pjm.domain.util.** { *; }
-keepclassmembers class com.dhhxfggg.pjm.domain.util.** { *; }

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
