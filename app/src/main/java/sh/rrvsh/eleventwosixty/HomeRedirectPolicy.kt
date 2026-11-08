package sh.rrvsh.eleventwosixty

internal object HomeRedirectPolicy {
    const val POLL_INTERVAL_MS = 1_000L
    const val INITIAL_LOOKBACK_MS = 120_000L
    const val RECENT_LOOKBACK_MS = 5_000L
    const val MIN_LAUNCH_INTERVAL_MS = 3_000L
    const val ATTEMPT_WINDOW_MS = 30_000L
    const val CIRCUIT_GRACE_MS = 7_000L
    const val MAX_ATTEMPTS = 3

    fun shouldHandle(
        eventTime: Long,
        now: Long,
        lookback: Long,
        lastHandledEventTime: Long,
        lastLaunchTime: Long,
    ): Boolean {
        if (now <= 0L || eventTime <= 0L || eventTime > now || now - eventTime > lookback) {
            return false
        }
        if (eventTime <= lastHandledEventTime) {
            return false
        }
        return lastLaunchTime <= 0L ||
            (lastLaunchTime <= now && now - lastLaunchTime >= MIN_LAUNCH_INTERVAL_MS)
    }

    fun sanitizeAttempts(attempts: List<Long>, now: Long): List<Long> {
        if (now <= 0L) return emptyList()

        val valid = attempts.filter { it in 1L..now }.sorted()
        latestQualifyingCohort(valid)?.let { return it }

        return valid
            .filter { it >= now - ATTEMPT_WINDOW_MS }
            .takeLast(MAX_ATTEMPTS - 1)
    }

    fun hasQualifyingCohort(attempts: List<Long>): Boolean =
        latestQualifyingCohort(attempts.filter { it > 0L }.sorted()) != null

    fun circuitShouldOpen(attempts: List<Long>, now: Long): Boolean {
        if (now <= 0L) return false
        val cohort = latestQualifyingCohort(attempts.filter { it in 1L..now }.sorted())
            ?: return false
        return now - cohort.last() >= CIRCUIT_GRACE_MS
    }

    private fun latestQualifyingCohort(attempts: List<Long>): List<Long>? {
        for (lastIndex in attempts.lastIndex downTo MAX_ATTEMPTS - 1) {
            val firstIndex = lastIndex - (MAX_ATTEMPTS - 1)
            if (attempts[lastIndex] - attempts[firstIndex] <= ATTEMPT_WINDOW_MS) {
                return attempts.subList(firstIndex, lastIndex + 1).toList()
            }
        }
        return null
    }
}
