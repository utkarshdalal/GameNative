package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * FINAL FANTASY XII THE ZODIAC AGE (Steam)
 *
 * The game reads the Steam ID for its save folder name out of a stack frame it has already popped.
 * Wine's ARM64EC ucrtbase writes over that frame in swprintf, so the folder name comes out as junk
 * and every session starts with empty saves. Microsoft's own ucrtbase leaves the frame alone, so it
 * is unpacked from the 2015 redistributable the game already ships and dropped beside the exe.
 *
 * The game also copies the display modes it keeps (1280x720 and up) into an array that it only
 * allocates when the mode count differs from the capacity, which starts at 1. A 1280x720 desktop
 * leaves exactly one mode, so the copy runs into a null pointer and the game's own handler answers
 * the crash with a full-memory minidump of 13 GB and more. Taking out the conditional jump makes the
 * allocation unconditional, which is safe because the code frees the old pointer first.
 */
val STEAM_Fix_595520: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "595520",
    fixes = listOf(
        RedistDllExtractFix(
            installerRelativePath = "_CommonRedist/vcredist/2015/vc_redist.x64.exe",
            cabinetEntryName = "a10",
            fileName = "ucrtbase.dll",
            destinationRelativePath = "x64/ucrtbase.dll",
        ),
        BinaryPatchFix(
            targetRelativePath = "x64/FFXII_TZA.exe",
            expectedFileLength = 32_864_752L,
            patches = listOf(
                BinaryPatch(
                    offset = 0xbcef4L,
                    original = byteArrayOf(0x8B.toByte(), 0x41, 0x20, 0x39, 0x41, 0x34, 0x74, 0x31),
                    replacement = byteArrayOf(0x8B.toByte(), 0x41, 0x20, 0x39, 0x41, 0x34, 0x90.toByte(), 0x90.toByte()),
                ),
            ),
        ),
    ),
)
