#pragma once

#include <jni.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool lsfg_probe_support(JNIEnv* env, jobject context, const char* driver_name);

#ifdef __cplusplus
}
#endif