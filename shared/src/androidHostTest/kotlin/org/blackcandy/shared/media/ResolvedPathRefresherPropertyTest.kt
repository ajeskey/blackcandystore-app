package org.blackcandy.shared.media

import kotlinx.coroutines.runBlocking
import org.blackcandy.shared.models.StreamSource
import org.blackcandy.shared.testing.Generators
import org.blackcandy.shared.testing.checkProperty
import org.blackcandy.shared.utils.TaskResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolvedPathRefresherPropertyTest {
    // Feature: app-multi-server-playback-and-casting, Property 6: Bounded refresh
    @Test
    fun atMostOneRefreshPerSongPerPlayRequest() {
        checkProperty { random, _ ->
            runBlocking {
                var fetchCount = 0
                val song =
                    Generators
                        .song(random)
                        .copy(streamSource = StreamSource.REMOTE, resolvedStreamPath = "https://s/old")

                val refresher =
                    ResolvedPathRefresher { _ ->
                        fetchCount++
                        // Always return a still-failing (empty) path to force repeated failures
                        TaskResult.Success(song.copy(resolvedStreamPath = ""))
                    }

                refresher.beginPlayRequest()

                val failures = random.nextInt(2, 20)
                repeat(failures) { refresher.onLoadFailure(song) }

                assertTrue(fetchCount <= 1, "expected at most one refresh, got $fetchCount")
            }
        }
    }

    @Test
    fun retriesWithRefreshedPathWhenAvailable() {
        checkProperty { random, _ ->
            runBlocking {
                val song =
                    Generators
                        .song(random)
                        .copy(streamSource = StreamSource.REMOTE, resolvedStreamPath = "")
                val refresher =
                    ResolvedPathRefresher { _ ->
                        TaskResult.Success(song.copy(resolvedStreamPath = "https://s/new"))
                    }
                refresher.beginPlayRequest()

                val outcome = refresher.onLoadFailure(song)
                assertTrue(outcome is ResolvedPathRefresher.RefreshOutcome.Retry)
                assertEquals("https://s/new", outcome.song.resolvedStreamPath)
            }
        }
    }

    @Test
    fun newPlayRequestAllowsAnotherRefresh() {
        runBlocking {
            var fetchCount = 0
            val song =
                Generators
                    .song(kotlin.random.Random(3))
                    .copy(streamSource = StreamSource.REMOTE)
            val refresher =
                ResolvedPathRefresher { _ ->
                    fetchCount++
                    TaskResult.Success(song.copy(resolvedStreamPath = ""))
                }

            refresher.beginPlayRequest()
            refresher.onLoadFailure(song)
            refresher.onLoadFailure(song)
            refresher.beginPlayRequest()
            refresher.onLoadFailure(song)

            assertEquals(2, fetchCount)
        }
    }

    @Test
    fun localSongIsNotRefreshed() {
        runBlocking {
            var fetchCount = 0
            val song =
                Generators
                    .song(kotlin.random.Random(4))
                    .copy(streamSource = StreamSource.LOCAL, resolvedStreamPath = "")
            val refresher =
                ResolvedPathRefresher { _ ->
                    fetchCount++
                    TaskResult.Success(song)
                }
            refresher.beginPlayRequest()
            val outcome = refresher.onLoadFailure(song)
            assertTrue(outcome is ResolvedPathRefresher.RefreshOutcome.Unavailable)
            assertEquals(0, fetchCount)
        }
    }
}
