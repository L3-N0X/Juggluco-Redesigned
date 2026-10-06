package tk.glucodata.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import tk.glucodata.R
import tk.glucodata.ui.model.DeltaReference
import tk.glucodata.ui.model.GlucosePoint
import tk.glucodata.ui.model.GlucoseStatus
import tk.glucodata.ui.model.GlucoseUnit
import tk.glucodata.ui.model.TrendArrow
import tk.glucodata.ui.theme.LocalClinicalColors
import tk.glucodata.ui.theme.logbookColors

@Composable
fun CurrentGlucoseHeroCard(
    currentReading: GlucosePoint?,
    deltaReference: DeltaReference?,
    unit: GlucoseUnit,
    sensorName: String? = null,
    minimalistUnits: Boolean = true,
    insulinOnboard: Float? = null,
    modifier: Modifier = Modifier
) {
    val clinicalColors = LocalClinicalColors.current

    val isConnected = currentReading != null
    val statusLabel = if (currentReading != null) stringResource(currentReading.status.labelRes) else stringResource(R.string.no_reading)
    val statusColor = if (currentReading != null) {
        when (currentReading.status) {
            GlucoseStatus.VERY_LOW -> clinicalColors.veryLow
            GlucoseStatus.LOW -> clinicalColors.low
            GlucoseStatus.IN_RANGE -> clinicalColors.inRange
            GlucoseStatus.HIGH -> clinicalColors.high
            GlucoseStatus.VERY_HIGH -> clinicalColors.veryHigh
        }
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // One clock for the whole card: the strike-through, the arrow and the age below all describe the
    // same reading, and a new reading clears the strike-through on the frame it arrives.
    val age = rememberReadingAge(currentReading?.timestamp)

    // No new reading: the last one stays, greyed and struck through, without arrow or change.
    val isStale = age.isStale && currentReading != null
    val trendArrow = if (currentReading != null && !isStale) TrendArrow.fromRate(currentReading.rate) else TrendArrow.UNKNOWN

    // Calculate time elapsed
    val timeAgoText = if (currentReading != null) {
        when {
            age.minutes <= 1 -> stringResource(R.string.just_now)
            age.minutes < 60 -> stringResource(R.string.min_ago, age.minutes)
            else -> stringResource(R.string.hours_min_ago, age.minutes / 60, age.minutes % 60)
        }
    } else {
        stringResource(R.string.waiting_for_readings)
    }

    // Delta from the reference reading (clean number, omitting redundant unit). The window is a
    // display setting the user already chose, so it is not repeated on the value itself.
    val deltaText = if (currentReading != null && deltaReference != null && !isStale) {
        val deltaMgDl = currentReading.valueMgDl - deltaReference.reading.valueMgDl
        val sign = if (deltaMgDl >= 0) "+" else ""
        val numStr = when (unit) {
            GlucoseUnit.MG_DL -> "$sign${deltaMgDl.toInt()}"
            GlucoseUnit.MMOL_L -> "$sign${String.format(java.util.Locale.getDefault(), "%.1f", unit.toDisplay(deltaMgDl))}"
        }
        if (!minimalistUnits) "$numStr ${stringResource(unit.labelRes)}" else numStr
    } else null

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Top Row: Sensor Name & Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = stringResource(R.string.sensor_content_description),
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = sensorName ?: (if (isConnected) stringResource(R.string.cgm_sensor) else stringResource(R.string.no_sensor_connected)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentReading != null) {
                    val statusIcon = when (currentReading.status) {
                        GlucoseStatus.VERY_LOW -> Icons.Default.KeyboardDoubleArrowDown
                        GlucoseStatus.LOW -> Icons.Default.ArrowDownward
                        GlucoseStatus.IN_RANGE -> Icons.Default.Check
                        GlucoseStatus.HIGH -> Icons.Default.ArrowUpward
                        GlucoseStatus.VERY_HIGH -> Icons.Default.KeyboardDoubleArrowUp
                    }
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Text(
                    text = statusLabel,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Middle Row: Big Glucose Value + Unit + Trend Arrow
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                val formattedValue = currentReading?.formatted(unit) ?: "—"
                Text(
                    text = formattedValue,
                    fontSize = 50.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isStale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (isStale) TextDecoration.LineThrough else null,
                    letterSpacing = (-1).sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(unit.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = if (minimalistUnits) 11.sp else 14.sp,
                    fontWeight = if (minimalistUnits) FontWeight.Normal else FontWeight.Medium,
                    color = if (minimalistUnits) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (minimalistUnits) 8.dp else 10.dp)
                )
            }

            // Trend Arrow Box
            Surface(
                shape = CircleShape,
                color = if (isConnected && !isStale) statusColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isConnected && trendArrow != TrendArrow.UNKNOWN) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = stringResource(trendArrow.labelRes),
                            tint = statusColor,
                            modifier = Modifier
                                .size(30.dp)
                                .rotate(trendArrow.angleDegrees)
                        )
                    } else {
                        Text(
                            text = "—",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Row: Time ago, Delta, insulin on board
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeAgoText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (deltaText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Δ $deltaText",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // Plain text next to the change rather than another pill: it is a value of its own,
            // not a status, and it wears the bolus hue so it never reads as the glucose's.
            insulinOnboard?.let { onBoard ->
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        R.string.iob_value,
                        stringResource(R.string.iob),
                        stringResource(R.string.log_value_insulin, plainAmount(onBoard, 1))
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.logbookColors.rapidInsulin
                )
            }
        }
    }
}
