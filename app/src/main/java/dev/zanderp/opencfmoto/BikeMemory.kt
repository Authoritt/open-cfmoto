package dev.zanderp.opencfmoto

import android.content.Context
import android.content.SharedPreferences
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.ConnectorChoice
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.connection.factory.bikeIdFor
import dev.zanderp.opencfmoto.connection.factory.detectedAtPairing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    // Per-bike rider-chosen connection MECHANISM override (ConnectorChoice.name), keyed by
    // ConnectionSpec.bikeIdFor (QR mac, else ssid) — the SAME key as the spec index, NOT the ssid.
    // Why: phone-hotspot QRs (Rieju / Zontes / opaque CARBIT) frequently carry NO ssid at all
    // (QrData.supportsPhoneHotspot even has an `ssid.isBlank() && mac != null` clause), so an ssid-keyed
    // index silently wrote nothing on exactly the bikes with the newest, least-proven connectors — killing
    // the picker AND the help sheet's "try another connector" escape hatch for them. Absent/blank ⇒ AUTO
    // (the app detects it). An explicit choice is ALSO mirrored into KEY_SPEC_PREFIX's spec.mode (see
    // setConnectorChoice) because the factory selects the transport by spec.mode.
    private const val KEY_CONNECTOR_PREFIX = "connector_"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All remembered bikes, most-recently-saved first. */
    fun devices(ctx: Context): List<SavedBike> {
        migrateIfNeeded(ctx)
        return devices(prefs(ctx))
    }

    /** [SharedPreferences]-direct core of [devices] (test seam; see [specFor]'s KDoc) — skips
     *  migration, a first-run [Context]-only concern with nothing to migrate on a hand-rolled fake. */
    internal fun devices(prefs: SharedPreferences): List<SavedBike> {
        val arr = runCatching { JSONArray(prefs.getString(KEY_LIST, "[]")) }.getOrNull()
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
        // refined mode/lastEndpointHint/defaultMapProvider a prior connect or the Garage already saved,
        // and would silently stomp a rider's PIN, which always writes a spec).
        //
        // The connector is DECIDED ONCE, HERE, and persisted: `detectedAtPairing` is the same decision the
        // classic path makes at connect time — the QR family, refined by the rider's Setup preference and
        // this bike's learned winner (`winningTransport`, which refines AUTO only, exactly as classic does).
        // A bare `fromQr` used to be stored instead, which is how a DIRECT-* bike whose P2P never forms on
        // this phone (learned winner "AP") kept being sent to the P2P connector on every ride.
        if (specFor(ctx, qr) == null) saveSpec(ctx, autoDetectedSpec(ctx, qr))
    }

    /**
     * What auto-detection says for [qr] on THIS phone right now: the QR family, refined by the rider's Setup
     * preference and this bike's learned winner — i.e. exactly the connector `AUTO` resolves to
     * ([ConnectionSpec.detectedAtPairing]; `BikeConnectionFactory.reconcileStoredMode` re-derives the same
     * value at connect time). [save] persists it as the pairing decision, and the Garage/Scan pickers show
     * [autoDetectedMode] as their "detected: …" hint — so the hint cannot drift from what actually happens.
     */
    fun autoDetectedSpec(ctx: Context, qr: QrData): ConnectionSpec {
        val pref = AppSettings.transport(ctx)
        val remembered = if (pref == WifiTransport.AUTO) winningTransport(ctx, qr.ssid) else null
        return ConnectionSpec.detectedAtPairing(qr, pref, remembered)
    }

    /** The connector `AUTO` resolves to for [qr] — see [autoDetectedSpec]. */
    fun autoDetectedMode(ctx: Context, qr: QrData): TransportKind = autoDetectedSpec(ctx, qr).mode

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

    /**
     * Remove a saved bike and purge every per-bike key it owns: the [ConnectionSpec], any pinned
     * [ConnectorChoice], the learned Wi-Fi [winningTransport], the projection [bikeMode], and its photo
     * file — not just the list entry. Before this fix, [remove] only dropped the list entry and cleared
     * [KEY_SELECTED]; re-scanning the SAME bike afterwards resurrected the stale spec/connector, because
     * [save] only seeds a fresh spec `if (specFor(ctx, qr) == null)` — silently defeating the app's own
     * "escanea el QR de la moto otra vez" remedy for a rider who removed and re-paired for a clean slate.
     *
     * [KEY_SPEC_PREFIX]/[KEY_CONNECTOR_PREFIX] are keyed by [ConnectionSpec.bikeIdFor] (the QR mac, else
     * ssid); [KEY_MODE_PREFIX]/[KEY_TRANSPORT_PREFIX] are keyed by the plain [QrData.ssid] — both need the
     * *parsed* QR, so this parses the stored [raw] itself ([QrData.parse]; the exact string the list
     * persists — see [SavedBike.qr]). A [raw] that no longer parses (corrupt/legacy entry) has no id/ssid
     * to purge by: the list entry and its photo (both keyed by [raw] alone) are still removed, and this
     * never throws.
     */
    fun remove(ctx: Context, raw: String) = remove(prefs(ctx), raw)

    /** [SharedPreferences]-direct core of [remove] (test seam; see [specFor]'s KDoc). */
    internal fun remove(prefs: SharedPreferences, raw: String) {
        val list = devices(prefs)
        val bike = list.firstOrNull { it.raw == raw }
        writeList(prefs, list.filter { it.raw != raw })
        if (prefs.getString(KEY_SELECTED, null) == raw) {
            prefs.edit().remove(KEY_SELECTED).apply()
        }
        // Legacy single-bike "last bike" keys (see their KDoc): harmless once migrated, but clear them
        // too if THIS was the last bike, so a stale one never lingers for some future reader to trip over.
        if (prefs.getString(KEY_RAW, null) == raw) {
            prefs.edit().remove(KEY_RAW).remove(KEY_NAME).apply()
        }
        QrData.parse(raw)?.let { qr ->
            val id = ConnectionSpec.bikeIdFor(qr)
            if (id.isNotBlank()) {
                prefs.edit().remove("$KEY_SPEC_PREFIX$id").remove("$KEY_CONNECTOR_PREFIX$id").apply()
            }
            if (qr.ssid.isNotBlank()) {
                prefs.edit().remove("$KEY_MODE_PREFIX${qr.ssid}").remove("$KEY_TRANSPORT_PREFIX${qr.ssid}").apply()
            }
        }
        bike?.photoPath?.let { path -> runCatching { File(path).delete() } }
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
    fun bikeMode(ctx: Context, ssid: String): String? = bikeMode(prefs(ctx), ssid)

    /** [SharedPreferences]-direct core of [bikeMode] (test seam; see [specFor]'s KDoc). */
    internal fun bikeMode(prefs: SharedPreferences, ssid: String): String? =
        if (ssid.isBlank()) null else prefs.getString("$KEY_MODE_PREFIX$ssid", null)

    fun setBikeMode(ctx: Context, ssid: String, mode: String) = setBikeMode(prefs(ctx), ssid, mode)

    /** [SharedPreferences]-direct core of [setBikeMode] (test seam; see [specFor]'s KDoc). */
    internal fun setBikeMode(prefs: SharedPreferences, ssid: String, mode: String) {
        if (ssid.isBlank()) return
        prefs.edit().putString("$KEY_MODE_PREFIX$ssid", mode).apply()
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
     * The rider's per-bike connection-mechanism override for the bike [qr] identifies, or
     * [ConnectorChoice.AUTO] (the default) when never set / no stable id / a corrupt value. Read on the
     * connect hot path (`CfmotoConnect.joinWifi`, `BikeConnectionFactory.create`) with the same
     * [ConnectionSpec.bikeIdFor] key [setConnectorChoice] writes — mac when the QR has one, else ssid — so a
     * blank-ssid phone-hotspot bike can hold a pin like any other. AUTO ⇒ the auto-detect path runs
     * byte-for-byte.
     */
    fun connectorChoice(ctx: Context, qr: QrData): ConnectorChoice = connectorChoice(prefs(ctx), qr)

    /** [SharedPreferences]-direct core of [connectorChoice] (test seam; see [specFor]'s KDoc). */
    internal fun connectorChoice(prefs: SharedPreferences, qr: QrData): ConnectorChoice {
        val id = ConnectionSpec.bikeIdFor(qr)
        if (id.isBlank()) return ConnectorChoice.AUTO
        // Legacy fallback: builds before the key moved to bikeIdFor wrote `connector_<ssid>`. Reading it
        // keeps a pin a rider already made from silently reverting to AUTO on upgrade (same value, older
        // key); for a mac-less QR the two keys are identical anyway, so this only fires for mac'd bikes.
        val raw = prefs.getString("$KEY_CONNECTOR_PREFIX$id", null)
            ?: qr.ssid.takeIf { it.isNotBlank() && it != id }
                ?.let { prefs.getString("$KEY_CONNECTOR_PREFIX$it", null) }
            ?: return ConnectorChoice.AUTO
        return runCatching { ConnectorChoice.valueOf(raw) }.getOrDefault(ConnectorChoice.AUTO)
    }

    /**
     * Set (or clear, with [ConnectorChoice.AUTO]) the per-bike connection mechanism for the bike [qr]
     * identifies. Takes the whole [QrData] — not just an ssid — because an explicit connector must ALSO be
     * reflected into the stored [ConnectionSpec.mode] (what `BikeConnectionFactory.selectTransport` reads):
     * SOFT_AP→SOFT_AP, P2P→P2P, RIEJU_BLE→PHONE_HOTSPOT, TETHER→TETHER, reusing the bike's existing spec so a
     * learned `lastEndpointHint`/`defaultMapProvider` survives. AUTO clears the override by resetting
     * `spec.mode` back to what auto-detection says today ([ConnectionSpec.detectedAtPairing], the pairing
     * decision) so detection runs fresh again.
     *
     * The connector index is keyed by [ConnectionSpec.bikeIdFor] — the QR mac when there is one, else the
     * ssid — NOT by the ssid. It used to be ssid-keyed and bail out on a blank one, which made the picker a
     * silent no-op on precisely the phone-hotspot bikes (Rieju / Zontes / opaque `CARBIT`) whose QRs
     * routinely carry no ssid at all. No stable id at all (no mac AND no ssid) is still a no-op — there is
     * nothing to key by.
     */
    fun setConnectorChoice(ctx: Context, qr: QrData, choice: ConnectorChoice) =
        setConnectorChoice(prefs(ctx), qr, choice)

    /** [SharedPreferences]-direct core of [setConnectorChoice] (test seam; see [specFor]'s KDoc). */
    internal fun setConnectorChoice(prefs: SharedPreferences, qr: QrData, choice: ConnectorChoice) {
        val id = ConnectionSpec.bikeIdFor(qr)
        if (id.isBlank()) return
        prefs.edit().putString("$KEY_CONNECTOR_PREFIX$id", choice.name).apply()
        // Reflect the choice in spec.mode so the factory picks the forced transport. All four connectors
        // force their transport (TETHER included, now that it is a real TransportKind with its own factory
        // transport); AUTO resets to the auto-detected mode to genuinely clear a prior override.
        val forcedMode: TransportKind = when (choice) {
            ConnectorChoice.SOFT_AP -> TransportKind.SOFT_AP
            ConnectorChoice.P2P -> TransportKind.P2P
            ConnectorChoice.RIEJU_BLE -> TransportKind.PHONE_HOTSPOT
            ConnectorChoice.TETHER -> TransportKind.TETHER
            ConnectorChoice.AUTO -> autoDetected(prefs, qr).mode
        }
        val base = specFor(prefs, qr) ?: autoDetected(prefs, qr)
        saveSpec(prefs, base.copy(mode = forcedMode))
    }

    /**
     * What auto-detection says for [qr] right now, using this bike's learned winner (the rider's Setup
     * preference is not reachable through a bare [SharedPreferences] seam, so AUTO is assumed — the same
     * assumption the Garage/Scan pickers make when they show the "detected" hint).
     */
    private fun autoDetected(prefs: SharedPreferences, qr: QrData): ConnectionSpec =
        ConnectionSpec.detectedAtPairing(qr, WifiTransport.AUTO, winningTransport(prefs, qr.ssid))

    // ---- convenience accessors used across the app (selected bike) ----
    fun lastRaw(ctx: Context): String? = selected(ctx)?.raw
    fun lastQr(ctx: Context): QrData? = selected(ctx)?.qr
    fun lastBikeName(ctx: Context): String? = selected(ctx)?.name
    fun hasSaved(ctx: Context): Boolean = selected(ctx) != null

    private fun writeList(ctx: Context, list: List<SavedBike>) = writeList(prefs(ctx), list)

    /** [SharedPreferences]-direct core of the private [writeList] (test seam for [remove]; see
     *  [specFor]'s KDoc). */
    internal fun writeList(prefs: SharedPreferences, list: List<SavedBike>) {
        val arr = JSONArray()
        for (b in list) {
            val o = JSONObject().put("raw", b.raw).put("name", b.name)
            b.photoPath?.let { o.put("photo", it) }
            arr.put(o)
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
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
