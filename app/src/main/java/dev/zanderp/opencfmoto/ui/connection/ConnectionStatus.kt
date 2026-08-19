// SPDX-License-Identifier: AGPL-3.0-or-later
// The cockpit's ONE reading of "what is the connection doing right now", shared by the dashboard gauge and
// by Scan's Conectar. Two screens narrating the same connect with two copies of these rules is how they
// drift: the gauge says "Reconectando 2/3" while the other still says "Conectando…". This is the copy.
package dev.zanderp.opencfmoto.ui.connection

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.connection.BikeConnectionHolder
import dev.zanderp.opencfmoto.connection.factory.ConnState
import dev.zanderp.opencfmoto.ui.components.StatusKind
import dev.zanderp.opencfmoto.ui.components.kind
import kotlinx.coroutines.flow.collect

/**
 * LocalContext inside CockpitActivity's `setContent` is the Activity today, but unwrap defensively so a
 * future ContextThemeWrapper / @Preview / ComposeView host can't crash Conectar with a hard cast.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * What the rider should be told about the connection, plus the two raw signals a screen may need to DECIDE
 * something (not just draw it).
 *
 * [kind]/[text] are the drawable part: the gauge colour and the one line under it. [phase] and [factory] are
 * the raw inputs, kept because "is it connected?" is a narrower question than "does the gauge look green"
 * — see [linkUp].
 */
data class ConnectionStatus(
    val kind: StatusKind,
    val text: String,
    /** The legacy process-global phase (`ConnectionState.flow`). */
    val phase: Phase,
    /** The live factory connection's own state, or null when no factory connection exists. RAW: unlike the
     *  gauge's view of it, this is not filtered by whether projection has taken over. */
    val factory: ConnState?,
) {
    /**
     * A link that ACTUALLY formed: the factory reached `Connected`, or the classic prober flipped the
     * process-global to `STREAMING`.
     *
     * Deliberately NOT `kind == LIVE`: `CfmotoConnect.startCfmotoMap` stamps [Phase.MIRRORING] ("projecting
     * to the dash") BEFORE it even tries to join the bike's Wi-Fi, and MIRRORING reads as LIVE. So the gauge
     * can be green a millisecond after the tap with nothing connected at all — and if the phone's Wi-Fi is
     * off, the flow returns early and it STAYS that way. Anything that must be sure a connector works (Scan
     * proving one) has to ask this, not the colour.
     */
    val linkUp: Boolean get() = factory is ConnState.Connected || phase == Phase.STREAMING
}

/**
 * Observe the connection and reduce it to one [ConnectionStatus]. Verbatim the rules the dashboard gauge has
 * always used — the comments below are the reasons each of them exists, learned the hard way.
 */
@Composable
fun rememberConnectionStatus(): ConnectionStatus {
    val snap by ConnectionState.flow.collectAsStateWithLifecycle()
    // The connection factory (the cockpit's own connect, preferFactory=true) drives its OWN ConnState during the
    // PRE-projection phases — while the classic ConnectionState is still parked at JOINING_WIFI. Observe it
    // reactively (null when no factory connection is live) so the gauge shows real Conectando…/Reconectando…/Error
    // detail; once the prober flips ConnectionState to STREAMING/MIRRORING (or a classic terminal state) the phase
    // label is authoritative again and wins.
    val factoryConn by BikeConnectionHolder.connectionFlow.collectAsStateWithLifecycle()
    val factoryState by produceState<ConnState?>(initialValue = null, factoryConn) {
        value = null
        factoryConn?.state?.collect { value = it }
    }
    // Projection live (or a classic terminal state) → the ConnectionState phase is authoritative; otherwise
    // the factory's pre-projection ConnState wins (Idle/none falls through to the phase label).
    val projecting = snap.phase == Phase.STREAMING || snap.phase == Phase.MIRRORING ||
        snap.phase == Phase.ERROR || snap.phase == Phase.STOPPED
    // …with ONE exception: a factory Error/Retrying always wins, because the legacy phase cannot express
    // either of them. Without this, the terminal Error is mirrored into Phase.ERROR (so auto-connect
    // re-arms), `projecting` flips true, and the actionable reason ("…escanea el QR otra vez") collapses to
    // a bare "Error — see logs"; and a mid-ride drop would keep showing STREAMING instead of "Reconectando
    // 1/3" while the driver retries the SAME connector.
    val factoryOverrides = factoryState is ConnState.Error || factoryState is ConnState.Retrying
    val fs = factoryState?.takeIf { (factoryOverrides || !projecting) && it != ConnState.Idle }
    val kind = when (fs) {
        is ConnState.Error -> StatusKind.FAULT
        is ConnState.Connected -> StatusKind.LIVE
        is ConnState.Connecting, is ConnState.Retrying -> StatusKind.BUSY
        else -> snap.phase.kind()
    }
    // The generic "Error — see logs" label is only a fallback: whenever we have a real reason (the factory's
    // ConnState.Error, or ConnectionState.detail — which the classic path fills too, e.g. the prober's
    // "lost bike link", and which the factory mirror fills with the very same reason) THAT is what the rider
    // needs to read, in full, unabbreviated: it is the actionable part ("…escanea el QR otra vez para
    // actualizar el garaje"). The gauge colour already says "fault", so the label does not repeat it.
    val errorLabel = stringResource(R.string.conn_error)
    fun errorText(reason: String): String = reason.ifBlank { errorLabel }
    val text = when (fs) {
        is ConnState.Connecting -> stringResource(R.string.conn_joining_wifi)
        is ConnState.Connected -> stringResource(R.string.conn_pxc_connecting)
        // Bounded, rider-visible reconnect on the SAME connector: "Reconectando 1/3".
        is ConnState.Retrying -> stringResource(R.string.ovk_conn_reconnecting_n, fs.attempt, fs.maxAttempts)
        is ConnState.Error -> errorText(fs.reason)
        else -> if (snap.phase == Phase.ERROR) errorText(snap.detail) else stringResource(snap.phase.labelRes)
    }
    return ConnectionStatus(kind = kind, text = text, phase = snap.phase, factory = factoryState)
}
