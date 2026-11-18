// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "lsfg_shaders.hpp"
#include "lsfg_common.hpp"
#include "lsfg_dll.h"

#include <android/log.h>

#define LOG_TAG "LsfgShaders"
#define SHADER_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define SHADER_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lsfg {

LsfgShaders::LsfgShaders(const Device& device_, const std::string& cache_path)
    : device{device_.Handle()} {
    LsfgModuleSet set{};
    const LsfgStatus status = lsfg_load_modules(cache_path.c_str(), &set);
    if (status != LSFG_OK) {
        SHADER_LOGE("Shader cache unusable (status %d)", static_cast<int>(status));
        return;
    }

    for (uint32_t i = 0; i < set.count; i++) {
        const LsfgModule& module = set.modules[i];

        VkShaderModuleCreateInfo module_ci{};
        module_ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        module_ci.codeSize = static_cast<size_t>(module.word_count) * sizeof(uint32_t);
        module_ci.pCode = module.words;

        VkShaderModule handle = VK_NULL_HANDLE;
        if (vkd.CreateShaderModule(device, &module_ci, nullptr, &handle) != VK_SUCCESS) {
            SHADER_LOGE("vkCreateShaderModule failed for shader %u", module.id);
            lsfg_release_modules(&set);
            Release();
            return;
        }
        modules.emplace(module.id, handle);
    }

    const LsfgVariant variant = set.variant;
    lsfg_release_modules(&set);
    valid = modules.size() == LSFG_SHADER_COUNT;
    if (valid) {
        const char* variant_name = variant == LSFG_VARIANT_FP16   ? "fp16"
                                   : variant == LSFG_VARIANT_FP32 ? "fp32"
                                   : variant == LSFG_VARIANT_DXBC ? "dxbc-translated"
                                                                  : "unknown";
        SHADER_LOGI("Created %zu LSFG shader modules, variant=%s", modules.size(),
                    variant_name);
    } else {
        SHADER_LOGE("Expected %u shader modules, got %zu", LSFG_SHADER_COUNT, modules.size());
        Release();
    }
}

LsfgShaders::~LsfgShaders() {
    Release();
}

void LsfgShaders::Release() {
    if (device != VK_NULL_HANDLE) {
        for (auto& [id, module] : modules) {
            vkd.DestroyShaderModule(device, module, nullptr);
        }
    }
    modules.clear();
    valid = false;
}

VkShaderModule LsfgShaders::Get(uint32_t shader_id) const {
    const auto it = modules.find(shader_id);
    return it == modules.end() ? VK_NULL_HANDLE : it->second;
}

}