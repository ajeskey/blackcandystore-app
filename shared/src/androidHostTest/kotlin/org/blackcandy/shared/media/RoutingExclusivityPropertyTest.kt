package org.blackcandy.shared.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.blackcandy.shared.models.DeviceOrigin
import org.blackcandy.shared.models.OutputDevice
import org.blackcandy.shared.models.OutputDeviceProtocol
import org.blackcandy.shared.models.Song
import org.blackcandy.shared.testing.Generators
import org.blackcandy.shared.testing.checkProperty
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Property 4: Routing exclusivity.
 *
 * For any sequence of target selections, at most one [PlaybackEngine] is active at a time, and
 * selecting a new target deactivates the previously active engine before activating the next
 * (spec R7.4, R15.1, R15.5).
 *
 * The test drives a real [PlaybackCoordinator] with four fake engines (local, chromecast,
 * airplay, server) that record every `activate`/`deactivate` call into a shared, ordered event
 * log. Replaying that log lets us assert the global invariant across the whole random sequence.
 */
class RoutingExclusivityPropertyTest {
    /** A single ordered activation event across all engines. */
    private sealed interface Event {
        val engineId: String

        data class Activate(override val engineId: String) : Event

        data class Deactivate(override val engineId: String) : Event
    }

    /** Shared, ordered recorder every fake engine writes to. Single-threaded under runBlocking. */
    private class Recorder {
        val events = mutableListOf<Event>()

        fun activate(engineId: String) = events.add(Event.Activate(engineId))

        fun deactivate(engineId: String) = events.add(Event.Deactivate(engineId))
    }

    /** A fake engine that records activation ordering and reflects a controllable status. */
    private class FakeEngine(
        private val id: String,
        private val recorder: Recorder,
        initialStatus: EngineStatus = EngineStatus(),
    ) : PlaybackEngine {
        private val statusState = MutableStateFlow(initialStatus)
        override val status: StateFlow<EngineStatus> = statusState.asStateFlow()

        override fun setQueue(
            songs: List<Song>,
            startIndex: Int,
        ) {
            val song = songs.getOrNull(startIndex)
            statusState.value = statusState.value.copy(currentSong = song)
        }

        override fun play() {
            statusState.value = statusState.value.copy(state = PlaybackState.PLAYING)
        }

        override fun pause() {
            statusState.value = statusState.value.copy(state = PlaybackState.PAUSED)
        }

        override fun stop() {
            statusState.value = statusState.value.copy(state = PlaybackState.IDLE, position = 0.0)
        }

        override fun next() = Unit

        override fun previous() = Unit

        override fun seekTo(seconds: Double) {
            statusState.value = statusState.value.copy(position = seconds)
        }

        override fun setVolume(level: Double) {
            statusState.value = statusState.value.copy(volume = level)
        }

        override suspend fun activate(target: PlaybackTarget?) {
            recorder.activate(id)
            statusState.value = statusState.value.copy(target = target)
        }

        override suspend fun deactivate(retainPosition: Boolean) {
            recorder.deactivate(id)
            if (!retainPosition) {
                statusState.value = statusState.value.copy(position = 0.0)
            }
        }
    }

    // Feature: app-multi-server-playback-and-casting, Property 4: Routing exclusivity
    @Test
    fun atMostOneEngineActiveAndPriorDeactivatedBeforeNextActivates() {
        checkProperty { random, _ ->
            runBlocking {
                val recorder = Recorder()
                val local = FakeEngine("local", recorder, playingStatus(random))
                val chromecast = FakeEngine("chromecast", recorder)
                val airplay = FakeEngine("airplay", recorder)
                val server = FakeEngine("server", recorder)

                val scope = CoroutineScope(Dispatchers.Unconfined)
                val coordinator =
                    PlaybackCoordinator(
                        local = local,
                        chromecast = chromecast,
                        airplay = airplay,
                        serverPlayback = server,
                        scope = scope,
                    )

                try {
                    // Seed a queue so switches exercise the queue/position transfer path (R15.4).
                    val queue = Generators.queue(random)
                    coordinator.setQueue(queue, random.nextInt(0, queue.size))

                    // A random sequence of target selections across local / cast / server.
                    val sequenceLength = random.nextInt(2, 20)
                    val devices = devicePool(random)
                    repeat(sequenceLength) {
                        coordinator.selectTarget(randomTarget(random, devices))
                    }

                    assertRoutingExclusivity(recorder.events)
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    /**
     * Replay the ordered event log and assert the routing-exclusivity invariant:
     * - at no point is more than one engine active (R7.4, R15.1);
     * - an engine is only activated when no other engine is active, i.e. the previously active
     *   engine was deactivated first (R15.5). Re-activating the already-active engine (same-engine
     *   device change) is allowed.
     */
    private fun assertRoutingExclusivity(events: List<Event>) {
        val active = mutableSetOf<String>()
        events.forEachIndexed { index, event ->
            when (event) {
                is Event.Activate -> {
                    val others = active - event.engineId
                    assertTrue(
                        others.isEmpty(),
                        "activating ${event.engineId} at step $index while $others still active " +
                            "(prior engine must be deactivated first)",
                    )
                    active.add(event.engineId)
                }

                is Event.Deactivate -> active.remove(event.engineId)
            }
            assertTrue(
                active.size <= 1,
                "more than one engine active after step $index: $active",
            )
        }
    }

    private fun playingStatus(random: Random): EngineStatus {
        val playing = random.nextBoolean()
        return EngineStatus(
            state = if (playing) PlaybackState.PLAYING else PlaybackState.PAUSED,
            position = random.nextDouble(0.0, 300.0),
        )
    }

    private data class DevicePool(
        val chromecast: OutputDevice,
        val airplay: OutputDevice,
        val serverDevices: List<OutputDevice>,
    )

    private fun devicePool(random: Random): DevicePool {
        val chromecast =
            OutputDevice(
                id = "cc-${random.nextInt(1000)}",
                name = "Chromecast ${random.nextInt(1000)}",
                protocol = OutputDeviceProtocol.CHROMECAST,
                origin = DeviceOrigin.LOCAL,
            )
        val airplay =
            OutputDevice(
                id = "ap-${random.nextInt(1000)}",
                name = "AirPlay ${random.nextInt(1000)}",
                protocol = OutputDeviceProtocol.AIRPLAY,
                origin = DeviceOrigin.LOCAL,
            )
        val serverDevices =
            (0..random.nextInt(0, 3)).map {
                OutputDevice(
                    id = "srv-$it-${random.nextInt(1000)}",
                    name = "Server Device $it",
                    protocol = if (random.nextBoolean()) OutputDeviceProtocol.AIRPLAY else OutputDeviceProtocol.CHROMECAST,
                    origin = DeviceOrigin.SERVER,
                )
            }
        return DevicePool(chromecast, airplay, serverDevices)
    }

    private fun randomTarget(
        random: Random,
        pool: DevicePool,
    ): PlaybackTarget? =
        when (random.nextInt(5)) {
            0 -> null // null target == default local device (R7.7)
            1 -> PlaybackTarget.LocalDevice
            2 -> PlaybackTarget.LocalCastDevice(pool.chromecast)
            3 -> PlaybackTarget.LocalCastDevice(pool.airplay)
            else -> PlaybackTarget.ServerDevice(pool.serverDevices)
        }
}
