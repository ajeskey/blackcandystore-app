# Requirements Document

## Introduction

Black Candy Store App is the official mobile client (iOS and Android) for the Black Candy Store music server. It is built with **Kotlin Multiplatform (KMP)** for shared business logic and **Hotwire Native** for server-driven screens, plus a native audio player (`MusicServiceController`: ExoPlayer on Android, `AVPlayer` on iOS) and a set of Hotwire bridge components.

The Black Candy Store server has gained two large feature sets — **multi-server-library-sharing** and **remote-library-mirror-sync** — that let a user browse and stream music that lives across multiple libraries and multiple servers, cast audio directly from the client to network speakers, drive playback from the server to network speakers, and choose between those playback routings. This spec defines what the **app** must do to support those server capabilities.

Two architectural facts shape the whole spec:

1. **Most browsing UI is server-driven.** Library management, active-library selection, invite generation, invite redemption, access lists, source-preference settings, and DAAP/RSP toggles are rendered by the server as Turbo/Hotwire web screens. The app inherits them automatically; its obligations are limited to routing (`path-configuration.json`), server-capability gating, and small bridge components where a native surface is required.
2. **The native app owns audio and device routing.** The `App_Player`, the streaming path a song plays from, client-side casting to AirPlay/Chromecast devices, and acting as a remote control for server-driven playback are genuine native concerns implemented in the KMP shared layer and the two platform players.

This spec does **not** implement any server behavior. It consumes the server's existing and new HTTP/JSON contract (the `stream_source` / `resolved_stream_path` song fields, the output-device list, the playback-session control endpoints, and the playback-mode / source-preference settings) and the server's Turbo-rendered screens. Where the server contract is not yet available, the app must degrade gracefully and keep behaving as it does today.

### Casting reality that shapes these requirements

Client-side casting is not one uniform mechanism, and the two supported protocols behave differently:

- **AirPlay** (iOS only, natively): the operating system routes the decoded output of the local `AVPlayer` to the selected AirPlay device. The phone remains the audio source; iOS handles device selection and any device password through the system route picker. Android has **no supported native AirPlay sender**, so client-cast to AirPlay is an iOS-only capability in this spec.
- **Chromecast** (iOS and Android, via the Google Cast SDK): the app hands the Cast receiver a **media URL that the receiver itself fetches and decodes**. The phone is a controller, not the audio source. Because the receiver fetches the URL directly and carries none of the app's session credentials, the URL it is given must be independently authenticated and reachable by the receiver on the network.
- **server_playback**: the Server is the audio source and performs all AirPlay/Chromecast protocol work against devices on the Server's network. The app is only a remote control and never touches the cast protocols in this mode.

These realities are reflected explicitly in Requirements 8–12.

## Glossary

Terms reused from the server specs (unchanged meaning), scoped to the app's view of them:

- **Server**: A running Black Candy Store instance the app connects to, identified by its base URL (the app's configured `serverAddress`).
- **App_Player**: The native audio player in the app (`MusicServiceController`), backed by ExoPlayer on Android and `AVPlayer` on iOS, that fetches and plays a Song's audio content.
- **Song**: The app's `Song` model returned by the server, extended by this spec with a stream source and a resolved stream path.
- **Stream_Source**: The classification of where a Song's audio is served from, either `local` (the current Server hosts it) or `remote` (it belongs to a library hosted on another Server and is proxied by the current Server).
- **Resolved_Stream_Path**: The absolute URL the App_Player requests a Song's audio from. For both `local` and `remote` sources this is a URL on the connected Server (remote sources are proxied same-origin by the Server), so the App_Player never contacts a foreign Server directly.
- **Resolved_Asset_Path**: The absolute URL the app uses to fetch a Displayable_Asset (cover art, artist image) for a Song, Album, or Artist.
- **Library / Active_Library**: A named collection of music; browsing is scoped to the user's Active_Library on the Server.
- **Output_Device**: A network audio endpoint (an **AirPlay_Device** or a **Chromecast_Device**), each carrying a protocol classification and whether it requires a password.
- **Playback_Routing**: The app-level term for the server spec's `Playback_Mode`, taking the value `client_cast` (the app or a Cast receiver plays to an Output_Device) or `server_playback` (the Server is the audio source and the app acts only as a Remote_Control). Named distinctly to avoid collision with the app's existing repeat/shuffle `PlaybackMode` enum.
- **Cast_Session**: A client-side session created by the App_Player under `client_cast` routing, holding the target Output_Device, the current Song, and a cast state of `stopped`, `playing`, or `paused`.
- **Playback_Session**: A Server-side session under `server_playback` routing that the app observes and controls as a Remote_Control; the app holds a view of its active Output_Devices, current Song, position, and state (`stopped`, `playing`, `paused`).
- **Remote_Control**: The role the App_Player plays under `server_playback` routing, issuing operations to a Playback_Session through the Server API rather than producing audio itself.
- **Source_Preference**: A per-user Server setting (`prefer_own_server` or `prefer_highest_quality`) the Server applies when resolving a Song's path; the app reads its effect through the resolved fields and MAY surface a control to change it.
- **Server_Capabilities**: The set of features the connected Server supports, determined by the app from the Server's version and/or a capabilities response, used to show or hide app features that depend on new Server endpoints.

Terms introduced by this spec:

- **Local_Output_Device**: An Output_Device the **app itself** discovers on the phone's network — a Chromecast device via the Google Cast SDK, or an AirPlay device via the iOS system route picker — and uses as a target for `client_cast`.
- **Server_Output_Device**: An Output_Device the connected **Server** discovered on the Server's network and exposes via API, used as a target for `server_playback`. The Local_Output_Device set and the Server_Output_Device set are distinct namespaces and MAY differ.
- **Cast_Stream_Url**: An authenticated, device-reachable variant of a Resolved_Stream_Path that a Chromecast receiver can fetch without the app's session credentials (for example a Server-provided signed or token-bearing URL). Distinct from the Resolved_Stream_Path the App_Player uses with its own credentials.
- **Device_Picker**: The app surface that lists available Output_Devices and the Playback_Routings and lets the user choose where and how audio plays.
- **Availability**: A per-Song flag derived from the resolved fields indicating whether the Song can currently be played (`false` when the Resolved_Stream_Path is empty).
- **Session_Observation**: The mechanism by which the app keeps its view of a Playback_Session current — polling the Server session endpoint at a defined interval and on user action, in the absence of a Server push channel.

## Requirements

### Requirement 1: Consume resolved stream source and path for songs

**User Story:** As a user, I want each song to play from the location the server resolved for it, so that songs from my own library and from shared remote libraries both play correctly without the app needing library-specific logic.

#### Acceptance Criteria

1. WHEN the app decodes a Song returned by the Server, THE app SHALL read the Song's `stream_source` and `resolved_stream_path` fields when present and expose them on the Song model.
2. WHEN the App_Player builds a playable media item for a Song, THE App_Player SHALL use the Song's Resolved_Stream_Path as the audio URL.
3. WHERE a Song returned by the Server omits `resolved_stream_path` but includes the legacy `url` field, THE app SHALL fall back to the legacy `url` as the Resolved_Stream_Path so that older Servers continue to work.
4. WHERE a Song has a Stream_Source of `remote`, THE App_Player SHALL request its audio from the Resolved_Stream_Path in exactly the same manner as a `local` Song, without contacting any Server other than the connected Server.
5. WHEN the Server returns a Song whose `resolved_stream_path` is empty, THE app SHALL mark that Song's Availability as `false`.
6. IF the App_Player is asked to play a Song whose Availability is `false`, THEN THE App_Player SHALL NOT attempt to fetch audio for that Song and SHALL surface to the user that the Song is currently unavailable.
7. THE app SHALL preserve every other attribute of a Song (name, album, artist, duration, artwork, favorite state) unchanged when Availability is `false`.

### Requirement 2: Handle unavailable and mixed-source songs in the playing queue

**User Story:** As a user, I want a queue that mixes my own songs and shared remote songs to keep playing smoothly, so that one unavailable song does not break the whole queue.

#### Acceptance Criteria

1. THE App_Player SHALL allow the playing queue to contain Songs with a Stream_Source of `local` and Songs with a Stream_Source of `remote` at the same time.
2. WHEN the App_Player resolves a media item for each Song in the queue, THE App_Player SHALL resolve each Song's audio URL independently from that Song's Resolved_Stream_Path.
3. WHILE one or more Songs in the queue have Availability `false`, THE App_Player SHALL preserve the queue's order and membership so each unavailable Song remains listed in its position.
4. WHEN the App_Player advances to the next Song during continuous playback AND the next Song's Availability is `false`, THE App_Player SHALL skip that Song and continue to the next available Song, surfacing that the skipped Song was unavailable.
5. IF every remaining Song in the queue has Availability `false`, THEN THE App_Player SHALL stop playback and indicate that no playable Song remains.
6. WHEN the user explicitly selects an unavailable Song to play, THE App_Player SHALL indicate that the Song is unavailable and SHALL NOT change the currently playing Song.
7. IF the repeat/shuffle `PlaybackMode` is `REPEAT_ONE` AND the single current Song has Availability `false`, THEN THE App_Player SHALL stop playback and indicate the Song is unavailable rather than repeatedly retrying the same unavailable Song.

### Requirement 3: Refresh resolved paths that expire or become stale

**User Story:** As a user, I want the app to recover when a remote song's link goes stale, so that playback resumes instead of failing permanently.

#### Acceptance Criteria

1. IF the App_Player reports a playback load failure for a Song whose Stream_Source is `remote`, THEN THE app SHALL re-fetch that Song's current resolved fields from the Server once before deciding the Song is unavailable.
2. WHEN a re-fetch returns a non-empty Resolved_Stream_Path for the Song, THE App_Player SHALL retry playback using the refreshed Resolved_Stream_Path.
3. WHEN a re-fetch returns an empty Resolved_Stream_Path for the Song, THE app SHALL mark the Song's Availability as `false` and apply the Requirement 2 skip behavior.
4. IF the App_Player does not begin receiving audio content from a Song's Resolved_Stream_Path within 30 seconds, THEN THE App_Player SHALL stop the request and indicate to the user that the Song is currently unavailable.
5. THE app SHALL limit re-fetch-and-retry to at most one attempt per Song per play request so that a persistently failing Song cannot loop.

### Requirement 4: Detect server capabilities and gate features

**User Story:** As a user connecting to any Black Candy server, I want the app to only offer features my server actually supports, so that I never hit dead ends against an older server.

#### Acceptance Criteria

1. WHEN the app retrieves system information from the Server, THE app SHALL determine the Server_Capabilities relevant to this spec from an explicit capabilities signal — a capabilities field in the system-information response when present, otherwise inferred from the Server version compared against a per-feature minimum version.
2. WHERE the connected Server does not report support for output-device discovery or server-driven playback, THE app SHALL hide the corresponding controls in the Device_Picker.
3. WHERE the connected Server does not report resolved stream fields, THE app SHALL treat every Song as `local` with Availability `true` and continue to play from the legacy `url`.
4. WHEN the connected Server is below the minimum version the app requires for a given feature, THE app SHALL degrade to its pre-feature behavior for that feature without error.
5. THE app SHALL determine Server_Capabilities without preventing login, browsing, or local playback when a capability is absent.
6. THE app SHALL treat client-cast capability as a property of the app platform and device (per Requirement 12), independent of Server_Capabilities, since client casting does not require Server support beyond providing a Cast_Stream_Url.

### Requirement 5: Route to multi-library browsing screens

**User Story:** As a user, I want to browse and switch between the libraries I can access, so that I can reach shared libraries as easily as my own.

#### Acceptance Criteria

1. WHERE the Server renders library-management, active-library-selection, invite-generation, invite-redemption, access-list, and source-preference screens as Turbo screens, THE app SHALL present those screens through its existing Hotwire Native web navigation.
2. THE app SHALL provide `path-configuration.json` rules on both platforms that route the Server's new library and sharing paths to the correct native presentation (default, modal, or replace-root) consistent with existing routing conventions.
3. WHEN the user changes the Active_Library through a Server screen and the change affects the playing queue's resolved paths, THE app SHALL refresh the current playlist from the Server so the App_Player reflects the newly scoped content.
4. WHERE a Server screen needs a native surface (for example a bottom sheet or a native picker) through a Hotwire bridge event, THE app SHALL provide a bridge component that handles that event on both platforms.
5. IF a deep link or Turbo visit targets a library or sharing path the connected Server does not serve, THEN THE app SHALL present the Server's response (including any error page) without crashing.

### Requirement 6: Discover and present output devices from both namespaces

**User Story:** As a user, I want to see the AirPlay and Chromecast speakers available for playback, so that I can choose where my music plays, whether my phone or the server drives them.

#### Acceptance Criteria

1. WHEN the user opens the Device_Picker, THE app SHALL present two distinguishable sets of targets: Local_Output_Devices discovered by the app for `client_cast`, and Server_Output_Devices reported by the Server for `server_playback`.
2. WHEN the app requests Server_Output_Devices, THE app SHALL include, for each device, its name, its protocol classification of `airplay` or `chromecast`, and whether it requires a password.
3. WHEN the app discovers Local_Output_Devices, THE app SHALL enumerate Chromecast devices via the Google Cast SDK on both platforms and AirPlay devices via the iOS system route picker on iOS, and SHALL present each with its name and protocol.
4. WHEN a device in either namespace is no longer available, THE app SHALL remove that device from the Device_Picker.
5. WHERE the Server returns an empty set of Server_Output_Devices or indicates device discovery is unavailable, THE app SHALL present that section of the Device_Picker as empty with an indication that no devices were found rather than an error state.
6. THE app SHALL classify and display each Output_Device as exactly one of `airplay` or `chromecast`.
7. THE app SHALL keep the Local_Output_Device set and the Server_Output_Device set visually and functionally separate so the user understands which routing a chosen device implies.

### Requirement 7: Select a playback routing

**User Story:** As a user, I want to choose whether my device casts audio to a speaker or the server plays audio to a speaker, so that I can pick the routing that fits my situation.

#### Acceptance Criteria

1. THE app SHALL support exactly two Playback_Routings, `client_cast` and `server_playback`, plus the default of local App_Player playback on the device.
2. WHEN the user selects a Server_Output_Device, THE app SHALL enter `server_playback` routing; WHEN the user selects a Local_Output_Device, THE app SHALL enter `client_cast` routing.
3. WHERE the Server supports recording the selected Playback_Mode, THE app SHALL report the selected routing to the Server for parity, and IF that report fails or the Server rejects the value, THEN THE app SHALL still honor the user's local routing selection and SHALL NOT block casting or remote control on that report.
4. THE app SHALL classify each active playback activity as exactly one Playback_Routing at a time.
5. WHILE the active Playback_Routing is `client_cast`, THE app or the Cast receiver SHALL be the audio source and THE Server SHALL NOT be the audio source for that activity.
6. WHILE the active Playback_Routing is `server_playback`, THE Server SHALL be the audio source and THE App_Player SHALL act only as a Remote_Control and SHALL NOT produce audio for that activity.
7. WHEN the user has selected no Output_Device, THE app SHALL default to local App_Player playback on the device.

### Requirement 8: Client casting to AirPlay via system audio routing (iOS)

**User Story:** As an iOS user, I want to send my music to an AirPlay speaker from the app, so that a real speaker plays while my phone stays the source.

#### Acceptance Criteria

1. WHERE the platform is iOS AND the user selects an AirPlay Local_Output_Device under `client_cast` routing, THE app SHALL route the local `AVPlayer` audio output to that AirPlay device using the iOS system route picker and SHALL create a Cast_Session whose target is that device.
2. WHILE audio is routed to an AirPlay device on iOS, THE App_Player SHALL remain the audio source and SHALL obtain each Song's audio from its Resolved_Stream_Path exactly as in local playback, including `remote` Songs.
3. WHERE the selected AirPlay device is password-protected, THE app SHALL rely on the iOS system route picker to collect and validate the device password and SHALL NOT collect the AirPlay password itself.
4. IF iOS reports that the AirPlay route became unavailable while the Cast_Session state is `playing`, THEN THE app SHALL apply the Requirement 10 disconnect handling.
5. WHERE the platform is Android, THE app SHALL NOT offer AirPlay as a `client_cast` target (per Requirement 12).

### Requirement 9: Client casting to a Chromecast receiver (iOS and Android)

**User Story:** As a user on either platform, I want to cast my music to a Chromecast device, so that the speaker plays it directly.

#### Acceptance Criteria

1. WHEN the user selects a Chromecast Local_Output_Device under `client_cast` routing, THE app SHALL establish a Cast SDK session to that receiver and create a Cast_Session whose target is that device.
2. WHEN the Cast_Session begins casting a Song, THE app SHALL load the Song's Cast_Stream_Url onto the receiver via the Cast SDK so that the receiver fetches and decodes the audio itself, and SHALL control playback through the Cast SDK remote media client.
3. WHERE a Song being cast to a Chromecast receiver has a Stream_Source of `remote`, THE app SHALL obtain that Song's Cast_Stream_Url per Requirement 11 so the receiver can fetch the proxied audio without the app's session credentials.
4. WHILE audio plays on a Chromecast receiver, THE app SHALL be a controller only and SHALL NOT decode or send the audio bytes itself, and THE Server SHALL NOT be the audio source for that activity.
5. WHEN the Cast SDK reports the receiver's media state and position, THE app SHALL reflect that state as the Cast_Session state and displayed position.
6. IF a Cast_Stream_Url cannot be obtained for a Song being cast to a Chromecast receiver, THEN THE app SHALL treat that Song as unavailable for casting and apply the Requirement 10 handling.

### Requirement 10: Cast_Session state, controls, and failure handling

**User Story:** As a user, I want casting controls to behave predictably and recover from device problems, so that I stay in control regardless of the cast protocol.

#### Acceptance Criteria

1. WHEN the user issues play or resume for a Cast_Session, THE app SHALL start or resume playback on the target and set the Cast_Session state to `playing`.
2. WHEN the user issues pause for a Cast_Session whose state is `playing`, THE app SHALL pause the target, retain the current Song and position, and set the state to `paused`.
3. WHEN the user issues stop for a Cast_Session, THE app SHALL stop the target, clear the position, and set the state to `stopped`.
4. WHEN the user issues a volume change for a Cast_Session, THE app SHALL set the target device's volume within the supported range (a normalized 0.0–1.0 scale mapped to the target's range).
5. IF the target Output_Device is not reachable when casting begins, THEN THE app SHALL reject the cast with a not-reachable error and set the Cast_Session state to `stopped`.
6. IF the target Output_Device disconnects while the Cast_Session state is `playing`, THEN THE app SHALL set the state to `stopped` and indicate that casting stopped because the device disconnected.
7. IF the target does not begin producing audio within 30 seconds of a play request, THEN THE app SHALL stop the cast and indicate that the Song is currently unavailable.
8. THE app SHALL keep every Cast_Session in exactly one of the states `stopped`, `playing`, or `paused`.
9. FOR ALL Cast_Sessions, a resume applied after a pause with no intervening operation SHALL return the Cast_Session to `playing` with the same current Song and position retained at pause.

### Requirement 11: Provide an authenticated, device-reachable stream URL for cast receivers

**User Story:** As a user casting to a Chromecast, I want the speaker to be able to fetch my song even when it comes from a shared remote library, so that casting works for all my music, not just local files.

#### Acceptance Criteria

1. WHEN the app prepares to cast a Song to a Chromecast receiver, THE app SHALL obtain a Cast_Stream_Url that the receiver can fetch without presenting the app's bearer token or session cookie.
2. WHERE the Server provides a Cast_Stream_Url (for example a signed or token-bearing URL) in the Song payload or from a dedicated endpoint, THE app SHALL use that Server-provided URL as the media URL loaded onto the receiver.
3. WHERE the Server does not provide a distinct Cast_Stream_Url but the Resolved_Stream_Path is already independently reachable by a receiver, THE app SHALL use the Resolved_Stream_Path as the Cast_Stream_Url.
4. IF no Cast_Stream_Url is obtainable for a Song, THEN THE app SHALL treat that Song as not castable to a Chromecast receiver and SHALL indicate this to the user rather than loading an unreachable URL.
5. THE app SHALL NOT embed the user's long-lived credentials into a Cast_Stream_Url in a way that would expose them beyond what the Server issues for cast use.
6. THE app SHALL apply the Cast_Stream_Url requirements only to the Chromecast receiver-fetch path and SHALL NOT require a Cast_Stream_Url for AirPlay routing or local App_Player playback, which use the app's own credentials.

### Requirement 12: Platform support constraints for casting

**User Story:** As a user, I want the app to offer only the casting options my platform can actually perform, so that I never see a control that cannot work.

#### Acceptance Criteria

1. THE app SHALL offer AirPlay `client_cast` only on iOS.
2. THE app SHALL offer Chromecast `client_cast` on both iOS and Android.
3. THE app SHALL offer `server_playback` to any Server_Output_Device (AirPlay or Chromecast) on both platforms, since the Server performs the cast-protocol work.
4. WHERE the platform cannot perform a given `client_cast` protocol, THE app SHALL omit that protocol's Local_Output_Devices from the Device_Picker rather than presenting an option that fails.
5. THE app SHALL document the support matrix so that server_playback remains the universal path to any device the Server can reach.

### Requirement 13: Provide the song and queue to server-driven playback

**User Story:** As a user, I want the server to play the music I chose when I hand off to server-driven playback, so that the right songs play on the speakers.

#### Acceptance Criteria

1. WHEN the user enters `server_playback` routing, THE app SHALL ensure the Server's current playlist reflects the app's current queue before or as part of starting the Playback_Session, using the existing current-playlist synchronization.
2. WHEN the user starts server_playback from a specific Song, THE app SHALL instruct the Server to set that Song as the Playback_Session's current Song.
3. WHEN the app requests the Server to create or update a Playback_Session, THE app SHALL include the selected Server_Output_Devices and the intended current Song reference.
4. WHERE the app changes the queue (add, remove, reorder) while in `server_playback` routing, THE app SHALL propagate the change to the Server's current playlist so the Playback_Session plays the updated queue.
5. IF the Server cannot determine what to play because no current Song or queue is available, THEN THE app SHALL surface the Server's error and remain in a stopped state without crashing.

### Requirement 14: Remote control of server-driven playback

**User Story:** As a user, I want to control server-driven playback like a remote and see its real state, so that I get multi-room audio driven by the server.

#### Acceptance Criteria

1. WHEN the user selects one or more Server_Output_Devices as active targets under `server_playback`, THE app SHALL request the Server to create or update a Playback_Session whose active Output_Devices equal the selection.
2. WHEN the user issues play, resume, pause, stop, or a volume change as a Remote_Control, THE app SHALL request the Server to apply that operation to the Playback_Session and SHALL reflect the resulting Server state in the app UI.
3. WHILE a Playback_Session is active, THE App_Player SHALL NOT produce local audio for that activity and SHALL present transport controls that operate on the Server session.
4. THE app SHALL keep its view of the Playback_Session current through Session_Observation — polling the Server session endpoint at a defined interval and immediately after issuing a control operation — and SHALL update its displayed state, current Song, position, and active devices to match the Server session.
5. IF the app requests play or resume for a Playback_Session with no active Output_Device, THEN THE app SHALL surface the Server's error that no device is selected and leave the displayed state unchanged.
6. IF the Server reports that an active Output_Device became unavailable, THEN THE app SHALL update the Device_Picker and transport state to reflect the Server's new session state, including a stop when the last device is lost.
7. WHERE a selected AirPlay Server_Output_Device requires a password, THE app SHALL collect the device password and include it when requesting the Server to play to that device, and SHALL surface the Server's authentication error when the password is missing or incorrect.

### Requirement 15: Keep client-cast and server-playback mutually exclusive in the app

**User Story:** As a user, I want the app to make it unambiguous whether my phone or the server is playing, so that the controls behave predictably.

#### Acceptance Criteria

1. THE app SHALL treat `client_cast` and `server_playback` as mutually exclusive for a single playback activity.
2. WHEN the user switches from `client_cast` to `server_playback`, THE app SHALL end any active Cast_Session before establishing or resuming a Playback_Session.
3. WHEN the user switches from `server_playback` to `client_cast`, THE app SHALL stop issuing Remote_Control operations to the Playback_Session before beginning a Cast_Session.
4. WHEN the user switches away from any external routing back to local device playback, THE app SHALL end the active Cast_Session or stop remote-controlling the Playback_Session and resume audio on the local App_Player from the retained position where possible.
5. WHILE a Cast_Session is active, THE app SHALL NOT simultaneously drive a Playback_Session for the same activity, and WHILE remote-controlling a Playback_Session, THE app SHALL NOT simultaneously run a Cast_Session for the same activity.

### Requirement 16: Present routing, availability, and now-playing controls in the player UI

**User Story:** As a user, I want the player and system now-playing controls to clearly show where my music is playing and whether each track is available, so that I always understand what is happening.

#### Acceptance Criteria

1. THE app SHALL present a Device_Picker entry point in the player UI that opens device and routing selection, shown only where Server_Capabilities include output devices or the platform supports client casting.
2. WHILE audio is routed to an Output_Device under `client_cast` or `server_playback`, THE player UI SHALL indicate the active routing and the target device name.
3. WHILE playback is on the local App_Player, THE player UI SHALL indicate local playback and SHALL provide the existing transport controls unchanged.
4. WHERE a Song in the visible queue has Availability `false`, THE app SHALL visually mark that Song as unavailable and SHALL disable direct selection of it for playback while keeping it listed.
5. WHEN the active routing or target device changes, THE player UI SHALL update its indication within one state update without requiring the user to reopen the player.
6. THE player UI SHALL keep the existing repeat/shuffle `PlaybackMode` control functioning independently of the Playback_Routing selection.
7. WHILE audio is routed under `client_cast` or `server_playback`, THE app SHALL keep the platform now-playing controls (iOS `MPNowPlayingInfoCenter` / lock screen and Android MediaSession / notification) reflecting the active session and SHALL route their transport commands to the active Cast_Session or Playback_Session rather than to silent local playback.

### Requirement 17: Fetch and display resolved artwork

**User Story:** As a user, I want album and artist artwork to load for shared remote content just like my own, so that shared libraries look the same as mine.

#### Acceptance Criteria

1. WHEN the app displays artwork for a Song, Album, or Artist, THE app SHALL use the Resolved_Asset_Path provided by the Server when present.
2. WHERE the Server omits a Resolved_Asset_Path but provides the legacy artwork URLs, THE app SHALL fall back to the legacy artwork URLs.
3. WHERE a Resolved_Asset_Path is empty because artwork is absent or a remote asset is unavailable, THE app SHALL display the app's placeholder artwork and SHALL NOT show a broken image.
4. THE app SHALL request a Resolved_Asset_Path only from the connected Server, relying on the Server to proxy remote assets.

### Requirement 18: Preserve existing behavior and graceful degradation

**User Story:** As an existing user on a single-library server, I want the app to keep working exactly as before, so that new multi-server features never regress my experience.

#### Acceptance Criteria

1. WHERE the connected Server exposes only a single default library and no sharing, THE app SHALL behave exactly as it did before this spec, playing from the legacy `url` and hiding multi-server controls.
2. WHEN the app cannot reach a Server capability endpoint (devices, playback session, capabilities), THE app SHALL fail that request silently for feature-gating purposes and continue offering local playback and browsing.
3. THE app SHALL not require any new Server feature to be present in order to authenticate, browse the Active_Library, or play a `local` Song.
4. WHEN a new Server feature is unavailable mid-session (for example the Server stops reporting devices), THE app SHALL revert the affected control to its degraded state without interrupting current local playback.
5. THE app SHALL maintain backward compatibility of the shared `Song` model so that older cached payloads without the new fields still decode.
