package app.gamenative.audio

import android.content.Context
import android.util.Log
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineInfo
import com.winlator.xenvironment.ImageFs
import java.io.File

object GameNativeAudio {
    private const val TAG = "GameNativeAudio"

    const val PAYLOAD_ASSET = "gamenative-audio-guest.tzst"

    private const val PLUGIN = "libasound_module_pcm_gamenative.so"
    private const val PLUGIN_DIR = "alsa-lib"
    private const val CONFIG = "gamenative.conf"
    private const val WINE_GUEST_DIR = "/lib/wine"

    @JvmStatic
    fun configPath(context: Context): String {
        val root = ImageFs.find(context).rootDir.path
        return "$root/usr/share/alsa/alsa.conf:$root/usr/share/alsa/gamenative.conf"
    }

    @JvmStatic
    fun ensurePayload(context: Context): Boolean {
        val guestDir = guestDir(context)
        if (!TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context.assets,
                PAYLOAD_ASSET,
                guestDir,
            )
        ) {
            Log.w(TAG, "could not unpack $PAYLOAD_ASSET into ${guestDir.path}")
            return false
        }
        return true
    }

    @JvmStatic
    fun install(context: Context, container: Container, wineInfo: WineInfo): Boolean {
        if (!ensurePayload(context)) return false
        val imageFs = ImageFs.find(context)
        val guest = guestDir(context)

        val plugin = File(File(guest, PLUGIN_DIR), PLUGIN)
        if (!plugin.isFile) {
            Log.w(TAG, "no $PLUGIN_DIR/$PLUGIN in ${guest.path}; there would be no sink")
            return false
        }
        val config = File(guest, CONFIG)
        if (!config.isFile) {
            Log.w(TAG, "no $CONFIG in ${guest.path}; the sink would not be selectable")
            return false
        }

        return try {
            copyInto(plugin, File(File(imageFs.rootDir, "usr/lib/$PLUGIN_DIR"), PLUGIN))
            copyInto(config, File(File(imageFs.rootDir, "usr/share/alsa"), CONFIG))
            Log.d(TAG, "installed the ALSA plugin; ALSA_CONFIG_PATH=${configPath(context)}")
            true
        } catch (error: Exception) {
            Log.w(TAG, "could not install the ALSA plugin into ${imageFs.rootDir}", error)
            false
        }
    }

    @JvmStatic
    fun uninstall(context: Context) {
        val imageFs = ImageFs.find(context)
        val plugin = File(File(imageFs.rootDir, "usr/lib/$PLUGIN_DIR"), PLUGIN)
        val config = File(File(imageFs.rootDir, "usr/share/alsa"), CONFIG)
        val removedPlugin = plugin.delete()
        val removedConfig = config.delete()
        if (removedPlugin || removedConfig) {
            Log.d(TAG, "removed the ALSA plugin and ${File(File(imageFs.rootDir, "usr/share/alsa"), CONFIG).path}")
        }
    }

    private fun copyInto(source: File, target: File) {
        if (target.isFile && sameContent(source, target)) return
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.setExecutable(true, false)
        target.setReadable(true, false)
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        return try {
            a.inputStream().use { left ->
                b.inputStream().use { right ->
                    val one = ByteArray(8192)
                    val two = ByteArray(8192)
                    var same = true
                    while (same) {
                        val readLeft = left.read(one)
                        val readRight = right.read(two)
                        same = readLeft == readRight &&
                            (readLeft <= 0 || one.copyOf(readLeft).contentEquals(two.copyOf(readRight)))
                        if (readLeft <= 0) break
                    }
                    same
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not compare ${a.path} with ${b.path}", error)
            false
        }
    }

    private fun guestDir(context: Context): File =
        File(ImageFs.find(context).winePath + WINE_GUEST_DIR)
}
