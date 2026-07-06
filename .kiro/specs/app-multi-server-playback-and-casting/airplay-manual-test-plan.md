# AirPlay Routing Integration / Manual Test Plan (Task 19.2)

Feature: `app-multi-server-playback-and-casting` — Phase 5, Task 19.2
Requirements covered: **R8.1, R8.3, R8.4** (with supporting R8.2, R8.5, R10.6, R12.1 context).

## Why this is a manual/integration plan (not property-based)

The AirPlay path drives Apple's system audio routing. `AirPlayEngine` (iOS only) reuses the local
`AVPlayer` (via `MusicServiceController`) as the audio source and hands device selection + password
entry to the **system route picker** (`AVRoutePickerView`), then follows the shared `AVAudioSession`
route. It also listens for `AVAudioSessionRouteChangeNotification` to detect route loss. None of
this can be exercised in CI/unit tests: it requires a real iOS device, a live AirPlay target on the
network, and the iOS audio session / UIKit route picker. The design's Testing Strategy explicitly
classifies this as "iOS AirPlay routing + password via route picker — Integration / manual (not
property-testable — external components)."

The **pure state logic** behind the engine is already automated and green (see
[Automated coverage](#automated-coverage-that-backs-this-plan) below) — `AirPlayEngine` projects its
observable state through the same shared `CastSessionMachine` proven by **Property 3**. This plan
therefore focuses only on the device/route I/O that automation cannot reach.

---

## Setup / prerequisites

### Device (sender)
- A physical **iOS device** (iPhone/iPad) with the app installed. AirPlay `client_cast` is offered
  only on iOS (R8.5, R12.1); there is no AirPlay sender on Android, so nothing to test there beyond
  the Android negative case (AT-6).
- The Simulator does **not** expose real AirPlay targets — use a real device.

### AirPlay target (receiver)
- At least one AirPlay-2 audio target on the **same Wi-Fi / subnet** as the phone: a HomePod /
  HomePod mini, an Apple TV, or a third-party AirPlay-2 speaker/receiver (e.g. an AirPlay-capable
  A/V receiver or Shairport-style software receiver).
- For **AT-2 (password)**, a **password-protected** AirPlay target. Configure an AirPlay password on
  an Apple TV/receiver (Settings → AirPlay → Security → Password) or on a software receiver that
  supports a device password, so the system route picker prompts for it on first connect.
- Local-network permission must be granted to the app on iOS so route discovery works.

### App / server
- A Black Candy server reachable by the app, logged in.
- At least:
  - one **local** song (`stream_source = local`) that plays fine in the local `App_Player`;
  - one **remote / proxied** song (`stream_source = remote`) that resolves to a reachable
    `playbackUrl` — this verifies R8.2 (remote songs route to AirPlay exactly like local ones,
    using the app's own credentials, since the phone stays the source).
- The Device_Picker must show the AirPlay target under the **Local_Output_Devices** (`client_cast`)
  section on iOS (R6.3).

### App wiring under test (for reference)
- `AirPlayEngine` (`shared/src/iosMain/.../media/AirPlayEngine.kt`) reuses `MusicServiceController`
  / `AVPlayer` as the audio source; each song's `AVURLAsset` is built from `Song.playbackUrl` with
  the app's `Authorization`/`User-Agent` headers (R8.1, R8.2).
- Device + password selection is the **`AVRoutePickerView` seam**: `activate()` calls
  `requestRoutePicker()`, which flips the `routePickerRequested` StateFlow to `true` and invokes the
  optional `routePickerPresenter` callback. The SwiftUI player layer observes this, presents the
  system `AVRoutePickerView`, then calls `onRoutePickerPresented()` to reset the flag. The app never
  collects the password itself (R8.3).
- Route loss: the engine registers an `AVAudioSessionRouteChangeNotification` observer while active;
  on `AVAudioSessionRouteChangeReasonOldDeviceUnavailable` for a previous route that carried an
  AirPlay output, and while the session state is `playing`, it pauses the local player and drives
  `CastSessionMachine.onDisconnect()` → `stopped` with `PlaybackError.DeviceDisconnected`
  (R8.4 → R10.6).

---

## Test cases

Each case lists steps, expected result, and the requirement(s) it verifies. Preconditions: app open
on the iOS device, logged in, on a network with the AirPlay target, Device_Picker shows the AirPlay
device under `client_cast`.

### AT-1 — Route local song audio to the AirPlay device; phone stays the source — R8.1, R8.2
1. Select a **local** song into the queue.
2. Open the Device_Picker and select the AirPlay target under `client_cast`.
3. The system route picker (`AVRoutePickerView`) appears; choose the AirPlay device.
4. Press Play.

Expected:
- A `Cast_Session` is created whose target is the selected AirPlay device (R8.1).
- Audio comes out of the **AirPlay speaker**, not the phone speaker; the phone remains the audio
  source (it is decoding/streaming the bytes and iOS routes the output). Confirm by observing the
  iOS route indicator / Control Center shows the AirPlay device as the audio route (R8.1, R8.2).
- Transport controls (play/pause) in the app affect the audio on the speaker.

### AT-2 — Route a remote/proxied song using `playbackUrl` (no Cast_Stream_Url) — R8.2 (+ R11.6)
1. Select a **remote** (`stream_source = remote`) song into the queue.
2. With the AirPlay device already selected (from AT-1) or re-select it, press Play.

Expected:
- The remote song plays on the AirPlay speaker exactly like a local song: the phone fetches it from
  the song's `Resolved_Stream_Path` / `playbackUrl` with the app's own auth headers and iOS routes
  the decoded output (R8.2). No `Cast_Stream_Url` is required for AirPlay (R11.6) — the receiver-
  fetch path is Chromecast-only.
- Audio plays on the speaker; no credential handoff to the device occurs.

### AT-3 — Device selection + password handled entirely by the system route picker — R8.3
1. Ensure the target for this case is a **password-protected** AirPlay device.
2. From the app, initiate AirPlay routing (select the AirPlay section / device). The engine requests
   the picker (`routePickerRequested` → `true`; SwiftUI presents `AVRoutePickerView`).
3. In the **system** route picker, select the password-protected device.

Expected:
- The **iOS system picker** prompts for the AirPlay device password and validates it. The **app UI
  never shows its own password field** for the AirPlay device and never collects/stores the password
  (R8.3). Confirm there is no app-rendered password prompt at any point in this flow.
- After the system validates the password, audio routes to the device and playback proceeds.
- If an incorrect password is entered, the failure is surfaced by the **system** picker (not the
  app), and audio does not route to the device.

### AT-4 — Route loss while playing → disconnect handling → stopped — R8.4 (→ R10.6)
1. Start playing to the AirPlay device (AT-1). Confirm state is `playing` on the speaker.
2. Force the route to become unavailable: power off the AirPlay target, disconnect it from Wi-Fi,
   or move it out of range (any action that makes iOS post
   `OldDeviceUnavailable` for the AirPlay route).

Expected:
- The engine detects the AirPlay route loss, pauses the local `AVPlayer`, and drives the
  `Cast_Session` to `stopped` with an indication that casting stopped because the device
  disconnected (`PlaybackError.DeviceDisconnected`) — the Requirement 10 disconnect handling reached
  via R8.4 (R8.4 → R10.6).
- No crash; audio does not silently fall back to the phone speaker mid-stream in a `playing` state,
  and local controls become available again. The app state reflects `stopped`.

### AT-5 — Route loss while NOT playing is not treated as a disconnect — R8.4 (boundary)
1. Route to the AirPlay device, then **pause** (state `paused`) or `stop`.
2. Power off / disconnect the AirPlay target.

Expected:
- Because the session was not `playing` at the moment of route loss, the engine does **not** trigger
  the `playing`-guarded disconnect-to-`stopped` transition (R8.4 applies only while `playing`). The
  app remains in its current non-`playing` state without spurious error. (This confirms the
  `machine.state == PLAYING` guard in `handleRouteChange`.)

### AT-6 — Android offers no AirPlay `client_cast` option — R8.5, R12.1
1. On an **Android** device, open the Device_Picker.

Expected:
- The Local_Output_Devices (`client_cast`) section shows **no AirPlay devices** and offers no
  AirPlay casting option; only Chromecast client-cast is available on Android (R8.5, R12.1). AirPlay
  targets are only reachable on Android via `server_playback` (server-driven), not `client_cast`.
- Quick confirmation only — no AirPlay engine is registered on Android
  (`PlaybackCoordinator` receives `airplay = null`).

---

## Requirement → test-case mapping

| Requirement | Description | Cases |
| --- | --- | --- |
| R8.1 | iOS: route local `AVPlayer` output to selected AirPlay device; create Cast_Session targeting it | AT-1 |
| R8.2 | App_Player stays the source; obtains audio from `Resolved_Stream_Path` incl. remote songs | AT-1, AT-2 |
| R8.3 | System route picker collects/validates the device password; app does NOT collect it | AT-3 |
| R8.4 | Route unavailable while `playing` ⇒ apply R10 disconnect handling | AT-4, AT-5 (boundary) |
| R10.6 | Disconnect while playing ⇒ `stopped` + reason (reached via R8.4) | AT-4 |
| R8.5 / R12.1 | AirPlay `client_cast` offered on iOS only (none on Android) | AT-6 |
| R11.6 | No `Cast_Stream_Url` required for AirPlay routing (context) | AT-2 |

R8.4's transition logic (route-loss while `playing` → `stopped` with `DeviceDisconnected`, and the
non-`playing` boundary) is proven by automation (Property 3); AT-4/AT-5 confirm the iOS route-change
notification feeds the real route-loss event into that proven machine.

---

## Automated coverage that backs this plan

The pure logic behind the AirPlay path is already automated and passing; the manual cases only
verify the device/route I/O the automation cannot reach:

- **Cast session state invariant (Property 3)** —
  `shared/src/commonTest/.../media/CastSessionMachinePropertyTest.kt` (≥100 iterations): every
  reachable state is exactly one of `stopped`/`playing`/`paused` (R10.8) and resume-after-pause
  returns to `playing` with the same song/position (R10.9). `AirPlayEngine` drives its state through
  this exact `CastSessionMachine`, including `onDisconnect()` for R8.4 → R10.6.
- **Cast session transitions / disconnect** —
  `shared/src/commonTest/.../media/CastSessionMachineTest.kt` pins example transitions, including the
  `onDisconnect()` → `stopped` + `DeviceDisconnected` behavior the AirPlay route-loss handler invokes
  and the `EngineStatus` projection the engine exposes.

### Route/device path is manual-only (why no engine-level automated test was added)

`AirPlayEngine` lives in `iosMain` and depends on Apple frameworks that do not exist in host/JVM
tests: `AVAudioSession` route-change notifications (`NSNotificationCenter` /
`AVAudioSessionRouteChangeNotification`), the `AVRoutePickerView` UIKit seam, and a live
`MusicServiceController`/`AVPlayer` producing audio that iOS routes to a network device. There is no
Kotlin/Native host-test harness for these, and Apple does not let third-party apps enumerate AirPlay
devices or supply the device password programmatically (that is exactly why R8.3 defers to the
system picker). Therefore the routing, password, and route-loss detection behaviors are verified
**manually** via AT-1..AT-6 above, while the state machine the engine projects through is covered by
the automated tests listed above.

---

## Execution log (fill in per run)

| Date | Device / iOS | App version | AirPlay target | Cases run | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
|  | iOS |  | HomePod / Apple TV / AirPlay speaker | AT-1..AT-5 |  |  |
|  | iOS |  | password-protected target | AT-3 |  | verify no app-side password prompt |
|  | Android |  | n/a | AT-6 |  | confirm no AirPlay client-cast option |
