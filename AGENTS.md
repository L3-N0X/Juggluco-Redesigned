# AI Agent Guidelines & Architecture for Juggluco

## Design & UX Vision
- **Modern, Polished Consumer Experience:** Juggluco's Compose UI prioritizes a clean, modern, and delightful UX using Material 3 principles. The app should feel like a premier modern Android application, explicitly avoiding stereotypical, dated "clinical" or "medical" aesthetics.
- **No Forced Medical/Clinical Colors:** Do not restrict color choices to clinical stereotypes or force medical color rules. Strive for harmonious, contemporary Material 3 color palettes with clear visual hierarchy, balanced contrast, and accessible typography.
- **First-Class Dark Theme Support:**
  - Never use hardcoded light/white backgrounds or fixed vibrant colors that look glaring or out of place in dark mode.
  - Pair status and category indicators using dynamic container/content color tokens (e.g., `container` and `onContainer`/`primary`).
  - Dark mode surfaces should use dark, tinted tonal elevations rather than harsh stark fills.
- **Logbook & Graph Aesthetics:**
  - Log events (Bolus, Basal, Carbs, Blood Glucose checks, Notes) use centralized, dark-mode adaptive theme tokens in `tk.glucodata.ui.theme.LogbookColors` (`MaterialTheme.logbookColors`).
  - Graph curves, markers, and axis elements must respect dynamic theme tokens and avoid visual clutter or overlapping labels.
  - Type hues (bolus azure, carbs tangerine, basal violet, finger-prick teal) must stay clear of the glucose range hues (sage, amber, coral) so an event marker never reads as a reading's status.

## Logbook Entry
- **One editor:** `ui/components/LogEntryEditor.kt` is a full-screen editor in its own window (so it also opens above the logbook sheet). It handles new entries, edits and deletes; list rows and graph markers open it on tap instead of carrying edit/delete icons.
- **Built around real usage:** carbs + bolus sit side by side and save together at one timestamp; basal and finger-pricks are one tap away. When basal is usually taken around the current time, the usual dose is offered as a one-tap chip.
- **No fixed presets:** quick values and note suggestions come from the user's own history (computed on `Dispatchers.Default`). Never add hardcoded amounts.
- **Wear keeps the dial** (`WearQuickLogScreen`), tinted with the same type colors and starting from the last logged amount.

## Logbook Labels
- **Full name + short name:** native keeps a label's name in a `char[12]` of the mmapped `settings.dat`, read by the classic view, Garmin, Nightscout and every mirroring Juggluco. That field stays and holds the **short name** (≤ 11 bytes of *modified* UTF-8, an emoji takes 6). The full name, of any length (`LabelNames.MAX_NAME_LENGTH`, 64, never noticeable), lives in `ui/data/LabelNames.kt` next to the short name it was saved with and only applies while native still holds that short name, so a rename made elsewhere shows up instead of being covered.
- **Nobody has to think about the short one:** the editor derives it from the name (`LabelNames.deriveShort`: the name if it fits, else its first word, else cut with a dot) until the user types their own. `saveLabel` cuts anything too long to fit, so a label can always be saved — never bring back a byte-limit error on the name.
- **Which name where:** the full name everywhere there is room (logbook rows via `LogRecord.title()`, the editor tiles, settings, reminders, LibreView/Nightscout mappings), the short one where space is tight (totals, filter chips, the watch dial) via `LabelConfig.displayName(type)` / `shortDisplayName(type)`. Never hardcode "Bolus"/"Basal"/"Carbs" next to an entry; the type strings are only the fallback while no label is set.
- **Sync:** the full names travel to a mirroring watch inside `DisplaySync`'s `labels` message; other native mirrors see the short names.
- **Defaults:** `LabelDefaults` renames, once, every label that still carries one of native's old default names in any language ("Ins schnell", "radeln", "Fast Insuli") to Carbs / Hypo treatment / Bolus insulin / Basal insulin / Exercise / Ketones / Finger prick, each with a short name. Positions keep their meaning, so existing entries stay what they were; the walk position becomes Ketones only while nothing was logged under it. It only runs where `label_defaults_language` matches the device language, so untranslated languages keep native's own localized names.

---

## Insulin Onboard (IOB)
- **Off by default, and that is a decision:** the toggle lives in Settings → Glucose & alerts → Insulin on board (`SettingsDestination.INSULIN_ONBOARD`, `ui/screens/settings/InsulinOnboardSettingsScreen.kt`). Nothing is calculated or shown until someone turns it on.
- **The types come first:** native refuses `setIOB(true)` while every label still holds `Insulin::Not` (`javasettings.cpp` `hasRapidInsulin()`), so the screen offers the per-label picker *and* spells the refusal out under the switch. Never add a switch somewhere that can only snap back silently.
- **Two stores, one bridge:** Compose keeps the bolus label in `NativeLabels`, the calculation reads native `insulintypes[]`. `NativeInsulinOnboard.ensureInsulinType()` closes the gap — picking a bolus label or mapping a label as rapid acting insulin in LibreView gives it Aspart unless it already holds a type. A type the user chose is never overwritten.
- **Wrapper, not `Natives`:** go through `ui/data/NativeInsulinOnboard.kt`. `setInsulinType`/`getInsulinType` are phone-only exports and `getIOBvalue` is the one read that Wear OS has too, so every call answers with the safe value instead of throwing.
- **Shown where the reading is:** `CurrentGlucoseHeroCard` and `WearGlucoseHero` carry the value next to the delta, as plain text in the bolus hue (never a pill, never a glucose range hue). The repository publishes it (`insulinOnboard`) and refreshes it every 30 s plus after every logbook change, since it only moves by decaying otherwise.
- **Device-local on/off:** the flag lives in each device's own `settings.dat` and is not synced; only the insulin types travel to a watch (`datbackup.cpp`), which is why the watch offers the switch and not the picker.

---

## Technical Stack & Architecture
- **UI Framework:** Jetpack Compose with Material 3 (`androidx.compose.material3`).
- **Target Modules:** Android Mobile & Wear OS. Code shared across platforms resides primarily in `Common/src/main/java/tk/glucodata/ui/`.
- **State & Performance:**
  - Viewport state is decoupled between interactive gesture drawing and settled calculation windows (`rememberSettledWindow`).
  - Heavy metric recalculations (TIR, GMI, averages, log filtering) must run off the main thread on `Dispatchers.Default` to prevent frame drops or recomposition churn during panning/zooming.
- **Theme Tokens:**
  - Palette definitions: `Common/src/main/java/tk/glucodata/ui/theme/Color.kt`
  - CompositionLocals and theme wrapper: `Common/src/main/java/tk/glucodata/ui/theme/Theme.kt` (`LocalClinicalColors`, `LocalLogbookColors`, `MaterialTheme.logbookColors`).

---

## Alerts
- **Engine, not native levels:** glucose alerts are user-defined `AlertRule`s in `Common/src/main/java/tk/glucodata/alerts/`. `AlertEngine` evaluates every reading (hooked in `SuperGattCallback.dowithglucose`) and signal loss (`GlucoseAlarms.handlealarm`) on both phone and watch; the native alarm codes only still drive the value-available chime.
- **Playback:** `AlertPlayer` owns sound, vibration, notification and the full-screen alert (`AlertActivity` on the phone, `WearAlertActivity` on the watch). Stop alerts through `AlertPlayer.dismiss()/snooze()`, which also sync; `Notify.stopAllAlarms()` is the entry point for legacy user actions.
- **Phone ↔ watch sync:** `AlertSync` sends ring/stop/snooze events and the phone's alert list over the `/alerts` Wear message path. Received events are applied without being re-sent. Device-local settings (on/off, sound here, mirror alerts, use phone's alerts) are never overwritten by a sync.

---

## Home Screen Widgets
- **Drawn, not inflated:** the widgets in `Common/src/mobile/java/tk/glucodata/widgets/` (Minimal, Compact, Trend graph, Dial, Time in range) are painted by `WidgetRenderer` onto a bitmap per launcher size and shown through `layout/home_widget.xml`. The configuration screen (`WidgetConfigActivity`, also the in-app gallery under Display settings) previews with the same renderer, so preview and home screen always match.
- **Colors come from the app theme:** `WidgetPalette` uses `jugglucoColorScheme()` (custom accent / Material You / fallback) and the `ClinicalColors` range hues, picking light or dark by what is behind the content (system mode, custom color luminance, or the wallpaper for transparent widgets).
- **Per-widget settings** live in `WidgetConfigStore` (JSON per app widget id). Add new options to `WidgetConfig` with a default so stored widgets keep working.
- **Updates** go through `WidgetUpdater.requestUpdateAll()` (hooked in `GlucoseWidget.update()/oldvalue()`), which reads one `WidgetSnapshot` off the main thread for all widgets. The old `GlucoseWidget` stays registered as "Classic" so placed instances keep working.

---

## Glucose Notification & Status Bar Icons
- **Drawn with the widget renderer:** the phone's glucose notification (`Common/src/mobile/java/tk/glucodata/notifications/`) is painted by `NotificationRenderer` from `WidgetRenderer.Frame`'s building blocks, so value, arrow and graph match the widgets. Collapsed: value, arrow, Δ change over the reading time, optional mini graph. Expanded: the same row plus graph and time-in-range bar. Colors come from `WidgetPalette.forNotification` (system light/dark), and the notification is redrawn when the system theme flips.
- **Status bar icons:** `StatusIconRenderer` draws alpha-only icons (value, arrow, value + arrow, change) centred on the digits' real outline. The glucose notification carries one; up to two extra silent notifications (`statusBarIcons` channel, local only) carry more. Alerts reuse the value icon via `Notify.setIcon`.
- **Bridge:** `Notify.java` (main) reaches the phone code through `tk.glucodata.GlucoseNotifications`, which has a no-op stub in `wear/` (symlinked into `small/`). The legacy `RemoteGlucose` layout remains only for native alarms with a stop button and as the fallback.
- **Live Update (Android 16):** with `liveUpdate` on and `canPostPromotedNotifications()` true, `LiveUpdate` swaps the drawn content for a system template (promotion forbids custom views and colorizing): title/text, `ProgressStyle` as a range gauge, chip icon + `setShortCriticalText`, and the `android.requestPromotedOngoing` extra (the setter is public only from API 36.1). `Notify.fornotify` updates a Live Update in place instead of cancelling first, so the chip does not blink. Otherwise it falls back to the drawn notification.
- **Settings** live in `NotificationConfigStore` (one JSON object) and are edited in `NotificationSettingsActivity`, opened from Settings → Notifications. Saving redraws through `NotificationRefresher`. Whether the notification shows at all stays the native `showalways` flag. Add new options to `NotificationConfig` with a default.

---

## Glucose Meters (secondary feature)
- **Off by default:** reading a Bluetooth finger-prick meter is a rarity next to a CGM sensor, so the whole feature stays switched off until someone asks for it. The flag is `MeterSettings.isEnabled/setEnabled` (SharedPreferences `meters_prefs`), defaulting to *on* only when a meter is already stored, so nobody loses a setup that used to work. `Applic.initbluetooth` only calls `BluetoothGlucoseMeter.startDevices()` when it is on, and Settings → Connectivity shows one row that both toggles it and opens the screen.
- **Split like the notifications:** the state lives in the shared source set, the Bluetooth does not. `ui/meters/MeterModels.kt` and `ui/meters/MeterBle.kt` (interface + `UnsupportedMeterBle`) are in `main`; `tk.glucodata.MeterBleSupport` is the phone implementation in `mobile/` and delegates to the no-op in `wear/` (symlinked into `small/`). The screen and `GlucoseRepository` only ever talk to the interface, which is why `MetersSettingsScreen` compiles for the watch too.
- **Inventory stays native:** there is no `Meter` model class. Name, address, active flag, last reading and the blood-after cutoff all come from the `Natives.GlucoseMeter*` calls into `settings.dat`. **A meter's `index` is its position, not an id** — removing one shifts the ones behind it, and `getActiveGlucoseMeters()` purges AiDEX X entries, so the list is always reread after a change rather than patched.
- **Discovery** goes through `MeterScanner.DeviceFoundListener` (new, alongside the old RecyclerView adapter path) and decides per device whether the address or just the name identifies it, exactly as `MeterScanner.shouldUseDeviceAddress` does for the legacy list. `MeterBleSupport` holds the scanned `BluetoothDevice`s until the user picks one.
- **Blood label** is the app-wide `bloodLabelIndex`, so the editor reuses `repository.setBloodLabelIndex` and refuses to save without one: readings are dropped by `GlucoseMeterSave` when no label is set. Saving a cutoff calls `GlucoseMeterSetLastTime`, which also switches the meter on and resets its record position — that is wanted, a meter nobody talks to collects nothing.

---

## Stale Readings
- **One rule, told the time:** a reading is current while there is one and it is younger than `Notify.glucosetimeout`; past that the last value stays, greyed and struck through (`StaleReading.isStale(timestamp, now)`), without arrow or change. Every surface goes by it: the Compose hero cards, the widgets, the notification.
- **Compose derives, it never remembers the verdict:** `rememberReadingAge(timestamp)` (`ui/components/StaleReadingState.kt`) returns the minutes and the verdict off a clock that is itself keyed on the timestamp, and wakes itself at the next minute and at the aging-out deadline. Nothing may cache "is stale" in a `produceState`/`remember`: a key change restarts the producer but keeps its old value, so a verdict set once latched for the rest of the composition - a value that had aged out stayed struck through right next to a "just now" that did keep up. The clock is also what the "x min ago" line reads, so age and strike-through can never disagree.
- **The drawn surfaces are computed, not cached:** the widget, the notification, the complications and the legacy float re-read `Natives.lastglucose()` on every event, so a new reading clears them by itself. Keep it that way - no cached strike-through state, no cached bitmap that outlives the reading it drew.

## Screen Layout & Headers
- **Shared layout module:** `Common/src/main/java/tk/glucodata/ui/screens/ScreenLayout.kt` owns the spacing scale (`Gutter` / `CardPadding` 16.dp, `SectionSpacing`, `TopPadding`, `BottomPadding` 96.dp) plus `ScreenContent { }` (standard scrolling tab body) and `SectionTitle(...)`. Use these instead of ad-hoc dp values.
- **One title per screen:** the persistent app bar supplies it — `JugglucoApp`'s `TopAppBar` for every tab, `SettingsDetailScaffold` for detail screens. Never repeat the page title in the content, and leave descriptions out unless they earn their space.
- **Two padding levels, never three:** screen gutter → card padding. Group content inside a card with spacing or `HorizontalDivider`, not another padded/tinted container — nesting a third level is the "double indent" bug.
- **No cards for screen sections:** sections (stats blocks, the current sensor, calls to action) sit directly on the screen background, separated by spacing, `SectionTitle`s or `HorizontalDivider`s. Cards are only for repeated list items, filled with `ScreenLayout.cardContainerColor`.
- **No badges:** don't put status pills/chips next to titles or values. Show status as plain text or by tinting the value.
- **Gutters belong to the parent:** screen-level Columns apply the gutter; cards and sections (hero card, stats card, time range pills, graph, logbook) fill the width and carry no horizontal padding of their own.

---

## Verification & Build Commands
Before finalizing UI or logic changes, always verify both Mobile and Wear OS builds:
```bash
# Mobile target compilation
./gradlew compileMobileLibre3SiDexGoogleDebugSources

# Wear OS target compilation
./gradlew compileWearLibre3SiDexGoogleDebugSources
```
