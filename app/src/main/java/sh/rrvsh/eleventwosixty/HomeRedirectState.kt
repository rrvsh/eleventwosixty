package sh.rrvsh.eleventwosixty

import android.content.Context
import android.content.SharedPreferences

internal class HomeRedirectState(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(): Boolean = readBoolean(ENABLED_KEY)

    fun circuitBreakerOpened(): Boolean = readBoolean(CIRCUIT_OPEN_KEY)

    fun enable(): Boolean = edit {
        putBoolean(ENABLED_KEY, true)
        putBoolean(CIRCUIT_OPEN_KEY, false)
        remove(ATTEMPTS_KEY)
    }

    fun restore(): Boolean = edit {
        putBoolean(ENABLED_KEY, false)
        putBoolean(CIRCUIT_OPEN_KEY, false)
        remove(ATTEMPTS_KEY)
    }

    fun openCircuit(): Boolean = edit {
        putBoolean(ENABLED_KEY, false)
        putBoolean(CIRCUIT_OPEN_KEY, true)
    }

    fun suppressUntil(now: Long): Boolean {
        if (now <= 0L) return false
        return edit { putLong(SUPPRESSION_DEADLINE_KEY, now + SUPPRESSION_DURATION_MS) }
    }

    fun clearSuppression(): Boolean = edit { remove(SUPPRESSION_DEADLINE_KEY) }

    fun isSuppressed(now: Long): Boolean {
        if (now <= 0L) return false
        val deadline = readLong(SUPPRESSION_DEADLINE_KEY)
        if (deadline <= now) {
            if (deadline != 0L) clearSuppression()
            return false
        }
        if (deadline - now > SUPPRESSION_DURATION_MS + CLOCK_TOLERANCE_MS) {
            suppressUntil(now)
            return true
        }
        return true
    }

    fun recentAttempts(now: Long): List<Long> {
        val parsed = try {
            preferences.getString(ATTEMPTS_KEY, null)
                ?.split(',')
                ?.mapNotNull(String::toLongOrNull)
                .orEmpty()
        } catch (_: ClassCastException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
        val sanitized = HomeRedirectPolicy.sanitizeAttempts(parsed, now)
        if (sanitized != parsed) writeAttempts(sanitized)
        return sanitized
    }

    fun recordAttempt(now: Long): Boolean {
        if (now <= 0L) return false
        val attempts = HomeRedirectPolicy.sanitizeAttempts(recentAttempts(now) + now, now)
        return writeAttempts(attempts)
    }

    fun clearAttempts(): Boolean = edit { remove(ATTEMPTS_KEY) }

    private fun writeAttempts(attempts: List<Long>): Boolean = edit {
        if (attempts.isEmpty()) {
            remove(ATTEMPTS_KEY)
        } else {
            putString(ATTEMPTS_KEY, attempts.joinToString(","))
        }
    }

    private fun readBoolean(key: String): Boolean = try {
        preferences.getBoolean(key, false)
    } catch (_: ClassCastException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun readLong(key: String): Long = try {
        preferences.getLong(key, 0L)
    } catch (_: ClassCastException) {
        0L
    } catch (_: RuntimeException) {
        0L
    }

    @Suppress("ApplySharedPref") // Redirect safety state must be durable before an activity launch.
    private fun edit(changes: SharedPreferences.Editor.() -> Unit): Boolean = try {
        preferences.edit().apply(changes).commit()
    } catch (_: RuntimeException) {
        false
    }

    internal companion object {
        const val PREFERENCES_NAME = "home_redirect_state"
        const val SUPPRESSION_DURATION_MS = 120_000L
        private const val CLOCK_TOLERANCE_MS = 5_000L
        private const val ENABLED_KEY = "redirect_enabled"
        private const val CIRCUIT_OPEN_KEY = "circuit_breaker_opened"
        private const val SUPPRESSION_DEADLINE_KEY = "suppression_deadline"
        private const val ATTEMPTS_KEY = "redirect_attempts"
    }
}
