// Desktop stand-in for <android/log.h>, so android/app/src/main/cpp/llm_jni.cpp builds unchanged on Linux.
#pragma once
#include <stdio.h>
#define ANDROID_LOG_ERROR 6
#define __android_log_print(priority, tag, ...) \
    (fprintf(stderr, "E/%s: ", tag), fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
