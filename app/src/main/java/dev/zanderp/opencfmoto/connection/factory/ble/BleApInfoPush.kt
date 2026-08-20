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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands the phone-hosted Wi-Fi credentials to a Carbit `action=128` dash (Rieju Aventure 500) over BLE,
 * so the dash can join the phone's Wi-Fi Direct group without the rider typing anything. Reverse-engineered
 * from `net.easyconn.carman.wws` v6.4.0 (design doc `2026-08-18-bike-connection-factory-design.md`,
 * Appendix A — bytecode-proven).
 *
 * **Two phases, because the ORDER is part of the protocol.** Appendix A's sequence is
 * `BLE connect → notify on → 0x30 CLIENT_INFO → 0x50 REQUEST_BUILD_NET → phone createGroup → 0x52 AP_INFO`:
 * the official app ASKS the dash to build a network and only then creates one and hands over its credentials.
 * We used to create the group first and fire all three writes back-to-back afterwards. The real Rieju settled
 * it on 2026-08-19: the BLE side works (service `B360`, one char `B362` doing write+notify, MTU 256) and the
 * dash **answers `0x50`** — `24 50 14 {"status":\t2} 7a 0a`, 45 ms after our write — but the app had already
 * fired `0x52` at a network the dash had not sanctioned, then sat waiting for a `0x51`/`0x53` that never came.
 * So the push is split at exactly the point where the official app interleaves the group:
 *  - [connectAndRequestNet] — scan, connect, discover, notifications on, `0x30`, `0x50`, **and the dash's own
 *    `0x50` reply**. **GATT stays open.**
 *  - [sendApInfo] — the `0x52` credentials frame (built by [EcBtpApInfo]) and the dash's `0x51`/`0x53` reply.
 * Whether that choreography is all the real dash needed is unproven until the owner's next log; what IS
 * certain is that we now do it in the documented order and no longer throw away what the dash says back.
 *
 * Reuses [dev.zanderp.opencfmoto.BleWakeUp]'s GATT scaffolding pattern (connect → `requestMtu(185)` →
 * discoverServices → `setCharacteristicNotification` + CCCD `2902`) but is deliberately a **separate class**:
 * the CFMOTO wake-up speaks a different wire protocol (BleProtocol `AB CD` framing + AES challenge) on a
 * different service (`B354`), so entangling the two would only add risk to a proven path. This class instead
 * speaks the **EcBtp** framing (`0x24 | cmd | len | payload | xor | 0x0A`, see [EcBtpProtocol]) on the
 * B36x service, and differs from the wake-up in three ways:
 *  1. **Find the dash by scanning, then connect** to the QR `bm=` MAC (or the ±1 address, or whatever
 *     advertises the AP-info service) — a blind dial at a never-scanned address goes nowhere.
 *  2. **Characteristics by GATT property, not fixed UUID** — the WRITE-capable char for writes, the
 *     NOTIFY/INDICATE-capable char for notifications. This absorbs the three observed layouts without
 *     hardcoding UUIDs: V3 (one `B364` with WRITE+NOTIFY), V2 (write `B363`, notify `B364`), V1 (by property
 *     among the `B362` set).
 *  3. **A fixed write handshake split around the group**: `0x30 CLIENT_INFO` → `0x50 REQUEST_BUILD_NET`
 *     → the dash's `0x50` reply → *(the caller creates the Wi-Fi Direct group)* → `0x52 NOTIFY_AP_INFO`
 *     → await `0x51` `NOTIFY_BUILD_NET_FINISH` / `0x53 NOTIFY_CAR_NET_INFO`.
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
    @Volatile private var pendingService: UUID = DEFAULT_SERVICE_UUID // set per-call in connectAndRequestNet
    private var notifyAcc = ByteArray(0)

    /**
     * Lifetime of the BLE session, which now OUTLIVES a single suspension (the caller creates the Wi-Fi
     * Direct group between the two phases). [LIVE] until either a failure closed the GATT ([DEAD]) or the
     * dash acknowledged the credentials ([ACKED]) — after which a late callback may log, but must never
     * close a link the caller has been told it can rely on, nor resume anything.
     */
    private enum class Session { LIVE, ACKED, DEAD }
    @Volatile private var session = Session.LIVE

    /**
     * Where we are in the fixed handshake. Advanced by each `onCharacteristicWrite` ack, except
     * [AWAIT_NET_REPLY] → [NET_REQUESTED], which is advanced by the DASH: its answer to `0x50` is what ends
     * phase 1 (2026-08-19 field evidence), not our own write completing.
     */
    private enum class Step { IDLE, CLIENT_INFO, REQUEST_BUILD, AWAIT_NET_REPLY, NET_REQUESTED, AP_INFO, AWAIT_ACK }
    @Volatile private var step = Step.IDLE

    /** Bounded wait for the dash's `0x50` reply; removed the moment it lands (or fires once and gives up). */
    @Volatile private var netReplyTimeout: Runnable? = null

    /** The repeating `0x50` poll that runs while we wait for the dash to finish joining. */
    @Volatile private var netPoll: Runnable? = null

    /**
     * The `status` the dash reported in its `0x50` reply (`2` on the Rieju Aventure 500, 2026-08-19), or null
     * if it never answered. **Data, not a verdict** — see [awaitNetReply]. The caller logs it; nothing branches
     * on it.
     */
    @Volatile
    var lastNetBuildStatus: String? = null
        private set

    /**
     * The ONE suspension awaiting a verdict right now — phase 1 or phase 2, never both. [settled] is the
     * single-resume guard (a timeout and a callback can race), and [timeout] is cancelled the moment the
     * phase settles, so a phase-1 timer can never fire into the phase-2 wait.
     */
    private class Phase(val name: String, val resume: (Boolean) -> Unit) {
        val settled = AtomicBoolean(false)
        @Volatile var timeout: Runnable? = null
    }
    @Volatile private var phase: Phase? = null

    // Resolved from the service once discovered, by GATT property (not fixed UUID).
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    // The frames, captured once so the callback thread can advance the handshake without recomputing.
    private lateinit var frameClientInfo: ByteArray
    private lateinit var frameRequestBuild: ByteArray
    private lateinit var frameApInfo: ByteArray

    /**
     * **Phase 1.** Find and connect to [mac], resolve chars on [service] by property, enable notifications and
     * run the opening handshake: `0x30 CLIENT_INFO` then `0x50 REQUEST_BUILD_NET` — the dash's cue that a
     * network is coming — and then **wait for the dash's own `0x50` reply** (see [awaitNetReply]; the local
     * write ack only means our radio sent it). Suspends until that reply lands, or until the short
     * [NET_REPLY_TIMEOUT_MS] grace expires without one — both resolve `true`, because going ahead without a
     * reply is exactly what the app did before this split, so it can never be worse. `false` means the attempt
     * genuinely failed (no dash, no service, dropped link) or timed out; never throws, so the caller can fall
     * back to the manual flow. The GATT is kept **open** on success (the caller now creates the group and comes
     * back with [sendApInfo]); on any failure it is closed here.
     */
    suspend fun connectAndRequestNet(
        mac: String,
        service: UUID,
        timeoutMs: Long = CONNECT_TIMEOUT_MS,
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { finishFailure("cancelled") }
        if (session != Session.LIVE) {
            log("[BLE-AP] refusing a second handshake on a $session session")
            cont.resumeWith(Result.success(false))
            return@suspendCancellableCoroutine
        }
        beginPhase(PHASE_HANDSHAKE, timeoutMs) { ok -> if (cont.isActive) cont.resumeWith(Result.success(ok)) }
        pendingService = service

        frameClientInfo = EcBtpProtocol.build(EcBtpProtocol.CMD_EC_BTP_CLIENT_INFO.toByte(), ByteArray(0))
        frameRequestBuild = EcBtpProtocol.build(EcBtpProtocol.CMD_REQUEST_BUILD_NET.toByte(), ByteArray(0))
        log("[BLE-AP] connectAndRequestNet mac=$mac service=$service (phase 1/2: ask the dash BEFORE the network exists)")
        // CLIENT_INFO/REQUEST_BUILD have empty payloads (no secrets) — log verbatim. The 0x52 AP_INFO
        // payload embeds ssid+pwd in cleartext, so phase 2 logs only its cmd/len.
        log("[BLE-AP] frames: CLIENT_INFO=${hex(frameClientInfo)} REQUEST_BUILD=${hex(frameRequestBuild)}")

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
     * **Phase 2.** Hand over the credentials of the group the caller has just created: build the `0x52`
     * NOTIFY_AP_INFO frame with [EcBtpApInfo] (the golden-frame-tested builder), write it on the link phase 1
     * left open, and await the dash's `0x51`/`0x53`. `true` on that acknowledgement, `false` on failure or
     * timeout — including the case that matters most for the reorder: a BLE link that died while the phone was
     * building the network, which this names instead of reporting a generic write failure.
     *
     * On success the GATT is kept **open** (dashes may rely on the BLE link staying up for the session) — the
     * caller owns teardown via [close]; on any failure it is closed here.
     */
    suspend fun sendApInfo(
        ssid: String,
        pwd: String,
        ip: String,
        timeoutMs: Long = AP_INFO_TIMEOUT_MS,
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { finishFailure("cancelled") }
        if (session != Session.LIVE) {
            // The most valuable line the reorder can produce: the dash was there for the handshake and is
            // gone by the time the creds are ready ⇒ the network the phone just built cost us the BLE link.
            log("[BLE-AP] cannot send 0x52: the BLE session is $session — the link ended before the creds were ready")
            cont.resumeWith(Result.success(false))
            return@suspendCancellableCoroutine
        }
        if (step != Step.NET_REQUESTED) {
            log("[BLE-AP] cannot send 0x52: the handshake is at $step, not NET_REQUESTED (0x50 was never acknowledged)")
            cont.resumeWith(Result.success(false))
            return@suspendCancellableCoroutine
        }
        val g = gatt
        if (g == null) {
            log("[BLE-AP] cannot send 0x52: phase 1 left no open GATT")
            cont.resumeWith(Result.success(false))
            return@suspendCancellableCoroutine
        }
        frameApInfo = EcBtpApInfo.frame(ssid = ssid, pwd = pwd, ip = ip)
        log("[BLE-AP] sendApInfo ssid='$ssid' pwdLen=${pwd.length} ip=$ip (phase 2/2: the group the dash asked for is up)")
        log("[BLE-AP] frame: AP_INFO cmd=0x52 len=${frameApInfo.size}B (payload redacted: embeds ssid+pwd)")
        // The phase is armed BEFORE the write: a submit that fails synchronously finishes through the same
        // funnel, and with no phase registered that verdict would have nobody to resume.
        beginPhase(PHASE_AP_INFO, timeoutMs) { ok -> if (cont.isActive) cont.resumeWith(Result.success(ok)) }
        step = Step.AP_INFO
        log("[BLE-AP] -> 0x52 NOTIFY_AP_INFO")
        writeFrame(g, frameApInfo)
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
        val picked = AtomicBoolean(false)
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
                handler.post { if (session == Session.LIVE) onFound(dev, how) }
            }

            override fun onScanFailed(errorCode: Int) {
                log("[BLE-AP] BLE scan failed ($errorCode) — dialling the QR address directly")
                if (picked.compareAndSet(false, true)) {
                    stop()
                    handler.post { if (session == Session.LIVE) onFound(fallback, "QR address, scan failed") }
                }
            }
        }
        cb = callback
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // BLUETOOTH_SCAN is a RUNTIME permission on Android 12+, and without it startScan throws a
        // SecurityException — which is exactly what happened on the rider's phone. Say so by name: the
        // first version of this just logged "could not start" and left us guessing.
        val scanErr = runCatching {
            // Unfiltered on purpose: we must also catch the ±1 address and a dash that advertises the
            // service without matching the QR at all. The window is short and we stop on the first hit.
            scanner.startScan(null, settings, callback)
        }.exceptionOrNull()
        if (scanErr != null) {
            val hint = if (!hasScanPermission()) {
                " — BLUETOOTH_SCAN is not granted (Android 12+ needs it at runtime)"
            } else {
                ""
            }
            log("[BLE-AP] could not start the BLE scan: ${scanErr.javaClass.simpleName}: ${scanErr.message}$hint")
            log("[BLE-AP] dialling the QR address directly instead")
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
                if (session == Session.LIVE) onFound(fallback, "QR address, scan found nothing")
            }
        }, SCAN_MS)
    }

    /** Android 12+ gates BLE scanning behind BLUETOOTH_SCAN; below that it rides on location. */
    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

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
        // Three things decide whether this dial can ever succeed, and all three are free to ask BEFORE
        // waiting 25 s for silence. bondState NONE on a dash that only serves bonded clients, or a
        // CLASSIC-only device when we force TRANSPORT_LE, both look identical from the outside: a
        // connect that is simply never answered.
        val bond = when (runCatching { device.bondState }.getOrNull()) {
            android.bluetooth.BluetoothDevice.BOND_BONDED -> "BONDED"
            android.bluetooth.BluetoothDevice.BOND_BONDING -> "BONDING"
            android.bluetooth.BluetoothDevice.BOND_NONE -> "NOT paired"
            else -> "unknown"
        }
        val kind = when (runCatching { device.type }.getOrNull()) {
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC (not LE! that would explain silence on TRANSPORT_LE)"
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
            else -> "unknown (never scanned/bonded — Android has no record of it)"
        }
        val name = runCatching { device.name }.getOrNull().orEmpty()
        log("[BLE-AP] target ${device.address}: bond=$bond type=$kind" + (if (name.isNotBlank()) " name='$name'" else ""))

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
        if (gatt == null) finishFailure("connectGatt returned null")
    }

    /** Tear down the GATT (idempotent). The caller invokes this from its transport `close()`. */
    fun close() {
        session = Session.DEAD
        netReplyTimeout?.let { handler.removeCallbacks(it) } // no "the dash didn't answer" line after teardown
        netReplyTimeout = null
        cancelNetPoll()
        val g = gatt
        gatt = null
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
        // A phase still waiting here would otherwise hang until its own timeout, long after the link is gone.
        settlePhase(false)
    }

    /**
     * Arm the single suspension a phase owns: its resume (guarded to fire ONCE) and its own timeout, removed
     * the moment the phase settles so it can never fire into the next phase.
     */
    private fun beginPhase(name: String, timeoutMs: Long, resume: (Boolean) -> Unit) {
        val ph = Phase(name, resume)
        phase = ph
        val timeout = Runnable {
            if (!ph.settled.get()) finishFailure("timeout after ${timeoutMs}ms waiting for ${ph.name}")
        }
        ph.timeout = timeout
        handler.postDelayed(timeout, timeoutMs)
    }

    /** The funnel every verdict goes through: at most one resume per phase, timer always cancelled. */
    private fun settlePhase(ok: Boolean) {
        cancelNetPoll()
        val ph = phase ?: return
        if (!ph.settled.compareAndSet(false, true)) return
        phase = null
        ph.timeout?.let { handler.removeCallbacks(it) }
        ph.resume(ok)
    }

    /**
     * The `0x50` write landed on the dash's radio; now give the DASH its turn. On the real Rieju it answers in
     * ~45 ms with `{"status":\t2}` — the frame the app used to throw away while it waited for a `0x51`/`0x53`
     * that never came, having already pushed `0x52` at a network the dash never sanctioned.
     *
     * The wait is short and NON-fatal: if no reply arrives we go ahead with the group and the creds anyway,
     * which is exactly what the app did before, so this can only add information.
     */
    private fun awaitNetReply() {
        step = Step.AWAIT_NET_REPLY
        log("[BLE-AP] 0x50 delivered; waiting up to ${NET_REPLY_TIMEOUT_MS}ms for the dash's own 0x50 reply")
        val timeout = Runnable {
            if (step == Step.AWAIT_NET_REPLY) {
                log(
                    "[BLE-AP] the dash did not answer 0x50 within ${NET_REPLY_TIMEOUT_MS}ms — going ahead with " +
                        "the group and the creds anyway (that is what the app did before, so we are no worse off)",
                )
                finishHandshake(answered = false)
            }
        }
        netReplyTimeout = timeout
        handler.postDelayed(timeout, NET_REPLY_TIMEOUT_MS)
    }

    /**
     * Keep ASKING while the dash joins, because the dash answers when asked — it does not volunteer.
     *
     * We used to fire `0x52` and then sit on the line waiting for an unsolicited `0x51`/`0x53`. On
     * 2026-08-19 the Rieju did everything right — answered `0x50` with `status=2`, took the creds — and we
     * still timed out after 20 s, because nothing was ever coming. The official app never waits like that:
     * `SdpBluetoothUtil.processRequestBuildNet` runs a `while (true)` that re-sends the build request every
     * 1-2 s and reads the `status` in each reply, treating the request as a POLL. `2` (USE_PHONE_AP) means
     * "keep hosting, I'm working on it"; the handoff is done when the dash finally answers `0` (SUCCEED).
     *
     * So: same cadence Carbit uses, same terminating condition. A `0x51`/`0x53` still completes it if this
     * dash happens to send one — this only removes our dependence on it.
     */
    private fun startNetPoll(g: BluetoothGatt) {
        cancelNetPoll()
        log("[BLE-AP] AP_INFO sent; now polling 0x50 every ${NET_POLL_INTERVAL_MS}ms until the dash reports status=0 (SUCCEED) — the official app's own loop")
        val r = object : Runnable {
            override fun run() {
                if (step != Step.AWAIT_ACK) return
                log("[BLE-AP] -> 0x50 (poll: has the dash joined yet?)")
                writeFrame(g, frameRequestBuild)
                handler.postDelayed(this, NET_POLL_INTERVAL_MS)
            }
        }
        netPoll = r
        handler.postDelayed(r, NET_POLL_INTERVAL_MS)
    }

    private fun cancelNetPoll() {
        netPoll?.let { handler.removeCallbacks(it) }
        netPoll = null
    }

    /** Phase 1 done: the dash has been asked and a network is expected. The link stays up for phase 2. */
    private fun finishHandshake(answered: Boolean) {
        netReplyTimeout?.let { handler.removeCallbacks(it) }
        netReplyTimeout = null
        step = Step.NET_REQUESTED
        if (answered) {
            log(
                "[BLE-AP] *** dash answered 0x50 (status=${lastNetBuildStatus ?: "?"}) — building the network " +
                    "it just sanctioned (GATT kept open) ***",
            )
        } else {
            log("[BLE-AP] *** no 0x50 reply — building the network anyway (GATT kept open) ***")
        }
        settlePhase(true)
    }

    /** Phase 2 done: the dash acknowledged the credentials. Nothing may close the link from here on. */
    private fun finishSuccess() {
        if (session != Session.LIVE) return
        session = Session.ACKED
        log("[BLE-AP] *** dash acknowledged AP info — Wi-Fi handoff complete (GATT kept open) ***")
        settlePhase(true)
    }

    private fun finishFailure(reason: String) {
        if (session != Session.LIVE) {
            // Post-mortem noise (a disconnect after the ack, a second cancellation): worth a line, never a
            // teardown — closing here would drop a link the caller has already been told it can rely on.
            log("[BLE-AP] (session already $session) $reason")
            return
        }
        log("[BLE-AP] FAILED: $reason")
        close() // marks the session DEAD and settles whichever phase was waiting
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("[BLE-AP] connState status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) { finishFailure("connectGatt status=$status"); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { log("[BLE-AP] connected; discovering services"); g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> finishFailure("disconnected at step=$step")
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
                // Our write is out — but phase 1 ends when the DASH answers it, not here.
                Step.REQUEST_BUILD -> awaitNetReply()
                Step.AP_INFO -> { step = Step.AWAIT_ACK; startNetPoll(g) }
                Step.IDLE, Step.AWAIT_NET_REPLY, Step.NET_REQUESTED, Step.AWAIT_ACK -> {}
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
        for (f in batch.frames) onEcBtpFrame(f)
    }

    private fun onEcBtpFrame(frame: EcBtpProtocol.Frame) {
        val c = frame.command.toInt() and 0xFF
        // EVERY decoded frame, payload included. These replies are the only window we have into the dash's
        // side of the protocol, and a `cmd=0x50` line with the payload thrown away is precisely what cost us
        // a whole field round. No new exposure either: the raw bytes of the same notification are logged
        // verbatim two lines above.
        log("[BLE-AP] <- EcBtp cmd=0x${"%02x".format(c)} len=${frame.payload.size}B payload='${text(frame.payload)}'")
        val isAck = c == EcBtpProtocol.CMD_NOTIFY_BUILD_NET_FINISH || c == EcBtpProtocol.CMD_NOTIFY_CAR_NET_INFO
        when {
            // The dash answering our REQUEST_BUILD_NET: this — not our own write ack — is the end of phase 1.
            c == EcBtpProtocol.CMD_REQUEST_BUILD_NET &&
                (step == Step.AWAIT_NET_REPLY || step == Step.REQUEST_BUILD) -> {
                val status = netBuildStatus(frame.payload)
                lastNetBuildStatus = status
                // The status is DATA, not a verdict. `2` is what the Rieju Aventure 500 sent (2026-08-19) and
                // it is the only value anyone has ever seen; the code table is unknown. So we log it and carry
                // on whatever it says — guessing that some value means "not ready" would strand a dash that is
                // perfectly fine. When a second value shows up in a log, that is when a table can be built.
                log("[BLE-AP] <- 0x50 response status=${status ?: "(none in payload)"} — recorded, not judged")
                finishHandshake(answered = true)
            }
            // A reply to one of our polls (above). `0` = SUCCEED: the dash is on the network and the handoff
            // is done — that is the completion signal Carbit waits for. Anything else means "not yet", so we
            // keep asking until the phase budget runs out.
            c == EcBtpProtocol.CMD_REQUEST_BUILD_NET && step == Step.AWAIT_ACK -> {
                val status = netBuildStatus(frame.payload)
                lastNetBuildStatus = status
                if (status == "0") {
                    log("[BLE-AP] <- 0x50 poll answered status=0 (SUCCEED) — the dash JOINED the group")
                    finishSuccess()
                } else {
                    log("[BLE-AP] <- 0x50 poll answered status=${status ?: "(none)"} — not joined yet, asking again")
                }
            }
            // Only an ack that answers OUR credentials completes the handoff. One arriving earlier is reported
            // and nothing more: inventing a success out of a frame we never asked for is how a connector
            // starts lying about the bike.
            isAck && (step == Step.AP_INFO || step == Step.AWAIT_ACK) -> finishSuccess()
            isAck -> log("[BLE-AP] (dash sent 0x${"%02x".format(c)} at step=$step, before the creds — noted, still following the order)")
            else -> log("[BLE-AP] (no action for cmd 0x${"%02x".format(c)} at step=$step)")
        }
    }

    /** Control characters escaped so one dash reply stays one log line. */
    private fun text(b: ByteArray): String = b.toString(Charsets.UTF_8)
        .replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
    private fun shortId(u: UUID): String = u.toString().substring(4, 8).uppercase()

    companion object {
        /** Carbit-generic B36x service (design Appendix A). Overridable per-bike via `spec.bleServiceOverride`. */
        val DEFAULT_SERVICE_UUID: UUID = UUID.fromString("0000B360-D6D8-C7EC-BDF0-EAB1BFC6BCBC")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val PHASE_HANDSHAKE = "connect + 0x30/0x50"
        private const val PHASE_AP_INFO = "0x52 + the dash's 0x51/0x53"

        /** Phase 1 budget: the 6 s scan, the connect, discovery, MTU, CCCD and two writes (the old total). */
        private const val CONNECT_TIMEOUT_MS = 25_000L

        /**
         * Phase 2 budget per band: the creds write plus association + DHCP on the dash, polled throughout.
         *
         * Halved from 30 s when the transport started offering both bands in one connect — two full 30 s
         * waits is a minute of a rider standing next to a bike, and the field log shows there is nothing to
         * wait for: the dash answers each poll in ~200 ms, so a join that is going to happen happens inside
         * a handful of polls.
         */
        private const val AP_INFO_TIMEOUT_MS = 15_000L

        /** Carbit's own re-ask cadence in `processRequestBuildNet` (`Thread.sleep(2000)`). */
        private const val NET_POLL_INTERVAL_MS = 2_000L

        /**
         * Grace for the dash's own `0x50` reply. The real Rieju answered in 45 ms; this is deliberately short
         * because missing it is not fatal (we proceed anyway) and the rider is waiting.
         */
        private const val NET_REPLY_TIMEOUT_MS = 4_000L

        /** `"status": <int>` in the dash's JSON, tolerating the tabs/newlines it actually sends. */
        private val STATUS_REGEX = Regex("\"status\"\\s*:\\s*(-?\\d+)")

        /**
         * The `status` value in a `0x50` reply payload, or null if there is none. PURE, so the ONE frame the
         * real bike has ever given us is a unit test: `24 50 14 7b 0a 09 22 73 74 61 74 75 73 22 3a 09 32 0a
         * 7d 7a 0a` → payload `{\n\t"status":\t2\n}` → `"2"` (Rieju Aventure 500, 2026-08-19).
         */
        internal fun netBuildStatus(payload: ByteArray): String? =
            STATUS_REGEX.find(payload.toString(Charsets.UTF_8))?.groupValues?.getOrNull(1)

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
