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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class CellLoggerService : Service() {

    // ───────────────── CONFIG Y CONSTANTES ─────────────────

    private val tag = "CelltraceLogger"
    private val channelId = "celltrace_logger_channel"
    private val notificationId = 1

    private val maxFiles = 10
    private val eventsPerFile = 50
    private val sendIntervalSeconds = 60L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())

    // Webhook dinámico
    private val webhookUrl: String
        get() = AppConfig.getWebhook(this) ?: ""

    // HTTP client compartido para toda la instancia
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ───────────────── ESTADO DE LOGGING ─────────────────

    private lateinit var telephonyManager: TelephonyManager

    private lateinit var sessionFile: File
    private var currentEventCount = 0
    private var currentFileIndex = 1

    private var timer: Timer? = null
    private var uploadTimer: Timer? = null

    // ───────────────── BASE DE DATOS LOCAL + CACHE ─────────────────

    private val cellDatabase = ConcurrentHashMap<String, Pair<Double, Double>>()
    private val tickCounter = AtomicLong(0)
    private val lastApiTickByKey = ConcurrentHashMap<String, Long>()

    @Volatile
    private var offlineDatabaseLoaded = false

    private val cacheFile: File by lazy {
        File(getExternalFilesDir(null), "cached_cells.csv")
    }

    // ───────────────── CICLO DE VIDA ─────────────────

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()
        setInstance(this)

        initializeCacheFile()
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

        Log.i(tag, "Initial file created: ${sessionFile.absolutePath}")

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

    // ───────────────── NOTIFICACIONES ─────────────────

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Celltrace Logger activo")
            .setContentText("Collecting cellular network data")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            channelId,
            "Celltrace Logger",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Collects cell data for research"
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ───────────────── ARCHIVOS DE LOG ─────────────────

    private fun getCurrentLogFile(): File = sessionFile

    private fun rotateFileIfNeeded() {
        if (currentEventCount < eventsPerFile) return

        currentFileIndex = if (currentFileIndex >= maxFiles) 1 else currentFileIndex + 1
        currentEventCount = 0

        sessionFile = File(
            getExternalFilesDir(null),
            "celltrace_events_${currentFileIndex.toString().padStart(3, '0')}.ndjson"
        )

        Log.i(tag, "✅ Rotated to new file: ${sessionFile.absolutePath}")

        // Cuando se completa el ciclo, crear y enviar traza completa
        if (currentFileIndex == 1) {
            mergeAndSendFullTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun appendToLogFile(line: String) {
        thread {
            try {
                val file = getCurrentLogFile()

                if (!file.exists()) {
                    file.createNewFile()
                    Log.i(tag, "✅ File created: ${file.absolutePath}")
                }

                FileWriter(file, true).use { it.append(line) }
                currentEventCount++
                broadcastStats()
                rotateFileIfNeeded()
            } catch (e: Exception) {
                Log.e(tag, "❌ Error writing file: ${e.message}", e)
            }
        }
    }

    private fun broadcastStats() {
        val intent = Intent("CELLTRACE_STATS").apply {
            putExtra("events", currentEventCount)
            putExtra("file", sessionFile.name)
        }
        sendBroadcast(intent)
    }

    // ───────────────── TIMERS (LOGGING + UPLOAD) ─────────────────

    private fun startLogging() {
        if (timer != null) return

        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                requestFreshCellInfo()
            }
        }, 0L, 5000L)
    }

    private fun stopLogging() {
        timer?.cancel()
        timer = null
        Log.i(tag, "Logging stopped")
    }

    private fun startUploading() {
        if (uploadTimer != null) return

        uploadTimer = Timer()
        uploadTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                sendCurrentFileToDiscord()
            }
        }, sendIntervalSeconds * 1000, sendIntervalSeconds * 1000)
    }

    private fun stopUploading() {
        uploadTimer?.cancel()
        uploadTimer = null
        Log.i(tag, "Uploading stopped")
    }

    // ───────────────── BASE LOCAL + CACHE OFFLINE ─────────────────

    private fun initializeCacheFile() {
        if (!cacheFile.exists()) {
            try {
                cacheFile.createNewFile()
                cacheFile.writeText("radio,mcc,mnc,area,cell,unit,lon,lat\n")
                Log.i(tag, "✅ Cache file created: ${cacheFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "Error creating cache file", e)
            }
        } else {
            val count = try {
                cacheFile.readLines().size - 1
            } catch (_: Exception) {
                0
            }
            Log.i(tag, "Cache file already exists with $count saved cells")
        }
    }

    private fun loadOfflineDatabase() {
        if (offlineDatabaseLoaded) return

        thread {
            try {
                val startTime = System.currentTimeMillis()
                var totalCells = 0

                // CSV offline principal
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
                            totalCells++
                        }
                    }
                }

                // Cache persistente
                if (cacheFile.exists()) {
                    cacheFile.bufferedReader().useLines { lines ->
                        lines.drop(1).forEach { line ->
                            val parts = line.split(',')
                            if (parts.size >= 8) {
                                val mcc = parts[1].trim()
                                val mnc = parts[2].trim()
                                val tac = parts[3].trim()
                                val ci = parts[4].trim()
                                val lat = parts[7].trim().toDoubleOrNull() ?: return@forEach
                                val lon = parts[6].trim().toDoubleOrNull() ?: return@forEach
                                val key = "$mcc-$mnc-$tac-$ci"

                                if (!cellDatabase.containsKey(key)) {
                                    cellDatabase[key] = lat to lon
                                    totalCells++
                                }
                            }
                        }
                    }
                }

                offlineDatabaseLoaded = true
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(tag, "✅ Complete database loaded: $totalCells cells in ${elapsed} ms")
            } catch (e: Exception) {
                Log.e(tag, "Error loading database", e)
            }
        }
    }

    private fun saveCellToCache(
        mcc: String,
        mnc: String,
        lac: String,
        cid: String,
        lat: Double,
        lon: Double,
        radio: String = "lte"
    ) {
        thread {
            try {
                val existingLines = cacheFile.readLines()
                val alreadyExists = existingLines.any { it.contains("$mcc,$mnc,$lac,$cid") }

                if (!alreadyExists) {
                    val csvLine = "$radio,$mcc,$mnc,$lac,$cid,,$lon,$lat\n"
                    cacheFile.appendText(csvLine)
                    Log.i(tag, "💾 Cell saved to cache: $mcc-$mnc-$lac-$cid")
                } else {
                    Log.d(tag, "Cell $mcc-$mnc-$lac-$cid already in cache, skip")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error saving cell to cache", e)
            }
        }
    }

    // ───────────────── RESOLUCIÓN DE CELDAS (LOCAL + API) ─────────────────

    private fun getCellLocation(
        mcc: String,
        mnc: String,
        lac: String,
        cid: String,
        radio: String = "lte",
        tickId: Long
    ): Pair<Double, Double>? {
        val key = "$mcc-$mnc-$lac-$cid"

        // 1) Intentar base local + cache
        cellDatabase[key]?.let {
            Log.d(tag, "✓ Cell found in local/cache database: $key")
            return it
        }

        if (!offlineDatabaseLoaded) return null

        if (lastApiTickByKey[key] == tickId) {
            Log.d(tag, "Skip API (already queried this tick): $key")
            return null
        }
        lastApiTickByKey[key] = tickId

        // 2) Intentar Unwired Labs
        val apiKey = AppConfig.getApiKey(this)
        if (apiKey.isNullOrEmpty()) {
            Log.d(tag, "⚠ No API key configured, cannot query API")
            return null
        }

        return try {
            Log.d(tag, "⚠ Cell $key not found locally, querying Unwired Labs...")
            val location = queryUnwiredLabsAPI(apiKey, mcc, mnc, lac, cid, radio)

            if (location != null) {
                val (lat, lon) = location  // destructuring OK en Kotlin [web:291]
                cellDatabase[key] = location
                saveCellToCache(mcc, mnc, lac, cid, lat, lon, radio)
                Log.i(tag, "✓ Cell obtained from Unwired Labs and saved to cache")
            } else {
                Log.w(tag, "✗ Cell not found in Unwired Labs")
            }

            location
        } catch (e: Exception) {
            Log.e(tag, "Error querying Unwired Labs API", e)
            null
        }
    }

    private fun queryUnwiredLabsAPI(
        apiKey: String,
        mcc: String,
        mnc: String,
        lac: String,
        cid: String,
        radio: String = "lte"
    ): Pair<Double, Double>? {
        val mccInt = mcc.toIntOrNull() ?: return null
        val mncInt = mnc.toIntOrNull() ?: return null
        val lacInt = lac.toIntOrNull() ?: return null
        val cidLong = cid.toLongOrNull() ?: return null

        val requestBodyJson = JSONObject().apply {
            put("token", apiKey)
            put("radio", when (radio) {
                "nr" -> "nr"
                "lte" -> "lte"
                "wcdma" -> "umts"
                else -> "gsm"
            })
            put("mcc", mccInt)
            put("mnc", mncInt)
            put("cells", JSONArray().apply {
                put(JSONObject().apply {
                    put("lac", lacInt)
                    put("cid", cidLong)
                })
            })
        }

        val request = Request.Builder()
            .url("https://us1.unwiredlabs.com/v2/process.php")
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(tag, "Unwired Labs API error: ${response.code}")
                    return null
                }

                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)

                val lat = json.optDouble("lat", Double.NaN)
                val lon = json.optDouble("lon", Double.NaN)

                if (!lat.isNaN() && !lon.isNaN()) {
                    lat to lon
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception calling Unwired Labs API", e)
            null
        }
    }

    // ───────────────── DISCORD: ENVÍO DE ARCHIVOS ─────────────────

    private fun sendCurrentFileToDiscord() {
        if (webhookUrl.isEmpty()) {
            Log.d(tag, "No webhook configured, skip sending to Discord")
            return
        }

        val currentFile = getCurrentLogFile()
        if (!currentFile.exists() || currentFile.length() == 0L) {
            Log.i(tag, "No data in current file")
            return
        }

        val snapshot = File(currentFile.parentFile, "upload_${currentFile.name}")
        try {
            currentFile.copyTo(snapshot, overwrite = true)
        } catch (e: Exception) {
            Log.e(tag, "Snapshot copy failed", e)
            return
        }

        val lines = snapshot.readLines()
        val totalEvents = lines.size
        val fileSizeKb = currentFile.length() / 1024
        val lastEvents = lines.takeLast(5)

        val locationLines = StringBuilder()

        lastEvents.forEach { line ->
            val type = when {
                line.contains("\"radio\":\"nr\"") -> "5G NR"
                line.contains("\"radio\":\"lte\"") -> "4G LTE"
                line.contains("\"radio\":\"wcdma\"") -> "3G WCDMA"
                else -> "Unknown"
            }

            val mcc = Regex("\"mcc\":\"?(\\d+)\"?").find(line)?.groupValues?.get(1) ?: "???"
            val mnc = Regex("\"mnc\":\"?(\\d+)\"?").find(line)?.groupValues?.get(1) ?: "???"
            val lac = Regex("\"lac\":\"?(\\d+)\"?").find(line)?.groupValues?.get(1) ?: "???"
            val cellId = Regex("\"cellid\":\"?(\\d+)\"?").find(line)?.groupValues?.get(1) ?: "???"
            val rsrp = Regex("\"rs[cp]p?\":(-?\\d+)").find(line)?.groupValues?.get(1) ?: "???"

            val key = "$mcc-$mnc-$lac-$cellId"
            val location = cellDatabase[key]
            val locationText = if (location != null) {
                val (lat, lon) = location
                "[$lat, $lon](https://www.google.com/maps?q=$lat,$lon&z=16)"
            } else {
                "Not found"
            }

            locationLines.append("• **$type** | Signal $rsrp dBm → $locationText\n")
        }

        val embed = JSONObject().apply {
            put("title", "📡 Celltrace Report (file ${currentFile.name})")
            put(
                "description",
                locationLines.toString()
            )
            put("color", 3447003)
            put("fields", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "Events")
                    put("value", totalEvents)
                    put("inline", true)
                })
                put(JSONObject().apply {
                    put("name", "Size")
                    put("value", "$fileSizeKb KB")
                    put("inline", true)
                })
                put(JSONObject().apply {
                    put("name", "File")
                    put("value", "#${currentFileIndex.toString().padStart(3, '0')}")
                    put("inline", true)
                })
            })
            put("footer", JSONObject().apply {
                put("text", "Celltrace Logger • Automatic rotation")
            })
            put(
                "timestamp",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(Date())
            )
        }

        val payload = JSONObject().apply {
            put("content", "**Partial report every minute**")
            put("embeds", JSONArray().put(embed))
        }

        val fileBody = snapshot.asRequestBody("application/x-ndjson".toMediaType())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload_json", payload.toString())
            .addFormDataPart("files[0]", snapshot.name, fileBody)
            .build()

        val request = Request.Builder()
            .url(webhookUrl)
            .post(multipartBody)
            .build()

        thread {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(tag, "Partial send error: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Partial send exception", e)
            } finally {
                snapshot.delete()
            }
        }
    }

    private fun mergeAndSendFullTrace() {
        thread {
            try {
                val filesDir = getExternalFilesDir(null) ?: return@thread

                val allFiles = filesDir.listFiles { _, name ->
                    name.startsWith("celltrace_events_") && name.endsWith(".ndjson")
                }?.sortedBy { it.name } ?: return@thread

                if (allFiles.size < maxFiles) return@thread

                val mergedFile = File(filesDir, "celltrace_full_trace.ndjson")

                FileWriter(mergedFile, false).use { writer ->
                    allFiles.forEach { file ->
                        file.bufferedReader().useLines { lines ->
                            lines.forEach { writer.append(it).append('\n') }
                        }
                    }
                }

                Log.i(tag, "Complete trace generated: ${mergedFile.length() / 1024} KB")

                if (webhookUrl.isEmpty()) {
                    Log.d(tag, "No webhook, trace saved but not sent")
                    return@thread
                }

                val payload = JSONObject().apply {
                    put("content", "**COMPLETE TRACE COLLECTED!** (${allFiles.size} files merged)")
                }

                val fileBody = mergedFile.asRequestBody("application/json".toMediaType())
                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", payload.toString())
                    .addFormDataPart("files[0]", "celltrace_full_trace.ndjson", fileBody)
                    .build()

                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(multipartBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(tag, "Complete trace sent!")
                        allFiles.forEach { it.delete() }
                        mergedFile.delete()
                    } else {
                        Log.e(tag, "Final send error: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error generating complete trace", e)
            }
        }
    }

    // ───────────────── LECTURA DE CELDAS (TELEPHONY) ─────────────────

    private fun requestFreshCellInfo() {
        val tickId = tickCounter.incrementAndGet()

        val hasFineLocation =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            telephonyManager.requestCellInfoUpdate(
                ContextCompat.getMainExecutor(this),
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: List<CellInfo>) {
                        processCellInfo(cellInfo, tickId)
                    }
                }
            )
        } else {
            processCellInfo(telephonyManager.allCellInfo, tickId)
        }
    }

    private fun resolveAndCacheLocationForRegisteredCell(cell: CellInfo, tickId: Long) {
        when (cell) {
            is CellInfoLte -> {
                val id = cell.cellIdentity
                val mcc = id.mccString ?: return
                val mnc = id.mncString ?: return
                val lac = id.tac.takeIf { it != Int.MAX_VALUE }?.toString() ?: return
                val cid = id.ci.takeIf { it != Int.MAX_VALUE }?.toString() ?: return
                getCellLocation(mcc, mnc, lac, cid, "lte", tickId)
            }
            is CellInfoNr -> {
                val id = cell.cellIdentity as CellIdentityNr
                val mcc = id.mccString ?: return
                val mnc = id.mncString ?: return
                val lac = id.tac.takeIf { it != Int.MAX_VALUE }?.toString() ?: return
                val cid = id.nci.takeIf { it != Long.MAX_VALUE }?.toString() ?: return
                getCellLocation(mcc, mnc, lac, cid, "nr", tickId)
            }
            is CellInfoWcdma -> {
                val id = cell.cellIdentity
                val mcc = id.mccString ?: return
                val mnc = id.mncString ?: return
                val lac = id.lac.takeIf { it != Int.MAX_VALUE }?.toString() ?: return
                val cid = id.cid.takeIf { it != Int.MAX_VALUE }?.toString() ?: return
                getCellLocation(mcc, mnc, lac, cid, "wcdma", tickId)
            }
        }
    }

    private fun processCellInfo(cells: List<CellInfo>?, tickId: Long) {
        if (cells.isNullOrEmpty()) return

        val registered = cells.filter { it.isRegistered }
        Log.i(tag, "Tick: ${cells.size} cells detected (${registered.size} registered)") // [web:203]

        val timestamp = dateFormat.format(Date())

        registered.forEach { cell ->
            val jsonEvent = when (cell) {
                is CellInfoLte -> extractLte(cell, timestamp)
                is CellInfoNr -> extractNr(cell, timestamp)
                is CellInfoWcdma -> extractWcdma(cell, timestamp)
                else -> null
            } ?: return@forEach

            appendToLogFile("$jsonEvent\n")

            // ✅ Aquí haces la consulta a Unwired Labs (si hace falta) UNA vez por tick
            thread {
                resolveAndCacheLocationForRegisteredCell(cell, tickId)
            }
        }
    }

    private fun extractLte(cell: CellInfoLte, timestamp: String): String {
        val identity = cell.cellIdentity as CellIdentityLte
        val signal = cell.cellSignalStrength as CellSignalStrengthLte

        val mcc = identity.mccString ?: "null"
        val mnc = identity.mncString ?: "null"
        val ci = if (identity.ci != Int.MAX_VALUE) identity.ci else "null"
        val tac = if (identity.tac != Int.MAX_VALUE) identity.tac else "null"

        return """{"radio":"lte","mcc":$mcc,"mnc":$mnc,"lac":$tac,"cellid":$ci,"rsrp":${signal.rsrp},"rsrq":${signal.rsrq},"rssnr":${signal.rssnr},"timestamp":"$timestamp"}"""
    }

    private fun extractNr(cell: CellInfoNr, timestamp: String): String {
        val identity = cell.cellIdentity as CellIdentityNr
        val signal = cell.cellSignalStrength as CellSignalStrengthNr

        val mcc = identity.mccString ?: "null"
        val mnc = identity.mncString ?: "null"
        val nci = if (identity.nci != Long.MAX_VALUE) identity.nci else "null"
        val tac = if (identity.tac != Int.MAX_VALUE) identity.tac else "null"

        return """{"radio":"nr","mcc":$mcc,"mnc":$mnc,"lac":$tac,"cellid":$nci,"rsrp":${signal.ssRsrp},"rsrq":${signal.ssRsrq},"rssinr":${signal.ssSinr},"timestamp":"$timestamp"}"""
    }

    private fun extractWcdma(cell: CellInfoWcdma, timestamp: String): String {
        val identity = cell.cellIdentity as CellIdentityWcdma
        val signal = cell.cellSignalStrength as CellSignalStrengthWcdma

        val mcc = identity.mccString ?: "null"
        val mnc = identity.mncString ?: "null"
        val cid = if (identity.cid != Int.MAX_VALUE) identity.cid else "null"
        val lac = if (identity.lac != Int.MAX_VALUE) identity.lac else "null"
        val psc = if (identity.psc != Int.MAX_VALUE) identity.psc else "null"

        return """{"radio":"wcdma","mcc":$mcc,"mnc":$mnc,"lac":$lac,"cellid":$cid,"psc":$psc,"rscp":${signal.dbm},"timestamp":"$timestamp"}"""
    }

    // ───────────────── COMPANION OBJECT ─────────────────

    companion object {
        private val statusClient = OkHttpClient()
        private var serviceInstance: CellLoggerService? = null

        fun setInstance(instance: CellLoggerService?) {
            serviceInstance = instance
        }

        fun sendStatusToDiscord(context: Context, action: String) {
            thread {
                try {
                    val webhook = AppConfig.getWebhook(context)
                    if (webhook.isNullOrEmpty()) {
                        Log.d("Celltrace", "No webhook configured, skip $action message")
                        return@thread
                    }

                    val title = if (action == "start") "📡 Celltrace Logger STARTED" else "🛑 Celltrace Logger STOPPED"
                    val color = if (action == "start") 0x00FF00 else 0xFF0000

                    val embed = JSONObject().apply {
                        put("title", title)
                        put("description", "Session ${if (action == "start") "started" else "finished"}")
                        put("color", color)
                        put(
                            "timestamp",
                            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                .format(Date())
                        )
                    }

                    val payload = JSONObject().apply {
                        put("embeds", JSONArray().put(embed))
                    }

                    val requestBody =
                        payload.toString().toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url(webhook)
                        .post(requestBody)
                        .build()

                    statusClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.i("Celltrace", "$action message sent")
                        } else {
                            Log.e("Celltrace", "$action send error: ${response.code}")
                        }
                    }

                    if (action == "stop") {
                        serviceInstance?.sendCurrentFileToDiscord()
                    }
                } catch (e: Exception) {
                    Log.e("Celltrace", "Exception sending $action status", e)
                }
            }
        }

        fun sendCurrentFileImmediately(context: Context) {
            // Si quieres, puedes exponer aquí un intent al servicio en vez de lógica directa,
            // pero por ahora no se usa (lo estás llamando desde MainActivity vía serviceInstance).
        }
    }
}
