// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.transport

import android.app.Activity
import android.content.Context
import android.widget.Toast
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.EasyConnDiscovery
import dev.zanderp.opencfmoto.PhoneHotspotAssist
import dev.zanderp.opencfmoto.PhoneHotspotScan
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.connection.factory.BikeEndpoint
import dev.zanderp.opencfmoto.connection.factory.BikeTransport
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.PlatformIO
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Layer-1 **tether** transport — the Zontes / opaque-`CARBIT` `action=128` connector. The RIDER turns the
 * Android hotspot on (Android forbids a normal app from creating an AP with the SSID/password printed on the
 * dash), the dash joins the PHONE as a DHCP client, and this transport locates it on the tether subnet. No
 * BLE handshake — that is the Rieju's [PhoneHotspotTransport]; the two are told apart by the single
 * [dev.zanderp.opencfmoto.connection.factory.isBleHotspotQr] predicate.
 *
 * **WRAPS the proven classic flow** (`CfmotoConnect.joinPhoneHotspot` → `startPhoneHotspotScan`,
 * community-confirmed on a Zontes 125X): the same [PhoneHotspotAssist.showSetupDialog], the same ~45 s
 * [PhoneHotspotScan.tetheringSubnets] poll, the same NSD-then-subnet peer search
 * ([EasyConnDiscovery.discoverNsd] → [PhoneHotspotScan.findEasyConnPeer]), and the same two addresses handed
 * to the prober. `PhoneHotspotAssist`/`PhoneHotspotScan`/`EasyConnProber` are untouched, and the classic path
 * itself stays intact and reachable for the legacy (`preferFactory = false`) and Android-Auto callers.
 *
 * The [BikeEndpoint] mirrors the classic prober call EXACTLY (`CfmotoConnect.startPhoneHotspotScan`):
 * ```
 * // classic                                  // factory (EasyConnBikeLink, kind = TETHER)
 * proberFor(activity).start(                  prober.start(
 *   network = null,                             network = null,
 *   gatewayOverride = peer,      // dash IP     gatewayOverride = endpoint.host,  // == peer
 *   bindIpOverride = bindIp,     // phone IP    bindIpOverride = endpoint.bindIp, // == subnet.localAddress
 * )                                           )
 * ```
 * — so `host` is the discovered dash address and `bindIp` is `subnet.localAddress` (the phone's tether-iface
 * address, i.e. the gateway of the tether subnet). `network = null` because a tether interface has no
 * bindable [android.net.Network] to hand out, and `phoneIsServer = true` because the phone hosts the AP and
 * the prober is structurally phone-as-server (it listens on :10920-10922 and the dash dials back) — the same
 * shape the Rieju path uses.
 *
 * **Foreground-only**, exactly like the classic path (which refuses when `activity == null`): the assist
 * dialog and the system tethering settings need an Activity.
 * [DefaultBikeConnection][dev.zanderp.opencfmoto.connection.factory.DefaultBikeConnection] gates the headless
 * auto-connect before [open] is ever called; [open] re-checks defensively and throws "needs foreground".
 *
 * **Retries.** The driver may re-`open()` this same instance (TETHER keeps the DEFAULT finite retry caps —
 * an interactive, foreground, one-shot flow, not a daily-ride infinite-retry path). The rider-facing assist
 * runs ONCE per connection: on a retry the creds are already typed and persisted, and re-popping a modal over
 * a rider who is probably standing IN the system hotspot settings would be pure noise — the retry silently
 * re-runs the discovery, which is exactly what a rider still flipping the hotspot on needs.
 *
 * **State.** Progress/terminal state belongs to the driver
 * ([dev.zanderp.opencfmoto.connection.factory.ConnState]), so this transport does not drive the legacy
 * `ConnectionState` — with ONE exception: a rider CANCEL on the assist dialog mirrors the classic `onCancel`
 * (`ConnectionState.set(Phase.ERROR, main_phone_hotspot_cancelled)`) and then unwinds as a cancellation, so
 * the rider gets the same "cancelled" feedback and the driver stops instead of re-popping the dialog on
 * every retry.
 *
 * **[close] never turns the rider's hotspot off** (neither does the classic path — the rider owns it). There
 * is no Wi-Fi request, P2P group or lock to release either: it only flags an in-flight discovery to stop at
 * its next checkpoint (a blocking NSD/port scan already inside `EasyConnDiscovery`/`PhoneHotspotScan` still
 * runs to completion on its IO thread and its result is discarded — exactly as the classic daemon thread did).
 */
class TetherTransport : BikeTransport {

    // Bare-JVM constructible on purpose (the PhoneHotspotTransport lesson): no Android type may be touched in
    // a field initializer, only inside open()/close() — BikeConnectionFactory.selectTransport is exercised by
    // plain-JVM unit tests.
    private val assistAnswered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile private var logCb: ((String) -> Unit)? = null

    override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint {
        val appCtx = ctx.applicationContext
        val log: (String) -> Unit = { msg -> io.log(TAG, msg) }
        this.logCb = log
        closed.set(false) // a re-open after a TransportLost close() must not stay short-circuited

        // Foreground gate — mirrors the classic joinPhoneHotspot refusal when activity == null (the driver
        // already blocks the headless case; this is the defensive re-check, as in PhoneHotspotTransport).
        val activity = io.activityOrNull() ?: throw IllegalStateException("needs foreground")

        if (assistAnswered.compareAndSet(false, true)) {
            awaitSetupAssist(activity, spec, log)
        } else {
            log("[HOTSPOT] assist already answered for this connection — re-running the tether scan silently")
        }

        // Same poll as the classic scan: wait for the tether interface, then NSD, then the subnet scan.
        val subnet = awaitTetherSubnet(appCtx, log)
        log(
            "[HOTSPOT] using ${subnet.interfaceName} " +
                "${subnet.localAddress.hostAddress}/${subnet.prefixLength}",
        )

        val peer = withContext(Dispatchers.IO) {
            if (closed.get()) throw CancellationException("tether transport closed before the dash scan")
            // Prefer NSD first (works when multicast reaches the tether iface) — the classic order.
            EasyConnDiscovery.discoverNsd(appCtx, log)?.host
                ?: PhoneHotspotScan.findEasyConnPeer(subnet, log)
        } ?: throw IllegalStateException(
            "hotspot is up but no EasyConn dash answered on " +
                "${subnet.localAddress.hostAddress}/${subnet.prefixLength} — keep the pairing screen open",
        )

        val bindIp: Inet4Address = subnet.localAddress
        log("→ hotspot peer ${peer.hostAddress}; bind=${bindIp.hostAddress} (EasyConn PXC next)")

        return BikeEndpoint(
            network = null,
            host = peer,
            bindIp = bindIp,
            kind = TransportKind.TETHER,
            phoneIsServer = true,
        )
    }

    override fun close() {
        // Idempotent and resource-free by construction: this connector never created an AP, never took a
        // Wi-Fi/P2P request and never held a lock — the rider's hotspot stays ON (as in the classic path).
        // The flag stops the tether-interface poll between rounds and short-circuits the discovery before it
        // starts; a scan already blocked inside EasyConnDiscovery/PhoneHotspotScan cannot be interrupted from
        // out here (the classic daemon thread had the same property), so it just finishes and is discarded.
        val first = closed.compareAndSet(false, true)
        if (first) logCb?.invoke("[HOTSPOT] tether transport closed (the rider's hotspot is left ON)")
        logCb = null
    }

    /**
     * The classic assist step, verbatim in behavior: log the mode line, show
     * [PhoneHotspotAssist.showSetupDialog] (which persists the dash creds and can deep-link into the system
     * tethering settings), and resume when the rider continues. A CANCEL mirrors the classic `onCancel`
     * (legacy ERROR state + "cancelled" text) and unwinds as a [CancellationException] so the driver goes
     * straight to Idle without burning its retries re-showing the dialog.
     *
     * Marshalled with [Activity.runOnUiThread] because a factory transport opens on the driver's background
     * dispatcher, whereas the classic call site was already on the main thread.
     */
    private suspend fun awaitSetupAssist(activity: Activity, spec: ConnectionSpec, log: (String) -> Unit) {
        val qr = qrFor(spec)
        log(
            "→ phone-hotspot mode (tether; action=${qr.action} mac=${qr.mac}) — " +
                "assist dialog (app cannot create AP; set dash SSID/pwd in system hotspot)",
        )
        suspendCancellableCoroutine { cont ->
            val settled = AtomicBoolean(false)
            activity.runOnUiThread {
                runCatching {
                    PhoneHotspotAssist.showSetupDialog(
                        activity = activity,
                        qr = qr,
                        onContinue = {
                            if (settled.compareAndSet(false, true)) {
                                showWaitingToast(activity)
                                cont.resume(Unit)
                            }
                        },
                        onCancel = {
                            if (settled.compareAndSet(false, true)) {
                                // Byte-for-byte the classic onCancel (CfmotoConnect.joinPhoneHotspot).
                                ConnectionState.set(
                                    dev.zanderp.opencfmoto.Phase.ERROR,
                                    activity.getString(R.string.main_phone_hotspot_cancelled),
                                )
                                cont.resumeWithException(
                                    CancellationException("phone-hotspot setup cancelled by the rider"),
                                )
                            }
                        },
                    )
                }.onFailure { e ->
                    if (settled.compareAndSet(false, true)) cont.resumeWithException(e)
                }
            }
        }
    }

    /** The classic post-dialog toast: the remembered dash SSID when we have one, else the generic hint. */
    private fun showWaitingToast(activity: Activity) {
        runCatching {
            val creds = PhoneHotspotAssist.loadCreds(activity)
            if (creds.ssid.isNotEmpty()) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.main_phone_hotspot_waiting_named, creds.ssid),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(activity, R.string.main_phone_hotspot_hint, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * The classic ~45 s tether-interface poll (30 rounds x 1.5 s), one-for-one: same order (sample, then
     * sleep), same every-5th-round log line with the remembered SSID, same "first subnet wins" pick. Two
     * mechanical substitutions for the factory world: the daemon `Thread`/`Thread.sleep` becomes
     * [Dispatchers.IO] + [delay] (so a disconnect cancels it), and the classic
     * `ConnectionState.phase == STOPPED` bail-out becomes coroutine cancellation plus the [close] flag.
     */
    private suspend fun awaitTetherSubnet(appCtx: Context, log: (String) -> Unit): PhoneHotspotScan.Subnet =
        withContext(Dispatchers.IO) {
            val ssidHint = PhoneHotspotAssist.loadCreds(appCtx).ssid
                .takeIf { it.isNotEmpty() }?.let { " SSID='$it'" }.orEmpty()
            for (round in 0 until POLL_ROUNDS) {
                if (closed.get()) {
                    throw CancellationException("tether transport closed while waiting for the hotspot")
                }
                PhoneHotspotScan.tetheringSubnets().firstOrNull()?.let { return@withContext it }
                if (round == 0 || round % 5 == 0) {
                    log(
                        "[HOTSPOT] waiting for tether interface… " +
                            "(${round + 1}/$POLL_ROUNDS) set Android hotspot$ssidHint",
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
            throw IllegalStateException(
                "no tether interface after ${POLL_ROUNDS * POLL_INTERVAL_MS / 1000}s — turn Android hotspot " +
                    "on with the SSID/password shown on the dash",
            )
        }

    /**
     * The minimal [QrData] [PhoneHotspotAssist.showSetupDialog] actually reads: only `mac` (the "MAC: …" hint
     * under the dialog message). Rebuilt from the [ConnectionSpec] because the spec does not persist the raw
     * QR — the same trick (and the same reason) as [P2pTransport]'s `qrFor`. `action` carries the
     * phone-hotspot bit so the reconstructed QR is shaped like the one that produced this spec; it only ever
     * reaches a log line.
     */
    private fun qrFor(spec: ConnectionSpec): QrData = QrData(
        ssid = spec.ssid.orEmpty(),
        pwd = spec.pwd.orEmpty(),
        auth = null,
        mac = spec.bleMac,
        name = null,
        action = PHONE_HOTSPOT_ACTION_BIT,
        modelId = null,
        sn = null,
        channel = null,
    )

    private companion object {
        private const val TAG = "TetherTransport"

        /** `QrData.supportsPhoneHotspot`: (action and 128) != 0. */
        private const val PHONE_HOTSPOT_ACTION_BIT = 128

        /** Classic `startPhoneHotspotScan`: 30 rounds x 1.5 s ~= 45 s waiting for the tether interface. */
        private const val POLL_ROUNDS = 30
        private const val POLL_INTERVAL_MS = 1_500L
    }
}
