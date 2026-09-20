#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "lsfg_dll.h"
#include "lsfg_probe.h"

#define LSFG_FN(name) Java_com_winlator_renderer_lsfg_LosslessScaling_##name

static char* copy_utf(JNIEnv* env, jstring value) {
    if (!value) return NULL;
    const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!chars) return NULL;
    char* copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

JNIEXPORT jint JNICALL LSFG_FN(nativeBuildCache)(JNIEnv* env, jclass clazz, jstring dllPath,
                                                 jstring cachePath, jboolean preferFp16) {
    (void)clazz;
    char* dll = copy_utf(env, dllPath);
    char* cache = copy_utf(env, cachePath);
    LsfgStatus status = LSFG_NOT_INSTALLED;
    if (dll && cache) status = lsfg_build_cache(dll, cache, preferFp16 == JNI_TRUE);
    free(dll);
    free(cache);
    return (jint)status;
}

JNIEXPORT jboolean JNICALL LSFG_FN(nativeCacheMatchesSource)(JNIEnv* env, jclass clazz,
                                                             jstring cachePath, jstring dllPath) {
    (void)clazz;
    char* cache = copy_utf(env, cachePath);
    char* dll = copy_utf(env, dllPath);
    bool matches = false;
    if (cache && dll) {
        if (lsfg_cache_matches_source(cache, dll, &matches) != LSFG_OK) matches = false;
    }
    free(cache);
    free(dll);
    return matches ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL LSFG_FN(nativeSupportsFrameGeneration)(JNIEnv* env, jclass clazz,
                                                                  jstring driverName,
                                                                  jobject context) {
    (void)clazz;
    char* driver = copy_utf(env, driverName);
    const bool supported = lsfg_probe_support(env, context, driver);
    free(driver);
    return supported ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL LSFG_FN(nativeSupportsFp16)(JNIEnv* env, jclass clazz,
                                                      jstring driverName,
                                                      jobject context) {
    (void)clazz;
    char* driver = copy_utf(env, driverName);
    const bool supported = lsfg_probe_fp16_support(env, context, driver);
    free(driver);
    return supported ? JNI_TRUE : JNI_FALSE;
}
