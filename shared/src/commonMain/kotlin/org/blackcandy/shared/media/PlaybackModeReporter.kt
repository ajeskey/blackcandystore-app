package org.blackcandy.shared.media

/**
 * Extension seam for reporting the app's selected [PlaybackRouting] back to the Server for
 * parity (spec R7.3). The design refers to this collaborator as the `PlaybackModeRepository`.
 *
 * The [PlaybackCoordinator] calls [reportRouting] whenever the active routing changes. Per
 * R7.3 the report is best-effort: the coordinator swallows any failure and never blocks the
 * routing switch, casting, or remote control on it.
 *
 * A concrete implementation (backed by `BlackCandyService.setPlaybackMode`) is wired in a later
 * task (12.2). Until then the coordinator accepts a `null` reporter and simply skips the report.
 */
interface PlaybackModeReporter {
    /** Best-effort report of the newly selected routing to the Server (R7.3). */
    suspend fun reportRouting(routing: PlaybackRouting)
}
