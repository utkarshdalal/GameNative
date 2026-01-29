package com.winlator.core.envvars

data class EnvVarInfo(
    val identifier: String,
    val selectionType: EnvVarSelectionType = EnvVarSelectionType.NONE,
    val possibleValues: List<String> = emptyList(),
) {
    companion object {
        val KNOWN_BOX64_VARS = mapOf(
            "BOX64_DYNAREC_SAFEFLAGS" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_SAFEFLAGS",
                possibleValues = listOf("0", "1", "2"),
            ),
            "BOX64_DYNAREC_FASTNAN" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_FASTNAN",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_DYNAREC_FASTROUND" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_FASTROUND",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_DYNAREC_X87DOUBLE" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_X87DOUBLE",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_DYNAREC_BIGBLOCK" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_BIGBLOCK",
                possibleValues = listOf("0", "1", "2", "3"),
            ),
            "BOX64_DYNAREC_STRONGMEM" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_STRONGMEM",
                possibleValues = listOf("0", "1", "2", "3"),
            ),
            "BOX64_DYNAREC_FORWARD" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_FORWARD",
                possibleValues = listOf("0", "128", "256", "512", "1024"),
            ),
            "BOX64_DYNAREC_CALLRET" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_CALLRET",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_DYNAREC_WAIT" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_WAIT",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_AVX" to EnvVarInfo(
                identifier = "BOX64_AVX",
                possibleValues = listOf("0", "1", "2"),
            ),
            "BOX64_MAXCPU" to EnvVarInfo(
                identifier = "BOX64_MAXCPU",
                possibleValues = listOf("4", "8", "16", "32", "64"),
            ),
            "BOX64_UNITYPLAYER" to EnvVarInfo(
                identifier = "BOX64_UNITYPLAYER",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_DYNAREC_WEAKBARRIER" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_WEAKBARRIER",
                possibleValues = listOf("0", "1", "2"),
            ),
            "BOX64_DYNAREC_ALIGNED_ATOMICS" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_ALIGNED_ATOMICS",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_DYNAREC_DF" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_DF",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_DYNAREC_DIRTY" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_DIRTY",
                possibleValues = listOf("0", "1", "2"),
            ),
            "BOX64_DYNAREC_NATIVEFLAGS" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_NATIVEFLAGS",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "BOX64_DYNAREC_PAUSE" to EnvVarInfo(
                identifier = "BOX64_DYNAREC_PAUSE",
                possibleValues = listOf("0", "1", "2", "3"),
            ),
            "BOX64_MMAP32" to EnvVarInfo(
                identifier = "BOX64_MMAP32",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
        )
        val KNOWN_FEXCORE_VARS = mapOf(
            "FEX_TSOENABLED" to EnvVarInfo(
                identifier = "FEX_TSOENABLED",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_VECTORTSOENABLED" to EnvVarInfo(
                identifier = "FEX_VECTORTSOENABLED",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_HALFBARRIERTSOENABLED" to EnvVarInfo(
                identifier = "FEX_HALFBARRIERTSOENABLED",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_MEMCPYSETTSOENABLED" to EnvVarInfo(
                identifier = "FEX_MEMCPYSETTSOENABLED",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_X87REDUCEDPRECISION" to EnvVarInfo(
                identifier = "FEX_X87REDUCEDPRECISION",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_MULTIBLOCK" to EnvVarInfo(
                identifier = "FEX_MULTIBLOCK",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_MAXINST" to EnvVarInfo(
                identifier = "FEX_MAXINST",
            ),
            "FEX_HOSTFEATURES" to EnvVarInfo(
                identifier = "FEX_HOSTFEATURES",
                possibleValues = listOf("enablesve", "disablesve", "enableavx", "disableavx", "off"),
            ),
            "FEX_SMALLTSCSCALE" to EnvVarInfo(
                identifier = "FEX_SMALLTSCSCALE",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_SMC_CHECKS" to EnvVarInfo(
                identifier = "FEX_SMC_CHECKS",
                possibleValues = listOf("none", "mtrack", "full"),
            ),
            "FEX_VOLATILEMETADATA" to EnvVarInfo(
                identifier = "FEX_VOLATILEMETADATA",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_MONOHACKS" to EnvVarInfo(
                identifier = "FEX_MONOHACKS",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_HIDEHYPERVISORBIT" to EnvVarInfo(
                identifier = "FEX_HIDEHYPERVISORBIT",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_DISABLEL2CACHE" to EnvVarInfo(
                identifier = "FEX_DISABLEL2CACHE",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "FEX_DYNAMICL1CACHE" to EnvVarInfo(
                identifier = "FEX_DYNAMICL1CACHE",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
        )
        val KNOWN_ENV_VARS = mapOf(
            "ZINK_DESCRIPTORS" to EnvVarInfo(
                identifier = "ZINK_DESCRIPTORS",
                possibleValues = listOf("auto", "lazy", "cached", "notemplates"),
            ),
            "ZINK_DEBUG" to EnvVarInfo(
                identifier = "ZINK_DEBUG",
                selectionType = EnvVarSelectionType.MULTI_SELECT,
                possibleValues = listOf("nir", "spirv", "tgsi", "validation", "sync", "compact", "noreorder"),
            ),
            "MESA_SHADER_CACHE_DISABLE" to EnvVarInfo(
                identifier = "MESA_SHADER_CACHE_DISABLE",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("false", "true"),
            ),
            "mesa_glthread" to EnvVarInfo(
                identifier = "mesa_glthread",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("false", "true"),
            ),
            "WINEESYNC" to EnvVarInfo(
                identifier = "WINEESYNC",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "TU_DEBUG" to EnvVarInfo(
                identifier = "TU_DEBUG",
                selectionType = EnvVarSelectionType.MULTI_SELECT,
                possibleValues = listOf(
                    "startup", "nir", "nobin", "sysmem", "gmem", "forcebin", "layout", "noubwc", "nomultipos",
                    "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw", "push_consts_per_stage", "rast_order",
                    "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "noconform", "rd", "deck_emu"
                ),
            ),
            "DXVK_HUD" to EnvVarInfo(
                identifier = "DXVK_HUD",
                selectionType = EnvVarSelectionType.MULTI_SELECT,
                possibleValues = listOf(
                    "async", "devinfo", "fps", "frametimes", "submissions", "drawcalls", "pipelines", "descriptors",
                    "memory", "gpuload", "version", "api", "cs", "compiler", "samplers",
                ),
            ),
            "MESA_EXTENSION_MAX_YEAR" to EnvVarInfo(
                identifier = "MESA_EXTENSION_MAX_YEAR",
            ),
            "WRAPPER_MAX_IMAGE_COUNT" to EnvVarInfo(
                identifier = "MESA_EXTENSION_MAX_YEAR",
            ),
            "MESA_GL_VERSION_OVERRIDE" to EnvVarInfo(
                identifier = "MESA_EXTENSION_MAX_YEAR",
            ),
            "PULSE_LATENCY_MSEC" to EnvVarInfo(
                identifier = "PULSE_LATENCY_MSEC",
            ),
            "MESA_VK_WSI_PRESENT_MODE" to EnvVarInfo(
                identifier = "MESA_VK_WSI_PRESENT_MODE",
                possibleValues = listOf("immediate", "mailbox", "fifo", "relaxed"),
            ),
            "DXVK_FRAME_RATE" to EnvVarInfo(
                identifier = "DXVK_FRAME_RATE",
            ),
            "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER" to EnvVarInfo(
                identifier = "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "WINE_NEW_MEDIASOURCE" to EnvVarInfo(
                identifier = "WINE_NEW_MEDIASOURCE",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            "GALLIUM_HUD" to EnvVarInfo(
                identifier = "GALLIUM_HUD",
                selectionType = EnvVarSelectionType.MULTI_SELECT,
                possibleValues = listOf("simple", "fps", "frametime"),
            ),
        )
    }
}
