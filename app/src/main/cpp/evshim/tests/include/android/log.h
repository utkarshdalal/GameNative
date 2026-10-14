#ifndef EVSHIM_TEST_ANDROID_LOG_H
#define EVSHIM_TEST_ANDROID_LOG_H

/* Android logging is the only platform service replaced by this host test. */
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_DEBUG 3
static inline int __android_log_print(int priority, const char *tag, const char *format, ...)
{
    (void)priority;
    (void)tag;
    (void)format;
    return 0;
}

#endif
