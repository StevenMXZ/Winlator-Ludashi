#pragma once
#include <cstdio>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
inline int __android_log_write(int, const char* tag, const char* msg) { return fprintf(stderr, "%s: %s\n", tag, msg); }
