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
 * Watches the foreground app. When Instagram or YouTube is open (native app or a site in a known
 * browser) without an active session, it shows [PauseActivity]. Time inside a session counts
 * against the daily budget.
 */
class BreatherService : AccessibilityService() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())

    /** Last target seen in each browser's address bar; kept while the bar is hidden by scrolling. */
    private val lastUrlTarget = mutableMapOf<String, Target?>()
    private var current: Target? = null
    private var trackingSince = 0L
    private var screenOn = true
    private var lastEval = 0L
    private var lastLaunch = 0L
    private var lastLog: String? = null

    private val tick = object : Runnable {
        override fun run() {
            val target = current ?: return
            flush()
            check(target, inSession = true)
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

    private fun evaluate() {
        lastEval = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow ?: return
        val fg = root.packageName?.toString() ?: return
        val target = when {
            fg == packageName -> null
            Browsers.isBrowser(fg) -> browserTarget(fg, root)
            else -> Target.forPackage(fg)
        }
        logChange(fg, target)
        val wasInside = target != null && target == current
        setCurrent(target)
        if (target != null) check(target, inSession = wasInside)
    }

    private fun browserTarget(pkg: String, root: AccessibilityNodeInfo): Target? {
        val bar = Browsers.readUrlBar(pkg, root)
        // A focused bar holds what the user is typing, not the page address.
        if (bar != null && !bar.focused) {
            val target = Target.forUrl(bar.address)
            if (lastUrlTarget[pkg] != target || pkg !in lastUrlTarget) {
                Log.d(TAG, "url[$pkg] ${bar.address?.let(Target::hostOf)} -> ${target ?: "-"}")
            }
            lastUrlTarget[pkg] = target
        }
        return lastUrlTarget[pkg]
    }

    private fun setCurrent(target: Target?) {
        if (target == current) return
        flush()
        current = target
        handler.removeCallbacks(tick)
        if (target != null) handler.postDelayed(tick, TICK_MS)
    }

    /** Adds the time spent on the current target during an open session to today's total. */
    private fun flush() {
        val now = SystemClock.elapsedRealtime()
        val target = current
        if (target != null && screenOn && store.grantUntil(target) > 0) {
            store.addUsed(now - trackingSince)
        }
        trackingSince = now
    }

    private fun check(target: Target, inSession: Boolean) {
        val until = store.grantUntil(target)
        val reason = when {
            store.remainingMs() <= 0 -> Reason.EXHAUSTED
            System.currentTimeMillis() < until -> return
            inSession && until > 0 -> Reason.EXPIRED
            else -> Reason.ATTEMPT
        }
        launchPause(target, reason, countAttempt = !inSession)
    }

    private fun launchPause(target: Target, reason: Reason, countAttempt: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (PauseActivity.isShowing || now - lastLaunch < LAUNCH_DEBOUNCE_MS) return
        lastLaunch = now
        store.clearGrant(target)
        val attempt = if (countAttempt) store.addAttempt(target) else store.attempts(target)
        Log.i(TAG, "pause $target $reason attempt=$attempt")
        startActivity(PauseActivity.intent(this, target, reason, attempt))
    }

    private fun logChange(fg: String, target: Target?) {
        val line = "$fg -> ${target ?: "-"}"
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
