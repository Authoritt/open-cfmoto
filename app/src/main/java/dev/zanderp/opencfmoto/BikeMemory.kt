package dev.zanderp.opencfmoto

import android.content.Context
import android.content.SharedPreferences
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.ConnectorChoice
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.connection.factory.bikeIdFor
import dev.zanderp.opencfmoto.connection.factory.fromQr
import org.json.JSONArray
import org.json.JSONObject

/**
 * One remembered bike: the exact scanned QR, a friendly (user-editable) name, and an optional photo
 * (an absolute path to an image imported into the app's private storage).
 */
data class SavedBike(val raw: String, val name: String, val photoPath: String? = null) {
    val qr: QrData? get() = QrData.parse(raw)
}

/**
 * Remembers the bikes the user has connected to so a returning rider can reconnect with one tap
 * instead of re-scanning the dash QR every time — and can keep more than one bike paired (e.g. two
 * motorcycles) and pick between them.
 *
 * We persist the **raw QR string** per bike (not the parsed fields): [QrData.parse] reconstructs the
 * exact same [QrData], and [BikeProfiles.selectByQr] picks the same profile, so a saved reconnect is
 * byte-identical to a fresh scan. The list is stored as JSON; a legacy single-bike entry (older app
 * versions) is migrated into the list on first read.
 */
object BikeMemory {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_LIST = "bikes_json"
    private const val KEY_SELECTED = "selected_raw"

    // Legacy single-bike keys (pre-multi-device); migrated then left in place harmlessly.
    private const val KEY_RAW = "last_qr_raw"
    private const val KEY_NAME = "last_bike_name"

    // Per-bike winning Wi-Fi transport ("AP" | "P2P"), keyed by the (stable) dash SSID.
    private const val KEY_TRANSPORT_PREFIX = "transport_"

    // Per-bike projection mode ("CFMOTO" | "ANDROID_AUTO"), chosen once at pairing.
    private const val KEY_MODE_PREFIX = "mode_"

    // Per-bike ConnectionSpec JSON (design doc 2026-08-18 §5), keyed by ConnectionSpec.bikeId
    // (QR mac, else ssid — see ConnectionSpec.bikeIdFor). Task 8.
    private const val KEY_SPEC_PREFIX = "spec_"

    // Per-bike rider-chosen connection MECHANISM override (ConnectorChoice.name), keyed by the (stable)
    // dash SSID like the mode/transport indexes. Absent/blank ⇒ AUTO (the app detects it) — so existing
    // bikes behave exactly as today. An explicit choice is ALSO mirrored into KEY_SPEC_PREFIX's spec.mode
    // (see setConnectorChoice) because the factory selects the transport by spec.mode.
    private const val KEY_CONNECTOR_PREFIX = "connector_"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All remembered bikes, most-recently-saved first. */
    fun devices(ctx: Context): List<SavedBike> {
        migrateIfNeeded(ctx)
        val arr = runCatching { JSONArray(prefs(ctx).getString(KEY_LIST, "[]")) }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val raw = o.optString("raw"); val name = o.optString("name")
                val photo = o.optString("photo").takeIf { it.isNotBlank() }
                if (raw.isNotBlank()) add(SavedBike(raw, name.ifBlank { raw }, photo))
            }
        }
    }

    /** The bike the one-tap Connect will use: the explicitly selected one, else the most recent. */
    fun selected(ctx: Context): SavedBike? {
        val list = devices(ctx)
        if (list.isEmpty()) return null
        val sel = prefs(ctx).getString(KEY_SELECTED, null)
        return list.firstOrNull { it.raw == sel } ?: list.first()
    }

    /** Persist (or move-to-front) the QR we just connected with, and select it. Keeps any custom
     *  name/photo the rider already gave this bike. */
    fun save(ctx: Context, raw: String, qr: QrData) {
        val prior = devices(ctx).firstOrNull { it.raw == raw }
        val name = prior?.name ?: displayName(qr)
        val existing = devices(ctx).filter { it.raw != raw }
        val list = buildList {
            add(SavedBike(raw, name, prior?.photoPath))
            addAll(existing)
        }
        writeList(ctx, list)
        prefs(ctx).edit().putString(KEY_SELECTED, raw).apply()
        // Config-ownership §2: "the mode is set at Scan, stored in the spec". Seed a fresh bike's
        // ConnectionSpec here (the chokepoint every scan/pairing flow already calls) so the Garage fast
        // path has data on the very next connect — never overwrite an existing spec (would erase a
        // refined mode/lastEndpointHint/defaultMapProvider a prior connect or the Garage already saved).
        if (specFor(ctx, qr) == null) saveSpec(ctx, ConnectionSpec.fromQr(qr))
    }

    fun select(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_SELECTED, raw).apply()
    }

    /** Give a bike a new display name (blank keeps the current one). */
    fun rename(ctx: Context, raw: String, newName: String) {
        val trimmed = newName.trim()
        writeList(ctx, devices(ctx).map {
            if (it.raw == raw && trimmed.isNotBlank()) it.copy(name = trimmed) else it
        })
    }

    /** Attach (or clear, with null) a bike's photo path. */
    fun setPhoto(ctx: Context, raw: String, path: String?) {
        writeList(ctx, devices(ctx).map { if (it.raw == raw) it.copy(photoPath = path) else it })
    }

    fun remove(ctx: Context, raw: String) {
        writeList(ctx, devices(ctx).filter { it.raw != raw })
        if (prefs(ctx).getString(KEY_SELECTED, null) == raw) {
            prefs(ctx).edit().remove(KEY_SELECTED).apply()
        }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_LIST).remove(KEY_SELECTED)
            .remove(KEY_RAW).remove(KEY_NAME)
            .apply()
    }

    /**
     * The Wi-Fi transport ("AP" | "P2P") that last produced a live link for [ssid], or null if we've
     * never connected this bike. Lets the connect path skip the transport that doesn't work on this
     * phone instead of burning its timeout every time (see [setWinningTransport]).
     */
    fun winningTransport(ctx: Context, ssid: String): String? = winningTransport(prefs(ctx), ssid)

    /** [SharedPreferences]-direct core of [winningTransport] — see [specFor]'s KDoc for why this seam exists. */
    internal fun winningTransport(prefs: SharedPreferences, ssid: String): String? =
        if (ssid.isBlank()) null else prefs.getString("$KEY_TRANSPORT_PREFIX$ssid", null)

    /**
     * Remember which transport just got Wi-Fi up for this bike. Some dashes advertise Wi-Fi Direct
     * (DIRECT-* SSID) yet never form a P2P group on a given phone; the app then falls back to the
     * SoftAP after a long timeout. Recording the winner makes every later connect go straight to it.
     */
    fun setWinningTransport(ctx: Context, ssid: String, transport: String) =
        setWinningTransport(prefs(ctx), ssid, transport)

    /** [SharedPreferences]-direct core of [setWinningTransport] (test seam; see [specFor]'s KDoc). */
    internal fun setWinningTransport(prefs: SharedPreferences, ssid: String, transport: String) {
        if (ssid.isBlank()) return
        prefs.edit().putString("$KEY_TRANSPORT_PREFIX$ssid", transport).apply()
    }

    /**
     * The projection mode ("CFMOTO" | "ANDROID_AUTO") chosen for [ssid] when pairing, or null if not
     * chosen yet (→ the first-connect popup asks). Kept per bike, not on the dashboard.
     */
    fun bikeMode(ctx: Context, ssid: String): String? =
        if (ssid.isBlank()) null else prefs(ctx).getString("$KEY_MODE_PREFIX$ssid", null)

    fun setBikeMode(ctx: Context, ssid: String, mode: String) {
        if (ssid.isBlank()) return
        prefs(ctx).edit().putString("$KEY_MODE_PREFIX$ssid", mode).apply()
    }

    /**
     * The persisted [ConnectionSpec] for the bike [qr] identifies (design doc 2026-08-18 §5), or null if
     * we've never saved one — [dev.zanderp.opencfmoto.connection.factory.BikeConnectionFactory.create]'s
     * fast path: a known spec skips re-detection (auto-vs-P2P racing, mode probing) entirely.
     */
    fun specFor(ctx: Context, qr: QrData): ConnectionSpec? = specFor(prefs(ctx), qr)

    /**
     * Persist [spec] under its own [ConnectionSpec.bikeId] (Task 8: called on a successful connect with
     * a refreshed [ConnectionSpec.lastEndpointHint], and from the Garage when the rider sets a per-bike
     * default map provider or a manual mode override).
     *
     * Persisting a spec NEVER touches the legacy [winningTransport] index. [spec.mode] is frequently just a
     * `fromQr` GUESS (the seed caller [save] and the Garage map-picker both pass a QR-derived spec), whereas
     * the transport-winner is a real connection OUTCOME the live path records itself via [setWinningTransport]
     * (CfmotoConnect). Mirroring the guess here silently rewrote a learned winner — e.g. a DIRECT-* bike whose
     * P2P never forms learns "AP", then a Garage map-pick flipped it back to "P2P", forcing the next
     * auto-connect to try P2P at the full timeout. The factory's success hook records the real winner
     * explicitly instead (BikeConnectionFactory.onConnected → [setWinningTransport]).
     */
    fun saveSpec(ctx: Context, spec: ConnectionSpec) = saveSpec(prefs(ctx), spec)

    /**
     * [SharedPreferences]-direct core of [specFor]/[saveSpec] (`internal`, not `private`): the seam
     * `BikeMemoryTest` uses to exercise the real persistence logic on the JVM with a hand-rolled
     * in-memory [SharedPreferences] fake, since this project has no Robolectric/mocking library on the
     * unit-test classpath to fabricate a real [Context] (see `DefaultBikeConnectionTest`'s KDoc for the
     * same constraint). The `Context`-taking overloads above are the only production entry points.
     */
    internal fun specFor(prefs: SharedPreferences, qr: QrData): ConnectionSpec? {
        val id = ConnectionSpec.bikeIdFor(qr)
        if (id.isBlank()) return null
        val json = prefs.getString("$KEY_SPEC_PREFIX$id", null) ?: return null
        return runCatching { ConnectionSpec.fromJson(json) }.getOrNull()
    }

    internal fun saveSpec(prefs: SharedPreferences, spec: ConnectionSpec) {
        if (spec.bikeId.isBlank()) return
        // ONLY the spec — never the winningTransport index (see the public overload's KDoc). spec.mode is
        // frequently a fromQr guess; mirroring it here silently clobbered a real learned winner.
        prefs.edit().putString("$KEY_SPEC_PREFIX${spec.bikeId}", spec.toJson()).apply()
    }

    /**
     * The rider's per-bike connection-mechanism override for [ssid], or [ConnectorChoice.AUTO] (the
     * default) when never set / blank ssid / a corrupt value. Read on the connect hot path
     * (`CfmotoConnect.joinWifi`) with the QR's own ssid, so it stays keyed by ssid like [bikeMode] /
     * [winningTransport]. AUTO ⇒ the existing auto-detect path runs byte-for-byte.
     */
    fun connectorChoice(ctx: Context, ssid: String): ConnectorChoice = connectorChoice(prefs(ctx), ssid)

    /** [SharedPreferences]-direct core of [connectorChoice] (test seam; see [specFor]'s KDoc). */
    internal fun connectorChoice(prefs: SharedPreferences, ssid: String): ConnectorChoice {
        if (ssid.isBlank()) return ConnectorChoice.AUTO
        val raw = prefs.getString("$KEY_CONNECTOR_PREFIX$ssid", null) ?: return ConnectorChoice.AUTO
        return runCatching { ConnectorChoice.valueOf(raw) }.getOrDefault(ConnectorChoice.AUTO)
    }

    /**
     * Set (or clear, with [ConnectorChoice.AUTO]) the per-bike connection mechanism for the bike [qr]
     * identifies. Takes the whole [QrData] — not just an ssid — because an explicit connector must ALSO be
     * reflected into the stored [ConnectionSpec.mode] (what `BikeConnectionFactory.selectTransport` reads):
     * SOFT_AP→SOFT_AP, P2P→P2P, RIEJU_BLE→PHONE_HOTSPOT, TETHER→TETHER, reusing the bike's existing spec so a
     * learned `lastEndpointHint`/`defaultMapProvider` survives. AUTO clears the override by resetting
     * `spec.mode` back to the QR-derived guess ([ConnectionSpec.fromQr]) so detection runs fresh again. The
     * connector index itself stays keyed by ssid (blank ssid ⇒ no-op, exactly like [setBikeMode]).
     */
    fun setConnectorChoice(ctx: Context, qr: QrData, choice: ConnectorChoice) =
        setConnectorChoice(prefs(ctx), qr, choice)

    /** [SharedPreferences]-direct core of [setConnectorChoice] (test seam; see [specFor]'s KDoc). */
    internal fun setConnectorChoice(prefs: SharedPreferences, qr: QrData, choice: ConnectorChoice) {
        val ssid = qr.ssid
        if (ssid.isBlank()) return
        prefs.edit().putString("$KEY_CONNECTOR_PREFIX$ssid", choice.name).apply()
        // Reflect the choice in spec.mode so the factory picks the forced transport. All four connectors
        // force their transport (TETHER included, now that it is a real TransportKind with its own factory
        // transport); AUTO resets to the fromQr guess to genuinely clear a prior override.
        val forcedMode: TransportKind = when (choice) {
            ConnectorChoice.SOFT_AP -> TransportKind.SOFT_AP
            ConnectorChoice.P2P -> TransportKind.P2P
            ConnectorChoice.RIEJU_BLE -> TransportKind.PHONE_HOTSPOT
            ConnectorChoice.TETHER -> TransportKind.TETHER
            ConnectorChoice.AUTO -> ConnectionSpec.fromQr(qr).mode
        }
        val base = specFor(prefs, qr) ?: ConnectionSpec.fromQr(qr)
        saveSpec(prefs, base.copy(mode = forcedMode))
    }

    // ---- convenience accessors used across the app (selected bike) ----
    fun lastRaw(ctx: Context): String? = selected(ctx)?.raw
    fun lastQr(ctx: Context): QrData? = selected(ctx)?.qr
    fun lastBikeName(ctx: Context): String? = selected(ctx)?.name
    fun hasSaved(ctx: Context): Boolean = selected(ctx) != null

    private fun writeList(ctx: Context, list: List<SavedBike>) {
        val arr = JSONArray()
        for (b in list) {
            val o = JSONObject().put("raw", b.raw).put("name", b.name)
            b.photoPath?.let { o.put("photo", it) }
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Fold a pre-multi-device single saved bike into the list, once. */
    private fun migrateIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains(KEY_LIST)) return
        val raw = p.getString(KEY_RAW, null)
        val arr = JSONArray()
        if (!raw.isNullOrBlank()) {
            val name = p.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: raw
            arr.put(JSONObject().put("raw", raw).put("name", name))
            p.edit().putString(KEY_SELECTED, raw).apply()
        }
        p.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun displayName(qr: QrData): String =
        qr.name?.takeIf { it.isNotBlank() } ?: qr.ssid
}
