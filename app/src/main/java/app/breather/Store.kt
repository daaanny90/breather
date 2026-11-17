package app.breather

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Settings plus today's counters (time used, attempts, open sessions), reset at midnight. */
class Store(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("breather", Context.MODE_PRIVATE)

    var pauseSeconds: Int
        get() = prefs.getInt(KEY_PAUSE, 15)
        set(value) = prefs.edit().putInt(KEY_PAUSE, value).apply()

    var budgetMinutes: Int
        get() = prefs.getInt(KEY_BUDGET, 30)
        set(value) = prefs.edit().putInt(KEY_BUDGET, value).apply()

    var sessionMinutes: Int
        get() = prefs.getInt(KEY_SESSION, 10)
        set(value) = prefs.edit().putInt(KEY_SESSION, value).apply()

    fun usedMs(): Long = today().getLong(KEY_USED, 0L)

    fun addUsed(ms: Long) {
        if (ms <= 0) return
        prefs.edit().putLong(KEY_USED, usedMs() + ms).apply()
    }

    fun remainingMs(): Long = (budgetMinutes * 60_000L - usedMs()).coerceAtLeast(0L)

    fun attempts(target: Target): Int = today().getInt(attemptsKey(target), 0)

    fun addAttempt(target: Target): Int {
        val count = attempts(target) + 1
        prefs.edit().putInt(attemptsKey(target), count).apply()
        return count
    }

    /** Wall-clock end of the session opened from the pause screen, or 0 when there is none. */
    fun grantUntil(target: Target): Long = today().getLong(grantKey(target), 0L)

    fun grant(target: Target, untilMs: Long) = prefs.edit().putLong(grantKey(target), untilMs).apply()

    fun clearGrant(target: Target) = prefs.edit().remove(grantKey(target)).apply()

    private fun today(): SharedPreferences {
        val day = LocalDate.now().toString()
        if (prefs.getString(KEY_DAY, null) != day) {
            prefs.edit().apply {
                putString(KEY_DAY, day)
                remove(KEY_USED)
                Target.entries.forEach {
                    remove(attemptsKey(it))
                    remove(grantKey(it))
                }
            }.apply()
        }
        return prefs
    }

    private fun attemptsKey(target: Target) = "attempts_${target.name}"
    private fun grantKey(target: Target) = "grant_${target.name}"

    private companion object {
        const val KEY_PAUSE = "pause_seconds"
        const val KEY_BUDGET = "budget_minutes"
        const val KEY_SESSION = "session_minutes"
        const val KEY_DAY = "day"
        const val KEY_USED = "used_ms"
    }
}
