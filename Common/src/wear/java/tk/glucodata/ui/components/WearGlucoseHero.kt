package tk.glucodata.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import tk.glucodata.R
import tk.glucodata.ui.model.DeltaCalculation
import tk.glucodata.ui.model.GlucosePoint
import tk.glucodata.ui.model.GlucoseStatus
import tk.glucodata.ui.model.GlucoseUnit
import tk.glucodata.ui.model.TrendArrow
import tk.glucodata.ui.theme.LocalClinicalColors
import tk.glucodata.ui.theme.LocalLogbookColors

@Composable
fun WearGlucoseHero(
    currentReading: GlucosePoint?,
    readings: List<GlucosePoint>,
    unit: GlucoseUnit,
    deltaCalculation: DeltaCalculation = DeltaCalculation.ONE_MINUTE,
    insulinOnboard: Float? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clinical = LocalClinicalColors.current

    val status = currentReading?.status ?: GlucoseStatus.IN_RANGE
    val statusColor = when (status) {
        GlucoseStatus.IN_RANGE -> clinical.inRange
        GlucoseStatus.LOW, GlucoseStatus.HIGH -> clinical.low
        GlucoseStatus.VERY_LOW, GlucoseStatus.VERY_HIGH -> clinical.veryLow
    }

    val arrow = remember(currentReading?.rate) {
        currentReading?.let { TrendArrow.fromRate(it.rate) } ?: TrendArrow.UNKNOWN
    }

    // Delta calculation, using the window the user selected rather than a fixed five minutes.
    val deltaReference = remember(currentReading, readings, deltaCalculation) {
        DeltaCalculation.findDeltaReference(currentReading, readings, deltaCalculation)
    }
    val deltaText = remember(currentReading, deltaReference, unit) {
        if (currentReading != null && deltaReference != null) {
            val diff = currentReading.valueMgDl - deltaReference.reading.valueMgDl
            val prefix = if (diff > 0) "+" else ""
            when (unit) {
                GlucoseUnit.MG_DL -> "$prefix${diff.toInt()}"
                GlucoseUnit.MMOL_L -> "$prefix${String.format(java.util.Locale.US, "%.1f", unit.toDisplay(diff))}"
            }
        } else null
    }
    // One clock for the whole card, so the age and the strike-through cannot drift apart.
    val age = rememberReadingAge(currentReading?.timestamp)
    val minutesAgo = age.minutes
    val justNowLabel = stringResource(R.string.wear_ui_just_now)
    val timeAgo = when {
        minutesAgo < 0 -> "--"
        minutesAgo == 0 -> justNowLabel
        minutesAgo < 60 -> stringResource(R.string.wear_ui_mins_ago, minutesAgo)
        else -> stringResource(R.string.wear_ui_hours_ago, minutesAgo / 60)
    }

    // No new reading: the last one stays, greyed and struck through, without arrow or change.
    val isStale = age.isStale

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Value + Arrow
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = currentReading?.formatted(unit) ?: "---",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = if (isStale) MaterialTheme.colorScheme.onSurfaceVariant else statusColor,
                textDecoration = if (isStale && currentReading != null) TextDecoration.LineThrough else null,
                letterSpacing = (-1).sp
            )

            if (arrow != TrendArrow.UNKNOWN && !isStale) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = arrow.symbol,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = statusColor
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Delta, Time Ago
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (deltaText != null && !isStale) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.wear_ui_delta, deltaText.orEmpty()),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Text(
                text = timeAgo,
                fontSize = 13.sp,
                color = if (isStale) clinical.low else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Plain text beside the change, in the bolus hue: a value of its own rather than a
            // status, and never to be mistaken for the glucose's own colour.
            insulinOnboard?.let { onBoard ->
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        R.string.iob_value,
                        stringResource(R.string.iob),
                        stringResource(R.string.log_value_insulin, plainAmount(onBoard, 1))
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalLogbookColors.current.rapidInsulin
                )
            }
        }
    }
}
