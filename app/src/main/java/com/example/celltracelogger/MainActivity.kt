package com.example.celltracelogger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint

class MainActivity : AppCompatActivity() {

    // ❌ ELIMINADA: private val webhookStartStop (no se usa aquí)

    // UI
    private lateinit var tvEventCount: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDebugFiles: Button
    private lateinit var tvDebugResult: TextView

    // Receiver: acepta nulls y protege actualizaciones de UI
    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i("CelltraceLogger", "Broadcast CELLTRACE_STATS recibido")
            val events = intent?.getIntExtra("events", 0) ?: 0
            val file = intent?.getStringExtra("file") ?: "—"

            // Protegemos por si la activity aún no inicializó las views
            if (::tvEventCount.isInitialized) {
                tvEventCount.text = "Eventos: $events"
            }
            if (::tvCurrentFile.isInitialized) {
                tvCurrentFile.text = "Archivo: $file"
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

        val btnSettings = findViewById<Button>(R.id.btnSettings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setContentView(R.layout.activity_main)

        Log.i("CelltraceLogger", "MainActivity started")

        // Inicializa vistas
        tvEventCount = findViewById(R.id.tvEventCount)
        tvCurrentFile = findViewById(R.id.tvCurrentFile)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDebugFiles = findViewById(R.id.btnDebugFiles)
        tvDebugResult = findViewById(R.id.tvDebugResult)

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
            } else {
                requestNecessaryPermissions()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, CellLoggerService::class.java))
            CellLoggerService.sendStatusToDiscord(this, "stop")
            CellLoggerService.sendCurrentFileImmediately(this)
        }

        btnDebugFiles.setOnClickListener {
            val info = debugListFiles()
            tvDebugResult.text = info
        }

        val btnOpenMap = findViewById<Button>(R.id.btnOpenMap)
        btnOpenMap.setOnClickListener {
            Log.i("CelltraceLogger", "🗺️ Abriendo mapa...")
            startActivity(Intent(this, MapActivity::class.java))
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
        // Paso 1: READ_PHONE_STATE
        if (!phoneStatePermissionGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_CODE_PHONE_STATE)
            return
        }

        // Paso 2: Location foreground (coarse + fine)
        if (!allForegroundLocationPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_LOCATION_FOREGROUND
            )
            return
        }

        // Paso 3: Background location (solo si Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationPermissionGranted()) {
            AlertDialog.Builder(this)
                .setTitle("Permiso de ubicación en segundo plano")
                .setMessage("Para recolectar datos de celdas incluso con la app minimizada (investigación científica, sin GPS ni tracking personal), necesitamos 'Permitir todo el tiempo'.\n\nEsto solo accede a info de red celular (MCC, MNC, cell ID, señal).")
                .setPositiveButton("Continuar") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        REQUEST_CODE_LOCATION_BACKGROUND
                    )
                }
                .setNegativeButton("Cancelar", null)
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
            Toast.makeText(this, "Permiso denegado - No se puede recolectar datos", Toast.LENGTH_LONG).show()
            return
        }

        when (requestCode) {
            REQUEST_CODE_PHONE_STATE,
            REQUEST_CODE_LOCATION_FOREGROUND -> {
                requestNecessaryPermissions()
            }
            REQUEST_CODE_LOCATION_BACKGROUND -> {
                Toast.makeText(this, "Permiso background concedido!", Toast.LENGTH_SHORT).show()
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
        info.append("📂 Directorio:\n${dir?.absolutePath}\n\n")
        info.append("Total archivos: ${files.size}\n\n")

        if (files.isEmpty()) {
            info.append("⚠️ NO HAY ARCHIVOS CREADOS\n\n")
            info.append("Posibles causas:\n")
            info.append("1. No has iniciado el servicio\n")
            info.append("2. No hay permisos de ubicación\n")
            info.append("3. Error en rotateFileIfNeeded()\n")
        } else {
            files.forEach { file ->
                info.append("📄 ${file.name}\n")
                info.append("   Tamaño: ${file.length() / 1024} KB\n")

                try {
                    val lines = file.readLines()
                    info.append("   Eventos: ${lines.size}\n")
                    if (lines.isNotEmpty()) {
                        info.append("   Primer evento: ${lines.first().take(80)}...\n")
                    }
                } catch (e: Exception) {
                    info.append("   Error leyendo: ${e.message}\n")
                }
                info.append("\n")
            }
        }

        Log.i("CelltraceDebug", info.toString())
        return info.toString()
    }
}
