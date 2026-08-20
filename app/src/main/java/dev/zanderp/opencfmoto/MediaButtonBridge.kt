// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * Turns the bike's handlebar buttons into Android Auto navigation.
 *
 * The buttons never reach us over the PXC/Wi-Fi link. What DOES work, verified on the bike:
 *   • short press ▲/▼ → AVRCP **absolute volume** → we read the DIRECTION as a knob click.
 *   • hold enter → AVRCP PLAY/PAUSE passthrough → mapped to ENTER (select).
 * The dash only emits the transport keys once we look like a real player. While capture is on we
 * own those keys for Android Auto UI navigation — control music by navigating that UI, not by
 * giving AVRCP to Spotify. Exclusive [AUDIOFOCUS_GAIN] kept the bars but **paused music**; we use
 * navigation-guidance + MAY_DUCK instead so playback can continue, and hammer the MediaSession so
 * the bars stay on AA.
 *
 * Volume is watched with a [ContentObserver] rather than a remote-volume `VolumeProvider`: the bike
 * sends absolute volume, so there is no volume KEY event to intercept.
 *
 * All of it is gated on [capturingForDash] — in Android Auto that IS [ButtonMode]; while our own
 * map (Overtake) projects. One switch decides which: [HandlebarAudioMode].
 * Toggle "control AA" off (or set ▲/▼ to Volume on your own map) and volume/media behave normally.
 */
class MediaButtonBridge(private val context: Context, private val log: (String) -> Unit) {

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    /** MediaSession / AVRCP "now playing" appearance (bike sees a normal media player). */
    private val mediaAttrs by lazy {
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
    /**
     * Focus + silent track: navigation usage ducks music instead of pausing it (unlike USAGE_MEDIA
     * + exclusive GAIN, which killed Spotify/YT).
     */
    private val navAttrs by lazy {
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private var volumeObserver: ContentObserver? = null
    /** Volume we hold the stream at while capturing, so there's always headroom up AND down. */
    private var pinnedVolume = -1
    /** One-shot guard: the dash only needs re-reading once per session. See [reassert]. */
    private var reasserted = false
    /** Last level we know the stream held — the baseline a press is measured against while unpinned. */
    @Volatile private var lastVolume = -1

    /** The user's own volume, restored when capture is turned off. */
    private var userVolume = -1
    /**
     * When true, the volume [ContentObserver] ignores stream changes (our own pin / Controls slider).
     * Without this, moving the listening slider would fire ▲/▼ as AA navigation.
     */
    @Volatile private var ignoreVolumeChanges = false
    private var focusRequest: AudioFocusRequest? = null
    private var silence: AudioTrack? = null
    /** True while a reclaim is queued after another app stole audio/media-button focus. */
    private var reclaimPending = false
    private var lastReclaimAt = 0L
    private val reclaimRunnable = Runnable {
        reclaimPending = false
        if (capturingForDash()) reclaimCapture("focus-loss")
    }
    private var keepAliveTicks = 0

    /** True while the audio-focus request is ours. Re-taking it churns the rider's music for nothing. */
    @Volatile private var holdsFocus = false

    /**
     * Set once a media key arrives. Only an external device can send one, so it is PROOF the handlebar
     * has a path to us — trusted over any profile check, which can be wrong. Learned the hard way: an
     * inference that overrules evidence is how the rocker got written off in the first place.
     */
    @Volatile private var sawExternalKey = false

    /** Last known "the bike can reach us over Bluetooth", so a change can be logged and acted on once. */
    @Volatile private var btLinkUp: Boolean? = null

    /**
     * Can the handlebar actually reach this phone right now?
     *
     * It matters because the bike's ▲/▼ arrive as a plain volume write, which is indistinguishable from
     * the rider pressing the phone's OWN volume keys. On 2026-08-20 the bike never connected over
     * Bluetooth (`connectedMac=null` for the whole session) and the consequence was absurd: the handlebar
     * did nothing, while the phone's volume buttons scrolled the map on the dash. With no device
     * connected, a volume change is the rider's and nothing else.
     */
    private fun bikeCanReachUs(): Boolean =
        sawExternalKey || runCatching { BluetoothHelper.status(context).connected }.getOrDefault(false)
    /** Last time a bike media key was handled — skip focus re-request while the rider is tapping. */
    private var lastKeyAt = 0L
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!capturingForDash() || session == null) return
            keepAliveTicks++
            // Session refresh only while keys are flying — re-requesting focus mid-tap makes the
            // BT stack re-deliver the same press (looks like "need 3 taps for one step").
            refreshPlayingAppearance(reason = "keep-alive")
            // The bike's Bluetooth can connect long after we started (rider turns BT on, the dash takes
            // its time). Notice the moment it does and start holding the volume then, instead of leaving
            // the handlebar dead for the whole ride.
            val up = bikeCanReachUs()
            if (btLinkUp != up) {
                btLinkUp = up
                log("[BTN] bike over Bluetooth: ${if (up) "CONNECTED — the handlebar can reach us; holding the volume to read it" else "not connected — the handlebar has no path here"}")
                if (up) maybePinVolume("bike connected") else unpinVolume()
            }
            val idle = SystemClock.elapsedRealtime() - lastKeyAt > KEY_IDLE_BEFORE_FOCUS_MS
            if (idle && keepAliveTicks % 3 == 0) requestButtonFocus(reason = "keep-alive")
            handler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    /**
     * Should the bridge CAPTURE the handlebar (pin ▲/▼, hold AVRCP, route presses to dash navigation)
     * rather than leave the buttons to the phone's audio?
     *
     * One question, one answer: [HandlebarAudioMode]. Off (the default) the handlebar drives whatever is
     * on the dash — our map, or the Android Auto UI. On, we do not touch a thing. Nothing else gets a
     * vote. The previous version also consulted a second toggle AND an auto-guess about whether the bike
     * had a rocker at all, either of which could quietly cancel what the rider had chosen.
     *
     * The bridge only exists while the service projects or Android Auto is up, so this never holds the
     * phone's volume during ordinary use.
     */
    private fun capturingForDash(): Boolean = !HandlebarAudioMode.isAudio(context)

    fun start() {
        handler.post {
            try {
                val s = MediaSession(context, "OpenCfMoto")
                s.setCallback(callback)
                val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
                // An active PLAYING session is what makes the system route media buttons to us.
                s.setPlaybackState(
                    PlaybackState.Builder().setActions(actions)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
                )
                session = s
                instance = this
                val on = capturingForDash()
                if (on) takeMediaFocus()
                s.isActive = on
                s.setPlaybackToLocal(mediaAttrs)
                if (on) maybePinVolume("start")
                startVolumeObserver()
                log("[BTN] bridge ready — mode=${if (on) "control dash (media focus + volume hijacked)" else "control media"} " +
                    "presence=${ButtonPresencePrefs.summarize(context)}")
                scheduleReassertWhenBikeUp()
            } catch (e: Exception) {
                log("[BTN] media session failed: $e")
            }
        }
    }

    /**
     * Re-announce ourselves to the dash once the bike is actually connected. We take media focus the
     * moment the service starts — before the bike's PXC link comes up. The dash reads the player's
     * capabilities when its AVRCP link forms and then never re-reads; this forces a re-read at the
     * right moment. Only while capture is on (music is paused anyway, so the brief focus drop is free).
     */
    private fun scheduleReassertWhenBikeUp() {
        if (reasserted) return
        val poll = object : Runnable {
            var waited = 0L
            override fun run() {
                if (reasserted || !capturingForDash()) return
                if (BikeLink.prober?.isStreaming == true) {
                    reasserted = true
                    handler.postDelayed({ reassert() }, REASSERT_SETTLE_MS)
                    return
                }
                waited += REASSERT_POLL_MS
                if (waited < REASSERT_GIVEUP_MS) handler.postDelayed(this, REASSERT_POLL_MS)
                else log("[BTN] no bike link within ${REASSERT_GIVEUP_MS / 1000}s — skipping media re-assert")
            }
        }
        handler.postDelayed(poll, REASSERT_POLL_MS)
    }

    private fun reassert() {
        if (!capturingForDash()) return
        try {
            // Soft re-announce: flip session active + refresh metadata/pin. Avoid abandon/re-take of
            // audio focus — that hard pause/resume cycle is what made music/nav sound "stuck" on
            // some Samsungs while still letting the dash re-read us as the AVRCP player.
            log("[BTN] bike link up — re-announcing media session so the dash re-reads our player")
            session?.isActive = false
            handler.postDelayed({
                try {
                    publishMetadata()
                    session?.isActive = true
                    maybePinVolume("reassert")
                    postMediaNotification()
                    log("[BTN] media session re-announced")
                } catch (e: Exception) {
                    log("[BTN] re-assert failed: $e")
                }
            }, REASSERT_GAP_MS)
        } catch (e: Exception) {
            log("[BTN] re-assert failed: $e")
        }
    }

    /**
     * Live toggle: grab (true) or release (false) the bike's AVRCP keys.
     * ON = bars always drive Android Auto UI (never Spotify skip). Control music by navigating
     * the AA UI with those same bars / the on-screen pad. OFF = normal media buttons.
     */
    fun setCaptureActive(on: Boolean) {
        handler.post {
            try {
                if (on) takeMediaFocus() else releaseMediaFocus()
                session?.isActive = on
                if (on) {
                    maybePinVolume("capture-on")
                    startKeepAlive()
                    } else {
                    stopKeepAlive()
                    cancelReclaim()
                    unpinVolume()
                    heldSelect.reset()
                    heldBack.reset()
                    heldFwd.reset()
                    cancelPendingTaps()
                }
                log("[BTN] capture ${if (on) "ON — bars → AA UI (music can play; control it in the AA UI)" else "OFF — bars control media/volume"}")
            } catch (e: Exception) {
                log("[BTN] setCaptureActive failed: $e")
            }
        }
    }

    /**
     * AA started guidance/media audio — reinforce the MediaSession only. Do not grab exclusive
     * focus (that pauses music the rider just started in AA).
     */
    fun yieldForAaAudio(@Suppress("UNUSED_PARAMETER") holdMs: Long) {
        handler.post {
            if (!capturingForDash()) return@post
            log("[BTN] AA audio active — refreshing button session (music keeps playing)")
            startSilence()
            refreshPlayingAppearance(reason = "aa-audio")
        }
    }

    private fun takeMediaFocus() {
        requestButtonFocus(reason = "take-focus")
        startSilence()
        refreshPlayingAppearance(reason = "take-focus")
        startKeepAlive()
    }

    /**
     * Duckable nav focus — music keeps playing (possibly ducked). Never exclusive GAIN.
     */
    private fun requestButtonFocus(reason: String) {
        // Already ours? Then leave it alone. This used to abandon and re-request unconditionally, and the
        // keep-alive runs it every 12s — so the rider's music un-ducked and re-ducked on a 12-second
        // cycle for the whole ride. Nothing needed re-taking; holding focus is not something that decays.
        // (Owner, 2026-08-20: "the handlebar drives the map now, but it also controls the audio.")
        if (holdsFocus) {
            if (reason == "keep-alive") return
            log("[BTN] already hold audio focus — not re-taking it ($reason)")
            return
        }
        try {
            try { focusRequest?.let { audio.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navAttrs)
                .setOnAudioFocusChangeListener { change -> onAudioFocusChange(change) }
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            val granted = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            holdsFocus = granted
            log("[BTN] audio focus ${if (granted) "granted — held from here on, music ducks ONCE (not every 12s)" else "DENIED"} ($reason)")
        } catch (e: Exception) {
            log("[BTN] audio focus failed: $e")
        }
    }

    private fun onAudioFocusChange(change: Int) {
        val name = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> "focus=$change"
        }
        log("[BTN] audio focus → $name")
        if (!capturingForDash()) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                startSilence()
                refreshPlayingAppearance(reason = "focus-gain")
            }
            // Music is playing — expected with MAY_DUCK. Keep the MediaSession hot; do not steal
            // exclusive focus (that pauses Spotify again).
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                refreshPlayingAppearance(reason = "ducked")
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                holdsFocus = false
                scheduleReclaim(name)
            }
        }
    }

    private fun scheduleReclaim(reason: String) {
        if (!capturingForDash()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReclaimAt < RECLAIM_MIN_GAP_MS) {
            if (!reclaimPending) {
                reclaimPending = true
                handler.postDelayed(reclaimRunnable, RECLAIM_MIN_GAP_MS)
            }
            return
        }
        if (reclaimPending) return
        reclaimPending = true
        log("[BTN] media focus lost ($reason) — reclaiming bike buttons in ${RECLAIM_DELAY_MS}ms")
        handler.postDelayed(reclaimRunnable, RECLAIM_DELAY_MS)
    }

    private fun cancelReclaim() {
        reclaimPending = false
        handler.removeCallbacks(reclaimRunnable)
    }

    /** Pull AVRCP back with duckable nav focus + session flip — does not pause music. */
    private fun reclaimCapture(reason: String) {
        if (!capturingForDash()) return
        lastReclaimAt = SystemClock.elapsedRealtime()
        log("[BTN] reclaiming media buttons ($reason) — nav duck (music keeps playing)")
        try {
            requestButtonFocus(reason = "reclaim")
            startSilence()
            session?.isActive = false
            handler.postDelayed({
                try {
                    if (!capturingForDash()) return@postDelayed
                    refreshPlayingAppearance(reason = "reclaim")
                    session?.isActive = true
                    maybePinVolume("reclaim")
                    log("[BTN] media buttons reclaimed")
                } catch (e: Exception) {
                    log("[BTN] reclaim failed: $e")
                }
            }, REASSERT_GAP_MS)
        } catch (e: Exception) {
            log("[BTN] reclaim failed: $e")
        }
    }

    private fun startKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
        if (capturingForDash()) {
            handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_MS)
        }
    }

    private fun stopKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
    }

    /** Keep metadata / PLAYING state / MediaStyle notification fresh so we stay the button target. */
    private fun refreshPlayingAppearance(reason: String) {
        try {
            publishMetadata()
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                            PlaybackState.ACTION_REWIND
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            session?.isActive = true
            postMediaNotification(quiet = reason == "keep-alive")
            if (reason != "keep-alive") log("[BTN] playing appearance refreshed ($reason)")
        } catch (e: Exception) {
            log("[BTN] refreshPlayingAppearance failed: $e")
        }
    }

    /** Publish a fake "now playing" track over AVRCP so the dash treats us as a playing player. */
    private fun publishMetadata() {
        try {
            session?.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Android Auto control")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "◀ ▶ knob · ×2 ←→ · ★ OK · ★★ Back · ★hold Home")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "OpenCfMoto")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, TRACK_MS)
                    .build()
            )
        } catch (e: Exception) {
            log("[BTN] metadata failed: $e")
        }
    }

    /** Post a MediaStyle notification bound to our session so the system treats us as a media app. */
    private fun postMediaNotification(quiet: Boolean = false) {
        val s = session ?: return
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(MEDIA_CHANNEL, context.getString(R.string.notif_media_channel), NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(context, MEDIA_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(context.getString(R.string.notif_media_title))
                .setContentText(context.getString(R.string.notif_media_text))
                .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build()
            nm.notify(MEDIA_NOTIF_ID, n)
            if (!quiet) log("[BTN] media notification posted (registers us as a media player)")
        } catch (e: Exception) {
            log("[BTN] media notification failed: $e")
        }
    }

    private fun cancelMediaNotification() {
        try { context.getSystemService(NotificationManager::class.java).cancel(MEDIA_NOTIF_ID) } catch (_: Exception) {}
    }

    private fun releaseMediaFocus() {
        stopKeepAlive()
        cancelReclaim()
        cancelMediaNotification()
        stopSilence()
        try { focusRequest?.let { audio.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focusRequest = null
        holdsFocus = false
    }

    /** A looping silent track: makes us a genuinely "playing" media app so we win button routing. */
    private fun startSilence() {
        if (silence != null) return
        try {
            val rate = 8000
            val frames = rate            // 1 s of silence, looped forever
            val zeros = ShortArray(frames)
            val t = AudioTrack.Builder()
                .setAudioAttributes(navAttrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(zeros, 0, zeros.size)
            t.setLoopPoints(0, frames, -1)
            // Near-silent nav stream — must not mute Spotify/YT under MAY_DUCK.
            t.setVolume(0.01f)
            t.play()
            silence = t
        } catch (e: Exception) {
            log("[BTN] silent track failed: $e")
        }
    }

    private fun stopSilence() {
        try { silence?.pause(); silence?.flush(); silence?.release() } catch (_: Exception) {}
        silence = null
    }

    fun stop() {
        if (instance === this) instance = null
        cancelPendingTaps()
        stopKeepAlive()
        cancelReclaim()
        stopVolumeObserver()
        unpinVolume()
        releaseMediaFocus()
        try { session?.isActive = false } catch (_: Exception) {}
        try { session?.release() } catch (_: Exception) {}
        session = null
    }

    // ── volume as a navigation source ────────────────────────────────────────────────────────────

    /** Hold the volume while the handlebar drives the dash; release it the moment it does not. */
    private fun maybePinVolume(reason: String) {
        // Nothing left to weigh. If the handlebar drives the dash we hold the stream so the dash's
        // absolute-volume writes can be read as presses; if it drives audio we release it. The guard that
        // used to sit here — "skip if we believe this bike has no rocker" — is what let an inference
        // overrule the switch, and it could never be revoked once wrong.
        if (!capturingForDash()) {
            if (pinnedVolume >= 0) {
                log("[BTN] skip pin ($reason) — the handlebar is set to control audio; releasing the volume")
                unpinVolume()
            }
            return
        }
        if (!bikeCanReachUs()) {
            // Holding the volume for a handlebar that has no path here only takes the rider's volume
            // away for nothing.
            if (pinnedVolume >= 0) unpinVolume()
            log("[BTN] skip pin ($reason) — the bike is not connected over Bluetooth; your volume stays yours")
            return
        }
        pinVolume()
    }

    /**
     * Pin the music stream while capturing. The bike sends AVRCP *absolute* volume, so we read the
     * DIRECTION of each change. We park at the rider's listening level (clamped away from 0 / max)
     * so nav prompts stay at the volume they chose on Controls; mid is only the fallback when we
     * have no preference yet. At the extreme ends one nav direction can stop registering — the
     * Controls slider hint covers that.
     */
    private fun pinVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (userVolume < 0) userVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val preferred = if (userVolume >= 0) userVolume else max / 2
            pinnedVolume = preferred.coerceIn(1, (max - 1).coerceAtLeast(1))
            ignoreVolumeChanges = true
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0)
            } finally {
                handler.postDelayed({ ignoreVolumeChanges = false }, 150)
            }
            lastVolume = pinnedVolume
            log("[BTN] volume pinned at $pinnedVolume/$max (listening=$userVolume)")
        } catch (e: Exception) {
            log("[BTN] pinVolume failed: $e")
        }
    }

    /**
     * The 90-second "this bike has no ▲/▼ rocker" probe used to live here. It is gone.
     *
     * It concluded from silence — 90s of projecting with no press, which is simply what a ride looks
     * like — then wrote that verdict per bike where it outranked the rider's own setting, with nothing on
     * screen to show for it. And it was one-way: the only code that could revoke it sat behind the very
     * gate it closed. It cost the 450NK its handlebar twice. What it guarded against, a pod with no
     * rocker holding the phone's volume, is now one switch away in the rider's hands.
     */

    /**
     * Re-decide [capturingForDash] against the CURRENT world and apply it.
     *
     * The decision used to be frozen at [start]: the service starts the bridge on ACTION_GPX_WAKE, and
     * if [GpxSession.active] had not flipped yet the own-map leg read false, the session was left
     * inactive and nothing ever re-ran the check — the whole ride went by with the handlebar on plain
     * phone volume. Called from the service every time it (re)enters a projecting state; idempotent.
     */
    fun refreshCapturePolicy() {
        handler.post {
            val on = capturingForDash()
            if (session?.isActive == on) return@post
            log("[BTN] capture policy re-evaluated — now ${if (on) "ON (dash owns the handlebar)" else "OFF (media/volume)"}")
            setCaptureActive(on)
        }
    }

    /** Re-apply the hold/release decision — called when the rider flips the switch. */
    fun refreshVolumePresencePolicy() {
        handler.post {
            if (capturingForDash()) maybePinVolume("switch changed") else unpinVolume()
        }
    }

    /** Stop pinning and give the user their volume back. */
    private fun unpinVolume() {
        pinnedVolume = -1
        if (userVolume >= 0) {
            ignoreVolumeChanges = true
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, userVolume, 0)
            } catch (_: Exception) {
            } finally {
                handler.postDelayed({ ignoreVolumeChanges = false }, 150)
            }
            lastVolume = userVolume
            userVolume = -1
        }
    }

    /**
     * Set the phone's music volume from the Controls slider. While handlebar capture is on this
     * also moves the AVRCP pin so nav prompts follow the slider without counting as ▲/▼ presses.
     */
    fun setListeningVolume(level: Int) {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = level.coerceIn(0, max)
            userVolume = v
            ignoreVolumeChanges = true
            try {
                if (capturingForDash() && pinnedVolume >= 0) {
                    pinnedVolume = v.coerceIn(1, (max - 1).coerceAtLeast(1))
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0)
                } else {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                }
            } finally {
                handler.postDelayed({ ignoreVolumeChanges = false }, 150)
            }
        } catch (e: Exception) {
            log("[BTN] setListeningVolume failed: $e")
        }
    }

    /** Current music volume and stream max (for the Controls SeekBar). */
    fun volumeLevels(): Pair<Int, Int> {
        val max = try { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
        val now = when {
            userVolume >= 0 -> userVolume
            else -> try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { max / 2 }
        }
        return now.coerceIn(0, max) to max.coerceAtLeast(1)
    }

    /**
     * Watch the stream volume: the bike's up/down arrive as AVRCP absolute volume (no key event at
     * all), so the direction of the change IS the button press. Re-pin immediately afterwards.
     */
    private fun startVolumeObserver() {
        if (volumeObserver != null) return
        lastVolume = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (ignoreVolumeChanges) return
                val now = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { return }
                if (!capturingForDash()) { lastVolume = now; return }

                // Two ways in. Normally we hold a pin and measure against it. But if the rocker was
                // (wrongly) written off as ABSENT nothing is pinned, and the press would be invisible —
                // that is the dead end the rider hit twice. So when ▲/▼ are SET to navigate and the bike
                // is streaming, an unexplained volume move is proof the rocker exists: adopt it, and treat
                // this very press as the gesture. Never in Volume mode — there the rocker is the rider's
                // volume control and must stay theirs.
                if (!bikeCanReachUs()) {
                    // Nothing is connected, so nobody but the rider moved this. Leave it alone.
                    lastVolume = now
                    return
                }
                val relearn = pinnedVolume < 0
                if (relearn && BikeLink.prober?.isStreaming != true) {
                    lastVolume = now
                    return
                }

                // What the volume must read once we are done: the pin, or — while adopting — whatever the
                // rider had before the dash touched it.
                val base = if (relearn) (if (lastVolume >= 0) lastVolume else now) else pinnedVolume
                // This observer watches ALL of Settings.System, so most of what arrives here is somebody
                // else's business — screen brightness above all, which moves constantly while riding. Only
                // an actual change in level is a button press; everything else leaves without a trace.
                if (now == base) return
                val jump = now - base            // signed, in Android volume steps
                val up = jump > 0
                val dir = if (up) "UP" else "DOWN"
                if (relearn) {
                    log("[BTN] ▲/▼ moved while nothing was pinned and the map is streaming — the rocker EXISTS; adopting it now and using THIS press")
                }
                ButtonPresencePrefs.markVolumeSeen(context)
        
                // Put the volume back FIRST, before handling the gesture: BACK/HOME redraw the dash and
                // take a while, and until the level is restored a follow-up press measures from the wrong
                // base — and, on the adoption path, the rider would be left with the volume the dash set.
                // The press must drive the map and leave no mark on their audio.
                ignoreVolumeChanges = true
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, base, 0)
                } catch (_: Exception) {
                } finally {
                    handler.postDelayed({ ignoreVolumeChanges = false }, 80)
                }
                lastVolume = base
                if (relearn) {
                    // Hold that same level from now on, so every later press is measured, not guessed.
                    val max = try { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
                    if (userVolume < 0) userVolume = base
                    pinnedVolume = base.coerceIn(1, (max - 1).coerceAtLeast(1))
                    if (pinnedVolume != base) {
                        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, pinnedVolume, 0) } catch (_: Exception) {}
                        lastVolume = pinnedVolume
                    }
                    log("[BTN] rocker adopted — volume restored to $base and pinned at $pinnedVolume (listening=$userVolume)")
                }

                // A big single write means the dash coalesced a double-tap into one jump — take the
                // fast path and fire ×2 immediately. Otherwise let the time-window detector decide,
                // so dashes that send two separate ~1-step writes still register a double-tap.
                val forceDouble = kotlin.math.abs(jump) >= DOUBLE_TAP_STEPS
                // Volume UP = backward (previous item), DOWN = forward (next item) — same semantics as
                // the 800MT's ◀/▶ track keys, so both layouts drive the same gestures.
                val single = if (up) ButtonGesture.NAV_BACK else ButtonGesture.NAV_FWD
                val double = if (up) ButtonGesture.NAV_BACK_DOUBLE else ButtonGesture.NAV_FWD_DOUBLE
                log("[BTN] volume $dir ($base→$now, jump=$jump)${if (forceDouble) " ×2" else ""}")
                detectDoubleTap(single, double, forceDouble)
            }
        }
        try {
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
            volumeObserver = obs
        } catch (e: Exception) {
            log("[BTN] volume observer failed: $e")
        }
    }

    private fun stopVolumeObserver() {
        volumeObserver?.let { try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        volumeObserver = null
    }

    // ── single vs. double-tap detection ──────────────────────────────────────────────────────────

    /** Per-channel state: a scheduled single press, and when we last saw a tap on this channel. */
    private class Tap(var pending: Runnable? = null, var lastAt: Long = 0L)
    /** Keyed by the *single* gesture so ▲ then ▼ within the window stays two singles, not a double. */
    private val taps = HashMap<ButtonGesture, Tap>()

    /**
     * Decide between a single press and a double-tap. A double-tap doesn't always arrive as one big
     * event: volume dashes that coalesce it are handled by [forceDouble] (fired instantly, no wait);
     * dashes that send two separate presses — and the discrete Select play/pause — are caught by
     * waiting [ButtonTimingPrefs.doubleTapMs] for a second same-channel tap. A single press therefore
     * fires only after that window, which is the cost of telling the two apart.
     *
     * Exception: [ButtonTimingPrefs.snappySingles] (default on) or
     * [ButtonClusterPreset.prefersInstantSingles] skips the wait — fire the single now; a second
     * tap in-window still runs ×2. Coalesced volume jumps use [forceDouble] immediately.
     */
    private fun detectDoubleTap(single: ButtonGesture, double: ButtonGesture, forceDouble: Boolean) {
        val ch = taps.getOrPut(single) { Tap() }
        val now = SystemClock.elapsedRealtime()
        val gap = now - ch.lastAt
        ch.lastAt = now
        val window = ButtonTimingPrefs.doubleTapMs(context)

        if (forceDouble) {
            ch.pending?.let { handler.removeCallbacks(it); ch.pending = null }
            run(double)
            return
        }

        // Eager singles: fire the tap now. A second tap inside the window still runs ×2 — no lag
        // waiting to disambiguate (BT echoes used to turn every press into D-pad when we waited).
        val snappy = ButtonTimingPrefs.snappySingles(context) ||
            ButtonClusterPreset.prefersInstantSingles(context)
        if (snappy) {
            if (ch.pending != null && gap in 1 until window) {
                ch.pending?.let { handler.removeCallbacks(it) }
                ch.pending = null
                run(double)
                return
            }
            run(single)
            val clear = Runnable { ch.pending = null }
            ch.pending = clear
            handler.postDelayed(clear, window)
            return
        }

        val wasPending = ch.pending != null
        ch.pending?.let { handler.removeCallbacks(it); ch.pending = null }
        if (wasPending) {
            run(double)
        } else {
            val r = Runnable { ch.pending = null; run(single) }
            ch.pending = r
            handler.postDelayed(r, window)
        }
    }

    private fun cancelPendingTaps() {
        taps.values.forEach { it.pending?.let(handler::removeCallbacks) }
        taps.clear()
    }

    // ── gesture → action dispatch ────────────────────────────────────────────────────────────────

    /** Run whatever [ButtonMap] says this gesture should do (read per press, so changes are live). */
    private fun run(gesture: ButtonGesture) {
        if (!capturingForDash()) return   // not capturing: leave the buttons to music / phone volume
        val action = ButtonMap.get(context, gesture)
        log("[BTN] ${gesture.label} → ${action.label}")
        perform(action)
    }

    private fun perform(action: ButtonAction) {
        when (action) {
            ButtonAction.NONE -> {}
            ButtonAction.KNOB_FORWARD -> scroll(+1)
            ButtonAction.KNOB_BACK -> scroll(-1)
            ButtonAction.SELECT -> key(AaInput.KEY_ENTER)
            ButtonAction.BACK -> key(AaInput.KEY_BACK)
            ButtonAction.HOME -> key(AaInput.KEY_HOME)
            ButtonAction.ASSISTANT -> key(AaInput.KEY_ASSISTANT)
            ButtonAction.DPAD_UP -> key(AaInput.KEY_UP)
            ButtonAction.DPAD_DOWN -> key(AaInput.KEY_DOWN)
            ButtonAction.DPAD_LEFT -> key(AaInput.KEY_LEFT)
            ButtonAction.DPAD_RIGHT -> key(AaInput.KEY_RIGHT)
            ButtonAction.NAV_1 -> navigate(0)
            ButtonAction.NAV_2 -> navigate(1)
            ButtonAction.NAV_3 -> navigate(2)
        }
    }

    /** Map Presentation wins while projected; otherwise Android Auto. */
    private fun key(code: Int) {
        val map = MapInputBridge.keySink
        if (map != null) {
            map(code)
            return
        }
        val aa = AaVideoBridge.keySink
        if (aa != null) {
            aa(code)
            return
        }
        log("[BTN] no Map/AA session — key $code dropped")
    }

    private fun scroll(delta: Int) {
        val map = MapInputBridge.scrollSink
        if (map != null) {
            map(delta)
            return
        }
        val aa = AaVideoBridge.scrollSink
        if (aa != null) {
            aa(delta)
            return
        }
        log("[BTN] no Map/AA session — scroll $delta dropped")
    }

    private fun navigate(slot: Int) {
        NavLauncher.navigate(context, SavedPlaces.query(context, slot), log)
    }

    // ── where gestures come from ─────────────────────────────────────────────────────────────────

    /**
     * One physical button that can express tap / ×2 / hold (needs KEY_UP or key-repeat).
     * Shared by Select and by ◀/▶ track keys — not by ▲/▼ volume (no release event).
     */
    private class HeldButton(
        val single: ButtonGesture,
        val double: ButtonGesture,
        val longG: ButtonGesture,
        val name: String,
    ) {
        var downAt = 0L
        var longFromRepeat = false
        fun reset() { downAt = 0L; longFromRepeat = false }
    }

    private val heldSelect = HeldButton(
        ButtonGesture.SELECT_PRESS, ButtonGesture.SELECT_DOUBLE, ButtonGesture.SELECT_LONG, "select",
    )
    private val heldBack = HeldButton(
        ButtonGesture.NAV_BACK, ButtonGesture.NAV_BACK_DOUBLE, ButtonGesture.NAV_BACK_LONG, "backward",
    )
    private val heldFwd = HeldButton(
        ButtonGesture.NAV_FWD, ButtonGesture.NAV_FWD_DOUBLE, ButtonGesture.NAV_FWD_LONG, "forward",
    )

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val ke = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return true
            when (ke.action) {
                KeyEvent.ACTION_DOWN -> {
                    val repeat = if (ke.repeatCount > 0) " repeat=${ke.repeatCount}" else ""
                    log("[BTN] media key ${KeyEvent.keyCodeToString(ke.keyCode)} down (code=${ke.keyCode})$repeat")
                    onKeyDown(ke.keyCode, ke.repeatCount)
                }
                KeyEvent.ACTION_UP -> onKeyUp(ke.keyCode)
            }
            return true
        }
        // Fallbacks: the bike takes the raw-key path above; other dashes / BT remotes may dispatch
        // here. These carry no hold timing, so they can only ever be a short press / double-tap.
        override fun onPlay() = detectDoubleTap(
            ButtonGesture.SELECT_PRESS, ButtonGesture.SELECT_DOUBLE, forceDouble = false,
        )
        override fun onPause() = detectDoubleTap(
            ButtonGesture.SELECT_PRESS, ButtonGesture.SELECT_DOUBLE, forceDouble = false,
        )
    }

    private fun isSelectKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> true
        else -> false
    }

    private fun heldFor(keyCode: Int): HeldButton? = when {
        isSelectKey(keyCode) -> heldSelect
        keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> heldFwd
        keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND -> heldBack
        else -> null
    }

    /**
     * Discrete track / select keys: defer tap vs hold until KEY_UP (or a key-repeat).
     * Volume ▲/▼ never reach here — they have no release, only [detectDoubleTap] via the observer.
     */
    private fun onKeyDown(keyCode: Int, repeatCount: Int = 0) {
        val held = heldFor(keyCode) ?: return
        sawExternalKey = true
        ButtonPresencePrefs.markTrackSeen(context)
        lastKeyAt = SystemClock.elapsedRealtime()
        if (repeatCount > 0) {
            // Setup → Hold detection off: ignore key-repeat so a long physical press stays a tap.
            if (!ButtonTimingPrefs.holdsEnabled(context)) return
            if (!held.longFromRepeat &&
                ButtonMap.get(context, held.longG) != ButtonAction.NONE
            ) {
                log("[BTN] ${held.name} key-repeat → long press")
                held.longFromRepeat = true
                held.downAt = 0L
                taps[held.single]?.pending?.let(handler::removeCallbacks)
                taps[held.single]?.pending = null
                run(held.longG)
            }
            return
        }
        // Second DOWN while still holding — ignore so we don't invent a long press from key spam.
        if (held.downAt != 0L) return
        held.downAt = SystemClock.elapsedRealtime()
    }

    private fun onKeyUp(keyCode: Int) {
        val held = heldFor(keyCode) ?: return
        lastKeyAt = SystemClock.elapsedRealtime()
        if (held.longFromRepeat) {
            held.reset()
            return
        }
        val downAt = held.downAt
        held.downAt = 0L
        if (downAt == 0L) return // UP without a matching DOWN we own
        val heldMs = SystemClock.elapsedRealtime() - downAt
        val holdsOn = ButtonTimingPrefs.holdsEnabled(context)
        val long = holdsOn && heldMs >= ButtonTimingPrefs.longPressMs(context)
        log(
            "[BTN] ${held.name} held ${heldMs}ms → " +
                when {
                    long -> "long press"
                    !holdsOn -> "tap (hold detection off)"
                    else -> "tap"
                },
        )
        if (long) {
            taps[held.single]?.pending?.let(handler::removeCallbacks)
            taps[held.single]?.pending = null
            run(held.longG)
        } else {
            detectDoubleTap(held.single, held.double, forceDouble = false)
        }
    }

    companion object {
        /** Duration of the fake track we advertise, so the dash sees a normal "now playing". */
        private const val TRACK_MS = 3_600_000L

        /**
         * How big a volume jump means the rider double-tapped rather than tapped once. A double-tap
         * does NOT arrive as two events — the bike coalesces the presses into a single AVRCP absolute
         * volume, so the *size* of the jump is the press count. The jump is logged on every press: if
         * a dash calibrates differently, that log line is what to re-read.
         */
        private const val DOUBLE_TAP_STEPS = 3

        /** Don't re-request audio focus while the rider is actively pressing. */
        private const val KEY_IDLE_BEFORE_FOCUS_MS = 2_500L

        private const val REASSERT_POLL_MS = 1_000L
        private const val REASSERT_GIVEUP_MS = 90_000L
        /** Let the dash's AVRCP link settle after the bike connects before poking it. */
        private const val REASSERT_SETTLE_MS = 3_000L
        /** Long enough that the drop and re-take read as two events, not a no-op. */
        private const val REASSERT_GAP_MS = 500L
        private const val RECLAIM_DELAY_MS = 500L
        private const val RECLAIM_MIN_GAP_MS = 2_000L
        /** Session refresh cadence; soft focus every 3rd tick when idle. */
        private const val KEEP_ALIVE_MS = 4_000L
        /** No ▲/▼ while streaming → mark rocker absent and stop pinning volume. */
        private const val MEDIA_CHANNEL = "opencfmoto_media"
        private const val MEDIA_NOTIF_ID = 3   // must not collide with AndroidAutoService's NOTIF_ID (2)

        /** The live bridge (when Android Auto is running), so the settings toggle can reach it. */
        @Volatile var instance: MediaButtonBridge? = null
            private set

        /**
         * Music volume for the Controls slider — works even before AA starts (plain [AudioManager]).
         * While the bridge is capturing handlebar volume for nav, updates go through
         * [setListeningVolume] so they don't fire AA knob steps.
         */
        fun volumeLevels(context: Context): Pair<Int, Int> {
            instance?.let { return it.volumeLevels() }
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            return now to max
        }

        fun setVolume(context: Context, level: Int) {
            val bridge = instance
            if (bridge != null) {
                bridge.setListeningVolume(level)
                return
            }
            try {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
            } catch (_: Exception) {
            }
        }
    }
}
