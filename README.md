# Adzan Countdown

An Android app that starts a **15-minute countdown (adzan → iqamah)** each time
the adzan time arrives. On Samsung One UI 8 / One UI 9 (Android 16+), the
countdown shows up as a **Live Update**: a ticking chip in the status bar, on
the lock screen / AOD, and in the **Now Bar**.

## Prayer times: choose your city

The app has two sources, chosen on the main screen:

1. **City — official Kemenag RI schedule (default).** Pick your kabupaten/kota
   (all 518 in Indonesia) from a searchable list. The schedule comes from the
   public **myQuran API**, which republishes Kemenag's official times:
   - `GET https://api.myquran.com/v2/sholat/kota/semua`: list of cities
   - `GET https://api.myquran.com/v2/sholat/jadwal/{cityId}/{yyyy}/{mm}`: one month of times

   The current and next month are stored on the phone, so the countdown works
   offline. The app tops up the download whenever it opens, at each adzan and
   after a reboot. Times are converted using the city's own zone (WIB/WITA/WIT),
   so they're correct even if the phone's clock zone differs.
2. **Coordinates — calculated on the phone.** For use outside Indonesia (or
   with no internet). Uses GPS or typed coordinates and a calculation method
   (Kemenag, MWL, ISNA, JAKIM, MUIS, Egypt, Karachi, Umm al-Qura).

If the city schedule hasn't been downloaded yet, the app falls back to the
calculated times, so you still get a countdown. The "Today" list shows which
source is in use.

## How it works

| Piece | File |
|---|---|
| City list + Kemenag schedule download/cache (myQuran API) | `KemenagRepository.kt` |
| Picks city schedule or calculation | `PrayerSchedule.kt` |
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
./gradlew testDebugUnitTest    # calculation checked against Kemenag's Jakarta schedule; API parsing + WIB/WITA/WIT tests
```

## First run on the phone

1. Allow **notifications**.
2. Allow **Alarms & reminders** (exact alarms). Without this the countdown may start late
   and the progress bar won't update.
3. On Android 16+, make sure **Live Updates** is on (the app shows a button that opens it).
4. Under **Prayer times**, keep **City** selected, tap **Choose city**, search
   (e.g. "bandung") and pick your kota/kabupaten. Then tap **Save & schedule**.
   (Outside Indonesia: choose **Coordinates** and use your location instead.)
5. Tap **Test countdown now (1 min)** to see the status bar chip / Now Bar right away.

On Samsung, also consider setting the app's battery usage to **Unrestricted**
(Settings → Apps → Adzan Countdown → Battery) so alarms aren't delayed.
