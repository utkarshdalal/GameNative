#define _GNU_SOURCE
#include <errno.h>
#include <jni.h>
#include <sched.h>

/*
 * sched_setaffinity on one task (a thread id, or a pid for its main thread). Linux allows it on
 * any task of the caller's uid without CAP_SYS_NICE: the app's own threads and the processes it
 * spawned. Returns 0, or the errno (EPERM, ESRCH, EINVAL).
 */
JNIEXPORT jint JNICALL
Java_app_gamenative_powercontrol_TaskAffinity_nativeSetTaskAffinity(JNIEnv *env, jclass clazz, jint tid, jint mask) {
    (void) env;
    (void) clazz;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int cpu = 0; cpu < 32; cpu++) {
        if ((unsigned) mask & (1u << cpu)) CPU_SET(cpu, &set);
    }
    return sched_setaffinity(tid, sizeof(set), &set) == 0 ? 0 : errno;
}
