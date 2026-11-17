package app.breather

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.reflect.KMutableProperty0

/** Pads the view by the system bars (and the keyboard), on top of its own padding: the app draws edge to edge. */
fun View.padForSystemBars() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
        )
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

/** Adds a "label  − value +" row bound to [value]. */
fun Activity.addStepper(
    parent: LinearLayout,
    label: Int,
    unit: Int,
    range: IntRange,
    step: Int,
    value: KMutableProperty0<Int>,
    onChange: () -> Unit = {},
) {
    val row = LayoutInflater.from(this).inflate(R.layout.item_stepper, parent, false)
    val valueView = row.findViewById<TextView>(R.id.value)
    row.findViewById<TextView>(R.id.label).setText(label)
    fun show() {
        valueView.text = getString(unit, value.get())
    }
    fun change(delta: Int) {
        value.set((value.get() + delta).coerceIn(range))
        show()
        onChange()
    }
    row.findViewById<Button>(R.id.minus).setOnClickListener { change(-step) }
    row.findViewById<Button>(R.id.plus).setOnClickListener { change(step) }
    show()
    parent.addView(row)
}

/** Label and icon of an installed app; falls back to the package name when it isn't installed. */
class AppEntry(val pkg: String, val label: String, val icon: Drawable?) {
    companion object {
        fun load(pm: PackageManager, pkg: String): AppEntry = try {
            val info = pm.getApplicationInfo(pkg, 0)
            AppEntry(pkg, info.loadLabel(pm).toString(), info.loadIcon(pm))
        } catch (_: PackageManager.NameNotFoundException) {
            AppEntry(pkg, pkg, null)
        }
    }
}
