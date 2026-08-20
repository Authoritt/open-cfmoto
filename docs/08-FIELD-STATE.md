# Field state — what is proven, what is waiting, what not to re-derive

Written 2026-08-20. This is the resume-here document: the other docs describe how the app works, this
one describes where the *work* is. Everything below is either backed by a log from a real bike or
marked as unproven on purpose.

## Where the work lives

| | |
|---|---|
| Fork | `Authoritt/open-cfmoto` |
| Branch | `feat/connection-factory` (built on `feat/cockpit-redesign`) |
| Worktree | `E:\Desarrollo\Activos\opencfmoto-cockpit` |
| Our PR | **#3 — DRAFT on purpose.** Do not merge until the Rieju owner's log and the 450NK tests conclude. |
| Upstream PR | #28, open and unreviewed. #3 depends on it; the note is in the PR body. |
| Design + plan | `docs/superpowers/{specs,plans}/2026-08-18-bike-connection-factory*` |
| Map stack | sibling repo `../overtake` (composite build) — see `docs/overtake-map-stack-runbook.md` |

**Build and deliver.** `./gradlew :app:assembleRelease` signs with the release key declared in
`local.properties` (gitignored; the keystore lives outside the repo). Gradle names the artifact
`app-release.apk` only when it is actually signed — an unsigned build comes out as
`app-release-unsigned.apk`, which is the cheapest signature check there is. The owner is sent a **.7z**,
not the raw APK: RelayCore caps attachments at 15 MB (`OpcionesEnvio.MaxTamanoAdjuntoMb`) and the APK is
~30 MB, so the raw file is refused before it is ever sent; the archive is ~9.5 MB.

## The three bikes

| Bike | Rider | Connector | Status |
|---|---|---|---|
| CFMoto 450NK | the project owner | SoftAP, and P2P (pinned by hand) | Daily driver. Map projects, handlebar works. |
| Rieju Aventure 500 | second rider | PHONE_HOTSPOT (Carbit `action=128` over BLE) | Bridge proven up to the credentials; the dash has never joined yet. |
| Zontes | third rider | Yunmo / TETHER | Untouched this round. One red test — see below. |

## Thread 1 — the Rieju BLE bridge

**Proven** by the 2026-08-19 log, end to end: BLE scan → GATT → MTU 256 → `0x30 CLIENT_INFO` →
`0x50 REQUEST_BUILD_NET` → **the dash answers `{"status":2}`** (USE_PHONE_AP) → Wi-Fi Direct group
created with Carbit's own credential shape (`Easyconn_AP-NNNN` + 12 chars, 5 GHz) → `0x52` delivered.

**The blocker, and its fix (not yet tested on a bike).** We waited for the dash to volunteer a
`0x51`/`0x53` and timed out after 20 s. It never volunteers: Carbit's own
`SdpBluetoothUtil.processRequestBuildNet` is a `while (true)` that re-sends the build request every
1-2 s and reads the `status` of each reply, finishing on `0` (SUCCEED). We now poll the same way
(`BleApInfoPush.startNetPoll`, 2 s cadence, 30 s budget).

*What the next log must show:* repeated `status=2 — not joined yet`, then
`status=0 (SUCCEED) — the dash JOINED the group`, then a client connecting from `192.168.49.X` where
**X is not 1**.

*If every poll stays at 2 until the timeout*, the dash accepts and asks for the network but cannot join
it. Two suspects, in this order: the **band** (we request 5 GHz because Carbit does — one constant,
`CarbitGroupConfig.BAND`), and the **SSID** (Android forces `DIRECT-xy-` in front of Carbit's raw
`Easyconn_AP-NNNN`; Carbit escapes that by reflecting on the built `WifiP2pConfig`, which lint refuses
at targetSdk 36 — a targetSdk conversation, not a `try/catch`).

## Thread 2 — the handlebar on the 450NK

**The model, settled with the owner.** ONE switch, per bike: *Controls → "El manillar maneja el audio"*.
OFF (default) the handlebar drives the dash; ON it is the phone's audio. `HandlebarAudioMode` owns the
value; `ButtonMode.isControlAa` survives as the old name for its inverse, so no call site had to change.
Three things used to decide this — two toggles plus an invisible auto-guess — and any of them could
cancel the rider's choice without saying so.

**His pod: ▲/▼, enter, back. No ◀/▶.** Every log so far shows only `KEYCODE_MEDIA_NEXT` and
`KEYCODE_MEDIA_PAUSE`; every "previous item" came from the rocker.

**The volume box on the dash is the dash's own UI.** Three independent checks:

1. the map goes out on a display with `flags=0xa (PRIVATE|PRESENTATION|OWN_CONTENT_ONLY)` — the system
   volume panel cannot be inside our video;
2. the PXC control link carries **no key events at all** — 201 control lines in that session, all
   `CLIENT_INFO` / `QUERY_SPEED` / `HU_TIME_SYNC` / `HU_QUERY_TIME`, and silence during every press;
3. the official app never repurposes the rocker — it steers with ◀/▶/select — so nobody upstream ever
   had this problem to solve.

On this bike ▲/▼ **are** the volume protocol: the dash writes an absolute volume, we read the direction
and put the level back. The dash pops its box because from its side the rider pressed volume.

**The last public lever, shipped as an experiment.** `AudioManager.setDeviceVolumeBehavior` — the
programmatic form of "disable absolute volume" — is **not in the public SDK** (verified against
`android.jar`, API 36.1: no such method, no `DEVICE_VOLUME_BEHAVIOR_*`); it is `@SystemApi` behind
`MODIFY_AUDIO_ROUTING`. `VolumeProvider` *is* public, so the session now declares remote volume
(`MediaButtonBridge.attachRemoteVolume`). Both paths stay armed: if the platform routes the dash's write
to `onSetVolumeTo`, the stream is never touched and the pin is dropped for good; if it does not, the
ContentObserver reads the stream exactly as before.

*What the next log decides:* whether `[BTN] *** remote volume: onSetVolumeTo(...)` ever appears. If it
never does, absolute volume is handled at device level and no app can intercept it — the final answer,
measured rather than argued.

**Still open, one cheap test.** Press each of the four buttons once, slowly, with the log running, and
record the order. It answers which physical button sends `MEDIA_NEXT` and whether it exits the
projection — the log shows the projection survived all five media keys, which contradicts the
expectation that "back" drops out. If two real keys turn out usable, the map can be steered without the
rocker at all and the box disappears for good.

## Thread 3 — "the map went green for longer"

The green itself is **known and documented** (`docs/cockpit-redesign.md` §5): MapLibre GL renders
uncapped and floods the dash's real-time decoder. Both caps are in place —
`MediaFormat.KEY_MAX_FPS_TO_ENCODER` (`VideoPipeline.kt:237`) and `MapView.setMaximumFps` through
`DashMapEngine` (`maxFps = 30` on the dash, `null` on the phone, which is correct).

What was never measured is **how long** the dash waits for its first keyframe, which is exactly how long
it paints green at connect. `VideoPipeline` now logs it: `[VIDEO] first keyframe queued Nms after the
dash attached`. No log carrying that line exists yet — the owner's logs so far either predate it or
never reached projection.

## Thread 4 — map controls on the phone (done)

The dash map always had follow / heading-up / route-overview; the phone map only ever had "centre on
me", which is why the driving view kept being reported as missing. `CockpitScreen` now shows the same
three, stacked above the locate FAB: `⤢` route overview (only with a route, always north-up), `➤`/`N`
driving view (lit while the map turns with the rider), `◎` centre. The chase camera reads the same
flag, so the choice survives each GPS fix.

## Facts paid for in the field — do not re-derive these

- **Changing `applicationId` wipes everything the app learned per bike.** `4e5fa19` gave the fork its
  own identity for Play Protect; Android saw a new package with an empty data dir, so the winning
  transport, the panel geometry, button mappings and "this bike has a ▲/▼ rocker" all reset. That is why
  a handlebar that worked on the 18th was dead on the 19th with no code change in between. The ▲/▼
  choice now travels in the settings export so it can at least be carried across.
- **An inference must never overrule evidence.** A 90-second probe used to conclude "this pod has no
  rocker" from silence — which is also what a normal ride looks like — write that verdict per bike, and
  outrank the rider's own setting. It was one-way: the only code that could revoke it sat behind the
  gate it closed. Deleted, not fenced.
- **The dash answers when asked; it does not volunteer.** True for the Rieju's build-net status, and
  worth assuming for anything else in that protocol.
- **As Wi-Fi Direct group owner, the "bike gateway" resolves to our own address.** Probing it means
  probing ourselves: 15 phantom `bike connected` lines and a false `Link dropped` in one log. Guarded in
  `EasyConnProber`.
- **A volume change cannot tell you who made it.** The bike's rocker and the rider's own volume keys are
  the same write. With no bike connected over Bluetooth, his phone's volume buttons were scrolling the
  map on the dash. Gated on a real link now (`MediaButtonBridge.bikeCanReachUs`).
- **An unescaped `'` in `strings.xml` fails only the RELEASE build**, with the misleading message
  *"Invalid unicode escape sequence"*. Unit tests never merge resources, so a commit can be green and
  still be unpackageable. Run `assembleRelease` before believing a build is deliverable.
- **`YunmoFrameTest.parseOkDimension_xCape1200Payload` is red, and was red before this work** (expects
  2048, gets 1024). The build flag says `1024x464`, so somebody was deliberately experimenting on the
  Zontes and left the test behind. Only the Zontes rider can settle which side is right — do not "fix"
  it blind.
- **Delivery.** Builds reach the owner's Outlook through RelayCore (`POST /api/v1/mensajes`, header
  `X-Api-Key`, attachment base64). The RelayCore→Outlook TLS bug is fixed and verified: 12.8 MB with
  attachment, `250` on the first attempt.

## Next actions, in order

1. Rieju rider installs a build with the poll (`2411b60` or later) and sends the log. That single log
   either closes the bridge or hands us the band/SSID question with data instead of theory.
2. Owner presses each handlebar button once with the log running — decides whether the rocker can be
   retired on this bike, which is the only way the volume box ever disappears.
3. Owner rides with music playing and sends the log — confirms the audio-focus fix and answers whether
   `onSetVolumeTo` ever fires.
4. Any log that actually reaches projection, to read the first-keyframe timing and close the green
   question.
5. Only then: take PR #3 out of draft.
