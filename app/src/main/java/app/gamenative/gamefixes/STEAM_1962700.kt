package app.gamenative.gamefixes

import app.gamenative.data.GameSource
import com.winlator.xenvironment.ImageFs

private val SUBNAUTICA2_ENGINE_INI = """
[SectionsToSave]
bCanSaveAllSections=true
+Section=CurrentIniVersion

[/Script/Engine.RendererSettings]
r.Nanite=0
r.DynamicGlobalIlluminationMethod=0
r.ReflectionMethod=0
r.ShadowQuality=0
r.Shadow.CSM.MaxCascades=1
r.Shadow.Virtual.Enable=0
r.VolumetricFog=0
r.GPUCrashDebugging=0
r.BufferPool.FreeUnusedVariables=1
r.D3D12.ZeroBufferAllocations=1

[/Script/WindowsTargetPlatform.WindowsTargetSettings]
r.D3D12.GlobalViewHeapSize=500000
r.D3D12.LocalViewHeapSize=16384
r.D3D12.MaxGraphicsResourceViewHeapSize=500000
d3d12.MaxDescriptorHeaps=4096
""".trimIndent() + "\n"

val STEAM_Fix_1962700: KeyedGameFix = KeyedPrefixFileFix(
    gameSource = GameSource.STEAM,
    gameId = "1962700",
    driveCRelativePath = "users/${ImageFs.USER}/AppData/Local/Subnautica2/Saved/Config/Windows/Engine.ini",
    content = SUBNAUTICA2_ENGINE_INI,
)
