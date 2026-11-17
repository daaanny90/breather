package app.breather

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.time.LocalDate

/** Settings, rules and each rule's counters for today (time used, attempts, open session). */
class Store(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("breather", Context.MODE_PRIVATE)
    private var cachedJson: String? = null
    private var cachedRules: List<Rule> = emptyList()

    var pauseSeconds: Int
        get() = prefs.getInt(KEY_PAUSE, 15)
        set(value) = prefs.edit().putInt(KEY_PAUSE, value).apply()

    fun rules(): List<Rule> {
        val json = prefs.getString(KEY_RULES, null) ?: return migrate()
        if (json != cachedJson) {
            val array = JSONArray(json)
            cachedRules = List(array.length()) { Rule.fromJson(array.getJSONObject(it)) }
            cachedJson = json
        }
        return cachedRules
    }

    fun rule(id: String): Rule? = rules().firstOrNull { it.id == id }

    fun saveRule(rule: Rule) {
        val rules = rules().toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules += rule
        writeRules(rules)
    }

    fun deleteRule(id: String) {
        writeRules(rules().filterNot { it.id == id })
        prefs.edit().remove(usedKey(id)).remove(attemptsKey(id)).remove(grantKey(id)).apply()
    }

    fun usedMs(rule: Rule): Long = today().getLong(usedKey(rule.id), 0L)

    fun addUsed(rule: Rule, ms: Long) {
        if (ms <= 0) return
        prefs.edit().putLong(usedKey(rule.id), usedMs(rule) + ms).apply()
    }

    fun remainingMs(rule: Rule): Long = (rule.budgetMinutes * 60_000L - usedMs(rule)).coerceAtLeast(0L)

    fun attempts(rule: Rule): Int = today().getInt(attemptsKey(rule.id), 0)

    fun addAttempt(rule: Rule): Int {
        val count = attempts(rule) + 1
        prefs.edit().putInt(attemptsKey(rule.id), count).apply()
        return count
    }

    /** Wall-clock end of the session opened from the pause screen, or 0 when there is none. */
    fun grantUntil(rule: Rule): Long = today().getLong(grantKey(rule.id), 0L)

    fun grant(rule: Rule, untilMs: Long) = prefs.edit().putLong(grantKey(rule.id), untilMs).apply()

    fun clearGrant(rule: Rule) = prefs.edit().remove(grantKey(rule.id)).apply()

    private fun writeRules(rules: List<Rule>) {
        prefs.edit().putString(KEY_RULES, JSONArray(rules.map { it.toJson() }).toString()).apply()
    }

    /** 0.1 had Instagram and YouTube built in, sharing one budget. They become the first two rules. */
    private fun migrate(): List<Rule> {
        val session = prefs.getInt(LEGACY_SESSION, Rule.DEFAULT_SESSION)
        val rules = Rule.defaults().map { it.copy(sessionMinutes = session) }
        prefs.edit().apply {
            prefs.all.keys.filter { key -> LEGACY_KEYS.any { key.startsWith(it) } }.forEach { remove(it) }
        }.apply()
        writeRules(rules)
        return rules
    }

    private fun today(): SharedPreferences {
        val day = LocalDate.now().toString()
        if (prefs.getString(KEY_DAY, null) != day) {
            prefs.edit().apply {
                putString(KEY_DAY, day)
                prefs.all.keys.filter { key -> DAILY_PREFIXES.any { key.startsWith(it) } }.forEach { remove(it) }
            }.apply()
        }
        return prefs
    }

    private fun usedKey(id: String) = "used_$id"
    private fun attemptsKey(id: String) = "attempts_$id"
    private fun grantKey(id: String) = "grant_$id"

    private companion object {
        const val KEY_PAUSE = "pause_seconds"
        const val KEY_RULES = "rules"
        const val KEY_DAY = "day"
        const val LEGACY_SESSION = "session_minutes"
        val DAILY_PREFIXES = listOf("used_", "attempts_", "grant_")
        val LEGACY_KEYS = listOf("budget_minutes", "session_minutes", "used_ms", "attempts_", "grant_")
    }
}
