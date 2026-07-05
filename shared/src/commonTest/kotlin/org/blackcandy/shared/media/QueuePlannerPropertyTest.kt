package org.blackcandy.shared.media

import org.blackcandy.shared.testing.Generators
import org.blackcandy.shared.testing.checkProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueuePlannerPropertyTest {
    private val modes = PlaybackMode.entries

    // Feature: app-multi-server-playback-and-casting, Property 2: Queue integrity under unavailability
    @Test
    fun advanceAlwaysLandsOnAvailableOrNone() {
        checkProperty { random, _ ->
            val queue = Generators.queue(random)
            val from = random.nextInt(0, queue.size)
            val mode = modes[random.nextInt(modes.size)]
            val direction =
                if (random.nextBoolean()) QueuePlanner.Direction.FORWARD else QueuePlanner.Direction.BACKWARD

            val next = QueuePlanner.nextAvailableIndex(queue, from, direction, mode)

            if (next != null) {
                // Never select an unavailable song
                assertTrue(queue[next].isAvailable, "returned index must be available")
                assertTrue(next in queue.indices, "returned index in range")
            } else {
                // Returning none is only valid when nothing reachable is available
                if (mode == PlaybackMode.REPEAT_ONE) {
                    val current = queue.getOrNull(from)
                    assertTrue(current == null || !current.isAvailable)
                }
            }
        }
    }

    @Test
    fun repeatOneStopsWhenCurrentUnavailable() {
        checkProperty { random, _ ->
            val queue = Generators.queue(random)
            val from = random.nextInt(0, queue.size)
            val result = QueuePlanner.nextAvailableIndex(queue, from, playbackMode = PlaybackMode.REPEAT_ONE)
            if (queue[from].isAvailable) {
                assertEquals(from, result)
            } else {
                assertNull(result)
            }
        }
    }

    @Test
    fun allUnavailableYieldsNone() {
        checkProperty { random, _ ->
            val queue = Generators.queue(random).map { it.copy(resolvedStreamPath = "", url = "") }
            val mode = modes[random.nextInt(modes.size)]
            assertNull(QueuePlanner.nextAvailableIndex(queue, 0, playbackMode = mode))
            assertNull(QueuePlanner.startIndex(queue))
            assertTrue(!QueuePlanner.hasAvailableSong(queue))
        }
    }

    @Test
    fun startIndexPrefersRequestedThenFirstAvailable() {
        checkProperty { random, _ ->
            val queue = Generators.queue(random)
            val preferred = random.nextInt(0, queue.size)
            val start = QueuePlanner.startIndex(queue, preferred)
            if (start != null) {
                assertTrue(queue[start].isAvailable)
                if (queue[preferred].isAvailable) assertEquals(preferred, start)
            } else {
                assertTrue(!QueuePlanner.hasAvailableSong(queue))
            }
        }
    }
}
