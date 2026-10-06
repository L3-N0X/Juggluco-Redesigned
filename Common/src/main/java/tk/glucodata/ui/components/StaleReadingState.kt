package tk.glucodata.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import tk.glucodata.Notify
import tk.glucodata.StaleReading

/**
 * How old the reading on screen is: the clock it was judged against, the whole minutes since it
 * arrived, and whether it is still current. Read off one clock on purpose - the strike-through, the
 * hidden arrow and the "5 min ago" line all describe the same reading, so they must not be able to
 * disagree about how old it is.
 */
@Immutable
data class ReadingAge(
    val now: Long,
    val minutes: Int,
    val isStale: Boolean
)

/**
 * The age of the reading taken at [timestamp], recomposed by itself as the minutes pass and as the
 * reading ages out, without waiting for a new one.
 *
 * Only the clock is remembered, keyed on the reading it belongs to; everything else is derived from
 * it on every composition. Deriving the verdict is the whole point: a remembered verdict latched,
 * because `produceState` (and `LaunchedEffect` over a state) keeps its value when only a key changes
 * - the initial value is applied once. A reading that had aged out left `true` behind, the next
 * reading restarted a producer that began at `while (!value)` and so never looked at the clock
 * again, and the value stayed struck through for the rest of the composition, right next to a
 * "just now" that did keep up. Deriving from a clock that is itself re-keyed per reading cannot
 * latch: the frame a reading arrives on already has the new timestamp.
 */
@Composable
fun rememberReadingAge(timestamp: Long?): ReadingAge {
    val clock = remember(timestamp) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timestamp) {
        if (timestamp == null) return@LaunchedEffect
        // The next moment anything shown changes: the reading turns a minute older, or it ages out
        // and has to be struck through. Waiting for exactly that is the whole cost of the clock -
        // one recomposition a minute, and nothing at all once the reading is stale.
        while (true) {
            val age = System.currentTimeMillis() - timestamp
            if (age > Notify.glucosetimeout) break
            delay(minOf(Notify.glucosetimeout - age, 60_000L - age.mod(60_000L)).coerceAtLeast(1L))
        }
        // Re-read the clock instead of trusting the schedule, so a device that slept through the
        // deadline, or a wall clock that moved, cannot leave a stale reading looking current.
        clock.value = System.currentTimeMillis()
    }
    val now = clock.value
    return remember(timestamp, now) {
        ReadingAge(
            now = now,
            minutes = if (timestamp == null) -1 else ((now - timestamp) / 60_000L).coerceAtLeast(0L).toInt(),
            isStale = StaleReading.isStale(timestamp, now)
        )
    }
}
