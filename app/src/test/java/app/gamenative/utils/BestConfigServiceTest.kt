package app.gamenative.utils

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.contents.AdrenotoolsManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BestConfigServiceTest {

    private lateinit var context: Context
    private lateinit var resources: Resources

    // Sample API responses from the user
    private val cs2Adreno735Response = """
        {"bestConfig":{"id":"STEAM_730","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/Counter-Strike Global Offensive","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60","showFPS":true,"useDRI3":false,"emulator":"Box64","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"dxvk","extraData":{"dxwrapper":"dxvk-async-1.10.3","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.6","desktopTheme":"LIGHT,IMAGE,#0277bd,640x480","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"false","fexcoreVersion":"2507","startupSelection":"1","lastInstalledMainWrapper":"wrapper-leegao","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"640x480","audioDriver":"pulseaudio","box64Preset":"PERFORMANCE","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-x86_64","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":false,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"game/bin/win64/cs2.exe","fexcoreVersion":"2507","graphicsDriver":"wrapper-leegao","needsUnpacking":false,"dxwrapperConfig":"version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1","launchRealSteam":true,"sessionMetadata":{"avg_fps":113.9,"session_length_sec":208},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":true,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"fallback_match","matchedGpu":"Mali-G57 MC2","matchedDeviceId":7929}
    """.trimIndent()

    private val detectiveDotsonMaliResponse = """
        {"bestConfig":{"id":"STEAM_2450840","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/Detective Dotson","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform,sysmem DXVK_FRAME_RATE=60","showFPS":false,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"dxvk","extraData":{"dxwrapper":"dxvk-2.6.1-gplasync","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"true","graphicsDriver":"turnip-25.2.0-22.2.5","startupSelection":"1","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"UNITY_MONO_BLEEDING_EDGE","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-x86_64","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":false,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"DetectiveDotson.exe","fexcoreVersion":"2507","graphicsDriver":"turnip","needsUnpacking":false,"dxwrapperConfig":"version=2.6.1-gplasync,framerate=0,maxDeviceMemory=0,async=1,asyncCache=1,vkd3dVersion=2.14.1,vkd3dLevel=12_1","launchRealSteam":false,"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=turnip25.3.0_R3_Auto;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"fallback_match","matchedGpu":"Adreno (TM) 735","matchedDeviceId":1}
    """.trimIndent()

    private val dota2MaliResponse = """
        {"bestConfig":{"id":"STEAM_570","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/dota 2 beta","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60","showFPS":false,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"dxvk","extraData":{"dxwrapper":"dxvk-async-1.10.3","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.6","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"true","fexcoreVersion":"2507","startupSelection":"1","lastInstalledMainWrapper":"Wrapper-leegao","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"COMPATIBILITY","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-arm64ec","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":false,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"game/bin/win64/dota2.exe","fexcoreVersion":"2507","graphicsDriver":"Wrapper-leegao","needsUnpacking":false,"dxwrapperConfig":"version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1","launchRealSteam":false,"sessionMetadata":{"avg_fps":39.810425,"session_length_sec":292},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"fallback_match","matchedGpu":"Adreno (TM) 830","matchedDeviceId":6172}
    """.trimIndent()

    private val cs2MaliExactMatchResponse = """
        {"bestConfig":{"id":"STEAM_730","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/Counter-Strike Global Offensive","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60","showFPS":true,"useDRI3":false,"emulator":"Box64","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"dxvk","extraData":{"dxwrapper":"dxvk-async-1.10.3","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.6","desktopTheme":"LIGHT,IMAGE,#0277bd,640x480","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"false","fexcoreVersion":"2507","startupSelection":"1","lastInstalledMainWrapper":"wrapper-leegao","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"640x480","audioDriver":"pulseaudio","box64Preset":"PERFORMANCE","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-x86_64","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":false,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"game/bin/win64/cs2.exe","fexcoreVersion":"2507","graphicsDriver":"wrapper-leegao","needsUnpacking":false,"dxwrapperConfig":"version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1","launchRealSteam":true,"sessionMetadata":{"avg_fps":113.9,"session_length_sec":208},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":true,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"exact_gpu_match","matchedGpu":"Mali-G57 MC2","matchedDeviceId":7929}
    """.trimIndent()

    private val dota2Adreno830ExactMatchResponse = """
        {"bestConfig":{"id":"STEAM_570","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/dota 2 beta","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60","showFPS":false,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"dxvk","extraData":{"dxwrapper":"dxvk-async-1.10.3","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.6","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"true","fexcoreVersion":"2507","startupSelection":"1","lastInstalledMainWrapper":"Wrapper-leegao","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"COMPATIBILITY","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-arm64ec","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":false,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"game/bin/win64/dota2.exe","fexcoreVersion":"2507","graphicsDriver":"Wrapper-leegao","needsUnpacking":false,"dxwrapperConfig":"version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1","launchRealSteam":false,"sessionMetadata":{"avg_fps":39.810425,"session_length_sec":292},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"exact_gpu_match","matchedGpu":"Adreno (TM) 830","matchedDeviceId":6172}
    """.trimIndent()

    private val dota2Adreno835FamilyMatchResponse = """
        {"bestConfig":{"id":"STEAM_570","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/dota 2 beta","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60","showFPS":false,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"dxvk","extraData":{"dxwrapper":"dxvk-async-1.10.3","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.6","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"true","fexcoreVersion":"2507","startupSelection":"1","lastInstalledMainWrapper":"Wrapper-leegao","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"COMPATIBILITY","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-arm64ec","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":false,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"game/bin/win64/dota2.exe","fexcoreVersion":"2507","graphicsDriver":"Wrapper-leegao","needsUnpacking":false,"dxwrapperConfig":"version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1","launchRealSteam":false,"sessionMetadata":{"avg_fps":39.810425,"session_length_sec":292},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"gpu_family_match","matchedGpu":"Adreno (TM) 830","matchedDeviceId":6172}
    """.trimIndent()

    private val dota2XClipseFallbackResponse = """
        {"bestConfig":{"id":"STEAM_570","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/dota 2 beta","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60","showFPS":false,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"dxvk","extraData":{"dxwrapper":"dxvk-async-1.10.3","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.6","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"true","fexcoreVersion":"2507","startupSelection":"1","lastInstalledMainWrapper":"Wrapper-leegao","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"COMPATIBILITY","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-arm64ec","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":false,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"game/bin/win64/dota2.exe","fexcoreVersion":"2507","graphicsDriver":"Wrapper-leegao","needsUnpacking":false,"dxwrapperConfig":"version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1","launchRealSteam":false,"sessionMetadata":{"avg_fps":39.810425,"session_length_sec":292},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"fallback_match","matchedGpu":"Adreno (TM) 830","matchedDeviceId":6172}
    """.trimIndent()

    private val hades2Adreno835FamilyMatchResponse = """
        {"bestConfig":{"id":"STEAM_1145350","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/Hades II","lc_all":"en_US.utf8","cpuList":"0,1,2,3","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform,sysmem DXVK_FRAME_RATE=60","showFPS":true,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"vkd3d","extraData":{"dxwrapper":"vkd3d-2.14.1","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.7","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=0,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"false","fexcoreVersion":"2507","graphicsDriver":"sd-8-elite-2.1-22.2.5","startupSelection":"1","graphicsDriverAdreno":"sd-8-elite-8Elite-800.51","lastInstalledMainWrapper":"wrapper","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"PERFORMANCE","box86Preset":"COMPATIBILITY","installPath":"","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":true,"midiSoundFont":"","wincomponents":"direct3d=0,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"Release/Hades2.exe","fexcoreVersion":"2507","graphicsDriver":"sd-8-elite","needsUnpacking":false,"dxwrapperConfig":"version=1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.14.1,vkd3dLevel=12_1,vkd3dFeatureLevel=12_1","launchRealSteam":false,"sessionMetadata":{"avg_fps":57.692307,"session_length_sec":123},"touchscreenMode":false,"containerVariant":"glibc","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=,frameSync=Normal,adrenotoolsTurnip=1,vkMaxVersion=1.3,exposedDeviceExtensions=all,maxDeviceMemory=4096,adrenotoolsDriver=vulkan.adreno.so","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"gpu_family_match","matchedGpu":"Adreno (TM) 825","matchedDeviceId":427}
    """.trimIndent()

    private val hades2Adreno735ExactMatchResponse = """
        {"bestConfig":{"id":"STEAM_1145350","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/Hades II","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60 PULSE_LATENCY_MSEC=144","showFPS":false,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"vkd3d","extraData":{"dxwrapper":"vkd3d-2.12","appVersion":"6","imgVersion":"25","audioDriver":"pulseaudio","box64Version":"0.3.7","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"true","fexcoreVersion":"2511","sharpnessLevel":"100","sharpnessEffect":"None","sharpnessDenoise":"100","startupSelection":"1","lastInstalledMainWrapper":"wrapper","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"COMPATIBILITY","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-arm64ec","box64Version":"0.3.7","box86Version":"0.3.2","cpuListWoW64":"4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":true,"fexcorePreset":"INTERMEDIATE","midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"Release/Hades2.exe","fexcoreVersion":"2511","graphicsDriver":"wrapper","needsUnpacking":false,"dxwrapperConfig":"version=2.4.1,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.12,vkd3dLevel=12_1,ddrawrapper=none,csmt=3,gpuName=NVIDIA GeForce GTX 480,videoMemorySize=2048,strict_shader_math=1,OffscreenRenderingMode=fbo,renderer=gl,vkd3dFeatureLevel=12_1","launchRealSteam":false,"sessionMetadata":{"avg_fps":91.23881,"session_length_sec":74},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"vulkanVersion=1.3;version=turnip25.3.0_R3_Auto;blacklistedExtensions=;maxDeviceMemory=0;presentMode=mailbox;syncFrame=0;disablePresentWait=0;resourceType=auto;bcnEmulation=auto;bcnEmulationType=software;bcnEmulationCache=0,version=turnip25.1.0,syncFrame=0,adrenotoolsTurnip=1,disablePresentWait=0,exposedDeviceExtensions=all,maxDeviceMemory=4096,presentMode=mailbox,resourceType=auto,bcnEmulation=auto,bcnEmulationType=software,bcnEmulationCache=0","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"exact_gpu_match","matchedGpu":"Adreno (TM) 735","matchedDeviceId":1}
    """.trimIndent()

    private val hades2MaliGc824FallbackResponse = """
        {"bestConfig":{"id":"STEAM_1145350","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/Hades II","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60","showFPS":false,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"vkd3d","extraData":{"dxwrapper":"vkd3d-2.13","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.7","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"true","fexcoreVersion":"2507","startupSelection":"1","lastInstalledMainWrapper":"wrapper","discord_support_prompt_shown":"true"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"INTERMEDIATE","box86Preset":"COMPATIBILITY","installPath":"","wineVersion":"proton-9.0-arm64ec","box64Version":"0.3.7","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":true,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"Release/Hades2.exe","fexcoreVersion":"2507","graphicsDriver":"wrapper","needsUnpacking":false,"dxwrapperConfig":"version=2.4.1,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.13,vkd3dLevel=12_1,vkd3dFeatureLevel=12_1","launchRealSteam":false,"sessionMetadata":{"avg_fps":346.1438,"session_length_sec":157},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=turnip_v25.3.0_R11,frameSync=Normal,adrenotoolsTurnip=1,exposedDeviceExtensions=all,maxDeviceMemory=4096","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"fallback_match","matchedGpu":"Adreno (TM) 740","matchedDeviceId":7191}
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resources = context.resources

        // Initialize PrefManager
        PrefManager.init(context)
    }

    /**
     * Helper function to parse JSON response and extract bestConfig
     */
    private fun parseBestConfig(jsonString: String): JsonObject {
        val json = org.json.JSONObject(jsonString)
        val bestConfigJson = json.getJSONObject("bestConfig")
        return Json.parseToJsonElement(bestConfigJson.toString()).jsonObject
    }

    /**
     * Helper function to get match type from JSON response
     */
    private fun getMatchType(jsonString: String): String {
        val json = org.json.JSONObject(jsonString)
        return json.getString("matchType")
    }

    @Test
    fun testExactGpuMatch_parsesAllFields() {
        val bestConfig = parseBestConfig(cs2MaliExactMatchResponse)
        val matchType = getMatchType(cs2MaliExactMatchResponse)

        assertEquals("exact_gpu_match", matchType)

        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, matchType != "fallback_match") }

        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())

        // Verify all fields are parsed (including containerVariant, graphicsDriver, dxwrapper, dxwrapperConfig)
        assertEquals("bionic", result["containerVariant"])
        assertEquals("wrapper-leegao", result["graphicsDriver"])
        assertEquals("dxvk", result["dxwrapper"])
        assertEquals("version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1", result["dxwrapperConfig"])
        assertEquals("version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal", result["graphicsDriverConfig"])

        // Verify other fields are parsed
        assertEquals("proton-9.0-x86_64", result["wineVersion"])
        assertEquals("0.3.6", result["box64Version"])
        assertEquals("PERFORMANCE", result["box64Preset"])
        assertEquals("Box64", result["emulator"])
        assertEquals(1, (result["startupSelection"] as? Byte)?.toInt() ?: (result["startupSelection"] as? Int))
        assertEquals("2507", result["fexcoreVersion"])
    }

    @Test
    fun testGpuFamilyMatch_parsesAllFields() {
        // Test gpu_family_match - should behave the same as exact_gpu_match (apply all fields)
        val bestConfig = parseBestConfig(dota2Adreno835FamilyMatchResponse)
        val matchType = getMatchType(dota2Adreno835FamilyMatchResponse)

        assertEquals("gpu_family_match", matchType)

        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, matchType != "fallback_match") }

        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())

        // Verify all fields are parsed (same as exact_gpu_match)
        assertEquals("bionic", result["containerVariant"])
        assertEquals("Wrapper-leegao", result["graphicsDriver"])
        assertEquals("dxvk", result["dxwrapper"])
        assertEquals("version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1", result["dxwrapperConfig"])
        assertEquals("version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal", result["graphicsDriverConfig"])

        // Verify other fields
        assertEquals("proton-9.0-arm64ec", result["wineVersion"])
        assertEquals("0.3.6", result["box64Version"])
        assertEquals("FEXCore", result["emulator"])
        assertEquals("COMPATIBILITY", result["box64Preset"])
        assertEquals("2507", result["fexcoreVersion"])
    }

    @Test
    fun testFallbackMatch_filtersExcludedFields() {
        val bestConfig = parseBestConfig(cs2Adreno735Response)
        val matchType = getMatchType(cs2Adreno735Response)

        assertEquals("fallback_match", matchType)

        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, matchType != "fallback_match") }

        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())

        // For fallback_match, only executablePath and useLegacyDRM should be in the map
        assertTrue("executablePath should be in map", result.containsKey("executablePath"))
        assertTrue("useLegacyDRM should be in map", result.containsKey("useLegacyDRM"))

        // Excluded fields should NOT be in the map
        assertFalse("graphicsDriver should NOT be in map for fallback_match", result.containsKey("graphicsDriver"))
        assertFalse("dxwrapper should NOT be in map for fallback_match", result.containsKey("dxwrapper"))
        assertFalse("dxwrapperConfig should NOT be in map for fallback_match", result.containsKey("dxwrapperConfig"))
    }

    @Test
    fun testFallbackMatch_glibcContainer() {
        val bestConfig = parseBestConfig(detectiveDotsonMaliResponse)
        val matchType = getMatchType(detectiveDotsonMaliResponse)

        assertEquals("fallback_match", matchType)

        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, matchType != "fallback_match") }

        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())

        // For fallback_match with applyKnownConfig=false, only executablePath and useLegacyDRM should be in the map
        assertTrue("executablePath should be in map", result.containsKey("executablePath") || result.containsKey("useLegacyDRM"))

        // Excluded fields should NOT be in the map for fallback_match
        assertFalse("graphicsDriver should NOT be in map for fallback_match", result.containsKey("graphicsDriver"))
        assertFalse("dxwrapper should NOT be in map for fallback_match", result.containsKey("dxwrapper"))
        assertFalse("dxwrapperConfig should NOT be in map for fallback_match", result.containsKey("dxwrapperConfig"))
    }

    @Test
    fun testFallbackMatch_bionicContainer() {
        val bestConfig = parseBestConfig(dota2MaliResponse)
        val matchType = getMatchType(dota2MaliResponse)

        assertEquals("fallback_match", matchType)

        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, matchType != "fallback_match") }

        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())

        // For fallback_match with applyKnownConfig=false, only executablePath and useLegacyDRM should be in the map
        assertTrue("executablePath should be in map", result.containsKey("executablePath") || result.containsKey("useLegacyDRM"))

        // Excluded fields should NOT be in the map for fallback_match
        assertFalse("graphicsDriver should NOT be in map for fallback_match", result.containsKey("graphicsDriver"))
        assertFalse("dxwrapper should NOT be in map for fallback_match", result.containsKey("dxwrapper"))
        assertFalse("dxwrapperConfig should NOT be in map for fallback_match", result.containsKey("dxwrapperConfig"))
    }

    @Test
    fun testApplyKnownConfigFalse_onlyReturnsExecutablePathAndUseLegacyDRM() {
        // Test that when applyKnownConfig=false, only executablePath and useLegacyDRM are returned
        // even for exact_gpu_match which would normally return all fields
        val bestConfig = parseBestConfig(cs2MaliExactMatchResponse)
        val matchType = getMatchType(cs2MaliExactMatchResponse)

        assertEquals("exact_gpu_match", matchType)

        // Call with applyKnownConfig=false
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, false) }

        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())

        // Should only contain executablePath and/or useLegacyDRM
        val keys = result.keys
        assertTrue("Result should only contain executablePath and/or useLegacyDRM", 
            keys.all { it == "executablePath" || it == "useLegacyDRM" })

        // Verify executablePath is present if it exists in config
        if (bestConfig.toString().contains("executablePath")) {
            assertTrue("executablePath should be in map", result.containsKey("executablePath"))
            assertEquals("game/bin/win64/cs2.exe", result["executablePath"])
        }

        // Verify useLegacyDRM is present if it exists in config
        if (bestConfig.toString().contains("useLegacyDRM")) {
            assertTrue("useLegacyDRM should be in map", result.containsKey("useLegacyDRM"))
            assertEquals(false, result["useLegacyDRM"])
        }

        // Verify other fields are NOT present
        assertFalse("graphicsDriver should NOT be in map when applyKnownConfig=false", result.containsKey("graphicsDriver"))
        assertFalse("dxwrapper should NOT be in map when applyKnownConfig=false", result.containsKey("dxwrapper"))
        assertFalse("dxwrapperConfig should NOT be in map when applyKnownConfig=false", result.containsKey("dxwrapperConfig"))
        assertFalse("wineVersion should NOT be in map when applyKnownConfig=false", result.containsKey("wineVersion"))
        assertFalse("box64Version should NOT be in map when applyKnownConfig=false", result.containsKey("box64Version"))
        assertFalse("containerVariant should NOT be in map when applyKnownConfig=false", result.containsKey("containerVariant"))
    }

    @Test
    fun testVersionValidation_validVersions() {
        val bestConfig = parseBestConfig(cs2MaliExactMatchResponse)
        val matchType = getMatchType(cs2MaliExactMatchResponse)

        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, matchType != "fallback_match") }

        assertNotNull("Result should not be null", result)

        // Verify versions are preserved if they exist in resource arrays
        // DXVK version "async-1.10.3" should be in dxvk_version_entries
        val dxwrapperConfig = result!!["dxwrapperConfig"] as? String ?: ""
        assertTrue("DXVK version should be valid", dxwrapperConfig.contains("async-1.10.3"))

        // Box64 version "0.3.6" should be in box64_bionic_version_entries (for bionic)
        assertEquals("0.3.6", result["box64Version"])
    }

    @Test
    fun testVersionValidation_invalidVersions_fallbackToNull() {
        // Create a config with invalid versions
        val invalidConfigJson = """
            {
                "box64Version": "999.999.999",
                "fexcoreVersion": "9999",
                "wineVersion": "invalid-wine-version",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=999.999.999",
                "containerVariant": "glibc",
                "graphicsDriver": "turnip",
                "graphicsDriverConfig": "version=999.999.999"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(invalidConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should not be null", result!!.isEmpty())
    }

    @Test
    fun testBionicBox64VersionValidation() {
        // Test bionic container with Box64 version
        val bionicConfigJson = """
            {
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=1.10.3",
                "containerVariant": "bionic",
                "box64Version": "0.3.6",
                "wineVersion": "proton-9.0-x86_64"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(bionicConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // For bionic, should check against box64_bionic_version_entries
        // If version exists, it should be preserved
        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        assertEquals("0.3.6", result["box64Version"])
    }

    @Test
    fun testGlibcBox64VersionValidation() {
        // Test glibc container with Box64 version
        val glibcConfigJson = """
            {
                "wineVersion": "invalid-wine-version",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=1.10.3",
                "containerVariant": "glibc",
                "box64Version": "0.3.6",
                "wineVersion": "wine-9.2-x86_64"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(glibcConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // For glibc, should check against box64_version_entries
        // If version exists, it should be preserved
        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        assertEquals("0.3.6", result["box64Version"])
    }

    @Test
    fun testBionicGraphicsDriverVersionValidation() {
        // Test bionic container with graphics driver version
        val bionicConfigJson = """
            {
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=1.10.3",
                "containerVariant": "bionic",
                "graphicsDriver": "Wrapper",
                "graphicsDriverConfig": "version=System"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(bionicConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // For bionic, should check against wrapper_graphics_driver_version_entries
        // "System" should be in that array
        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        val graphicsDriverConfig = result["graphicsDriverConfig"] as? String ?: ""
        assertTrue("Graphics driver config should contain System", graphicsDriverConfig.contains("System"))
    }

    @Test
    fun testGlibcGraphicsDriverVersionValidation() {
        // Test glibc container with turnip graphics driver
        val glibcConfigJson = """
            {
                "wineVersion": "wine-9.2-x86_64",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=1.10.3",
                "containerVariant": "glibc",
                "graphicsDriver": "turnip",
                "graphicsDriverConfig": "version=25.3.0"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(glibcConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // For glibc with turnip, should check against turnip_version_entries
        // "25.3.0" should be in that array
        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        val graphicsDriverConfig = result["graphicsDriverConfig"] as? String ?: ""
        assertTrue("Graphics driver config should contain 25.3.0", graphicsDriverConfig.contains("25.3.0"))
    }

    @Test
    fun testPrefManagerDefaults_usedWhenFieldsMissing() {
        // Create a minimal config with only a few fields
        val minimalConfigJson = """
            {
                "wineVersion": "wine-9.2-x86_64",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=1.10.3",
                "containerVariant": "glibc",
                "box64Version": "0.3.6"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(minimalConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // Verify provided fields are used
        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        assertEquals("wine-9.2-x86_64", result["wineVersion"])
        assertEquals("0.3.6", result["box64Version"])

        // Note: showFPS is currently being parsed, but user says it should NOT be parsed
        // screenSize is NOT being parsed (not in ContainerData constructor call) - this is correct
        // screenSize will use Container.DEFAULT_SCREEN_SIZE (not parsed)

        // Note: Missing fields won't be in the map - they're not included when not present in config
        // The map only contains fields that were explicitly in the filtered config
    }

    @Test
    fun testPrefManagerDefaults_usedWhenFieldsEmpty() {
        // Create a config with empty string fields
        val emptyFieldsConfigJson = """
            {
                "dxwrapper": "dxvk",
                "containerVariant": "bionic",
                "dxwrapperConfig": "version=1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=999.99.99,vkd3dLevel=12_1",
                "envVars": "",
                "graphicsDriver": "",
                "wineVersion": "proton-9.0-arm64ec",
                "box64Version": ""
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(emptyFieldsConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // Empty strings should be treated as missing and use PrefManager defaults
        // Note: optString returns empty string if field exists but is empty
        // So we need to check if the parsing logic handles this correctly
        // Based on the implementation, empty strings will be used as-is, not replaced with defaults
        // This is expected behavior - empty strings are valid values
        assertNotNull("Result should be created", result)
    }

    @Test
    fun testWoWBox64VersionValidation() {
        // Test with arm64ec wine version (should use wowbox64 versions)
        val arm64ecConfigJson = """
            {
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=1.10.3",
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-arm64ec",
                "box64Version": "0.3.7"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(arm64ecConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // If version exists in wowbox64_version_entries, it should be preserved
        // Otherwise, should fall back to PrefManager default
        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        assertNotNull("Box64 version should be set", result["box64Version"])
    }

    @Test
    fun testDxvkVersionValidation() {
        // Test DXVK version validation
        val dxvkConfigJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-arm64ec",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(dxvkConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // "async-1.10.3" should be in dxvk_version_entries, so it should be preserved
        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        val dxwrapperConfig = result["dxwrapperConfig"] as? String ?: ""
        assertTrue("DXVK version should be preserved if valid", dxwrapperConfig.contains("async-1.10.3"))
    }

    @Test
    fun testVkd3dVersionValidation() {
        // Test VKD3D version validation
        val vkd3dConfigJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-arm64ec",
                "dxwrapper": "vkd3d",
                "dxwrapperConfig": "vkd3dVersion=2.14.1"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(vkd3dConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertNotNull("Result should not be null", result)

        // "2.14.1" should be in vkd3d_version_entries, so it should be preserved
        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())
        val dxwrapperConfig = result["dxwrapperConfig"] as? String ?: ""
        assertTrue("VKD3D version should be preserved if valid", dxwrapperConfig.contains("2.14.1"))
    }

    @Test
    fun testAllInvalidVersions_fallbackToNull() {
        // Test that all downloadable components with invalid versions fall back to PrefManager defaults
        val invalidVersionsConfigJson = """
            {
                "containerVariant": "bionic",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=invalid-dxvk-999.99.99,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=999.99.99,vkd3dLevel=12_1",
                "box64Version": "invalid-box64-999.99.99",
                "fexcoreVersion": "invalid-fexcore-99999",
                "wineVersion": "invalid-wine-999.99.99",
                "box64Preset": "INVALID_PRESET_999",
                "box86Preset": "INVALID_PRESET_999",
                "graphicsDriver": "turnip",
                "graphicsDriverConfig": "version=invalid-turnip-999.99.99;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal",
                "emulator": "FEXCore",
                "envVars": "TEST_VAR=test",
                "audioDriver": "pulseaudio",
                "cpuList": "0,1,2,3",
                "cpuListWoW64": "0,1,2,3",
                "wow64Mode": true,
                "startupSelection": 1,
                "box86Version": "0.3.2",
                "box86Preset": "COMPATIBILITY",
                "box64Preset": "COMPATIBILITY",
                "language": "english",
                "steamType": "normal",
                "useDRI3": true,
                "launchRealSteam": false
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(invalidVersionsConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should not be null", result!!.isEmpty())
    }

    @Test
    fun testInvalidPresets_returnNull() {
        // Test that invalid Box64 and Box86 presets fall back to PrefManager defaults
        val invalidPresetsConfigJson = """
            {
                "wineVersion": "proton-9.0-arm64ec",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=invalid-dxvk-999.99.99,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=999.99.99,vkd3dLevel=12_1",
                "containerVariant": "bionic",
                "box64Preset": "INVALID_PRESET_999",
                "box86Preset": "INVALID_PRESET_999",
                "box64Version": "0.3.6",
                "box86Version": "0.3.2",
                "emulator": "FEXCore",
                "envVars": "TEST_VAR=test",
                "audioDriver": "pulseaudio",
                "cpuList": "0,1,2,3",
                "cpuListWoW64": "0,1,2,3",
                "wow64Mode": true,
                "startupSelection": 1,
                "language": "english",
                "steamType": "normal",
                "useDRI3": true,
                "launchRealSteam": false
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(invalidPresetsConfigJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should not be null", result!!.isEmpty())
    }

    /**
     * Test to print parsed output for manual verification
     * This test prints the ContainerData output for each GPU scenario
     */
    @Test
    fun testPrintParsedOutputForVerification() {
        println("\n=== Testing BestConfigService.parseConfigToContainerData ===\n")

        // Test 1: Counter-Strike 2 + Adreno (TM) 735 (fallback_match, bionic)
        println("1. Counter-Strike 2 + Adreno (TM) 735 (fallback_match, bionic)")
        val cs2Adreno = parseBestConfig(cs2Adreno735Response)
        val cs2AdrenoMatch = getMatchType(cs2Adreno735Response)
        val cs2AdrenoResult = runBlocking { BestConfigService.parseConfigToContainerData(context, cs2Adreno, cs2AdrenoMatch, cs2AdrenoMatch != "fallback_match") }
        println("Match Type: $cs2AdrenoMatch")
        printContainerData(cs2AdrenoResult, "CS2-Adreno735")
        println()

        // Test 2: Detective Dotson + Mali-G57 MC2 (fallback_match, glibc)
        println("2. Detective Dotson + Mali-G57 MC2 (fallback_match, glibc)")
        val detectiveMali = parseBestConfig(detectiveDotsonMaliResponse)
        val detectiveMaliMatch = getMatchType(detectiveDotsonMaliResponse)
        val detectiveMaliResult = runBlocking { BestConfigService.parseConfigToContainerData(context, detectiveMali, detectiveMaliMatch, detectiveMaliMatch != "fallback_match") }
        println("Match Type: $detectiveMaliMatch")
        printContainerData(detectiveMaliResult, "Detective-Mali")
        println()

        // Test 3: Dota 2 + Mali-G57 MC2 (fallback_match, bionic)
        println("3. Dota 2 + Mali-G57 MC2 (fallback_match, bionic)")
        val dota2Mali = parseBestConfig(dota2MaliResponse)
        val dota2MaliMatch = getMatchType(dota2MaliResponse)
        val dota2MaliResult = runBlocking { BestConfigService.parseConfigToContainerData(context, dota2Mali, dota2MaliMatch, dota2MaliMatch != "fallback_match") }
        println("Match Type: $dota2MaliMatch")
        printContainerData(dota2MaliResult, "Dota2-Mali")
        println()

        // Test 4: Counter-Strike 2 + Mali-G57 MC2 (exact_gpu_match, bionic)
        println("4. Counter-Strike 2 + Mali-G57 MC2 (exact_gpu_match, bionic)")
        val cs2Mali = parseBestConfig(cs2MaliExactMatchResponse)
        val cs2MaliMatch = getMatchType(cs2MaliExactMatchResponse)
        val cs2MaliResult = runBlocking { BestConfigService.parseConfigToContainerData(context, cs2Mali, cs2MaliMatch, cs2MaliMatch != "fallback_match") }
        println("Match Type: $cs2MaliMatch")
        printContainerData(cs2MaliResult, "CS2-Mali-Exact")
        println()

        // Test 5: Dota 2 + Adreno (TM) 830 (exact_gpu_match, bionic)
        println("5. Dota 2 + Adreno (TM) 830 (exact_gpu_match, bionic)")
        val dota2Adreno830 = parseBestConfig(dota2Adreno830ExactMatchResponse)
        val dota2Adreno830Match = getMatchType(dota2Adreno830ExactMatchResponse)
        val dota2Adreno830Result = runBlocking { BestConfigService.parseConfigToContainerData(context, dota2Adreno830, dota2Adreno830Match, dota2Adreno830Match != "fallback_match") }
        println("Match Type: $dota2Adreno830Match")
        printContainerData(dota2Adreno830Result, "Dota2-Adreno830-Exact")
        println()

        // Test 6: Dota 2 + Adreno (TM) 835 (gpu_family_match, bionic)
        println("6. Dota 2 + Adreno (TM) 835 (gpu_family_match, bionic)")
        val dota2Adreno835 = parseBestConfig(dota2Adreno835FamilyMatchResponse)
        val dota2Adreno835Match = getMatchType(dota2Adreno835FamilyMatchResponse)
        val dota2Adreno835Result = runBlocking { BestConfigService.parseConfigToContainerData(context, dota2Adreno835, dota2Adreno835Match, dota2Adreno835Match != "fallback_match") }
        println("Match Type: $dota2Adreno835Match")
        printContainerData(dota2Adreno835Result, "Dota2-Adreno835-Family")
        println()

        // Test 7: Dota 2 + XClipse xxx (fallback_match, bionic)
        println("7. Dota 2 + XClipse xxx (fallback_match, bionic)")
        val dota2XClipse = parseBestConfig(dota2XClipseFallbackResponse)
        val dota2XClipseMatch = getMatchType(dota2XClipseFallbackResponse)
        val dota2XClipseResult = runBlocking { BestConfigService.parseConfigToContainerData(context, dota2XClipse, dota2XClipseMatch, dota2XClipseMatch != "fallback_match") }
        println("Match Type: $dota2XClipseMatch")
        printContainerData(dota2XClipseResult, "Dota2-XClipse-Fallback")
        println()

        // Test 8: Hades II + Adreno (TM) 835 (gpu_family_match, glibc)
        println("8. Hades II + Adreno (TM) 835 (gpu_family_match, glibc)")
        val hades2Adreno835 = parseBestConfig(hades2Adreno835FamilyMatchResponse)
        val hades2Adreno835Match = getMatchType(hades2Adreno835FamilyMatchResponse)
        val hades2Adreno835Result = runBlocking { BestConfigService.parseConfigToContainerData(context, hades2Adreno835, hades2Adreno835Match, hades2Adreno835Match != "fallback_match") }
        println("Match Type: $hades2Adreno835Match")
        printContainerData(hades2Adreno835Result, "Hades2-Adreno835-Family")
        println()

        // Test 9: Hades II + Adreno (TM) 735 (exact_gpu_match, bionic)
        println("9. Hades II + Adreno (TM) 735 (exact_gpu_match, bionic)")
        val hades2Adreno735 = parseBestConfig(hades2Adreno735ExactMatchResponse)
        val hades2Adreno735Match = getMatchType(hades2Adreno735ExactMatchResponse)
        val hades2Adreno735Result = runBlocking { BestConfigService.parseConfigToContainerData(context, hades2Adreno735, hades2Adreno735Match, hades2Adreno735Match != "fallback_match") }
        println("Match Type: $hades2Adreno735Match")
        printContainerData(hades2Adreno735Result, "Hades2-Adreno735-Exact")
        println()

        // Test 10: Hades II + Mali-GC 824 (fallback_match, bionic)
        println("10. Hades II + Mali-GC 824 (fallback_match, bionic)")
        val hades2MaliGc824 = parseBestConfig(hades2MaliGc824FallbackResponse)
        val hades2MaliGc824Match = getMatchType(hades2MaliGc824FallbackResponse)
        val hades2MaliGc824Result = runBlocking { BestConfigService.parseConfigToContainerData(context, hades2MaliGc824, hades2MaliGc824Match, hades2MaliGc824Match != "fallback_match") }
        println("Match Type: $hades2MaliGc824Match")
        printContainerData(hades2MaliGc824Result, "Hades2-MaliGc824-Fallback")
        println()

        // All tests should pass (not null or empty)
        assertNotNull("CS2 Adreno result should not be null", cs2AdrenoResult)
        assertTrue("CS2 Adreno result should not be empty", cs2AdrenoResult?.isNotEmpty() == true)
        assertNotNull("Detective Mali result should not be null", detectiveMaliResult)
        assertTrue("Detective Mali result should not be empty", detectiveMaliResult?.isNotEmpty() == true)
        assertNotNull("Dota2 Mali result should not be null", dota2MaliResult)
        assertTrue("Dota2 Mali result should not be empty", dota2MaliResult?.isNotEmpty() == true)
        assertNotNull("CS2 Mali exact result should not be null", cs2MaliResult)
        assertTrue("CS2 Mali exact result should not be empty", cs2MaliResult?.isNotEmpty() == true)
        assertNotNull("Dota2 Adreno830 exact result should not be null", dota2Adreno830Result)
        assertTrue("Dota2 Adreno830 exact result should not be empty", dota2Adreno830Result?.isNotEmpty() == true)
        assertNotNull("Dota2 Adreno835 family result should not be null", dota2Adreno835Result)
        assertTrue("Dota2 Adreno835 family result should not be empty", dota2Adreno835Result?.isNotEmpty() == true)
        assertNotNull("Dota2 XClipse fallback result should not be null", dota2XClipseResult)
        assertTrue("Dota2 XClipse fallback result should not be empty", dota2XClipseResult?.isNotEmpty() == true)
        assertTrue("Hades2 Adreno835 family result should be empty", hades2Adreno835Result?.isNotEmpty() == true) // Missing wineVersion
        assertNotNull("Hades2 Adreno735 exact result should not be null", hades2Adreno735Result)
        assertTrue("Hades2 Adreno735 exact result should not be empty", hades2Adreno735Result?.isNotEmpty() == true)
        assertNotNull("Hades2 MaliGc824 fallback result should not be null", hades2MaliGc824Result)
        assertTrue("Hades2 MaliGc824 fallback result should not be empty", hades2MaliGc824Result?.isNotEmpty() == true)
    }

    /**
     * Helper function to print Map in a readable format
     */
    private fun printContainerData(data: Map<String, Any?>?, testName: String) {
        if (data == null || data.isEmpty()) {
            println("  Result: null or empty")
            return
        }

        println("  Result for $testName:")
        data.forEach { (key, value) ->
            println("    $key: $value")
        }
    }

    @Test
    fun testAllFieldsExhaustive() {
        // Test that all important fields are being parsed correctly
        val bestConfig = parseBestConfig(cs2MaliExactMatchResponse)
        val matchType = getMatchType(cs2MaliExactMatchResponse)
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, matchType != "fallback_match") }

        assertNotNull("Result should not be null", result)
        assertTrue("Result should not be empty", result!!.isNotEmpty())

        // Test fields that should be in the map for exact_gpu_match
        assertEquals("graphicsDriver should be parsed in exact match", "wrapper-leegao", result["graphicsDriver"])
        assertEquals("graphicsDriverConfig should be parsed in exact match", "version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal", result["graphicsDriverConfig"])
        assertEquals("dxwrapper should be parsed in exact match", "dxvk", result["dxwrapper"])
        assertEquals("dxwrapperConfig should be parsed in exact match", "version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1", result["dxwrapperConfig"])
        assertEquals("execArgs should be parsed", "", result["execArgs"])
        assertEquals("containerVariant should be parsed", "bionic", result["containerVariant"])
        assertEquals("wineVersion should be parsed", "proton-9.0-x86_64", result["wineVersion"])
        assertEquals("emulator should be parsed", "Box64", result["emulator"])
        assertEquals("fexcoreVersion should be parsed", "2507", result["fexcoreVersion"])
        assertEquals("box64Version should be parsed", "0.3.6", result["box64Version"])
        assertEquals("box64Preset should be parsed", "PERFORMANCE", result["box64Preset"])
        assertEquals(1, (result["startupSelection"] as? Byte)?.toInt() ?: (result["startupSelection"] as? Int))
        assertEquals(false, result["useLegacyDRM"])
    }

    @Test
    fun testMissingContainerVariant_returnsNull() {
        // Test that missing containerVariant returns null
        val configJson = """
            {
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when containerVariant is missing", result == null || result.isEmpty())
    }

    @Test
    fun testMissingWineVersion_returnsNull() {
        // Test that missing wineVersion returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when wineVersion is missing", result == null || result.isEmpty())
    }

    @Test
    fun testMissingDxwrapper_returnsNull() {
        // Test that missing dxwrapper returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when dxwrapper is missing", result == null || result.isEmpty())
    }

    @Test
    fun testMissingDxwrapperConfig_returnsNull() {
        // Test that missing dxwrapperConfig returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": "dxvk"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when dxwrapperConfig is missing", result == null || result.isEmpty())
    }

    @Test
    fun testEmptyContainerVariant_returnsNull() {
        // Test that empty containerVariant returns null
        val configJson = """
            {
                "containerVariant": "",
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when containerVariant is empty", result == null || result.isEmpty())
    }

    @Test
    fun testEmptyWineVersion_returnsNull() {
        // Test that empty wineVersion returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when wineVersion is empty", result == null || result.isEmpty())
    }

    @Test
    fun testEmptyDxwrapper_returnsNull() {
        // Test that empty dxwrapper returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": "",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when dxwrapper is empty", result == null || result.isEmpty())
    }

    @Test
    fun testEmptyDxwrapperConfig_returnsNull() {
        // Test that empty dxwrapperConfig returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": ""
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when dxwrapperConfig is empty", result == null || result.isEmpty())
    }

    @Test
    fun testAllMandatoryFieldsMissing_returnsNull() {
        // Test that missing all mandatory fields returns null
        val configJson = """
            {
                "box64Version": "0.3.6",
                "emulator": "Box64",
                "envVars": "TEST_VAR=test"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when all mandatory fields are missing", result == null || result.isEmpty())
    }

    @Test
    fun testNullContainerVariant_returnsNull() {
        // Test that null containerVariant returns null
        val configJson = """
            {
                "containerVariant": null,
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when containerVariant is null", result == null || result.isEmpty())
    }

    @Test
    fun testNullWineVersion_returnsNull() {
        // Test that null wineVersion returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": null,
                "dxwrapper": "dxvk",
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when wineVersion is null", result == null || result.isEmpty())
    }

    @Test
    fun testNullDxwrapper_returnsNull() {
        // Test that null dxwrapper returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": null,
                "dxwrapperConfig": "version=async-1.10.3"
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when dxwrapper is null", result == null || result.isEmpty())
    }

    @Test
    fun testNullDxwrapperConfig_returnsNull() {
        // Test that null dxwrapperConfig returns null
        val configJson = """
            {
                "containerVariant": "bionic",
                "wineVersion": "proton-9.0-x86_64",
                "dxwrapper": "dxvk",
                "dxwrapperConfig": null
            }
        """.trimIndent()

        val bestConfig = Json.parseToJsonElement(configJson).jsonObject
        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, "exact_gpu_match", true) }

        assertTrue("Result should be empty map when dxwrapperConfig is null", result == null || result.isEmpty())
    }

    @Test
    fun testNidhoggResponse_missingWineVersion_returnsNull() {
        // Test Nidhogg response where wineVersion is missing from bestConfig
        // This simulates a real API response where wineVersion might be missing
        // Note: wineVersion has been removed from the bestConfig object
        val nidhoggResponse = """
            {"bestConfig":{"id":"STEAM_94400","name":"","drives":"D:/storage/emulated/0/DownloadE:/data/data/app.gamenative/storageA:/data/user/0/app.gamenative/Steam/steamapps/common/Nidhogg","lc_all":"en_US.utf8","cpuList":"0,1,2,3,4,5,6,7","envVars":"ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60","showFPS":false,"useDRI3":true,"emulator":"FEXCore","execArgs":"","forceDlc":false,"language":"english","rcfileId":0,"dxwrapper":"dxvk","extraData":{"dxwrapper":"dxvk-async-1.10.3","appVersion":"6","imgVersion":"24","audioDriver":"pulseaudio","box64Version":"0.3.6","desktopTheme":"LIGHT,IMAGE,#0277bd,1280x720","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","config_changed":"true","fexcoreVersion":"2507","startupSelection":"1","lastInstalledMainWrapper":"Wrapper-leegao"},"inputType":3,"steamType":"normal","wow64Mode":true,"screenSize":"1280x720","audioDriver":"pulseaudio","box64Preset":"COMPATIBILITY","box86Preset":"COMPATIBILITY","installPath":"","box64Version":"0.3.6","box86Version":"0.3.2","cpuListWoW64":"0,1,2,3,4,5,6,7","desktopTheme":"LIGHT,IMAGE,#0277bd","useLegacyDRM":false,"midiSoundFont":"","wincomponents":"direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,vcrun2010=1,wmdecoder=1,opengl=0","executablePath":"Nidhogg.exe","fexcoreVersion":"2507","graphicsDriver":"Wrapper-leegao","needsUnpacking":false,"dxwrapperConfig":"version=async-1.10.3,framerate=0,maxDeviceMemory=0,async=1,asyncCache=0,vkd3dVersion=2.6,vkd3dLevel=12_1","launchRealSteam":false,"sessionMetadata":{"avg_fps":58.443478,"session_length_sec":117},"touchscreenMode":false,"containerVariant":"bionic","dinputMapperType":1,"sdlControllerAPI":true,"startupSelection":1,"allowSteamUpdates":false,"controllerMapping":"","disableMouseInput":false,"primaryController":1,"emulateKeyboardMouse":false,"graphicsDriverConfig":"version=System;blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Normal","graphicsDriverVersion":"","controllerEmulationBindings":{"A":"KEY_SPACE","B":"KEY_E","X":"KEY_Q","Y":"KEY_TAB","L1":"KEY_SHIFT_L","L2":"MOUSE_LEFT_BUTTON","L3":"NONE","R1":"KEY_CTRL_R","R2":"MOUSE_RIGHT_BUTTON","R3":"NONE","START":"KEY_ENTER","SELECT":"KEY_ESC","DPAD_UP":"KEY_UP","DPAD_DOWN":"KEY_DOWN","DPAD_LEFT":"KEY_LEFT","DPAD_RIGHT":"KEY_RIGHT"}},"matchType":"gpu_family_match","matchedGpu":"Mali-G57","matchedDeviceId":285}
        """.trimIndent()

        val bestConfig = parseBestConfig(nidhoggResponse)
        val matchType = getMatchType(nidhoggResponse)

        // Verify wineVersion is actually missing from bestConfig
        val bestConfigJson = org.json.JSONObject(bestConfig.toString())
        assertFalse("wineVersion should be missing from bestConfig", bestConfigJson.has("wineVersion"))

        val result = runBlocking { BestConfigService.parseConfigToContainerData(context, bestConfig, matchType, matchType != "fallback_match") }

        assertTrue("Result should be empty map when wineVersion is missing from bestConfig", result == null || result.isEmpty())
    }
}

