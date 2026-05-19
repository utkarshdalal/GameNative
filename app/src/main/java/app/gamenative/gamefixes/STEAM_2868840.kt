package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Slay the Spire 2 (Steam)
 */
val STEAM_Fix_2868840: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "2868840",
    fixes = listOf(
        LaunchArgFix("--rendering-driver vulkan"),
        WineEnvVarFix(
            mapOf(
                "WINEDLLOVERRIDES" to "icu=d",
                "DOTNET_EnableWriteXorExecute" to "0",
                "DOTNET_GCHeapHardLimit" to "0x400000000",
            ),
        ),
    ),
)
