<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# Bike Connection Factory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `CfmotoConnect.joinWifi`'s branching with a pluggable two-layer connection factory
(Transport + Link) that owns a single `StateFlow` lifecycle, persists a per-bike `ConnectionSpec`, and
isolates the new Rieju phone-hotspot connector — wrapping the proven Wi-Fi/BLE internals, not rewriting them.

**Architecture:** In-app package `connection/factory/`. Layer 1 `BikeTransport` (SoftAp/P2p/PhoneHotspot)
yields a `BikeEndpoint`; Layer 2 `BikeLink` (EasyConn→Yunmo, ordered) yields a `LinkSession`. A factory
composes them from the QR or the Garage spec; `DefaultBikeConnection` owns state/retry/teardown; the app
supplies platform pieces (Activity, logging, the video sink) via a `PlatformIO` seam, keeping the factory
out of the video/projection code (Overtake discipline).

**Tech Stack:** Kotlin 2.2, Jetpack (coroutines/`StateFlow`), AndroidX Wi-Fi (`WifiNetworkSpecifier`,
`WifiP2pManager`), BLE GATT, Gson. Build via composite `includeBuild("../overtake")`. Design spec:
`docs/superpowers/specs/2026-08-18-bike-connection-factory-design.md`.

## Global Constraints

- **Branch:** `feat/connection-factory` (separate from the cockpit PR). Wrap-not-rewrite.
- **Additive / no regression:** the classic Android-Auto path and existing SoftAP/P2P behavior must be
  byte-for-byte unchanged until explicitly switched; the old `joinWifi` path stays live behind the factory
  until a phase verifies the replacement on the NK debug phone.
- **No secrets / no VM:** never introduce OTA / RemoteLog / `publish-ota` / any VM URL (grep-verify each commit).
- **No `git add -A`** — the worktree has untracked scratch PNGs; stage explicit paths only.
- **JDK 17, minSdk 29, compileSdk 36.** Build: `./gradlew.bat assembleDebug --no-daemon --console=plain`
  (JAVA_HOME=`C:\Program Files\Java\jdk-17.0.5`, ANDROID_HOME=`E:\Android\Sdk`). Unit tests currently fail to
  compile (pre-existing stale router tests moved to Overtake) — **Task 0 fixes that first** so `testDebugUnitTest` runs.
- **Package:** all new code under `dev.zanderp.opencfmoto.connection.factory` (+ `.transport`, `.link`, `.ble`).
- **EcBtp `0x52` payload keys are literally `a,b,c,d,e`** (no `@SerializedName`); `c="WPA2"`, `d=""` (empty).

---

## File Structure

**Create (factory package):**
- `connection/factory/Contracts.kt` — `BikeConnection`, `ConnState`, `Phase`, `BikeEndpoint`, `LinkSession`, `TransportKind`, `BikeTransport`, `BikeLink`, `PlatformIO`, `VideoSink` (small, all the interfaces + value types together — they change together).
- `connection/factory/ConnectionSpec.kt` — `ConnectionSpec` data class, `TransportKind`, `fromQr()`, JSON (de)serialize.
- `connection/factory/BikeConnectionFactory.kt` — `create()`, `selectTransport()`.
- `connection/factory/DefaultBikeConnection.kt` — the state machine (owns `MutableStateFlow`, retry, teardown).
- `connection/factory/transport/{SoftApTransport,P2pTransport,PhoneHotspotTransport}.kt`
- `connection/factory/link/{EasyConnBikeLink,YunmoBikeLink}.kt`
- `connection/factory/ble/EcBtpApInfo.kt` — the `0x52` AP-info frame builder (pure).

**Modify:**
- `EcBtpProtocol.kt` — add `CMD_NOTIFY_AP_INFO=0x52` (+ `0x30/0x50/0x51/0x53`) constants.
- `CfmotoConnect.kt` — route `joinWifi` through the factory (behind a flag, then default on).
- `BikeMemory.kt` — `specFor(qr)`, `saveSpec(spec)`.
- `ui/settings/SettingsScreen.kt` — remove the map-provider selector (config-ownership §2).
- `GarageActivity.kt` / garage screen — per-bike default map provider + connection mode display.
- `QrScanActivity.kt` — set connection mode at pairing.
- App init (`OpenCfMotoApp` / `CockpitActivity`) — install the `PlatformIO` slot.

**Test:**
- `app/src/test/java/dev/zanderp/opencfmoto/connection/factory/{EcBtpApInfoTest,ConnectionSpecTest,BikeConnectionFactoryTest,ConnStateReducerTest}.kt`

---

## Task 0: Make unit tests compile (unblock TDD)

**Files:**
- Delete: `app/src/test/java/dev/zanderp/opencfmoto/FunRoutePlannerTest.kt`, `ValhallaRouterTest.kt`
- Modify: any of `GpxNavTest.kt`/`GpxParserTest.kt`/`TripGpxTest.kt` that import moved router/coordinate types

- [ ] **Step 1: See the failure**

Run: `./gradlew.bat :app:compileDebugUnitTestKotlin --no-daemon --console=plain`
Expected: FAIL — unresolved references to `FunRoutePlanner`/`ValhallaRouter`/etc. (moved to Overtake).

- [ ] **Step 2: Delete the stale router tests** (their classes now live in the `overtake` project)

```bash
git rm app/src/test/java/dev/zanderp/opencfmoto/FunRoutePlannerTest.kt \
       app/src/test/java/dev/zanderp/opencfmoto/ValhallaRouterTest.kt
```

- [ ] **Step 3: Fix remaining stale imports.** For each still-failing test (`GpxNavTest`/`GpxParserTest`/`TripGpxTest`), open it; if it references a type moved to `dev.overtake.maps.*`, either update the import to the Overtake type (if the composite exposes it) or delete the test if it only tested moved code. Re-run Step 1 until it compiles.

- [ ] **Step 4: Verify tests compile and run**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
Expected: compiles; existing tests run (pass/fail is fine — we only needed compilation unblocked).

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/dev/zanderp/opencfmoto/
git commit -m "test: drop stale router unit tests (classes moved to Overtake); unblock testDebugUnitTest"
```

---

## Task 1: EcBtp `0x52` AP-info frame builder (pure, golden-frame TDD)

The crown jewel from the RE — pure, unit-testable against the verified bytes. Do this first: it proves the
Carbit protocol and de-risks the Rieju connector before any Android plumbing.

**Files:**
- Create: `connection/factory/ble/EcBtpApInfo.kt`
- Modify: `EcBtpProtocol.kt` (add command constants)
- Test: `.../connection/factory/EcBtpApInfoTest.kt`

**Interfaces:**
- Produces: `object EcBtpApInfo { fun frame(ssid: String, pwd: String, ip: String, auth: String = "WPA2", phoneMac: String = ""): ByteArray }` — returns the full `0x24…0x0A` EcBtp frame for cmd `0x52`.

- [ ] **Step 1: Write the failing golden-frame test** (bytes from spec Appendix A)

```kotlin
package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.connection.factory.ble.EcBtpApInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EcBtpApInfoTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    @Test fun `0x52 frame matches the Carbit golden bytes`() {
        val frame = EcBtpApInfo.frame(
            ssid = "Easyconn_AP-1234", pwd = "a1b2c3d4e5f6", ip = "192.168.49.1"
        )
        val golden =
            "24 52 54 7b 22 61 22 3a 22 45 61 73 79 63 6f 6e 6e 5f 41 50 2d 31 32 33 34 22 2c " +
            "22 62 22 3a 22 61 31 62 32 63 33 64 34 65 35 66 36 22 2c 22 63 22 3a 22 57 50 41 " +
            "32 22 2c 22 64 22 3a 22 22 2c 22 65 22 3a 22 31 39 32 2e 31 36 38 2e 34 39 2e 31 " +
            "22 7d 59 0a"
        assertEquals(golden.replace("\n", " ").trim(), hex(frame))
    }

    @Test fun `checksum is xor of start, cmd, len and payload`() {
        val f = EcBtpApInfo.frame("s", "p", "1.2.3.4")
        val payloadLen = f.size - 4                    // minus 0x24, cmd, len, xor, 0x0A → +1 back = -4
        var xor = 0
        for (i in 0 until f.size - 2) xor = xor xor (f[i].toInt() and 0xff)  // 0x24..last payload byte + len
        assertEquals((f[f.size - 2].toInt() and 0xff), xor)                  // second-to-last byte is the xor
        assertEquals(0x0a, f.last().toInt() and 0xff)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*EcBtpApInfoTest*" --no-daemon --console=plain`
Expected: FAIL — `EcBtpApInfo` unresolved.

- [ ] **Step 3: Add the command constants to `EcBtpProtocol.kt`**

Find the existing command constants block; add:

```kotlin
const val CMD_EC_BTP_CLIENT_INFO = 0x30
const val CMD_REQUEST_BUILD_NET = 0x50
const val CMD_NOTIFY_BUILD_NET_FINISH = 0x51
const val CMD_NOTIFY_AP_INFO = 0x52
const val CMD_NOTIFY_CAR_NET_INFO = 0x53
```

- [ ] **Step 4: Implement `EcBtpApInfo`** (frame = `0x24 | cmd | len | payload | xor | 0x0A`, `len = payload+4`, `xor = 0x24 ^ cmd ^ len ^ Σpayload`; payload = Gson JSON with literal keys `a,b,c,d,e`)

```kotlin
package dev.zanderp.opencfmoto.connection.factory.ble

import com.google.gson.Gson
import dev.zanderp.opencfmoto.EcBtpProtocol
import java.io.ByteArrayOutputStream

/** Builds the Carbit EcBtp NOTIFY_AP_INFO (0x52) frame that hands Wi-Fi creds to a phone-hosts-hotspot
 *  dash over BLE. Wire keys are the literal letters a,b,c,d,e (verified against net.easyconn.carman.wws). */
object EcBtpApInfo {
    private val gson = Gson()
    private class Body(val a: String, val b: String, val c: String, val d: String, val e: String)

    fun frame(ssid: String, pwd: String, ip: String, auth: String = "WPA2", phoneMac: String = ""): ByteArray {
        val payload = gson.toJson(Body(ssid, pwd, auth, phoneMac, ip)).toByteArray(Charsets.UTF_8)
        val cmd = EcBtpProtocol.CMD_NOTIFY_AP_INFO
        val len = payload.size + 4
        var xor = 0x24 xor cmd xor len
        for (byte in payload) xor = xor xor (byte.toInt() and 0xff)
        return ByteArrayOutputStream().apply {
            write(0x24); write(cmd); write(len); write(payload); write(xor and 0xff); write(0x0a)
        }.toByteArray()
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*EcBtpApInfoTest*" --no-daemon --console=plain`
Expected: PASS (both tests). If the golden test fails, Gson may be ordering keys or adding spaces — confirm
Gson emits `{"a":"…","b":…}` with no spaces (it does by default) and field order a→e (declaration order).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/connection/factory/ble/EcBtpApInfo.kt \
        app/src/main/java/dev/zanderp/opencfmoto/EcBtpProtocol.kt \
        app/src/test/java/dev/zanderp/opencfmoto/connection/factory/EcBtpApInfoTest.kt
git commit -m "feat(ble): EcBtp 0x52 AP-info frame builder (golden-frame test vs Carbit bytes)"
```

---

## Task 2: Contracts + value types

**Files:**
- Create: `connection/factory/Contracts.kt`

**Interfaces:**
- Produces: `BikeConnection` (`val state: StateFlow<ConnState>`, `fun connect()`, `fun disconnect()`);
  `ConnState` (`Idle`/`Connecting(phase,detail)`/`Connected(endpoint)`/`Retrying(reason,nextInMs)`/`Error(reason,recoverable)`);
  `enum Phase { Discovering, JoinTransport, Handshake, Starting }`;
  `data class BikeEndpoint(val network: Network?, val host: java.net.Inet4Address, val bindIp: java.net.Inet4Address?, val kind: TransportKind, val phoneIsServer: Boolean)`;
  `interface LinkSession { fun close() }`;
  `interface BikeTransport { suspend fun open(ctx, spec, io): BikeEndpoint; fun close() }`;
  `interface BikeLink { suspend fun establish(ctx, ep, spec, io): LinkSession; fun stop() }`;
  `interface PlatformIO { val log: (String,String)->Unit; fun activityOrNull(): Activity?; fun videoSink(): VideoSink }`;
  `interface VideoSink { fun attach(session: LinkSession) }`.

- [ ] **Step 1: Write `Contracts.kt`** with all the interfaces/types above (no logic). `TransportKind` is
  defined in Task 3's `ConnectionSpec.kt` — import it here (forward reference is fine within the same module;
  if the compiler complains about order, move `enum class TransportKind { SOFT_AP, P2P, PHONE_HOTSPOT }` into `Contracts.kt`).

- [ ] **Step 2: Compile**

Run: `./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain`
Expected: PASS (interfaces only).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/connection/factory/Contracts.kt
git commit -m "feat(connection): factory contracts (BikeConnection/Transport/Link, ConnState, PlatformIO)"
```

---

## Task 3: `ConnectionSpec` + `fromQr` (TDD)

**Files:**
- Create: `connection/factory/ConnectionSpec.kt`
- Test: `.../connection/factory/ConnectionSpecTest.kt`

**Interfaces:**
- Produces: `enum class TransportKind { SOFT_AP, P2P, PHONE_HOTSPOT }`;
  `data class ConnectionSpec(bikeId, mode, profile, ssid, pwd, bleMac, bleServiceOverride, lastEndpointHint, defaultMapProvider)`;
  `fun ConnectionSpec.Companion.fromQr(qr: QrData): ConnectionSpec`; `toJson()/fromJson()`.

- [ ] **Step 1: Write the failing test** (mode selection from the QR `action` bitmask, per spec §7/§8)

```kotlin
package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.QrData
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionSpecTest {
    private fun qr(action: Int, ssid: String = "", mac: String? = "DD:0D:30:16:6B:50") =
        QrData(ssid = ssid, pwd = "", action = action, mac = mac /* fill remaining required fields per QrData */)

    @Test fun `action 128 with bm maps to PHONE_HOTSPOT and keeps bleMac`() {
        val s = ConnectionSpec.fromQr(qr(action = 128))
        assertEquals(TransportKind.PHONE_HOTSPOT, s.mode)
        assertEquals("DD:0D:30:16:6B:50", s.bleMac)
    }
    @Test fun `action bit3 maps to P2P`() =
        assertEquals(TransportKind.P2P, ConnectionSpec.fromQr(qr(action = 8, ssid = "DIRECT-ab")).mode)
    @Test fun `action bit0 maps to SOFT_AP`() =
        assertEquals(TransportKind.SOFT_AP, ConnectionSpec.fromQr(qr(action = 1, ssid = "CFMOTO")).mode)
    @Test fun `json round-trips`() {
        val s = ConnectionSpec.fromQr(qr(action = 128))
        assertEquals(s, ConnectionSpec.fromJson(s.toJson()))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ConnectionSpecTest*" --no-daemon --console=plain`
Expected: FAIL — `ConnectionSpec` unresolved. (First, open `QrData.kt` and copy its real constructor
signature into the `qr(...)` helper so the test compiles — do not guess fields.)

- [ ] **Step 3: Implement `ConnectionSpec.kt`.** Mode from the bitmask, mirroring `QrData`'s existing
  `supportsPhoneHotspot`/`supportsP2p`/`supportsAp` (reuse them): PHONE_HOTSPOT if `qr.supportsPhoneHotspot`,
  else P2P if `qr.supportsP2p && ssid startsWith "DIRECT"`, else SOFT_AP. `bikeId = qr.mac ?: qr.ssid`.
  Gson for `toJson/fromJson`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ConnectionSpecTest*" --no-daemon --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/connection/factory/ConnectionSpec.kt \
        app/src/test/java/dev/zanderp/opencfmoto/connection/factory/ConnectionSpecTest.kt
git commit -m "feat(connection): ConnectionSpec + fromQr(action bitmask) with JSON round-trip"
```

---

## Task 4: `BikeConnectionFactory.selectTransport` (TDD)

**Files:**
- Create: `connection/factory/BikeConnectionFactory.kt`
- Test: `.../connection/factory/BikeConnectionFactoryTest.kt`

**Interfaces:**
- Consumes: `ConnectionSpec`, `TransportKind`, the three transports (Task 6/7 — reference by type; for this
  task use lightweight class stubs that `selectTransport` returns by `TransportKind`, filled in Task 6/7).
- Produces: `object BikeConnectionFactory { fun selectTransport(spec): BikeTransport; fun create(ctx, qr, memory, io): BikeConnection }`.

- [ ] **Step 1: Write the failing test** — `selectTransport` returns the right transport class per mode

```kotlin
class BikeConnectionFactoryTest {
    @Test fun `PHONE_HOTSPOT selects PhoneHotspotTransport`() {
        val t = BikeConnectionFactory.selectTransport(spec(TransportKind.PHONE_HOTSPOT))
        assertTrue(t is dev.zanderp.opencfmoto.connection.factory.transport.PhoneHotspotTransport)
    }
    // + SOFT_AP → SoftApTransport, P2P → P2pTransport
}
```

- [ ] **Step 2: Run — fails** (`BikeConnectionFactory` unresolved).
- [ ] **Step 3: Implement `selectTransport`** as a `when (spec.mode)` returning the transport; `create()`
  builds `DefaultBikeConnection(selectTransport(spec), listOf(EasyConnBikeLink(), YunmoBikeLink()), spec, io)`
  with `val spec = memory.specFor(qr) ?: ConnectionSpec.fromQr(qr)`. (Transports/links referenced here are
  created in Tasks 6–8; to keep this task green, create empty class shells for them now that satisfy the
  interfaces with `TODO()` bodies, replaced in later tasks.)
- [ ] **Step 4: Run — passes.**
- [ ] **Step 5: Commit** (`feat(connection): BikeConnectionFactory.selectTransport by mode`).

---

## Task 5: `DefaultBikeConnection` state reducer (TDD the pure logic)

Extract the state transitions into a pure function so they're unit-testable without Android.

**Files:**
- Create: `connection/factory/DefaultBikeConnection.kt` (holds `internal fun reduce(cur, event): ConnState`)
- Test: `.../connection/factory/ConnStateReducerTest.kt`

**Interfaces:**
- Produces: `sealed interface ConnEvent { StartRequested; TransportOpened(endpoint); LinkEstablished; LinkDropped(reason); TransportLost(reason); Failed(reason,recoverable); Disconnected }`;
  `internal fun reduce(cur: ConnState, ev: ConnEvent): ConnState`;
  class `DefaultBikeConnection(transport, links, spec, io): BikeConnection`.

- [ ] **Step 1: Write the failing reducer test** — the reconnect rule from spec §6

```kotlin
class ConnStateReducerTest {
    private val ep = BikeEndpoint(null, java.net.Inet4Address.getByName("192.168.0.1") as java.net.Inet4Address,
        null, TransportKind.SOFT_AP, false)
    @Test fun `link drop while transport up goes to Retrying then re-enters at Handshake`() {
        val s1 = reduce(ConnState.Connected(ep), ConnEvent.LinkDropped("pxc timeout"))
        assertTrue(s1 is ConnState.Retrying)
        // resume re-establishes the LINK only (Handshake), not the transport
        val s2 = reduce(s1, ConnEvent.LinkEstablished)   // after re-establish
        assertTrue(s2 is ConnState.Connected)
    }
    @Test fun `transport lost re-enters at JoinTransport`() {
        val s = reduce(ConnState.Connected(ep), ConnEvent.TransportLost("wifi gone"))
        assertEquals(Phase.JoinTransport, (s as ConnState.Connecting).phase.let { it } .let { it })
    }
    @Test fun `disconnect always goes Idle`() =
        assertEquals(ConnState.Idle, reduce(ConnState.Connected(ep), ConnEvent.Disconnected))
}
```

- [ ] **Step 2: Run — fails.**
- [ ] **Step 3: Implement `reduce`** as a pure `when`, and `DefaultBikeConnection` driving it: `connect()`
  launches a coroutine on `io`'s scope that emits `Connecting(Discovering)` → `transport.open()` →
  `Connecting(Handshake)` → try links in order → `Connected`; a supervising loop feeds `ConnEvent`s
  (drop/lost) into `reduce`, applying the recovery (link-only re-establish on `LinkDropped` while transport
  is up; `transport.open` again on `TransportLost`) with capped exponential backoff; `disconnect()` stops
  links + transport and emits `Idle`. `state` is a `MutableStateFlow` exposed read-only.
- [ ] **Step 4: Run — passes.**
- [ ] **Step 5: Commit** (`feat(connection): DefaultBikeConnection state machine + reconnect reducer`).

---

## Task 6: Wrap SoftAP + P2P + EasyConn + Yunmo (device-verified)

Mechanical wraps of proven code — no unit tests (device-dependent); verified on the NK debug phone.

**Files:**
- Create: `connection/factory/transport/SoftApTransport.kt` (wraps `BikeWifi.reuseOrJoin`),
  `transport/P2pTransport.kt` (wraps `BikeWifiP2p.connect`),
  `link/EasyConnBikeLink.kt` (wraps `EasyConnProber`), `link/YunmoBikeLink.kt` (wraps `YunmoLink`).

- [ ] **Step 1: Implement `SoftApTransport.open()`** — `suspendCancellableCoroutine` bridging
  `BikeWifi.reuseOrJoin(ctx, ssid, psk, onAvailable = { net -> resume(BikeEndpoint(net, gateway, …, SOFT_AP, false)) }, onLost = …)`; `close()` releases the request. Read the current `BikeWifi` call site in
  `CfmotoConnect.kt:240-264` and mirror its exact args/gateway derivation.
- [ ] **Step 2: Implement `P2pTransport.open()`** — bridge `BikeWifiP2p.connect(...)` (deviceAddress from
  `spec` / the mac, timeout, the MAC±1 quirk already inside `BikeWifiP2p`); endpoint has `network = null`,
  `bindIp` set. Mirror `CfmotoConnect.kt:400-473`.
- [ ] **Step 3: Implement `EasyConnBikeLink.establish()`** — bridge `EasyConnProber.start(network, …)` →
  `LinkSession` wrapping the prober (`stop()` → `prober.stop()`). Add a `serverMode` param (default false)
  wired from `endpoint.phoneIsServer` for later use by PhoneHotspot; for now Softap/P2p pass false (unchanged).
- [ ] **Step 4: Implement `YunmoBikeLink.establish()`** — instantiate `YunmoLink` (the logic today hidden in
  `EasyConnProber.tryYunmoFallback`, `EasyConnProber.kt:451`) as a first-class link; `establish()` runs it,
  `stop()` stops it.
- [ ] **Step 5: Replace the factory shells** (Task 4/5 stubs) with these real classes; build.

Run: `./gradlew.bat assembleDebug --no-daemon --console=plain` → Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit** (`feat(connection): SoftAp/P2p transports + explicit EasyConn/Yunmo links (wrappers)`).

---

## Task 7: Route `joinWifi` through the factory (behind a flag) + verify NK regression

**Files:**
- Modify: `CfmotoConnect.kt` (add `USE_FACTORY` path in `joinWifi`), app init (install `PlatformIO`).

- [ ] **Step 1: Install `PlatformIO`** in the app (a `DefaultPlatformIO` that returns the current Activity,
  logs to `LogBus`, and a `VideoSink` that hands the `LinkSession`'s socket to the existing `VideoPipeline`
  the same way `EasyConnProber` does today). Set it in `OpenCfMotoApp`/`CockpitActivity` init.
- [ ] **Step 2: Add the factory path to `joinWifi`** — behind `private const val USE_FACTORY = true`: build
  `BikeConnectionFactory.create(ctx, qr, BikeMemory, platformIO).connect()` for SoftAP/P2P QRs; keep the old
  path reachable if `USE_FACTORY=false` (do NOT delete it yet).
- [ ] **Step 3: Build + install on the NK debug phone**

```bash
./gradlew.bat assembleDebug --no-daemon --console=plain
"E:/Android/Sdk/platform-tools/adb.exe" -s <device> install -r -d app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 4: Regression test on the bike (owner-in-the-loop or the NK phone against the bike):** Connect
  via SoftAP and via P2P; confirm the map projects and reconnect still works — same as before the factory.
  Watch the log for `[GPX]/[PROBE]` and the new `ConnState` transitions. If anything regresses, set
  `USE_FACTORY=false` (instant rollback) and fix.
- [ ] **Step 5: Commit** (`feat(connection): route SoftAP/P2P connect through the factory (flagged, NK-verified)`).

---

## Task 8: `ConnectionSpec` persistence in the Garage + config-ownership cleanup

**Files:**
- Modify: `BikeMemory.kt` (`specFor`/`saveSpec`), `DefaultBikeConnection` (save spec on `Connected`),
  `ui/settings/SettingsScreen.kt` (remove map-provider selector), garage screen (per-bike default provider),
  `QrScanActivity.kt` (set mode at pairing).

- [ ] **Step 1: Add `BikeMemory.specFor(qr): ConnectionSpec?` and `saveSpec(spec)`** — persist the
  `ConnectionSpec` JSON per `bikeId` alongside the existing `winningTransport`; `specFor` returns it (fast
  path). Keep `winningTransport` working (or have it read from the spec).
- [ ] **Step 2: Save the spec on success** — in `DefaultBikeConnection`, on `Connected`, call
  `memory.saveSpec(spec.copy(lastEndpointHint = endpoint.host.hostAddress))`.
- [ ] **Step 3: Config-ownership cleanup (spec §2)** — remove the map-**provider** selector from
  `SettingsScreen` (keep style tunables); ensure the Garage holds the per-bike `defaultMapProvider` and the
  Map screen's inline selector seeds from it with a "set as default" that writes back. Remove any global
  Wi-Fi-mode toggle; the mode is set at Scan → stored in the spec.
- [ ] **Step 4: Build + install + verify on the NK phone** — reconnect a known bike is faster (uses the
  saved spec, no P2P-vs-AP racing); Settings no longer shows the provider selector; the Garage default seeds
  the Map. Verify no setting is editable in two places.
- [ ] **Step 5: Commit** (`feat(connection): per-bike ConnectionSpec in Garage + single-owner config cleanup`).

---

## Task 9: `PhoneHotspotTransport` — the Rieju connector (ship APK for owner test)

The device-gated final piece. Reuses Task 1's `EcBtpApInfo` + the existing `BleWakeUp` scaffolding.

**Files:**
- Create: `connection/factory/transport/PhoneHotspotTransport.kt`
- Modify: `BleWakeUp.kt`/`BleProtocol.kt` (parameterize the GATT service: default `0000B360-…`,
  char by property among `B362/B363/B364`; keep the existing `B354` wake-up untouched).

- [ ] **Step 1: Add a BLE "AP-info push" helper** reusing `BleWakeUp`'s connect/MTU/notify (connect by
  `spec.bleMac`, service `spec.bleServiceOverride ?: "0000B360-D6D8-C7EC-BDF0-EAB1BFC6BCBC"`, pick WRITE +
  NOTIFY chars by GATT property). Expose `suspend fun pushApInfo(mac, service, ssid, pwd, ip): Boolean` that
  runs the sequence `0x30 CLIENT_INFO → 0x50 REQUEST_BUILD_NET → write EcBtpApInfo.frame(ssid,pwd,ip) → await 0x51/0x53`.
- [ ] **Step 2: Implement `PhoneHotspotTransport.open()`** per spec §8: `WifiP2pManager.createGroup` (phone
  = group owner; read `networkName/passphrase` + `groupOwnerAddress`); `pushApInfo(...)`; return
  `BikeEndpoint(network=null, host=192.168.49.1, kind=PHONE_HOTSPOT, phoneIsServer=true)`. On any BLE failure,
  fall back to the improved manual flow (readable creds + 2.4 GHz guidance) from the current `PhoneHotspotAssist`.
  If `io.activityOrNull() == null`, throw/emit `Error("needs foreground")`.
- [ ] **Step 3: Wire `EasyConnBikeLink` server mode** — when `endpoint.phoneIsServer`, run the PXC handshake
  in server mode (phone hosts). Confirm the prober supports it; if not, add a minimal server entry.
- [ ] **Step 4: Build the APK for the Rieju owner**

```bash
./gradlew.bat assembleDebug --no-daemon --console=plain
# hand app/build/outputs/apk/debug/app-debug.apk to the Rieju owner (zip + short instructions)
```

- [ ] **Step 5: Owner test + confirm the two open items** — the owner scans the Rieju QR (`action=128`),
  connects, and (if it fails) captures a one-tap **BT-HCI snoop** (Developer Options → Enable Bluetooth HCI
  snoop log → one official Carbit pairing → pull `btsnoop_hci.log`). Diff the real `0x52` frame + write-char
  UUID against `EcBtpApInfo`/`B360`; adjust `bleServiceOverride`/write-type if the dash remaps them.
- [ ] **Step 6: Commit** (`feat(connection): PhoneHotspotTransport — P2P group-owner + BLE 0x52 AP-info (Rieju)`).

---

## Self-Review

- **Spec coverage:** §2 config ownership → Task 8; §3/§4 architecture/contracts → Tasks 2,4,5; §5 spec/Garage
  → Tasks 3,8; §6 lifecycle → Task 5; §7 connectors → Tasks 6,9; §8 Rieju → Tasks 1,9; §9 testing → Tasks
  1,3,4,5 (unit) + 7,8,9 (device); §10 build order → Tasks 0–9 in order; Appendix A → Task 1 + Task 9. Covered.
- **Type consistency:** `EcBtpApInfo.frame(ssid,pwd,ip,auth,phoneMac)`, `ConnectionSpec.fromQr`,
  `BikeConnectionFactory.selectTransport/create`, `reduce(cur,ev)`, `BikeTransport.open`, `BikeLink.establish`,
  `PlatformIO.activityOrNull/videoSink` — used consistently across tasks.
- **Placeholder scan:** the `qr(...)` test helpers and the factory shells are explicitly "copy the real
  `QrData` signature" / "empty shells replaced in Task 6" — concrete instructions, not TBDs. The two Rieju
  open items are real, snoop-confirmed unknowns (spec §11), not plan gaps.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-08-18-bike-connection-factory.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — a fresh subagent per task, review between tasks, fast iteration.
**2. Inline Execution** — execute tasks in this session with checkpoints for review.

**Which approach?**
