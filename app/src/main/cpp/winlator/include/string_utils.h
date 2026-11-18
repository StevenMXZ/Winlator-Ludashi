#ifndef WINLATOR_STRING_UTILS_H
#define WINLATOR_STRING_UTILS_H

#include <ctype.h>
#include <stdarg.h>

#include "arrays.h"

#define INT2CHR(x) ('0' + x)
#define CHR2INT(x) (x - '0')
#define FOREACH_LINE(input, inputSize, content) \
    do { \
        int pos = 0; \
        int lnStart = 0;  \
        int inputLimit = inputSize; \
        char* lastLine = NULL; \
        while (pos < inputLimit) { \
            if (input[pos] == '\n' || pos == inputLimit-1) { \
                bool wasNewLine = input[pos] == '\n'; \
                if (wasNewLine) input[pos] = '\0'; \
                char* line = input + lnStart; \
                int lineLen = pos - lnStart + (input[pos] != '\0' ? 1 : 0); \
                if (pos == inputLimit-1) { \
                    if (lineLen == 0) break; \
                    if (input[pos] != '\0') line = lastLine = substr(line, 0, lineLen); \
                } \
                content \
                if (wasNewLine) input[pos] = '\n';\
                if (lastLine) { \
                    if (memcmp(input + lnStart, lastLine, lineLen) != 0) memcpy(input + lnStart, lastLine, lineLen); \
                    free(lastLine); \
                } \
                lnStart = pos + 1; \
            } \
            pos++; \
        } \
    } \
    while (0)

#ifdef __ANDROID__
#include <jni.h>

static inline ArrayList* jstringArrayToCharArray(JNIEnv* env, jobjectArray stringArray) {
    if (!stringArray) return NULL;
    int stringCount = (*env)->GetArrayLength(env, stringArray);
    ArrayList* arrayList = calloc(1, sizeof(ArrayList));

    for (int i = 0; i < stringCount; i++) {
        jstring string = (jstring)((*env)->GetObjectArrayElement(env, stringArray, i));
        const char* rawString = (*env)->GetStringUTFChars(env, string, 0);
        ArrayList_add(arrayList, strdup(rawString));
        (*env)->ReleaseStringUTFChars(env, string, rawString);
    }

    return arrayList;
}

static inline wchar_t* jstringToWChars(JNIEnv* env, jstring string) {
    const jchar* stringPtr = (*env)->GetStringChars(env, string, 0);
    size_t len = (*env)->GetStringLength(env, string);

    wchar_t* result = calloc(len + 1, sizeof(wchar_t));
    int i = 0;
    while (len > 0) {
        result[i] = stringPtr[i];
        len--;
        i++;
    }

    (*env)->ReleaseStringChars(env, string, stringPtr);
    return result;
}
#endif

static inline bool cstartswith(char* prefix, char* string) {
    while (*prefix) if (*prefix++ != *string++) return false;
    return true;
}

static inline bool wcstartswith(wchar_t* prefix, wchar_t* string) {
    while (*prefix) if (*prefix++ != *string++) return false;
    return true;
}

static inline int mbclen(wchar_t* string) {
    int count = 0;
    while (*string) if (*string++ > 0x7f) count++;
    return count;
}

static inline void* memmerge(void* src, size_t srcSize, void* dst, size_t dstSize, size_t dstOffset, size_t* newSize) {
    if (dstOffset > dstSize) dstOffset = dstSize;
    void* res = malloc(srcSize + dstSize);
    memcpy(res, dst, dstOffset);
    memcpy(res + dstOffset, src, srcSize);
    memcpy(res + dstOffset + srcSize, dst + dstOffset, dstSize - dstOffset);
    if (newSize) *newSize = srcSize + dstSize;
    return res;
}

static inline void* memdel(void* src, size_t size, size_t offset, size_t delSize, size_t* newSize) {
    if (newSize) *newSize = 0;
    int movedCount = size - offset - delSize;
    if (movedCount <= 0) return NULL;

    void* dst = malloc(size - delSize);
    memcpy(dst, src, offset);
    memcpy(dst + offset, src + offset + delSize, movedCount);
    if (newSize) *newSize = size - delSize;
    return dst;
}

static inline void* memdup(const void* src, size_t size) {
    void* dst = malloc(size);
    if (dst != NULL) memcpy(dst, src, size);
    return dst;
}

static inline char* ltrim(char* s) {
    while (isspace(*s)) s++;
    return s;
}

static inline char* rtrim(char* s) {
    char* back = s + strlen(s);
    while (isspace(*--back));
    *(back+1) = '\0';
    return s;
}

static inline char* trim(char* s) {
    return ltrim(rtrim(s));
}

static inline void strsplit(char* input, char delimiter, ArrayList* outStrings) {
    char* index;
    while ((index = strchr(input, delimiter)) != NULL) {
        int len = index - input;
        char* string = malloc(len + 1);
        memcpy(string, input, len);
        string[len] = '\0';
        ArrayList_add(outStrings, string);
        input = index + 1;
    }

    if (*input != '\0') {
        int len = strlen(input);
        char* string = malloc(len + 1);
        memcpy(string, input, len);
        string[len] = '\0';
        ArrayList_add(outStrings, string);
    }
}

static inline char* strjoin(char delimiter, int count, ...) {
    va_list valist;
    va_start(valist, count);

    int size = 0;
    for (int i = 0; i < count; i++) {
        char* string = va_arg(valist, char*);
        size += (i > 0 && delimiter ? 1 : 0) + strlen(string);
    }

    va_start(valist, count);

    char* result = malloc(size + 1);
    for (int i = 0, j = 0; i < count; i++) {
        char* string = va_arg(valist, char*);
        if (i > 0 && delimiter) result[j++] = delimiter;
        int len = strlen(string);
        memcpy(result + j, string, len);
        j += len;
    }

    result[size] = '\0';
    va_end(valist);
    return result;
}

static inline char* strwrd(char* haystack, char* needle, char** endPtr) {
    char* chr = haystack;
    int start = -1;
    char* result = NULL;
    if (endPtr) *endPtr = NULL;

    while (1) {
        if (isalnum(*chr) || *chr == '_') {
            if (start == -1) start = chr - haystack;
        }
        else if (start != -1) {
            char* word = haystack + start;

            char old = *chr;
            *chr = '\0';
            bool found = !needle || strcmp(word, needle) == 0;
            *chr = old;

            if (found) {
                if (endPtr) *endPtr = chr;
                result = word;
                break;
            }
            start = -1;
        }

        if (!*chr) break;
        chr++;
    }

    return result;
}

static inline char* str_replace(char* search, char* replace, char* subject) {
    int strSize = strlen(subject) + 1;
    char* result = malloc(strSize);
    int offset = 0;

    char* str;
    while ((str = strstr(subject, search))) {
        memcpy(result + offset, subject, str - subject);
        offset += str - subject;
        subject = str + strlen(search);
        strSize = strSize - strlen(search) + strlen(replace);
        result = realloc(result, strSize);
        memcpy(result + offset, replace, strlen(replace));
        offset += strlen(replace);
    }

    strcpy(result + offset, subject);
    return result;
}

static inline char* substr_replace(char* replace, int start, int end, char* subject) {
    char prefix[start + 1];
    memcpy(prefix, subject, start);
    prefix[start] = '\0';
    return strjoin(0, 3, prefix, replace, subject + end);
}

static inline char* strwrd_replace(char* search, char* replace, char* subject) {
    char* start;
    char* end;
    char* old;
    while ((start = strwrd(subject, search, &end))) {
        subject = substr_replace(replace, start - subject, end - subject, old = subject);
        free(old);
    }
    return subject;
}

static inline char* substr(char* string, int offset, int length) {
    char* result = malloc(length + 1);
    memcpy(result, string + offset, length);
    result[length] = '\0';
    return result;
}

static inline bool substrv(char* string, char delimiterStart, char delimiterEnd, char* result) {
    if (*string != delimiterStart) {
        string = strchr(string, delimiterStart);
        if (!string) {
            result[0] = '\0';
            return false;
        }
    }
    string++;
    int i = 0;
    while (*string && *string != delimiterEnd) result[i++] = *string++;
    result[i] = '\0';
    return true;
}

static inline int strnums(char* string, char* result) {
    result[0] = '\0';
    if (!string) return 0;
    int i = 0;
    while (*string++) if (isdigit(*string)) result[i++] = *string;
    result[i] = '\0';
    return i;
}

static inline bool is_int(char* string) {
    if (!string) return false;
    if (string[0] == '-' || string[0] == '+') string++;
    bool hasDigit = false;
    while (*string) {
        if (!isdigit(*string)) return false;
        hasDigit = true;
        string++;
    }
    return hasDigit;
}

static inline bool is_float(char* string) {
    if (!string) return false;
    char* endPtr;
    strtod(string, &endPtr);
    if (string == endPtr) return false;
    while (isspace((uint8_t)*endPtr)) endPtr++;
    return *endPtr == '\0';
}

static inline uint32_t fnv1aHash32(const void* data, int len) {
#define FNV_PRIME_32 0x1000193

    uint32_t hash = 0x811c9dc5;
    const uint8_t* bytes = (const uint8_t*)data;

    for (int i = 0; i < len; i++) {
        hash ^= bytes[i];
        hash *= FNV_PRIME_32;
    }
    return hash;
}

// MurmurHash2, 64-bit version, by Austin Appleby
static inline uint64_t murmurHash64(const char* key, size_t len, uint64_t seed) {
    const uint64_t kMul = 0xc6a4a7935bd1e995UL;
    const int kShift = 47;
    uint64_t hash = seed ^ (len * kMul);
    const uint64_t* ptr = (const uint64_t*)key;
    const uint64_t* end = ptr + (len / 8);

    while (ptr != end) {
        uint64_t k = *ptr++;

        k *= kMul;
        k ^= k >> kShift;
        k *= kMul;

        hash ^= k;
        hash *= kMul;
    }

    const u_char* data = (const u_char*)ptr;
    switch (len & 7) {
        case 7: hash ^= ((uint64_t)data[6]) << 48;
        case 6: hash ^= ((uint64_t)data[5]) << 40;
        case 5: hash ^= ((uint64_t)data[4]) << 32;
        case 4: hash ^= ((uint64_t)data[3]) << 24;
        case 3: hash ^= ((uint64_t)data[2]) << 16;
        case 2: hash ^= ((uint64_t)data[1]) << 8;
        case 1: hash ^= ((uint64_t)data[0]);
                hash *= kMul;
    };

    hash ^= hash >> kShift;
    hash *= kMul;
    hash ^= hash >> kShift;
    return hash;
}

#endif