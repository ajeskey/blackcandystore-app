package org.blackcandy.shared.media

import org.blackcandy.shared.testing.Generators
import org.blackcandy.shared.testing.checkProperty
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property 3: Cast session state invariant.
 *
 * Validates that the pure [CastSessionMachine] (spec R10) upholds two universal invariants
 * across arbitrary random control sequences:
 * - R10.8: every reachable session state is exactly one of `stopped`, `playing`, or `paused`.
 * - R10.9: for any session in `playing`, applying `pause()` then `resume()` with no intervening
 *   operation returns it to `playing` with the same current Song and the position retained at pause.
 *
 * The universal state invariant is enforced by driving a random sequence of every control /
 * failure operation and asserting a valid state after each step; the resume-after-pause invariant
 * is checked whenever the sequence lands in `playing`.
 */
class CastSessionMachinePropertyTest {
    /** The discrete operations that can be applied to a cast session. */
    private enum class Op {
        PLAY,
        RESUME,
        PAUSE,
        STOP,
        SET_VOLUME,
        ON_DISCONNECT,
        ON_STALLED_TIMEOUT,
    }

    private val ops = Op.entries

    private fun apply(
        machine: CastSessionMachine,
        op: Op,
        random: Random,
    ): CastSessionMachine =
        when (op) {
            Op.PLAY ->
                machine.play(
                    song = Generators.song(random),
                    positionSeconds = random.nextDouble(0.0, 600.0),
                    targetReachable = random.nextInt(5) != 0, // occasionally unreachable (R10.5)
                )

            Op.RESUME -> machine.resume()
            Op.PAUSE -> machine.pause()
            Op.STOP -> machine.stop()
            Op.SET_VOLUME -> machine.setVolume(random.nextDouble(-1.0, 2.0))
            Op.ON_DISCONNECT -> machine.onDisconnect()
            Op.ON_STALLED_TIMEOUT -> machine.onPlaybackStalledTimeout()
        }

    // Feature: app-multi-server-playback-and-casting, Property 3: Cast session state invariant
    @Test
    fun stateAlwaysValidAndResumeAfterPauseReturnsToPlayingRetainingSongAndPosition() {
        checkProperty { random, _ ->
            var machine = CastSessionMachine()

            // Every freshly constructed machine already satisfies the state invariant (R10.8).
            assertValidState(machine)

            val sequenceLength = random.nextInt(1, 40)
            repeat(sequenceLength) {
                val op = ops[random.nextInt(ops.size)]
                machine = apply(machine, op, random)

                // R10.8: after every operation the state is exactly one valid state.
                assertValidState(machine)

                // R10.9: whenever the session is playing, pause-then-resume with no intervening
                // operation must return to playing with the retained song and position.
                if (machine.state == CastSessionState.PLAYING) {
                    val paused = machine.pause()
                    assertEquals(
                        CastSessionState.PAUSED,
                        paused.state,
                        "pause from playing must enter paused",
                    )

                    val resumed = paused.resume()
                    assertEquals(
                        CastSessionState.PLAYING,
                        resumed.state,
                        "resume after pause must return to playing",
                    )
                    assertEquals(
                        machine.currentSong,
                        resumed.currentSong,
                        "resume after pause must retain the current song",
                    )
                    assertEquals(
                        paused.position,
                        resumed.position,
                        "resume after pause must retain the position held at pause",
                    )
                    // The position held at pause is exactly the playing position (R10.2).
                    assertEquals(
                        machine.position,
                        paused.position,
                        "pause must retain the playing position",
                    )
                }
            }
        }
    }

    private fun assertValidState(machine: CastSessionMachine) {
        assertTrue(
            machine.state == CastSessionState.STOPPED ||
                machine.state == CastSessionState.PLAYING ||
                machine.state == CastSessionState.PAUSED,
            "cast session state must be exactly one of STOPPED/PLAYING/PAUSED but was ${machine.state}",
        )
    }
}
