package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Spelunky 2 (Steam)
 *
 * Right after its renderer comes up, the game's memory heap commits 32 MB blocks at fixed
 * addresses it picks at random inside a 1 TB window starting at 7 TB (`0x7017f800000` plus a
 * random 4 GB-aligned slot). It keeps rolling new slots until a commit succeeds. Android kernels
 * on these devices give user space 39 address bits (512 GB), so Wine has to refuse every one of
 * those addresses and the main thread spins on VirtualAlloc forever behind a black screen.
 *
 * The game never checks that it got the address it asked for, so the window is moved down below
 * 512 GB: the base becomes `0x17f800000` and the slot mask `0xfe` becomes `0x7e`, which keeps the
 * highest possible block (`0x7e17f800000` + 32 MB) under the 39-bit limit.
 */
val STEAM_Fix_418530: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "418530",
    fixes = listOf(
        BinaryPatchFix(
            targetRelativePath = "Spel2.exe",
            expectedFileLength = 585_350_144L,
            patches = listOf(
                // movabs rbx, 0x7017f800000 -> movabs rbx, 0x17f800000
                BinaryPatch(
                    offset = 0x22cbca24L,
                    original = byteArrayOf(0x48, 0xBB.toByte(), 0x00, 0x00, 0x80.toByte(), 0x7F, 0x01, 0x07, 0x00, 0x00),
                    replacement = byteArrayOf(0x48, 0xBB.toByte(), 0x00, 0x00, 0x80.toByte(), 0x7F, 0x01, 0x00, 0x00, 0x00),
                ),
                // and ecx, 0xfe -> and ecx, 0x7e
                BinaryPatch(
                    offset = 0x22cbcabaL,
                    original = byteArrayOf(0x81.toByte(), 0xE1.toByte(), 0xFE.toByte(), 0x00, 0x00, 0x00),
                    replacement = byteArrayOf(0x81.toByte(), 0xE1.toByte(), 0x7E, 0x00, 0x00, 0x00),
                ),
            ),
        ),
    ),
)
