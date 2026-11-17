package app.breather

import android.view.accessibility.AccessibilityNodeInfo

/** What a browser's address bar currently shows. */
class UrlBar(val address: CharSequence?, val focused: Boolean)

/** Where each supported browser keeps its address bar in the accessibility tree. */
object Browsers {

    /**
     * [viewId] is the bar's resource id. Firefox's Compose toolbar exposes a test tag instead and
     * keeps the address in the content description (" instagram.com. Suche oder Adresse").
     */
    private class Spec(val viewId: String, val fromDescription: Boolean = false)

    private val specs = mapOf(
        "com.vivaldi.browser" to Spec("com.vivaldi.browser:id/url_bar"),
        "com.android.chrome" to Spec("com.android.chrome:id/url_bar"),
        "com.brave.browser" to Spec("com.brave.browser:id/url_bar"),
        "org.mozilla.firefox" to Spec("ADDRESSBAR_URL_BOX", fromDescription = true),
        // Not installed on the test phone, id taken from Samsung Internet's known layout.
        "com.sec.android.app.sbrowser" to Spec("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
    )

    private const val MAX_NODES = 600

    fun isBrowser(pkg: String) = pkg in specs

    /** Returns the address bar, or null when it isn't on screen (e.g. hidden while scrolling). */
    fun readUrlBar(pkg: String, root: AccessibilityNodeInfo): UrlBar? {
        val spec = specs[pkg] ?: return null
        val node = root.findAccessibilityNodeInfosByViewId(spec.viewId).firstOrNull()
            ?: findByIdBreadthFirst(root, spec.viewId)
            ?: return null
        val address = if (spec.fromDescription) {
            node.contentDescription?.toString()?.trim()?.substringBefore(' ')?.trimEnd('.')
        } else {
            node.text
        }
        return UrlBar(address, node.isFocused)
    }

    /** Fallback for ids the framework lookup can't resolve (Compose test tags); toolbars sit near the root. */
    private fun findByIdBreadthFirst(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (node.viewIdResourceName == viewId) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }
}
