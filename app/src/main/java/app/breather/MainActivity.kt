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

/** Status, today's numbers and the three rules. */
class MainActivity : Activity() {

    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()
        store = Store(this)

        findViewById<Button>(R.id.enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.battery).setOnClickListener { requestBatteryExemption() }

        val rules = findViewById<LinearLayout>(R.id.rules)
        addStepper(rules, R.string.rule_pause, R.string.unit_seconds, 5..60, 5, store::pauseSeconds)
        addStepper(rules, R.string.rule_budget, R.string.unit_minutes, 5..180, 5, store::budgetMinutes)
        addStepper(rules, R.string.rule_session, R.string.unit_minutes, 1..30, 1, store::sessionMinutes)
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

        val usedSeconds = (store.usedMs() / 1_000L).toInt()
        findViewById<TextView>(R.id.used).text =
            getString(R.string.used, usedSeconds / 60, store.budgetMinutes)
        findViewById<ProgressBar>(R.id.usage).apply {
            max = store.budgetMinutes * 60
            progress = usedSeconds
        }
        findViewById<TextView>(R.id.attempts).text =
            getString(R.string.attempts, store.attempts(Target.INSTAGRAM), store.attempts(Target.YOUTUBE))
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

    private fun addStepper(
        parent: LinearLayout,
        label: Int,
        unit: Int,
        range: IntRange,
        step: Int,
        value: kotlin.reflect.KMutableProperty0<Int>,
    ) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_stepper, parent, false)
        val valueView = row.findViewById<TextView>(R.id.value)
        row.findViewById<TextView>(R.id.label).setText(label)
        fun show() {
            valueView.text = getString(unit, value.get())
        }
        fun change(delta: Int) {
            value.set((value.get() + delta).coerceIn(range))
            show()
            refresh()
        }
        row.findViewById<Button>(R.id.minus).setOnClickListener { change(-step) }
        row.findViewById<Button>(R.id.plus).setOnClickListener { change(step) }
        show()
        parent.addView(row)
    }
}
