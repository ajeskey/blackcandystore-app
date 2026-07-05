# Design Document: App Multi-Server Playback and Casting

## Overview

This design adds multi-server streaming, client casting (AirPlay/Chromecast), server-driven playback remote control, and the supporting UI to the Black Candy Store mobile app, consuming the server's `multi-server-library-sharing` and `remote-library-mirror-sync` contracts. It does not implement any server behavior.

The guiding principle mirrors the server spec's "isolate the risky wire protocols behind a boundary" approach: **all pure decision logic lives in `commonMain` (KMP shared) and is unit/property-tested, while the platform-specific protocol work (Cast SDK, AirPlay routing, ExoPlayer/AVPlayer) is isolated behind `expect`/`actual` engine implementations covered by integration/manual tests.**

The existing player (`MusicServiceController`) is preserved as the **local playback engine**. A new `commonMain` **`PlaybackCoordinator`** sits above it and routes transport operations to exactly one active engine — local, client-cast, or server-playback — based on the selected `PlaybackRouting`. ViewModels talk to the coordinator instead of the controller directly.

### Requirements mapping (high level)

| Area | Requirements | Where |
|---|---|---|
| Resolved stream/asset paths, availability | 1, 2, 3, 17 | `commonMain` model + engines |
| Capability gating, backward compat | 4, 18 | `SystemInfo`, `ServerCapabilities` |
| Multi-library Turbo screens | 5 | `path-configuration.json`, bridges |
| Output device discovery (two namespaces) | 6, 12 | `LocalDeviceDiscovery` (expect/actual) + `ServerDeviceRepository` |
| Routing selection | 7, 15 | `PlaybackCoordinator` |
| AirPlay client cast (iOS) | 8, 10 | `AirPlayEngine` (iOS) |
| Chromecast client cast | 9, 10, 11 | `ChromecastEngine` (expect/actual) |
| Server-driven playback | 13, 14 | `ServerPlaybackEngine` + `PlaybackSessionRepository` |
| Player UI, now-playing | 16 | Compose (Android) / SwiftUI (iOS) + `PlayerViewModel` |

## Architecture

### Layering

```
┌────────────────────────────────────────────────────────────────┐
│ Native UI (Android Compose / iOS SwiftUI + Hotwire web screens)  │
│   PlayerScreen · DevicePicker · bridge components                │
└───────────────▲───────────────────────────────▲─────────────────┘
                │ observes StateFlow             │ intents
┌───────────────┴───────────────────────────────┴─────────────────┐
│ ViewModels (commonMain): PlayerViewModel, DevicePickerViewModel, │
│   MusicServiceViewModel                                          │
└───────────────▲──────────────────────────────────────────────────┘
                │
┌───────────────┴──────────────────────────────────────────────────┐
│ PlaybackCoordinator (commonMain)                                  │
│   - holds active PlaybackRouting + active PlaybackEngine          │
│   - unified PlaybackStatus StateFlow                              │
│   - enforces routing exclusivity (R15)                            │
└───┬───────────────────┬───────────────────────┬──────────────────┘
    │                   │                        │
┌───▼──────────┐  ┌─────▼─────────┐   ┌──────────▼──────────────────┐
│ LocalEngine  │  │ Cast engines  │   │ ServerPlaybackEngine        │
│ (wraps       │  │ Chromecast    │   │ (commonMain, API + polling) │
│ MusicService │  │  (expect/act) │   └──────────┬──────────────────┘
│ Controller)  │  │ AirPlay (iOS) │              │
└──────────────┘  └───────────────┘   ┌──────────▼──────────────────┐
                                       │ BlackCandyService (Ktor)    │
                                       │  + PlaybackSessionRepository │
                                       │  + ServerDeviceRepository    │
                                       └─────────────────────────────┘
```

### The `PlaybackEngine` abstraction

A single interface in `commonMain` unifies the three routings so the coordinator and UI are engine-agnostic:

```kotlin
interface PlaybackEngine {
    val status: StateFlow<EngineStatus>      // state, currentSong, position, target
    fun setQueue(songs: List<Song>, startIndex: Int)
    fun play()
    fun pause()
    fun stop()
    fun next()
    fun previous()
    fun seekTo(seconds: Double)
    fun setVolume(level: Double)             // normalized 0.0–1.0 (R10.4)
    suspend fun activate(target: PlaybackTarget?)   // attach device / session
    suspend fun deactivate(retainPosition: Boolean) // detach, return position
}

data class EngineStatus(
    val state: PlaybackState,                // reuse existing enum
    val currentSong: Song? = null,
    val position: Double = 0.0,
    val volume: Double = 1.0,
    val target: PlaybackTarget? = null,      // null = local device
    val error: PlaybackError? = null,
)
```

- **`LocalPlaybackEngine`** (`commonMain`) delegates to the existing `MusicServiceController`. This keeps today's behavior byte-for-byte for single-library servers (R18.1).
- **`ChromecastEngine`** is `expect class` with `actual` on each platform (Android: Media3 `CastPlayer`; iOS: `GCKRemoteMediaClient`). It loads a `Cast_Stream_Url` onto the receiver (R9, R11).
- **`AirPlayEngine`** exists only on iOS; it reuses the local `AVPlayer` and routes its output via the system route picker (R8). On Android it is not registered.
- **`ServerPlaybackEngine`** (`commonMain`) issues control operations to the server `Playback_Session` and mirrors its state via polling (R14).

### The `PlaybackCoordinator`

```kotlin
class PlaybackCoordinator(
    private val local: LocalPlaybackEngine,
    private val chromecast: ChromecastEngine?,   // null where unsupported
    private val airplay: AirPlayEngine?,          // iOS only
    private val serverPlayback: ServerPlaybackEngine,
    private val playbackModeRepository: PlaybackModeRepository,
) {
    val status: StateFlow<PlaybackStatus>         // adds routing + target on top of EngineStatus
    val routing: StateFlow<PlaybackRouting>

    suspend fun selectTarget(target: PlaybackTarget?)  // drives routing + engine switch (R7, R15)
    // transport ops forward to the active engine
}
```

Routing switch logic (R15): `selectTarget` deactivates the current engine (retaining position), activates the target's engine, transfers queue + position, and resumes where possible. Exactly one engine is ever active.

```kotlin
enum class PlaybackRouting { LOCAL, CLIENT_CAST, SERVER_PLAYBACK }

sealed interface PlaybackTarget {
    data object LocalDevice : PlaybackTarget
    data class LocalCastDevice(val device: OutputDevice) : PlaybackTarget   // client_cast
    data class ServerDevice(val devices: List<OutputDevice>) : PlaybackTarget // server_playback
}
```

## Components and Interfaces

### 1. Data model changes

**`Song` (commonMain)** — add resolved fields with backward-compatible defaults (R1, R11, R18.5):

```kotlin
@Serializable
enum class StreamSource { @SerialName("local") LOCAL, @SerialName("remote") REMOTE }

@Serializable
data class Song(
    val id: Long,
    val name: String,
    val duration: Double,
    val albumId: Long,
    val artistId: Long,
    val url: String,                                   // legacy, retained
    val albumName: String,
    val artistName: String,
    val format: String,
    val albumImageUrls: ImageURLs,
    var isFavorited: Boolean,
    val streamSource: StreamSource = StreamSource.LOCAL,   // R1.1, R4.3
    val resolvedStreamPath: String? = null,                 // R1.1
    val castStreamUrl: String? = null,                      // R11.2
    val resolvedAssetPath: String? = null,                  // R17.1
) {
    // R1.2/R1.3: prefer resolved path, fall back to legacy url
    val playbackUrl: String get() = resolvedStreamPath ?: url
    // R1.5: empty resolved path => unavailable; absent => use legacy url
    val isAvailable: Boolean get() =
        if (resolvedStreamPath != null) resolvedStreamPath.isNotEmpty() else url.isNotEmpty()
    // R11.3: fall back to playback url when server gives no dedicated cast url
    fun castUrlOrNull(): String? = castStreamUrl ?: playbackUrl.takeIf { isAvailable }
    val artworkUrl: String get() = resolvedAssetPath?.takeIf { it.isNotEmpty() } ?: albumImageUrls.large
}
```

`Json` is already configured with `ignoreUnknownKeys = true` and `SnakeCase`, so old payloads decode and new fields map automatically.

**New models (commonMain):**

```kotlin
@Serializable enum class OutputDeviceProtocol { @SerialName("airplay") AIRPLAY, @SerialName("chromecast") CHROMECAST }

@Serializable
data class OutputDevice(
    val id: String,
    val name: String,
    val protocol: OutputDeviceProtocol,
    val requiresPassword: Boolean = false,
    val origin: DeviceOrigin,      // LOCAL (app-discovered) vs SERVER (server-reported) — R6.7
)
enum class DeviceOrigin { LOCAL, SERVER }

@Serializable
data class PlaybackSession(
    val state: String,             // stopped|playing|paused
    val currentSongId: Long?,
    val position: Double,
    val activeDeviceIds: List<String>,
    val volume: Double = 1.0,
)
```

### 2. Server capability detection (R4)

Extend `SystemInfo` with an optional capabilities block and fall back to version thresholds:

```kotlin
@Serializable
data class SystemInfo(
    val version: Version,
    var serverAddress: String? = null,
    val minAppVersion: Version? = null,
    val capabilities: ServerCapabilities? = null,   // R4.1
) { /* isServerSupported, isAppSupported unchanged */ }

@Serializable
data class ServerCapabilities(
    val resolvedStreamPaths: Boolean = false,
    val outputDevices: Boolean = false,
    val serverPlayback: Boolean = false,
    val castStreamUrls: Boolean = false,
)
```

`SystemInfoRepository` exposes a resolved `ServerCapabilities` (either the reported block or one inferred from `version` against per-feature minimums). All feature gating (R4.2, R16.1, R18) reads from this. Client-cast capability is computed separately from platform + device availability (R4.6, R12), not from the server.

### 3. Service / API additions (`BlackCandyService`)

New suspend methods (assumed server contract; each is capability-gated and degrades silently per R18.2):

```kotlin
suspend fun getSong(songId: Long): ApiResponse<Song>                       // R3 refresh
suspend fun getOutputDevices(): ApiResponse<List<OutputDevice>>            // R6.2
suspend fun getCastStreamUrl(songId: Long): ApiResponse<String>           // R11.2 (optional endpoint)
suspend fun getPlaybackSession(): ApiResponse<PlaybackSession>            // R14.4
suspend fun putPlaybackSession(                                            // R13.3, R14.1
    deviceIds: List<String>, currentSongId: Long?, devicePassword: String?,
): ApiResponse<PlaybackSession>
suspend fun controlPlaybackSession(                                        // R14.2
    op: PlaybackOp, volume: Double?, deviceId: String?,
): ApiResponse<PlaybackSession>
suspend fun setPlaybackMode(routing: String): ApiResponse<Unit>           // R7.3
```

`PlaybackOp = play | resume | pause | stop`. Errors surface via the existing `ApiException`/`ApiError` → `TaskResult.Failure` path. New repositories: `ServerDeviceRepository`, `PlaybackSessionRepository`, `PlaybackModeRepository`, plus `SongRepository.getSong` for refresh.

### 4. Availability & queue handling (R2, R3)

Pure logic in `commonMain`, engine-independent and property-tested:

- `QueuePlanner.nextAvailableIndex(queue, from, direction, repeatMode)` returns the next index whose `Song.isAvailable` is true, honoring `NO_REPEAT`/`REPEAT`/`REPEAT_ONE`/`SHUFFLE` and returning `null` when none remain (R2.4, R2.5, R2.7).
- The coordinator uses `QueuePlanner` when advancing so all three engines share identical skip semantics.
- **Refresh flow (R3):** on an engine "load failed" callback for a `remote` song, `PlaybackCoordinator.refreshAndRetry(song)` calls `SongRepository.getSong(id)` **once** (guarded by a per-song attempt flag, R3.5), replaces the queue entry, and retries if the refreshed `resolvedStreamPath` is non-empty; otherwise marks unavailable and skips. A 30s no-first-byte watchdog (R3.4, R10.7) is implemented with a coroutine timeout per engine.

### 5. Output device discovery (R6, R12)

Two namespaces, never merged:

- **`ServerDeviceRepository`** wraps `getOutputDevices()` for `server_playback` targets (R6.2). Empty/failed → empty section with "no devices" state (R6.5), not an error.
- **`LocalDeviceDiscovery`** is `expect class`:
  - **Android `actual`**: Google Cast `SessionManager` / `MediaRouter` for Chromecast devices only (R12.2). No AirPlay.
  - **iOS `actual`**: Chromecast via GoogleCast SDK **and** AirPlay via `AVRouteDetector`/route picker (R12.1). Exposes both.

`DevicePickerViewModel` combines the two into a UI model with clearly separated sections and per-device `origin`/protocol (R6.1, R6.7). Devices dropping out are removed (R6.4).

### 6. Client casting engines

**Chromecast (`ChromecastEngine`, expect/actual) — R9, R10, R11:**
- Android `actual`: Media3 `androidx.media3:media3-cast` `CastPlayer` fed a `MediaItem` built from `Song.castUrlOrNull()`. `CastPlayer` already surfaces state; adapt to `EngineStatus`.
- iOS `actual`: `GCKSessionManager` + `GCKRemoteMediaClient`; load `GCKMediaInformation(contentURL = castUrl)`.
- Both are controllers only; the **receiver fetches the URL** (R9.4). If `castUrlOrNull()` is null, the song is "not castable" and handled per R10 (R9.6, R11.4).

**AirPlay (`AirPlayEngine`, iOS only) — R8:**
- Reuses the local `AVPlayer`; presents `AVRoutePickerView` for selection and password (R8.3), routes output to the chosen route. The phone stays the audio source and uses `Song.playbackUrl` with the app's own auth headers (R8.2). Route loss → R10.6 disconnect handling.

**Shared Cast_Session state machine (`CastSessionMachine`, commonMain) — R10:**
Pure state machine over `{stopped, playing, paused}` implementing play/resume/pause/stop/volume, reachability rejection (R10.5), disconnect→stopped (R10.6), 30s timeout (R10.7), and the resume-after-pause invariant (R10.9). Both cast engines drive their `EngineStatus` through this machine so behavior is identical and testable independent of the SDKs.

### 7. Server-driven playback (`ServerPlaybackEngine`) — R13, R14

- On `activate(ServerDevice(devices))`: sync the app queue to the server current playlist (reuses existing `CurrentPlaylistRepository`), then `putPlaybackSession(deviceIds, currentSongId, password?)` (R13.1–13.3, R14.1).
- Transport ops call `controlPlaybackSession(...)` and adopt the returned `PlaybackSession` as truth (R14.2).
- **`Session_Observation`**: a coroutine polls `getPlaybackSession()` every N seconds (default 5s, configurable) and immediately after each control op, mapping `PlaybackSession` → `EngineStatus` (R14.4). Device-lost / last-device-stop reflected from server state (R14.6).
- Queue edits while in this mode propagate to the server current playlist (R13.4).
- The local `AVPlayer`/ExoPlayer is paused/detached so no local audio is produced (R14.3, R7.6). Password for protected AirPlay collected in-app and passed to `putPlaybackSession` (R14.7).

### 8. UI

**Player UI (R16):**
- A **Device_Picker entry point** (cast icon) added to `PlayerScreen`/`PlayerActions` (Compose) and `FullPlayer`/`PlayerActions.swift` (SwiftUI), shown only when `ServerCapabilities.outputDevices` or platform client-cast is available (R16.1).
- The picker (native bottom sheet on Android via the existing `BottomSheetDialog` pattern; SwiftUI sheet on iOS) lists the two device sections and the local option, and shows the active routing + device name (R16.2, R16.3).
- Unavailable queue songs are visually disabled but listed (R16.4) in the playlist views.
- `PlayerViewModel` exposes routing/target/availability from `PlaybackCoordinator.status`; UI updates reactively within one state emission (R16.5). The repeat/shuffle control is untouched (R16.6).
- **Now-playing (R16.7):** the existing `MPNowPlayingInfoCenter` (iOS) and MediaSession (Android) integrations are pointed at the coordinator's active engine so lock-screen transport controls drive the cast/server session, not silent local playback.

**Multi-library screens (R5):** add `path-configuration.json` rules (both platforms) for the server's new library/sharing/settings paths, following existing `default`/`modal`/`replace_root` conventions; add bridge components only where the server emits bridge events. Active-library change triggers a current-playlist refresh (R5.3).

## Data Models

Summary of new/changed serializable types (all `commonMain`): `Song` (+4 fields), `StreamSource`, `OutputDevice`, `OutputDeviceProtocol`, `DeviceOrigin`, `PlaybackSession`, `ServerCapabilities`, `SystemInfo` (+capabilities). Internal (non-serialized) types: `PlaybackRouting`, `PlaybackTarget`, `EngineStatus`, `PlaybackStatus`, `PlaybackError`, `CastSessionState`.

## Error Handling

- API failures → `ApiException` → `TaskResult.Failure`; capability probes fail **silently** for gating (R18.2).
- Unavailable song on explicit selection → user-facing message via existing `AlertMessage`, no queue change (R2.6).
- Cast/route errors (not reachable, disconnect, timeout, bad password) → `PlaybackError` on `EngineStatus`, surfaced in the player UI; state forced to `stopped` per R10.
- Server-playback errors (no device, auth) → surfaced from the server response text (R14.5, R14.7).
- Stale remote link → single refresh-and-retry, then skip (R3).

## Correctness Properties

These are the invariants the pure `commonMain` logic must uphold. Each is validated by a property-based test running a minimum of 100 iterations (see Testing Strategy).

### Property 1: Resolution consistency
For every Song, `isAvailable` and `playbackUrl` are determined solely by the resolved fields: a non-empty `resolvedStreamPath` is used and available; an empty `resolvedStreamPath` is unavailable; an absent `resolvedStreamPath` falls back to the legacy `url`.
**Validates: Requirements 1.2, 1.3, 1.5**

### Property 2: Queue integrity under unavailability
For any queue and repeat mode, `QueuePlanner` preserves order and membership, advancing always lands on an available song or returns none, and no unavailable song is ever selected as the playing song — including `REPEAT_ONE` on an unavailable song, which yields none rather than looping.
**Validates: Requirements 2.3, 2.4, 2.5, 2.7**

### Property 3: Cast session state invariant
A `CastSessionMachine` is always in exactly one of `stopped`, `playing`, or `paused`, and a resume applied directly after a pause returns it to `playing` with the same current Song and position retained at pause.
**Validates: Requirements 10.8, 10.9**

### Property 4: Routing exclusivity
For any sequence of target selections, at most one `PlaybackEngine` is active at a time, and selecting a new target deactivates the previously active engine before activating the next.
**Validates: Requirements 7.4, 15.1, 15.5**

### Property 5: Capability and platform gating
A Server-dependent feature is offered if and only if the resolved `ServerCapabilities` reports it, and a client-cast protocol is offered if and only if the platform supports it (AirPlay⇒iOS only, Chromecast⇒both).
**Validates: Requirements 4.2, 4.6, 12.1, 12.2**

### Property 6: Bounded refresh
For any single play request on a song, the app performs at most one resolved-path refresh-and-retry before deciding availability.
**Validates: Requirements 3.5**

## Testing Strategy

Following the server spec's split: **pure logic is unit/property-tested (≥100 iterations for properties); protocol/device paths are integration/manual-only.**

Property-tested pure logic (`commonMain`, `androidHostTest`/common test):
- **P1 — Resolution consistency:** for generated songs, `isAvailable`/`playbackUrl`/`artworkUrl` follow R1.2/R1.3/R1.5 (resolved-nonempty→used; resolved-empty→unavailable; absent→legacy url).
- **P2 — Queue integrity:** `QueuePlanner` preserves order/membership, skips unavailable, stops when none remain, and handles `REPEAT_ONE`+unavailable (R2.3–2.5, R2.7).
- **P3 — Cast session invariant:** `CastSessionMachine` always in one state; resume-after-pause returns to `playing` with retained song/position (R10.8, R10.9).
- **P4 — Routing exclusivity:** for random target-selection sequences, exactly one engine is active and switching deactivates the previous (R7.4, R15).
- **P5 — Capability gating:** feature offered iff capability present (server) / platform supports (cast) (R4, R12).
- **P6 — Refresh cap:** at most one refresh per song per play request (R3.5).

Integration / manual (not property-testable — external components):
- Chromecast load/control against a real/emulated receiver (Android `media3-cast`, iOS GoogleCast).
- iOS AirPlay routing + password via route picker.
- Server-playback control + polling against a stubbed server (WebMock-style Ktor `MockEngine`).
- Hotwire routing of new library/sharing paths.

## Phased Delivery Plan

Each phase is independently shippable and leaves the app working on any server.

- **Phase 1 — Resolved paths, availability, capabilities, backward compat (R1, R2, R3, R4, R17, R18).** No external SDKs. Song model, capability detection, `QueuePlanner`, refresh flow, artwork fallback, and wiring both players to `playbackUrl`. Fully property-tested. Delivers cross-server *streaming* on its own.
- **Phase 2 — Multi-library Turbo routing (R5).** `path-configuration.json` + any bridges + active-library queue refresh. Small.
- **Phase 3 — Device model, server-driven playback, routing scaffolding (R6 server side, R7, R13, R14, R16 core).** API + `PlaybackCoordinator` + `ServerPlaybackEngine` + Device_Picker (server section) + player indicators. No cast SDK yet — delivers "stream from server to devices."
- **Phase 4 — Chromecast client casting (R6 local, R9, R10, R11, R12).** HIGH RISK external SDK (`media3-cast` / GoogleCast). `ChromecastEngine`, `LocalDeviceDiscovery`, cast URL handling.
- **Phase 5 — AirPlay client casting (iOS) + now-playing + exclusivity finalize (R8, R15, R16.7).** `AirPlayEngine`, lock-screen control routing, final routing-switch polish.

## Open Contract Dependencies

These must be confirmed with the server team; the app degrades gracefully if absent:
1. `SystemInfo.capabilities` (or agreed version thresholds) for R4.
2. `stream_source` / `resolved_stream_path` / `resolved_asset_path` on song payloads (R1, R17).
3. A `cast_stream_url` field or endpoint delivering an independently authenticated, receiver-reachable URL for proxied remote songs (R11) — **without this, Chromecast casting of remote songs is not possible** and Phase 4 falls back to local-source cast only.
4. Output-device list and playback-session control endpoints (R6, R13, R14).
5. Playback-mode setter for parity (R7.3).
