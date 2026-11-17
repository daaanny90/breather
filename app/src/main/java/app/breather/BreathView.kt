package app.breather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * A circle that grows on the inhale and shrinks on the exhale, with a ring that fills up over
 * [start]'s duration. Calls [onPhase] when the breath turns and [onDone] once the ring is full.
 */
class BreathView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onPhase: ((inhale: Boolean) -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent_soft) }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = context.getColor(R.color.line)
    }
    private val progressArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.ink)
    }
    private val arcBounds = RectF()

    private var startedAt = 0L
    private var durationMs = 0L
    private var running = false
    private var done = false
    private var lastInhale: Boolean? = null

    /** Starts breathing; with [durationMs] 0 there is no progress ring. */
    fun start(durationMs: Long) {
        this.durationMs = durationMs
        startedAt = SystemClock.uptimeMillis()
        running = true
        done = durationMs <= 0
        lastInhale = null
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = if (running) SystemClock.uptimeMillis() - startedAt else 0L
        val t = elapsed % CYCLE_MS
        val inhale = t < INHALE_MS
        val fullness = if (inhale) ease(t / INHALE_MS.toFloat()) else 1f - ease((t - INHALE_MS) / EXHALE_MS.toFloat())

        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - progressArc.strokeWidth
        val maxRadius = outer - 16f * density
        val minRadius = maxRadius * 0.45f
        val radius = minRadius + (maxRadius - minRadius) * fullness

        canvas.drawCircle(cx, cy, radius, halo)
        canvas.drawCircle(cx, cy, radius * 0.62f, core)
        canvas.drawCircle(cx, cy, outer, track)

        if (durationMs > 0) {
            val progress = (elapsed / durationMs.toFloat()).coerceIn(0f, 1f)
            arcBounds.set(cx - outer, cy - outer, cx + outer, cy + outer)
            canvas.drawArc(arcBounds, -90f, 360f * progress, false, progressArc)
            if (progress >= 1f && !done) {
                done = true
                post { onDone?.invoke() }
            }
        }

        if (running && inhale != lastInhale) {
            lastInhale = inhale
            post { onPhase?.invoke(inhale) }
        }
        if (running) postInvalidateOnAnimation()
    }

    private fun ease(x: Float) = ((1 - cos(PI * x)) / 2).toFloat()

    private companion object {
        const val INHALE_MS = 4_000L
        const val EXHALE_MS = 6_000L
        const val CYCLE_MS = INHALE_MS + EXHALE_MS
    }
}
