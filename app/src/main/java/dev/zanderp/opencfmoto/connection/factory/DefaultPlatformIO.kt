// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import android.app.Activity
import android.content.Context
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.BikeProfileHolder
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.VideoPipeline
import java.lang.ref.WeakReference

/**
 * The app's single [PlatformIO] — the seam a [BikeConnectionFactory]-built [BikeConnection] uses to reach
 * the Activity, [LogBus], and the video pipeline (Contracts.kt) without the `connection.factory` package
 * importing any of them directly (Overtake discipline).
 *
 * A Kotlin `object` (process-global singleton), not a class, so call sites pass it the same bare way as
 * [dev.zanderp.opencfmoto.BikeMemory] — `BikeConnectionFactory.create(ctx, qr, BikeMemory,
 * DefaultPlatformIO)` (see [dev.zanderp.opencfmoto.connection.CfmotoConnect]).
 *
 * Two seams need one-time wiring from outside this package, both done at process start in
 * `OpenCfMotoApp.onCreate`:
 *  - [install] stashes the application [Context] (an `object` has no constructor to take one).
 *  - `registerActivityLifecycleCallbacks` feeds [onActivityResumed]/[onActivityPaused] so [activityOrNull]
 *    tracks whichever Activity is actually in the foreground — the Compose `CockpitActivity`, the classic
 *    `MainActivity`, or any future one — from ONE registration site in the Application class instead of
 *    duplicating onResume/onPause overrides across every Activity (the app has no such tracker yet — grepped).
 */
object DefaultPlatformIO : PlatformIO {

    private const val TAG = "PlatformIO"

    private lateinit var appCtx: Context

    /** Call once from `OpenCfMotoApp.onCreate`, before anything can build a factory connection. */
    fun install(context: Context) {
        appCtx = context.applicationContext
    }

    override val appContext: Context
        get() = appCtx

    override val log: (String, String) -> Unit = { tag, msg -> LogBus.log("[$tag] $msg") }

    @Volatile private var foreground: WeakReference<Activity>? = null

    override fun activityOrNull(): Activity? = foreground?.get()

    /** Fed by the `Application.ActivityLifecycleCallbacks` registered in `OpenCfMotoApp.onCreate`. */
    fun onActivityResumed(activity: Activity) {
        foreground = WeakReference(activity)
    }

    /**
     * Only clears if [activity] is still the tracked one: during a screen transition B.onResume can run
     * before A.onPause, and A's later onPause must not wipe out the newly-foregrounded B.
     */
    fun onActivityPaused(activity: Activity) {
        if (foreground?.get() === activity) foreground = null
    }

    private val sink = object : VideoSink {
        /**
         * Mirrors how [dev.zanderp.opencfmoto.EasyConnProber] starts streaming on REQ_RV_DATA_START(112)
         * (EasyConnProber.kt): reuse the shared Android Auto pipeline when AA is already driving one
         * ([AaVideoBridge.pipeline]) — same "use it instead of creating our own Presentation/mirror
         * source" call the prober makes — otherwise start a fresh own-content [VideoPipeline] and prime
         * its first keyframe with [VideoPipeline.onBikeDataStart].
         *
         * [LinkSession] carries no negotiated canvas size — the prober's `negW`/`negH` (learned from the
         * bike's REQ_RV_CONFIG_CAPTURE) stay internal to it, never surfaced on [LinkSession] — so the
         * own-pipeline size falls back to the AA-profile resolution, the same pick
         * [dev.zanderp.opencfmoto.connection.factory.link.YunmoBikeLink] uses for the same reason.
         *
         * Not yet called: [dev.zanderp.opencfmoto.connection.factory.link.EasyConnBikeLink] and
         * `YunmoBikeLink` (Task 6) still own their video path internally end-to-end, unchanged from before
         * this factory existed, so this seam is wired ahead of a future link that hands off a
         * [LinkSession] without an owned video source of its own — harmless while [videoSink] is unused.
         */
        override fun attach(session: LinkSession) {
            val shared = AaVideoBridge.pipeline
            if (shared != null) {
                log(TAG, "videoSink.attach: reusing shared Android Auto pipeline")
                shared.onBikeDataStart()
                return
            }
            val aa = BikeProfileHolder.aaVideo
            val w = if (aa.width >= 64) aa.width else 800
            val h = if (aa.height >= 64) aa.height else 480
            log(TAG, "videoSink.attach: starting own VideoPipeline ${w}x$h")
            val logCb: (String) -> Unit = { msg -> log(TAG, msg) }
            val vp = VideoPipeline(appContext, w, h, logCb)
            vp.start()
            if (vp.isAlive) {
                vp.onBikeDataStart()
            } else {
                log(TAG, "videoSink.attach: VideoPipeline failed to start (own source, ${w}x$h)")
            }
        }
    }

    override fun videoSink(): VideoSink = sink
}
