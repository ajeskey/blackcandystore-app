# Chromecast Integration / Manual Test Plan (Task 17.3)

Feature: `app-multi-server-playback-and-casting` — Phase 4, Task 17.3
Requirements covered: **R9.1, R9.2, R9.5, R9.6, R11.4** (with supporting R9.3, R9.4, R10.x context).

## Why this is a manual/integration plan (not property-based)

The Chromecast path drives external SDKs — Android `androidx.media3:media3-cast` `CastPlayer` +
`play-services-cast-framework` `CastContext`, and iOS `GoogleCast` (`GCKSessionManager` /
`GCKRemoteMediaClient`). These require a real or emulated Chromecast receiver on the local network
and cannot be exercised in CI/unit tests. The design's Testing Strategy explicitly classifies this
as "Chromecast load/control against a real/emulated receiver ... Integration/manual (NOT
property-based) — external SDK path."

The **pure state logic** behind the engine is already automated and green (see
[Automated coverage](#automated-coverage-that-backs-this-plan) below), so this plan focuses only on
the SDK/device I/O that automation cannot reach.

---

## Setup / prerequisites

### Receiver
- A Chromecast device (Chromecast Audio, Chromecast with Google TV, or a Cast-enabled speaker/TV),
  **or** the Google Cast Command and Control (CaC) tool / emulated receiver.
- Receiver application id: currently the **Default Media Receiver `CC1AD845`**
  (Android `CastOptionsProvider.RECEIVER_APPLICATION_ID`; iOS
  `CastConfiguration.receiverApplicationID = kGCKDefaultMediaReceiverApplicationID`). The Default
  Media Receiver plays plain media URLs and is sufficient for these tests. If a custom receiver is
  registered later, use its id and keep both platforms in sync.

### Network
- The test device (phone) and the Chromecast receiver must be on the **same Wi-Fi / subnet** and
  mDNS/Bonjour discovery must be permitted by the network.
- iOS additionally requires the local-network permission prompt to be accepted and the
  `NSBonjourServices` + `NSLocalNetworkUsageDescription` `Info.plist` entries (see
  `CastConfiguration.swift` header) to be present.

### App / server
- A Black Candy server reachable by the app, logged in.
- At least:
  - one **local** song (`stream_source = local`) that resolves to a receiver-reachable URL;
  - one **remote** song (`stream_source = remote`) that has a `cast_stream_url` (or a reachable
    `resolved_stream_path`) per R11.2/R11.3;
  - one **not-castable** song — a song for which no `cast_stream_url` is available and whose
    `resolved_stream_path`/legacy `url` is empty or not independently reachable (so
    `Song.castUrlOrNull()` returns `null`, R11.4).
- Server capabilities must advertise Chromecast so the Device_Picker exposes the receiver
  (Property 5 / R12.2).

### Platform-specific enablement status
- **Android**: fully wired. `CastContext` is initialized at launch in `MainApplication`, and
  `ChromecastEngine` (androidMain) drives a Media3 `CastPlayer`. Execute all cases below on Android.
- **iOS**: the `ChromecastEngine` iOS `actual` is currently a **compiling stub** — the GoogleCast
  SDK is not yet exposed to Kotlin/Native via cinterop (`canImport(GoogleCast)` false; no
  `GoogleCast.def`). Until that cinterop is configured (see `ChromecastEngine.kt` iosMain
  `TODO(GCK cinterop)` and `CastConfiguration.swift` header), iOS `play()` reports the current song
  as not-castable rather than casting. On iOS today, only case **MT-6 (not-castable fallback)** is
  meaningfully exercisable; the load/control cases (MT-1..MT-5) become executable once the GCK
  cinterop lands. Re-run the full matrix on iOS at that point.

---

## Test cases

Each case lists steps, expected result, and the requirement(s) it verifies. Preconditions:
app open, logged in, on a network with the receiver, Device_Picker shows the Chromecast device.

### MT-1 — Establish session and load from Cast_Stream_Url (local song) — R9.1, R9.2, R9.4
1. Select a **local** song into the queue.
2. Open the Device_Picker and select the Chromecast receiver under `client_cast`.
3. Press Play.

Expected:
- A Cast SDK session is established to that receiver and a Cast_Session targets it (R9.1).
- The receiver begins playing; audio comes out of the **receiver**, not the phone. The phone is a
  controller only and does not decode/stream the bytes itself (R9.4).
- The URL loaded onto the receiver is the song's `castUrlOrNull()` (Cast_Stream_Url), i.e. the
  receiver fetches the media itself (R9.2). Confirm via the CaC tool / receiver debug that the
  loaded media URL matches the resolved Cast_Stream_Url.

### MT-2 — Load Cast_Stream_Url for a remote (proxied) song — R9.2, R9.3, R11 (context)
1. Select a **remote** song (has `cast_stream_url` or reachable `resolved_stream_path`).
2. Cast to the receiver and Play.

Expected:
- The receiver fetches and plays the proxied audio using the server-provided Cast_Stream_Url
  **without** the app's bearer token / session cookie (R9.3, R11.1/R11.2). Confirm the loaded URL is
  the `cast_stream_url` (or reachable resolved path), not an app-credentialed URL.
- Audio plays on the receiver.

### MT-3 — Play / pause / resume / stop reflect on the receiver — R9.5 (+ R10.1/R10.2/R10.3)
1. With a song casting (MT-1 or MT-2), press **Pause**.
   - Expected: receiver pauses; app shows `paused`; song and position retained (R10.2). App state
     reflects the receiver's reported media state (R9.5).
2. Press **Play/Resume**.
   - Expected: receiver resumes from the retained position; app shows `playing` (R10.1, R9.5).
3. Press **Stop**.
   - Expected: receiver stops; app shows `stopped` and position cleared (R10.3, R9.5).

### MT-4 — Volume control maps to the device — R9.5 (+ R10.4)
1. While casting, change the app's cast volume across its range (min → mid → max).

Expected:
- The receiver device volume changes accordingly, normalized 0.0–1.0 mapped to the device range
  (R10.4). The app reflects the receiver's reported volume/state (R9.5).

### MT-5 — Receiver state changes originate from the device — R9.5
1. While the app is casting, change state **from another controller** (e.g. Google Home app or the
   CaC tool): pause, change volume, or stop on the receiver directly.

Expected:
- The app's Cast_Session state and displayed position update to match the receiver's reported
  state (R9.5) — the app follows the receiver, it does not fight it.

### MT-6 — Not-castable fallback — R9.6, R11.4
1. Select the **not-castable** song (no obtainable Cast_Stream_Url; `castUrlOrNull()` is `null`).
2. Cast to the receiver and press Play.

Expected:
- The app does **not** load an unreachable URL onto the receiver (R11.4).
- The song is treated as unavailable for casting: the Cast_Session goes/stays `stopped` with a
  "song unavailable" indication (`PlaybackError.SongUnavailable`), applying the Requirement 10
  handling (R9.6). No crash, no silent hang.
- Executable on both platforms today (this is the one iOS-stub-safe case).

### MT-7 — Disconnect handling while playing — R10.6 (verifies R9.5 device-state reflection on loss)
1. Start casting (MT-1). While `playing`, force a disconnect: power off the receiver, drop it from
   Wi-Fi, or move out of range.

Expected:
- The app detects the session end and sets the Cast_Session to `stopped`, indicating that casting
  stopped because the device disconnected (R10.6). The app reflects the terminated receiver state
  (R9.5). No crash; routing falls back cleanly and local controls become available again.

### MT-8 — Not-reachable at cast start — R10.5 (supporting)
1. Choose a receiver that is known/registered but currently unreachable (powered off or off-network)
   and attempt to cast + Play.

Expected:
- The cast is rejected with a not-reachable error and the Cast_Session is set to `stopped` (R10.5).
  No unreachable URL load; clear error indication.

### MT-9 — Stall timeout (30s no first byte) — R10.7 (supporting)
1. Cast a song whose Cast_Stream_Url is reachable to discovery but never produces audio (e.g. a
   deliberately stalled/black-hole URL served to the receiver).

Expected:
- After ~30 seconds with no audio produced, the app stops the cast and indicates the song is
  currently unavailable (R10.7).

---

## Requirement → test-case mapping

| Requirement | Description | Cases |
| --- | --- | --- |
| R9.1 | Establish Cast SDK session; Cast_Session targets device | MT-1 |
| R9.2 | Load Song's Cast_Stream_Url onto receiver (receiver fetches) | MT-1, MT-2 |
| R9.3 | Remote song uses R11 Cast_Stream_Url (no app session creds) | MT-2 |
| R9.4 | App is controller-only; not the audio source | MT-1 |
| R9.5 | Reflect receiver-reported media state + position | MT-3, MT-4, MT-5, MT-7 |
| R9.6 | No Cast_Stream_Url ⇒ treat song unavailable (R10 handling) | MT-6 |
| R11.4 | Not-castable: indicate, don't load an unreachable URL | MT-6 |
| R10.6 | Disconnect while playing ⇒ stopped + reason | MT-7 |
| R10.5 | Not reachable at start ⇒ reject + stopped | MT-8 |
| R10.7 | 30s no-first-byte ⇒ stop + unavailable | MT-9 |

R9.5's transition/invariant logic (states, resume-after-pause retaining song/position) is proven
by automation (Property 3); MT-3..MT-5/MT-7 confirm the SDK adapter feeds the receiver's real state
into that proven machine.

---

## Automated coverage that backs this plan

The pure logic behind the SDK path is already automated and passing; the manual cases only verify
the device I/O the automation cannot reach:

- **Cast session state invariant (Property 3)** —
  `shared/src/commonTest/.../media/CastSessionMachinePropertyTest.kt` (≥100 iterations): every
  reachable state is exactly one of `stopped`/`playing`/`paused` (R10.8) and resume-after-pause
  returns to `playing` with the same song/position (R10.9). Example transitions incl. disconnect,
  timeout, volume clamp, and the `EngineStatus` projection are pinned in
  `.../media/CastSessionMachineTest.kt`.
- **Cast_Stream_Url resolution / not-castable (R11)** —
  `shared/src/androidHostTest/.../media/CastStreamUrlResolverTest.kt`: preference order
  (payload → dedicated endpoint → reachable path → not castable), the R18.2 endpoint gate, R11.5
  no-credential-appending, and — most relevant to MT-6 — `returnsNullWhenNothingObtainable` proving
  the resolver yields `null` (not-castable) without loading an unreachable URL (R11.4).
- **`Song.castUrlOrNull()` (R11.3)** — `shared/src/commonTest/.../models/SongResolutionPropertyTest.kt`:
  dedicated cast url wins, else reachable `playbackUrl` when available, else `null`.

### SDK path is manual-only (why no engine-level automated test was added)

`ChromecastEngine`'s Android `actual` cannot be instantiated in host/JVM tests: its constructor and
transport calls require an Android `Context`, a main `Looper`/`Handler`, and a live `CastContext`
(`CastContext.getSharedInstance(...)`), none of which exist in `androidHostTest` without an
emulator/Robolectric and Google Play services. The iOS `actual` is a compiling stub pending GCK
cinterop. Therefore the engine's "surface not-castable (`SongUnavailable`)" behavior and all
load/control behavior are verified **manually** via MT-1..MT-9 above, while the decision inputs the
engine relies on (`castUrlOrNull()` / resolver returning `null`) and the state machine it projects
through are covered by the automated tests listed above.

---

## Execution log (fill in per run)

| Date | Platform / OS | App version | Receiver / app id | Cases run | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
|  | Android |  | CC1AD845 | MT-1..MT-9 |  |  |
|  | iOS (post-GCK cinterop) |  | CC1AD845 | MT-1..MT-9 |  |  |
|  | iOS (stub build) |  | n/a | MT-6 |  | only not-castable case applicable |
