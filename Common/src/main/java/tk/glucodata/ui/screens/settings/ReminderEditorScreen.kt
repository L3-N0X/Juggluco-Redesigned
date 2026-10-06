package tk.glucodata.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.alerts.AlertPlayer
import tk.glucodata.alerts.AlertRule
import tk.glucodata.alerts.AlertRuntime
import tk.glucodata.alerts.AlertStore
import tk.glucodata.alerts.ReminderRepeat
import tk.glucodata.alerts.ReminderSpec
import tk.glucodata.alerts.Reminders
import tk.glucodata.ui.data.NativeLabels
import tk.glucodata.ui.model.LabelConfig
import tk.glucodata.ui.model.LogType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private const val DAY_MILLIS = 86_400_000L

/**
 * Everything about one medication reminder: what to take, when, how insistently, how it
 * ties into the logbook, and how it sounds. Saved as it is edited, like [AlertEditorScreen].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderEditorScreen(
    rule: AlertRule,
    runtime: AlertRuntime,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val active by AlertPlayer.active.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val spec = rule.reminder
    fun update(transform: (AlertRule) -> AlertRule) = AlertStore.upsert(transform(rule))
    fun updateSpec(transform: (ReminderSpec) -> ReminderSpec) = update { it.copy(reminder = transform(it.reminder)) }

    val testing = active?.let { it.isTest && it.rule.id == rule.id } == true
    val now = System.currentTimeMillis()
    val offLabel = stringResource(R.string.loc_common_off)
    val untilDismissedLabel = stringResource(R.string.loc_alert_until_dismissed)
    val defaultName = stringResource(R.string.loc_reminder_default_name)

    SettingsDetailScaffold(
        title = rule.name.ifBlank { defaultName },
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.loc_action_delete_alert))
            }
        }
    ) {
        // --- Medication -------------------------------------------------------------
        SettingsSection(title = stringResource(R.string.loc_reminder_medication_section)) {
            ReminderTextRow(
                key = rule.id,
                icon = Icons.Default.Medication,
                label = stringResource(R.string.loc_reminder_name_label),
                value = rule.name,
                onChange = { name -> if (name.isNotBlank()) update { it.copy(name = name) } }
            )
            ReminderTextRow(
                key = rule.id,
                icon = Icons.Default.Scale,
                label = stringResource(R.string.loc_reminder_dose_label),
                placeholder = stringResource(R.string.loc_reminder_dose_placeholder),
                value = spec.dose,
                onChange = { dose -> updateSpec { it.copy(dose = dose) } }
            )
            ReminderTextRow(
                key = rule.id,
                icon = Icons.AutoMirrored.Filled.Notes,
                label = stringResource(R.string.loc_reminder_note_label),
                placeholder = stringResource(R.string.loc_reminder_note_placeholder),
                value = spec.note,
                onChange = { note -> updateSpec { it.copy(note = note) } }
            )
            val status = buildList {
                if (rule.enabled) {
                    add(
                        Reminders.nextDue(rule, now)
                            ?.let { context.getString(R.string.loc_reminder_next, formatReminderTime(context, it, now)) }
                            ?: context.getString(R.string.loc_reminder_no_next)
                    )
                } else {
                    add(context.getString(R.string.loc_reminder_off_desc))
                }
                if (runtime.lastTaken > 0L) add(context.getString(R.string.loc_reminder_last_taken, formatReminderTime(context, runtime.lastTaken, now)))
            }.joinToString(" · ")
            val (tint, background) = alertColors(rule)
            SettingsSwitchRow(
                title = stringResource(R.string.loc_reminder_on),
                subtitle = status,
                icon = Icons.Default.NotificationsActive,
                iconTint = tint,
                iconBackground = background,
                checked = rule.enabled,
                onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } }
            )
            if (rule.enabled && runtime.pendingDue > 0L) {
                val snoozed = runtime.snoozedUntil > now
                SettingsActionRow(
                    title = stringResource(R.string.loc_reminder_open_dose),
                    subtitle = buildString {
                        append(context.getString(R.string.loc_reminder_due_at, formatClock(runtime.pendingDue)))
                        if (snoozed) append(" · ").append(context.getString(R.string.loc_reminder_again_at, formatClock(runtime.snoozedUntil)))
                    },
                    icon = if (snoozed) Icons.Default.Snooze else Icons.Default.HistoryToggleOff,
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { Reminders.skip(rule.id, runtime.pendingDue) }) {
                                Text(stringResource(R.string.loc_reminder_skip))
                            }
                            FilledTonalButton(onClick = { Reminders.taken(rule.id, runtime.pendingDue) }) {
                                Text(stringResource(R.string.loc_reminder_taken))
                            }
                        }
                    }
                )
            }
        }

        // --- When -------------------------------------------------------------------
        SettingsSection(title = stringResource(R.string.loc_reminder_when_section)) {
            ReminderTimesRow(times = spec.times, onChange = { times -> updateSpec { it.copy(times = times) } })
            SettingsSegmentedRow(
                title = stringResource(R.string.loc_reminder_repeat),
                subtitle = reminderSummary(context, rule).substringAfter(" · "),
                icon = Icons.Default.EventRepeat
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    val choices = listOf(
                        ReminderRepeat.DAYS_OF_WEEK to stringResource(R.string.loc_reminder_repeat_days),
                        ReminderRepeat.EVERY_N_DAYS to stringResource(R.string.loc_reminder_repeat_interval),
                        ReminderRepeat.ONCE to stringResource(R.string.loc_common_once)
                    )
                    choices.forEachIndexed { index, (repeat, label) ->
                        SegmentedButton(
                            selected = spec.repeat == repeat,
                            onClick = {
                                updateSpec {
                                    // A single or first reminder in the past would never ring.
                                    val today = LocalDate.now().toEpochDay()
                                    val start = if (repeat != ReminderRepeat.DAYS_OF_WEEK && it.startDay < today) today else it.startDay
                                    it.copy(repeat = repeat, startDay = start)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }
            }
            when (spec.repeat) {
                ReminderRepeat.DAYS_OF_WEEK -> {
                    SettingsSegmentedRow(
                        title = stringResource(R.string.loc_schedule_active_on),
                        subtitle = scheduleSummary(rule.schedule.copy(allDay = true)),
                        icon = Icons.Default.CalendarMonth
                    ) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 68.dp, end = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            DayOfWeek.entries.forEach { day ->
                                val bit = 1 shl (day.value - 1)
                                FilterChip(
                                    selected = rule.schedule.days and bit != 0,
                                    onClick = { update { it.copy(schedule = it.schedule.copy(days = it.schedule.days xor bit, allDay = true)) } },
                                    label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) }
                                )
                            }
                        }
                    }
                }
                ReminderRepeat.EVERY_N_DAYS -> {
                    ChoiceRow(
                        title = stringResource(R.string.loc_reminder_repeat_interval),
                        icon = Icons.Default.Repeat,
                        options = listOf(2, 3, 4, 5, 7, 10, 14, 21, 28),
                        selected = spec.intervalDays,
                        label = { context.getString(R.string.loc_reminder_every_n_days, it) },
                        onSelect = { days -> updateSpec { it.copy(intervalDays = days) } }
                    )
                    DateRow(
                        title = stringResource(R.string.loc_reminder_starting),
                        epochDay = spec.startDay,
                        onPick = { day -> updateSpec { it.copy(startDay = day) } }
                    )
                }
                ReminderRepeat.ONCE -> DateRow(
                    title = stringResource(R.string.loc_reminder_on_date),
                    epochDay = spec.startDay,
                    onPick = { day -> updateSpec { it.copy(startDay = day) } }
                )
            }
        }

        // --- Until taken ------------------------------------------------------------
        SettingsSection(title = stringResource(R.string.loc_reminder_until_taken_section)) {
            ChoiceRow(
                title = stringResource(R.string.loc_reminder_again_every),
                subtitle = if (rule.repeatMinutes > 0) {
                    stringResource(R.string.loc_reminder_again_every_desc, formatDuration(rule.repeatMinutes * 60))
                } else {
                    stringResource(R.string.loc_reminder_again_off_desc)
                },
                icon = Icons.Default.Repeat,
                options = listOf(0, 5, 10, 15, 20, 30, 45, 60, 120),
                selected = rule.repeatMinutes,
                label = { if (it == 0) offLabel else formatDuration(it * 60) },
                onSelect = { minutes -> update { it.copy(repeatMinutes = minutes) } }
            )
            if (rule.repeatMinutes > 0) {
                val untilTakenLabel = stringResource(R.string.loc_reminder_until_taken)
                ChoiceRow(
                    title = stringResource(R.string.loc_reminder_max_repeats),
                    subtitle = if (spec.maxRepeats == ReminderSpec.UNTIL_TAKEN) {
                        stringResource(R.string.loc_reminder_until_taken_desc)
                    } else {
                        stringResource(R.string.loc_reminder_max_repeats_desc, spec.maxRepeats)
                    },
                    icon = Icons.Default.Tag,
                    options = listOf(1, 2, 3, 5, 10, ReminderSpec.UNTIL_TAKEN),
                    selected = spec.maxRepeats,
                    label = { if (it == ReminderSpec.UNTIL_TAKEN) untilTakenLabel else it.toString() },
                    onSelect = { count -> updateSpec { it.copy(maxRepeats = count) } }
                )
            }
            ChoiceRow(
                title = stringResource(R.string.loc_alert_ring_for),
                subtitle = if (rule.playDurationSec > 0) {
                    stringResource(R.string.loc_alert_ring_duration, formatDuration(rule.playDurationSec))
                } else {
                    stringResource(R.string.loc_alert_ring_until_dismissed)
                },
                icon = Icons.Default.AccessTime,
                options = listOf(10, 15, 30, 60, 120, 300, 0),
                selected = rule.playDurationSec,
                label = { if (it == 0) untilDismissedLabel else formatDuration(it) },
                onSelect = { seconds -> update { it.copy(playDurationSec = seconds) } }
            )
        }

        // --- Logbook ----------------------------------------------------------------
        LogbookSection(rule = rule, onChange = { transform -> updateSpec(transform) })

        // --- Sound and vibration ----------------------------------------------------
        AlertSoundSection(rule = rule, onChange = { transform -> update(transform) })
        VibrationSection(rule = rule, onChange = { transform -> update(transform) })

        SettingsSection(title = stringResource(R.string.loc_alert_behavior)) {
            SettingsSwitchRow(
                title = stringResource(R.string.loc_alert_full_screen),
                subtitle = stringResource(R.string.loc_alert_full_screen_desc),
                icon = Icons.Default.Fullscreen,
                checked = rule.fullScreen,
                onCheckedChange = { on -> update { it.copy(fullScreen = on) } }
            )
            SettingsSwitchRow(
                title = stringResource(R.string.loc_reminder_speak),
                subtitle = stringResource(R.string.loc_reminder_speak_desc),
                icon = Icons.Default.RecordVoiceOver,
                checked = rule.announce,
                onCheckedChange = { on -> update { it.copy(announce = on) } }
            )
            if (!Applic.isWearable) {
                SettingsSwitchRow(
                    title = stringResource(R.string.loc_alert_flashlight),
                    subtitle = stringResource(R.string.loc_alert_flashlight_desc),
                    icon = Icons.Default.FlashlightOn,
                    checked = rule.flash,
                    onCheckedChange = { on -> update { it.copy(flash = on) } }
                )
            }
        }

        FilledTonalButton(
            onClick = { if (testing) AlertPlayer.dismiss() else AlertPlayer.test(rule) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Icon(if (testing) Icons.Default.Stop else Icons.Default.NotificationsActive, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (testing) R.string.loc_alert_stop_test else R.string.loc_alert_test_this))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.loc_delete_alert_named, rule.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (AlertPlayer.active.value?.rule?.id == rule.id) AlertPlayer.dismiss()
                    onNavigateBack()
                    AlertStore.delete(rule.id)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** Text field row; keeps its own text so typing is not interrupted by the store round trip. */
@Composable
private fun ReminderTextRow(
    key: String,
    icon: ImageVector,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null
) {
    var text by remember(key) { mutableStateOf(value) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIcon(icon = icon)
        Spacer(Modifier.width(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it.trim())
            },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            suffix = suffix?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The reminder's times of day as chips: tap one to change it, × to remove it, + to add one. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimesRow(times: List<Int>, onChange: (List<Int>) -> Unit) {
    // Index of the time being edited; -1 adds a new one.
    var editing by remember { mutableStateOf<Int?>(null) }
    SettingsSegmentedRow(
        title = stringResource(R.string.loc_reminder_times),
        icon = Icons.Default.AccessTime
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 68.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            times.forEachIndexed { index, minute ->
                val label = formatMinuteOfDay(minute)
                val removeLabel = stringResource(R.string.loc_reminder_remove_time, label)
                InputChip(
                    selected = false,
                    onClick = { editing = index },
                    label = { Text(label) },
                    trailingIcon = if (times.size > 1) {
                        {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = removeLabel,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onChange(times - minute) }
                            )
                        }
                    } else null
                )
            }
            AssistChip(
                onClick = { editing = -1 },
                label = { Text(stringResource(R.string.loc_reminder_add_time)) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
    }

    editing?.let { index ->
        // A new time starts a few hours after the last one, a common spacing for doses.
        val initial = if (index >= 0) times[index] else ((times.lastOrNull() ?: (4 * 60)) + 4 * 60) % (24 * 60)
        val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.loc_reminder_pick_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.hour * 60 + state.minute
                    val updated = if (index >= 0) times.toMutableList().apply { set(index, picked) } else times + picked
                    onChange(updated.distinct().sorted())
                    editing = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(title: String, epochDay: Long, onPick: (Long) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    SettingsActionRow(
        title = title,
        subtitle = formatEpochDay(epochDay),
        icon = Icons.Default.Today,
        onClick = { picking = true }
    )
    if (picking) {
        // The date picker works in UTC midnights.
        val state = rememberDatePickerState(initialSelectedDateMillis = epochDay * DAY_MILLIS)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Math.floorDiv(it, DAY_MILLIS)) }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * The old numeric alarms live on here: link the reminder to a logbook type so an entry made
 * shortly before keeps it quiet, and optionally log the dose when it is confirmed.
 */
@Composable
private fun LogbookSection(rule: AlertRule, onChange: ((ReminderSpec) -> ReminderSpec) -> Unit) {
    val context = LocalContext.current
    val spec = rule.reminder
    // logLabel is a native label index, the one the dose is saved under and looked for.
    val labels by produceState(LabelConfig()) {
        value = withContext(Dispatchers.Default) {
            runCatching { if (Applic.Nativesloaded) NativeLabels.read() else LabelConfig() }.getOrDefault(LabelConfig())
        }
    }
    // ReminderSpec stores native label indexes, so offer the native catalog directly. Inferring
    // the choices from log types omitted labels assigned to another role and made the visible
    // choice list differ from the indexes written by Natives.saveNum.
    val validLabel = spec.logLabel.takeIf { it in labels.labels.indices } ?: -1
    val noneLabel = stringResource(R.string.loc_reminder_log_none)
    fun typeName(label: Int): String = when (label) {
        -1 -> noneLabel
        else -> labels.nameOf(label).ifBlank { "#$label" }
    }
    val unit = when (labels.typeOf(validLabel).takeIf { validLabel >= 0 }) {
        LogType.RAPID_INSULIN, LogType.BASAL_INSULIN -> context.getString(R.string.unit_insulin_short)
        LogType.CARBS -> context.getString(R.string.unit_carbs_short)
        else -> null
    }
    val amountText = formatAmount(spec.logAmount)
    val entryText = if (spec.logAmount > 0f) {
        listOfNotNull(amountText, unit).joinToString(" ") + " " + typeName(validLabel)
    } else {
        typeName(validLabel)
    }

    SettingsSection(title = stringResource(R.string.loc_reminder_logbook_section)) {
        val options = listOf(-1) + labels.labels.map { it.index }
        ChoiceRow(
            title = stringResource(R.string.loc_reminder_log_type),
            subtitle = stringResource(R.string.loc_reminder_log_type_desc),
            icon = Icons.AutoMirrored.Filled.MenuBook,
            options = options,
            selected = validLabel,
            label = { typeName(it) },
            onSelect = { label -> onChange { it.copy(logLabel = label) } }
        )
        if (validLabel >= 0) {
            ReminderTextRow(
                key = rule.id + spec.logLabel,
                icon = Icons.Default.Scale,
                label = stringResource(R.string.loc_reminder_amount_label),
                placeholder = stringResource(R.string.loc_reminder_amount_any),
                value = if (spec.logAmount > 0f) amountText else "",
                keyboardType = KeyboardType.Decimal,
                suffix = unit,
                onChange = { text ->
                    val amount = text.replace(',', '.').toFloatOrNull()
                    if (text.isEmpty() || amount != null) onChange { it.copy(logAmount = (amount ?: 0f).coerceIn(0f, 9_999f)) }
                }
            )
            ChoiceRow(
                title = stringResource(R.string.loc_reminder_skip_if_logged),
                subtitle = if (spec.lookBackMinutes > 0) {
                    stringResource(R.string.loc_reminder_skip_if_logged_desc, entryText, formatDuration(spec.lookBackMinutes * 60))
                } else {
                    stringResource(R.string.loc_reminder_skip_if_logged_off)
                },
                icon = Icons.Default.PlaylistAddCheck,
                options = listOf(0, 30, 60, 120, 180, 360, 720),
                selected = spec.lookBackMinutes,
                label = { if (it == 0) context.getString(R.string.loc_common_off) else formatDuration(it * 60) },
                onSelect = { minutes -> onChange { it.copy(lookBackMinutes = minutes) } }
            )
            SettingsSwitchRow(
                title = stringResource(R.string.loc_reminder_log_when_taken),
                subtitle = if (spec.logAmount > 0f) {
                    stringResource(R.string.loc_reminder_log_when_taken_desc, entryText)
                } else {
                    stringResource(R.string.loc_reminder_log_needs_amount)
                },
                icon = Icons.Default.CheckCircle,
                enabled = spec.logAmount > 0f,
                checked = spec.logWhenTaken && spec.logAmount > 0f,
                onCheckedChange = { on -> onChange { it.copy(logWhenTaken = on) } }
            )
        }
    }
}

/** Amount without a trailing ".0", in the user's locale. */
private fun formatAmount(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else String.format(Locale.getDefault(), "%.1f", value)
