package app.breather

/** A site/app that gets a breathing pause before it opens. */
enum class Target(val label: String, private val domains: List<String>, val appPackage: String) {
    INSTAGRAM("Instagram", listOf("instagram.com"), "com.instagram.android"),
    YOUTUBE("YouTube", listOf("youtube.com", "youtu.be"), "com.google.android.youtube");

    private fun matchesHost(host: String) = domains.any { host == it || host.endsWith(".$it") }

    companion object {
        fun forPackage(pkg: String): Target? = entries.firstOrNull { it.appPackage == pkg }

        fun forUrl(raw: CharSequence?): Target? {
            val host = hostOf(raw ?: return null) ?: return null
            return entries.firstOrNull { it.matchesHost(host) }
        }

        /** Extracts the host from what a browser shows in its address bar ("instagram.com/x", "https://m.youtube.com"). */
        fun hostOf(raw: CharSequence): String? {
            val text = raw.toString().trim().lowercase()
            if (text.isEmpty() || text.contains(' ')) return null
            val host = text.substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .removePrefix("www.")
            return host.takeIf { it.contains('.') }
        }
    }
}

/** Why the pause screen is shown. */
enum class Reason { ATTEMPT, EXPIRED, EXHAUSTED }
