package sh.rrvsh.eleventwosixty

import android.support.test.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRedirectStateInstrumentedTest {
    @Test
    fun enableSuppressAndRestorePrivateState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(HomeRedirectState.PREFERENCES_NAME, 0).edit().clear().commit()
        val state = HomeRedirectState(context)
        val now = System.currentTimeMillis()

        assertTrue(state.enable())
        assertTrue(state.isEnabled())
        assertTrue(state.suppressUntil(now))
        assertTrue(state.isSuppressed(now + 1_000L))
        assertTrue(state.restore())
        assertFalse(state.isEnabled())

        context.getSharedPreferences(HomeRedirectState.PREFERENCES_NAME, 0).edit().clear().commit()
    }

    @Test
    fun backwardClockSanitizationIsPersisted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(HomeRedirectState.PREFERENCES_NAME, 0).edit().clear().commit()
        val state = HomeRedirectState(context)
        val correctedNow = System.currentTimeMillis()
        val oldFutureTime = correctedNow + 60_000L

        assertTrue(state.recordAttempt(oldFutureTime))
        assertTrue(state.recentAttempts(correctedNow).isEmpty())
        assertTrue(state.recentAttempts(oldFutureTime).isEmpty())

        context.getSharedPreferences(HomeRedirectState.PREFERENCES_NAME, 0).edit().clear().commit()
    }
}
