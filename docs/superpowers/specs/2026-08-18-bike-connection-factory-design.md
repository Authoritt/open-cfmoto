<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# Bike Connection Factory — design

**Status:** Design approved (brainstorming). Next: implementation plan (writing-plans).
**Date:** 2026-08-18
**Scope:** A clean, pluggable "connection factory" over the different ways a phone reaches a
motorcycle dash (CFMOTO SoftAP / Wi‑Fi Direct P2P, Carbit `action=128` phone-hosts-hotspot as on the
Rieju Aventure 500, and future modes), analogous to the **Overtake** map-supplier factory. Delivered as
an **in-app package** (`connection/`) built with Overtake's seam discipline so it can be extracted to a
module/library later.

## 1. Goal & non-goals

**Goal.** Replace the branching `if`-tree in `CfmotoConnect.joinWifi` with a factory that (a) selects the
right connector by connection *mode*, (b) owns the connection lifecycle (`StateFlow` state, retry,
teardown) as a single source of truth, (c) makes the hidden Yunmo fallback explicit, and (d) isolates
all new/risky work (the phone-hosts-hotspot + BLE credential push) inside **one** connector — so adding a
bike model is "add a connector/profile", not "edit the god-object".

**Non-goals.** Not extracting to a separate published library now (in-app package first, extraction-ready).
Not re-enabling the full multi-brand matrix speculatively — CFMOTO (works today) + the Rieju
phone-hotspot (the driving case) are the target bikes; the design stays open to more via profiles/connectors.
Not rewriting the proven transport/handshake internals — connectors **wrap** `BikeWifi`,
`BikeWifiP2p`, `EasyConnProber`, `YunmoLink`.

## 2. Configuration ownership (source of truth)

A cross-cutting cleanup that this work bakes in. Today the map **provider** lives in three editable
places (global Settings, per-bike Garage, the live Map selector) — "which wins?" is undefined. Rule:
**each setting has exactly one owner by its natural scope; others read/seed from it, never edit it.**

| Scope | What lives there | Rule |
|---|---|---|
| **Global (Settings)** | App-wide only: language, units, developer mode, telemetry, style tunables | One value. **No** per-bike selections, **no** "which map / which Wi-Fi". |
| **Per-bike (Garage)** | The `ConnectionSpec` (connection mode) **+ the per-bike default map provider/renderer** | Source of truth for "how I connect and my default experience for THIS bike". |
| **Dynamic (live screen)** | The choice I make *now*: the map I feel like this ride, day/night | Seeds from the Garage default; overridable for the session. A "set as default for this bike" writes back to the Garage. |

**Applied:**
- **Connection mode (SoftAP / P2P / phone-hotspot):** set-once, mostly auto-detected from the QR `action`
  bitmask; a manual override belongs at **Scan** (pairing time) and is stored per-bike in the **Garage**.
  → Remove any Wi-Fi-mode toggle from global Settings.
- **Map provider + renderer:** dynamic. Garage holds the per-bike default; the **Map screen** selector is
  the live override; a "set as default" writes back. → Remove the "which provider" selection from Settings.

## 3. Architecture — two layers + factory

```
QR (action bitmask) ─► BikeConnectionFactory.create(qr, memory, io) ─► BikeConnection
                                                                          │ StateFlow<ConnState>
  LAYER 1 · TRANSPORT (get an IP path to the bike)                        │
    SoftApTransport (wraps BikeWifi)            — CFMOTO SoftAP           │
    P2pTransport    (wraps BikeWifiP2p)         — Wi-Fi Direct            │  ── composed ─►
    PhoneHotspotTransport (NEW: P2P group-owner + BLE creds) — Rieju      │
  LAYER 2 · LINK (turn the path into a live video socket)                 │
    EasyConnLink (wraps EasyConnProber, PXC)  →  YunmoLink (explicit)     ▼
                                                          the APP owns the VideoPipeline/projection
```

- **Layer 1 (Transport)** establishes the L2/L3 path to the bike and yields a `BikeEndpoint`.
- **Layer 2 (Link)** runs the protocol handshake over that path and yields a `LinkSession` (the live
  socket the app's `VideoPipeline` consumes). Links are tried in order: `[EasyConnLink, YunmoLink]` — Yunmo
  is no longer a hidden fallback inside `EasyConnProber`.
- **The factory** composes a transport + the link list from the QR (or the Garage `ConnectionSpec`).
- **The seam** (Overtake discipline): the factory stays out of the video/projection. Platform pieces the
  connector needs are injected via a `PlatformIO` slot the app sets once (like `OvertakeLog`/`OvertakeHttp`):
  the `Activity` for interactive dialogs, logging, and the `VideoSink` that connects a `LinkSession` to the
  app's pipeline. The factory never references `Presentation`/`VirtualDisplay`/PXC framing.

## 4. Contracts

```kotlin
// Public: what the app holds
interface BikeConnection {
    val state: StateFlow<ConnState>
    fun connect()
    fun disconnect()
}
sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val phase: Phase, val detail: String? = null) : ConnState // Discovering·JoinTransport·Handshake·Starting
    data class Connected(val endpoint: BikeEndpoint) : ConnState
    data class Retrying(val reason: String, val nextInMs: Long) : ConnState
    data class Error(val reason: String, val recoverable: Boolean) : ConnState
}

// Layer 1
interface BikeTransport {
    suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint
    fun close()
}
// BikeEndpoint = { network: Network?, hostOrGateway: Inet4Address, bindIp: Inet4Address?, kind: TransportKind, phoneIsServer: Boolean }

// Layer 2
interface BikeLink {
    suspend fun establish(ctx: Context, ep: BikeEndpoint, spec: ConnectionSpec, io: PlatformIO): LinkSession
    fun stop()
}

// The factory
object BikeConnectionFactory {
    fun create(ctx: Context, qr: QrData, memory: BikeMemory, io: PlatformIO): BikeConnection {
        val spec = memory.specFor(qr) ?: ConnectionSpec.fromQr(qr)     // Garage fast-path, else detect
        return DefaultBikeConnection(selectTransport(spec), listOf(EasyConnLink(), YunmoLink()), spec, io)
    }
}

// Platform seam (app sets once; @Volatile-style)
interface PlatformIO {
    val log: (tag: String, msg: String) -> Unit
    fun activityOrNull(): Activity?      // interactive dialogs; null in background ⇒ mode may be foreground-only
    fun videoSink(): VideoSink           // app wires the LinkSession into its VideoPipeline here
}
```

**Decisions:** `suspend` + `StateFlow` (not callbacks) for clean composition/cancellation, wrapping the
proven internals; the ordered `links` list makes fallback explicit; the app is kept out of the video path
via `PlatformIO.videoSink()`.

## 5. Persistence — `ConnectionSpec` in the Garage

The Garage is the per-bike persistence layer. `BikeMemory.winningTransport` (which already remembers
AP-vs-P2P per SSID) generalizes into:

```kotlin
data class ConnectionSpec(
    val bikeId: String,              // stable key: bm= MAC (SSID fallback)
    val mode: TransportKind,         // SOFT_AP | P2P | PHONE_HOTSPOT
    val profile: BikeProfileId,      // brand quirks (timeouts, P2P MAC±1, BLE service override)
    val ssid: String?, val pwd: String?,   // SoftAP/P2P
    val bleMac: String?,             // phone-hotspot BLE pairing
    val bleServiceOverride: String?, // e.g. CFMOTO remaps B360→B354; null = default B360
    val lastEndpointHint: String?,   // last gateway/host
    val defaultMapProvider: MapProvider?   // §2 per-bike default
)
```

**Flow:** known bike (spec present) → factory pre-configures the connector → fast reconnect (skip
detection / P2P-vs-AP racing). New bike (QR) → detect → connect → on success **save/refresh the spec**.
Fast-path failure → fall back to full detection and update the spec. The spec's `mode` also gates
auto-connect: `PHONE_HOTSPOT` ⇒ foreground-only (see §6).

## 6. Lifecycle — state machine, retry, teardown, auto-connect

`DefaultBikeConnection` owns a single `StateFlow<ConnState>`; the legacy `ConnectionState` singleton
becomes a thin mirror (or is retired). The dashboard gauge and UI observe `connection.state`.

```
Idle ─connect()→ Connecting(Discovering) → Connecting(JoinTransport)  [transport.open]
     → Connecting(Handshake)  [link.establish: EasyConn→Yunmo]  → Connected(endpoint)
     → (drop) → Retrying(reason, nextInMs) → re-enter at the right layer
     ─disconnect()→ teardown → Idle
```

**Retry (reuses the shipped reconnect fix, now formalized):**
- **Link drops, transport still up** (mid-ride PXC/socket drop, Wi-Fi still associated): re-`establish()`
  Layer 2 only on the live network — do NOT re-open the transport. This is exactly the
  `recoverSocketLinkOnLiveNetwork` / `bikeWifiStillUp` fix, expressed as a state transition.
- **Transport drops:** re-`open()` with capped exponential backoff, then the link.
- `Retrying` distinguishes recoverable (auto) from fatal (`Error`, needs the user).

**Teardown (`disconnect`):** `link.stop()` → `transport.close()` → release. The **app** tears down the
video/projection when it observes `state → Idle` (via the seam) — the connection never touches the FGS/PXC.
This gives the previously-leaking `AndroidAutoService` FGS + turn-by-turn TTS a clean, single owner:
`disconnect → Idle → app stops projection`.

**Auto-connect (fits the existing `CompanionDeviceService`):** background calls the factory with
`io.activityOrNull() = null`. SoftAP/P2P connect headless; `PhoneHotspotTransport` sees `Activity == null`
and emits `Error("needs foreground")` so the service defers to opening the app. The `ConnectionSpec.mode`
records this, so the gate is remembered, not rediscovered.

## 7. Concrete connectors

| Connector | Wraps / new | `open()`/`establish()` |
|---|---|---|
| `SoftApTransport` | `BikeWifi.reuseOrJoin` | join the bike SoftAP → endpoint (network + gateway `192.168.x.1`) |
| `P2pTransport` | `BikeWifiP2p.connect` | form/join the P2P group (deviceAddress from `mac`, MAC±1 quirk) → endpoint (no Network, bindIp) |
| `PhoneHotspotTransport` | **NEW** | see §8 — P2P group-owner + BLE credential push; `phoneIsServer = true` |
| `EasyConnLink` | `EasyConnProber` | PXC handshake → `LinkSession`. Must support **server mode** when `endpoint.phoneIsServer` (phone-hotspot). |
| `YunmoLink` | existing | explicit `BikeLink`, tried after EasyConn |

Compositions: NK SoftAP = `SoftApTransport + [EasyConn, Yunmo]`; CFMOTO P2P = `P2pTransport + [EasyConn]`;
Rieju = `PhoneHotspotTransport + [EasyConn(server), Yunmo]`.

## 8. `PhoneHotspotTransport` — the Rieju connector (from the Carbit RE, see Appendix A)

`open()`:
1. **Create a Wi-Fi Direct group as owner** — `WifiP2pManager.createGroup(...)`. On API 29+, optionally set
   a custom name/passphrase (reflection-inject, as Carbit does). The phone becomes group owner + PXC server
   at `192.168.49.1`. Read `WifiP2pGroup.networkName/passphrase` and `WifiP2pInfo.groupOwnerAddress`.
2. **BLE-connect to `spec.bleMac`** (the QR `bm=`) reusing `BleWakeUp` scaffolding, but on service
   **`0000B360-…`** (or `spec.bleServiceOverride`); pick the WRITE/NOTIFY chars by GATT property among the
   `B362/B363/B364` set; enable notifications (CCCD `2902`).
3. **Push AP info over BLE** — EcBtp frame `0x24|cmd|len|payload|xor|0x0A` with **cmd `0x52`
   (NOTIFY_AP_INFO)** and Gson body `{"a":ssid,"b":pwd,"c":"WPA2","d":"","e":"192.168.49.1"}`. Preceded by
   `0x30 CLIENT_INFO` and `0x50 REQUEST_BUILD_NET`; dash replies `0x51`/`0x53`.
4. The dash joins the phone's group → optional tether/subnet scan (reuse `PhoneHotspotScan`) to confirm →
   endpoint (`phoneIsServer = true`).
5. **Fallback** (BLE unavailable / older dash): the improved manual flow (readable creds + 2.4 GHz guidance).
6. Requires an `Activity` → foreground-only; `null` in background ⇒ `Error("needs foreground")`.

## 9. Testing

- **Unit (no bike):** factory selection (`qr → connector`), `ConnState` transitions, and a **golden-frame
  test for the EcBtp `0x52` builder** against the RE-verified bytes in Appendix A (`24 52 54 7b … 59 0a`) —
  deterministic proof our frame equals Carbit's, no dash needed.
- **On-device (NK debug phone):** SoftAP/P2P regression (connects same as before), mid-ride reconnect,
  auto-connect.
- **Rieju (owner-in-the-loop):** ship the APK to the bike's owner; a one-tap **BT-HCI snoop** confirms the
  two open items (GATT write type; whether the dash keeps `B360` or remaps the short ID).

## 10. Build order (each increment compiles; old path stays live until switched)

1. **Contracts + factory skeleton** (`BikeConnection/Transport/Link`, `ConnState`, `PlatformIO`,
   `ConnectionSpec`) — no behavior change.
2. **Wrap existing modes** as connectors (SoftAp/P2p/EasyConn/**Yunmo explicit**) + route `joinWifi`
   through the factory for CFMOTO. Verify NK regression.
3. **Consolidate the state machine** (`DefaultBikeConnection` owns `StateFlow` + retry; `ConnectionState`
   → mirror). Verify reconnect.
4. **Garage `ConnectionSpec` persistence** + the §2 ownership cleanup (thin Settings; Scan sets connection;
   Garage seeds map default). Verify NK.
5. **`PhoneHotspotTransport`** (P2P-GO + BLE `B360` + `cmd 0x52`) → ship APK to the Rieju owner → confirm
   the two open items via BT-HCI snoop.

Risk is isolated: the new/uncertain work is one connector; SoftAP/P2P behavior is unchanged (wrapped); the
factory can fall back to the old path during migration.

## 11. Open questions / risks

- **GATT write type** (with/without response) and MTU split for the `0x52` frame — confirmed by the snoop.
- **BLE service ID on the Rieju dash** — likely `B360` (Carbit-generic), but CFMOTO remapped to `B354`;
  `spec.bleServiceOverride` + property-based char resolution absorbs either; snoop confirms.
- **EasyConn server mode** on the phone-hosts path — `EasyConnProber` must accept the phone-as-server role;
  scope the wrapper accordingly.
- **Extraction later:** if reuse materializes, the package lifts to a `:bike-connect` module or
  `dev.overtake:overtake-connect` library — the seam discipline (contracts + `PlatformIO`) keeps that cheap.

---

## Appendix A — Carbit `action=128` protocol (reverse-engineered, bytecode-proven)

Source: static jadx decompile of the official `net.easyconn.carman.wws` (CarbitLink-EasyConnection) v6.4.0.
Proven from bytecode unless marked INFERRED. This mode is otherwise undocumented in the public RE community.

**Hotspot creation** (`WifiHotspotUtils.initCreateApType`): **Wi-Fi Direct P2P group** (phone = group owner),
NOT `startLocalOnlyHotspot` (that path exists but is dead code). API 29+ 5 GHz → custom config via
`WifiP2pConfig.Builder().setNetworkName(...).setPassphrase(...)` with private fields overwritten by
reflection (`setGroupOperatingBand(2)` = 5 GHz); API 26–28 → plain `createGroup` (system-generated
`DIRECT-…`); < 26 → legacy `setWifiApEnabled`. Generated creds (`WifiApUtils`): SSID = `"Easyconn_AP-" +
4 digits`, passphrase = 12 × `[a-z0-9]`, persisted in SharedPreferences `SP_BLE_NET_BUILD_AP_INFO`. Phone IP =
`WifiP2pInfo.groupOwnerAddress` (typically `192.168.49.1`).

**BLE transport** (`s5/a.java`, `s5/d.java`): service **`0000B360-d6d8-c7ec-bdf0-eab1bfc6bcbc`** (B36x family;
distinct from CFMOTO's B35x wake-up service). Connect by the QR `bm=` MAC. Char resolution tries V3
(single `B364` WRITE+NOTIFY) → V2 (write `B363`, notify `B364`) → V1 (by property among the `B362` set);
CCCD `00002902`.

**Credential push** (`ble_net.b.F` → `BleRespBridge.sendHotspot2Car` → `u7.p.Q` → `u7.a.b`): EcBtp frame
```
0x24 | cmd(1) | len(1) | payload… | xor(1) | 0x0A
len = payload.length + 4 ; xor = 0x24 ^ cmd ^ len ^ Σ(payload)
```
**cmd = `0x52` (COMMAND_EC_BTP_NOTIFY_AP_INFO)**. Payload = Gson JSON of `w7.l` (fields literally `a,b,c,d,e`,
no `@SerializedName`): `{"a":ssid,"b":pwd,"c":"WPA2","d":"","e":"192.168.49.1"}` (`d` = phone MAC, sent empty;
the 4-arg overload omits `e` for dashes predating `SP_BLE_SUPPORT_IP`). Related cmds: `0x30 CLIENT_INFO`,
`0x50 REQUEST_BUILD_NET`, `0x51 NOTIFY_BUILD_NET_FINISH`, `0x53 NOTIFY_CAR_NET_INFO`, `0x01 SYNC_TIME`.

**Verified golden frame** (cmd `0x52`, ssid `Easyconn_AP-1234`, pwd `a1b2c3d4e5f6`, auth `WPA2`, mac ``,
ip `192.168.49.1`):
```
24 52 54 7b 22 61 22 3a 22 45 61 73 79 63 6f 6e 6e 5f 41 50 2d 31 32 33 34 22 2c
22 62 22 3a 22 61 31 62 32 63 33 64 34 65 35 66 36 22 2c 22 63 22 3a 22 57 50 41
32 22 2c 22 64 22 3a 22 22 2c 22 65 22 3a 22 31 39 32 2e 31 36 38 2e 34 39 2e 31
22 7d 59 0a
```

**Sequence:** QR `action&128` + `bm=` → BLE connect (B360) → notify on → `0x30` CLIENT_INFO → `0x50`
REQUEST_BUILD_NET → phone `createGroup` (P2P) → read ssid/pwd + groupOwnerAddress → `0x52` AP_INFO → dash
joins the group → `0x51`/`0x53` → PXC over P2P (**phone is group owner AND PXC server** at `192.168.49.1`).

**INFERRED (one-tap snoop confirms):** exact GATT write type + MTU split; whether a non-Carbit-branded dash
keeps `B360` or remaps the short ID.

**Repo mapping:** `QrData.kt` already parses `action&128` + `bm=` ✓. `EcBtpProtocol.kt` already has the frame
+ checksum → add `CMD_NOTIFY_AP_INFO=0x52` (+ `0x50/0x51/0x53`) and the JSON body. `BleWakeUp.kt`/
`BleProtocol.kt` scaffolding is reusable with service `B360` + property-based char selection.
`PhoneHotspotAssist.kt`'s "ask the rider to type creds" assumption is superseded by the programmatic path.
