#include <stdint.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <stdarg.h>

#include "arrays.h"
#include "string_utils.h"
#include "winlator.h"

#define ARRAY_BUFFER_PUT_TYPE(arrayBuffer, type, value) \
    int targetSize = arrayBuffer->size + sizeof(type); \
    ENSURE_ARRAY_CAPACITY(targetSize, arrayBuffer->capacity, arrayBuffer->buffer, 1); \
    *(type*)(arrayBuffer->buffer + arrayBuffer->size) = value; \
    arrayBuffer->size = targetSize

#define ARRAY_BUFFER_GET_TYPE(arrayBuffer, type) \
    type value = *(type*)(arrayBuffer->buffer + arrayBuffer->position); \
    arrayBuffer->position += sizeof(type); \
    return value

void ArrayBuffer_put(ArrayBuffer* arrayBuffer, char value) {
    ENSURE_ARRAY_CAPACITY(arrayBuffer->size + 1, arrayBuffer->capacity, arrayBuffer->buffer, 1);
    arrayBuffer->buffer[arrayBuffer->size++] = value;
}

void ArrayBuffer_putShort(ArrayBuffer* arrayBuffer, short value) {
    ARRAY_BUFFER_PUT_TYPE(arrayBuffer, short, value);
}

void ArrayBuffer_putInt(ArrayBuffer* arrayBuffer, int value) {
    ARRAY_BUFFER_PUT_TYPE(arrayBuffer, int, value);
}

void ArrayBuffer_putLong(ArrayBuffer* arrayBuffer, long value) {
    ARRAY_BUFFER_PUT_TYPE(arrayBuffer, long, value);
}

void ArrayBuffer_putFloat(ArrayBuffer* arrayBuffer, float value) {
    ARRAY_BUFFER_PUT_TYPE(arrayBuffer, float, value);
}

float* ArrayBuffer_putFloat2(ArrayBuffer* arrayBuffer, float x, float y) {
    int targetSize = arrayBuffer->size + 2 * sizeof(float);
    ENSURE_ARRAY_CAPACITY(targetSize, arrayBuffer->capacity, arrayBuffer->buffer, 1);
    float* vec2 = (float*)(arrayBuffer->buffer + arrayBuffer->size);
    vec2[0] = x;
    vec2[1] = y;
    arrayBuffer->size = targetSize;
    return vec2;
}

float* ArrayBuffer_putFloat3(ArrayBuffer* arrayBuffer, float x, float y, float z) {
    int targetSize = arrayBuffer->size + 3 * sizeof(float);
    ENSURE_ARRAY_CAPACITY(targetSize, arrayBuffer->capacity, arrayBuffer->buffer, 1);
    float* vec3 = (float*)(arrayBuffer->buffer + arrayBuffer->size);
    vec3[0] = x;
    vec3[1] = y;
    vec3[2] = z;
    arrayBuffer->size = targetSize;
    return vec3;
}

float* ArrayBuffer_putFloat4(ArrayBuffer* arrayBuffer, float x, float y, float z, float w) {
    int targetSize = arrayBuffer->size + 4 * sizeof(float);
    ENSURE_ARRAY_CAPACITY(targetSize, arrayBuffer->capacity, arrayBuffer->buffer, 1);
    float* vec4 = (float*)(arrayBuffer->buffer + arrayBuffer->size);
    vec4[0] = x;
    vec4[1] = y;
    vec4[2] = z;
    vec4[3] = w;
    arrayBuffer->size = targetSize;
    return vec4;
}

void ArrayBuffer_putDouble(ArrayBuffer* arrayBuffer, double value) {
    ARRAY_BUFFER_PUT_TYPE(arrayBuffer, double, value);
}

void ArrayBuffer_putBytes(ArrayBuffer* arrayBuffer, const void* bytes, int size) {
    ENSURE_ARRAY_CAPACITY(arrayBuffer->size + size, arrayBuffer->capacity, arrayBuffer->buffer, 1);
    if (!bytes) {
        memset(arrayBuffer->buffer + arrayBuffer->size, 0, size);
    }
    else memcpy(arrayBuffer->buffer + arrayBuffer->size, bytes, size);
    arrayBuffer->size += size;
}

void ArrayBuffer_putString(ArrayBuffer* arrayBuffer, const char* string, ...) {
    char buffer[strlen(string) + 128];
    va_list valist;
    va_start(valist, string);
    vsprintf(buffer, string, valist);
    va_end(valist);
    ArrayBuffer_putBytes(arrayBuffer, buffer, strlen(buffer));
}

char ArrayBuffer_get(ArrayBuffer* arrayBuffer) {
    return arrayBuffer->buffer[arrayBuffer->position++];
}

short ArrayBuffer_getShort(ArrayBuffer* arrayBuffer) {
    ARRAY_BUFFER_GET_TYPE(arrayBuffer, short);
}

int ArrayBuffer_getInt(ArrayBuffer* arrayBuffer) {
    ARRAY_BUFFER_GET_TYPE(arrayBuffer, int);
}

long ArrayBuffer_getLong(ArrayBuffer* arrayBuffer) {
    ARRAY_BUFFER_GET_TYPE(arrayBuffer, long);
}

float ArrayBuffer_getFloat(ArrayBuffer* arrayBuffer) {
    ARRAY_BUFFER_GET_TYPE(arrayBuffer, float);
}

double ArrayBuffer_getDouble(ArrayBuffer* arrayBuffer) {
    ARRAY_BUFFER_GET_TYPE(arrayBuffer, double);
}

void* ArrayBuffer_getBytes(ArrayBuffer* arrayBuffer, int size) {
    char* bytes = arrayBuffer->buffer + arrayBuffer->position;
    arrayBuffer->position += size;
    return bytes;
}

void ArrayBuffer_skip(ArrayBuffer* arrayBuffer, int length) {
    arrayBuffer->position += length;
}

int ArrayBuffer_available(ArrayBuffer* arrayBuffer) {
    return arrayBuffer->size - arrayBuffer->position;
}

void ArrayBuffer_rewind(ArrayBuffer* arrayBuffer) {
    arrayBuffer->position = 0;
    arrayBuffer->size = 0;
}

void ArrayBuffer_free(ArrayBuffer* arrayBuffer) {
    if (arrayBuffer && arrayBuffer->buffer) {
        free(arrayBuffer->buffer);
        arrayBuffer->buffer = NULL;
    }

    arrayBuffer->capacity = 0;
    arrayBuffer->position = 0;
    arrayBuffer->size = 0;
}

void ArrayBuffer_copy(ArrayBuffer* src, ArrayBuffer* dst) {
    ArrayBuffer_free(dst);
    dst->buffer = memdup(src->buffer, src->size);
    dst->capacity = dst->size = src->size;
}

static int intCompare(const void * a, const void * b) {
    return (*(int*)a - *(int*)b);
}

static int hashCode(const char* key) {
    int hash = 0;
    for (const char* p = key; *p; p++) hash = 31 * hash + *p;
    return hash;
}

void IntArray_add(IntArray* intArray, int value) {
    ENSURE_ARRAY_CAPACITY((intArray->size + 1) * sizeof(int), intArray->capacity, intArray->values, 1);
    intArray->values[intArray->size++] = value;
}

void IntArray_addAt(IntArray* intArray, int index, int value) {
    if (index < 0 || index >= intArray->size) {
        IntArray_add(intArray, value);
        return;
    }

    ENSURE_ARRAY_CAPACITY((intArray->size + 1) * sizeof(int), intArray->capacity, intArray->values, 1);
    memmove(intArray->values + index + 1, intArray->values + index, (intArray->size - index) * sizeof(int));
    intArray->values[index] = value;
    intArray->size++;
}

void IntArray_addAll(IntArray* intArray, int valueCount, ...) {
    va_list valist;
    va_start(valist, valueCount);

    ENSURE_ARRAY_CAPACITY((intArray->size + valueCount) * sizeof(int), intArray->capacity, intArray->values, 1);
    for (int i = 0; i < valueCount; i++) intArray->values[intArray->size++] = va_arg(valist, int);

    va_end(valist);
}

void IntArray_remove(IntArray* intArray, int offset, int count) {
    size_t newSize;
    int* newValues = memdel(intArray->values, intArray->size * sizeof(int), offset * sizeof(int), count * sizeof(int), &newSize);
    if (newValues) {
        int* oldValues = intArray->values;
        intArray->size = newSize / sizeof(int);
        intArray->values = newValues;
        free(oldValues);
    }
    else if (intArray->size <= 1) {
        IntArray_clear(intArray);
    }
}

int IntArray_removeAt(IntArray* intArray, int index) {
    if (index < 0 || index >= intArray->size) return -1;
    int oldValue = intArray->values[index];
    IntArray_remove(intArray, index, 1);
    return oldValue;
}

void IntArray_clear(IntArray* intArray) {
    if (intArray && intArray->values) {
        free(intArray->values);
        intArray->values = NULL;
        intArray->size = 0;
        intArray->capacity = 0;
    }
}

void IntArray_sort(IntArray* intArray) {
    if (intArray) qsort(intArray->values, intArray->size, sizeof(int), intCompare);
}

int IntArray_indexOf(IntArray* intArray, int value) {
    for (int i = 0; i < intArray->size; i++) if (intArray->values[i] == value) return i;
    return -1;
}

int ArrayList_indexOf(ArrayList* arrayList, void* element) {
    for (int i = 0; i < arrayList->size; i++) if (arrayList->elements[i] == element) return i;
    return -1;
}

void ArrayList_add(ArrayList* arrayList, void* element) {
    if (!element) return;
    ENSURE_ARRAY_CAPACITY(arrayList->size + 1, arrayList->capacity, arrayList->elements, sizeof(void*));
    arrayList->elements[arrayList->size++] = element;
}

void ArrayList_addAt(ArrayList* arrayList, int index, void* element) {
    if (!element) return;
    if (index < 0 || index >= arrayList->size) {
        ArrayList_add(arrayList, element);
        return;
    }

    ENSURE_ARRAY_CAPACITY(arrayList->size + 1, arrayList->capacity, arrayList->elements, sizeof(void*));
    memmove(arrayList->elements + index + 1, arrayList->elements + index, (arrayList->size - index) * sizeof(void*));
    arrayList->elements[index] = element;
    arrayList->size++;
}

void ArrayList_fill(ArrayList* arrayList, int size, void* element) {
    if (!element) return;
    ENSURE_ARRAY_CAPACITY(arrayList->size + size, arrayList->capacity, arrayList->elements, sizeof(void*));
    for (int i = 0; i < size; i++) arrayList->elements[arrayList->size++] = element;
}

void* ArrayList_removeAt(ArrayList* arrayList, int index) {
    if (index < 0 || index >= arrayList->size) return NULL;

    void* element = arrayList->elements[index];
    int movedCount = arrayList->size - index - 1;
    if (movedCount > 0) memmove(arrayList->elements + index, arrayList->elements + index + 1, movedCount * sizeof(void*));
    arrayList->elements[--arrayList->size] = NULL;
    return element;
}

void ArrayList_remove(ArrayList* arrayList, void* element) {
    int index = ArrayList_indexOf(arrayList, element);
    if (index != -1) ArrayList_removeAt(arrayList, index);
}

void ArrayList_free(ArrayList* arrayList, bool freeValues) {
    if (!arrayList) return;

    if (arrayList->elements) {
        if (freeValues) {
            for (int i = 0; i < arrayList->size; i++) {
                if (arrayList->elements[i]) {
                    free(arrayList->elements[i]);
                    arrayList->elements[i] = NULL;
                }
            }
        }

        free(arrayList->elements);
        arrayList->elements = NULL;
    }

    arrayList->size = 0;
    arrayList->capacity = 0;
}

ArrayList* ArrayList_fromStrings(const char** strings, int size) {
    ArrayList* arrayList = calloc(1, sizeof(ArrayList));
    for (int i = 0; i < size; i++) ArrayList_add(arrayList, strdup(strings[i]));
    return arrayList;
}

static int ArrayMap_binarySearch(ArrayMap* arrayMap, const char* key) {
    int lo = 0;
    int hi = arrayMap->size - 1;
    int hash = hashCode(key);

    while (lo <= hi) {
        int mid = (lo + hi) >> 1;
        int midHash = arrayMap->entries[mid].key ? hashCode(arrayMap->entries[mid].key) : 0;

        if (midHash < hash) {
            lo = mid + 1;
        }
        else if (midHash > hash) {
            hi = mid - 1;
        }
        else return mid;
    }
    return ~lo;
}

int ArrayMap_indexOfKey(ArrayMap* arrayMap, const char* key) {
    if (arrayMap->size == 0) return -1;
    int index = ArrayMap_binarySearch(arrayMap, key);
    if (index < 0) return index;
    if (strcmp(key, arrayMap->entries[index].key) == 0) return index;

    int end;
    int hash = hashCode(key);
    for (end = index + 1; end < arrayMap->size && hashCode(arrayMap->entries[end].key) == hash; end++) {
        if (strcmp(key, arrayMap->entries[end].key) == 0) return end;
    }

    for (int i = index - 1; i >= 0 && hashCode(arrayMap->entries[i].key) == hash; i--) {
        if (strcmp(key, arrayMap->entries[i].key) == 0) return i;
    }
    return ~end;
}

void ArrayMap_put(ArrayMap* arrayMap, const char* key, void* value) {
    int index = ArrayMap_indexOfKey(arrayMap, key);
    if (index >= 0) {
        arrayMap->entries[index].value = value;
        return;
    }

    index = ~index;
    ENSURE_ARRAY_CAPACITY(arrayMap->size + 1, arrayMap->capacity, arrayMap->entries, sizeof(ArrayMap_Entry));

    if (index < arrayMap->size) {
        memmove(arrayMap->entries + index + 1, arrayMap->entries + index, (arrayMap->size - index) * sizeof(ArrayMap_Entry));
    }

    arrayMap->entries[index].key = key;
    arrayMap->entries[index].value = value;
    arrayMap->size++;
}

void* ArrayMap_get(ArrayMap* arrayMap, const char* key) {
    int index = ArrayMap_indexOfKey(arrayMap, key);
    return index >= 0 ? arrayMap->entries[index].value : NULL;
}

void* ArrayMap_removeAt(ArrayMap* arrayMap, int index) {
    if (index >= arrayMap->size) return NULL;
    void* oldValue = arrayMap->entries[index].value;
    memmove(arrayMap->entries + index, arrayMap->entries + index + 1, (arrayMap->size - (index + 1)) * sizeof(ArrayMap_Entry));
    arrayMap->size--;
    arrayMap->entries[arrayMap->size].key = NULL;
    arrayMap->entries[arrayMap->size].value = NULL;
    return oldValue;
}

void* ArrayMap_remove(ArrayMap* arrayMap, const char* key) {
    int index = ArrayMap_indexOfKey(arrayMap, key);
    return index >= 0 ? ArrayMap_removeAt(arrayMap, index) : NULL;
}

void ArrayMap_free(ArrayMap* arrayMap, bool freeKeys, bool freeValues) {
    if (arrayMap->entries) {
        for (int i = 0; i < arrayMap->capacity; i++) {
            if (freeKeys && arrayMap->entries[i].key) free(arrayMap->entries[i].key);
            if (freeValues && arrayMap->entries[i].value) free(arrayMap->entries[i].value);
        }
        free(arrayMap->entries);
        arrayMap->entries = NULL;
    }

    arrayMap->size = 0;
    arrayMap->capacity = 0;
}

#define SPARSEARRAY_BINARY_SEARCH(sparseArray, key, type) \
    int lo = 0; \
    int hi = sparseArray->size - 1;  \
    int mid; \
    type midVal; \
\
    while (lo <= hi) { \
        mid = (lo + hi) >> 1; \
        midVal = sparseArray->entries[mid].key; \
\
        if (midVal < key) { \
            lo = mid + 1; \
        } \
        else if (midVal > key) { \
            hi = mid - 1; \
        } \
        else return mid; \
    } \
    return ~lo

#define SPARSEARRAY_PUT(className, sparseArray, key, value) \
    int index = className##_indexOfKey(sparseArray, key); \
    if (index >= 0) { \
        sparseArray->entries[index].value = value; \
        return; \
    } \
\
    index = ~index; \
    ENSURE_ARRAY_CAPACITY(sparseArray->size + 1, sparseArray->capacity, sparseArray->entries, sizeof(className##_Entry)); \
\
    if (index < sparseArray->size) { \
        memmove(sparseArray->entries + index + 1, sparseArray->entries + index, (sparseArray->size - index) * sizeof(className##_Entry)); \
    } \
\
    sparseArray->entries[index].key = key; \
    sparseArray->entries[index].value = value; \
    sparseArray->size++

#define SPARSEARRAY_REMOVE_AT(className, sparseArray, index) \
    if (index >= sparseArray->size) return NULL; \
    void* oldValue = sparseArray->entries[index].value; \
    memmove(sparseArray->entries + index, sparseArray->entries + index + 1, (sparseArray->size - (index + 1)) * sizeof(className##_Entry)); \
    sparseArray->size--; \
    sparseArray->entries[sparseArray->size].key = 0; \
    sparseArray->entries[sparseArray->size].value = NULL; \
    return oldValue

#define SPARSEARRAY_FREE(sparseArray, freeValues) \
    if (sparseArray->entries) { \
        if (freeValues) { \
            for (int i = 0; i < sparseArray->capacity; i++) { \
                if (sparseArray->entries[i].value) free(sparseArray->entries[i].value); \
            } \
        } \
        free(sparseArray->entries); \
        sparseArray->entries = NULL; \
    } \
\
    sparseArray->size = 0; \
    sparseArray->capacity = 0;

int SparseArray_indexOfKey(SparseArray* sparseArray, int key) {
    if (!sparseArray->entries) return -1;
    SPARSEARRAY_BINARY_SEARCH(sparseArray, key, int);
}

void SparseArray_put(SparseArray* sparseArray, int key, void* value) {
    SPARSEARRAY_PUT(SparseArray, sparseArray, key, value);
}

void* SparseArray_get(SparseArray* sparseArray, int key) {
    int index = SparseArray_indexOfKey(sparseArray, key);
    return index >= 0 ? sparseArray->entries[index].value : NULL;
}

void* SparseArray_removeAt(SparseArray* sparseArray, int index) {
    SPARSEARRAY_REMOVE_AT(SparseArray, sparseArray, index);
}

void* SparseArray_remove(SparseArray* sparseArray, int key) {
    int index = SparseArray_indexOfKey(sparseArray, key);
    return index >= 0 ? SparseArray_removeAt(sparseArray, index) : NULL;
}

void SparseArray_free(SparseArray* sparseArray, bool freeValues) {
    SPARSEARRAY_FREE(sparseArray, freeValues);
}

int SparseIntArray_indexOfKey(SparseIntArray* sparseArray, int key) {
    if (!sparseArray->entries) return -1;
    SPARSEARRAY_BINARY_SEARCH(sparseArray, key, int);
}

void SparseIntArray_put(SparseIntArray* sparseArray, int key, int value) {
    SPARSEARRAY_PUT(SparseIntArray, sparseArray, key, value);
}

int SparseIntArray_get(SparseIntArray* sparseArray, int key) {
    int index = SparseIntArray_indexOfKey(sparseArray, key);
    return index >= 0 ? sparseArray->entries[index].value : 0;
}

void SparseIntArray_removeAt(SparseIntArray* sparseArray, int index) {
    if (index >= sparseArray->size) return;
    memmove(sparseArray->entries + index, sparseArray->entries + index + 1, (sparseArray->size - (index + 1)) * sizeof(SparseIntArray_Entry));
    sparseArray->size--;
    sparseArray->entries[sparseArray->size].key = 0;
    sparseArray->entries[sparseArray->size].value = 0;
}

void SparseIntArray_remove(SparseIntArray* sparseArray, int key) {
    int index = SparseIntArray_indexOfKey(sparseArray, key);
    if (index >= 0) SparseIntArray_removeAt(sparseArray, index);
}

void SparseIntArray_free(SparseIntArray* sparseArray) {
    if (sparseArray->entries) {
        free(sparseArray->entries);
        sparseArray->entries = NULL;
    }

    sparseArray->size = 0;
    sparseArray->capacity = 0;
}

int SparseArray64_indexOfKey(SparseArray64* sparseArray, int64_t key) {
    if (!sparseArray->entries) return -1;
    SPARSEARRAY_BINARY_SEARCH(sparseArray, key, int64_t);
}

void SparseArray64_put(SparseArray64* sparseArray, int64_t key, void* value) {
    SPARSEARRAY_PUT(SparseArray64, sparseArray, key, value);
}

void* SparseArray64_get(SparseArray64* sparseArray, int64_t key) {
    int index = SparseArray64_indexOfKey(sparseArray, key);
    return index >= 0 ? sparseArray->entries[index].value : NULL;
}

void* SparseArray64_removeAt(SparseArray64* sparseArray, int index) {
    SPARSEARRAY_REMOVE_AT(SparseArray64, sparseArray, index);
}

void* SparseArray64_remove(SparseArray64* sparseArray, int64_t key) {
    int index = SparseArray64_indexOfKey(sparseArray, key);
    return index >= 0 ? SparseArray64_removeAt(sparseArray, index) : NULL;
}

void SparseArray64_free(SparseArray64* sparseArray, bool freeValues) {
    SPARSEARRAY_FREE(sparseArray, freeValues);
}

static void ArrayDeque_doubleCapacity(ArrayDeque* arrayDeque) {
    int head = arrayDeque->head;
    int size = arrayDeque->size;
    int diff = size - head;
    int newCapacity = size << 1;
    if (newCapacity < 0) return;

    void** newElements = calloc(newCapacity, sizeof(void*));
    memcpy(newElements, arrayDeque->elements + head, diff * sizeof(void*));
    memcpy(newElements + diff, arrayDeque->elements, head * sizeof(void*));
    free(arrayDeque->elements);

    arrayDeque->elements = newElements;
    arrayDeque->size = newCapacity;
    arrayDeque->head = 0;
    arrayDeque->tail = size;
}

static void ArrayDeque_ensureCapacity(ArrayDeque* arrayDeque) {
    if (!arrayDeque->elements) {
        arrayDeque->head = 0;
        arrayDeque->tail = 0;
        arrayDeque->size = 8;
        arrayDeque->elements = calloc(8, sizeof(void*));
    }
}

bool ArrayDeque_isEmpty(ArrayDeque* arrayDeque) {
    return arrayDeque && arrayDeque->head == arrayDeque->tail;
}

void ArrayDeque_addFirst(ArrayDeque* arrayDeque, void* element) {
    ArrayDeque_ensureCapacity(arrayDeque);
    arrayDeque->head = (arrayDeque->head - 1) & (arrayDeque->size - 1);
    arrayDeque->elements[arrayDeque->head] = element;
    if (arrayDeque->head == arrayDeque->tail) ArrayDeque_doubleCapacity(arrayDeque);
}

void ArrayDeque_addLast(ArrayDeque* arrayDeque, void* element) {
    ArrayDeque_ensureCapacity(arrayDeque);
    arrayDeque->elements[arrayDeque->tail] = element;
    arrayDeque->tail = (arrayDeque->tail + 1) & (arrayDeque->size - 1);
    if (arrayDeque->tail == arrayDeque->head) ArrayDeque_doubleCapacity(arrayDeque);
}

void* ArrayDeque_removeFirst(ArrayDeque* arrayDeque) {
    ArrayDeque_ensureCapacity(arrayDeque);
    int head = arrayDeque->head;
    void* result = arrayDeque->elements[head];
    if (result) {
        arrayDeque->elements[head] = NULL;
        arrayDeque->head = (head + 1) & (arrayDeque->size - 1);
    }
    return result;
}

void* ArrayDeque_removeLast(ArrayDeque* arrayDeque) {
    ArrayDeque_ensureCapacity(arrayDeque);
    int tail = (arrayDeque->tail - 1) & (arrayDeque->size - 1);
    void* result = arrayDeque->elements[tail];
    if (result) {
        arrayDeque->elements[tail] = NULL;
        arrayDeque->tail = tail;
    }
    return result;
}

void* ArrayDeque_getFirst(ArrayDeque* arrayDeque) {
    ArrayDeque_ensureCapacity(arrayDeque);
    return arrayDeque->elements[arrayDeque->head];
}

void* ArrayDeque_getLast(ArrayDeque* arrayDeque) {
    ArrayDeque_ensureCapacity(arrayDeque);
    int tail = (arrayDeque->tail - 1) & (arrayDeque->size - 1);
    return arrayDeque->elements[tail];
}

void ArrayDeque_free(ArrayDeque* arrayDeque, bool freeValues) {
    if (arrayDeque->elements) {
        if (freeValues) {
            for (int i = 0; i < arrayDeque->size; i++) {
                if (arrayDeque->elements[i]) {
                    free(arrayDeque->elements[i]);
                    arrayDeque->elements[i] = NULL;
                }
            }
        }
        free(arrayDeque->elements);
        arrayDeque->elements = NULL;
    }

    arrayDeque->head = 0;
    arrayDeque->tail = 0;
    arrayDeque->size = 0;
}