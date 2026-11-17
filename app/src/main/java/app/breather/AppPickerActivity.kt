package app.breather

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/** Lists the launchable apps with a checkbox each; returns the checked packages. */
class AppPickerActivity : Activity() {

    private val selected = linkedSetOf<String>()
    private var apps = listOf<AppEntry>()
    private var shown = listOf<AppEntry>()
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        findViewById<View>(R.id.root).padForSystemBars()

        val initial = savedInstanceState?.getStringArray(EXTRA_PACKAGES)
            ?: intent.getStringArrayExtra(EXTRA_PACKAGES)
        selected += initial.orEmpty()

        apps = launchableApps()
        shown = apps
        adapter = AppAdapter()
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val pkg = shown[position].pkg
            if (!selected.remove(pkg)) selected += pkg
            adapter.notifyDataSetChanged()
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                shown = if (query.isEmpty()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
                adapter.notifyDataSetChanged()
            }
        })

        findViewById<Button>(R.id.done).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGES, selected.toTypedArray()))
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(EXTRA_PACKAGES, selected.toTypedArray())
    }

    /**
     * Home screens and Settings are left out. A paused home screen would loop forever, since leaving
     * the pause goes home. A paused Settings app could lock the user out of turning the service off.
     */
    private fun launchableApps(): List<AppEntry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val excluded = packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet() +
            packageName + SETTINGS_PACKAGE
        return packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .filter { it !in excluded }
            .distinct()
            .map { AppEntry.load(packageManager, it) }
            .sortedBy { it.label.lowercase() }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.label).text = app.label
            row.findViewById<CheckBox>(R.id.check).isChecked = app.pkg in selected
            return row
        }
    }

    companion object {
        const val EXTRA_PACKAGES = "packages"
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
