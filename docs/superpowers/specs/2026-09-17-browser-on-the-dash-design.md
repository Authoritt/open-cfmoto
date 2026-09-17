<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# Browser on the dash — design

**Status:** Design approved (brainstorming). Next: implementation plan (writing-plans).
**Date:** 2026-09-17
**Scope:** A new map provider whose dash content is a **web browser** rendered at the dash's own
resolution, driven either from the dash touchscreen or from a live, interactive preview on the phone.
Default home is Google Maps web; it is a general browser, not a Maps wrapper.

## 1. Goal & non-goals

**Goal.** One browser, rendered at the bike's canvas size, whose pixels reach **both** the dash (over the
existing PXC/H.264 path) and a preview on the phone; where a touch on either surface lands on the same
page, so the two can never show different things.

**Non-goals.** Not a replacement for the built-in map or for Android Auto — it is a fourth provider
beside them. Not an attempt to project the Google Maps *app* (that is walled: see
[`docs/09-DASH-CONTENT-WALL.md`](../../09-DASH-CONTENT-WALL.md)). Not a browser hardened for untrusted
browsing: it runs in the app's own WebView with the app's own privileges.

## 2. Why this is possible at all

Measured on the owner's 450NK phone (2026-09-16, `WebViewVdProbe`): a `WebView` on the dash
VirtualDisplay keeps running with the phone **screen off** — rAF 59.5 Hz, `setInterval` 10/s, encoder
30 fps, and `document.visibilityState` never leaves `visible`. The reason is structural: the page lives
on the VirtualDisplay, whose display state is independent of the physical panel, so Chromium never sees
a `visibilitychange` and never throttles. A WebView is also **our own View in our own process**, so the
`ADD_TRUSTED_DISPLAY` wall that blocks projecting other apps does not apply.

## 3. Architecture

```
          WebView (bike canvas size, e.g. 1024x464)
          in a Presentation on the VirtualDisplay
                          |
                    SurfaceTexture
                          |
                    AaCompositor (GL)
                     /            \
        encoder input surface      previewSurface
                |                        |
          H.264 -> PXC -> dash     SurfaceView on the phone
```

**One browser, two render targets.** There is no second WebView that could drift: there is one, and its
pixels fan out.

**`AaCompositor` is reused, not rewritten.** It is already "SurfaceTexture in → encoder out + optional
in-app phone preview" (it serves `HudViewActivity` today for the Android Auto path). The only change is
the producer: the VirtualDisplay instead of the AA video decoder.

**The map path is untouched.** Today `createOwnVirtualDisplay` renders *straight into* the encoder's
input surface. The compositor is inserted **only** when this provider is active; with `BUILTIN` or
Android Auto the pipeline stays byte-for-byte as it is. That is deliberate: the map was validated in the
field and must not inherit a GL stage it does not need.

**Touch has one funnel, two sources.** `GpxSession.setTouchTarget(view)` + `dispatchTouch(action, x, y)`
already turns dash coordinates into `MotionEvent`s on any `View`. Because the WebView is laid out at
exactly the dash canvas size, dash touches map **1:1 with no scaling**. The phone preview scales its
touches into that same canvas space and uses the same call. Single entry point = the two surfaces
cannot desynchronise.

## 4. The phone control screen

A route in the cockpit, opened when the provider is selected from the map option. Top to bottom: a
**URL/search field**, the **live preview**, and a row of **back · forward · reload · home**.

A consequence of the requirement worth stating: since there is one browser and it is dash-shaped, the
preview on the phone is a **wide band** (~2.2:1), not a phone-shaped page. That is the price of what you
touch being exactly what is transmitted.

The field is both address bar and search box: text that parses as a URL navigates, anything else is sent
to **Google search** (`https://www.google.com/search?q=`) — one engine, no preference screen, because a
setting nobody changes is a setting that costs a screen. Home returns to Google Maps. A
`ConnectionState`-backed indicator shows whether the dash is actually receiving, so the rider is never
driving a surface that is going nowhere.

The provider is `MapProvider.WEB`, added beside `BUILTIN`, `GOOGLE`, `WAZE`, `MIRROR`. **Adding a value
to a persisted enum is a data migration** — `mapProvider` is stored per install, so the reader must
already tolerate an unknown name (it does: `runCatching { MapProvider.valueOf(...) }` falls back to
`BUILTIN`), and nothing may reorder the existing constants.

## 5. Networking — the part that decides whether this works at all

While projecting, the app pins the **process** default route to the bike's Wi-Fi
(`BikeWifi.rebindProcessToBike`, called from `BikeLink` once PXC starts). That network has no internet,
and a `WebView` uses Chromium's own stack, which follows the process route. Mobile data being up is
irrelevant. The proof this is real is already in the tree: `AppHttp.ensureCellularUplink()` plus an
OkHttp client pinned to the cellular network exist solely so map tiles can load
(`AppHttp.kt:105` — *"Map/nav calls must therefore be pinned"*).

**Preferred: do not pin the process while this provider is active.** The pin exists to stop a VPN
stealing the default route, but the PXC sockets already bind themselves (`EasyConnProber.kt:171`,
`YunmoLink.kt:105`). If that holds, dropping the pin gives the browser the cellular route while PXC keeps
working through its own binding. Upstream is moving the same way (`d3b1936` defers the bind and adds
`unbindProcess`).

**Fallback: intercept every request.** `WebViewClient.shouldInterceptRequest` + an uplink-pinned OkHttp
client, reusing the MapLibre pattern. Honest limitation: `WebResourceRequest` **exposes no POST body**,
so forms and much XHR break — and Google Maps web leans on both. This fallback may therefore serve
general browsing while failing the default home page.

**Which one is right is a measurement, not an opinion:** connect, drop the pin, and see whether PXC
survives. That measurement is the first task of the implementation plan.

## 6. Errors

A dropped link leaves the browser running; the indicator says so and nothing needs restarting. A failed
page renders **our own HTML error**, not Chromium's — the built-in one is unreadable at 1024x464 from a
metre away. If the panel folder or the compositor cannot be set up, the pipeline falls back to the
existing native content rather than showing a black dash.

## 7. Testing

**Without the bike:** `HtmlPanelPreview` already runs the production presentation path headlessly, so the
browser, the compositor fan-out and the whole phone control screen can be exercised at a desk. Only the
dash actually painting the stream needs the motorcycle.

**Real unit tests** for the two pure pieces:
- URL-vs-search parsing.
- The phone-preview → dash-canvas coordinate mapping. Wrong here does not crash: it makes every touch
  land a few centimetres off, which is invisible by inspection and maddening in use.

## 8. Open questions

- Whether dropping the process pin keeps PXC alive (§5) — decided by measurement, first task.
- Whether Google Maps web is usable at all inside a WebView at this size, independent of networking.
  A split-screen test panel is already staged on the phone for this.
- Whether the dash should overlay anything (speed, nav card) on top of the browser. Out of scope for the
  first version.
