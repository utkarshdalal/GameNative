// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox

import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/** MSA tokens the runtime stand-in exchanges itself for Xbox Live user and title tokens. */
data class RuntimeMsaToken(val token: String, val expiry: Long, val deviceRps: String, val deviceExpiry: Long)

fun interface RuntimeMsaTokenProvider {
    suspend fun msaToken(clientId: String, fullTrust: Boolean): RuntimeMsaToken?
}

/**
 * Loopback endpoint the runtime stand-in (xgameruntime.dll inside the game) connects to for account tokens.
 * Frames are `u32 magic | u16 type | u16 size | payload`, little endian. The first frame must be HELLO carrying
 * the per-launch session secret written to xboxhost.ini; anything else closes the connection.
 */
class XboxRuntimeServer(private val tokens: RuntimeMsaTokenProvider) {
    val session: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile private var server: ServerSocket? = null
    private val clients = mutableSetOf<Socket>()

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({ acceptLoop(socket) }, "xbox-runtime-accept").apply { isDaemon = true }.start()
        Timber.i("Xbox runtime server listening on %d", socket.localPort)
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            }
            synchronized(clients) { clients.add(client) }
            Thread({ serve(client) }, "xbox-runtime-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            try {
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                val hello = readFrame(input)
                if (hello.type != HELLO || !MessageDigest.isEqual(hello.payload, session.toByteArray())) {
                    Timber.w("Xbox runtime: rejected a connection without the session secret")
                    return
                }
                writeFrame(output, HELLO + 1, ByteArray(0))
                while (true) {
                    val frame = readFrame(input)
                    val reply = handle(frame)
                    writeFrame(output, frame.type + 1, reply)
                }
            } catch (_: EOFException) {
            } catch (_: SocketException) {
            } catch (e: Exception) {
                Timber.w(e, "Xbox runtime: connection ended")
            } finally {
                synchronized(clients) { clients.remove(client) }
            }
        }
    }

    private fun handle(frame: Frame): ByteArray = when (frame.type) {
        PING -> frame.payload
        MSA_TOKEN_REQUEST -> {
            val request = String(frame.payload, Charsets.UTF_8)
            val clientId = element(request, "ClientId").orEmpty()
            val fullTrust = (element(request, "MSAFullTrust") ?: element(request, "MsaFullTrust")).toBoolean()
            val token = if (clientId.isEmpty()) {
                null
            } else {
                runCatching { runBlocking { tokens.msaToken(clientId, fullTrust) } }
                    .onFailure { Timber.w(it, "Xbox runtime: MSA token for %s failed", clientId) }
                    .getOrNull()
            }
            token?.let { encodeToken(it).toByteArray(Charsets.UTF_8) } ?: ByteArray(0)
        }
        else -> ByteArray(0)
    }

    private class Frame(val type: Int, val payload: ByteArray)

    private fun readFrame(input: DataInputStream): Frame {
        val header = ByteArray(8)
        input.readFully(header)
        val magic = le32(header, 0)
        if (magic != MAGIC) throw SocketException("bad frame magic")
        val type = le16(header, 4)
        val size = le16(header, 6)
        val payload = ByteArray(size)
        input.readFully(payload)
        return Frame(type, payload)
    }

    private fun writeFrame(output: OutputStream, type: Int, payload: ByteArray) {
        require(payload.size <= 0xFFFF) { "frame too large" }
        val header = ByteArray(8)
        putLe32(header, 0, MAGIC)
        header[4] = type.toByte(); header[5] = (type ushr 8).toByte()
        header[6] = payload.size.toByte(); header[7] = (payload.size ushr 8).toByte()
        synchronized(output) {
            output.write(header)
            output.write(payload)
            output.flush()
        }
    }

    companion object {
        const val MAGIC = 0x58445358
        const val PING = 1
        const val MSA_TOKEN_REQUEST = 3
        const val HELLO = 0x0100

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

        private fun putLe32(b: ByteArray, o: Int, v: Int) {
            for (i in 0..3) b[o + i] = (v ushr (8 * i)).toByte()
        }

        internal fun element(xml: String, name: String): String? =
            Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.let(::unescape)

        internal fun encodeToken(t: RuntimeMsaToken): String =
            "<MSATokenResponse><Token>${escape(t.token)}</Token><Expiry>${t.expiry}</Expiry>" +
                "<DeviceRps>${escape(t.deviceRps)}</DeviceRps><DeviceExpiry>${t.deviceExpiry}</DeviceExpiry></MSATokenResponse>"

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
