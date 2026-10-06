package tk.glucodata.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.nums.numio

/**
 * Medication reminders ([AlertKind.REMINDER]): works out when each one is due, wakes the
 * device for it, rings it through [AlertPlayer] and keeps the dose open until it is
 * confirmed ("Taken"), skipped, or replaced by the next dose.
 *
 * Like the old numeric alarms, a reminder linked to a logbook type stays quiet when a
 * matching entry was already logged shortly before it is due, and closes by itself when
 * one is logged while it is open.
 *
 * Every state change runs on one handler thread, so receivers, the UI and the watch sync
 * can call in from anywhere.
 */
object Reminders {
    private const val LOG_ID = "Reminders"
    private const val ALARM_REQUEST = 81460

    /** A dose noticed this late (device off or alerts off) is let go instead of ringing. */
    private const val LATE_LIMIT_MS = 6 * 3_600_000L

    /** Wait before trying again while a glucose alert rings or alerts are snoozed. */
    private const val RETRY_MS = 60_000L

    private val handler: Handler by lazy {
        Handler(HandlerThread("Reminders").apply { start() }.looper)
    }

    private val context: Context get() = Applic.app

    /** Open doses shown without ringing, so they are posted once rather than on every retry. */
    private val shownQuietly = mutableSetOf<Pair<String, Long>>()

    private fun post(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (th: Throwable) {
                Log.stack(LOG_ID, "post", th)
            }
        }
    }

    // --- Entry points ---------------------------------------------------------------------

    /** App start and boot: takes over the old numeric alarms once, then catches up and schedules. */
    @JvmStatic
    fun start() {
        try {
            AlertStore.ensureLoaded()
            AlertStore.importLegacyReminders()
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "start", th)
        }
        refresh()
    }

    /** True once the native numeric alarms live on as reminders, so the old code leaves them alone. */
    @JvmStatic
    fun replacesLegacyAlarms(): Boolean = runCatching { AlertStore.legacyRemindersImported }.getOrDefault(false)

    /** Rings what is due and sets the next wake-up. Safe to call at any time. */
    @JvmStatic
    fun refresh() = post {
        AlertStore.ensureLoaded()
        checkNow()
    }

    /**
     * Called after the alert list changed. A changed schedule, or an alert switched back on,
     * starts counting from now, so editing never rings a time that has just passed.
     */
    fun onRulesChanged(before: List<AlertRule>, after: List<AlertRule>) = post {
        val now = System.currentTimeMillis()
        val previous = before.filter { it.kind.isReminder }.associateBy { it.id }
        val current = after.filter { it.kind.isReminder }.associateBy { it.id }
        previous.values.forEach { old ->
            val new = current[old.id]
            if (new == null || !new.enabled) closeDose(old.id, due = 0L)
        }
        current.values.forEach { new ->
            val old = previous[new.id] ?: return@forEach
            val rebase = (!old.enabled && new.enabled) ||
                old.reminder.timingDiffers(new.reminder) ||
                old.schedule.days != new.schedule.days
            if (rebase) AlertStore.updateRuntime(new.id) { it.copy(handledDue = now) }
        }
        checkNow()
    }

    /** The dose due at [due] was taken; logs it when the reminder asks for that. */
    fun taken(ruleId: String, due: Long, isTest: Boolean = false, fallback: AlertRule? = null, remote: Boolean = false) = post {
        val rule = AlertStore.rule(ruleId) ?: fallback
        val now = System.currentTimeMillis()
        val closed = !isTest && closeDose(ruleId, due, takenAt = now)
        if (isTest) {
            finishShown(ruleId)
            if (!remote) AlertSync.sendStop(ruleId)
        }
        if (!remote && !isTest) {
            // A reminder mirrored from a device whose list is not synced here is logged here too.
            if (rule != null && (closed || AlertStore.rule(ruleId) == null)) logDose(rule, now)
            AlertSync.sendReminderDone(ruleId, due, taken = true)
        }
        checkNow()
    }

    /** The dose due at [due] is skipped: no more reminders for it, nothing logged. */
    fun skip(ruleId: String, due: Long, isTest: Boolean = false, remote: Boolean = false) = post {
        if (isTest) finishShown(ruleId) else closeDose(ruleId, due)
        if (!remote) {
            if (isTest) AlertSync.sendStop(ruleId) else AlertSync.sendReminderDone(ruleId, due, taken = false)
        }
        checkNow()
    }

    /** Rings the open dose again in [minutes]; the notification stays up meanwhile. */
    fun snooze(ruleId: String, minutes: Int, isTest: Boolean = false, remote: Boolean = false, until: Long = 0L) = post {
        if (isTest) {
            finishShown(ruleId)
            if (!remote) AlertSync.sendStop(ruleId)
            return@post
        }
        val snoozeEnd = if (until > 0L) until else System.currentTimeMillis() + minutes * 60_000L
        var due = 0L
        if (AlertStore.rule(ruleId) != null) {
            AlertStore.updateRuntime(ruleId) { runtime ->
                due = runtime.pendingDue
                if (runtime.pendingDue > 0L) runtime.copy(snoozedUntil = snoozeEnd, ringCount = 1) else runtime
            }
        }
        if (AlertPlayer.shownRuleId == ruleId) AlertPlayer.stop(AlertPlayer.StopReason.SNOOZED)
        val rule = AlertStore.rule(ruleId)
        if (rule != null && due > 0L) AlertPlayer.postReminder(rule, due, isTest = false, silent = true, asActive = false, snoozedUntil = snoozeEnd)
        if (!remote && rule != null) AlertSync.sendSnooze(rule, snoozeEnd)
        checkNow()
    }

    /** The notification was swiped away: quiet for now, "remind again" still applies. */
    fun silence(ruleId: String) = post {
        if (AlertPlayer.activeRuleId == ruleId) AlertPlayer.stop(AlertPlayer.StopReason.RESOLVED)
    }

    /** A connected device rang this reminder; count it as rung here so it does not ring twice. */
    fun markRungRemotely(ruleId: String, due: Long) = post {
        if (due <= 0L || AlertStore.rule(ruleId) == null) return@post
        val now = System.currentTimeMillis()
        AlertStore.updateRuntime(ruleId) {
            it.copy(
                handledDue = maxOf(it.handledDue, due),
                pendingDue = due,
                ringCount = maxOf(it.ringCount, 1),
                lastFired = now
            )
        }
        checkNow()
    }

    // --- Engine ---------------------------------------------------------------------------

    private fun checkNow() {
        val now = System.currentTimeMillis()
        val settings = AlertStore.settings.value
        if (!settings.enabled) {
            setAlarm(0L)
            return
        }
        val quiet = settings.isSnoozed(now)
        AlertStore.rules.value.filter { it.kind.isReminder && it.enabled }.forEach { rule ->
            try {
                check(rule, now, quiet)
            } catch (th: Throwable) {
                Log.stack(LOG_ID, "check ${rule.name}", th)
            }
        }
        schedule(now)
    }

    private fun check(rule: AlertRule, now: Long, quiet: Boolean) {
        val spec = rule.reminder
        val days = rule.schedule.days
        var runtime = AlertStore.runtimeOf(rule.id)
        if (runtime.handledDue == 0L) {
            // A new reminder starts with the next due time, never with one already past.
            AlertStore.updateRuntime(rule.id) { it.copy(handledDue = now) }
            return
        }

        val due = spec.latestBetween(runtime.handledDue, now, days)
        if (due != null) {
            if (runtime.pendingDue > 0L) {
                Log.i(LOG_ID, "${rule.name}: dose of ${runtime.pendingDue} left open, next one due")
                AlertPlayer.cancelReminder(rule.id)
                if (AlertPlayer.shownRuleId == rule.id) AlertPlayer.stop(AlertPlayer.StopReason.RESOLVED)
            }
            runtime = when {
                now - due > LATE_LIMIT_MS -> runtime.copy(handledDue = due, pendingDue = 0L, ringCount = 0)
                alreadyLogged(rule, due) -> {
                    Log.i(LOG_ID, "${rule.name}: already in the logbook")
                    runtime.copy(handledDue = due, pendingDue = 0L, ringCount = 0, lastTaken = now)
                }
                else -> runtime.copy(handledDue = due, pendingDue = due, ringCount = 0, snoozedUntil = 0L)
            }
            AlertStore.updateRuntime(rule.id) { runtime }
        }

        val pending = runtime.pendingDue
        if (pending <= 0L) return
        if (alreadyLogged(rule, pending)) {
            Log.i(LOG_ID, "${rule.name}: logged meanwhile, closing")
            closeDose(rule.id, pending, takenAt = now)
            return
        }
        val ringAt = nextRing(rule, runtime) ?: return
        if (ringAt > now) return
        if (!quiet && AlertPlayer.canPlay(rule) && AlertPlayer.activeRuleId != rule.id) {
            AlertStore.updateRuntime(rule.id) { it.copy(ringCount = it.ringCount + 1, lastFired = now) }
            shownQuietly.remove(rule.id to pending)
            AlertPlayer.play(rule, null, reminderDue = pending, eventTime = now)
        } else if (runtime.ringCount == 0 && shownQuietly.add(rule.id to pending)) {
            // It cannot ring yet, but the dose should be visible right away.
            AlertPlayer.postReminder(rule, pending, isTest = false, silent = true, asActive = false)
        }
    }

    /** When the open dose should ring next, or null when it has rung as often as allowed. */
    private fun nextRing(rule: AlertRule, runtime: AlertRuntime): Long? {
        if (runtime.pendingDue <= 0L) return null
        if (runtime.ringCount == 0) return maxOf(runtime.pendingDue, runtime.snoozedUntil)
        if (runtime.snoozedUntil > runtime.lastFired) return runtime.snoozedUntil
        val maxRepeats = rule.reminder.maxRepeats
        val repeatsLeft = maxRepeats == ReminderSpec.UNTIL_TAKEN || runtime.ringCount <= maxRepeats
        if (rule.repeatMinutes <= 0 || !repeatsLeft) return null
        return runtime.lastFired + maxOf(rule.repeatMinutes * 60_000L, RETRY_MS)
    }

    /** Next due time of [rule], for the editor and the list. */
    fun nextDue(rule: AlertRule, now: Long = System.currentTimeMillis()): Long? {
        if (!rule.enabled) return null
        val runtime = AlertStore.runtimeOf(rule.id)
        return rule.reminder.nextAfter(maxOf(runtime.handledDue, now), rule.schedule.days)
    }

    private fun schedule(now: Long) {
        val settings = AlertStore.settings.value
        var next = Long.MAX_VALUE
        if (settings.enabled) {
            AlertStore.rules.value.filter { it.kind.isReminder && it.enabled }.forEach { rule ->
                val runtime = AlertStore.runtimeOf(rule.id)
                rule.reminder.nextAfter(maxOf(runtime.handledDue, now), rule.schedule.days)?.let { next = minOf(next, it) }
                nextRing(rule, runtime)?.let { at ->
                    var wake = if (at <= now) now + RETRY_MS else at
                    if (settings.isSnoozed(now)) wake = maxOf(wake, settings.snoozeAllUntil)
                    next = minOf(next, wake)
                }
            }
        }
        setAlarm(if (next == Long.MAX_VALUE) 0L else next)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, ALARM_REQUEST,
        Intent(context, AlertActionReceiver::class.java).setAction(AlertActionReceiver.ACTION_REMINDER_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun setAlarm(at: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = alarmIntent()
        if (at <= 0L) {
            manager.cancel(intent)
            return
        }
        try {
            when {
                // The watch drops inexact and idle alarms too eagerly; the old reminders used this as well.
                Applic.isWearable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, intent), intent)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms() ->
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                else -> manager.setExact(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "setAlarm", th)
            runCatching { manager.set(AlarmManager.RTC_WAKEUP, at, intent) }
        }
    }

    /** True when exact timing is available; without it Android may deliver reminders late. */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    // --- Doses ----------------------------------------------------------------------------

    /**
     * Closes the open dose of [ruleId] when it is the one due at [due] (0 matches any) and
     * clears its notification. Returns true when a dose was open.
     */
    private fun closeDose(ruleId: String, due: Long, takenAt: Long = 0L): Boolean {
        var closed = false
        if (AlertStore.rule(ruleId) != null) {
            AlertStore.updateRuntime(ruleId) { runtime ->
                if (runtime.pendingDue > 0L && (due == 0L || runtime.pendingDue == due)) {
                    closed = true
                    runtime.copy(
                        pendingDue = 0L,
                        ringCount = 0,
                        snoozedUntil = 0L,
                        lastTaken = if (takenAt > 0L) takenAt else runtime.lastTaken
                    )
                } else {
                    runtime
                }
            }
        }
        shownQuietly.removeAll { it.first == ruleId }
        finishShown(ruleId)
        return closed
    }

    private fun finishShown(ruleId: String) {
        AlertPlayer.cancelReminder(ruleId)
        if (AlertPlayer.active.value?.rule?.id == ruleId) AlertPlayer.stop(AlertPlayer.StopReason.RESOLVED)
    }

    /**
     * True when the logbook already holds the dose due at [due]: an entry of the linked type
     * and at least the set amount, within the look-back time. The window never reaches past
     * the middle to the previous due time, so one entry cannot cover two doses.
     */
    private fun alreadyLogged(rule: AlertRule, due: Long): Boolean {
        val spec = rule.reminder
        if (!spec.isLinked || spec.lookBackMinutes <= 0 || !hasNativeLabel(spec.logLabel)) return false
        var since = due - spec.lookBackMinutes * 60_000L
        spec.latestBetween(since, due - 1, rule.schedule.days)?.let { previous -> since = maxOf(since, (previous + due) / 2) }
        return loggedSince(spec.logLabel, spec.logAmount, since)
    }

    private fun loggedSince(label: Int, minAmount: Float, sinceMillis: Long): Boolean {
        if (!Applic.Nativesloaded) return false
        val since = sinceMillis / 1000L
        return numio.numptrs.any { ptr ->
            ptr != 0L && runCatching {
                val first = Natives.getfirstNum(ptr)
                var pos = Natives.getlastNum(ptr) - 1
                while (pos >= first) {
                    val item = Natives.getNumitem(ptr, pos--) ?: continue
                    if (item.time <= 0L) continue
                    if (item.time < since) break
                    if (item.label == label && item.value > 0f && item.value >= minAmount) return@runCatching true
                }
                false
            }.getOrDefault(false)
        }
    }

    /** Adds the confirmed dose to this device's logbook. */
    private fun logDose(rule: AlertRule, time: Long) {
        val spec = rule.reminder
        if (!spec.logWhenTaken || !spec.isLinked || spec.logAmount <= 0f || !hasNativeLabel(spec.logLabel)) return
        val ptr = numio.numptrs.getOrNull(1)?.takeIf { it != 0L } ?: return
        try {
            Natives.saveNum(ptr, time / 1000L, spec.logAmount, spec.logLabel, 0)
            if (!Applic.isWearable) Applic.app?.numdata?.changedback(1)
            Log.i(LOG_ID, "${rule.name}: logged ${spec.logAmount}")
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "logDose", th)
        }
    }

    /** Reminder settings store native label indexes; reject stale indexes after labels change. */
    private fun hasNativeLabel(label: Int): Boolean {
        if (label < 0 || !Applic.Nativesloaded) return false
        return runCatching { label < (Natives.getLabels()?.size ?: 0) - 1 }.getOrDefault(false)
    }
}
