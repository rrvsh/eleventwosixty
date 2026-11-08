package sh.rrvsh.eleventwosixty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRedirectPolicyTest {
    @Test
    fun deduplicatesHandledEventsAndRateLimitsNewEvents() {
        val now = 100_000L

        assertFalse(
            HomeRedirectPolicy.shouldHandle(
                eventTime = 99_000L,
                now = now,
                lookback = 5_000L,
                lastHandledEventTime = 99_000L,
                lastLaunchTime = 0L,
            ),
        )
        assertFalse(
            HomeRedirectPolicy.shouldHandle(
                eventTime = 99_500L,
                now = now,
                lookback = 5_000L,
                lastHandledEventTime = 99_000L,
                lastLaunchTime = 98_000L,
            ),
        )
        assertTrue(
            HomeRedirectPolicy.shouldHandle(
                eventTime = 99_500L,
                now = now,
                lookback = 5_000L,
                lastHandledEventTime = 99_000L,
                lastLaunchTime = 97_000L,
            ),
        )
    }

    @Test
    fun rejectsStaleAndFutureEventsAndFutureLaunchClockState() {
        assertFalse(HomeRedirectPolicy.shouldHandle(90_000L, 100_000L, 5_000L, 0L, 0L))
        assertFalse(HomeRedirectPolicy.shouldHandle(100_001L, 100_000L, 5_000L, 0L, 0L))
        assertFalse(HomeRedirectPolicy.shouldHandle(99_000L, 100_000L, 5_000L, 0L, 101_000L))
    }

    @Test
    fun thirdUnclearedAttemptOpensCircuitAfterHealthGrace() {
        val attempts = listOf(100_000L, 108_000L, 116_000L)

        assertFalse(HomeRedirectPolicy.circuitShouldOpen(attempts, 122_000L))
        assertTrue(HomeRedirectPolicy.circuitShouldOpen(attempts, 123_000L))
        assertTrue(HomeRedirectPolicy.circuitShouldOpen(attempts, 131_000L))
    }

    @Test
    fun qualifyingBoundaryCohortSurvivesThroughGrace() {
        val attempts = listOf(100_000L, 124_000L, 129_000L)

        assertFalse(HomeRedirectPolicy.circuitShouldOpen(attempts, 129_000L))
        assertFalse(HomeRedirectPolicy.circuitShouldOpen(attempts, 135_999L))
        assertTrue(HomeRedirectPolicy.circuitShouldOpen(attempts, 136_000L))
        assertEquals(attempts, HomeRedirectPolicy.sanitizeAttempts(attempts, 136_000L))
    }

    @Test
    fun nonqualifyingTimestampsRollForwardIntoLaterCohort() {
        val nonqualifying = listOf(100_000L, 124_000L, 131_000L)

        assertFalse(HomeRedirectPolicy.hasQualifyingCohort(nonqualifying))
        assertFalse(HomeRedirectPolicy.circuitShouldOpen(nonqualifying, 138_000L))

        val rolling = HomeRedirectPolicy.sanitizeAttempts(nonqualifying, 131_000L)
        assertEquals(listOf(124_000L, 131_000L), rolling)
        assertTrue(
            HomeRedirectPolicy.shouldHandle(
                eventTime = 134_500L,
                now = 135_000L,
                lookback = 5_000L,
                lastHandledEventTime = 0L,
                lastLaunchTime = rolling.last(),
            ),
        )

        val laterCohort = HomeRedirectPolicy.sanitizeAttempts(rolling + 135_000L, 135_000L)
        assertEquals(listOf(124_000L, 131_000L, 135_000L), laterCohort)
        assertTrue(HomeRedirectPolicy.hasQualifyingCohort(laterCohort))
        assertTrue(HomeRedirectPolicy.circuitShouldOpen(laterCohort, 142_000L))
    }

    @Test
    fun futureAttemptIsInvalidatedOnceAfterBackwardClockJump() {
        val sanitized = HomeRedirectPolicy.sanitizeAttempts(listOf(200_000L), 100_000L)
        assertTrue(sanitized.isEmpty())
        assertTrue(
            HomeRedirectPolicy.shouldHandle(
                eventTime = 99_500L,
                now = 100_000L,
                lookback = 5_000L,
                lastHandledEventTime = 0L,
                lastLaunchTime = sanitized.lastOrNull() ?: 0L,
            ),
        )

        val nextTick = HomeRedirectPolicy.sanitizeAttempts(sanitized, 101_000L)
        assertTrue(nextTick.isEmpty())
        assertTrue(
            HomeRedirectPolicy.shouldHandle(
                eventTime = 100_500L,
                now = 101_000L,
                lookback = 5_000L,
                lastHandledEventTime = 0L,
                lastLaunchTime = nextTick.lastOrNull() ?: 0L,
            ),
        )
    }

    @Test
    fun fewerThanThreeAttemptsNeverOpenCircuit() {
        assertFalse(
            HomeRedirectPolicy.circuitShouldOpen(
                attempts = listOf(100_000L, 110_000L),
                now = 125_000L,
            ),
        )
    }
}
