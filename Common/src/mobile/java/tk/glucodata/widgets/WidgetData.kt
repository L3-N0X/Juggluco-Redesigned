package tk.glucodata.widgets

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.StaleReading
import tk.glucodata.ui.data.NativeHistory
import tk.glucodata.ui.model.DeltaCalculation
import tk.glucodata.ui.model.GlucosePoint
import tk.glucodata.ui.model.GlucoseRange
import tk.glucodata.ui.model.GlucoseStatus
import tk.glucodata.ui.model.GlucoseUnit
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Share of readings per glucose range, plus the average, over one period. */
class RangeStats(
    val veryLow: Float,
    val low: Float,
    val inRange: Float,
    val high: Float,
    val veryHigh: Float,
    val averageMgDl: Float
)

/**
 * Everything the widgets draw, read once per update and shared by every widget on the home screen.
 *
 * [times] (epoch milliseconds) and [values] (mg/dL) are sorted by time and hold one sensor's
 * readings at any moment: where two sensors overlap only the newer one is kept, so the graph never
 * zigzags between them.
 */
class WidgetSnapshot(
    val now: Long,
    val currentTime: Long,
    val currentMgDl: Float,
    val rate: Float,
    val deltaMgDl: Float?,
    val times: LongArray,
    val values: FloatArray,
    val unit: GlucoseUnit,
    val range: GlucoseRange
) {
    val hasReading: Boolean get() = currentTime > 0L && currentMgDl > 0f
    val isStale: Boolean get() = StaleReading.isStale(if (hasReading) currentTime else null, now)

    /** A reading worth showing: current, or stale (shown struck through) but not older than [Notify.lastreadingshown]. */
    val hasRecentReading: Boolean get() = hasReading && now - currentTime <= Notify.lastreadingshown

    /** How much longer the reading may be shown. */
    val lastReadingShownFor: Long get() = (currentTime + Notify.lastreadingshown - now).coerceAtLeast(60_000L)
    val status: GlucoseStatus? get() = if (hasReading) statusOf(currentMgDl) else null

    fun statusOf(mgDl: Float): GlucoseStatus = range.statusOf(mgDl)

    fun stats(period: WidgetStatsPeriod): RangeStats? {
        val from = periodStart(period, now)
        var veryLow = 0
        var low = 0
        var inRange = 0
        var high = 0
        var veryHigh = 0
        var sum = 0.0
        for (i in times.indices) {
            if (times[i] < from) continue
            val value = values[i]
            sum += value
            when (statusOf(value)) {
                GlucoseStatus.VERY_LOW -> veryLow++
                GlucoseStatus.LOW -> low++
                GlucoseStatus.IN_RANGE -> inRange++
                GlucoseStatus.HIGH -> high++
                GlucoseStatus.VERY_HIGH -> veryHigh++
            }
        }
        val count = veryLow + low + inRange + high + veryHigh
        if (count == 0) return null
        val total = count.toFloat()
        return RangeStats(
            veryLow = veryLow / total,
            low = low / total,
            inRange = inRange / total,
            high = high / total,
            veryHigh = veryHigh / total,
            averageMgDl = (sum / count).toFloat()
        )
    }

    companion object {
        fun periodStart(period: WidgetStatsPeriod, now: Long): Long =
            if (period == WidgetStatsPeriod.TODAY) {
                Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } else {
                now - period.hours * HOUR
            }
    }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

object WidgetDataSource {
    private const val UI_PREFS = "ui_prefs"
    private const val KEY_DELTA_CALCULATION = "delta_calculation_minutes"

    /**
     * Reads the current reading plus [historyMillis] of history from the native store. Call off the main thread.
     *
     * A reading that is being handed out right now can be passed as [latestTime] (epoch milliseconds),
     * [latestMgDl] and [latestRate]; it then takes the place of the stored last reading.
     */
    fun load(
        context: Context,
        historyMillis: Long,
        latestTime: Long = 0L,
        latestMgDl: Float = 0f,
        latestRate: Float = Float.NaN
    ): WidgetSnapshot {
        val now = System.currentTimeMillis()
        val unit = GlucoseUnit.fromNative(Applic.unit)
        var range = GlucoseRange()
        var currentTime = 0L
        var currentMgDl = 0f
        var rate = Float.NaN
        var times = LongArray(0)
        var values = FloatArray(0)
        if (Applic.Nativesloaded) {
            try {
                range = GlucoseRange(
                    veryLowMgDl = unit.toMgDl(Natives.verylow()),
                    lowMgDl = unit.toMgDl(Natives.targetlow()),
                    highMgDl = unit.toMgDl(Natives.targethigh()),
                    veryHighMgDl = unit.toMgDl(Natives.veryhigh())
                ).normalized()
            } catch (_: Throwable) {
            }
            if (latestTime > 0L && latestMgDl > 0f) {
                currentTime = latestTime
                currentMgDl = latestMgDl
                rate = latestRate
            } else try {
                Natives.lastglucose()?.takeIf { it.time > 0L }?.let { last ->
                    val shown = last.value?.replace(',', '.')?.toFloatOrNull()
                    if (shown != null && shown > 0f) {
                        currentTime = last.time * 1000L
                        currentMgDl = unit.toMgDl(shown)
                        rate = last.rate
                    }
                }
            } catch (_: Throwable) {
            }
            try {
                val history = NativeHistory.read(now - max(historyMillis, 30 * MINUTE))
                times = history.first
                values = history.second
            } catch (_: Throwable) {
            }
        }
        if (currentTime == 0L && times.isNotEmpty()) {
            currentTime = times.last()
            currentMgDl = values.last()
        }
        // The last reading may be newer than the stream (a scan, a mirror), and the graph should end on it.
        if (currentTime > 0L && (times.isEmpty() || currentTime > times.last() + 30_000L)) {
            times = times + currentTime
            values = values + currentMgDl
        }
        return WidgetSnapshot(
            now = now,
            currentTime = currentTime,
            currentMgDl = currentMgDl,
            rate = rate,
            deltaMgDl = delta(context, currentTime, currentMgDl, times, values),
            times = times,
            values = values,
            unit = unit,
            range = range
        )
    }

    private fun delta(context: Context, time: Long, mgDl: Float, times: LongArray, values: FloatArray): Float? {
        if (time == 0L) return null
        val minutes = try {
            context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getInt(KEY_DELTA_CALCULATION, 1)
        } catch (_: Throwable) {
            1
        }
        // Only the last quarter of an hour can matter, so there is no point wrapping the whole history.
        val points = ArrayList<GlucosePoint>()
        for (i in times.indices) {
            if (times[i] >= time - 20 * MINUTE && times[i] <= time) points.add(GlucosePoint(times[i], values[i]))
        }
        val reference = DeltaCalculation.findDeltaReference(
            GlucosePoint(time, mgDl),
            points,
            DeltaCalculation.fromMinutes(minutes)
        ) ?: return null
        return mgDl - reference.reading.valueMgDl
    }

    /** A plausible day of readings, for previews when there is no real data yet. */
    fun sample(unit: GlucoseUnit = GlucoseUnit.fromNative(Applic.unit)): WidgetSnapshot {
        val now = System.currentTimeMillis()
        val count = 24 * 12
        val times = LongArray(count) { now - (count - 1 - it) * 5 * MINUTE }
        val values = FloatArray(count) { index ->
            val hours = (count - 1 - index) * 5f / 60f
            val meal = 55f * sin((hours / 5.5f) * 2f * PI.toFloat()).coerceAtLeast(0f)
            val drift = 18f * sin((hours / 9f) * 2f * PI.toFloat())
            val dip = if (hours in 13f..14.5f) -52f * sin(((hours - 13f) / 1.5f) * PI.toFloat()) else 0f
            (112f + meal + drift + dip).coerceIn(48f, 280f)
        }
        return WidgetSnapshot(
            now = now,
            currentTime = times.last(),
            currentMgDl = values.last(),
            rate = 0.7f,
            deltaMgDl = values.last() - values[count - 2],
            times = times,
            values = values,
            unit = unit,
            range = GlucoseRange()
        )
    }
}
