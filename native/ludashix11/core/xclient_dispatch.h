#pragma once

#include "core/exceptions.h"

#define CHECK_REQUEST_SIZE_OP(expected, op)                                                        \
    do {                                                                                           \
        if (request_size op sizeof(expected)) {                                                    \
            throw BadLengthError();                                                                     \
        }                                                                                          \
    } while (0)

#define CHECK_REQUEST_SIZE_STRICT(expected) CHECK_REQUEST_SIZE_OP(expected, !=)
#define CHECK_REQUEST_SIZE(expected) CHECK_REQUEST_SIZE_OP(expected, <)

#define CHECK_MSG(cond, ...)                                                                       \
    do {                                                                                           \
        if (!(cond)) {                                                                             \
            throw std::runtime_error(fmt::format(__VA_ARGS__));                                    \
        }                                                                                          \
    } while (0)
