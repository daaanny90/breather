package app.breather

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Status, the rules with today's usage, and the pause length. */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var ruleList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()
        store = Store(this)
        ruleList = findViewById(R.id.rule_list)

        findViewById<Button>(R.id.enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.battery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.add_rule).setOnClickListener {
            startActivity(Intent(this, RuleActivity::class.java))
        }
        addStepper(findViewById(R.id.settings), R.string.rule_pause, R.string.unit_seconds, 5..60, 5, store::pauseSeconds)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        findViewById<TextView>(R.id.status).apply {
            setText(if (enabled) R.string.status_on else R.string.status_off)
            setTextColor(getColor(if (enabled) R.color.accent else R.color.warn))
        }
        findViewById<TextView>(R.id.status_hint)
            .setText(if (enabled) R.string.status_hint_on else R.string.status_hint_off)
        findViewById<View>(R.id.enable).visibility = if (enabled) View.GONE else View.VISIBLE
        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<View>(R.id.battery).visibility = if (unrestricted) View.GONE else View.VISIBLE
        showRules()
    }

    private fun showRules() {
        ruleList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (rule in store.rules()) {
            val row = inflater.inflate(R.layout.item_rule, ruleList, false)
            val usedSeconds = (store.usedMs(rule) / 1_000L).toInt()
            row.findViewById<TextView>(R.id.name).text = rule.name
            val attempts = store.attempts(rule)
            row.findViewById<TextView>(R.id.usage).text = resources.getQuantityString(
                R.plurals.rule_usage, attempts, usedSeconds / 60, rule.budgetMinutes, attempts,
            )
            row.findViewById<ProgressBar>(R.id.bar).apply {
                max = rule.budgetMinutes * 60
                progress = usedSeconds
            }
            row.setOnClickListener {
                startActivity(Intent(this, RuleActivity::class.java).putExtra(RuleActivity.EXTRA_RULE, rule.id))
            }
            ruleList.addView(row)
        }
    }

    private fun isServiceEnabled(): Boolean {
        val mine = ComponentName(this, BreatherService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
        )
    }
}
