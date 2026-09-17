// SPDX-License-Identifier: AGPL-3.0-or-later
// Cockpit redesign — durable app settings on DataStore. Replaces the scattered SharedPreferences
// reads for the NEW shell (modes, developer mode, auto-connect, telemetry). The legacy
// AppSettings/MapPrefs stay for the core it already drives; this is additive.
package dev.zanderp.opencfmoto.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How a bike is projected. CFMOTO skips all of Android Auto; ANDROID_AUTO keeps gearhead. */
enum class AppMode { CFMOTO, ANDROID_AUTO }

/** Which map reaches the dash. BUILTIN is the AA-free star of CFMOTO mode. */
/**
 * Which map the rider gets. Persisted BY NAME — append only, never reorder or rename: the stored
 * string of an already-paired install has to keep meaning what it meant.
 *
 * WEB is the browser projected to the dash ([dev.zanderp.opencfmoto.browser.DashBrowser]): one WebView
 * at the bike canvas size, driven from the dash touchscreen or from the phone.
 */
/**
 * Which map the cockpit and the dash show.
 *
 * APPEND-ONLY: the value is persisted BY NAME, so reordering or renaming silently moves every rider
 * who had picked one. [BUILTIN_GSEARCH] is the native map with a different way of finding a
 * destination, not a different map -- see [dev.zanderp.opencfmoto.browser.GooglePlaceSearch].
 */
enum class MapProvider { BUILTIN, GOOGLE, WAZE, MIRROR, WEB, BUILTIN_GSEARCH }

/**
 * Does this provider paint OUR map? Overtake and every variant of it.
 *
 * One concept instead of `p == BUILTIN || p == BUILTIN_GSEARCH` copied around: the fork has already
 * been bitten twice by a hand-written list that stopped seeing a new value without saying so.
 */
val MapProvider.rendersNativeMap: Boolean
    get() = this == MapProvider.BUILTIN || this == MapProvider.BUILTIN_GSEARCH

/**
 * Pintamos NOSOTROS lo que va al tablero con este proveedor.
 *
 * Google y Waze no: los pinta Android Auto, y el espejo necesita antes el consentimiento de captura.
 * Para esos, cambiar de mapa no es re-armar contenido sino cambiar el MODO de conexion, asi que
 * reconstruirles el pipeline por su cuenta dejaria el tablero en negro.
 */
val MapProvider.paintedByUs: Boolean
    get() = rendersNativeMap || this == MapProvider.WEB

/**
 * Which engine renders the built-in dash map projected to the bike (VirtualDisplay → H.264).
 * MAPLIBRE (default) is the premium vector + 3D look and is PROVEN clean on that encoder path — it
 * keeps rendering even with the phone screen OFF (probe commit 0c5b7c8). OSMDROID is the classic
 * raster engine, kept fully working as the alternative. MAPSFORGE renders offline VECTOR `.map`
 * files through android.graphics.Canvas — it keeps drawing screen-OFF like osmdroid but with vector
 * quality; with no `.map` present the library degrades gracefully to the osmdroid raster.
 */
enum class DashRenderer { MAPLIBRE, OSMDROID, MAPSFORGE }

/** Cockpit theme selection. AUTO follows the system light/dark setting. */
enum class ThemeMode { AUTO, LIGHT, DARK }

private val Context.cockpitDataStore by preferencesDataStore("cockpit_settings")

/**
 * App-wide preferences for the redesigned shell. The per-bike mode lives with the bike (see
 * BikeMemory); [defaultMode] here is only the fallback for a bike with no explicit choice yet.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val defaultMode = stringPreferencesKey("default_mode")
        val mapProvider = stringPreferencesKey("map_provider")
        val dashRenderer = stringPreferencesKey("dash_renderer")
        val themeMode = stringPreferencesKey("theme_mode")
        val developerMode = booleanPreferencesKey("developer_mode")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val autoConnectPrompted = booleanPreferencesKey("auto_connect_prompted")
        val telemetry = booleanPreferencesKey("telemetry_enabled")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
    }

    val defaultMode: Flow<AppMode> = context.cockpitDataStore.data.map { p ->
        runCatching { AppMode.valueOf(p[Keys.defaultMode] ?: AppMode.CFMOTO.name) }.getOrDefault(AppMode.CFMOTO)
    }
    val mapProvider: Flow<MapProvider> = context.cockpitDataStore.data.map { p ->
        runCatching { MapProvider.valueOf(p[Keys.mapProvider] ?: MapProvider.BUILTIN.name) }.getOrDefault(MapProvider.BUILTIN)
    }

    /**
     * Which engine renders the projected dash. Default MAPLIBRE — proven clean on the VD → H.264
     * encoder and the premium vector + 3D look the owner wants (see [DashRenderer]).
     */
    val dashRenderer: Flow<DashRenderer> = context.cockpitDataStore.data.map { p ->
        runCatching { DashRenderer.valueOf(p[Keys.dashRenderer] ?: DashRenderer.MAPLIBRE.name) }.getOrDefault(DashRenderer.MAPLIBRE)
    }

    /** Cockpit light/dark theme. AUTO (default) follows the system setting. */
    val themeMode: Flow<ThemeMode> = context.cockpitDataStore.data.map { p ->
        runCatching { ThemeMode.valueOf(p[Keys.themeMode] ?: ThemeMode.AUTO.name) }.getOrDefault(ThemeMode.AUTO)
    }

    /** Off by default — revealed by the 7-tap gesture on the version row. */
    val developerMode: Flow<Boolean> = context.cockpitDataStore.data.map { it[Keys.developerMode] ?: false }

    /** Off until the rider says yes in the first-connect consent popup. */
    val autoConnect: Flow<Boolean> = context.cockpitDataStore.data.map { it[Keys.autoConnect] ?: false }

    /** True once the auto-connect consent popup has been answered (so it's shown only once). */
    val autoConnectPrompted: Flow<Boolean> = context.cockpitDataStore.data.map { it[Keys.autoConnectPrompted] ?: false }

    /** Anonymous telemetry is off by default in this fork (explicit consent). */
    val telemetryEnabled: Flow<Boolean> = context.cockpitDataStore.data.map { it[Keys.telemetry] ?: false }

    /**
     * True once the rider finishes (or explicitly skips) the first-run onboarding. The cockpit's
     * start-up gate reads this to decide whether to open [ui.settings.OnboardingScreen] before the
     * dashboard; set to true from either the "Start" or the "Skip" affordance so the gate can never
     * re-trap someone who already answered it.
     */
    val onboardingDone: Flow<Boolean> = context.cockpitDataStore.data.map { it[Keys.onboardingDone] ?: false }

    suspend fun setDefaultMode(mode: AppMode) = edit { it[Keys.defaultMode] = mode.name }
    /**
     * Set the provider AND mirror it into
     * [dev.zanderp.opencfmoto.browser.DashBrowserPrefs] in the same action.
     *
     * `VideoPipeline` cannot suspend to collect this DataStore Flow while it is choosing a
     * presentation, so it reads a plain-prefs mirror instead. Two copies of the same rule always
     * diverge unless one place writes both — every provider change goes through here, never through
     * [setMapProvider] alone.
     */
    suspend fun setMapProviderMirrored(p: MapProvider) {
        setMapProvider(p)
        dev.zanderp.opencfmoto.browser.DashBrowserPrefs.setEnabled(context, p == MapProvider.WEB)
        // Y decirselo al tablero, que si no no se entera. VideoPipeline lee el proveedor UNA vez, al
        // arrancar la proyeccion: hasta ahora cambiar de mapa con la moto conectada no hacia nada
        // hasta desconectar y volver a conectar. Re-armar NO toca PXC ni el Wi-Fi -- es la misma
        // maquinaria del salto AA->Mapa, que lleva tiempo funcionando en la moto.
        if (p.paintedByUs) dev.zanderp.opencfmoto.BikeLink.requestDashRearm("cambio de mapa a ${p.name}")
    }

    suspend fun setMapProvider(p: MapProvider) = edit { it[Keys.mapProvider] = p.name }
    suspend fun setDashRenderer(r: DashRenderer) = edit { it[Keys.dashRenderer] = r.name }
    suspend fun setThemeMode(m: ThemeMode) = edit { it[Keys.themeMode] = m.name }
    suspend fun setDeveloperMode(on: Boolean) = edit { it[Keys.developerMode] = on }
    suspend fun setAutoConnect(on: Boolean) = edit { it[Keys.autoConnect] = on; it[Keys.autoConnectPrompted] = true }
    suspend fun setTelemetryEnabled(on: Boolean) = edit { it[Keys.telemetry] = on }
    suspend fun setOnboardingDone(done: Boolean) = edit { it[Keys.onboardingDone] = done }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.cockpitDataStore.edit(block)
    }
}
