package app.gamenative.service.ubisoft

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import timber.log.Timber

/** An authenticated demux session that can open services and exchange their messages. */
interface UbisoftDemuxConnection : Closeable {
    /** Opens [service] and returns the connection id the server assigned. */
    fun openService(service: String): Long

    /** Sends one service message on [connectionId] and returns the next message the service sends back. */
    fun exchange(connectionId: Long, message: ByteArray): ByteArray
}

/** Demux over a TLS socket: length-prefixed messages, one request in flight at a time. */
class UbisoftDemuxClient internal constructor(
    input: InputStream,
    private val output: OutputStream,
    private val closer: Closeable,
) : UbisoftDemuxConnection {
    private val input = DataInputStream(input)
    private var nextRequestId = 1L

    /** Announces the client version and authenticates with the session ticket. */
    internal fun handshake(clientVersion: Long, clientId: String, ticket: String) {
        send(UbisoftDemuxProto.clientVersion(clientVersion))
        val rsp = request({ id -> UbisoftDemuxProto.authenticate(id, ticket, clientId) }, "authenticate")
        val auth = UbisoftDemuxProto.authenticateResult(rsp)
        when {
            !auth.present -> throw UbisoftCloudException("demux authenticate: no answer")
            auth.banned -> throw UbisoftCloudException("demux authenticate: account refused", 403)
            auth.expired -> throw UbisoftCloudException("demux authenticate: session expired", 401)
            !auth.success -> throw UbisoftCloudException("demux authenticate: rejected", 401)
        }
        Timber.i("Ubisoft demux authenticated")
    }

    override fun openService(service: String): Long {
        val rsp = request({ id -> UbisoftDemuxProto.openConnection(id, service) }, "open $service")
        return UbisoftDemuxProto.openConnectionResult(rsp)
            ?: throw UbisoftCloudException("demux: $service could not be opened")
    }

    override fun exchange(connectionId: Long, message: ByteArray): ByteArray {
        send(UbisoftDemuxProto.data(connectionId, message))
        repeat(MAX_SKIPPED) {
            val down = UbisoftDemuxProto.parseDown(receive())
            if (down.outdated) throw UbisoftCloudException("demux: client version refused")
            if (down.dataConnection == connectionId) {
                return down.data ?: throw UbisoftCloudException("demux: malformed service message")
            }
            if (down.closedConnection == connectionId) throw UbisoftCloudException("demux: service connection closed")
        }
        throw UbisoftCloudException("demux: no answer from the service")
    }

    private fun request(build: (Long) -> ByteArray, what: String): UbisoftDemuxProto.Message {
        val id = nextRequestId++
        send(build(id))
        repeat(MAX_SKIPPED) {
            val down = UbisoftDemuxProto.parseDown(receive())
            if (down.outdated) throw UbisoftCloudException("demux: client version refused")
            if (down.response != null && down.requestId == id) return down.response
        }
        throw UbisoftCloudException("demux: no answer to $what")
    }

    private fun send(message: ByteArray) {
        output.write(UbisoftDemuxProto.frame(message))
        output.flush()
    }

    private fun receive(): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        val length = UbisoftDemuxProto.frameLength(header)
        if (length > MAX_MESSAGE_BYTES) throw UbisoftCloudException("demux: message exceeds size limit")
        return ByteArray(length.toInt()).also { input.readFully(it) }
    }

    override fun close() {
        runCatching { closer.close() }
    }

    companion object {
        private const val MAX_SKIPPED = 256
        private const val MAX_MESSAGE_BYTES = 64L * 1024 * 1024
        private const val TIMEOUT_MS = 15_000

        /** Opens the TLS connection (TLS 1.2 only, certificate and host name verified) and authenticates. */
        fun connect(host: String, port: Int, clientVersion: Long, clientId: String, ticket: String): UbisoftDemuxClient {
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                raw.soTimeout = TIMEOUT_MS
                val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                socket.enabledProtocols = arrayOf("TLSv1.2")
                socket.soTimeout = TIMEOUT_MS
                socket.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) {
                    throw SSLPeerUnverifiedException("demux certificate does not match the host")
                }
                val client = UbisoftDemuxClient(socket.inputStream.buffered(), socket.outputStream.buffered(), socket)
                try {
                    client.handshake(clientVersion, clientId, ticket)
                } catch (e: Exception) {
                    client.close()
                    throw e
                }
                return client
            } catch (e: UbisoftCloudException) {
                runCatching { raw.close() }
                throw e
            } catch (e: IOException) {
                runCatching { raw.close() }
                throw UbisoftCloudException("demux connection failed: ${e.javaClass.simpleName}", cause = e)
            } catch (e: RuntimeException) {
                runCatching { raw.close() }
                throw e
            }
        }
    }
}
