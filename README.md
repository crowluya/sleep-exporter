# Sleep Exporter

A tiny Android app that queries `UsageStatsManager` for the past 30 days of usage events
(including `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE`, `ACTIVITY_RESUMED/PAUSED`,
`KEYGUARD_*`) plus per-day aggregated per-app usage stats, and exports them to a CSV file.

The CSV is written to:
- `/sdcard/Download/sleep_export_<timestamp>.csv` (preferred)
- `/sdcard/sleep_export_<timestamp>.csv` (fallback)
- App external dir if neither is writable

## Why

`adb shell dumpsys usagestats` only dumps the **last 24 hours** of fine-grained events.
The system actually stores more (typically ~14 days), but only a Java/Kotlin app calling
`UsageStatsManager.queryEvents(start, end)` can retrieve the deeper history. This app is
that minimal probe.

## Steps on the phone

1. Install the APK: `adb install app-debug.apk`
2. Open "Sleep Exporter" in launcher.
3. Tap "Open Usage Access settings", find "Sleep Exporter", toggle it on.
4. Come back to the app, tap "Export now (past 30 days)".
5. See the printed path.
6. On PC: `adb pull /sdcard/Download/sleep_export_<timestamp>.csv`

## Permission

Only the special-role `PACKAGE_USAGE_STATS` ("Usage access") permission is required.
No INTERNET, no STORAGE runtime permission (uses app-external + public Downloads, which
on Android 10+ works via MediaStore / shared storage).

The app has NO network code. Inspect `MainActivity.kt`.
