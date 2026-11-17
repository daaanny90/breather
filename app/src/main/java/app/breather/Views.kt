package app.breather

import android.view.View
import android.view.WindowInsets
import android.widget.TextView

/** Pads the view by the system bars, on top of its own padding (the app draws edge to edge). */
fun View.padForSystemBars() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
}

fun TextView.crossfadeTo(newText: CharSequence) {
    if (text == newText) return
    animate().alpha(0f).setDuration(180).withEndAction {
        text = newText
        animate().alpha(1f).setDuration(320).start()
    }.start()
}

fun View.fadeIn() {
    alpha = 0f
    visibility = View.VISIBLE
    animate().alpha(1f).setDuration(450).start()
}
