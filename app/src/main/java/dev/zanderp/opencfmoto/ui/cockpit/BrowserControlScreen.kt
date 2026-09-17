// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.cockpit

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.browser.DashBrowserHost
import dev.zanderp.opencfmoto.browser.PreviewTouchMap

/**
 * The browser, full screen, with no chrome of its own.
 *
 * No address bar and no buttons: the rider is *inside* the page, exactly as in Chrome. Navigation is
 * whatever the page offers, plus the system Back key.
 *
 * This is not a second browser — it is a live view of the ONE browser in [DashBrowserHost], on its own
 * dash-sized virtual display. Touches and keystrokes made here are forwarded into it, so the phone and
 * the dash cannot diverge, the dash keeps receiving while you browse, and none of it needs a bike.
 *
 * **The keyboard is the part that needs help.** The page lives on a virtual display, and the system IME
 * attaches to the focused window on the *physical* display — so tapping a field in the page would leave
 * a blinking cursor and no keyboard. A hidden [EditText] takes the IME instead, and what is typed is
 * inserted into the page's focused element.
 */
@Composable
fun BrowserControlScreen(nav: NavController, canvasW: Int, canvasH: Int) {
    val ctx = LocalContext.current

    DisposableEffect(Unit) {
        DashBrowserHost.ensureStarted(ctx)
        onDispose {
            DashBrowserHost.onEditableFocus = null
            DashBrowserHost.detachPreview()
        }
    }

    // System Back walks the page's history first, like a browser; it only leaves once there is none.
    BackHandler(enabled = true) {
        if (!DashBrowserHost.goBackIfPossible()) nav.popBackStack()
    }

    Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c -> browserSurface(c, canvasW, canvasH) },
        )
    }
}

/**
 * A [SurfaceView] showing the browser, with a 1x1 [EditText] on top that exists only to own the soft
 * keyboard. Built as a plain View tree rather than Compose because both halves need raw touch and IME
 * plumbing.
 */
internal fun browserSurface(ctx: Context, canvasW: Int, canvasH: Int): ViewGroup {
    val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

    val keyboardSink = EditText(ctx).apply {
        alpha = 0f
        isCursorVisible = false
        setBackgroundColor(Color.TRANSPARENT)
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSingleLine = true
        setOnEditorActionListener { _, _, _ -> DashBrowserHost.pressEnter(); true }
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL) {
                DashBrowserHost.pressBackspace(); true
            } else {
                false
            }
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // A soft keyboard emits no key events for characters, so the page is fed the text.
                if (count > before && s != null) {
                    val added = s.subSequence(start + before, start + count).toString()
                    if (added.isNotEmpty()) DashBrowserHost.typeText(added)
                } else if (before > count) {
                    repeat(before - count) { DashBrowserHost.pressBackspace() }
                }
            }
            override fun afterTextChanged(s: Editable?) {
                // Deliberately NOT cleared. Emptying the sink after every keystroke looked tidy — it made
                // each change a pure "added character" — but it destroys the IME's composing region
                // underneath: typing "cali" produced the suggestion "jcali" and nothing reached the page.
                // The delta in onTextChanged is enough; the sink just accumulates, invisible, at 1x1 px.
            }
        })
    }

    val surface = SurfaceView(ctx).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                DashBrowserHost.ensureStarted(ctx)
                DashBrowserHost.attachPreview(h.surface, width, height)
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                DashBrowserHost.updatePreviewSize(w, hh)
            }

            override fun surfaceDestroyed(h: SurfaceHolder) {
                // Stops the preview, never the browser: the dash keeps its output, the page its state.
                DashBrowserHost.detachPreview()
            }
        })
        setOnTouchListener { v, e ->
            val action = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0
                MotionEvent.ACTION_UP -> 1
                MotionEvent.ACTION_MOVE -> 2
                else -> return@setOnTouchListener false
            }
            PreviewTouchMap.toCanvas(e.x, e.y, v.width, v.height, canvasW, canvasH)
                ?.let { (cx, cy) -> DashBrowserHost.dispatchTouch(action, cx, cy) }
            true
        }
    }

    root.addView(
        surface,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    root.addView(keyboardSink, FrameLayout.LayoutParams(1, 1))

    // The page says when the rider focused something editable; only then does the keyboard come up, and
    // it goes away when the page blurs. Anything else leaves a keyboard sitting over the map.
    DashBrowserHost.onEditableFocus = { focused ->
        keyboardSink.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (focused) {
                keyboardSink.isFocusableInTouchMode = true
                keyboardSink.requestFocus()
                imm?.showSoftInput(keyboardSink, InputMethodManager.SHOW_IMPLICIT)
            } else {
                imm?.hideSoftInputFromWindow(keyboardSink.windowToken, 0)
                keyboardSink.clearFocus()
            }
        }
    }
    return root
}
