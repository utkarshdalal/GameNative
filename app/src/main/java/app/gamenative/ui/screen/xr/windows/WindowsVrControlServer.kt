package app.gamenative.ui.screen.xr.windows

import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class WindowsVrControlServer(
    private val config: WindowsVrRuntimeConfig,
    private val diagnostics: WindowsVrDiagnostics,
    private val snapshots: WindowsVrSnapshotProvider,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val clients = Semaphore(16)
    private val executor = Executors.newFixedThreadPool(5)
    private var serverSocket: ServerSocket? = null
    private var lastFrameSerial = 0L
    private var firstFrameRecorded = false
    private var trackingSpaceRecorded = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(config.controlPort, 4, InetAddress.getByName("127.0.0.1"))
        socket.reuseAddress = true
        serverSocket = socket
        executor.execute {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (!clients.tryAcquire()) {
                    client.close()
                } else {
                    executor.execute { handle(client) }
                }
            }
        }
        diagnostics.record("control", "listening=127.0.0.1:${config.controlPort}")
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 120000
            val input = socket.getInputStream()
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII), 1024)
            var greeted = false
            while (running.get()) {
                val line = readBoundedLine(input) ?: break
                if (!greeted && line != "HELLO") break
                val response = respond(line)
                if (!greeted) greeted = response == "OK GameNativeVR ${config.protocolVersion}"
                writer.write(response)
                writer.newLine()
                writer.flush()
                if (line == "BYE") break
            }
        } catch (error: Exception) {
            diagnostics.record("control-client", error.javaClass.simpleName)
        } finally {
            runCatching { socket.close() }
            clients.release()
        }
    }

    private fun readBoundedLine(input: InputStream): String? {
        val bytes = ByteArray(1024)
        var length = 0
        while (true) {
            val value = input.read()
            if (value < 0) return null
            if (value == 10) return bytes.decodeToString(0, length)
            if (value !in 32..126 || length == bytes.size) return null
            bytes[length++] = value.toByte()
        }
    }

    private fun respond(line: String): String {
        val tokens = line.trim().split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "ERROR malformed"
        return when (tokens[0]) {
            "HELLO" -> if (tokens.size == 1) "OK GameNativeVR ${config.protocolVersion}" else "ERROR malformed"
            "GET_SYSTEM" -> if (tokens.size == 1) "OK system=1 vendor=2833 name=Meta_Quest_GameNative" else "ERROR malformed"
            "GET_VIEWS" -> if (tokens.size == 1) getViews() else "ERROR malformed"
            "GET_BOUNDS" -> if (tokens.size == 1) getBounds() else "ERROR malformed"
            "WAIT_FRAME" -> if (tokens.size == 1) waitFrame() else "ERROR malformed"
            "FRAME_SYNC" -> if (tokens.size == 1) frameSync() else "ERROR malformed"
            "LOCATE_VIEWS" -> if (tokens.size == 1) locateViews() else "ERROR malformed"
            "GET_INPUT" -> getInput(tokens)
            "HAPTIC" -> haptic(tokens)
            "BEGIN_SESSION" -> if (tokens.size == 1) {
                diagnostics.record("session", "began")
                "OK"
            } else "ERROR malformed"
            "END_SESSION", "REQUEST_EXIT", "BEGIN_FRAME" -> if (tokens.size == 1) "OK" else "ERROR malformed"
            "END_FRAME" -> run {
                val layers = tokens.singleOrNull { it.startsWith("layers=") }?.substringAfter('=')?.toIntOrNull()
                if (tokens.size != 2 || layers !in 0..16) "ERROR malformed" else {
                    if (!firstFrameRecorded) {
                        firstFrameRecorded = true
                        diagnostics.record("frame", "first-end layers=$layers")
                    }
                    "OK"
                }
            }
            "STATUS" -> if (tokens.size == 1) "OK ${diagnostics.compactStatus().replace(' ', '_').take(768)}" else "ERROR malformed"
            "SWAPCHAIN_CREATE" -> validateSwapchainCreate(tokens)
            "SWAPCHAIN_DESTROY", "SWAPCHAIN_RESET", "BYE" -> if (tokens.size == 1) "OK" else "ERROR malformed"
            "GFX_API" -> if (tokens.size == 2 && tokens[1] in setOf("d3d11", "d3d12", "vulkan")) {
                diagnostics.record("graphics", "api=${tokens[1]}")
                "OK"
            } else "ERROR unsupported"
            else -> "ERROR unsupported"
        }
    }

    private fun validateSwapchainCreate(tokens: List<String>): String {
        if (tokens.size == 2 && tokens[1].startsWith("images=")) {
            val count = tokens[1].substringAfter('=').toIntOrNull() ?: return "ERROR malformed"
            if (count !in 1..8) return "ERROR malformed"
            diagnostics.record("swapchain", "create images=$count")
            return "OK"
        }
        if (tokens.size != 5) return "ERROR malformed"
        val values = tokens.drop(1).associate { token ->
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return "ERROR malformed"
            token.substring(0, separator) to token.substring(separator + 1)
        }
        if (values.keys != setOf("w", "h", "count", "format")) return "ERROR malformed"
        val width = values["w"]?.toIntOrNull() ?: return "ERROR malformed"
        val height = values["h"]?.toIntOrNull() ?: return "ERROR malformed"
        val count = values["count"]?.toIntOrNull() ?: return "ERROR malformed"
        if (values["format"]?.toLongOrNull() == null || width !in 1..8192 || height !in 1..8192 || count !in 1..4) {
            return "ERROR malformed"
        }
        diagnostics.record("swapchain", "create width=$width height=$height count=$count")
        return "OK"
    }

    /** One round-trip for the whole per-frame state: frame timing, view poses, and both
     * hands' input as four lines. Saves three guest-side TCP round trips every frame. */
    private var frameCount = 0
    private var frameCountStartMs = 0L

    private var handlerMs = 0L

    private fun frameSync(): String {
        val handlerStart = System.nanoTime()
        val frame = waitFrame()
        if (!frame.startsWith("OK")) return frame
        handlerMs += (System.nanoTime() - handlerStart) / 1_000_000
        val now = System.currentTimeMillis()
        if (frameCountStartMs == 0L) frameCountStartMs = now
        frameCount++
        if (now - frameCountStartMs >= 5000) {
            val fps = frameCount * 1000f / (now - frameCountStartMs)
            timber.log.Timber.i(
                "Windows VR game frame rate: %.1f fps (server wait avg %.1f ms)",
                fps,
                handlerMs.toFloat() / frameCount,
            )
            frameCount = 0
            handlerMs = 0
            frameCountStartMs = now
        }
        return frame + "\n" + locateViews() + "\n" +
            getInput(listOf("GET_INPUT", "hand=0")) + "\n" +
            getInput(listOf("GET_INPUT", "hand=1"))
    }

    private fun waitFrame(): String {
        val snapshot = snapshots.waitFrame(lastFrameSerial, 1000) ?: return "ERROR timeout"
        lastFrameSerial = snapshot.timing[0]
        if (!trackingSpaceRecorded) {
            trackingSpaceRecorded = true
            diagnostics.record(
                "tracking",
                "guestSpace=${if (snapshot.timing[10] != 0L) "STAGE" else "LOCAL-fallback"} " +
                    "bounds=${if (snapshot.timing[7] != 0L) "${snapshot.timing[8]}x${snapshot.timing[9]}um" else "unavailable"}",
            )
        }
        return "OK serial=${snapshot.timing[0]} time=${snapshot.timing[1]} period=${snapshot.timing[2]} shouldRender=${snapshot.timing[4]} state=${snapshot.timing[3]} render=${snapshot.timing[4]} recenter=${snapshot.timing[11]}"
    }

    private fun currentSnapshot(): WindowsVrRuntimeSnapshot? {
        return snapshots.latest() ?: snapshots.waitFrame(0, 1000)?.also { lastFrameSerial = it.timing[0] }
    }

    private fun getViews(): String {
        val snapshot = currentSnapshot() ?: return "ERROR unavailable"
        val scale = config.renderScalePercent.toLong()
        val scaledWidth = (snapshot.timing[5] * scale / 100) and 1L.inv()
        val scaledHeight = (snapshot.timing[6] * scale / 100) and 1L.inv()
        return "OK count=2 width=$scaledWidth height=$scaledHeight"
    }

    private fun getBounds(): String {
        val snapshot = currentSnapshot() ?: return "ERROR unavailable"
        return "OK available=${snapshot.timing[7]} width=${snapshot.timing[8]} height=${snapshot.timing[9]} supported=${snapshot.timing[10]}"
    }

    private fun locateViews(): String {
        val snapshot = snapshots.latest() ?: return "ERROR unavailable"
        val rawValues = buildList(22) {
            for (eye in 0 until 2) {
                for (field in 0 until 11) {
                    add((snapshot.views[eye * 11 + field] * 1_000_000f).toLong())
                }
            }
        }.joinToString(" ")
        val namedValues = buildList(22) {
            for (eye in 0 until 2) {
                val prefix = if (eye == 0) "l" else "r"
                for (field in viewFields.indices) {
                    add("$prefix${viewFields[field]}=${(snapshot.views[eye * 11 + field] * 1_000_000f).toLong()}")
                }
            }
        }.joinToString(" ")
        return "OK flags=${snapshot.flags[0]} $rawValues $namedValues"
    }

    private fun getInput(tokens: List<String>): String {
        if (tokens.size != 2 || tokens[1] !in setOf("hand=0", "hand=1")) return "ERROR malformed"
        val snapshot = snapshots.latest() ?: return "ERROR unavailable"
        val hand = tokens[1].last().digitToInt()
        val base = hand * 18
        val rawValues = (0 until 18).joinToString(" ") { field ->
            (snapshot.input[base + field] * 1_000_000f).toLong().toString()
        }
        val namedValues = inputFields.indices.joinToString(" ") { field ->
            "${inputFields[field]}=${(snapshot.input[base + field] * 1_000_000f).toLong()}"
        }
        val active = (snapshot.flags[2] shr hand) and 1
        return "OK active=$active buttons=${handButtons(snapshot.flags[1], hand)} $rawValues $namedValues"
    }

    private fun handButtons(buttons: Int, hand: Int): Int {
        val sourceBits = if (hand == 0) intArrayOf(2, 3, 8, 7) else intArrayOf(0, 1, 9, -1)
        var packed = 0
        sourceBits.forEachIndexed { index, bit ->
            if (bit >= 0 && buttons and (1 shl bit) != 0) packed = packed or (1 shl index)
        }
        return packed
    }

    private fun haptic(tokens: List<String>): String {
        if (tokens.size != 5) return "ERROR malformed"
        val values = tokens.drop(1).associate { token ->
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return "ERROR malformed"
            token.substring(0, separator) to token.substring(separator + 1)
        }
        if (values.keys != setOf("hand", "amp", "dur", "freq")) return "ERROR malformed"
        val hand = values["hand"]?.toIntOrNull() ?: return "ERROR malformed"
        val amplitude = values["amp"]?.toIntOrNull() ?: return "ERROR malformed"
        val duration = values["dur"]?.toLongOrNull() ?: return "ERROR malformed"
        val frequency = values["freq"]?.toIntOrNull() ?: return "ERROR malformed"
        if (hand !in 0..1 || amplitude !in 0..1_000_000 || duration !in 0..5_000_000_000L ||
            frequency !in 0..1_000_000_000) return "ERROR malformed"
        return if (snapshots.applyHaptic(
                hand,
                amplitude / 1_000_000f,
                duration,
                frequency / 1_000_000f,
            )) {
            diagnostics.record("haptic", "hand=$hand")
            "OK"
        } else {
            diagnostics.record("haptic", "unavailable hand=$hand")
            "ERROR unavailable"
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
        diagnostics.record("control", "stopped")
    }

    private companion object {
        val viewFields = arrayOf("qx", "qy", "qz", "qw", "px", "py", "pz", "fl", "fr", "fu", "fd")
        val inputFields = arrayOf(
            "tr", "sq", "sx", "sy", "gqx", "gqy", "gqz", "gqw", "gpx", "gpy", "gpz",
            "aqx", "aqy", "aqz", "aqw", "apx", "apy", "apz",
        )
    }
}
