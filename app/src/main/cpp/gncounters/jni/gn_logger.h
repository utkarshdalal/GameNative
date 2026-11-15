/* Routes libGPUCounters logging (HWCPIPE_LOGGER_HEADER) to logcat tag GNCounters, rate limited. */
#pragma once
#ifdef __cplusplus
extern "C"
#endif
void gn_hwc_log(int level, const char *fmt, ...);
#define HWCPIPE_LOG_INFO(...) gn_hwc_log(0, __VA_ARGS__)
#define HWCPIPE_LOG_ERROR(...) gn_hwc_log(1, __VA_ARGS__)
