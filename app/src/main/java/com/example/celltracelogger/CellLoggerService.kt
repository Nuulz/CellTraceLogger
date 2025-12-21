package com.example.celltracelogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.RequestBody.Companion.asRequestBody

class CellLoggerService : Service() {

    private val tag = "CelltraceLogger"
    private val channelId = "celltrace_logger_channel"
    private val notificationId = 1

    private lateinit var sessionFile: File

    private val cellDatabase = mutableMapOf<String, Pair<Double, Double>>()
    private var offlineDatabaseLoaded = false

    private val MAX_FILES = 10
    private val EVENTS_PER_FILE = 50
    private val SEND_INTERVAL_SECONDS = 60L

    private var currentEventCount = 0
    private var currentFileIndex = 1

    private var timer: Timer? = null
    private var uploadTimer: Timer? = null
    private lateinit var telephonyManager: TelephonyManager
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())

    // ✅ Webhooks dinámicos desde configuración
    private val webhookUrl: String
        get() = AppConfig.getWebhook(this) ?: ""

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Celltrace Logger activo")
            .setContentText("Recolectando datos de red celular")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun loadOfflineDatabase() {
        if (offlineDatabaseLoaded) return
        thread {
            try {
                val startTime = System.currentTimeMillis()
                assets.open("732.csv").bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.split(',')
                        if (parts.size >= 8) {
                            val mcc = parts[1].trim()
                            val mnc = parts[2].trim()
                            val tac = parts[3].trim()
                            val ci = parts[4].trim()
                            val lat = parts[6].trim().toDoubleOrNull() ?: return@forEach
                            val lon = parts[7].trim().toDoubleOrNull() ?: return@forEach
                            val key = "$mcc-$mnc-$tac-$ci"
                            cellDatabase[key] = lat to lon
                        }
                    }
                }
                offlineDatabaseLoaded = true
                Log.i(tag, "Base offline cargada: ${cellDatabase.size} celdas en ${System.currentTimeMillis() - startTime} ms")
            } catch (e: Exception) {
                Log.e(tag, "Error cargando CSV offline", e)
            }
        }
    }

    // ✅ Nueva función: Consultar celda con fallback a Unwired Labs
    private fun getCellLocation(mcc: String, mnc: String, lac: String, cid: String): Pair<Double, Double>? {
        // 1. Buscar en base local
        val key = "$mcc-$mnc-$lac-$cid"
        val localResult = cellDatabase[key]

        if (localResult != null) {
            Log.d(tag, "✓ Celda encontrada en base local: $key")
            return localResult
        }

        // 2. Fallback a Unwired Labs API
        val apiKey = AppConfig.getApiKey(this)
        if (apiKey.isNullOrEmpty()) {
            Log.d(tag, "⚠ No hay API key configurada, no se puede consultar API")
            return null
        }

        return try {
            Log.d(tag, "⚠ Celda $key no encontrada localmente, consultando Unwired Labs...")
            val location = queryUnwiredLabsAPI(apiKey, mcc, mnc, lac, cid)

            if (location != null) {
                // Guardar en cache local para futuras consultas
                cellDatabase[key] = location
                Log.i(tag, "✓ Celda obtenida de Unwired Labs y guardada en cache")
            } else {
                Log.w(tag, "✗ Celda no encontrada en Unwired Labs")
            }

            location
        } catch (e: Exception) {
            Log.e(tag, "Error consultando Unwired Labs API", e)
            null
        }
    }

    // ✅ Cliente de Unwired Labs API
    private fun queryUnwiredLabsAPI(apiKey: String, mcc: String, mnc: String, lac: String, cid: String): Pair<Double, Double>? {
        val requestBody = JSONObject().apply {
            put("token", apiKey)
            put("radio", "gsm")
            put("mcc", mcc.toIntOrNull() ?: return null)
            put("mnc", mnc.toIntOrNull() ?: return null)
            put("cells", JSONArray().apply {
                put(JSONObject().apply {
                    put("lac", lac.toIntOrNull() ?: return@queryUnwiredLabsAPI null)
                    put("cid", cid.toLongOrNull() ?: return@queryUnwiredLabsAPI null)
                })
            })
        }

        val request = Request.Builder()
            .url("https://us1.unwiredlabs.com/v2/process.php")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val lat = json.optDouble("lat", Double.NaN)
                    val lon = json.optDouble("lon", Double.NaN)

                    if (!lat.isNaN() && !lon.isNaN()) {
                        lat to lon
                    } else {
                        null
                    }
                } else {
                    Log.e(tag, "Unwired Labs API error: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception calling Unwired Labs API", e)
            null
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()
        setInstance(this)
        loadOfflineDatabase()
        Log.i(tag, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, buildNotification())

        currentFileIndex = 1
        currentEventCount = 0
        sessionFile = File(
            getExternalFilesDir(null),
            "celltrace_events_${currentFileIndex.toString().padStart(3, '0')}.ndjson"
        )

        Log.i(tag, "Archivo inicial creado: ${sessionFile.absolutePath}")

        startLogging()
        startUploading()
        return START_STICKY
    }

    override fun onDestroy() {
        stopLogging()
        stopUploading()
        setInstance(null)
        Log.i(tag, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getCurrentLogFile(): File = sessionFile

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Celltrace Logger",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Recolectando datos de celdas celulares para investigación"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun rotateFileIfNeeded() {
        if (currentEventCount >= EVENTS_PER_FILE) {
            currentFileIndex = if (currentFileIndex >= MAX_FILES) 1 else currentFileIndex + 1
            currentEventCount = 0

            sessionFile = File(
                getExternalFilesDir(null),
                "celltrace_events_${currentFileIndex.toString().padStart(3, '0')}.ndjson"
            )

            Log.i(tag, "✅ ROTADO a archivo nuevo: ${sessionFile.absolutePath}")

            if (currentFileIndex == 1) {
                mergeAndSendFullTrace()
            }
        }
    }

    private fun startLogging() {
        if (timer != null) return
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                requestFreshCellInfo()
            }
        }, 0L, 5000L)
    }

    private fun startUploading() {
        if (uploadTimer != null) return
        uploadTimer = Timer()
        uploadTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                sendCurrentFileToDiscord()
            }
        }, SEND_INTERVAL_SECONDS * 1000, SEND_INTERVAL_SECONDS * 1000)
    }

    private fun sendCurrentFileToDiscord() {
        // ✅ Verificar que hay webhook configurado
        if (webhookUrl.isEmpty()) {
            Log.d(tag, "No hay webhook configurado, skip envío a Discord")
            return
        }

        val currentFile = getCurrentLogFile()
        if (!currentFile.exists() || currentFile.length() == 0L) {
            Log.i(tag, "No hay datos en archivo actual")
            return
        }

        val lines = currentFile.readLines()
        val totalEvents = lines.size
        val fileSizeKb = currentFile.length() / 1024

        val lastEvents = lines.takeLast(5)

        val locationLines = StringBuilder()
        val previewBlocks = StringBuilder()

        lastEvents.forEach { line ->
            val type = when {
                line.contains("\"radio\":\"nr\"") -> "5G NR"
                line.contains("\"radio\":\"lte\"") -> "4G LTE"
                line.contains("\"radio\":\"wcdma\"") -> "3G WCDMA"
                else -> "Desconocido"
            }

            val mcc = Regex("\"mcc\":\"?(\\d+)\"?").find(line)?.groupValues?.get(1) ?: "???"
            val mnc = Regex("\"mnc\":\"?(\\d+)\"?").find(line)?.groupValues?.get(1) ?: "???"
            val lac = Regex("\"lac\":\"?(\\d+)\"?").find(line)?.groupValues?.get(1) ?: "???"
            val cellId = Regex("\"cellid\":\"?(\\d+)\"?").find(line)?.groupValues?.get(1) ?: "???"
            val rsrp = Regex("\"rs[cp]p?\":(-?\\d+)").find(line)?.groupValues?.get(1) ?: "???"

            val location = getCellLocation(mcc, mnc, lac, cellId)
            val locationText = if (location != null) {
                val (lat, lon) = location
                "[$lat, $lon](https://www.google.com/maps?q=$lat,$lon&z=16)"
            } else {
                "No encontrado"
            }

            locationLines.append("• **$type** | Signal $rsrp dBm → $locationText\n")
            previewBlocks.append("```json\n${line.trim()}\n```\n")
        }

        val embed = JSONObject().apply {
            put("title", "📡 Reporte Celltrace (archivo ${currentFile.name})")
            put("description", locationLines.toString() + "\n**Últimos eventos:**\n" + previewBlocks.toString())
            put("color", 3447003)
            put("fields", JSONArray().apply {
                put(JSONObject().apply { put("name", "Eventos"); put("value", totalEvents); put("inline", true) })
                put(JSONObject().apply { put("name", "Tamaño"); put("value", "$fileSizeKb KB"); put("inline", true) })
                put(JSONObject().apply { put("name", "Archivo"); put("value", "#${currentFileIndex.toString().padStart(3, '0')}"); put("inline", true) })
            })
            put("footer", JSONObject().apply { put("text", "Celltrace Logger • Rotación automática") })
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date()))
        }

        val payload = JSONObject().apply {
            put("content", "**Reporte parcial cada minuto**")
            put("embeds", JSONArray().put(embed))
        }

        val fileBody = currentFile.asRequestBody("application/json".toMediaType())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload_json", payload.toString())
            .addFormDataPart("files[0]", currentFile.name, fileBody)
            .build()

        val request = Request.Builder()
            .url(webhookUrl)  // ✅ Usa webhook dinámico
            .post(multipartBody)
            .build()

        thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(tag, "Reporte parcial enviado")
                    } else {
                        Log.e(tag, "Error envío parcial: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Excepción envío parcial", e)
            }
        }
    }

    private fun mergeAndSendFullTrace() {
        thread {
            try {
                val filesDir = getExternalFilesDir(null)!!
                val allFiles = filesDir.listFiles { _, name ->
                    name.startsWith("celltrace_events_") && name.endsWith(".ndjson")
                }?.sortedBy { it.name } ?: return@thread

                if (allFiles.size < MAX_FILES) return@thread

                val mergedFile = File(filesDir, "celltrace_full_trace.ndjson")
                FileWriter(mergedFile, false).use { writer ->
                    allFiles.forEach { file ->
                        file.bufferedReader().useLines { lines ->
                            lines.forEach { writer.append(it).append('\n') }
                        }
                    }
                }

                Log.i(tag, "Trace completo generado: ${mergedFile.length() / 1024} KB")

                if (webhookUrl.isEmpty()) {
                    Log.d(tag, "No hay webhook, trace guardado pero no enviado")
                    return@thread
                }

                val fileBody = mergedFile.asRequestBody("application/json".toMediaType())
                val payload = JSONObject().apply {
                    put("content", "**¡TRACE COMPLETO RECOLECTADO!** (${allFiles.size} archivos unidos)")
                }

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", payload.toString())
                    .addFormDataPart("files[0]", "celltrace_full_trace.ndjson", fileBody)
                    .build()

                val request = Request.Builder().url(webhookUrl).post(multipartBody).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(tag, "¡Trace completo enviado!")
                        allFiles.forEach { it.delete() }
                        mergedFile.delete()
                    } else {
                        Log.e(tag, "Error envío final: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error generando trace completo", e)
            }
        }
    }

    private fun requestFreshCellInfo() {
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            Log.w(tag, "Permiso de ubicación no concedido")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                telephonyManager.requestCellInfoUpdate(
                    ContextCompat.getMainExecutor(this),
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: List<CellInfo>) {
                            processCellInfo(cellInfo)
                        }

                        override fun onError(errorCode: Int, throwable: Throwable?) {
                            Log.w(tag, "CellInfo update error: $errorCode", throwable)
                        }
                    }
                )
            } catch (e: SecurityException) {
                Log.e(tag, "SecurityException al solicitar CellInfoUpdate", e)
            }
        } else {
            try {
                val cellInfo = telephonyManager.allCellInfo
                processCellInfo(cellInfo)
            } catch (e: SecurityException) {
                Log.e(tag, "SecurityException al leer allCellInfo", e)
            }
        }
    }

    private fun processCellInfo(cells: List<CellInfo>?) {
        if (cells.isNullOrEmpty()) {
            Log.i(tag, "Tick: No cell info available")
            return
        }

        Log.i(tag, "Tick: ${cells.size} celdas detectadas")

        val timestamp = dateFormat.format(Date())

        cells.forEach { cell ->
            val jsonEvent = when (cell) {
                is CellInfoLte -> extractLte(cell, timestamp)
                is CellInfoNr -> extractNr(cell, timestamp)
                is CellInfoWcdma -> extractWcdma(cell, timestamp)
                else -> {
                    Log.d(tag, "Tipo celda no soportado: ${cell.javaClass.simpleName}")
                    null
                }
            }

            if (jsonEvent != null) {
                val jsonLine = "$jsonEvent\n"
                appendToLogFile(jsonLine)
                Log.d(tag, "✅ Evento guardado")
            }
        }
    }

    private fun extractLte(cell: CellInfoLte, timestamp: String): String {
        val identity = cell.cellIdentity as CellIdentityLte
        val signal = cell.cellSignalStrength as CellSignalStrengthLte

        val mcc = identity.mccString ?: "null"
        val mnc = identity.mncString ?: "null"
        val ci = if (identity.ci != Int.MAX_VALUE) identity.ci else "null"
        val pci = if (identity.pci != Int.MAX_VALUE) identity.pci else "null"
        val tac = if (identity.tac != Int.MAX_VALUE) identity.tac else "null"

        return """{"radio":"lte","mcc":$mcc,"mnc":$mnc,"lac":$tac,"cellid":$ci,"rsrp":${signal.rsrp},"rsrq":${signal.rsrq},"rssnr":${signal.rssnr},"timestamp":"$timestamp"}"""
    }

    private fun extractNr(cell: CellInfoNr, timestamp: String): String {
        val identity = cell.cellIdentity as CellIdentityNr
        val signal = cell.cellSignalStrength as CellSignalStrengthNr

        val mcc = identity.mccString ?: "null"
        val mnc = identity.mncString ?: "null"
        val nci = if (identity.nci != Long.MAX_VALUE) identity.nci else "null"
        val pci = if (identity.pci != Int.MAX_VALUE) identity.pci else "null"
        val tac = if (identity.tac != Int.MAX_VALUE) identity.tac else "null"

        return """{"radio":"nr","mcc":$mcc,"mnc":$mnc,"lac":$tac,"cellid":$nci,"rsrp":${signal.ssRsrp},"rsrq":${signal.ssRsrq},"rssinr":${signal.ssSinr},"timestamp":"$timestamp"}"""
    }

    private fun extractWcdma(cell: CellInfoWcdma, timestamp: String): String {
        val identity = cell.cellIdentity as CellIdentityWcdma
        val signal = cell.cellSignalStrength as CellSignalStrengthWcdma

        val mcc = identity.mccString ?: "null"
        val mnc = identity.mncString ?: "null"
        val cid = if (identity.cid != Int.MAX_VALUE) identity.cid else "null"
        val psc = if (identity.psc != Int.MAX_VALUE) identity.psc else "null"
        val lac = if (identity.lac != Int.MAX_VALUE) identity.lac else "null"

        return """{"radio":"wcdma","mcc":$mcc,"mnc":$mnc,"lac":$lac,"cellid":$cid,"psc":$psc,"rscp":${signal.dbm},"timestamp":"$timestamp"}"""
    }

    private fun broadcastStats() {
        val intent = Intent("CELLTRACE_STATS")
        intent.putExtra("events", currentEventCount)
        intent.putExtra("file", sessionFile.name)
        sendBroadcast(intent)
    }

    @SuppressLint("MissingPermission")
    private fun appendToLogFile(line: String) {
        thread {
            try {
                val file = getCurrentLogFile()

                if (!file.exists()) {
                    file.createNewFile()
                    Log.i(tag, "✅ Archivo creado: ${file.absolutePath}")
                }

                FileWriter(file, true).use { it.append(line) }
                currentEventCount++
                broadcastStats()
                rotateFileIfNeeded()

            } catch (e: Exception) {
                Log.e(tag, "❌ Error escribiendo archivo: ${e.message}", e)
            }
        }
    }

    private fun stopLogging() {
        timer?.cancel()
        timer = null
        Log.i(tag, "Logging stopped")
    }

    private fun stopUploading() {
        uploadTimer?.cancel()
        uploadTimer = null
        Log.i(tag, "Uploading stopped")
    }

    companion object {
        private val client = OkHttpClient()
        private var serviceInstance: CellLoggerService? = null

        fun setInstance(instance: CellLoggerService?) {
            serviceInstance = instance
        }

        fun sendStatusToDiscord(context: Context, action: String) {
            thread {
                try {
                    val webhook = AppConfig.getWebhook(context)
                    if (webhook.isNullOrEmpty()) {
                        Log.d("Celltrace", "No hay webhook configurado, skip mensaje de $action")
                        return@thread
                    }

                    val title = if (action == "start") "📡 Celltrace Logger INICIADO" else "🛑 Celltrace Logger DETENIDO"
                    val color = if (action == "start") 0x00FF00 else 0xFF0000

                    val embed = JSONObject().apply {
                        put("title", title)
                        put("description", "Sesión ${if (action == "start") "iniciada" else "finalizada"}")
                        put("color", color)
                        put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.format(Date()))
                    }

                    val payload = JSONObject().apply {
                        put("embeds", JSONArray().put(embed))
                    }

                    val requestBody = payload.toString().toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url(webhook)
                        .post(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.i("Celltrace", "Mensaje de $action enviado")
                        } else {
                            Log.e("Celltrace", "Error envío $action: ${response.code}")
                        }
                    }

                    if (action == "stop") {
                        serviceInstance?.sendCurrentFileToDiscord()
                    }

                } catch (e: Exception) {
                    Log.e("Celltrace", "Excepción enviando estado $action", e)
                }
            }
        }

        fun sendCurrentFileImmediately(context: Context) {
            // ... mantén el código existente
        }
    }
}
