#pragma once
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
extern "C" int __android_log_print(int, const char *, const char *, ...);
