package com.stayhmbl.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepsWriterService : Service() {

    companion object {
        const val ACTION_START = "com.stayhmbl.app.START"
        const val ACTION_STOP = "com.stayhmbl.app.STOP"
        const val EXTRA_TOTAL = "total"
        const val CHANNEL_ID = "stay_hmbl_writes"
        const val CHANNEL_ID_DONE = "stay_hmbl_done"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_ID_DONE = 1002

        const val BROADCAST_PROGRESS = "com.stayhmbl.app.PROGRESS"
        const val EXTRA_PROGRESS_PCT = "pct"
        const val EXTRA_WRITTEN = "written"
        const val EXTRA_TOTAL_OUT = "total_out"
        const val EXTRA_RECORDS_DONE = "records_done"
        const val EXTRA_RECORDS_TOTAL = "records_total"
        const val EXTRA_ETA_SEC = "eta_sec"
        const val EXTRA_STATE = "state"          // running | done | error | cancelled
        const val EXTRA_MESSAGE = "message"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                job?.cancel()
                stopForegroundCompat()
                stopSelf()
                broadcast(state = "cancelled", message = "Cancelled by user")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val total = intent.getLongExtra(EXTRA_TOTAL, 0L)
                startForegroundWithNotification(0, "Starting…")
                job?.cancel()
                job = scope.launch { writeLoop(total) }
            }
        }
        return START_STICKY
    }

    private suspend fun writeLoop(total: Long) {
        val client = HealthConnectClient.getOrCreate(this)
        val startWallMs = System.currentTimeMillis()
        try {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val totalMillis = (now.toEpochMilli() - startOfDay.toEpochMilli()).coerceAtLeast(2L)

            val perRecord = 1_000_000L
            val recordCountLong = (total + perRecord - 1) / perRecord
            val recordCount = minOf(recordCountLong, totalMillis).toInt()
            val perRecordEffective = (total + recordCount - 1) / recordCount
            val sliceMillis = (totalMillis / recordCount).coerceAtLeast(1L)
            val offset = zone.rules.getOffset(now)

            val batchSize = 200
            val basePauseMs = 500L      // ~2 calls per second, well under HC's foreground rate limit
            val quotaBackoffMs = 15_000L // refill cycle for the rate-limit bucket
            val maxRetries = 20
            var inserted = 0
            var remaining = total
            val batch = ArrayList<StepsRecord>(batchSize)

            var i = 0
            while (i < recordCount) {
                val end = minOf(i + batchSize, recordCount)
                batch.clear()
                for (j in i until end) {
                    val sliceStart = startOfDay.plusMillis(j * sliceMillis)
                    val sliceEnd = if (j == recordCount - 1) now
                    else startOfDay.plusMillis((j + 1) * sliceMillis - 1)
                    val take = minOf(perRecordEffective, remaining)
                    if (take <= 0) break
                    remaining -= take
                    batch += StepsRecord(
                        count = take,
                        startTime = sliceStart,
                        endTime = sliceEnd,
                        startZoneOffset = offset,
                        endZoneOffset = offset,
                    )
                }
                if (batch.isEmpty()) break

                var attempt = 0
                while (true) {
                    try {
                        client.insertRecords(batch)
                        break
                    } catch (e: Throwable) {
                        val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
                        if (msg.contains("quota", ignoreCase = true) && attempt < maxRetries) {
                            attempt++
                            val written = total - remaining - batch.size.toLong() * perRecordEffective.coerceAtLeast(1L)
                            val pct = (inserted.toLong() * 1000L / recordCount).toInt()
                            updateNotification(
                                pct,
                                "Waiting for Health Connect (rate limited)",
                                "retry ${attempt}/${maxRetries} in ${quotaBackoffMs / 1000}s"
                            )
                            broadcast(
                                pct = pct, written = total - remaining, totalOut = total,
                                recordsDone = inserted, recordsTotal = recordCount,
                                etaSec = 0L, state = "running",
                                message = "Rate limited, retrying in ${quotaBackoffMs / 1000}s (attempt ${attempt}/${maxRetries})"
                            )
                            delay(quotaBackoffMs)
                            continue
                        }
                        throw e
                    }
                }

                inserted += batch.size
                i = end

                val written = total - remaining
                val pct = (inserted.toLong() * 1000L / recordCount).toInt()
                val elapsed = (System.currentTimeMillis() - startWallMs) / 1000.0
                val rate = if (elapsed > 0) inserted / elapsed else 0.0
                val etaSec = if (rate > 0) ((recordCount - inserted) / rate).toLong() else 0L

                updateNotification(
                    pct,
                    "Writing  ${formatNum(written)} / ${formatNum(total)}",
                    "$inserted / $recordCount records · eta ${formatDuration(etaSec)}"
                )
                broadcast(
                    pct = pct, written = written, totalOut = total,
                    recordsDone = inserted, recordsTotal = recordCount,
                    etaSec = etaSec, state = "running"
                )
                if (inserted % 5000 == 0) System.gc()
                delay(basePauseMs)
            }

            postDoneNotification(
                title = "Done. ${formatNum(total)} steps written.",
                text = "$inserted records inserted into Health Connect."
            )
            broadcast(
                pct = 1000, written = total, totalOut = total,
                recordsDone = inserted, recordsTotal = recordCount,
                etaSec = 0, state = "done",
                message = "Wrote ${formatNum(total)} steps across $inserted records."
            )
        } catch (t: Throwable) {
            updateNotification(0, "Error", t.message ?: t.javaClass.simpleName)
            broadcast(state = "error", message = t.message ?: t.javaClass.simpleName)
        } finally {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun startForegroundWithNotification(pct: Int, text: String) {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(-1, "Stay Hmbl", text))
    }

    private fun updateNotification(pct: Int, title: String, text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(pct, title, text))
    }

    private fun buildNotification(pct: Int, title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StepsWriterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Stay Hmbl")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        if (pct < 0) {
            builder.setProgress(0, 0, true) // indeterminate while preparing
        } else {
            builder.setProgress(1000, pct.coerceAtMost(1000), false)
        }
        return builder.build()
    }

    private fun postDoneNotification(title: String, text: String) {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Stay Hmbl")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID_DONE, n)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Step writes",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Foreground notification while writing steps." }
                mgr.createNotificationChannel(ch)
            }
            if (mgr.getNotificationChannel(CHANNEL_ID_DONE) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID_DONE, "Step writes complete",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Confirmation when a step write finishes." }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun broadcast(
        pct: Int = 0, written: Long = 0, totalOut: Long = 0,
        recordsDone: Int = 0, recordsTotal: Int = 0, etaSec: Long = 0,
        state: String, message: String = ""
    ) {
        val i = Intent(BROADCAST_PROGRESS).setPackage(packageName)
            .putExtra(EXTRA_PROGRESS_PCT, pct)
            .putExtra(EXTRA_WRITTEN, written)
            .putExtra(EXTRA_TOTAL_OUT, totalOut)
            .putExtra(EXTRA_RECORDS_DONE, recordsDone)
            .putExtra(EXTRA_RECORDS_TOTAL, recordsTotal)
            .putExtra(EXTRA_ETA_SEC, etaSec)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_MESSAGE, message)
        sendBroadcast(i)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun formatNum(n: Long): String = String.format("%,d", n)
    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val m = seconds / 60
        val s = seconds % 60
        if (m < 60) return "${m}m ${s}s"
        val h = m / 60
        val rm = m % 60
        return "${h}h ${rm}m"
    }
}
