// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.transport

import android.net.wifi.p2p.WifiP2pConfig
import kotlin.random.Random

/**
 * The Wi-Fi Direct group the OFFICIAL app creates, as closely as a normal app is allowed to.
 *
 * Reverse-engineered from `net.easyconn.carman.wws` v6.4.0 (`WifiHotspotUtils.initCreateApType` +
 * `WifiApUtils`, design doc `2026-08-18-bike-connection-factory-design.md`, Appendix A):
 *  - **API 29+ with 5 GHz** → a *custom* [WifiP2pConfig]: chosen network name, chosen passphrase and
 *    `setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)` (the literal `2` in the decompile).
 *  - **Credentials**: SSID = `"Easyconn_AP-" + 4 digits`, passphrase = 12 × `[a-z0-9]`.
 *  - Older APIs → plain `createGroup` (system-generated `DIRECT-…`). This app is `minSdk 29`, so that
 *    branch does not exist here; plain `createGroup` remains only as the LAST RESORT below.
 *
 * We had been calling plain `createGroup` always, so the dash was offered `DIRECT-fa-<phone name>` where
 * the official app offers `Easyconn_AP-1234`. The dash is TOLD the name over BLE (`0x52`), so a
 * system-generated one must keep working — hence every rung here is best-effort and a failure returns
 * `null`, never an exception: [PhoneHotspotTransport] then creates the group exactly as before.
 *
 * **Why there is a ladder at all.** Stock Android enforces the Wi-Fi Direct naming rule in
 * `WifiP2pConfig.Builder.setNetworkName`: the name must start with `DIRECT-` + two alphanumerics — the
 * message `network name must starts with the prefix DIRECT-xy` is in the framework of the phone this was
 * written on (Android 16, `framework-wifi.jar`). So:
 *  1. ask the public Builder for Carbit's own name (works only where this Android does not enforce it),
 *  2. ask for a LEGAL `DIRECT-xy-Easyconn_AP-1234`, which still carries our passphrase and the band,
 *  3. `null` → plain `createGroup`, today's proven behaviour.
 *
 * **The reflection the official app uses is NOT here, and that is deliberate.** Carbit overwrites the built
 * config's hidden `networkName` field to escape rule 1. On our target that is not a risk, it is a
 * certainty of failure: `WifiP2pConfig.networkName` is on the blocklist, and Android's own lint refuses the
 * release build with *"Reflective access to networkName is forbidden when targeting API 36 and above"
 * [BlockedPrivateApi]*. Shipping it would mean a suppression around code that cannot execute — so rung 2
 * gets as close as the platform allows instead. If the dash ever proves it needs the literal
 * `Easyconn_AP-…` SSID, that is a targetSdk conversation, not a `try/catch`.
 *
 * **Band trade-off (say it out loud).** Requesting 5 GHz is what Carbit does and the Rieju dash works
 * with Carbit, but the manual-fallback text has always warned that a dash may be 2.4 GHz-only. The band
 * is requested only on the rungs that also carry a custom name, and only when the phone reports 5 GHz
 * support (the official app's own gate) — if the field log ever shows a dash that cannot see the group,
 * [BAND] is the single constant to flip.
 */
object CarbitGroupConfig {

    /** SSID + passphrase in the official app's own shape (`WifiApUtils`). */
    data class Creds(val ssid: String, val passphrase: String)

    /** A config we managed to build, and WHICH rung produced it — that phrase goes straight to the log. */
    class Built(val config: WifiP2pConfig, val how: String, val networkName: String)

    /** `Easyconn_AP-` + 4 digits, exactly as `WifiApUtils` generates it. */
    private const val SSID_PREFIX = "Easyconn_AP-"
    private const val PASSPHRASE_LEN = 12
    private const val PASSPHRASE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** Wi-Fi Direct reserves this prefix for group SSIDs; the framework enforces `DIRECT-` + 2 alphanumerics. */
    private const val DIRECT_PREFIX = "DIRECT-"
    private const val NAME_MAX_CHARS = 32

    /** The band Carbit asks for (`setGroupOperatingBand(2)` = 5 GHz) when the phone can do it. */
    const val BAND: Int = WifiP2pConfig.GROUP_OWNER_BAND_5GHZ

    /** Fresh credentials for one group. Pure (the [Random] is injectable) so the SHAPE is unit-testable. */
    fun creds(random: Random = Random.Default): Creds = Creds(
        ssid = SSID_PREFIX + "%04d".format(random.nextInt(0, 10_000)),
        passphrase = buildString(PASSPHRASE_LEN) {
            repeat(PASSPHRASE_LEN) { append(PASSPHRASE_ALPHABET[random.nextInt(PASSPHRASE_ALPHABET.length)]) }
        },
    )

    /**
     * A framework-legal network name that still carries [ssid]: `DIRECT-xy-<ssid>`, where `xy` are two
     * alphanumerics taken from the ssid itself (so the same ssid always yields the same name). Pure.
     */
    fun legalNetworkName(ssid: String): String {
        val alnum = ssid.filter { it.isLetterOrDigit() }
        val xy = (alnum.takeLast(2) + "OC").take(2) // exactly two [a-zA-Z0-9], whatever the ssid looks like
        return (DIRECT_PREFIX + xy + "-" + ssid).take(NAME_MAX_CHARS)
    }

    /**
     * Climb the ladder for [creds]. Returns the config to hand to `createGroup(channel, config, listener)`,
     * or `null` when this phone allows neither custom-name path — in which case the caller keeps its plain
     * `createGroup`. Never throws: the connect must not die over a cosmetic network name.
     *
     * @param band the operating band to request, or `WifiP2pConfig.GROUP_OWNER_BAND_AUTO` to let the
     *   framework choose (what the caller passes when the phone reports no 5 GHz support).
     */
    fun build(creds: Creds, band: Int = BAND, log: (String) -> Unit): Built? {
        val asked = runCatching { config(creds.ssid, creds.passphrase, band) }
        asked.getOrNull()?.let { cfg ->
            return Built(cfg, "public Builder accepted the Carbit name as-is", nameOf(cfg, creds.ssid))
        }
        val why = asked.exceptionOrNull()
        log(
            "custom group config: the framework refused the name '${creds.ssid}' " +
                "(${why?.javaClass?.simpleName}: ${why?.message}) — Wi-Fi Direct reserves the DIRECT-xy " +
                "prefix, so asking for the legal form of the same name",
        )

        val legal = legalNetworkName(creds.ssid)
        val prefixed = runCatching { config(legal, creds.passphrase, band) }
        prefixed.getOrNull()?.let { cfg ->
            return Built(cfg, "public Builder, Carbit name behind the mandatory DIRECT- prefix", nameOf(cfg, legal))
        }
        val refused = prefixed.exceptionOrNull()
        log(
            "custom group config: even '$legal' was refused " +
                "(${refused?.javaClass?.simpleName}: ${refused?.message}) — falling back to a " +
                "system-generated name, which is fine: the dash is told the name over BLE",
        )
        return null
    }

    private fun config(networkName: String, passphrase: String, band: Int): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(passphrase)
            .setGroupOperatingBand(band)
            .build()

    /** The name the config actually carries (public getter), falling back to what we asked for. */
    private fun nameOf(cfg: WifiP2pConfig, asked: String): String =
        runCatching { cfg.networkName }.getOrNull()?.takeIf { it.isNotBlank() } ?: asked
}
