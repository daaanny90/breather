package app.breather

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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

    /** Browser showing the current rule's site; null when the rule matched an app. */
    private var currentBrowser: String? = null

    /** Browser whose tab is being moved off a site; no pause there until that's done. */
    private var closingIn: String? = null
    private var trackingSince = 0L
    private var screenOn = true
    private var lastEval = 0L
    private var lastLaunch = 0L
    private var lastLog: String? = null

    private val tick = object : Runnable {
        override fun run() {
            val rule = current() ?: return setCurrent(null)
            flush()
            check(rule, inSession = true, browser = currentBrowser)
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
        val appRule = if (fg == packageName) null else rules.forPackage(fg)
        val browser = fg.takeIf { appRule == null && Browsers.isBrowser(it) }
        val rule = appRule ?: browser?.let { browserRule(it, root, rules) }
        logChange(fg, rule)
        val wasInside = rule != null && rule.id == currentId
        setCurrent(rule)
        currentBrowser = browser.takeIf { rule != null }
        when {
            rule == null -> Unit
            browser != null && PauseActivity.takeTabToClose(browser) -> closeTab(browser, rule, root)
            else -> check(rule, inSession = wasInside, browser = browser)
        }
    }

    /**
     * Moves the browser's tab off the rule's site by typing a blank page into the address bar, so
     * the browser stays usable. If the tab is still on the site afterwards, the pause comes back
     * and sends the user home instead.
     */
    private fun closeTab(browser: String, rule: Rule, root: AccessibilityNodeInfo) {
        closingIn = browser
        val bar = Browsers.findUrlBar(browser, root)
        val tapped = bar != null &&
            (bar.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                tap(bar))
        Log.i(TAG, "close tab[$browser] tapped=$tapped")
        handler.postDelayed({ typeBlankPage(browser) }, TYPE_DELAY_MS)
        handler.postDelayed({ checkTabClosed(browser, rule) }, CLOSE_TAB_MS)
    }

    /** Taps the node's center like a finger, for bars without a click action (Firefox). */
    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun typeBlankPage(browser: String) {
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == browser } ?: return
        val field = Browsers.findTypingField(root)
        if (field == null) {
            Log.w(TAG, "close tab[$browser] no focused field")
            return
        }
        val text = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, BLANK_PAGE)
        }
        val typed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)
        val sent = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        Log.i(TAG, "close tab[$browser] field=${field.viewIdResourceName} typed=$typed sent=$sent")
    }

    private fun checkTabClosed(browser: String, rule: Rule) {
        closingIn = null
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == browser } ?: return
        if (browserRule(browser, root, store.rules())?.id != rule.id) return
        Log.w(TAG, "close tab[$browser] failed, pausing with home as the way out")
        check(rule, inSession = true, browser = null)
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

    /** [browser] is set when the rule's site is open there, so the pause can close just its tab. */
    private fun check(rule: Rule, inSession: Boolean, browser: String?) {
        val until = store.grantUntil(rule)
        val reason = when {
            store.remainingMs(rule) <= 0 -> Reason.EXHAUSTED
            System.currentTimeMillis() < until -> return
            inSession && until > 0 -> Reason.EXPIRED
            else -> Reason.ATTEMPT
        }
        launchPause(rule, reason, countAttempt = !inSession, browser)
    }

    private fun launchPause(rule: Rule, reason: Reason, countAttempt: Boolean, browser: String?) {
        val now = SystemClock.elapsedRealtime()
        if (PauseActivity.isShowing || now - lastLaunch < LAUNCH_DEBOUNCE_MS) return
        if (browser != null && browser == closingIn) return
        lastLaunch = now
        store.clearGrant(rule)
        val attempt = if (countAttempt) store.addAttempt(rule) else store.attempts(rule)
        Log.i(TAG, "pause ${rule.name} $reason attempt=$attempt browser=${browser ?: "-"}")
        startActivity(PauseActivity.intent(this, rule, reason, attempt, browser))
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
        const val TAP_MS = 50L
        const val TYPE_DELAY_MS = 400L
        const val CLOSE_TAB_MS = 2_000L
        const val BLANK_PAGE = "about:blank"
    }
}
