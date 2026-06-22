package com.stayhmbl.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import com.stayhmbl.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hc: HealthConnectClient? = null

    private val permissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(permissions)) {
                setStatus("Permissions granted. Ready.")
            } else {
                setStatus("Permissions denied. Open Health Connect and allow Stay Hmbl.")
            }
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore result */ }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != StepsWriterService.BROADCAST_PROGRESS) return
            val state = intent.getStringExtra(StepsWriterService.EXTRA_STATE) ?: return
            val pct = intent.getIntExtra(StepsWriterService.EXTRA_PROGRESS_PCT, 0)
            val written = intent.getLongExtra(StepsWriterService.EXTRA_WRITTEN, 0L)
            val total = intent.getLongExtra(StepsWriterService.EXTRA_TOTAL_OUT, 0L)
            val rDone = intent.getIntExtra(StepsWriterService.EXTRA_RECORDS_DONE, 0)
            val rTotal = intent.getIntExtra(StepsWriterService.EXTRA_RECORDS_TOTAL, 0)
            val eta = intent.getLongExtra(StepsWriterService.EXTRA_ETA_SEC, 0L)
            val msg = intent.getStringExtra(StepsWriterService.EXTRA_MESSAGE) ?: ""

            when (state) {
                "running" -> {
                    binding.progress.visibility = View.VISIBLE
                    binding.txtProgress.visibility = View.VISIBLE
                    binding.progress.progress = pct
                    if (msg.isNotEmpty()) {
                        setStatus(msg)
                    } else {
                        setStatus("Writing ${formatNum(written)} / ${formatNum(total)} steps")
                    }
                    binding.txtProgress.text =
                        "records  $rDone / $rTotal   ·   eta  ${if (eta > 0) formatDuration(eta) else "--"}"
                    binding.btnWrite.isEnabled = false
                    binding.btnClear.isEnabled = false
                }
                "done" -> {
                    binding.progress.progress = 1000
                    setStatus(msg.ifEmpty { "Done." })
                    binding.txtProgress.text = "complete"
                    binding.btnWrite.isEnabled = true
                    binding.btnClear.isEnabled = true
                }
                "error" -> {
                    setStatus(getString(R.string.status_err, msg))
                    binding.btnWrite.isEnabled = true
                    binding.btnClear.isEnabled = true
                }
                "cancelled" -> {
                    setStatus("Cancelled.")
                    binding.btnWrite.isEnabled = true
                    binding.btnClear.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val status = HealthConnectClient.getSdkStatus(this)
        when (status) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                setStatus("Health Connect not available on this device.")
                binding.btnWrite.isEnabled = false
                binding.btnClear.isEnabled = false
                return
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                setStatus("Update Health Connect app from Play Store, then reopen.")
                binding.btnWrite.isEnabled = false
                binding.btnClear.isEnabled = false
                return
            }
        }

        hc = HealthConnectClient.getOrCreate(this)
        ensurePermissions()
        ensureNotificationPermission()

        binding.btnWrite.setOnClickListener {
            val raw = binding.inputSteps.text.toString().trim()
            val total = raw.toLongOrNull()
            if (total == null || total <= 0) {
                Toast.makeText(this, "Enter a positive number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startWriteService(total)
        }

        binding.btnClear.setOnClickListener { clearToday() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(StepsWriterService.BROADCAST_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(progressReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(progressReceiver) }
    }

    private fun startWriteService(total: Long) {
        binding.progress.visibility = View.VISIBLE
        binding.txtProgress.visibility = View.VISIBLE
        binding.progress.progress = 0
        setStatus("Preparing…")
        binding.btnWrite.isEnabled = false
        binding.btnClear.isEnabled = false

        val i = Intent(this, StepsWriterService::class.java)
            .setAction(StepsWriterService.ACTION_START)
            .putExtra(StepsWriterService.EXTRA_TOTAL, total)
        ContextCompat.startForegroundService(this, i)
    }

    private fun ensurePermissions() {
        lifecycleScope.launch {
            val client = hc ?: return@launch
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(permissions)) {
                requestPermissions.launch(permissions)
            } else {
                setStatus("Ready.")
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun clearToday() {
        val client = hc ?: return
        binding.btnWrite.isEnabled = false
        binding.btnClear.isEnabled = false
        binding.progress.visibility = View.GONE
        binding.txtProgress.visibility = View.GONE
        setStatus("Clearing today…")
        lifecycleScope.launch {
            try {
                val zone = ZoneId.systemDefault()
                val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                val now = Instant.now()
                val read = client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                        dataOriginFilter = setOf(),
                    )
                )
                val ours = read.records.filter { it.metadata.dataOrigin.packageName == packageName }
                if (ours.isEmpty()) {
                    setStatus("No Stay Hmbl records for today.")
                } else {
                    val ids = ours.map { it.metadata.id }
                    val batch = 1000
                    withContext(Dispatchers.IO) {
                        var i = 0
                        while (i < ids.size) {
                            val end = minOf(i + batch, ids.size)
                            client.deleteRecords(
                                recordType = StepsRecord::class,
                                recordIdsList = ids.subList(i, end),
                                clientRecordIdsList = emptyList(),
                            )
                            i = end
                        }
                    }
                    setStatus("Cleared ${ours.size} records.")
                }
            } catch (t: Throwable) {
                setStatus(getString(R.string.status_err, t.message ?: t.javaClass.simpleName))
            } finally {
                binding.btnWrite.isEnabled = true
                binding.btnClear.isEnabled = true
            }
        }
    }

    private fun setStatus(s: String) { binding.txtStatus.text = s }

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
