package sh.rrvsh.eleventwosixty

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent

internal object HomeRedirectComponents {
    val PATCHWALL_HOME = ComponentName(
        "com.mitv.tvhome",
        "com.mitv.tvhome.MainActivityUserMode",
    )
    val STOCK_HOME = ComponentName(
        "com.mitv.tvhome",
        "com.mitv.tvhome.MainActivity",
    )
}

internal object UsageAccess {
    @Suppress("DEPRECATION")
    fun isAvailable(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                context.applicationInfo.uid,
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: RuntimeException) {
            false
        }
    }
}

class HomeRedirectAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: HomeRedirectState
    private var firstQuery = true
    private var lastHandledEventTime = 0L

    private val poll = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (_: RuntimeException) {
                // A denied system service or malformed platform event must not stop future ticks.
            } finally {
                handler.postDelayed(this, HomeRedirectPolicy.POLL_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        state = HomeRedirectState(this)
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // PatchWall emits no useful window event on the target firmware. Polling uses no event data.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun tick() {
        if (!::state.isInitialized || !state.isEnabled()) return

        val now = System.currentTimeMillis()
        if (state.isSuppressed(now) || !isDisplayInteractive()) return

        val attempts = state.recentAttempts(now)
        if (HomeRedirectPolicy.circuitShouldOpen(attempts, now)) {
            openCircuitAndStockHome(now)
            return
        }
        if (HomeRedirectPolicy.hasQualifyingCohort(attempts)) return
        if (!UsageAccess.isAvailable(this)) return

        val lookback = if (firstQuery) {
            HomeRedirectPolicy.INITIAL_LOOKBACK_MS
        } else {
            HomeRedirectPolicy.RECENT_LOOKBACK_MS
        }
        val foreground = newestForegroundEvent(now - lookback, now) ?: run {
            firstQuery = false
            return
        }
        firstQuery = false

        if (
            foreground.packageName != HomeRedirectComponents.PATCHWALL_HOME.packageName ||
            foreground.className != HomeRedirectComponents.PATCHWALL_HOME.className
        ) {
            return
        }

        val normalizedLastHandled = lastHandledEventTime.takeIf {
            it in (now - HomeRedirectPolicy.INITIAL_LOOKBACK_MS)..now
        } ?: 0L
        val lastLaunch = attempts.lastOrNull() ?: 0L
        if (
            !HomeRedirectPolicy.shouldHandle(
                eventTime = foreground.timeStamp,
                now = now,
                lookback = lookback,
                lastHandledEventTime = normalizedLastHandled,
                lastLaunchTime = lastLaunch,
            )
        ) {
            return
        }

        lastHandledEventTime = foreground.timeStamp
        if (!state.recordAttempt(now)) return

        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                ),
            )
        } catch (_: RuntimeException) {
            // The persisted attempt remains for the circuit breaker.
        }
    }

    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND is the Android 6 event required on the target.
    private fun newestForegroundEvent(begin: Long, end: Long): ForegroundEvent? {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val events = try {
            manager.queryEvents(begin.coerceAtLeast(0L), end)
        } catch (_: SecurityException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        val event = UsageEvents.Event()
        var newest: ForegroundEvent? = null
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            if (event.timeStamp <= 0L || event.timeStamp > end) continue
            if (newest == null || event.timeStamp > newest.timeStamp) {
                newest = ForegroundEvent(
                    packageName = event.packageName?.toString(),
                    className = event.className?.toString(),
                    timeStamp = event.timeStamp,
                )
            }
        }
        return newest
    }

    private fun isDisplayInteractive(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try {
            power.isInteractive
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun openCircuitAndStockHome(now: Long) {
        if (!state.suppressUntil(now)) return
        if (!openStockHome(this)) return
        state.openCircuit()
    }

    private data class ForegroundEvent(
        val packageName: String?,
        val className: String?,
        val timeStamp: Long,
    )
}

internal fun openStockHome(context: Context): Boolean {
    val component = resolveEnabledComponent(context, HomeRedirectComponents.STOCK_HOME) ?: return false
    return try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        true
    } catch (_: RuntimeException) {
        false
    }
}

internal fun resolveEnabledComponent(context: Context, component: ComponentName): ComponentName? {
    return try {
        val info = context.packageManager.getActivityInfo(component, 0)
        val application = info.applicationInfo ?: return null
        if (!info.enabled || !info.exported || !application.enabled) null else component
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
