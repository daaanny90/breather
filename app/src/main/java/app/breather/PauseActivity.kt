package app.breather

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView

/** Why the pause screen is shown. */
enum class Reason { ATTEMPT, EXPIRED, EXHAUSTED }

/** Full-screen breathing pause shown before an app or site from a rule opens. */
class PauseActivity : Activity() {

    private lateinit var store: Store
    private lateinit var rule: Rule
    private lateinit var breath: BreathView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var open: Button
    private var asked = false

    /** Browser package when the pause covers a site, null when it covers an app. */
    private var browser: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pause)
        findViewById<View>(R.id.root).padForSystemBars()

        store = Store(this)
        rule = intent.getStringExtra(EXTRA_RULE)?.let(store::rule) ?: return finish()
        val reason = intent.getStringExtra(EXTRA_REASON)
            ?.let { name -> Reason.entries.firstOrNull { it.name == name } }
            ?: Reason.ATTEMPT
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        browser = intent.getStringExtra(EXTRA_BROWSER)

        breath = findViewById(R.id.breath)
        headline = findViewById(R.id.headline)
        detail = findViewById(R.id.detail)
        open = findViewById(R.id.open)
        findViewById<TextView>(R.id.eyebrow).text = rule.name

        val leave = findViewById<Button>(R.id.leave)
        leave.setOnClickListener { leave() }
        open.setOnClickListener { openSession() }

        if (reason == Reason.EXHAUSTED) {
            showExhausted(leave)
        } else {
            breathe(reason, attempt)
        }
        if (browser != null) leave.text = getString(R.string.close_tab)
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
    }

    override fun onPause() {
        super.onPause()
        isShowing = false
    }

    @Deprecated("Back always leaves, like the leave button")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = leave()

    private fun breathe(reason: Reason, attempt: Int) {
        headline.text = getString(R.string.inhale)
        breath.onPhase = { inhale ->
            if (!asked) headline.crossfadeTo(getString(if (inhale) R.string.inhale else R.string.exhale))
        }
        breath.onDone = { ask(reason, attempt) }
        breath.start(store.pauseSeconds * 1_000L)
    }

    private fun ask(reason: Reason, attempt: Int) {
        asked = true
        val usedMinutes = (store.usedMs(rule) / 60_000L).toInt()
        if (reason == Reason.EXPIRED) {
            val minutes = rule.sessionMinutes
            headline.crossfadeTo(resources.getQuantityString(R.plurals.question_expired, minutes, minutes, rule.name))
            detail.text = getString(R.string.detail_session, usedMinutes, rule.budgetMinutes)
        } else {
            headline.crossfadeTo(getString(R.string.question, rule.name))
            detail.text = getString(R.string.detail_attempt, attempt, usedMinutes, rule.budgetMinutes)
        }
        detail.fadeIn()
        open.text = getString(R.string.open_for, sessionMinutes())
        open.fadeIn()
    }

    private fun showExhausted(leave: Button) {
        asked = true
        breath.start(0)
        headline.text = getString(R.string.exhausted_title)
        detail.text = getString(R.string.exhausted_detail, rule.budgetMinutes, rule.name)
        detail.visibility = View.VISIBLE
        leave.text = getString(R.string.close)
        open.visibility = View.GONE
    }

    /** Session length, capped by what's left of the rule's budget for today. */
    private fun sessionMinutes(): Int {
        val remaining = (store.remainingMs(rule) / 60_000L).toInt()
        return minOf(rule.sessionMinutes, remaining).coerceAtLeast(1)
    }

    private fun openSession() {
        store.grant(rule, System.currentTimeMillis() + sessionMinutes() * 60_000L)
        finish()
    }

    /**
     * Leaves an app for the home screen. A site only loses its tab: going home would leave the tab
     * open, and the pause would come back each time the browser opens.
     */
    private fun leave() {
        val browser = browser ?: return goHome()
        tabToClose = browser
        tabToCloseAt = SystemClock.elapsedRealtime()
        // Back to the browser, where BreatherService moves the tab off the site.
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        @Volatile
        var isShowing = false
            private set

        private var tabToClose: String? = null
        private var tabToCloseAt = 0L

        private const val EXTRA_RULE = "rule"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_ATTEMPT = "attempt"
        private const val EXTRA_BROWSER = "browser"
        private const val CLOSE_REQUEST_MS = 3_000L

        /** True once if, moments ago, the user chose to close the site's tab in [browser]. */
        fun takeTabToClose(browser: String): Boolean {
            val asked = tabToClose == browser &&
                SystemClock.elapsedRealtime() - tabToCloseAt < CLOSE_REQUEST_MS
            if (asked) tabToClose = null
            return asked
        }

        fun intent(context: Context, rule: Rule, reason: Reason, attempt: Int, browser: String?): Intent =
            Intent(context, PauseActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
                .putExtra(EXTRA_RULE, rule.id)
                .putExtra(EXTRA_REASON, reason.name)
                .putExtra(EXTRA_ATTEMPT, attempt)
                .putExtra(EXTRA_BROWSER, browser)
    }
}
