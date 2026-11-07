# Adzan Countdown

An Android app that starts a **15-minute countdown (adzan → iqamah)** each time
the adzan time arrives. On Samsung One UI 8 / One UI 9 (Android 16+), the
countdown shows up as a **Live Update**: a ticking chip in the status bar, on
the lock screen / AOD, and in the **Now Bar**.

## How it works

| Piece | File |
|---|---|
| Prayer-time calculation (offline, astronomical; Kemenag, MWL, ISNA, JAKIM, …) | `PrayerTimes.kt` |
| Arms an exact alarm for the next enabled adzan | `AdzanScheduler.kt` |
| Alarm fires → starts the countdown, arms the next alarm | `AdzanAlarmReceiver.kt` |
| Foreground service for the countdown; posts the "iqamah" alert at the end | `CountdownService.kt` |
| The Live Update notification | `CountdownNotification.kt` |
| Re-arms after reboot / time-zone change / app update | `RescheduleReceiver.kt` |
| Settings screen | `MainActivity.kt` |

### The status bar / Now Bar part

Samsung's Now Bar and status-bar chip on One UI 8+ show Android 16's
**Live Updates** (promoted ongoing notifications). The countdown notification
is built to meet the promotion rules:

- `setRequestPromotedOngoing(true)` + the `POST_PROMOTED_NOTIFICATIONS` permission
- ongoing, has a title, not colorized, no custom views
- `ProgressStyle` progress bar (adzan → iqamah)
- `setUsesChronometer(true)` + `setChronometerCountDown(true)` + `setWhen(iqamahTime)`,
  so System UI ticks the seconds itself in the chip and shade
- channel importance `DEFAULT` (silent), not `MIN`

On Android 15 and older, the same notification appears as a normal ongoing
notification with a countdown.

## Build

Requires the Android SDK (platform 36) and JDK 17+.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # prayer-time tests (checked against Kemenag's schedule for Jakarta)
```

## First run on the phone

1. Allow **notifications**.
2. Allow **Alarms & reminders** (exact alarms). Without this the countdown may start late
   and the progress bar won't update.
3. On Android 16+, make sure **Live Updates** is on (the app shows a button that opens it).
4. Tap **Use current location** (or type the coordinates), choose a method
   (default: Kemenag RI, +2 min ihtiyat), then **Save & schedule**.
5. Tap **Test countdown now (1 min)** to see the status bar chip / Now Bar right away.

On Samsung, also consider setting the app's battery usage to **Unrestricted**
(Settings → Apps → Adzan Countdown → Battery) so alarms aren't delayed.
