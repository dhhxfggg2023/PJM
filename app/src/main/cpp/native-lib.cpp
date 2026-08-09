#include <jni.h>
#include <stdint.h>

/**
 * 极致性能优化：C++ 原生字节流异或变换
 * 相比 Java 循环，原生指针操作能显著提升吞吐量并降低 CPU 功耗
 */
extern "C" JNIEXPORT void JNICALL
Java_com_dhhxfggg_pjm_domain_util_CryptoUtils_transformBytesNative(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray data,
        jint off,
        jint len,
        jbyteArray key,
        jlong startPos) {
    
    // 获取数组指针
    jbyte* pData = env->GetByteArrayElements(data, nullptr);
    jbyte* pKey = env->GetByteArrayElements(key, nullptr);
    jsize keyLen = env->GetArrayLength(key);

    if (pData != nullptr && pKey != nullptr) {
        // 使用 uint8_t 确保无符号运算一致性
        uint8_t* uData = (uint8_t*)pData;
        uint8_t* uKey = (uint8_t*)pKey;
        uint32_t mask = (uint32_t)(keyLen - 1); // 假设 keyLen 为 32

        for (int i = 0; i < len; i++) {
            // 实现 & 31 位运算优化逻辑
            int keyIndex = (int)((startPos + i) & mask);
            uData[off + i] ^= uKey[keyIndex];
        }
    }

    // 释放并同步回 Java 数组
    env->ReleaseByteArrayElements(data, pData, 0);
    env->ReleaseByteArrayElements(key, pKey, JNI_ABORT); // Key 无需同步回 Java
}
