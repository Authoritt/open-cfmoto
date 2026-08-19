// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.zanderp.opencfmoto.PhoneHotspotAssist
import dev.zanderp.opencfmoto.connection.factory.BikeEndpoint
import dev.zanderp.opencfmoto.connection.factory.BikeTransport
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.PlatformIO
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.connection.factory.TransportUnavailableException
import dev.zanderp.opencfmoto.connection.factory.ble.BleApInfoPush
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Layer-1 phone-hotspot transport — the Rieju connector (design doc 2026-08-18 §8, protocol proven in
 * Appendix A). The phone becomes the **Wi-Fi Direct group owner** (`WifiP2pManager.createGroup`, so it is
 * the PXC server at `192.168.49.1`), reads the system-generated `networkName`/`passphrase`, and hands those
 * creds to the dash over BLE (EcBtp `0x30`→`0x50`→`0x52`, see [BleApInfoPush]). The dash then joins the
 * phone's group as a legacy STA — no rider typing. `phoneIsServer = true`.
 *
 * **Foreground-only.** [DefaultBikeConnection][dev.zanderp.opencfmoto.connection.factory.DefaultBikeConnection]
 * already gates auto-connect: with `spec.mode == PHONE_HOTSPOT` and `activityOrNull() == null` it emits
 * `Error("needs foreground")` *before* calling [open]. [open] re-checks defensively and throws if the
 * Activity is gone.
 *
 * **Fallback.** Any BLE failure/absence (no MAC, adapter off, dash didn't ack) drops to the improved manual
 * flow: the phone's group creds are *readable*, so [PhoneHotspotAssist.showReadableHotspotGuidance] shows them
 * for the rider to enter on the dash (with a 2.4 GHz note) — a strict upgrade over the old "type the dash
 * creds" dialog. The transport still returns the endpoint so the link layer waits for the dash to join.
 *
 * **Isolation.** This connector owns its *own* [WifiP2pManager]/[WifiP2pManager.Channel]; it never touches
 * `BikeWifiP2p` (the P2P *client*-join path), so SoftAP/P2P behavior is unchanged.
 *
 * **Diagnosability (2026-08-19, from the Rieju owner's log).** `createGroup` failed twice in ~11 ms with the
 * single word `ERROR`, before the BLE handshake could even start — no reason code, no precondition, no
 * advice. So [open] now runs [WifiDirectPreflight] first (phone Wi-Fi on · Android 13+ `NEARBY_WIFI_DEVICES`
 * granted · Wi-Fi Direct supported — the missing grant produces exactly that generic `ERROR (0)`), every
 * framework rejection is logged by NAME and number, and a leftover group from a previous attempt is retired
 * and the request re-issued EXACTLY ONCE. That repair is a precondition fix INSIDE the single connect
 * attempt the contract allows — never a retry loop, never a second connector.
 *
 * @param msgs rider-facing text for the pre-flight failures, injected (localized) by
 *   [dev.zanderp.opencfmoto.connection.factory.BikeConnectionFactory]; the default keeps this class
 *   constructible from plain-JVM unit tests.
 */
@SuppressLint("MissingPermission")
class PhoneHotspotTransport(
    private val msgs: WifiDirectMessages = WifiDirectMessages(),
) : BikeTransport {

    // Lazy so merely *constructing* the transport (e.g. BikeConnectionFactory.selectTransport in a plain-JVM
    // unit test) never touches the Android main Looper — only open()/close(), which run on a device, do.
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var manager: WifiP2pManager? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var blePush: BleApInfoPush? = null
    @Volatile private var logCb: (String) -> Unit = {}

    private data class OwnerGroup(val ssid: String, val passphrase: String, val goAddress: Inet4Address)

    override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint {
        val appCtx = ctx.applicationContext
        this.appContext = appCtx
        val log: (String) -> Unit = { msg -> io.log(TAG, msg) }
        this.logCb = log

        // Foreground gate (defensive — the driver already blocks the headless case; see class KDoc).
        val activity = io.activityOrNull() ?: throw IllegalStateException("needs foreground")

        // 0) Preconditions FIRST: the three states in which Wi-Fi Direct cannot work at all. Each throws a
        //    rider-facing "here is what to do" instead of letting the framework answer `ERROR` and leaving
        //    both the rider and the log with nothing (the 2026-08-19 field failure).
        WifiDirectPreflight.requireReady(appCtx, msgs, log, activity)

        // 1) Become the Wi-Fi Direct group owner and read the system-generated creds + our GO address.
        val group = createOwnerGroup(appCtx, log)
        log("group formed: ssid='${group.ssid}' pwdLen=${group.passphrase.length} go=${group.goAddress.hostAddress}")

        // 2) Hand the creds to the dash over BLE (EcBtp 0x30 → 0x50 → 0x52 → await 0x51/0x53).
        val mac = spec.bleMac?.takeIf { it.isNotBlank() }
        val service = spec.bleServiceOverride?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: BleApInfoPush.DEFAULT_SERVICE_UUID
        val pushed = if (mac == null) {
            log("no BLE MAC in spec (bm=) — cannot push AP info; using manual fallback")
            false
        } else {
            val pusher = BleApInfoPush(appCtx, log).also { blePush = it }
            runCatching {
                pusher.pushApInfo(
                    mac = mac,
                    service = service,
                    ssid = group.ssid,
                    pwd = group.passphrase,
                    ip = group.goAddress.hostAddress ?: DEFAULT_GO_IP,
                )
            }.getOrElse { e ->
                // A disconnect() mid-push cancels this coroutine; pushApInfo then resumes with a
                // CancellationException that MUST propagate to the driver's finally (a cancel is teardown,
                // not a BLE failure — DefaultBikeConnection.kt:172-174 cancels, :195-196 rethrows). Swallowing
                // it here would pop the manual-fallback dialog and return a phantom endpoint during a cancel.
                if (e is CancellationException) throw e
                log("BLE push threw: ${e.message}")
                false
            }
        }

        // 3) On any BLE failure, fall back to the improved manual flow (readable creds + 2.4 GHz note).
        if (!pushed) {
            log("BLE AP-info push did not complete — showing readable-creds manual guidance")
            runCatching {
                PhoneHotspotAssist.showReadableHotspotGuidance(
                    activity = activity,
                    ssid = group.ssid,
                    pwd = group.passphrase,
                    goAddress = group.goAddress.hostAddress ?: DEFAULT_GO_IP,
                )
            }.onFailure { log("manual guidance failed: ${it.message}") }
        }

        // 4) Endpoint: phone is GO + PXC server at its GO address. No bindable Network (P2P), so the link
        //    layer binds to our GO address and runs the prober in server mode (phoneIsServer = true).
        return BikeEndpoint(
            network = null,
            host = group.goAddress,
            bindIp = group.goAddress,
            kind = TransportKind.PHONE_HOTSPOT,
            phoneIsServer = true,
        )
    }

    /**
     * `createGroup` as group owner, then resolve `networkName`/`passphrase` (from [WifiP2pGroup]) and the GO
     * address (from [WifiP2pInfo], defaulting to the canonical `192.168.49.1` a Wi-Fi Direct GO always owns).
     * Uses a `WIFI_P2P_CONNECTION_CHANGED` receiver plus a short poll so a DHCP/interface lag after the group
     * forms doesn't lose the creds. The group is kept up on success (the dash must join it); it is removed
     * only in [close].
     *
     * **Every rejection is named** (`ERROR (0)` / `P2P_UNSUPPORTED (1)` / `BUSY (2)`), and there is exactly
     * ONE self-repair: a leftover group from a previous attempt — the prime suspect in the field log, where
     * both failures landed right after `mode switch: stop previous projection (keep Wi-Fi)` — is inspected,
     * removed, and the request re-issued a single time. `BUSY` and the generic `ERROR` share that one path,
     * because OEM stacks report the same stale-group condition either way. `P2P_UNSUPPORTED` is not
     * repairable and stops immediately with its own rider-facing message.
     */
    private suspend fun createOwnerGroup(appCtx: Context, log: (String) -> Unit): OwnerGroup =
        suspendCancellableCoroutine { cont ->
            val mgr = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (mgr == null) {
                cont.resumeWithException(
                    TransportUnavailableException(msgs.unsupported, "device has no Wi-Fi P2P service"),
                )
                return@suspendCancellableCoroutine
            }
            val chan = mgr.initialize(appCtx, Looper.getMainLooper(), null)
            manager = mgr
            channel = chan

            val settled = AtomicBoolean(false)
            // The ONE repair this connect attempt is allowed. Spent, never reset: the connect-time contract is
            // a single attempt, and a "clear the group and try again" loop is exactly how a connector that
            // cannot work becomes an invisible one that retries forever.
            val repairSpent = AtomicBoolean(false)
            // While the repair runs the poller must not reap: the only group it could find is the STALE one we
            // are about to remove, and resuming with its creds would hand the dash a network that is seconds
            // from disappearing.
            val repairing = AtomicBoolean(false)

            fun succeed(g: OwnerGroup) {
                if (settled.compareAndSet(false, true)) {
                    unregisterReceiver(appCtx) // group stays up; only stop listening
                    cont.resume(g)
                }
            }
            fun failCleanup(reason: String, rider: String? = null) {
                if (settled.compareAndSet(false, true)) {
                    log("createOwnerGroup FAILED: $reason")
                    close() // remove the half-formed group + drop manager/channel
                    cont.resumeWithException(
                        if (rider != null) TransportUnavailableException(rider, reason)
                        else IllegalStateException(reason),
                    )
                }
            }

            // Read connection + group info; resume once both the GO address and creds are known.
            fun reap() {
                if (settled.get() || repairing.get()) return
                mgr.requestConnectionInfo(chan) { info: WifiP2pInfo? ->
                    if (info == null || !info.groupFormed) return@requestConnectionInfo
                    mgr.requestGroupInfo(chan) { grp: WifiP2pGroup? ->
                        val ssid = grp?.networkName
                        val pwd = grp?.passphrase
                        val go = (info.groupOwnerAddress as? Inet4Address)
                            ?: runCatching { InetAddress.getByName(DEFAULT_GO_IP) as Inet4Address }.getOrNull()
                        if (!ssid.isNullOrBlank() && pwd != null && go != null) {
                            succeed(OwnerGroup(ssid, pwd, go))
                        }
                    }
                }
            }

            val rx = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) reap()
                }
            }
            receiver = rx
            registerSystemReceiver(appCtx, rx, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))

            cont.invokeOnCancellation { failCleanup("cancelled") }

            // Poll as a backstop for the CONNECTION_CHANGED broadcast (some stacks fire it before we register,
            // or the createGroup listener never fires). Started unconditionally so the deadline always runs —
            // it also bounds the repair below, which is why that repair needs no clock of its own.
            val deadline = System.currentTimeMillis() + CREATE_GROUP_TIMEOUT_MS
            val poller = object : Runnable {
                override fun run() {
                    if (settled.get()) return
                    if (System.currentTimeMillis() > deadline) {
                        failCleanup(
                            "no Wi-Fi Direct group formed within ${CREATE_GROUP_TIMEOUT_MS / 1000}s " +
                                "(the request was accepted, the group never came up)",
                        )
                        return
                    }
                    reap()
                    handler.postDelayed(this, GROUP_POLL_INTERVAL_MS)
                }
            }
            handler.postDelayed(poller, GROUP_POLL_INTERVAL_MS)

            /** Retire a leftover group — naming it first, because when there IS one that line is the diagnosis. */
            fun removeStaleGroupThen(next: () -> Unit) {
                val acted = AtomicBoolean(false)
                fun remove() {
                    runCatching {
                        mgr.removeGroup(chan, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                log("leftover group removed — re-issuing createGroup ONCE (bounded repair, not a retry loop)")
                                handler.postDelayed({ next() }, REPAIR_SETTLE_MS)
                            }
                            override fun onFailure(reason: Int) {
                                log(
                                    "removeGroup rejected: ${WifiDirectPreflight.reasonName(reason)} — " +
                                        "re-issuing createGroup ONCE anyway",
                                )
                                handler.postDelayed({ next() }, REPAIR_SETTLE_MS)
                            }
                        })
                    }.onFailure { e ->
                        log("removeGroup threw: ${e.message} — re-issuing createGroup ONCE anyway")
                        handler.postDelayed({ next() }, REPAIR_SETTLE_MS)
                    }
                }
                runCatching {
                    mgr.requestGroupInfo(chan) { grp: WifiP2pGroup? ->
                        if (grp == null) {
                            log("leftover group check: none present — the rejection was not a stale group")
                        } else {
                            log(
                                "leftover group check: '${grp.networkName}' is still up " +
                                    "(ours: ${grp.isGroupOwner}, joined dashes: ${grp.clientList?.size ?: 0})",
                            )
                        }
                        if (acted.compareAndSet(false, true)) remove()
                    }
                }.onFailure { e ->
                    log("leftover group check threw: ${e.message} — removing anyway")
                    if (acted.compareAndSet(false, true)) remove()
                }
                // requestGroupInfo's callback is not guaranteed to arrive; the repair must never hang on it.
                handler.postDelayed({
                    if (acted.compareAndSet(false, true)) {
                        log("leftover group check timed out — removing anyway")
                        remove()
                    }
                }, GROUP_INFO_TIMEOUT_MS)
            }

            fun attemptCreate() {
                log("createGroup: becoming Wi-Fi Direct group owner (PXC server @ $DEFAULT_GO_IP)")
                mgr.createGroup(chan, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        log("createGroup: accepted by the framework — waiting for the group to form")
                        reap()
                    }
                    override fun onFailure(reason: Int) {
                        val named = WifiDirectPreflight.reasonName(reason)
                        log("createGroup rejected: $named")
                        // A verdict about the PHONE (P2P_UNSUPPORTED) cannot be repaired: say so plainly.
                        val verdict = WifiDirectPreflight.blockerForReason(reason)
                        if (verdict != null) {
                            failCleanup(
                                "createGroup rejected: $named — ${WifiDirectPreflight.technical(verdict)}",
                                WifiDirectPreflight.riderMessage(verdict, msgs),
                            )
                            return
                        }
                        if (!repairSpent.compareAndSet(false, true)) {
                            failCleanup("createGroup rejected again ($named) after the one repair this attempt allows")
                            return
                        }
                        repairing.set(true)
                        removeStaleGroupThen {
                            repairing.set(false)
                            if (!settled.get()) attemptCreate()
                        }
                    }
                })
            }

            attemptCreate()
        }

    override fun close() {
        val ctx = appContext
        if (ctx != null) unregisterReceiver(ctx)
        runCatching { blePush?.close() }
        blePush = null
        val mgr = manager
        val chan = channel
        if (mgr != null && chan != null) {
            runCatching {
                mgr.removeGroup(chan, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { logCb("P2P group removed") }
                    override fun onFailure(reason: Int) { /* no active group; ignore */ }
                })
            }
        }
        manager = null
        channel = null
        appContext = null
    }

    private fun unregisterReceiver(ctx: Context) {
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
        receiver = null
    }

    private fun registerSystemReceiver(ctx: Context, rx: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(rx, filter)
        }
    }

    private companion object {
        private const val TAG = "PhoneHotspotTransport"
        private const val DEFAULT_GO_IP = "192.168.49.1"
        private const val CREATE_GROUP_TIMEOUT_MS = 15_000L
        private const val GROUP_POLL_INTERVAL_MS = 500L

        /** Let the framework settle after retiring a leftover group, before re-issuing the request. */
        private const val REPAIR_SETTLE_MS = 400L

        /** Cap on the "which group is actually up?" question, so the repair can never hang on its callback. */
        private const val GROUP_INFO_TIMEOUT_MS = 1_500L
    }
}
