package app.gamenative.steamcontroller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/**
 * Direct Bluetooth-LE transport for the 2026 Steam Controller — the no-dongle, no-root path
 * (docs/BLE-GATT-PATH.md). Finds the controller among BONDED devices only (never scans — see [start]), connects GATT,
 * **un-lizards it by writing settings to the control characteristic** (the BLE analog of USB's lizard-off —
 * required because Android grabs the controller as a system HID, leaving it in lizard mode), subscribes to
 * the input characteristic(s), and emits decoded [TritonState]s. A 2 s heartbeat re-sends lizard-off
 * (firmware watchdog re-enables it). Requires the runtime BT permissions
 * ([REQUIRED_PERMISSIONS]): [start] refuses to touch a Bluetooth API without them, and the calls that can still
 * throw if the grant is revoked mid-session are contained locally instead of escaping to the game-launch path.
 *
 * GATT ops are serialized through [opQueue] because Android allows only one outstanding op at a time.
 */
// Lint can't see through the hasPermissions() gate in start(), so every BT call below reads as unguarded to it.
@SuppressLint("MissingPermission")
class TritonBle(private val context: Context) {

    companion object {
        private const val TAG = "TritonBle"
        val SERVICE_UUID: UUID = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3")
        /** READ|WRITE control/report characteristic — feature reports (lizard, etc.) go here. */
        val CONTROL_UUID: UUID = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3")
        /** Triton input characteristics: 6c7a = report 0x45, 6c7c = report 0x47 (per SDL's Android bridge). */
        val INPUT_TRITON_45: UUID = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
        val INPUT_TRITON_47: UUID = UUID.fromString("100F6C7C-1735-4313-B402-38567131E5F3")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // The 45-byte Triton report exceeds the default 23-byte BLE MTU, so it can't be delivered until we
        // request a large MTU (Data Length Extensions). 517 is Android's "enable DLE" magic value (per SDL).
        private const val TRITON_MTU = 517
        // Resend lizard-off this often; the firmware watchdog re-enables it within ~3 s.
        private const val LIZARD_HEARTBEAT_MS = 2000L

        /** Runtime permissions this transport needs. API 31+ split BT perms; <=30 needs neither (manifest-only). */
        val REQUIRED_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // CONNECT only: we never scan, so BLUETOOTH_SCAN would be a permission we ask for and never use.
                arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyArray()
            }

        /**
         * True when every [REQUIRED_PERMISSIONS] entry is granted. Declaring them in the manifest is NOT enough on
         * API 31+: without the grant, `bondedDevices` and `connectGatt` both throw SecurityException.
         * The Steam-Controller setting requests them; the launch path refuses to start the transport without them.
         */
        fun hasPermissions(context: Context): Boolean = REQUIRED_PERMISSIONS.all {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = manager.adapter
    private val handler = Handler(context.mainLooper)

    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    // Output-report characteristics keyed by report id (Triton: id = charByte - 0x35, for ids >= 0x80).
    // Used to route USB-style output reports (haptics 0x82, etc.) to the right BLE char.
    private val outputReportChars = HashMap<Int, BluetoothGattCharacteristic>()
    @Volatile private var inputUuid: UUID? = null
    @Volatile private var closed = false

    private val opQueue = ArrayDeque<() -> Unit>()
    private var opBusy = false
    private var opGen = 0
    private var readyFired = false
    private var rawNotifyCount = 0

    private var onState: ((TritonState) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onReady: (() -> Unit)? = null

    /** Optional raw-report sink (the exact bytes off the input characteristic) for golden-trace capture. */
    @Volatile var onRaw: ((ByteArray) -> Unit)? = null

    fun start(onState: (TritonState) -> Unit, onReady: () -> Unit, onError: (String) -> Unit) {
        this.onState = onState
        this.onReady = onReady
        this.onError = onError

        if (adapter == null) { fail("This device has no Bluetooth adapter."); return }
        if (!adapter.isEnabled) { fail("Bluetooth is OFF — turn it on and retry."); return }
        // Never reach the BluetoothAdapter/GATT APIs ungranted: every one of them throws SecurityException on
        // API 31+, and this runs off the game-launch path where an uncaught throw kills the launch.
        if (!hasPermissions(context)) { fail("Bluetooth permission not granted (Nearby devices)."); return }

        // Bonded devices ONLY. An advertised name or service UUID is not authentication — both are trivially
        // spoofable, so scanning and connecting to whatever claims to be a Steam Controller would let any
        // nearby device inject keystrokes and mouse movement into the game. A bond is a real, user-driven
        // trust decision, so we require one and never scan. Pair the controller in Android's Bluetooth
        // settings first; that is the normal flow for a Bluetooth device anyway.
        val bonded = runCatching {
            adapter.bondedDevices?.firstOrNull { d ->
                val n = d.name ?: ""
                // Match the Steam Controller specifically — "Controller" alone also matches an Xbox pad.
                n.contains("Steam", true) || n.contains("Valve", true)
            }
        }.getOrNull()
        if (bonded == null) {
            fail("No paired Steam Controller. Pair it in Android's Bluetooth settings, then try again.")
            return
        }
        Log.i(TAG, "found bonded controller: ${bonded.name} ${bonded.address}")
        connect(bonded)
    }

    private fun connect(device: BluetoothDevice) {
        gatt = runCatching { device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { fail("BLE connect failed: ${it.message}") }
            .getOrNull()
    }

    @Volatile private var discoverStarted = false
    private fun discoverOnce(g: BluetoothGatt) {
        if (discoverStarted) return
        discoverStarted = true
        g.discoverServices()
    }

    // ---- GATT operation queue (one outstanding op at a time) ----
    private fun enqueue(op: () -> Unit) {
        synchronized(opQueue) { opQueue.add(op) }
        processNext()
    }

    private fun processNext() {
        val op: (() -> Unit)?
        val gen: Int
        synchronized(opQueue) {
            if (opBusy) return
            op = opQueue.poll()
            if (op == null) {
                if (!readyFired) { readyFired = true; handler.post { onReady?.invoke(); startLizardHeartbeat() } }
                return
            }
            opBusy = true
            gen = ++opGen
        }
        // Watchdog: if a GATT op never calls back (e.g. a system-owned characteristic), force the queue on.
        handler.postDelayed({
            synchronized(opQueue) { if (!opBusy || opGen != gen) return@postDelayed }
            Log.w(TAG, "op timed out — advancing queue")
            opDone()
        }, 1500)
        op?.invoke()
    }

    private fun opDone() {
        synchronized(opQueue) { opBusy = false }
        processNext()
    }

    private fun enableNotify(ch: BluetoothGattCharacteristic) {
        val g = gatt ?: return opDone()
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: run { Log.w(TAG, "no CCCD on ${ch.uuid}"); return opDone() }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
    }

    private fun writeControl(bytes: ByteArray) {
        val ch = control ?: return opDone()
        writeTo(ch, bytes, noResponse = false)
    }

    private fun writeTo(ch: BluetoothGattCharacteristic, bytes: ByteArray, noResponse: Boolean) {
        val g = gatt ?: return opDone()
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, type)
        } else {
            ch.value = bytes
            ch.writeType = type
            g.writeCharacteristic(ch)
        }
    }

    /**
     * Route a USB-style output report (`[id, payload...]`) to its BLE characteristic — the id selects the char
     * (and is dropped over the air; the char encodes it), so only the payload is written. Used for haptics
     * (report 0x82 → 6CB7). No-op until the output chars are discovered. Uses WRITE_NO_RESPONSE so frequent
     * detent ticks stay cheap. Serialized through [opQueue] like every other GATT op.
     */
    fun writeOutputReport(report: ByteArray) {
        if (closed || report.isEmpty()) return
        val reportId = report[0].toInt() and 0xFF
        val ch = outputReportChars[reportId] ?: return
        val payload = report.copyOfRange(1, report.size)
        enqueue { writeTo(ch, payload, noResponse = true) }
    }

    @Volatile private var heartbeatStarted = false
    private fun startLizardHeartbeat() {
        if (heartbeatStarted || control == null) return
        heartbeatStarted = true
        val r = object : Runnable {
            override fun run() {
                if (closed) return
                enqueue { writeControl(TritonProtocol.bleLizardOff()) }
                handler.postDelayed(this, LIZARD_HEARTBEAT_MS)
            }
        }
        handler.postDelayed(r, LIZARD_HEARTBEAT_MS)
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "connected (status=$status); requesting high priority + MTU $TRITON_MTU")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // MUST raise the MTU before service discovery, or the 45-byte report can't be delivered.
                if (!g.requestMtu(TRITON_MTU)) {
                    Log.w(TAG, "requestMtu returned false; discovering anyway")
                    discoverOnce(g)
                }
                // Safety: if onMtuChanged never fires, discover anyway after a short delay.
                handler.postDelayed({ if (!closed) discoverOnce(g) }, 1500)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (!closed) fail("BLE disconnected (status=$status).")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiated = $mtu (status=$status)")
            discoverOnce(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            for (service in g.services) {
                Log.i(TAG, "service ${service.uuid}")
                for (ch in service.characteristics) {
                    Log.i(TAG, "  char ${ch.uuid} props=${propStr(ch.properties)}")
                }
            }
            val svc = g.getService(SERVICE_UUID)
                ?: run { fail("Connected, but the controller GATT service ($SERVICE_UUID) was not found."); return }
            control = svc.getCharacteristic(CONTROL_UUID)

            // Map output-report characteristics so haptics (report 0x82) etc. can be written over BLE: Triton
            // exposes each output report id >= 0x80 as a dedicated char at (id + 0x35), e.g. 0x82 -> 6CB7.
            outputReportChars.clear()
            for (ch in svc.characteristics) {
                val idByte = runCatching { ch.uuid.toString().substring(6, 8).toInt(16) }.getOrNull() ?: continue
                val reportId = idByte - 0x35
                if (reportId in 0x80..0xFF) outputReportChars[reportId] = ch
            }
            Log.i(TAG, "output report chars: ${outputReportChars.keys.map { "0x%02x".format(it) }}")

            // Triton needs NO un-lizard to stream — just subscribe to the input characteristic (6c7a=report
            // 0x45, else 6c7c=0x47). The full 45-byte report flows once the MTU is large enough (set above).
            val input = svc.getCharacteristic(INPUT_TRITON_45) ?: svc.getCharacteristic(INPUT_TRITON_47)
            if (input == null) {
                fail("Triton input characteristic (6c7a/6c7c) not found on the controller service."); return
            }
            inputUuid = input.uuid
            Log.i(TAG, "subscribing to Triton input ${input.uuid}")
            enqueue { enableNotify(input) }
            // Suppress lizard mode (mouse/keyboard emulation) so the cursor doesn't wander during gameplay.
            // This does NOT stop the full report on 6c7a — only the HID emulation. Heartbeat keeps it off.
            if (control != null) enqueue { writeControl(TritonProtocol.bleLizardOff()) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                Log.i(TAG, "notify enabled on ${descriptor.characteristic.uuid} (status=$status)")
            }
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.i(TAG, "control write done on ${characteristic.uuid} status=$status")
            opDone()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleReport(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { handleReport(characteristic, it) }
            }
        }
    }

    private val lastPayload = HashMap<String, String>()
    private var changeLogCount = 0

    private fun handleReport(ch: BluetoothGattCharacteristic, value: ByteArray) {
        // Log on-CHANGE per characteristic so a button press reveals which channel carries the input
        // (without flooding from steady-state streams). Key by uuid-tail + instanceId.
        val key = "${ch.uuid.toString().substring(4, 8)}#${ch.instanceId}"
        val hex = value.joinToString("") { "%02x".format(it) }
        val prev = lastPayload.put(key, hex)
        if (prev != hex && changeLogCount < 140) {
            changeLogCount++
            val shown = value.take(28).joinToString(" ") { "%02x".format(it) }
            Log.i(TAG, "CHG $key len=${value.size} [$shown]")
        }
        rawNotifyCount++
        onRaw?.invoke(value)
        // The Triton input characteristic delivers the prefix-less 45-byte report (seq at offset 0).
        val state = TritonProtocol.decodeBleState(value, value.size)
        if (state != null) onState?.invoke(state)
    }

    private fun propStr(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }.joinToString("|").ifEmpty { "none" }

    private fun fail(reason: String) {
        if (closed) return
        Log.w(TAG, "fail: $reason")
        val cb = onError
        onError = null
        cb?.invoke(reason)
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null) // stop the lizard heartbeat + any pending discover
        // Tear down the ACL link before releasing the client, or the controller stays connected at the OS level
        // ("not released" after a game exits). disconnect() then close() is the documented clean teardown.
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; control = null
        outputReportChars.clear()
        synchronized(opQueue) { opQueue.clear(); opBusy = false }
        onState = null; onError = null; onReady = null
    }
}
