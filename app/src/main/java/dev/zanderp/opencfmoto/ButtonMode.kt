// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Handlebar-button control model ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Kept as the older name for the same single decision — see [HandlebarAudioMode], which owns it now.
 *
 * `controlAa == true` is "the handlebar drives the dash", the exact inverse of "controls audio". It
 * used to be its own stored flag, which meant two switches could disagree about one thing; they are
 * one value now, so a rider changing either in any screen changes the same setting.
 */
object ButtonMode {
    fun isControlAa(context: Context): Boolean = !HandlebarAudioMode.isAudio(context)

    fun set(context: Context, controlAa: Boolean) {
        HandlebarAudioMode.setAudio(context, !controlAa)
    }
}
