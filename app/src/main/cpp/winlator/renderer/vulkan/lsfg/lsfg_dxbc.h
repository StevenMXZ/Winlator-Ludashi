#pragma once
#include <stdbool.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
bool lsfg_translate_dxbc(const uint8_t* data, uint32_t size, uint32_t** words, uint32_t* count);
#ifdef __cplusplus
}
#endif
