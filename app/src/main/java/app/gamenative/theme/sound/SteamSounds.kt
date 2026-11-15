package app.gamenative.theme.sound

import app.gamenative.theme.ThemeZip
import java.io.File
import kotlin.random.Random

enum class UiSound { NAVIGATE, SELECT, BACK, TAB, MENU_OPEN, MENU_CLOSE, DIALOG_OPEN, DIALOG_CLOSE, TOGGLE_ON, TOGGLE_OFF, SLIDER, SLIDER_DOWN, TYPE, PAGE_IN, PAGE_OUT, LAUNCH, ERROR, NOTIFY }

/** Steam's UI sound file names, which sound pack files replace, and which of them play for each [UiSound]. Pure JVM. */
object SteamSounds {

    const val MENU_MUSIC = "menu_music.mp3"
    const val INTRO_MUSIC = "intro_music.mp3"

    val AUDIO_EXTENSIONS = setOf("wav", "mp3", "ogg", "m4a", "flac")

    /** Every file in Steam's `steamui/sounds` folder. */
    val ALL: List<String> = listOf(
        "bumper_end.wav",
        "confirmation_negative.wav",
        "confirmation_positive.wav",
        "deck_ui_achievement_toast.wav",
        "deck_ui_bumper_end_02.wav",
        "deck_ui_default_activation.wav",
        "deck_ui_hide_modal.wav",
        "deck_ui_into_game_detail.wav",
        "deck_ui_launch_game.wav",
        "deck_ui_message_toast.wav",
        "deck_ui_misc_01.wav",
        "deck_ui_misc_08.wav",
        "deck_ui_misc_10.wav",
        "deck_ui_navigation.wav",
        "deck_ui_out_of_game_detail.wav",
        "deck_ui_show_modal.wav",
        "deck_ui_side_menu_fly_in.wav",
        "deck_ui_side_menu_fly_out.wav",
        "deck_ui_slider_down.wav",
        "deck_ui_slider_up.wav",
        "deck_ui_switch_toggle_off.wav",
        "deck_ui_switch_toggle_on.wav",
        "deck_ui_tab_transition_01.wav",
        "deck_ui_tile_scroll.wav",
        "deck_ui_toast.wav",
        "deck_ui_typing.wav",
        "deck_ui_volume.wav",
        "pop_sound.wav",
        "steam_at_mention.m4a",
        "steam_chatroom_notification.m4a",
        "ui_steam_message_old_smooth.m4a",
        "ui_steam_smoother_friend_join.m4a",
        "ui_steam_smoother_friend_online.m4a",
    )

    /** Steam sounds tried in order for each [UiSound]; the first one the pack provides plays. */
    val TABLE: Map<UiSound, List<String>> = mapOf(
        UiSound.NAVIGATE to listOf("deck_ui_misc_10.wav", "deck_ui_navigation.wav"),
        UiSound.SELECT to listOf("deck_ui_default_activation.wav", "confirmation_positive.wav"),
        UiSound.BACK to listOf("confirmation_negative.wav", "deck_ui_out_of_game_detail.wav"),
        UiSound.TAB to listOf("deck_ui_tab_transition_01.wav"),
        UiSound.MENU_OPEN to listOf("deck_ui_side_menu_fly_in.wav"),
        UiSound.MENU_CLOSE to listOf("deck_ui_side_menu_fly_out.wav"),
        UiSound.DIALOG_OPEN to listOf("deck_ui_show_modal.wav"),
        UiSound.DIALOG_CLOSE to listOf("deck_ui_hide_modal.wav"),
        UiSound.TOGGLE_ON to listOf("deck_ui_switch_toggle_on.wav"),
        UiSound.TOGGLE_OFF to listOf("deck_ui_switch_toggle_off.wav"),
        UiSound.SLIDER to listOf("deck_ui_slider_up.wav", "deck_ui_slider_down.wav"),
        UiSound.SLIDER_DOWN to listOf("deck_ui_slider_down.wav", "deck_ui_slider_up.wav"),
        UiSound.TYPE to listOf("deck_ui_typing.wav"),
        UiSound.PAGE_IN to listOf("deck_ui_into_game_detail.wav"),
        UiSound.PAGE_OUT to listOf("deck_ui_out_of_game_detail.wav"),
        UiSound.LAUNCH to listOf("deck_ui_launch_game.wav"),
        UiSound.ERROR to listOf("deck_ui_bumper_end_02.wav", "bumper_end.wav"),
        UiSound.NOTIFY to listOf("deck_ui_toast.wav", "deck_ui_message_toast.wav"),
    )

    const val NAVIGATE_MIN_GAP_MS = 35L

    /** False when a navigate sound comes less than [minGapMs] after the last one that played. */
    fun navigateAllowed(lastPlayedMs: Long, nowMs: Long, minGapMs: Long = NAVIGATE_MIN_GAP_MS): Boolean =
        lastPlayedMs <= 0L || nowMs - lastPlayedMs !in 0 until minGapMs

    /**
     * Files the pack plays for Steam sound [steamName], as the Audio Loader picks them: nothing when the name is ignored,
     * the mapped candidates when mapped, else the file named after the sound. Missing and unsafe paths are dropped.
     */
    fun candidates(dir: File, manifest: SoundPackManifest, steamName: String): List<File> {
        if (steamName in manifest.ignore) return emptyList()
        manifest.mappings[steamName]?.mapNotNull { packFile(dir, it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        return listOfNotNull(packFile(dir, steamName))
    }

    /** Candidate files for [sound]: those of the first Steam sound in [TABLE] the pack provides. */
    fun candidates(dir: File, manifest: SoundPackManifest, sound: UiSound): List<File> {
        for (name in TABLE[sound].orEmpty()) {
            val files = candidates(dir, manifest, name)
            if (files.isNotEmpty()) return files
        }
        return emptyList()
    }

    fun <T> pick(options: List<T>, random: Random = Random.Default): T? = if (options.isEmpty()) null else options[random.nextInt(options.size)]

    fun menuMusic(dir: File, manifest: SoundPackManifest, random: Random = Random.Default): File? =
        pick(musicCandidates(dir, manifest, MENU_MUSIC), random)

    /** Like the Audio Loader, a pack has an intro when it maps `intro_music.mp3` or ships that file. */
    fun introMusic(dir: File, manifest: SoundPackManifest, random: Random = Random.Default): File? =
        pick(musicCandidates(dir, manifest, INTRO_MUSIC), random)

    private fun musicCandidates(dir: File, manifest: SoundPackManifest, name: String): List<File> =
        manifest.mappings[name]?.mapNotNull { packFile(dir, it) }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(packFile(dir, name))

    /** Number of [UiSound]s a sound pack plays, or of tracks a music pack has. */
    fun soundCount(dir: File, manifest: SoundPackManifest): Int =
        if (manifest.music) {
            listOfNotNull(menuMusic(dir, manifest), introMusic(dir, manifest)).size
        } else {
            UiSound.entries.count { candidates(dir, manifest, it).isNotEmpty() }
        }

    /** [rel] inside [dir] when it is a safe relative path to an existing audio file; exact name first, then ignoring case. */
    fun packFile(dir: File, rel: String): File? {
        val safe = ThemeZip.safeEntryPath(rel) ?: return null
        if (safe.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) return null
        val base = dir.canonicalFile
        val exact = File(base, safe)
        val found = if (exact.isFile) exact else findIgnoringCase(base, safe.split('/')) ?: return null
        val canonical = found.canonicalFile
        return canonical.takeIf { it.isFile && it.path.startsWith(base.path + File.separator) }
    }

    private fun findIgnoringCase(base: File, parts: List<String>): File? {
        var cur = base
        for ((i, part) in parts.withIndex()) {
            val next = cur.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            if (i < parts.size - 1 && !next.isDirectory) return null
            cur = next
        }
        return cur
    }
}
