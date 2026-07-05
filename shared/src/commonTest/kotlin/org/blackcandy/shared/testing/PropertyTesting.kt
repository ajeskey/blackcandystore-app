package org.blackcandy.shared.testing

import kotlin.random.Random
import kotlin.test.fail

/**
 * Minimal property-testing harness for the app-multi-server-playback-and-casting spec.
 *
 * The project has no property-testing library, so this provides a small seeded-random
 * runner. Each property test runs a minimum of [iterations] (default 100) generated
 * cases and, on failure, reports the seed and iteration so the case can be reproduced.
 *
 * Property tests are tagged in the exact format:
 *   `# Feature: app-multi-server-playback-and-casting, Property {number}: {property_text}`
 */
const val DEFAULT_PROPERTY_ITERATIONS = 100

fun checkProperty(
    iterations: Int = DEFAULT_PROPERTY_ITERATIONS,
    seed: Long = 0x9E3779B97F4A7C15uL.toLong(),
    block: (random: Random, iteration: Int) -> Unit,
) {
    require(iterations >= DEFAULT_PROPERTY_ITERATIONS) {
        "Property tests must run at least $DEFAULT_PROPERTY_ITERATIONS iterations"
    }
    for (i in 0 until iterations) {
        val iterationSeed = seed + i
        val random = Random(iterationSeed)
        try {
            block(random, i)
        } catch (e: Throwable) {
            fail(
                "Property failed at iteration $i (seed=$iterationSeed): ${e.message}",
                e,
            )
        }
    }
}
