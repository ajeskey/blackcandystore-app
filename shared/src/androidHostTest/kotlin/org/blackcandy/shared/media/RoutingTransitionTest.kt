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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Targeted verification of the routing **switch transitions** (task 20.2, spec R15.2–R15.5).
 *
 * Property 4 ([RoutingExclusivityPropertyTest]) already proves the global at-most-one-active
 * invariant and prior-deactivated-before-next-activated over long random sequences. This test
 * is deliberately narrower and complementary: for each ordered routing pair across
 * `client_cast ↔ server_playback ↔ local`, it asserts the *content* of the switch —
 *
 * - the outgoing engine is fully **deactivated (retaining position) before** the incoming engine
 *   is activated (R15.2 cast→server ends the Cast_Session first; R15.3 server→cast stops remote
 *   control first; R15.5 never two live engines),
 * - the retained **queue + position** are transferred to the incoming engine before activation,
 * - playback **resumes** on the incoming engine when the outgoing engine was playing, and does
 *   NOT resume when it was paused (R15.4, "resume ... from the retained position where possible").
 *
 * The three logical routings are driven through fake engines that record an ordered log of every
 * `deactivate`/`setQueue`/`seekTo`/`activate`/`play` call so the ordering and transferred values
 * can be asserted precisely.
 */
class RoutingTransitionTest {
    /** An ordered operation recorded across all engines. */
    private sealed interface Op {
        val engineId: String

        data class Deactivate(override val engineId: String, val retainPosition: Boolean) : Op

        data class SetQueue(override val engineId: String, val startIndex: Int) : Op

        data class SeekTo(override val engineId: String, val position: Double) : Op

        data class Activate(override val engineId: String) : Op

        data class Play(override val engineId: String) : Op
    }

    /** Shared, ordered recorder every fake engine writes to. Single-threaded under runBlocking. */
    private class Recorder {
        val ops = mutableListOf<Op>()

        fun clear() = ops.clear()
    }

    /**
     * A fake engine recording the transition-relevant calls and reflecting a controllable status
     * so the coordinator can read position/currentSong/state when planning a switch.
     */
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
            recorder.ops.add(Op.SetQueue(id, startIndex))
            statusState.value = statusState.value.copy(currentSong = songs.getOrNull(startIndex))
        }

        override fun play() {
            recorder.ops.add(Op.Play(id))
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
            recorder.ops.add(Op.SeekTo(id, seconds))
            statusState.value = statusState.value.copy(position = seconds)
        }

        override fun setVolume(level: Double) {
            statusState.value = statusState.value.copy(volume = level)
        }

        override suspend fun activate(target: PlaybackTarget?) {
            recorder.ops.add(Op.Activate(id))
            statusState.value = statusState.value.copy(target = target)
        }

        override suspend fun deactivate(retainPosition: Boolean) {
            recorder.ops.add(Op.Deactivate(id, retainPosition))
            statusState.value =
                if (retainPosition) {
                    // Pause in place; position stays observable for the coordinator (R15.4).
                    statusState.value.copy(state = PlaybackState.PAUSED)
                } else {
                    statusState.value.copy(state = PlaybackState.IDLE, position = 0.0)
                }
        }
    }

    private enum class Routing { LOCAL, CAST, SERVER }

    private class Rig(
        random: Random,
        startPlaying: Boolean,
        startPosition: Double,
    ) {
        val recorder = Recorder()
        val queue: List<Song> = Generators.queue(random)
        val startIndex: Int = random.nextInt(0, queue.size)

        private val scope = CoroutineScope(Dispatchers.Unconfined)
        private val local =
            FakeEngine(
                "local",
                recorder,
                EngineStatus(
                    state = if (startPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED,
                    position = startPosition,
                ),
            )
        private val cast = FakeEngine("cast", recorder)
        private val server = FakeEngine("server", recorder)

        val coordinator =
            PlaybackCoordinator(
                local = local,
                chromecast = cast,
                airplay = null,
                serverPlayback = server,
                scope = scope,
            )

        fun engineId(routing: Routing) =
            when (routing) {
                Routing.LOCAL -> "local"
                Routing.CAST -> "cast"
                Routing.SERVER -> "server"
            }

        fun target(routing: Routing): PlaybackTarget? =
            when (routing) {
                Routing.LOCAL -> PlaybackTarget.LocalDevice
                Routing.CAST ->
                    PlaybackTarget.LocalCastDevice(
                        OutputDevice(
                            id = "cc-1",
                            name = "Living Room",
                            protocol = OutputDeviceProtocol.CHROMECAST,
                            origin = DeviceOrigin.LOCAL,
                        ),
                    )
                Routing.SERVER ->
                    PlaybackTarget.ServerDevice(
                        listOf(
                            OutputDevice(
                                id = "srv-1",
                                name = "Kitchen Speaker",
                                protocol = OutputDeviceProtocol.AIRPLAY,
                                origin = DeviceOrigin.SERVER,
                            ),
                        ),
                    )
            }

        fun cancel() = scope.cancel()
    }

    private val orderedPairs =
        listOf(
            Routing.LOCAL to Routing.CAST,
            Routing.CAST to Routing.LOCAL,
            Routing.LOCAL to Routing.SERVER,
            Routing.SERVER to Routing.LOCAL,
            Routing.CAST to Routing.SERVER,
            Routing.SERVER to Routing.CAST,
        )

    /**
     * Every pairwise transition across `client_cast ↔ server_playback ↔ local`, starting from a
     * playing source at a non-zero position, ends the prior engine before starting the next and
     * resumes from the retained queue index + position (R15.2, R15.3, R15.4, R15.5).
     */
    @Test
    fun pairwiseTransitionsEndPriorEngineAndResumeFromRetainedPosition() {
        val random = Random(1)
        val retainedPosition = 42.5

        for ((from, to) in orderedPairs) {
            val rig = Rig(random, startPlaying = true, startPosition = retainedPosition)
            try {
                runBlocking {
                    rig.coordinator.setQueue(rig.queue, rig.startIndex)

                    // Move onto the source engine (unless it is already the default local engine),
                    // then isolate the transition under test.
                    if (from != Routing.LOCAL) {
                        rig.coordinator.selectTarget(rig.target(from))
                    }
                    rig.recorder.clear()

                    rig.coordinator.selectTarget(rig.target(to))
                }

                val label = "${from.name} -> ${to.name}"
                val ops = rig.recorder.ops
                val fromId = rig.engineId(from)
                val toId = rig.engineId(to)

                assertSingleActiveEngineInvariant(ops, label)
                assertDeactivatedBeforeActivated(ops, fromId, toId, label, retainPosition = true)
                assertQueueAndPositionTransferred(ops, toId, rig.startIndex, retainedPosition, label)
                assertResumedAfterActivate(ops, toId, label, shouldResume = true)
            } finally {
                rig.cancel()
            }
        }
    }

    // Feature: app-multi-server-playback-and-casting, Property 4: Routing exclusivity (transition content)
    /**
     * For random retained positions and playing state across every ordered routing pair, the
     * incoming engine receives the retained queue index and position before activation, and
     * resumes iff the outgoing engine was playing (R15.4). Complements Property 4 by asserting the
     * transferred values and resume semantics rather than only the activate/deactivate ordering.
     */
    @Test
    fun retainedPositionAndResumeTransferMatchPreviousPlayingState() {
        checkProperty { random, _ ->
            runBlocking {
                val (from, to) = orderedPairs[random.nextInt(orderedPairs.size)]
                val wasPlaying = random.nextBoolean()
                val position = random.nextDouble(0.1, 600.0)

                val rig = Rig(random, startPlaying = wasPlaying, startPosition = position)
                try {
                    rig.coordinator.setQueue(rig.queue, rig.startIndex)
                    if (from != Routing.LOCAL) {
                        // Establishing the source engine always resumes it (it is reached from a
                        // playing local engine); force its state to match the case under test so
                        // the transition observes the intended wasPlaying value.
                        rig.coordinator.selectTarget(rig.target(from))
                        if (!wasPlaying) rig.coordinator.pause()
                    }
                    rig.recorder.clear()

                    rig.coordinator.selectTarget(rig.target(to))

                    val label = "${from.name} -> ${to.name} (wasPlaying=$wasPlaying)"
                    val ops = rig.recorder.ops
                    val toId = rig.engineId(to)

                    assertSingleActiveEngineInvariant(ops, label)
                    assertDeactivatedBeforeActivated(ops, rig.engineId(from), toId, label, retainPosition = true)
                    assertQueueAndPositionTransferred(ops, toId, rig.startIndex, position, label)
                    assertResumedAfterActivate(ops, toId, label, shouldResume = wasPlaying)
                } finally {
                    rig.cancel()
                }
            }
        }
    }

    // ---- Assertions ---------------------------------------------------------------------------

    private fun assertDeactivatedBeforeActivated(
        ops: List<Op>,
        fromId: String,
        toId: String,
        label: String,
        retainPosition: Boolean,
    ) {
        val deactivate = ops.indexOfFirst { it is Op.Deactivate && it.engineId == fromId }
        val activate = ops.indexOfFirst { it is Op.Activate && it.engineId == toId }
        assertTrue(deactivate >= 0, "$label: outgoing engine $fromId was never deactivated")
        assertTrue(activate >= 0, "$label: incoming engine $toId was never activated")
        assertTrue(
            deactivate < activate,
            "$label: $fromId must be deactivated (idx=$deactivate) before $toId is activated (idx=$activate)",
        )
        val deactivateOp = ops[deactivate] as Op.Deactivate
        assertEquals(
            retainPosition,
            deactivateOp.retainPosition,
            "$label: outgoing engine must be deactivated retaining position (R15.4)",
        )
    }

    private fun assertQueueAndPositionTransferred(
        ops: List<Op>,
        toId: String,
        expectedIndex: Int,
        expectedPosition: Double,
        label: String,
    ) {
        val activate = ops.indexOfFirst { it is Op.Activate && it.engineId == toId }

        val setQueue = ops.indexOfFirst { it is Op.SetQueue && it.engineId == toId }
        assertTrue(setQueue in 0 until activate, "$label: queue must be transferred to $toId before activation")
        assertEquals(
            expectedIndex,
            (ops[setQueue] as Op.SetQueue).startIndex,
            "$label: incoming engine must start from the retained queue index",
        )

        val seek = ops.indexOfFirst { it is Op.SeekTo && it.engineId == toId }
        assertTrue(seek in 0 until activate, "$label: retained position must be transferred to $toId before activation")
        assertEquals(
            expectedPosition,
            (ops[seek] as Op.SeekTo).position,
            0.0001,
            "$label: incoming engine must resume from the retained position (R15.4)",
        )
    }

    private fun assertResumedAfterActivate(
        ops: List<Op>,
        toId: String,
        label: String,
        shouldResume: Boolean,
    ) {
        val activate = ops.indexOfFirst { it is Op.Activate && it.engineId == toId }
        val play = ops.indexOfFirst { it is Op.Play && it.engineId == toId }
        if (shouldResume) {
            assertTrue(play >= 0, "$label: incoming engine must resume playback when previously playing (R15.4)")
            assertTrue(play > activate, "$label: resume must happen after activation, not before")
        } else {
            assertEquals(-1, play, "$label: incoming engine must NOT auto-resume when previously paused")
        }
    }

    /** Replay the ordered log and assert no two engines are ever active at once (R15.5). */
    private fun assertSingleActiveEngineInvariant(
        ops: List<Op>,
        label: String,
    ) {
        val active = mutableSetOf<String>()
        ops.forEachIndexed { index, op ->
            when (op) {
                is Op.Activate -> {
                    val others = active - op.engineId
                    assertTrue(others.isEmpty(), "$label: activating ${op.engineId} at $index while $others still active")
                    active.add(op.engineId)
                }
                is Op.Deactivate -> active.remove(op.engineId)
                else -> Unit
            }
            assertTrue(active.size <= 1, "$label: more than one engine active after step $index: $active")
        }
    }
}
