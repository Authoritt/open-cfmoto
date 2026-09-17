# 09 — What can, and cannot, be put on the bike dash

> Why Android Auto can throw Google Maps at the TFT with the phone in your pocket and we cannot, what
> the exact blocking step is, and which routes are actually open. Written down because this keeps being
> re-investigated from scratch — it had lived only in one session's head until 2026-09-16.

## First, correct the mental model: the dash is a dumb video sink

The TFT does not render HTML, or a map, or anything. It **decodes an H.264 stream** and paints it, and
it sends touch coordinates back. All the intelligence is on the phone. Everything below follows from
that: the question is never "can the dash do X", it is "can the phone RENDER X into a Surface we own".

```
phone: content → VirtualDisplay → MediaCodec (H.264) → PXC → dash: decode + paint
dash:  touch → PXC → phone: inject into the VirtualDisplay → the content receives it
```

Both directions are proven in the field with the built-in map.

## The blocking step, precisely

A flow people keep drawing, and the one step that fails:

| Step | |
|---|---|
| App opens, connects to the TFT | ✅ |
| A `VirtualDisplay` is created | ✅ |
| **Google Maps is launched onto that display** | ❌ **this one** |
| MediaCodec encodes the display's Surface | ✅ measured |
| The app streams the video | ✅ field-proven |
| The dash decodes and paints it | ✅ field-proven |
| Touch comes back and is injected | ✅ field-proven |

Launching **another app's Activity** on a display we created requires **`ADD_TRUSTED_DISPLAY`**. It is
`signature|role`, "managed by role", and the role holder is Google's own Android Auto. That single
permission is the entire difference between what AA does and what we can do.

### What does NOT get around it (all checked, do not re-try)

- `pm grant` — refused: it is not a runtime permission.
- **Shizuku** — runs as uid `shell`, which does not hold it either.
- `SYSTEM_ALERT_WINDOW` — lets us *draw over* things; it does not let us host or capture another app.
- `overlay_display_devices` — produces a trusted display, but it is drawn **visibly on the phone
  screen**, so the phone is no longer free. That defeats the purpose.
- **Single-app capture (Android 14+)** — the capture **pauses** the moment the user leaves Maps, so it
  cannot run with the phone in a pocket.
- **adb shell** — Google removed the shell's ability in **Android 15 QPR2** (this is what broke
  `scrcpy --new-display` on Pixel; Google issue **384752747**) and returned it in **Android 16 for
  testing only**. Note what that means: it came back to the **shell**, not to apps. It would let us
  demo the idea with a cable attached, which is worth nothing on a moving motorcycle.

**With root** it is believed to open up (root > shell → create the trusted display and capture it).
That is the product's "Overtake" mode: Google Maps literally on the dash, gated behind root detection.
**The engine for it is not built.** The owner's 450NK phone is **not rooted** (checked 2026-09-16,
Android 16 / SDK 36).

## What IS open: anything we render ourselves

The wall is about hosting *someone else's* app. Content of our own in our own process goes straight
through, with no special permission:

- Jetpack Compose / native views — the cockpit.
- **MapLibre** (vector + 3D), **osmdroid** (raster), **Mapsforge** (offline vector) — the three free
  dash renderers.
- **A `WebView`** — see `DashHtmlPanel`. A WebView is our own View, not a hosted app, so it is not
  blocked. This is the cheapest way to put arbitrary, changeable content on the dash.

### The screen-off question, measured twice

The obvious worry is that everything freezes when the phone screen goes off. It does not, and the
reason is structural rather than lucky: **the content lives on the VirtualDisplay, whose display state
is independent of the physical panel.**

| Renderer | Screen on | Screen off | Probe |
|---|---|---|---|
| MapLibre | ~60 fps | **~115 fps** (faster off), clean H.264 | `MapLibreVdProbe`, commit `0c5b7c8` |
| WebView | rAF 59.3 Hz · timers 9.86/s · encoder 30.1 fps | **rAF 59.5 Hz · timers 10.0/s · encoder 30.0-30.2 fps** | `WebViewVdProbe`, 2026-09-16 |

For the WebView the decisive detail is that `document.visibilityState` **never leaves `visible`**:
Chromium ties visibility to its window's display, so turning the phone screen off never fires a
`visibilitychange`, so timers and `requestAnimationFrame` are never throttled. No `resumeTimers()` or
any other mitigation was needed — the probe ran deliberately without them.

Both probes are on the real 450NK phone (Redmi `zircon`), both hold a partial wakelock so the CPU
cannot be the variable, and both feed the *real* VirtualDisplay→encoder pipeline rather than a parallel
one.

> **Reading a frame-rate log**: rate ≈ encoder fps means the renderer is producing new frames; rate
> ≈ 1 fps means the renderer **stopped** and only the encoder's static-frame repeat is emitting; 0
> means the whole pipeline stalled. "Frames are still flowing" alone never proves the content is alive
> — a frozen page with a live encoder looks almost identical. Measure the CONTENT, not just the rate.

### A consequence worth remembering

Because dash touches are injected into the VirtualDisplay, they reach whatever we render there — the
map today, and equally a WebView. **Own content on the dash can be interactive**, not just a display.

## The routes to Google Maps specifically, ranked by what they cost

1. **Android Auto mode** — the only sanctioned way to get Google's own map on the dash with the phone
   free. Already in the app. Since **AA 17.4+** Google blocked the *automatic* start: after Connect the
   rider must open AA settings → tap **Version** ~10× (first time only) → **⋮** → *Start head unit
   server*, and repeat the ⋮ step after every phone reboot or AA update.
2. **Google Navigation SDK** — Google's maps and turn-by-turn drawn by *us*, so they project freely.
   Commercially licensed; not free.
3. **Mirror** — works, but the phone is occupied and it is visual-only.
4. **Our own map** — free, no AA, no root. Google's guidance can still ride along as a notification
   card.
5. **Root + "Overtake" mode** — literal Google Maps. Engine unbuilt, requires root.

Maps' **mobile web** is not on this list on purpose: it has no turn-by-turn and Google's terms forbid
embedding it. An HTML panel does not resurrect this route.

## What this means when someone proposes the idea again

Say which of the steps above they are proposing. If it is "launch another app onto our display", the
answer is the permission, and it has not changed since Android 15. If it is "render something
ourselves", it is open, it survives screen-off, and it can take touch.
