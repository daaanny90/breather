package app.breather

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the foreground app. When an app or a site (in a known browser) from one of the rules is
 * open without an active session, it shows [PauseActivity]. Time inside a session counts against
 * that rule's daily budget.
 */
class BreatherService : AccessibilityService() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())

    /** Id of the rule last seen in each browser's address bar; kept while the bar is hidden by scrolling. */
    private val lastUrlRule = mutableMapOf<String, String?>()
    private var currentId: String? = null
    private var trackingSince = 0L
    private var screenOn = true
    private var lastEval = 0L
    private var lastLaunch = 0L
    private var lastLog: String? = null

    private val tick = object : Runnable {
        override fun run() {
            val rule = current() ?: return setCurrent(null)
            flush()
            check(rule, inSession = true)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Stops counting and the session check until the next window event after unlock.
                    setCurrent(null)
                    screenOn = false
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    trackingSince = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    override fun onServiceConnected() {
        store = Store(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!Browsers.isBrowser(pkg)) return
            if (SystemClock.elapsedRealtime() - lastEval < CONTENT_THROTTLE_MS) return
        }
        evaluate()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::store.isInitialized) flush()
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    private fun current(): Rule? = currentId?.let(store::rule)

    private fun evaluate() {
        lastEval = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow ?: return
        val fg = root.packageName?.toString() ?: return
        val rules = store.rules()
        val rule = when {
            fg == packageName -> null
            else -> rules.forPackage(fg) ?: if (Browsers.isBrowser(fg)) browserRule(fg, root, rules) else null
        }
        logChange(fg, rule)
        val wasInside = rule != null && rule.id == currentId
        setCurrent(rule)
        if (rule != null) check(rule, inSession = wasInside)
    }

    private fun browserRule(pkg: String, root: AccessibilityNodeInfo, rules: List<Rule>): Rule? {
        val bar = Browsers.readUrlBar(pkg, root)
        // A focused bar holds what the user is typing, not the page address.
        if (bar != null && !bar.focused) {
            val id = rules.forUrl(bar.address)?.id
            if (lastUrlRule[pkg] != id || pkg !in lastUrlRule) {
                Log.d(TAG, "url[$pkg] ${bar.address?.let(Urls::hostOf)} -> ${id ?: "-"}")
            }
            lastUrlRule[pkg] = id
        }
        return lastUrlRule[pkg]?.let { id -> rules.firstOrNull { it.id == id } }
    }

    private fun setCurrent(rule: Rule?) {
        if (rule?.id == currentId) return
        flush()
        currentId = rule?.id
        handler.removeCallbacks(tick)
        if (rule != null) handler.postDelayed(tick, TICK_MS)
    }

    /** Adds the time spent on the current rule during an open session to its total for today. */
    private fun flush() {
        val now = SystemClock.elapsedRealtime()
        val rule = current()
        if (rule != null && screenOn && store.grantUntil(rule) > 0) {
            store.addUsed(rule, now - trackingSince)
        }
        trackingSince = now
    }

    private fun check(rule: Rule, inSession: Boolean) {
        val until = store.grantUntil(rule)
        val reason = when {
            store.remainingMs(rule) <= 0 -> Reason.EXHAUSTED
            System.currentTimeMillis() < until -> return
            inSession && until > 0 -> Reason.EXPIRED
            else -> Reason.ATTEMPT
        }
        launchPause(rule, reason, countAttempt = !inSession)
    }

    private fun launchPause(rule: Rule, reason: Reason, countAttempt: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (PauseActivity.isShowing || now - lastLaunch < LAUNCH_DEBOUNCE_MS) return
        lastLaunch = now
        store.clearGrant(rule)
        val attempt = if (countAttempt) store.addAttempt(rule) else store.attempts(rule)
        Log.i(TAG, "pause ${rule.name} $reason attempt=$attempt")
        startActivity(PauseActivity.intent(this, rule, reason, attempt))
    }

    private fun logChange(fg: String, rule: Rule?) {
        val line = "$fg -> ${rule?.name ?: "-"}"
        if (line != lastLog) {
            Log.i(TAG, line)
            lastLog = line
        }
    }

    private companion object {
        const val TAG = "Breather"
        const val TICK_MS = 5_000L
        const val CONTENT_THROTTLE_MS = 400L
        const val LAUNCH_DEBOUNCE_MS = 1_500L
    }
}
