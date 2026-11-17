package app.breather

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Creates or edits a rule: name, sites, apps and its limits. */
class RuleActivity : Activity() {

    private lateinit var store: Store
    private var existing: Rule? = null
    private lateinit var nameInput: EditText
    private lateinit var sitesInput: EditText
    private lateinit var appList: LinearLayout
    private var packages = listOf<String>()
    private var budgetMinutes = Rule.DEFAULT_BUDGET
    private var sessionMinutes = Rule.DEFAULT_SESSION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule)
        findViewById<View>(R.id.root).padForSystemBars()
        store = Store(this)
        existing = intent.getStringExtra(EXTRA_RULE)?.let(store::rule)

        nameInput = findViewById(R.id.name)
        sitesInput = findViewById(R.id.sites)
        appList = findViewById(R.id.app_list)
        findViewById<TextView>(R.id.title).setText(if (existing == null) R.string.rule_new else R.string.rule_edit)

        existing?.let { rule ->
            nameInput.setText(rule.name)
            sitesInput.setText(rule.domains.joinToString("\n"))
            packages = rule.packages
            budgetMinutes = rule.budgetMinutes
            sessionMinutes = rule.sessionMinutes
        }
        savedInstanceState?.let { state ->
            state.getStringArray(STATE_PACKAGES)?.let { packages = it.toList() }
            budgetMinutes = state.getInt(STATE_BUDGET, budgetMinutes)
            sessionMinutes = state.getInt(STATE_SESSION, sessionMinutes)
        }

        val limits = findViewById<LinearLayout>(R.id.limits)
        addStepper(limits, R.string.rule_budget, R.string.unit_minutes, 5..240, 5, ::budgetMinutes)
        addStepper(limits, R.string.rule_session, R.string.unit_minutes, 1..60, 1, ::sessionMinutes)

        findViewById<Button>(R.id.choose_apps).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_PACKAGES, packages.toTypedArray()),
                REQUEST_APPS,
            )
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.delete).apply {
            visibility = if (existing == null) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete() }
        }
        showApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_PACKAGES, packages.toTypedArray())
        outState.putInt(STATE_BUDGET, budgetMinutes)
        outState.putInt(STATE_SESSION, sessionMinutes)
    }

    @Deprecated("Uses the platform result API; there is no AndroidX in this app")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_APPS && resultCode == RESULT_OK) {
            packages = data?.getStringArrayExtra(AppPickerActivity.EXTRA_PACKAGES)?.toList().orEmpty()
            showApps()
        }
    }

    private fun showApps() {
        appList.removeAllViews()
        if (packages.isEmpty()) {
            appList.addView(
                TextView(this).apply {
                    setText(R.string.rule_apps_none)
                    setTextColor(getColor(R.color.muted))
                    textSize = 15f
                },
            )
            return
        }
        val inflater = LayoutInflater.from(this)
        for (pkg in packages) {
            val app = AppEntry.load(packageManager, pkg)
            val row = inflater.inflate(R.layout.item_app, appList, false)
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.label).text = app.label
            row.findViewById<View>(R.id.check).visibility = View.GONE
            appList.addView(row)
        }
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        val domains = sitesInput.text.split(Regex("[\\s,]+")).mapNotNull(Urls::hostOf).distinct()
        if (name.isEmpty() || (domains.isEmpty() && packages.isEmpty())) {
            Toast.makeText(this, R.string.rule_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val base = existing ?: Rule(name = name)
        store.saveRule(
            base.copy(
                name = name,
                domains = domains,
                packages = packages,
                budgetMinutes = budgetMinutes,
                sessionMinutes = sessionMinutes,
            ),
        )
        finish()
    }

    private fun confirmDelete() {
        val rule = existing ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.rule_delete_confirm, rule.name))
            .setPositiveButton(R.string.rule_delete) { _, _ ->
                store.deleteRule(rule.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_RULE = "rule"
        private const val STATE_PACKAGES = "packages"
        private const val STATE_BUDGET = "budget"
        private const val STATE_SESSION = "session"
        private const val REQUEST_APPS = 1
    }
}
