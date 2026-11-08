package sh.rrvsh.eleventwosixty

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

internal data class LaunchableApp(
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
)

internal data class LaunchCandidate(
    val packageName: String,
    val className: String,
    val label: String,
    val leanback: Boolean,
)

internal object AppCatalog {
    fun discover(packageManager: PackageManager, ownPackage: String): List<LaunchableApp> {
        val candidates = ArrayList<LaunchCandidate>()
        collect(packageManager, Intent.CATEGORY_LEANBACK_LAUNCHER, true, ownPackage, candidates)
        collect(packageManager, Intent.CATEGORY_LAUNCHER, false, ownPackage, candidates)

        return preferLeanback(candidates).map {
            LaunchableApp(
                label = it.label,
                component = ComponentName(it.packageName, it.className),
                leanback = it.leanback,
            )
        }
    }

    fun preferLeanback(candidates: List<LaunchCandidate>): List<LaunchCandidate> {
        return candidates
            .groupBy { it.packageName }
            .values
            .map { packageCandidates ->
                packageCandidates.minWithOrNull(
                    compareBy<LaunchCandidate>({ !it.leanback }, { it.className }),
                ) ?: error("A grouped package must contain a candidate")
            }
            .sortedWith(
                compareBy<LaunchCandidate>(
                    { it.label.lowercase(Locale.ROOT) },
                    { it.packageName },
                ),
            )
    }

    private fun collect(
        packageManager: PackageManager,
        category: String,
        leanback: Boolean,
        ownPackage: String,
        destination: MutableList<LaunchCandidate>,
    ) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        for (resolved in packageManager.queryIntentActivities(intent, 0)) {
            val activity = resolved.activityInfo ?: continue
            val application = activity.applicationInfo ?: continue
            if (
                activity.packageName == ownPackage ||
                !activity.enabled ||
                !application.enabled ||
                !activity.exported
            ) {
                continue
            }
            val label = resolved.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            destination += LaunchCandidate(
                packageName = activity.packageName,
                className = activity.name,
                label = label.ifEmpty { activity.packageName },
                leanback = leanback,
            )
        }
    }
}
