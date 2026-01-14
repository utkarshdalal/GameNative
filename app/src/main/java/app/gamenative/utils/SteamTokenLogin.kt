package app.gamenative.utils

import android.annotation.SuppressLint
import android.content.Context
import com.auth0.android.jwt.JWT
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import `in`.dragonbra.javasteam.types.KeyValue
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

// This is the key to make config.vdf work
const val NULL_CHAR = '\u0000'
const val TOKEN_EXPIRE_TIME = 86400L // 1 day

class SteamTokenLogin(
    private val steamId: String,
    private val login: String,
    private val token: String,
    private val imageFs: ImageFs,
    private val guestProgramLauncherComponent: GuestProgramLauncherComponent? = null,
) {
    fun setupSteamFiles() {
        // For loginusers.vdf and reg values
        SteamUtils.autoLoginUserChanges(imageFs = imageFs)
        phase1SteamConfig()
    }

    private fun hdr() : String {
        val crc = CRC32()
        crc.update(login.toByteArray())
        return "${crc.value.toString(16)}1"
    }

    private fun execCommand(command: String) : String {
        return guestProgramLauncherComponent?.execShellCommand(command, false)
            ?: throw IllegalStateException("GuestProgramLauncherComponent is required for command execution")
    }

    private fun killWineServer() {
        try {
            execCommand("wineserver -k")
        } catch (e: Exception) {
            Timber.tag("SteamTokenLogin").e("Failed to kill wineserver: ${e.message}")
        }
    }

    private fun encryptToken(token: String) : String {
        // Simple encoding (not as secure as Windows CryptProtectData, but cross-platform)
        // run steam-token.exe from extractDir and return the result
        return execCommand("wine ${imageFs.rootDir}/opt/apps/steam-token.exe encrypt $login $token")
    }

    private fun decryptToken(vdfValue: String) : String {
        // Simple decoding (not as secure as Windows CryptProtectData, but cross-platform)
        // run steam-token.exe from extractDir and return the result
        return execCommand("wine ${imageFs.rootDir}/opt/apps/steam-token.exe decrypt $login $vdfValue")
    }

    private fun obfuscateToken(value: String, mtbf: Long) : String {
        return SteamTokenHelper.obfuscate(value.toByteArray(), mtbf)
    }

    private fun deobfuscateToken(value: String, mtbf: Long) : String {
        return SteamTokenHelper.deobfuscate(value, mtbf)
    }

    @SuppressLint("BinaryOperationInTimber")
    private fun createConfigVdf(): String {
        // Simple hash-based encryption for cross-platform compatibility
        val hdr = hdr()

        // e.g. 1329969238
        val minMTBF = 1000000000L
        val maxMTBF = 2000000000L
        var mtbf = kotlin.random.Random.nextLong(minMTBF, maxMTBF)
        var encoded = ""

        // Try to encode the token until it get a value
        do {
            try {
                encoded = obfuscateToken("$token$NULL_CHAR", mtbf)
            } catch (_: Exception) {
                mtbf = kotlin.random.Random.nextLong(minMTBF, maxMTBF)
            }
        } while (encoded == "")

        Timber.tag("SteamTokenLogin").d("MTBF: $mtbf")
        Timber.tag("SteamTokenLogin").d("Encoded: $encoded")

        return """
            "InstallConfigStore"
            {
                "Software"
                {
                    "Valve"
                    {
                        "Steam"
                        {
                            "MTBF"		"$mtbf"
                            "ConnectCache"
                            {
                                "$hdr"		"$encoded$NULL_CHAR"
                            }
                            "Accounts"
                            {
                                "$login"
                                {
                                    "SteamID"		"$steamId"
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }

    @SuppressLint("BinaryOperationInTimber")
    private fun createLocalVdf(): String {
        // Simple hash-based encryption for cross-platform compatibility
        val hdr = hdr()
        val encoded = encryptToken(token)

        return """
            "MachineUserConfigStore"
            {
                "Software"
                {
                    "Valve"
                    {
                        "Steam"
                        {
                            "ConnectCache"
                            {
                                "$hdr"		"$encoded"
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }

    /**
     * Phase 1 Steam Config
     * Write config.vdf
     */
    fun phase1SteamConfig() {
        val steamConfigDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/config").toPath()
        Files.createDirectories(steamConfigDir)

        // Check if config.vdf not exists, or its contents does not contains MTBF
        val configVdfPath = steamConfigDir.resolve("config.vdf")

        var shouldWriteConfig = true
        var shouldProcessPhase2 = false

        if (Files.exists(configVdfPath)) {
            val vdfContent = FileUtils.readString(configVdfPath.toFile())
            if (vdfContent.contains("ConnectCache")) {
                // Find the value of ConnectCache
                // Use structured parsing:
                val vdfData = KeyValue.loadFromString(vdfContent)!!
                val mtbf = vdfData["Software"]["Valve"]["Steam"]["MTBF"].value
                val connectCacheValue = vdfData["Software"]["Valve"]["Steam"]["ConnectCache"][hdr()].value

                if (mtbf != null && connectCacheValue != null) {
                    try {
                        val dToken = deobfuscateToken(connectCacheValue.trimEnd(NULL_CHAR), mtbf.toLong()).trimEnd(NULL_CHAR)
                        if (JWT(dToken).isExpired(TOKEN_EXPIRE_TIME)) {
                            Timber.tag("SteamTokenLogin").d("Saved JWT expired, overriding config.vdf")
                            // If the saved JWT is expired, override it
                            shouldWriteConfig = true
                        } else {
                            Timber.tag("SteamTokenLogin").d("Saved JWT is not expired, do not override config.vdf")
                            shouldWriteConfig = false
                        }
                    } catch (_: Exception) {
                        Timber.tag("SteamTokenLogin").d("Cannot parse saved JWT, overriding config.vdf")
                        shouldWriteConfig = true
                    }
                } else {
                    if (mtbf == null && connectCacheValue == null) {
                        Timber.tag("SteamTokenLogin").d("MTBF and ConnectCache not found, overriding config.vdf")
                        shouldWriteConfig = true
                    } else if (mtbf != null) {
                        Timber.tag("SteamTokenLogin").d("MTBF exists but ConnectCache not found, it is an updated steam client, processing phase 2")
                        shouldWriteConfig = false
                        shouldProcessPhase2 = true
                    }
                }
            } else if (vdfContent.contains("MTBF")) {
                Timber.tag("SteamTokenLogin").d("MTBF exists but ConnectCache not found, it is an updated steam client, processing phase 2")
                shouldWriteConfig = false
                shouldProcessPhase2 = true
            }
        }

        if (shouldWriteConfig) {
            Timber.tag("SteamTokenLogin").d("Overriding config.vdf")

            Files.write(
                steamConfigDir.resolve("config.vdf"),
                createConfigVdf().toByteArray(),
            )

            // Set permissions
            FileUtils.chmod(File(steamConfigDir.absolutePathString(), "loginusers.vdf"), 505) // 0771
            FileUtils.chmod(File(steamConfigDir.absolutePathString(), "config.vdf"), 505) // 0771

            // Remove local.vdf
            val localSteamDir = File(imageFs.wineprefix, "drive_c/users/${ImageFs.USER}/AppData/Local/Steam").toPath()
            localSteamDir.createDirectories()

            if (localSteamDir.resolve("local.vdf").exists()) {
                Files.delete(localSteamDir.resolve("local.vdf"))
            }
        } else if (shouldProcessPhase2) {
            phase2LocalConfig()
        }
    }

    /**
     * Phase 2 Local Config
     * Refresh local.vdf value
     */
    fun phase2LocalConfig() {
        try {
            // Extract steam-token.tzst
            val extractDir = File(imageFs.rootDir, "/opt/apps/")
            Files.createDirectories(extractDir.toPath())
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, File(imageFs.filesDir, "steam-token.tzst"), extractDir)

            val localSteamDir = File(imageFs.wineprefix, "drive_c/users/${ImageFs.USER}/AppData/Local/Steam").toPath()
            Files.createDirectories(localSteamDir)

            // Remove local.vdf
            if (localSteamDir.resolve("local.vdf").exists()) {
                val vdfContent = FileUtils.readString(localSteamDir.resolve("local.vdf").toFile())
                val vdfData = KeyValue.loadFromString(vdfContent)!!
                val connectCacheValue = vdfData["Software"]["Valve"]["Steam"]["ConnectCache"][hdr()].value
                if (connectCacheValue != null) {
                    try {
                        val dToken = decryptToken(connectCacheValue.trimEnd(NULL_CHAR))
                        val savedJWT = JWT(dToken)

                        // If the saved JWT is not expired, do not override it
                        if (!savedJWT.isExpired(TOKEN_EXPIRE_TIME)) {
                            Timber.tag("SteamTokenLogin").d("Saved JWT is not expired, do not override local.vdf")
                            return
                        }
                    } catch (e: Exception) {
                        Timber.tag("SteamTokenLogin").d("An unexpected error occurred: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }

            Timber.tag("SteamTokenLogin").d("Overriding local.vdf")

            Files.write(
                localSteamDir.resolve("local.vdf"),
                createLocalVdf().toByteArray(),
            )

            killWineServer()

            // Set permissions
            FileUtils.chmod(File(localSteamDir.absolutePathString(), "local.vdf"), 505) // 0771
        } catch (e: Exception) {
            Timber.tag("SteamTokenLogin").d("An unexpected error occurred: ${e.message}")
            e.printStackTrace()
        }
    }
}
