package com.winlator.core.envvars

import org.intellij.lang.annotations.Identifier

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
            "FEX_SMCCHECKS" to EnvVarInfo(
                identifier = "FEX_SMCCHECKS",
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
                identifier = "WRAPPER_MAX_IMAGE_COUNT",
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
            "VKD3D_SHADER_MODEL" to EnvVarInfo(
                identifier = "VKD3D_SHADER_MODEL",
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
            "MESA_GL_VERSION_OVERRIDE" to EnvVarInfo(
                identifier = "MESA_GL_VERSION_OVERRIDE",
            ),
            "MESA_VK_WSI_DEBUG" to EnvVarInfo(
                identifier = "MESA_VK_WSI_DEBUG",
            ),
            "BOX64_MAX_THREADS" to EnvVarInfo(
                identifier = "BOX64_MAX_THREADS",
            ),
            "VKD3D_FRAME_RATE" to EnvVarInfo(
                identifier = "VKD3D_FRAME_RATE",
            ),
            "VKD3D_THREAD_COUNT" to EnvVarInfo(
                identifier = "VKD3D_THREAD_COUNT",
            ),
            // Shader cache saves in a folder instead of pipeline
            "VKD3D_SHADER_CACHE_PATH" to EnvVarInfo(
                identifier = "VKD3D_SHADER_CACHE_PATH",
            ),
            // 0 to 16 values can be used to trade latency for smooth fps
            "VKD3D_SWAPCHAIN_LATENCY_FRAMES" to EnvVarInfo(
                identifier = "VKD3D_SWAPCHAIN_LATENCY_FRAMES",
            ),
            "VKD3D_CONFIG" to EnvVarInfo(
                identifier = "VKD3D_CONFIG",
                selectionType = EnvVarSelectionType.SUGGESTIONS,
                possibleValues = listOf(
                    "no_upload_hvv,nodxr", //prevents free use of vram as memory, disables Ray Tracing
                    "skip_application_workarounds", //disables x86 fixes that are mostly not in android
                    "no_upload_hvv,nodxr,skip_application_workarounds", //combination of the above two
                ),
            ),
            "DXVK_CONFIG" to EnvVarInfo(
                identifier = "DXVK_CONFIG",
            ),
            // New var for DXVK 2.5.x FPS regressions as per Leegao, ahead of new builds
            "DXVK_DISABLE_TIMELINE_SEMAPHORES" to EnvVarInfo(
                identifier = "DXVK_DISABLE_TIMELINE_SEMAPHORES",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1")
            ),
            "MESA_VK_PRESENT_MODE" to EnvVarInfo(
                identifier = "MESA_VK_PRESENT_MODE",
            ),
            // Wine DLL overrides — user types freely or picks a common preset
            "WINEDLLOVERRIDES" to EnvVarInfo(
                identifier = "WINEDLLOVERRIDES",
                selectionType = EnvVarSelectionType.SUGGESTIONS,
                possibleValues = listOf(
                    // Category header: Audio
                    "---Audio",
                    "openal32=native,builtin",
                    "soft_oal=native",
                    "openal32=native,builtin;soft_oal=native",
                    "xaudio2_7=native,builtin",
                    // Category header: Input
                    "---Input",
                    "dinput8=n,b",
                    "dinput8=n,b;dinput=n,b",
                    "xinput1_3=n,b;xinput1_4=n,b",
                    "xinput9_1_0=n,b;windows.gaming.input=n,b",
                    "xinput1_1=n,b;xinput1_2=n,b",
                    // Category header: Network
                    "---Network",
                    "wininet=n,b",
                ),
            ),
            // "ONE UI" BUG VARIABLE - seen in a lot of Samsung and A8xx
            "FD_DEV_FEATURES" to EnvVarInfo(
                identifier = "FD_DEV_FEATURES",
                selectionType = EnvVarSelectionType.SUGGESTIONS,
                possibleValues = listOf(
                    "enable_tp_ubwc_flag_hint=1",
                ),
            ),
            // Releases unused memory in background threads, per sec, reducing micro-stutters
            "MALLOC_CONF" to EnvVarInfo(
                identifier = "MALLOC_CONF",
                selectionType = EnvVarSelectionType.SUGGESTIONS,
                possibleValues = listOf(
                    "background_thread:true,dirty_decay_ms:1000",
                ),
            ),
            // ---- GameNative wrapper (libvulkan_wrapper.so) -------------------
            // Compatibility controls the wrapper reads at device creation. Every
            // one of them is a no-op unless the base Vulkan driver actually
            // lacks the capability being emulated, so a wrong guess costs
            // nothing on a driver that is already fine.
            //
            // The first three are tri-state: DELETING the row restores the
            // automatic per-driver default, "1" opts a driver in, "0" is the
            // kill switch. The switch position only ever shows the override.

            // DXVK forces hostQueryReset on from D3D_FEATURE_LEVEL_9_1 up, so a
            // driver without it gets no D3D10/D3D11 device at any level. Default
            // on for NVIDIA proprietary; try it on any pre-Vulkan-1.2 driver
            // (Adreno 4xx/5xx, Mali Midgard, PowerVR Rogue, Tegra).
            "WRAPPER_EMULATE_HOST_QUERY_RESET" to EnvVarInfo(
                identifier = "WRAPPER_EMULATE_HOST_QUERY_RESET",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            // Use the driver's own BC1-BC7 instead of transcoding to ASTC.
            // Measured ~11x cheaper per BC1 upload on Tegra. Default on for
            // NVIDIA proprietary; only acts while BCn Emulation is on "auto".
            "WRAPPER_NATIVE_BCN" to EnvVarInfo(
                identifier = "WRAPPER_NATIVE_BCN",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            // Release swapchain images to VK_QUEUE_FAMILY_FOREIGN_EXT before
            // present, so a driver with private render-target layouts does not
            // hand the compositor unresolved contents. Default on for NVIDIA
            // proprietary; the fix for a garbled or stale direct-present image.
            "WRAPPER_AHB_OWNERSHIP_RELEASE" to EnvVarInfo(
                identifier = "WRAPPER_AHB_OWNERSHIP_RELEASE",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),

            // The next two are plain opt-ins, off unless set to "1".

            // Lower Vulkan 1.3 core calls onto a 1.0/1.1 driver. Needed by D8VK
            // once the reported core version is raised: without it D3D8 opens a
            // window and draws nothing.
            "WRAPPER_EMULATE_VULKAN_1_3" to EnvVarInfo(
                identifier = "WRAPPER_EMULATE_VULKAN_1_3",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),
            // Emulate VK_KHR_imageless_framebuffer. Zink needs it; without it an
            // OpenGL title reaches pipeline creation and then exits.
            "WRAPPER_EMULATE_IMAGELESS_FRAMEBUFFER" to EnvVarInfo(
                identifier = "WRAPPER_EMULATE_IMAGELESS_FRAMEBUFFER",
                selectionType = EnvVarSelectionType.TOGGLE,
                possibleValues = listOf("0", "1"),
            ),

            // Reported Vulkan version, globally and then per engine. The pair
            // below is matched with an exact string compare against the engine
            // name in VkApplicationInfo, and BOTH engine variables are required
            // before the override applies. Use it to raise the version for one
            // compatibility layer (D8VK) while pinning another (Zink) back to
            // what the driver can really do.
            "WRAPPER_VK_VERSION" to EnvVarInfo(
                identifier = "WRAPPER_VK_VERSION",
                selectionType = EnvVarSelectionType.SUGGESTIONS,
                possibleValues = listOf("1.1", "1.2", "1.3", "1.3.178"),
            ),
            "WRAPPER_ENGINE_NAME" to EnvVarInfo(
                identifier = "WRAPPER_ENGINE_NAME",
                selectionType = EnvVarSelectionType.SUGGESTIONS,
                // "mesa zink" contains a space on purpose -- type it plainly
                // here, the container escapes it on save.
                possibleValues = listOf("mesa zink", "DXVK", "vkd3d"),
            ),
            "WRAPPER_ENGINE_VK_VERSION" to EnvVarInfo(
                identifier = "WRAPPER_ENGINE_VK_VERSION",
                selectionType = EnvVarSelectionType.SUGGESTIONS,
                possibleValues = listOf("1.1", "1.2", "1.3"),
            ),

            // Diagnostics. Every control point above announces itself in this
            // log, which is the only way to tell "the override did not apply"
            // from "the override applied and did not help". The level is a set
            // of FLAGS, not a threshold -- there is no "debug", and a value that
            // matches nothing leaves the log empty.
            "WRAPPER_LOG_LEVEL" to EnvVarInfo(
                identifier = "WRAPPER_LOG_LEVEL",
                selectionType = EnvVarSelectionType.MULTI_SELECT,
                possibleValues = listOf("info", "error", "shader", "validation", "bcn", "apidump"),
            ),
            "WRAPPER_LOG_FILE" to EnvVarInfo(
                identifier = "WRAPPER_LOG_FILE",
            ),
        )
    }
}
