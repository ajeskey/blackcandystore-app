package org.blackcandy.shared.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.blackcandy.shared.models.Song

/**
 * Android Chromecast [PlaybackEngine] backed by Media3 `CastPlayer` (spec R9, R10, R11).
 *
 * `CastPlayer` follows the current Cast session managed by the [CastContext] (initialized at app
 * startup) and controls the receiver's `GCKRemoteMediaClient` under the hood, so the app is a
 * **controller only** — the receiver fetches and decodes the media URL itself (R9.4). Each queued
 * Song is turned into a `MediaItem` whose URI is the Song's Cast_Stream_Url (R9.2, R9.3, R11.3);
 * a Song with no obtainable cast URL is treated as not castable (R9.6, R11.4).
 *
 * All observable [EngineStatus] flows through the pure [CastSessionMachine] (R9.5, R10.8), so the
 * engine's externally-visible behavior matches the property-tested state machine (Property 3). The
 * `CastPlayer` is the device I/O; a [Player.Listener] adapts its callbacks (playing/paused, session
 * end) into machine transitions.
 *
 * ## Threading
 * `CastPlayer` must be created and driven on the app's main looper. The engine posts every
 * `CastPlayer` interaction through a main-looper [Handler] (dependency-free — it does not require
 * `Dispatchers.Main`/`kotlinx-coroutines-android`), while URL resolution runs on [scope].
 *
 * @param context application context used to obtain the shared [CastContext].
 * @param castStreamUrlResolver resolves each Song's receiver-fetchable Cast_Stream_Url (R11).
 * @param scope coroutine scope for cast-URL resolution; owned by the DI graph. Matches the other engines.
 */
@androidx.annotation.OptIn(UnstableApi::class)
actual class ChromecastEngine(
    private val context: Context,
    private val castStreamUrlResolver: CastStreamUrlResolver,
    private val scope: CoroutineScope,
) : PlaybackEngine {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pure cast state; the single source of truth for [status] (R9.5, R10.8). */
    private var machine = CastSessionMachine()

    private var target: PlaybackTarget? = null

    /** The queue set through the coordinator; used to resolve current song + cast URLs. */
    private var queue: List<Song> = emptyList()
    private var currentIndex: Int = 0

    private val statusState = MutableStateFlow(machine.toEngineStatus(target))
    actual override val status: StateFlow<EngineStatus> = statusState.asStateFlow()

    /**
     * The Media3 `CastPlayer`, created lazily on the main looper. Null when the Cast framework is
     * unavailable (e.g. Google Play services missing) — in that case casting simply does nothing
     * and the machine stays stopped rather than crashing.
     */
    private var castPlayer: CastPlayer? = null

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Reflect the receiver's real media state as the Cast_Session state (R9.5).
                update {
                    if (isPlaying) machine.resume() else machine.pause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // A session that ends / goes idle on the receiver stops the cast (R10.3/R10.6).
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    update { machine.stop() }
                }
            }
        }

    // ---- Queue ---------------------------------------------------------------------------------

    actual override fun setQueue(
        songs: List<Song>,
        startIndex: Int,
    ) {
        queue = songs
        currentIndex = startIndex.coerceIn(0, maxOf(0, songs.lastIndex))

        // Resolve each Song's receiver-fetchable Cast_Stream_Url off the main thread (R9.2, R9.3,
        // R11), then hand the resulting MediaItems to CastPlayer on the main looper.
        scope.launch {
            val items = songs.map { song -> song to castStreamUrlResolver.resolve(song) }
            runOnMain {
                val mediaItems =
                    items.mapNotNull { (song, url) -> url?.let { toCastMediaItem(song, it) } }
                val player = ensurePlayer() ?: return@runOnMain
                player.setMediaItems(mediaItems, currentIndex.coerceAtMost(maxOf(0, mediaItems.lastIndex)), 0L)
                player.prepare()
            }
        }
    }

    // ---- Transport (controller-only; forwards to CastPlayer + drives the machine, R9.4) --------

    actual override fun play() {
        val song = queue.getOrNull(currentIndex)
        if (song == null) return

        // Not castable: no Cast_Stream_Url obtainable ⇒ treat the Song as unavailable (R9.6, R11.4).
        val castUrl = song.castUrlOrNull()
        if (castUrl == null) {
            update {
                machine.copy(
                    state = CastSessionState.STOPPED,
                    currentSong = song,
                    position = 0.0,
                    error = PlaybackError.SongUnavailable,
                )
            }
            return
        }

        // Resume from pause returns to playing with retained song/position (R10.9); otherwise a
        // fresh play (R10.1). Reachability is reflected by whether a CastPlayer/session exists.
        val reachable = ensurePlayer() != null
        update {
            if (machine.state == CastSessionState.PAUSED) {
                machine.resume()
            } else {
                machine.play(song, machine.position, targetReachable = reachable)
            }
        }
        runOnMain { castPlayer?.play() }
    }

    actual override fun pause() {
        update { machine.pause() }
        runOnMain { castPlayer?.pause() }
    }

    actual override fun stop() {
        update { machine.stop() }
        runOnMain { castPlayer?.stop() }
    }

    actual override fun next() {
        runOnMain {
            castPlayer?.let { player ->
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.play()
                }
            }
        }
        val nextIndex = (currentIndex + 1).coerceAtMost(maxOf(0, queue.lastIndex))
        currentIndex = nextIndex
        queue.getOrNull(nextIndex)?.let { song -> update { machine.play(song, 0.0) } }
    }

    actual override fun previous() {
        runOnMain {
            castPlayer?.let { player ->
                if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                    player.play()
                }
            }
        }
        val prevIndex = (currentIndex - 1).coerceAtLeast(0)
        currentIndex = prevIndex
        queue.getOrNull(prevIndex)?.let { song -> update { machine.play(song, 0.0) } }
    }

    actual override fun seekTo(seconds: Double) {
        update { machine.copy(position = seconds) }
        runOnMain { castPlayer?.seekTo((seconds * 1000).toLong()) }
    }

    actual override fun setVolume(level: Double) {
        val clamped = level.coerceIn(0.0, 1.0)
        update { machine.setVolume(clamped) }
        runOnMain { castPlayer?.volume = clamped.toFloat() }
    }

    // ---- Activation ----------------------------------------------------------------------------

    actual override suspend fun activate(target: PlaybackTarget?) {
        // Only a Chromecast LocalCastDevice is handled by this engine; other targets are ignored.
        this.target = target as? PlaybackTarget.LocalCastDevice ?: target
        runOnMain { ensurePlayer() }
        pushStatus()
    }

    actual override suspend fun deactivate(retainPosition: Boolean) {
        // Detach: stop controlling the receiver. The last position stays on [status] for the
        // coordinator to resume elsewhere (R15.4). A non-retaining detach clears the position.
        runOnMain {
            castPlayer?.let { player ->
                player.pause()
                if (!retainPosition) player.stop()
            }
        }
        if (!retainPosition) {
            update { machine.stop() }
        } else {
            update { machine.pause() }
        }
        target = null
    }

    // ---- Internals -----------------------------------------------------------------------------

    /**
     * Create the [CastPlayer] on the main looper if needed. Returns null when the Cast framework
     * cannot be obtained (Google Play services absent/outdated), so casting degrades to a no-op
     * (a subsequent play surfaces not-reachable via the machine) rather than crashing.
     */
    private fun ensurePlayer(): CastPlayer? {
        castPlayer?.let { return it }
        val castContext =
            try {
                CastContext.getSharedInstance(context)
            } catch (e: Exception) {
                null
            } ?: return null

        val player = CastPlayer(castContext)
        player.addListener(playerListener)
        castPlayer = player
        return player
    }

    /** Apply a machine transition and republish the derived [EngineStatus]. */
    private inline fun update(transition: () -> CastSessionMachine) {
        machine = transition()
        pushStatus()
    }

    private fun pushStatus() {
        statusState.value = machine.toEngineStatus(target)
    }

    /** Run [block] on the main looper (immediately if already there). */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun toCastMediaItem(
        song: Song,
        castUrl: String,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(song.id.toString())
            .setUri(Uri.parse(castUrl))
            // The receiver needs a content type to fetch/decode; derive from the Song format.
            .setMimeType(mimeTypeFor(song.format))
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(song.name)
                    .setArtist(song.artistName)
                    .setAlbumTitle(song.albumName)
                    .setArtworkUri(Uri.parse(song.artworkUrl))
                    .build(),
            ).build()

    private fun mimeTypeFor(format: String): String =
        when (format.lowercase()) {
            "mp3", "mpeg" -> MimeTypes.AUDIO_MPEG
            "aac" -> MimeTypes.AUDIO_AAC
            "flac" -> MimeTypes.AUDIO_FLAC
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            "opus" -> MimeTypes.AUDIO_OPUS
            "wav" -> MimeTypes.AUDIO_WAV
            "m4a", "mp4" -> MimeTypes.AUDIO_AAC
            else -> MimeTypes.AUDIO_MPEG
        }
}
