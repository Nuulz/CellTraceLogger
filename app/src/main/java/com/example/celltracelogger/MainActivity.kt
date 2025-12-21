package com.example.celltracelogger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import java.io.File

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvEventCount: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDebugFiles: Button
    private lateinit var tvDebugResult: TextView

    // Estado del servicio
    private var isServiceRunning = false

    // Receiver
    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i("CelltraceLogger", "Broadcast CELLTRACE_STATS recibido")
            val events = intent?.getIntExtra("events", 0) ?: 0
            val file = intent?.getStringExtra("file") ?: "—"

            if (::tvEventCount.isInitialized) {
                tvEventCount.text = events.toString()
            }
            if (::tvCurrentFile.isInitialized) {
                tvCurrentFile.text = file
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PHONE_STATE = 1000
        private const val REQUEST_CODE_LOCATION_FOREGROUND = 2000
        private const val REQUEST_CODE_LOCATION_BACKGROUND = 3000
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 4000
    }

    private fun allForegroundLocationPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun phoneStatePermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun backgroundLocationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun canStartLogging(): Boolean {
        return phoneStatePermissionGranted() &&
                allForegroundLocationPermissionsGranted() &&
                backgroundLocationPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AppConfig.isConfigured(this)) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        Log.i("CelltraceLogger", "MainActivity started")

        // Inicializa vistas
        tvStatus = findViewById(R.id.tvStatus)
        tvEventCount = findViewById(R.id.tvEventCount)
        tvCurrentFile = findViewById(R.id.tvCurrentFile)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDebugFiles = findViewById(R.id.btnDebugFiles)
        tvDebugResult = findViewById(R.id.tvDebugResult)

        // Estado inicial
        updateServiceStatus(false)

        val btnSettings = findViewById<Button>(R.id.btnSettings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Solicita POST_NOTIFICATIONS si Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_POST_NOTIFICATIONS)
            }
        }

        btnStart.setOnClickListener {
            if (canStartLogging()) {
                startForegroundService(Intent(this, CellLoggerService::class.java))
                CellLoggerService.sendStatusToDiscord(this, "start")
                updateServiceStatus(true)
                Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
            } else {
                requestNecessaryPermissions()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, CellLoggerService::class.java))
            CellLoggerService.sendStatusToDiscord(this, "stop")
            CellLoggerService.sendCurrentFileImmediately(this)
            updateServiceStatus(false)
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        }

        btnDebugFiles.setOnClickListener {
            val cacheFile = File(getExternalFilesDir(null), "cached_cells.csv")
            val cacheLines = if (cacheFile.exists()) {
                try {
                    cacheFile.readLines().size - 1
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }

            val info = StringBuilder()
            info.append("📊 CACHE STATISTICS\n\n")
            info.append("💾 Cached cells: $cacheLines\n")
            info.append("📁 File: ${cacheFile.name}\n")
            info.append("📦 Size: ${cacheFile.length() / 1024} KB\n\n")
            info.append("─────────────────────\n\n")
            info.append(debugListFiles())

            tvDebugResult.text = info.toString()
        }

        val btnOpenMap = findViewById<Button>(R.id.btnOpenMap)
        btnOpenMap.setOnClickListener {
            Log.i("CelltraceLogger", "🗺️ Abriendo mapa...")
            startActivity(Intent(this, MapActivity::class.java))
        }
    }

    private fun updateServiceStatus(running: Boolean) {
        isServiceRunning = running

        if (running) {
            tvStatus.text = "STATUS: ACTIVE"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            tvStatus.setBackgroundResource(R.drawable.status_bg_active)

            btnStart.isEnabled = false
            btnStart.alpha = 0.5f
            btnStop.isEnabled = true
            btnStop.alpha = 1.0f
        } else {
            tvStatus.text = "STATUS: STOPPED"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            tvStatus.setBackgroundResource(R.drawable.status_bg)

            btnStart.isEnabled = true
            btnStart.alpha = 1.0f
            btnStop.isEnabled = false
            btnStop.alpha = 0.5f

            // Reset contadores
            tvEventCount.text = "0"
            tvCurrentFile.text = "—"
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                statsReceiver,
                IntentFilter("CELLTRACE_STATS"),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(statsReceiver, IntentFilter("CELLTRACE_STATS"))
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(statsReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("CelltraceLogger", "Receiver no estaba registrado: ${e.message}")
        }
        super.onStop()
    }

    private fun requestNecessaryPermissions() {
        if (!phoneStatePermissionGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_CODE_PHONE_STATE)
            return
        }

        if (!allForegroundLocationPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_LOCATION_FOREGROUND
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationPermissionGranted()) {
            AlertDialog.Builder(this)
                .setTitle("Background location permission")
                .setMessage("To collect cell data even with the app minimized (scientific research, no GPS or personal tracking), we need 'Allow all the time'.\n\nThis only accesses cellular network info (MCC, MNC, cell ID, signal).")
                .setPositiveButton("Continue") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        REQUEST_CODE_LOCATION_BACKGROUND
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission denied - Cannot collect data", Toast.LENGTH_LONG).show()
            return
        }

        when (requestCode) {
            REQUEST_CODE_PHONE_STATE,
            REQUEST_CODE_LOCATION_FOREGROUND -> {
                requestNecessaryPermissions()
            }
            REQUEST_CODE_LOCATION_BACKGROUND -> {
                Toast.makeText(this, "Background permission granted!", Toast.LENGTH_SHORT).show()
            }
            REQUEST_CODE_POST_NOTIFICATIONS -> {
                // OK
            }
        }

        if (canStartLogging()) {
            startForegroundService(Intent(this, CellLoggerService::class.java))
        }
    }

    private fun debugListFiles(): String {
        val dir = getExternalFilesDir(null)
        val files = dir?.listFiles()?.sortedBy { it.name } ?: emptyList()

        val info = StringBuilder()
        info.append("Directory:\n${dir?.absolutePath}\n\n")
        info.append("Total files: ${files.size}\n\n")

        if (files.isEmpty()) {
            info.append("NO FILES CREATED\n\n")
            info.append("Possible causes:\n")
            info.append("1. Service not started\n")
            info.append("2. No location permissions\n")
            info.append("3. Error in rotateFileIfNeeded()\n")
        } else {
            files.forEach { file ->
                info.append("📄 ${file.name}\n")
                info.append("   Size: ${file.length() / 1024} KB\n")

                try {
                    val lines = file.readLines()
                    info.append("   Events: ${lines.size}\n")
                    if (lines.isNotEmpty()) {
                        info.append("   First event: ${lines.first().take(80)}...\n")
                    }
                } catch (e: Exception) {
                    info.append("   Error reading: ${e.message}\n")
                }
                info.append("\n")
            }
        }

        Log.i("CelltraceDebug", info.toString())
        return info.toString()
    }
}
