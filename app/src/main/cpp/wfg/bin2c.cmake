# bin2c.cmake — converts a binary SPIR-V file into a C header containing a uint32_t array.
#
# Required inputs:
#   INPUT_FILE   path to .spv binary
#   OUTPUT_FILE  path to .h to write
#   VAR_NAME     C identifier for the array
#
# Invocation:
#   ${CMAKE_COMMAND} -DINPUT_FILE=... -DOUTPUT_FILE=... -DVAR_NAME=... -P bin2c.cmake

if(NOT INPUT_FILE OR NOT OUTPUT_FILE OR NOT VAR_NAME)
    message(FATAL_ERROR "bin2c.cmake requires INPUT_FILE, OUTPUT_FILE, VAR_NAME")
endif()

file(READ "${INPUT_FILE}" hex_data HEX)
string(LENGTH "${hex_data}" hex_len)
math(EXPR byte_count "${hex_len} / 2")
math(EXPR word_count "${byte_count} / 4")

# SPIR-V is little-endian; chunk the hex stream into 4-byte little-endian words.
set(words "")
set(line_words "")
set(words_per_line 0)
math(EXPR last_byte_offset "${hex_len} - 8")

set(i 0)
while(i LESS hex_len)
    string(SUBSTRING "${hex_data}" ${i} 2 b0)
    math(EXPR i "${i} + 2")
    string(SUBSTRING "${hex_data}" ${i} 2 b1)
    math(EXPR i "${i} + 2")
    string(SUBSTRING "${hex_data}" ${i} 2 b2)
    math(EXPR i "${i} + 2")
    string(SUBSTRING "${hex_data}" ${i} 2 b3)
    math(EXPR i "${i} + 2")
    string(APPEND line_words "0x${b3}${b2}${b1}${b0}, ")
    math(EXPR words_per_line "${words_per_line} + 1")
    if(words_per_line EQUAL 8)
        string(APPEND words "    ${line_words}\n")
        set(line_words "")
        set(words_per_line 0)
    endif()
endwhile()
if(words_per_line GREATER 0)
    string(APPEND words "    ${line_words}\n")
endif()

file(WRITE "${OUTPUT_FILE}"
"// Auto-generated from ${INPUT_FILE}. Do not edit.
#pragma once
#include <stdint.h>
#include <stddef.h>

static const uint32_t ${VAR_NAME}[] = {
${words}};
static const size_t ${VAR_NAME}_size = sizeof(${VAR_NAME});
")
