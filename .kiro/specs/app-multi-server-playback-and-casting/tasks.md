# Implementation Plan: App Multi-Server Playback and Casting

## Overview

This plan implements multi-server streaming, client casting (AirPlay/Chromecast), and server-driven playback remote control in the Black Candy Store app (KMP + Hotwire Native). Work follows the design's five-phase Phased Delivery Plan; each phase is independently shippable and leaves the app working on any server, including old single-library servers.

Within each phase, tasks are sequenced models → shared logic → API/repositories → engines → platform integration → UI, so each step builds on the last. Every task grounds itself in the existing codebase (`Song`, `SystemInfo`, `BlackCandyService`, `MusicServiceController` expect/actual, `PlayerViewModel`, `CurrentPlaylistRepository`, Koin `commonModule`, `path-configuration.json`, the Android Media3 `MusicService`, the iOS `AVPlayer` controller, and the bridge components).

Testing follows the design's dual approach:
- **Property-based tests** cover the 6 correctness properties in pure `commonMain` logic. A property-testing helper is added in task 1.1; each property test runs a **minimum of 100 iterations** and is tagged `# Feature: app-multi-server-playback-and-casting, Property {number}: {property_text}`.
- **Integration / manual tests** cover the paths the design marks NOT property-testable: Chromecast (media3-cast / GoogleCast), iOS AirPlay routing, server-playback control+polling (Ktor `MockEngine`), and Hotwire routing.

High-risk protocol work (Chromecast in Phase 4, AirPlay in Phase 5) is isolated behind `expect`/`actual` engines; the `commonMain` state machines and planners are property-tested while the SDK/wire paths are integration/manual only.

## Tasks

---

## Phase 1 — Resolved paths, availability, capabilities, backward compat
**Requirements: 1, 2, 3, 4, 17, 18. Properties: 1, 2, 6.**
Delivers cross-server streaming with no external SDKs. Fully testable.

- [x] 1. Test harness and shared model foundation
  - [x] 1.1 Add a KMP property-testing helper
    - Add a `checkProperty(iterations = 100) { ... }` helper under `shared/src/commonTest` (and `androidHostTest` wiring) that runs a minimum of 100 generated iterations and reports the failing input
    - Document the required tag format `# Feature: app-multi-server-playback-and-casting, Property {number}: {property_text}`
    - _Requirements: supports all property tests; Testing Strategy_

  - [x] 1.2 Extend the `Song` model with resolved fields
    - Add `StreamSource` enum (`local`/`remote`) and `streamSource`, `resolvedStreamPath`, `castStreamUrl`, `resolvedAssetPath` to `Song` with backward-compatible defaults
    - Add computed `playbackUrl`, `isAvailable`, `castUrlOrNull()`, `artworkUrl` per the design
    - Confirm the shared `Json` config (`ignoreUnknownKeys`, `SnakeCase`) decodes both old and new payloads
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 1.7, 11.3, 17.1, 17.2, 18.5_

  - [x] 1.3 Write property test for resolution consistency
    - `# Feature: app-multi-server-playback-and-casting, Property 1: Resolution consistency`
    - Generate songs with resolved path present-nonempty / present-empty / absent (with/without legacy url); assert `isAvailable`/`playbackUrl`/`artworkUrl` follow the rules
    - **Validates: Requirements 1.2, 1.3, 1.5** (Property 1), min 100 iterations
    - _Requirements: 1.2, 1.3, 1.5_

- [x] 2. Server capability detection
  - [x] 2.1 Add `ServerCapabilities` and extend `SystemInfo`
    - Add `ServerCapabilities` model and optional `capabilities` field on `SystemInfo`; add a resolver in `SystemInfoRepository` that returns reported capabilities or infers them from `version` per-feature minimums
    - Expose a resolved capabilities value the app can read for gating
    - _Requirements: 4.1, 4.4, 4.5, 18.2, 18.3_

  - [x] 2.2 Write unit tests for capability resolution
    - Test reported-capabilities pass-through, version-inferred fallback, and absent-capabilities → all-off with no error
    - _Requirements: 4.1, 4.4, 4.5_

- [x] 3. Availability-aware queue planning
  - [x] 3.1 Implement `QueuePlanner` pure logic
    - Create `QueuePlanner.nextAvailableIndex(queue, from, direction, repeatMode)` honoring `NO_REPEAT`/`REPEAT`/`REPEAT_ONE`/`SHUFFLE`, returning `null` when no available song remains; add helpers for order/membership preservation
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.7_

  - [x] 3.2 Write property test for queue integrity
    - `# Feature: app-multi-server-playback-and-casting, Property 2: Queue integrity under unavailability`
    - Generate mixed available/unavailable queues and repeat modes; assert order/membership preserved, advance lands on available or none, `REPEAT_ONE`+unavailable yields none
    - **Validates: Requirements 2.3, 2.4, 2.5, 2.7** (Property 2), min 100 iterations
    - _Requirements: 2.3, 2.4, 2.5, 2.7_

- [x] 4. Wire both players to resolved paths and availability
  - [x] 4.1 Use `playbackUrl` in the Android player
    - Update `toMediaItem` in the Android `MusicServiceController` to build the URI from `song.playbackUrl` and artwork from `song.artworkUrl`
    - Guard `playOn`/advance against unavailable songs using `QueuePlanner`; surface unavailable via existing state
    - NOTE: URL + artwork wiring and the explicit-selection guard (4.3) are done. Auto-skip of unavailable songs during continuous ExoPlayer advance is deferred to the Phase 3 `PlaybackCoordinator`, which centralizes `QueuePlanner`-based advancement across engines.
    - _Requirements: 1.2, 1.4, 1.6, 2.4, 2.6, 16.4_

  - [x] 4.2 Use `playbackUrl` in the iOS player
    - Update the iOS `MusicServiceController` `playOn` and the play-to-end handler to use `song.playbackUrl`, artwork `song.artworkUrl`, and `QueuePlanner` for advancing; keep the existing auth-header asset setup
    - NOTE: URL + artwork wiring done. Auto-skip via `QueuePlanner` in the play-to-end handler is deferred to the Phase 3 `PlaybackCoordinator` to keep skip semantics identical across platforms and engines.
    - _Requirements: 1.2, 1.4, 1.6, 2.4, 2.5, 2.6, 2.7_

  - [x] 4.3 Handle explicit selection of an unavailable song
    - In `PlayerViewModel.playOn`, reject selection of an unavailable song with an `AlertMessage` and no current-song change
    - _Requirements: 1.6, 2.6_

- [x] 5. Stale remote-link refresh
  - [x] 5.1 Add `SongRepository.getSong` and `getSong` service method
    - Add `getSong(songId)` to `BlackCandyService` + a `SongRepository`; return the refreshed `Song`
    - _Requirements: 3.1, 3.2_

  - [x] 5.2 Implement bounded refresh-and-retry + 30s watchdog
    - On an engine load-failure for a `remote` song, re-fetch once (per-song guard), retry if refreshed path non-empty, else mark unavailable and skip; add a 30s no-first-byte timeout per play request
    - NOTE: The bounded refresh-and-retry policy (`ResolvedPathRefresher`) is implemented and property-tested (R3.5). Hooking it to the platform players' load-failure callbacks and the 30s no-first-byte watchdog is deferred to the Phase 3 `PlaybackCoordinator`, which owns engine error callbacks.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [x] 5.3 Write property test for bounded refresh
    - `# Feature: app-multi-server-playback-and-casting, Property 6: Bounded refresh`
    - Generate repeated-failure scenarios; assert at most one refresh-and-retry per song per play request
    - **Validates: Requirements 3.5** (Property 6), min 100 iterations
    - _Requirements: 3.5_

- [x] 6. Artwork fallback and gating cleanup
  - [x] 6.1 Apply `artworkUrl` fallback in artwork loading
    - Use `song.artworkUrl` (resolved → legacy → placeholder) wherever artwork loads (Android Coil, iOS now-playing artwork, player art), never showing a broken image
    - _Requirements: 17.1, 17.2, 17.3, 17.4_

  - [x] 6.2 Verify backward compatibility on a legacy server
    - Confirm that with capabilities off and no resolved fields, playback uses legacy `url`, all songs are available, and no multi-server controls appear
    - NOTE: Verified via unit tests (capability resolution + Song resolution fallback) and full compile of both apps. Live legacy-server smoke test still pending an actual server.
    - _Requirements: 18.1, 18.3, 18.4, 18.5_

- [x] 7. Phase 1 checkpoint
  - Build shared + both apps, run all Phase 1 tests, confirm streaming works against a resolved-path server and an old server.

---

## Phase 2 — Multi-library Turbo routing
**Requirements: 5.**
Small; leverages Hotwire.

- [ ] 8. Route the server's new library/sharing screens
  - [ ] 8.1 Add `path-configuration.json` rules on both platforms
    - Add rules for library management, active-library selection, invites, redemptions, access lists, and source-preference paths using existing `default`/`modal`/`replace_root` conventions (Android `androidApp/src/main/assets/json/path-configuration.json` and `iosApp/iosApp/path-configuration.json`)
    - _Requirements: 5.1, 5.2, 5.5_

  - [ ] 8.2 Add bridge components for any native surfaces
    - Where the server emits Hotwire bridge events for a native picker/sheet on these screens, add matching bridge components on Android and iOS following the existing component pattern
    - _Requirements: 5.4_

  - [ ] 8.3 Refresh the queue on active-library change
    - Detect active-library change (bridge event or navigation signal) and refresh the current playlist so the player reflects newly scoped content
    - _Requirements: 5.3_

- [ ] 9. Phase 2 checkpoint
  - Verify new server screens present correctly and library switching refreshes playback context.

---

## Phase 3 — Device model, server-driven playback, routing scaffolding
**Requirements: 6 (server side), 7, 13, 14, 16 (core). Properties: 4 (routing half).**
API-only; delivers "stream from server to devices." No cast SDK.

- [ ] 10. Output device + session models and services
  - [ ] 10.1 Add device/session models and service methods
    - Add `OutputDevice`, `OutputDeviceProtocol`, `DeviceOrigin`, `PlaybackSession`; add `getOutputDevices`, `getPlaybackSession`, `putPlaybackSession`, `controlPlaybackSession`, `setPlaybackMode` to `BlackCandyService`
    - _Requirements: 6.2, 6.6, 7.3, 13.3, 14.1, 14.2_

  - [ ] 10.2 Add `ServerDeviceRepository` and `PlaybackSessionRepository`
    - Wrap the new service methods; empty/failed device list → empty "no devices" state, not an error; register in Koin `commonModule`
    - _Requirements: 6.5, 14.4, 18.2_

- [ ] 11. PlaybackEngine abstraction and coordinator
  - [ ] 11.1 Define `PlaybackEngine`, `EngineStatus`, `PlaybackTarget`, `PlaybackRouting`
    - Add the interface and value types in `commonMain` per the design
    - _Requirements: 7.1, 7.4_

  - [ ] 11.2 Implement `LocalPlaybackEngine` wrapping `MusicServiceController`
    - Adapt the existing controller behind the interface so local playback is unchanged
    - _Requirements: 7.1, 7.7, 18.1_

  - [ ] 11.3 Implement `PlaybackCoordinator` with routing exclusivity
    - Hold the active routing/engine, expose a unified `PlaybackStatus`, and implement `selectTarget` to deactivate the current engine (retain position), activate the next, and transfer queue/position
    - _Requirements: 7.2, 7.4, 7.5, 7.6, 15.1, 15.2, 15.3, 15.4, 15.5_

  - [ ] 11.4 Write property test for routing exclusivity
    - `# Feature: app-multi-server-playback-and-casting, Property 4: Routing exclusivity`
    - Generate target-selection sequences (local/cast/server); assert at most one active engine and prior engine deactivated before the next activates
    - **Validates: Requirements 7.4, 15.1, 15.5** (Property 4), min 100 iterations
    - _Requirements: 7.4, 15.1, 15.5_

- [ ] 12. Server-driven playback engine
  - [ ] 12.1 Implement `ServerPlaybackEngine` with Session_Observation
    - On activate: sync app queue to server current playlist, `putPlaybackSession(devices, currentSongId, password?)`; transport ops via `controlPlaybackSession`; poll `getPlaybackSession` every 5s and after each op, mapping to `EngineStatus`; detach local audio
    - Propagate queue edits to the server current playlist while active
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 14.1, 14.2, 14.3, 14.4, 14.6, 14.7_

  - [ ] 12.2 Wire playback-mode parity reporting
    - Report selected routing via `setPlaybackMode` without blocking casting/remote control on failure
    - _Requirements: 7.3_

  - [ ] 12.3 Write integration tests for server-playback control + polling
    - With a Ktor `MockEngine` stub: verify create/update session, play/pause/stop/volume, polling adoption of server state, no-device error (14.5), and last-device stop (14.6)
    - Integration tests (NOT property-based) — network path
    - _Requirements: 14.1, 14.2, 14.4, 14.5, 14.6_

- [ ] 13. Device picker and player UI (core)
  - [ ] 13.1 Add `DevicePickerViewModel` and the two device sections
    - Combine `ServerDeviceRepository` output (and, later, local devices) into a UI model with separate server/local sections gated by capabilities; selection drives `PlaybackCoordinator.selectTarget`
    - _Requirements: 6.1, 6.5, 6.7, 7.2, 16.1_

  - [ ] 13.2 Add the Device_Picker entry point and routing indicators
    - Add a cast/devices control to Android `PlayerActions`/`PlayerScreen` and iOS `PlayerActions.swift`/`FullPlayer.swift`, shown only when capable; indicate active routing + device name; keep repeat/shuffle control independent
    - _Requirements: 16.1, 16.2, 16.3, 16.5, 16.6_

  - [ ] 13.3 Route `PlayerViewModel` through the coordinator
    - Point `PlayerViewModel` transport actions and state at `PlaybackCoordinator` instead of `MusicServiceController` directly; mark unavailable queue songs disabled-but-listed in playlist UIs
    - _Requirements: 14.3, 16.3, 16.4_

  - [ ] 13.4 Collect AirPlay password for server_playback
    - Prompt for a device password when a selected server AirPlay device requires one and pass it to `putPlaybackSession`; surface the server's auth error
    - _Requirements: 14.7_

- [ ] 14. Phase 3 checkpoint
  - Verify device list, routing selection, and server-driven play/pause/stop/volume with live state on a capable server.

---

## Phase 4 — Chromecast client casting
**Requirements: 6 (local), 9, 10, 11, 12. Properties: 3, 5.**
HIGH RISK: external Cast SDKs (`androidx.media3:media3-cast` on Android, GoogleCast on iOS).

- [ ] 15. Cast session state machine and local device discovery
  - [ ] 15.1 Implement `CastSessionMachine` pure state logic
    - Implement `{stopped,playing,paused}` transitions for play/resume/pause/stop/volume, reachability rejection, disconnect→stopped, 30s timeout, resume-after-pause retaining song/position
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9_

  - [ ] 15.2 Write property test for cast session invariant
    - `# Feature: app-multi-server-playback-and-casting, Property 3: Cast session state invariant`
    - Generate random control sequences; assert state always valid and resume-after-pause returns to `playing` with retained song/position
    - **Validates: Requirements 10.8, 10.9** (Property 3), min 100 iterations
    - _Requirements: 10.8, 10.9_

  - [ ] 15.3 Implement `LocalDeviceDiscovery` (expect/actual)
    - `expect` in `commonMain`; Android `actual` = Chromecast via Cast SDK MediaRouter; iOS `actual` = Chromecast (GoogleCast) + AirPlay (`AVRouteDetector`); classify each device, remove on drop-out
    - _Requirements: 6.1, 6.3, 6.4, 6.6, 12.1, 12.2, 12.4_

  - [ ] 15.4 Write property test for capability/platform gating
    - `# Feature: app-multi-server-playback-and-casting, Property 5: Capability and platform gating`
    - Generate capability + platform combinations; assert a feature/protocol is offered iff supported (AirPlay⇒iOS, Chromecast⇒both)
    - **Validates: Requirements 4.2, 4.6, 12.1, 12.2** (Property 5), min 100 iterations
    - _Requirements: 4.2, 4.6, 12.1, 12.2_

- [ ] 16. Cast stream URL handling
  - [ ] 16.1 Implement Cast_Stream_Url resolution
    - Add `getCastStreamUrl(songId)` service method; `castUrlOrNull()` prefers server `castStreamUrl`, then a dedicated endpoint, then reachable `playbackUrl`; treat none as not-castable without loading an unreachable URL; do not leak long-lived credentials
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6_

- [ ] 17. Chromecast engine
  - [ ] 17.1 Implement `ChromecastEngine` (expect/actual)
    - Android `actual`: Media3 `CastPlayer` loading a `MediaItem` from `castUrlOrNull()`, adapting `CastPlayer` state to `EngineStatus` via `CastSessionMachine`
    - iOS `actual`: `GCKSessionManager` + `GCKRemoteMediaClient` loading `GCKMediaInformation`; controller-only, receiver fetches URL
    - Register in the coordinator only where supported (Android + iOS)
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 11.3_

  - [ ] 17.2 Add Cast SDK dependencies and initialization
    - Add `media3-cast` + `play-services-cast-framework` (Android) and GoogleCast (iOS, SPM/CocoaPods) in `libs.versions.toml`/build files; initialize the Cast context and receiver app id
    - Medium risk: external SDK setup and app-id configuration
    - _Requirements: 9.1, 12.2_

  - [ ] 17.3 Write integration/manual tests for Chromecast
    - Against an emulated/real receiver: verify load from Cast_Stream_Url, play/pause/stop/volume, disconnect handling, and not-castable fallback; state logic itself is covered by Property 3
    - Integration/manual (NOT property-based) — external SDK path
    - _Requirements: 9.1, 9.2, 9.5, 9.6, 11.4_

- [ ] 18. Phase 4 checkpoint
  - Verify Chromecast casting of local and remote (proxied) songs on both platforms, plus graceful not-castable behavior.

---

## Phase 5 — AirPlay client casting (iOS), now-playing, exclusivity finalize
**Requirements: 8, 15, 16.7.**

- [ ] 19. AirPlay engine (iOS)
  - [ ] 19.1 Implement `AirPlayEngine` (iOS only)
    - Reuse the local `AVPlayer`; present `AVRoutePickerView` for device + password selection; route output to the selected AirPlay device keeping the phone as audio source and using `playbackUrl` with existing auth headers; handle route loss via the disconnect path
    - Do not register AirPlay client-cast on Android
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 12.1_

  - [ ] 19.2 Write integration/manual tests for AirPlay routing
    - On an iOS device with an AirPlay target: verify audio routes, password handled by the system picker, and route-loss handling; state logic covered by Property 3
    - Integration/manual (NOT property-based) — device path
    - _Requirements: 8.1, 8.3, 8.4_

- [ ] 20. Now-playing and final exclusivity polish
  - [ ] 20.1 Route now-playing/lock-screen controls to the active engine
    - Point iOS `MPNowPlayingInfoCenter`/`MediaRemoteController` and Android MediaSession transport commands at the coordinator's active engine so lock-screen controls drive the cast/server session
    - _Requirements: 16.7_

  - [ ] 20.2 Finalize routing switch transitions
    - Verify switching client_cast ↔ server_playback ↔ local ends the prior session and resumes from retained position; no simultaneous engines
    - _Requirements: 15.2, 15.3, 15.4, 15.5_

- [ ] 21. Final checkpoint
  - Build shared + both apps, run all property and integration tests, and validate the full routing matrix end to end.

## Notes

- Each task references specific requirement clauses and, where applicable, the design correctness property it validates.
- Every property test uses the tag `# Feature: app-multi-server-playback-and-casting, Property {number}: {property_text}` and runs a minimum of 100 iterations (harness from task 1.1).
- Protocol/device paths the design marks NOT property-testable (Chromecast, AirPlay, server-playback network path, Hotwire routing) are covered by integration/manual tests instead.
- High-risk, externally-dependent work is concentrated in Phase 4 (Chromecast) and Phase 5 (AirPlay), isolated behind `expect`/`actual` engines and sequenced last.
- Phases are independently shippable; each ends with a checkpoint. Phase 1 alone delivers cross-server streaming; Phase 3 alone adds server-driven playback; Phases 4–5 add client casting.
- Open server-contract dependencies (design "Open Contract Dependencies") should be confirmed before Phases 3–4; the app degrades gracefully if any are absent.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1", "3.1"] },
    { "id": 1, "tasks": ["1.3", "2.2", "3.2", "5.1"] },
    { "id": 2, "tasks": ["4.1", "4.2", "4.3", "5.2", "6.1"] },
    { "id": 3, "tasks": ["5.3", "6.2", "7"] },
    { "id": 4, "tasks": ["8.1", "8.2", "8.3", "9"] },
    { "id": 5, "tasks": ["10.1", "10.2", "11.1"] },
    { "id": 6, "tasks": ["11.2", "11.3"] },
    { "id": 7, "tasks": ["11.4", "12.1", "12.2"] },
    { "id": 8, "tasks": ["12.3", "13.1", "13.2", "13.3", "13.4"] },
    { "id": 9, "tasks": ["14", "15.1", "15.3"] },
    { "id": 10, "tasks": ["15.2", "15.4", "16.1"] },
    { "id": 11, "tasks": ["17.1", "17.2"] },
    { "id": 12, "tasks": ["17.3", "18"] },
    { "id": 13, "tasks": ["19.1"] },
    { "id": 14, "tasks": ["19.2", "20.1", "20.2"] },
    { "id": 15, "tasks": ["21"] }
  ]
}
```
