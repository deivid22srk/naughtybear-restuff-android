/* Stub de android/log.h para checagem de sintaxe no host. */
#ifndef _STUB_ANDROID_LOG_H
#define _STUB_ANDROID_LOG_H
#include <stdio.h>
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    (void)prio; (void)tag; (void)fmt; return 0;
}
#endif
