package sh.rrvsh.eleventwosixty

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

class MainActivity : Activity() {
    private lateinit var settingsButton: Button
    private lateinit var stockHomeButton: Button
    private lateinit var diagnosticsButton: Button
    private lateinit var statusText: TextView
    private lateinit var appCountText: TextView
    private lateinit var appGrid: GridView
    private lateinit var adapter: AppGridAdapter
    private lateinit var preferences: SharedPreferences
    private lateinit var redirectState: HomeRedirectState

    private var apps: List<LaunchableApp> = emptyList()
    private var settingsIntent: Intent? = null
    private var stockHomeComponent: ComponentName? = null
    private var hasResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsButton = findViewById(R.id.settings_button)
        stockHomeButton = findViewById(R.id.stock_home_button)
        diagnosticsButton = findViewById(R.id.diagnostics_button)
        statusText = findViewById(R.id.status_text)
        appCountText = findViewById(R.id.app_count)
        appGrid = findViewById(R.id.app_grid)
        preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        redirectState = HomeRedirectState(this)

        adapter = AppGridAdapter(this, packageManager)
        appGrid.adapter = adapter
        appGrid.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            launchApp(apps[position])
        }
        appGrid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                remember(apps[position].component)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        settingsButton.setOnClickListener {
            settingsIntent?.let {
                launchIntent(getString(R.string.settings), Intent(it))
            }
        }
        stockHomeButton.setOnClickListener { suppressAndOpenStockHome() }
        diagnosticsButton.setOnClickListener { showDiagnostics() }

        refresh()
        appGrid.post { restoreFocus() }
    }

    override fun onResume() {
        super.onResume()
        redirectState.clearSuppression()
        if (hasResumed) {
            refresh()
            appGrid.post { restoreFocus() }
        }
        hasResumed = true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        redirectState.clearSuppression()
        refresh()
        appGrid.post { restoreFocus() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) redirectState.clearAttempts()
    }

    @Deprecated("The launcher deliberately keeps its root activity available on Back")
    override fun onBackPressed() {
        Toast.makeText(this, R.string.stays_open, Toast.LENGTH_SHORT).show()
        if (currentFocus == null) {
            restoreFocus()
        }
    }

    private fun refresh() {
        statusText.setTextColor(getColor(R.color.text_secondary))
        var discoveryFailed = false
        apps = try {
            AppCatalog.discover(packageManager, packageName)
        } catch (error: RuntimeException) {
            discoveryFailed = true
            showError(getString(R.string.launcher_title), error)
            emptyList()
        }
        refreshUtilities()
        adapter.replace(apps)
        appCountText.text = resources.getQuantityString(R.plurals.app_count, apps.size, apps.size)
        when {
            discoveryFailed -> Unit
            apps.isEmpty() -> {
                statusText.setText(R.string.no_apps)
                statusText.visibility = View.VISIBLE
            }
            else -> statusText.visibility = View.GONE
        }
        clearMissingPreference()
    }

    private fun refreshUtilities() {
        val standardSettings = Intent(Settings.ACTION_SETTINGS)
        settingsIntent = resolveEnabled(standardSettings)?.let {
            standardSettings.setComponent(it)
        } ?: apps.firstOrNull { it.component.packageName == XIAOMI_SETTINGS_PACKAGE }?.let {
            val category = if (it.leanback) {
                Intent.CATEGORY_LEANBACK_LAUNCHER
            } else {
                Intent.CATEGORY_LAUNCHER
            }
            Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(it.component)
        }
        stockHomeComponent = resolveEnabled(
            Intent().setComponent(HomeRedirectComponents.STOCK_HOME),
            matchDefaultOnly = false,
        )
        configureUtility(settingsButton, R.string.settings, settingsIntent != null)
        configureUtility(stockHomeButton, R.string.stock_home, stockHomeComponent != null)
    }

    private fun configureUtility(button: Button, labelResource: Int, available: Boolean) {
        button.isEnabled = available
        button.text = if (available) {
            getString(labelResource)
        } else {
            getString(R.string.unavailable_label, getString(labelResource))
        }
    }

    private fun resolveEnabled(intent: Intent, matchDefaultOnly: Boolean = true): ComponentName? {
        val flags = if (matchDefaultOnly) PackageManager.MATCH_DEFAULT_ONLY else 0
        return try {
            val activity = packageManager.resolveActivity(intent, flags)?.activityInfo ?: return null
            val application = activity.applicationInfo ?: return null
            if (!activity.enabled || !application.enabled || !activity.exported) {
                null
            } else {
                ComponentName(activity.packageName, activity.name)
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun launchApp(app: LaunchableApp) {
        remember(app.component)
        val category = if (app.leanback) {
            Intent.CATEGORY_LEANBACK_LAUNCHER
        } else {
            Intent.CATEGORY_LAUNCHER
        }
        launchIntent(
            app.label,
            Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(app.component),
        )
    }

    private fun launchIntent(label: String, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            refresh()
            showError(label, error)
        } catch (error: SecurityException) {
            showError(label, error)
        } catch (error: RuntimeException) {
            showError(label, error)
        }
    }

    private fun showError(label: String, error: RuntimeException) {
        showFailure(getString(R.string.launch_failed, label, errorReason(error)))
    }

    private fun showActionError(label: String, error: RuntimeException) {
        showFailure(getString(R.string.action_failed, label, errorReason(error)))
    }

    private fun errorReason(error: RuntimeException): String {
        return error.localizedMessage?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
    }

    private fun showFailure(message: String) {
        statusText.text = message
        statusText.setTextColor(getColor(R.color.error))
        statusText.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun restoreFocus() {
        if (apps.isNotEmpty()) {
            val remembered = readRememberedComponent()
            val position = apps.indexOfFirst { it.component == remembered }.let { if (it >= 0) it else 0 }
            appGrid.setSelection(position)
            appGrid.requestFocus()
        } else {
            when {
                settingsButton.isEnabled -> settingsButton.requestFocus()
                stockHomeButton.isEnabled -> stockHomeButton.requestFocus()
                else -> diagnosticsButton.requestFocus()
            }
        }
    }

    private fun remember(component: ComponentName) {
        try {
            preferences.edit().putString(LAST_COMPONENT_KEY, component.flattenToString()).apply()
        } catch (_: RuntimeException) {
            resetPreferences()
        }
    }

    private fun readRememberedComponent(): ComponentName? {
        val flattened = try {
            preferences.getString(LAST_COMPONENT_KEY, null)
        } catch (_: ClassCastException) {
            resetPreferences()
            null
        } catch (_: RuntimeException) {
            resetPreferences()
            null
        }
        if (flattened == null) return null
        return ComponentName.unflattenFromString(flattened).also {
            if (it == null) resetPreferences()
        }
    }

    private fun clearMissingPreference() {
        val remembered = readRememberedComponent() ?: return
        if (apps.none { it.component == remembered }) {
            resetPreferences()
        }
    }

    private fun resetPreferences() {
        try {
            preferences.edit().clear().apply()
        } catch (_: RuntimeException) {
            // The UI remains usable even if the private preference file cannot be repaired.
        }
    }

    private fun showDiagnostics() {
        val redirectEnabled = redirectState.isEnabled()
        val actionLabel = if (redirectEnabled) {
            R.string.restore_stock_home
        } else {
            R.string.enable_home_redirect
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setMessage(buildDiagnostics(redirectEnabled))
            .setNeutralButton(actionLabel) { _, _ ->
                if (redirectEnabled) restoreStockHome() else enableHomeRedirect()
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun enableHomeRedirect() {
        val usageAccess = UsageAccess.isAvailable(this)
        if (!redirectState.enable()) {
            showActionError(
                getString(R.string.enable_home_redirect),
                IllegalStateException(getString(R.string.private_state_unavailable)),
            )
            return
        }
        statusText.visibility = View.GONE
        Toast.makeText(
            this,
            if (usageAccess) {
                R.string.home_redirect_enabled
            } else {
                R.string.home_redirect_enabled_without_usage
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun restoreStockHome() {
        if (!redirectState.restore()) {
            showActionError(
                getString(R.string.restore_stock_home),
                IllegalStateException(getString(R.string.private_state_unavailable)),
            )
            return
        }
        suppressAndOpenStockHome()
    }

    private fun suppressAndOpenStockHome() {
        val now = System.currentTimeMillis()
        if (!redirectState.suppressUntil(now)) {
            showActionError(
                getString(R.string.stock_home),
                IllegalStateException(getString(R.string.private_state_unavailable)),
            )
            return
        }
        val component = resolveEnabledComponent(this, HomeRedirectComponents.STOCK_HOME)
        if (component == null) {
            refresh()
            showFailure(
                getString(
                    R.string.launch_failed,
                    getString(R.string.stock_home),
                    getString(R.string.unavailable),
                ),
            )
            return
        }
        launchIntent(
            getString(R.string.stock_home),
            Intent(Intent.ACTION_MAIN).setComponent(component),
        )
    }

    @Suppress("DEPRECATION")
    private fun buildDiagnostics(redirectEnabled: Boolean): String {
        val displaySize = Point().also { windowManager.defaultDisplay.getRealSize(it) }
        val settings = settingsIntent?.component?.flattenToShortString() ?: "unavailable"
        val homeSettings = describeResolution(Intent(Settings.ACTION_HOME_SETTINGS))
        val stockHome = describeResolution(
            Intent().setComponent(HomeRedirectComponents.STOCK_HOME),
            false,
        )
        val currentHome = describeResolution(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        )

        return buildString {
            append("App: ").append(appVersion()).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Display: ").append(displaySize.x).append('×').append(displaySize.y).append(" px\n")
            append("Settings: ").append(settings).append('\n')
            append("HOME_SETTINGS: ").append(homeSettings).append('\n')
            append("Stock Home: ").append(stockHome).append('\n')
            append("Current HOME: ").append(currentHome).append('\n')
            append("Home redirect: ")
                .append(if (redirectEnabled) "enabled" else "disabled")
            if (redirectState.circuitBreakerOpened()) append(" (crash circuit breaker)")
            append('\n')
            append("Accessibility service: ")
                .append(if (isRedirectAccessibilityServiceEnabled()) "enabled" else "disabled")
                .append('\n')
            append("Usage access: ")
                .append(if (UsageAccess.isAvailable(this@MainActivity)) "available" else "unavailable")
                .append('\n')
            append("Launchable apps: ").append(apps.size).append("\n\n")
            append("TV inputs\n")
            append(describeTvInputs())
        }
    }

    private fun isRedirectAccessibilityServiceEnabled(): Boolean {
        return try {
            if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                false
            } else {
                val ownService = ComponentName(this, HomeRedirectAccessibilityService::class.java)
                Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty().split(':').any {
                    ComponentName.unflattenFromString(it) == ownService
                }
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun appVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        } catch (_: RuntimeException) {
            "unknown"
        }
    }

    private fun describeResolution(intent: Intent, matchDefaultOnly: Boolean = true): String {
        return resolveEnabled(intent, matchDefaultOnly)?.flattenToShortString() ?: "unavailable"
    }

    private fun describeTvInputs(): String {
        val manager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return "TV input service unavailable"
        return try {
            val inputs = manager.tvInputList
            if (inputs.isEmpty()) {
                "No TV inputs reported"
            } else {
                inputs.joinToString("\n") { input -> describeTvInput(manager, input) }
            }
        } catch (error: SecurityException) {
            "TV inputs unavailable: ${error.javaClass.simpleName}"
        } catch (error: RuntimeException) {
            "TV inputs unavailable: ${error.javaClass.simpleName}"
        }
    }

    private fun describeTvInput(manager: TvInputManager, input: TvInputInfo): String {
        val label = try {
            input.loadLabel(this)?.toString()?.trim().orEmpty().ifEmpty { "Unnamed input" }
        } catch (_: RuntimeException) {
            "Unnamed input"
        }
        val state = try {
            when (manager.getInputState(input.id)) {
                TvInputManager.INPUT_STATE_CONNECTED -> "connected"
                TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "standby"
                TvInputManager.INPUT_STATE_DISCONNECTED -> "disconnected"
                else -> "unknown"
            }
        } catch (_: RuntimeException) {
            "unavailable"
        }
        return "$label — ${input.id} — $state"
    }

    private companion object {
        const val PREFERENCES_NAME = "launcher_state"
        const val LAST_COMPONENT_KEY = "last_focused_component"
        const val XIAOMI_SETTINGS_PACKAGE = "com.xiaomi.mitv.settings"
    }
}
