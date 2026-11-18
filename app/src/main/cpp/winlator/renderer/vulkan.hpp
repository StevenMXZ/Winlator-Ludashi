#pragma once

#include <renderer_jni.hpp>

struct VulkanTable {
    PFN_vkGetFenceFdKHR GetFenceFdKHR;
};