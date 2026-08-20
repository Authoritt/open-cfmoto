// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.zanderp.opencfmoto.EcBtpProtocol
import java.util.UUID

/**
 * Hands the phone-hosted Wi-Fi credentials to a Carbit `action=128` dash (Rieju Aventure 500) over BLE,
 * so the dash can join the phone's Wi-Fi Direct group without the rider typing anything. Reverse-engineered
 * from `net.easyconn.carman.wws` v6.4.0 (design doc `2026-08-18-bike-connection-factory-design.md`,
 * Appendix A — bytecode-proven).
 *
 * Reuses [dev.zanderp.opencfmoto.BleWakeUp]'s GATT scaffolding pattern (connect → `requestMtu(185)` →
 * discoverServices → `setCharacteristicNotification` + CCCD `2902`) but is deliberately a **separate class**:
 * the CFMOTO wake-up speaks a different wire protocol (BleProtocol `AB CD` framing + AES challenge) on a
 * different service (`B354`), so entangling the two would only add risk to a proven path. This class instead
 * speaks the **EcBtp** framing (`0x24 | cmd | len | payload | xor | 0x0A`, see [EcBtpProtocol]) on the
 * B36x service, and differs from the wake-up in three ways:
 *  1. **Connect by MAC** (`spec.bleMac`, the QR `bm=`) — no scan; the official app knows the MAC from the QR.
 *  2. **Characteristics by GATT property, not fixed UUID** — the WRITE-capable char for writes, the
 *     NOTIFY/INDICATE-capable char for notifications. This absorbs the three observed layouts without
 *     hardcoding UUIDs: V3 (one `B364` with WRITE+NOTIFY), V2 (write `B363`, notify `B364`), V1 (by property
 *     among the `B362` set).
 *  3. **A fixed 3-write handshake** then await the dash's reply: `0x30 CLIENT_INFO` → `0x50 REQUEST_BUILD_NET`
 *     → `0x52 NOTIFY_AP_INFO` (the Gson creds body, built by [EcBtpApInfo]) → await `0x51`
 *     `NOTIFY_BUILD_NET_FINISH` / `0x53 NOTIFY_CAR_NET_INFO`.
 *
 * Device-gated unknowns (design §11, one-tap BT-HCI snoop confirms): the exact GATT write **type**
 * (with/without response) — resolved here by property, defaulting to with-response when the char advertises
 * `WRITE` — and whether a non-Carbit-branded dash keeps `B360` or remaps the short id (absorbed by the
 * caller passing `spec.bleServiceOverride`).
 *
 * @param context any [Context]; only the [BluetoothManager] system service is used.
 * @param log verbose sink (matches `BleWakeUp`'s "one logged session reveals what happened" discipline).
 */
@SuppressLint("MissingPermission")
class BleApInfoPush(
    private val context: Context,
    private val log: (String) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var done = false
    @Volatile private var pendingService: UUID = DEFAULT_SERVICE_UUID // set per-call in pushApInfo
    @Volatile private var resume: ((Boolean) -> Unit)? = null
    private var notifyAcc = ByteArray(0)

    /** Where we are in the fixed write handshake; advanced from each `onCharacteristicWrite` ack. */
    private enum class Step { CLIENT_INFO, REQUEST_BUILD, AP_INFO, AWAIT_ACK }
    private var step = Step.CLIENT_INFO

    // Resolved from the service once discovered, by GATT property (not fixed UUID).
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    // The three frames, captured once so the callback thread can advance the handshake without recomputing.
    private lateinit var frameClientInfo: ByteArray
    private lateinit var frameRequestBuild: ByteArray
    private lateinit var frameApInfo: ByteArray

    /**
     * Connect to [mac], resolve chars on [service] by property, and run the AP-info handshake. Suspends until
     * the dash acknowledges (`0x51`/`0x53` → `true`) or the attempt fails/times out (`false`). Never throws:
     * every failure path resolves to `false` so the caller can fall back to the manual flow. On success the
     * GATT is kept **open** (the dash sends `0x51`/`0x53` after joining, and dashes may rely on the BLE link
     * staying up for the session) — the caller owns teardown via [close]; on any failure it is closed here.
     */
    suspend fun pushApInfo(
        mac: String,
        service: UUID,
        ssid: String,
        pwd: String,
        ip: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { finishFailure("cancelled") }
        this.resume = { ok -> if (cont.isActive) cont.resumeWith(Result.success(ok)) }
        pendingService = service

        frameClientInfo = EcBtpProtocol.build(EcBtpProtocol.CMD_EC_BTP_CLIENT_INFO.toByte(), ByteArray(0))
        frameRequestBuild = EcBtpProtocol.build(EcBtpProtocol.CMD_REQUEST_BUILD_NET.toByte(), ByteArray(0))
        frameApInfo = EcBtpApInfo.frame(ssid = ssid, pwd = pwd, ip = ip)
        log("[BLE-AP] pushApInfo mac=$mac service=$service ssid='$ssid' pwdLen=${pwd.length} ip=$ip")
        // CLIENT_INFO/REQUEST_BUILD have empty payloads (no secrets) — log verbatim. The 0x52 AP_INFO
        // payload embeds ssid+pwd in cleartext, so log only cmd/len (match the fallback's pwdLen hygiene).
        log("[BLE-AP] frames: CLIENT_INFO=${hex(frameClientInfo)} REQUEST_BUILD=${hex(frameRequestBuild)} " +
            "AP_INFO=cmd=0x52 len=${frameApInfo.size}B (payload redacted: embeds ssid+pwd)")

        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) { finishFailure("Bluetooth adapter unavailable/off"); return@suspendCancellableCoroutine }
        // Android validates the address with BluetoothAdapter.checkBluetoothAddress(), which demands
        // UPPERCASE hex — "dd:0d:30:16:6b:50" is rejected as "not a valid Bluetooth address" while the
        // identical "DD:0D:30:16:6B:50" is accepted. The QR carries it uppercase (bm=DD:0D:…); our own
        // MAC normalisation lowercases it, and that is what reached this call and killed the very first
        // real Rieju attempt AFTER the group had already formed and the frames were built.
        val btMac = mac.uppercase()
        val device = try {
            adapter.getRemoteDevice(btMac)
        } catch (e: Exception) {
            finishFailure("bad BLE MAC '$btMac': ${e.message}"); return@suspendCancellableCoroutine
        }

        handler.postDelayed({ if (!done) finishFailure("timeout after ${timeoutMs}ms") }, timeoutMs)

        // Find the dash before dialling it. A blind connect to the QR's address timed out on the real
        // Rieju with onConnectionStateChange never firing at all, and there are two known reasons why
        // that address can be the wrong door: (a) a connect to a never-scanned device often goes
        // nowhere, and (b) on Carbit units the BLE address can differ from the QR's `bm=` by one on the
        // last octet — the same Wi-Fi/BT offset BikeWifiP2p.macMatches already handles for the P2P join.
        // So scan first, accepting either the advertised B360 service or an address that matches exactly
        // or ±1, and dial whatever the scan actually saw. If the scan finds nothing we still try the
        // QR address directly, so this can only add reach, never take it away.
        scanThenConnect(adapter, device, btMac, service) { target, how ->
            log("[BLE-AP] connecting to ${target.address} ($how) …")
            connectGatt(target)
        }
        return@suspendCancellableCoroutine
    }

    /**
     * Scan up to [SCAN_MS] for the dash, then hand the winner to [onFound]. Falls back to [fallback]
     * (the QR's own address) when the scan sees nothing, so behaviour is never worse than before.
     */
    private fun scanThenConnect(
        adapter: android.bluetooth.BluetoothAdapter,
        fallback: android.bluetooth.BluetoothDevice,
        wantMac: String,
        service: UUID,
        onFound: (android.bluetooth.BluetoothDevice, String) -> Unit,
    ) {
        val scanner = try { adapter.bluetoothLeScanner } catch (_: Exception) { null }
        if (scanner == null) {
            log("[BLE-AP] no BLE scanner — dialling the QR address directly")
            onFound(fallback, "QR address, no scanner")
            return
        }
        val picked = java.util.concurrent.atomic.AtomicBoolean(false)
        // Everything the scan saw, so a failure says what WAS in the air instead of just "nothing".
        val seen = java.util.LinkedHashMap<String, String>()
        var cb: android.bluetooth.le.ScanCallback? = null
        fun stop() { cb?.let { c -> runCatching { scanner.stopScan(c) } }; cb = null }
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return
                val advertisesService = result.scanRecord?.serviceUuids
                    ?.any { it.uuid == service } == true
                if (seen.size < SCAN_LOG_CAP && !seen.containsKey(addr)) {
                    val nm = runCatching { dev.name }.getOrNull().orEmpty()
                    val svcs = result.scanRecord?.serviceUuids?.joinToString(",") { it.uuid.toString().take(8) }.orEmpty()
                    seen[addr] = "rssi=${result.rssi}" +
                        (if (nm.isNotBlank()) " name='" + nm + "'" else "") +
                        (if (svcs.isNotBlank()) " svc=[" + svcs + "]" else "")
                }
                val how = when {
                    addr.equals(wantMac, ignoreCase = true) -> "scanned, exact address"
                    macMatchesWithOffset(wantMac, addr) -> "scanned, address ±1 from the QR"
                    advertisesService -> "scanned, advertises the AP-info service"
                    else -> return
                }
                if (!picked.compareAndSet(false, true)) return
                stop()
                handler.post { if (!done) onFound(dev, how) }
            }

            override fun onScanFailed(errorCode: Int) {
                log("[BLE-AP] BLE scan failed ($errorCode) — dialling the QR address directly")
                if (picked.compareAndSet(false, true)) {
                    stop()
                    handler.post { if (!done) onFound(fallback, "QR address, scan failed") }
                }
            }
        }
        cb = callback
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val ok = runCatching {
            // Unfiltered on purpose: we must also catch the ±1 address and a dash that advertises the
            // service without matching the QR at all. The window is short and we stop on the first hit.
            scanner.startScan(null, settings, callback)
        }.isSuccess
        if (!ok) {
            log("[BLE-AP] could not start the BLE scan — dialling the QR address directly")
            if (picked.compareAndSet(false, true)) onFound(fallback, "QR address, scan unavailable")
            return
        }
        log("[BLE-AP] scanning ${SCAN_MS}ms for the dash (address $wantMac, ±1, or service $service) …")
        handler.postDelayed({
            if (picked.compareAndSet(false, true)) {
                stop()
                if (seen.isEmpty()) {
                    log("[BLE-AP] scan saw NOTHING at all in ${SCAN_MS}ms — is the dash on and in range? is its Bluetooth advertising?")
                } else {
                    log("[BLE-AP] scan saw ${seen.size} device(s), none matching $wantMac (±1) nor advertising $service:")
                    seen.forEach { (a, d) -> log("[BLE-AP]   seen $a $d") }
                }
                log("[BLE-AP] scan saw no matching dash — dialling the QR address anyway")
                if (!done) onFound(fallback, "QR address, scan found nothing")
            }
        }, SCAN_MS)
    }

    /** Exact, or the ±1 last-octet Wi-Fi/BT offset seen on Carbit units (see BikeWifiP2p.macMatches). */
    private fun macMatchesWithOffset(want: String, peer: String): Boolean {
        val w = want.filter { it.isLetterOrDigit() }.lowercase()
        val p = peer.filter { it.isLetterOrDigit() }.lowercase()
        if (w.length != 12 || p.length != 12) return false
        if (w == p) return true
        if (w.dropLast(2) != p.dropLast(2)) return false
        val a = w.takeLast(2).toIntOrNull(16) ?: return false
        val b = p.takeLast(2).toIntOrNull(16) ?: return false
        return kotlin.math.abs(a - b) == 1
    }

    private fun connectGatt(device: android.bluetooth.BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
        if (gatt == null) finishFailure("connectGatt returned null")
    }

    /** Tear down the GATT (idempotent). The caller invokes this from its transport `close()`. */
    fun close() {
        val g = gatt
        gatt = null
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
    }

    private fun finishSuccess() {
        if (done) return
        done = true
        log("[BLE-AP] *** dash acknowledged AP info — Wi-Fi handoff complete (GATT kept open) ***")
        resume?.invoke(true)
    }

    private fun finishFailure(reason: String) {
        if (done) return
        done = true
        log("[BLE-AP] FAILED: $reason")
        close()
        resume?.invoke(false)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("[BLE-AP] connState status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) { finishFailure("connectGatt status=$status"); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { log("[BLE-AP] connected; discovering services"); g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> if (!done) finishFailure("disconnected mid-handshake")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { finishFailure("svc discovery status=$status"); return }
            log("[BLE-AP] services discovered; requesting MTU 185 (matches official app)")
            if (!g.requestMtu(185)) finishFailure("requestMtu failed")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("[BLE-AP] MTU = $mtu (status=$status)")
            if (!resolveChars(g)) return
            enableNotifications(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) { finishFailure("CCCD write status=$status"); return }
            log("[BLE-AP] notifications enabled; starting handshake -> 0x30 CLIENT_INFO")
            step = Step.CLIENT_INFO
            writeFrame(g, frameClientInfo)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { finishFailure("write status=$status at step=$step"); return }
            when (step) {
                Step.CLIENT_INFO -> { step = Step.REQUEST_BUILD; log("[BLE-AP] -> 0x50 REQUEST_BUILD_NET"); writeFrame(g, frameRequestBuild) }
                Step.REQUEST_BUILD -> { step = Step.AP_INFO; log("[BLE-AP] -> 0x52 NOTIFY_AP_INFO"); writeFrame(g, frameApInfo) }
                Step.AP_INFO -> { step = Step.AWAIT_ACK; log("[BLE-AP] AP_INFO sent; awaiting 0x51/0x53 from dash") }
                Step.AWAIT_ACK -> {}
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleNotification(c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }
    }

    /** Pick the WRITE and NOTIFY characteristics by GATT property (V3 single char / V2 split / V1 by property). */
    private fun resolveChars(g: BluetoothGatt): Boolean {
        val svc = g.getService(pendingService) ?: run { finishFailure("service $pendingService not present"); return false }
        val chars = svc.characteristics
        log("[BLE-AP] service $pendingService chars=${chars.map { "${shortId(it.uuid)}:0x${"%02x".format(it.properties)}" }}")
        val write = chars.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }
        val notify = chars.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }
        if (write == null) { finishFailure("no WRITE-capable characteristic on $pendingService"); return false }
        if (notify == null) { finishFailure("no NOTIFY-capable characteristic on $pendingService"); return false }
        writeChar = write
        notifyChar = notify
        writeType = if (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        log("[BLE-AP] write=${shortId(write.uuid)} (type=${if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) "with-resp" else "no-resp"}) notify=${shortId(notify.uuid)}")
        return true
    }

    private fun enableNotifications(g: BluetoothGatt) {
        val notify = notifyChar ?: run { finishFailure("notify char unresolved"); return }
        if (!g.setCharacteristicNotification(notify, true)) { finishFailure("setCharacteristicNotification(true) returned false"); return }
        val cccd = notify.getDescriptor(CCCD_UUID) ?: run { finishFailure("CCCD descriptor not present"); return }
        val enable = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = enable; g.writeDescriptor(cccd) }
        }
        if (!ok) finishFailure("writeDescriptor (enable notify) failed")
    }

    private fun writeFrame(g: BluetoothGatt, frame: ByteArray) {
        val w = writeChar ?: run { finishFailure("write char unresolved"); return }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(w, frame, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { w.value = frame; w.writeType = writeType; g.writeCharacteristic(w) }
        }
        if (!ok) finishFailure("writeCharacteristic submit failed at step=$step")
    }

    /** Accumulate BLE chunks and dispatch complete EcBtp frames; a `0x51`/`0x53` reply ends the handshake. */
    private fun handleNotification(data: ByteArray) {
        log("[BLE-AP] <- raw ${hex(data)}") // the owner's ONLY receive-path signal — keep the raw bytes
        val batch = extractFrames(notifyAcc, data)
        notifyAcc = batch.remainder
        for (region in batch.dropped) log("[BLE-AP] <- dropped malformed ${hex(region)}")
        log("[BLE-AP] <- chunk(${data.size}) frames=${batch.frames.size} dropped=${batch.dropped.size} buffered=${notifyAcc.size}B")
        for (f in batch.frames) onEcBtpFrame(f.command)
    }

    private fun onEcBtpFrame(cmd: Byte) {
        val c = cmd.toInt() and 0xFF
        log("[BLE-AP] <- EcBtp cmd=0x${"%02x".format(c)}")
        when (c) {
            EcBtpProtocol.CMD_NOTIFY_BUILD_NET_FINISH, EcBtpProtocol.CMD_NOTIFY_CAR_NET_INFO -> finishSuccess()
            else -> log("[BLE-AP] (ignoring cmd 0x${"%02x".format(c)} while awaiting 0x51/0x53)")
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
    private fun shortId(u: UUID): String = u.toString().substring(4, 8).uppercase()

    companion object {
        /** Carbit-generic B36x service (design Appendix A). Overridable per-bike via `spec.bleServiceOverride`. */
        val DEFAULT_SERVICE_UUID: UUID = UUID.fromString("0000B360-D6D8-C7EC-BDF0-EAB1BFC6BCBC")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val DEFAULT_TIMEOUT_MS = 25_000L

        /** How long we look for the dash before dialling the QR address blind. */
        private const val SCAN_MS = 6_000L

        /** Cap on the 'what else is advertising' diagnostic so one crowded street can't flood the log. */
        private const val SCAN_LOG_CAP = 12

        /** EcBtp frame overhead: `START | cmd | len | … | xor | END` = 5 bytes around the payload. */
        private const val FRAME_OVERHEAD = 5

        /**
         * Result of [extractFrames]: complete EcBtp [frames] pulled out, the still-incomplete [remainder]
         * kept for the next chunk, and any [dropped] byte-regions that framed like a frame but failed
         * [EcBtpProtocol.parse] (bad checksum / framing) — RETURNED, not logged, so the caller owns the hex.
         */
        internal class FrameBatch(
            val remainder: ByteArray,
            val frames: List<EcBtpProtocol.Frame>,
            val dropped: List<ByteArray>,
        )

        /**
         * PURE (no GATT/Android, no logging) EcBtp reassembler for BLE notifications, split out so the receive
         * path — the only device-INDEPENDENT parse in this class — is unit-testable without a bike. Appends
         * [incoming] to [acc], then pulls every complete frame: sync to `START 0x24`, read the declared length
         * at index 2 (`total = declared + 1`), validate via [EcBtpProtocol.parse]. Leftovers (a partial frame,
         * or bytes with no START yet) stay in [FrameBatch.remainder] for the next chunk; a bad length byte
         * skips one byte and resyncs; a well-framed frame that fails parse goes to [FrameBatch.dropped].
         */
        internal fun extractFrames(acc: ByteArray, incoming: ByteArray): FrameBatch {
            var buf = acc + incoming
            val frames = ArrayList<EcBtpProtocol.Frame>()
            val dropped = ArrayList<ByteArray>()
            while (true) {
                val start = buf.indexOf(EcBtpProtocol.START)
                if (start < 0) { buf = ByteArray(0); break }         // no START anywhere: nothing parseable
                if (start > 0) buf = buf.copyOfRange(start, buf.size) // drop leading garbage before the START
                if (buf.size < FRAME_OVERHEAD) break                 // need at least the 5-byte overhead
                val declared = buf[2].toInt() and 0xFF
                val total = declared + 1                             // full frame = (declared-4 payload) + 5
                if (total < FRAME_OVERHEAD) { buf = buf.copyOfRange(1, buf.size); continue } // bad len; resync
                if (buf.size < total) break                          // wait for the rest of the frame
                val frameBytes = buf.copyOfRange(0, total)
                buf = buf.copyOfRange(total, buf.size)
                val parsed = EcBtpProtocol.parse(frameBytes)
                if (parsed != null) frames.add(parsed) else dropped.add(frameBytes) // checksum/framing fail
            }
            return FrameBatch(buf, frames, dropped)
        }
    }
}
