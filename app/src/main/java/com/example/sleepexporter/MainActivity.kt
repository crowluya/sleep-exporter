package com.example.sleepexporter

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Queries Android's UsageStatsManager for the past 30 days of usage events
 * (including SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE) and per-day aggregated stats,
 * then writes them as CSV to the app's external files dir (always writable, adb-pullable).
 *
 * Requires the user to grant "Usage access" (PACKAGE_USAGE_STATS).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Sleep Data Exporter\n\nQueries UsageStatsManager for the past 30 days and writes CSV to the app's external files dir (adb-pullable)."
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }
        container.addView(title)

        val exportBtn = Button(this).apply { text = "Export now (past 30 days)" }
        exportBtn.setOnClickListener { doExport() }
        container.addView(exportBtn)

        val permBtn = Button(this).apply { text = "Open Usage Access settings" }
        permBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        container.addView(permBtn)

        logView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 32, 0, 0)
            text = "Ready. Tap 'Export now' after granting Usage Access."
        }
        container.addView(logView)

        setContentView(container)
    }

    private fun log(msg: String) {
        runOnUiThread { logView.text = logView.text.toString() + "\n" + msg }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun doExport() {
        if (!hasUsageStatsPermission()) {
            log("ERROR: Usage Access not granted. Tap the button below to grant.")
            return
        }

        Thread {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val daysBack = 30L
                val start = now - daysBack * 24L * 60L * 60L * 1000L

                log("Querying events $daysBack days back...")

                val sb = StringBuilder()
                sb.append("timestamp_ms,timestamp_iso,event_type,package\n")
                val events = usm.queryEvents(start, now)
                val ev = UsageEvents.Event()
                var evCount = 0
                val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    val typeStr = when (ev.eventType) {
                        UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
                        UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
                        UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
                        UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
                        UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
                        UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
                        UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
                        else -> "OTHER_${ev.eventType}"
                    }
                    sb.append(ev.timeStamp).append(',')
                      .append(iso.format(Date(ev.timeStamp))).append(',')
                      .append(typeStr).append(',')
                      .append(ev.packageName).append('\n')
                    evCount++
                }
                log("Got $evCount events.")

                sb.append("\n\n### DAILY_AGGREGATED ###\n")
                sb.append("day_start_ms,day_start_iso,package,total_time_used_ms,last_time_used_ms\n")
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = start
                while (cal.timeInMillis < now) {
                    val dayStart = cal.timeInMillis
                    cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                    val dayEnd = cal.timeInMillis
                    val stats = usm.queryAndAggregateUsageStats(dayStart, dayEnd)
                    val dayIso = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(dayStart))
                    for ((pkg, st) in stats) {
                        if (st.totalTimeInForeground <= 0) continue
                        sb.append(dayStart).append(',').append(dayIso).append(',')
                          .append(pkg).append(',').append(st.totalTimeInForeground).append(',')
                          .append(st.lastTimeUsed).append('\n')
                    }
                }
                log("Daily aggregation done.")

                // Primary write: app-private external dir (always writable, adb-pullable)
                val outDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                outDir.mkdirs()
                val outFile = File(outDir, "sleep_export_${now}.csv")
                outFile.writeText(sb.toString())
                log("WROTE: ${outFile.absolutePath}")
                log("Size: ${outFile.length()} bytes, events: $evCount")

                // Best-effort: try to also drop a copy on public /sdcard/Download/
                try {
                    val pubDir = File(Environment.getExternalStorageDirectory(), "Download")
                    if (pubDir.isDirectory) {
                        val pubFile = File(pubDir, "sleep_export_${now}.csv")
                        pubFile.writeText(sb.toString())
                        log("ALSO AT: ${pubFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    log("(public copy skipped: ${e.message})")
                }

                log("\nDONE. Pull on PC with:")
                log("adb pull ${outFile.absolutePath}")
            } catch (e: Exception) {
                log("FAILED: ${e.javaClass.simpleName}: ${e.message}")
                log(e.stackTraceToString())
            }
        }.start()
    }
}
