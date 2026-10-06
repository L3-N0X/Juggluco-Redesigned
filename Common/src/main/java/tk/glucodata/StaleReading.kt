package tk.glucodata

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max

/**
 * The last reading, shown once no new ones arrive (a sensor warning, a lost connection): greyed and
 * struck through, so the user still sees where they were while it never passes for a current value.
 */
object StaleReading {
    /**
     * Whether the reading taken at [timestamp] (epoch milliseconds) can no longer be presented as
     * the current one: there is none at all, or it has aged past [Notify.glucosetimeout].
     *
     * The one answer every surface goes by - the Compose hero cards, the widgets, the notification -
     * and it is told the time instead of reading it, so a caller that already knows "now" stays the
     * one deciding how old the reading is.
     */
    @JvmStatic
    fun isStale(timestamp: Long?, now: Long): Boolean =
        timestamp == null || now - timestamp > Notify.glucosetimeout

    /** Whether a reading [ageMillis] old is still worth showing struck through. */
    @JvmStatic
    fun shown(ageMillis: Long): Boolean = ageMillis <= Notify.lastreadingshown

    /** Height of the digits [paint] draws at its current size. */
    @JvmStatic
    fun digitHeight(paint: Paint): Float {
        val bounds = Rect()
        paint.getTextBounds("0", 0, 1, bounds)
        return bounds.height().toFloat()
    }

    /** Draws [text] like [Canvas.drawText] and strikes it through. */
    @JvmStatic
    @JvmOverloads
    fun drawStruck(canvas: Canvas, text: String, x: Float, baseline: Float, paint: Paint, digitHeight: Float = digitHeight(paint)) {
        canvas.drawText(text, x, baseline, paint)
        val width = paint.measureText(text)
        val left = when (paint.textAlign) {
            Paint.Align.CENTER -> x - width / 2f
            Paint.Align.RIGHT -> x - width
            else -> x
        }
        strike(canvas, left, left + width, baseline - digitHeight / 2f, digitHeight, paint)
    }

    /** A line from [left] to [right] through the middle ([centerY]) of digits [digitHeight] tall, in [paint]'s color. */
    @JvmStatic
    fun strike(canvas: Canvas, left: Float, right: Float, centerY: Float, digitHeight: Float, paint: Paint) {
        val overhang = digitHeight * 0.08f
        val line = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, digitHeight * 0.1f)
            strokeCap = Paint.Cap.ROUND
            isStrikeThruText = false
        }
        canvas.drawLine(left - overhang, centerY, right + overhang, centerY, line)
    }
}
