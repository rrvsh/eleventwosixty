package sh.rrvsh.eleventwosixty

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

internal class AppGridAdapter(
    context: Context,
    private val packageManager: PackageManager,
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val placeholder = context.getDrawable(R.drawable.ic_app_placeholder)
    private val iconCache = LruCache<String, Drawable>(MAX_ICON_COUNT)
    private var apps: List<LaunchableApp> = emptyList()

    fun replace(newApps: List<LaunchableApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun getCount(): Int = apps.size

    override fun getItem(position: Int): LaunchableApp = apps[position]

    override fun getItemId(position: Int): Long = getItem(position).component.hashCode().toLong()

    override fun hasStableIds(): Boolean = true

    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
        val holder: ViewHolder
        val view: View
        if (recycled == null) {
            view = inflater.inflate(R.layout.app_tile, parent, false)
            holder = ViewHolder(
                icon = view.findViewById(R.id.app_icon),
                label = view.findViewById(R.id.app_label),
            )
            view.tag = holder
        } else {
            view = recycled
            holder = recycled.tag as ViewHolder
        }

        val app = getItem(position)
        holder.label.text = app.label
        holder.icon.setImageDrawable(loadIcon(app))
        return view
    }

    private fun loadIcon(app: LaunchableApp): Drawable? {
        val key = app.component.flattenToString()
        iconCache.get(key)?.let { return it }
        val icon = try {
            packageManager.getActivityIcon(app.component)
        } catch (_: PackageManager.NameNotFoundException) {
            placeholder
        } catch (_: RuntimeException) {
            placeholder
        }
        if (icon != null) {
            iconCache.put(key, icon)
        }
        return icon
    }

    private data class ViewHolder(
        val icon: ImageView,
        val label: TextView,
    )

    private companion object {
        const val MAX_ICON_COUNT = 48
    }
}
