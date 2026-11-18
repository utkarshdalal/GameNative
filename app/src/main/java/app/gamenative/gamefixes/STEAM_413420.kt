package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Danganronpa 2: Goodbye Despair (Steam App ID 413420)
 *
 * After launching another game and returning to DR2, the game crashes on any scene with
 * shader compilation. The symptom is E5017 ("Flatten 'if' conditionals branches") logged
 * by vkd3d-shader, causing a hard crash shortly after the title screen.
 *
 * Root cause: Wine's builtin d3dx9_43 (from Proton) routes HLSL shader compilation through
 * vkd3d-shader, which does not support the [flatten] attribute → E5017 crash.
 *
 * Fix: Set WINEDLLOVERRIDES to ensure d3dx9_43 and d3dcompiler_43 load as native,builtin.
 * This forces Wine to use the real Microsoft DirectX DLLs (when present) instead of Proton
 * stubs, allowing shaders to compile internally and bypass vkd3d entirely.
 */
val STEAM_Fix_413420: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "413420",
    envVarsToSet = mapOf(
        "WINEDLLOVERRIDES" to "d3dx9_43=native,builtin d3dcompiler_43=native,builtin",
    ),
)
