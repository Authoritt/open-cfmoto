// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/** What the bike needs switched on. Each case only differs by its two strings. */
enum class RadioNeed { WIFI, BLUETOOTH }

/**
 * One dialog for "turn this radio on so the bike can connect".
 *
 * There used to be two different asks, neither of them ours: a MaterialAlertDialog in the connect path
 * with its title and buttons hardcoded in English ("Turn on Wi‑Fi" / "Dismiss"), and, for Bluetooth, the
 * system's own enable prompt — system-styled, system-worded, and jarring in the middle of a Spanish app.
 * This replaces both with a single cockpit-styled, localized dialog that says WHY the bike needs it and
 * what tapping the button will do; the platform action (Wi-Fi panel, Bluetooth enable request) is then
 * triggered by the caller, so the rider always gets our explanation first.
 *
 * Reusable by design: a new radio or a new caller is two strings and a `when` branch, not another dialog.
 */
@Composable
fun RadioNeededDialog(
    need: RadioNeed,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalCockpitColors.current
    val title = stringResource(
        when (need) {
            RadioNeed.WIFI -> R.string.ovk_radio_wifi_title
            RadioNeed.BLUETOOTH -> R.string.ovk_radio_bt_title
        },
    )
    val body = stringResource(
        when (need) {
            RadioNeed.WIFI -> R.string.ovk_radio_wifi_body
            RadioNeed.BLUETOOTH -> R.string.ovk_radio_bt_body
        },
    )
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface1)
                .border(1.dp, c.line, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(body, color = c.inkDim, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.ovk_cancel),
                    color = c.inkDim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    stringResource(R.string.ovk_radio_enable),
                    color = c.onIgnition,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.ignition)
                        .clickable(onClick = onEnable)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
