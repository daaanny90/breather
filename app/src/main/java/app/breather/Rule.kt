package app.breather

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sites and apps that share a daily budget and a session length. */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val domains: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val budgetMinutes: Int = DEFAULT_BUDGET,
    val sessionMinutes: Int = DEFAULT_SESSION,
) {
    fun matchesHost(host: String) = domains.any { host == it || host.endsWith(".$it") }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("domains", JSONArray(domains))
        .put("packages", JSONArray(packages))
        .put("budget", budgetMinutes)
        .put("session", sessionMinutes)

    companion object {
        const val DEFAULT_BUDGET = 15
        const val DEFAULT_SESSION = 10

        fun fromJson(json: JSONObject) = Rule(
            id = json.getString("id"),
            name = json.getString("name"),
            domains = json.optJSONArray("domains").toStringList(),
            packages = json.optJSONArray("packages").toStringList(),
            budgetMinutes = json.optInt("budget", DEFAULT_BUDGET),
            sessionMinutes = json.optInt("session", DEFAULT_SESSION),
        )

        /** The two rules a fresh install starts with. */
        fun defaults() = listOf(
            Rule(
                name = "Instagram",
                domains = listOf("instagram.com"),
                packages = listOf("com.instagram.android"),
            ),
            Rule(
                name = "YouTube",
                domains = listOf("youtube.com", "youtu.be"),
                packages = listOf("com.google.android.youtube"),
            ),
        )

        private fun JSONArray?.toStringList(): List<String> =
            if (this == null) emptyList() else List(length()) { getString(it) }
    }
}

fun List<Rule>.forPackage(pkg: String): Rule? = firstOrNull { pkg in it.packages }

fun List<Rule>.forUrl(raw: CharSequence?): Rule? {
    val host = raw?.let(Urls::hostOf) ?: return null
    return firstOrNull { it.matchesHost(host) }
}

object Urls {
    /**
     * Extracts the host from what a browser shows in its address bar ("instagram.com/x",
     * "https://m.youtube.com") or what the user types as a site ("www.reddit.com/r/x").
     */
    fun hostOf(raw: CharSequence): String? {
        val text = raw.toString().trim().lowercase()
        if (text.isEmpty() || text.contains(' ')) return null
        val host = text.substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removePrefix("www.")
        return host.takeIf { it.contains('.') && !it.startsWith('.') && !it.endsWith('.') }
    }
}
