package com.example.celltracelogger

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.File
import androidx.core.graphics.drawable.DrawableCompat
import android.graphics.drawable.Drawable

data class DetectedCellInfo(
    val key: String,
    val mcc: String,
    val mnc: String,
    val lac: String,
    val cid: String,
    val radio: String,
    val rsrp: String?,
    val rsrq: String?,
    val rscp: String?
)


class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvMapInfo: TextView
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val cellDatabase = mutableMapOf<String, Pair<Double, Double>>()
    private val detectedCellsInfo = mutableMapOf<String, DetectedCellInfo>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File by lazy {
        File(getExternalFilesDir(null), "cached_cells.csv")
    }

    private fun initializeCacheFile() {
        if (!cacheFile.exists()) {
            try {
                cacheFile.createNewFile()
                cacheFile.writeText("radio,mcc,mnc,area,cell,unit,lon,lat\n")
                Log.i("MapActivity", "✅ Cache file created")
            } catch (e: Exception) {
                Log.e("MapActivity", "Error creating cache file", e)
            }
        }
    }

    private fun saveCellToCache(mcc: String, mnc: String, lac: String, cid: String, lat: Double, lon: Double, radio: String = "lte") {
        thread {
            try {
                val existingLines = cacheFile.readLines()
                val alreadyExists = existingLines.any { it.contains("$mcc,$mnc,$lac,$cid") }

                if (!alreadyExists) {
                    val csvLine = "$radio,$mcc,$mnc,$lac,$cid,,$lon,$lat\n"
                    cacheFile.appendText(csvLine)
                    Log.i("MapActivity", "💾 Cell saved to cache: $mcc-$mnc-$lac-$cid")
                }
            } catch (e: Exception) {
                Log.e("MapActivity", "Error saving to cache", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map)

        initializeCacheFile()

        mapView = findViewById(R.id.mapView)
        tvMapInfo = findViewById(R.id.tvMapInfo)
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnCenterLocation = findViewById<Button>(R.id.btnCenterLocation)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

        btnBack.setOnClickListener { finish() }
        btnCenterLocation.setOnClickListener { centerOnMyLocation() }

        // ✅ ORDEN OPTIMIZADO: Primero CSV, luego cache, luego celdas detectadas
        loadAllDataAndShowMap()
    }

    // ✅ NUEVA FUNCIÓN: Carga secuencial optimizada
    private fun loadAllDataAndShowMap() {
        thread {
            // 1. Cargar base de datos principal (732.csv)
            loadCellDatabaseSync()

            // 2. Cargar cache (cached_cells.csv)
            loadCachedCellsSync()

            // 3. Cargar celdas detectadas (.ndjson)
            loadDetectedCellsSync()

            // 4. Mostrar en el mapa
            runOnUiThread {
                tvMapInfo.text = "${detectedCellsInfo.size} detected cells, loading map..."
                showDetectedCellsOnMap()
            }
        }
    }

    // ✅ Versión síncrona de loadCellDatabase
    private fun loadCellDatabaseSync() {
        try {
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
            Log.i("MapActivity", "✅ Database loaded: ${cellDatabase.size} cells from 732.csv")
        } catch (e: Exception) {
            Log.e("MapActivity", "Error loading CSV", e)
        }
    }

    // ✅ Versión síncrona de loadCachedCells
    private fun loadCachedCellsSync() {
        try {
            if (!cacheFile.exists()) {
                Log.i("MapActivity", "No cache file found")
                return
            }

            var loadedCount = 0

            cacheFile.readLines().drop(1).forEach { line ->
                val parts = line.split(',')
                if (parts.size >= 8) {
                    val mcc = parts[1].trim()
                    val mnc = parts[2].trim()
                    val lac = parts[3].trim()
                    val cid = parts[4].trim()
                    val lon = parts[6].trim().toDoubleOrNull() ?: return@forEach
                    val lat = parts[7].trim().toDoubleOrNull() ?: return@forEach

                    val key = "$mcc-$mnc-$lac-$cid"

                    if (!cellDatabase.containsKey(key)) {
                        cellDatabase[key] = lat to lon
                        loadedCount++
                    }
                }
            }

            Log.i("MapActivity", "✅ Cache loaded: $loadedCount new cells (total: ${cellDatabase.size})")

        } catch (e: Exception) {
            Log.e("MapActivity", "Error loading cache", e)
        }
    }

    // ✅ Versión síncrona de loadDetectedCells
    private fun loadDetectedCellsSync() {
        try {
            val filesDir = getExternalFilesDir(null) ?: return
            val files = filesDir.listFiles { _, name ->
                name.startsWith("celltrace_events_") && name.endsWith(".ndjson")
            } ?: return

            files.forEach { file ->
                file.readLines().forEach { line ->
                    try {
                        val json = JSONObject(line)
                        val mcc = json.optString("mcc", "")
                        val mnc = json.optString("mnc", "")
                        val lac = json.optString("lac", "")
                        val cid = json.optString("cellid", "")
                        val radio = json.optString("radio", "unknown")

                        val rsrp = json.optString("rsrp", null)
                        val rsrq = json.optString("rsrq", null)
                        val rscp = json.optString("rscp", null)

                        if (mcc.isNotEmpty() && mnc.isNotEmpty() && lac.isNotEmpty() && cid.isNotEmpty()) {
                            val key = "$mcc-$mnc-$lac-$cid"

                            detectedCellsInfo[key] = DetectedCellInfo(
                                key = key,
                                mcc = mcc,
                                mnc = mnc,
                                lac = lac,
                                cid = cid,
                                radio = radio,
                                rsrp = rsrp,
                                rsrq = rsrq,
                                rscp = rscp
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("MapActivity", "Error parsing line: ${e.message}")
                    }
                }
            }

            Log.i("MapActivity", "✅ Detected cells loaded: ${detectedCellsInfo.size} unique cells")

        } catch (e: Exception) {
            Log.e("MapActivity", "Error loading detected cells", e)
        }
    }

    private fun showDetectedCellsOnMap() {
        if (detectedCellsInfo.isEmpty()) return

        thread {
            mapView.overlays.clear()

            var foundInDB = 0  // Incluye CSV + cache
            var foundInAPI = 0
            var notFound = 0

            detectedCellsInfo.values.forEach { cellInfo ->
                val location = getCellLocation(cellInfo)

                if (location != null) {
                    val (lat, lon) = location

                    runOnUiThread {
                        val marker = Marker(mapView)
                        marker.position = GeoPoint(lat, lon)

                        val (color, networkType) = when (cellInfo.radio) {
                            "nr" -> Color.RED to "5G NR"
                            "lte" -> Color.BLUE to "4G LTE"
                            "wcdma" -> Color.GREEN to "3G WCDMA"
                            else -> Color.GRAY to "Unknown"
                        }

                        marker.icon = createColoredMarker(color)
                        marker.title = "$networkType - Cell ${cellInfo.cid}"

                        val signalInfo = buildSignalInfo(cellInfo)
                        marker.snippet = """
                            MCC-MNC: ${cellInfo.mcc}-${cellInfo.mnc}
                            LAC: ${cellInfo.lac}
                            CID: ${cellInfo.cid}
                            $signalInfo
                            📍 $lat, $lon
                        """.trimIndent()

                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                        marker.setOnMarkerClickListener { clickedMarker, _ ->
                            clickedMarker.showInfoWindow()
                            true
                        }

                        mapView.overlays.add(marker)

                        if (cellDatabase.containsKey(cellInfo.key)) {
                            foundInDB++
                        } else {
                            foundInAPI++
                        }
                    }
                } else {
                    notFound++
                }
            }

            runOnUiThread {
                mapView.invalidate()
                val total = foundInDB + foundInAPI
                tvMapInfo.text = """
                    📍 $total antennas displayed
                    💾 $foundInDB from local database
                    🌐 $foundInAPI from Unwired Labs API
                    ❌ $notFound not found
                """.trimIndent()

                Log.i("MapActivity", "✅ Map ready: $total markers ($foundInDB local, $foundInAPI API, $notFound not found)")

                centerOnMyLocation()
            }
        }
    }

    private fun getCellLocation(cellInfo: DetectedCellInfo): Pair<Double, Double>? {
        // ✅ Ahora busca primero en cellDatabase (que incluye CSV + cache)
        val localResult = cellDatabase[cellInfo.key]
        if (localResult != null) {
            Log.d("MapActivity", "✓ Cell ${cellInfo.key} found in local database")
            return localResult
        }

        // Solo consulta API si no está en ninguna fuente local
        val apiKey = AppConfig.getApiKey(this)
        if (apiKey.isNullOrEmpty()) {
            Log.d("MapActivity", "⚠ No API key configured, skipping ${cellInfo.key}")
            return null
        }

        return try {
            Log.d("MapActivity", "🌐 Querying API for ${cellInfo.key}...")
            val location = queryUnwiredLabsAPI(apiKey, cellInfo)

            if (location != null) {
                val (lat, lon) = location

                cellDatabase[cellInfo.key] = location

                saveCellToCache(
                    cellInfo.mcc,
                    cellInfo.mnc,
                    cellInfo.lac,
                    cellInfo.cid,
                    lat,
                    lon,
                    cellInfo.radio
                )

                Log.i("MapActivity", "✓ Cell ${cellInfo.key} obtained from API and cached")
            } else {
                Log.w("MapActivity", "✗ Cell ${cellInfo.key} not found in API")
            }

            location
        } catch (e: Exception) {
            Log.e("MapActivity", "Error querying API for ${cellInfo.key}: ${e.message}", e)
            null
        }
    }

    private fun queryUnwiredLabsAPI(apiKey: String, cellInfo: DetectedCellInfo): Pair<Double, Double>? {
        val requestBody = JSONObject().apply {
            put("token", apiKey)
            put("radio", when(cellInfo.radio) {
                "nr" -> "nr"
                "lte" -> "lte"
                "wcdma" -> "umts"
                else -> "gsm"
            })
            put("mcc", cellInfo.mcc.toIntOrNull() ?: return null)
            put("mnc", cellInfo.mnc.toIntOrNull() ?: return null)
            put("cells", JSONArray().apply {
                put(JSONObject().apply {
                    put("lac", cellInfo.lac.toIntOrNull() ?: return@queryUnwiredLabsAPI null)
                    put("cid", cellInfo.cid.toLongOrNull() ?: return@queryUnwiredLabsAPI null)
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
                    Log.e("MapActivity", "Unwired Labs API error: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Exception calling Unwired Labs API", e)
            null
        }
    }

    private fun buildSignalInfo(cellInfo: DetectedCellInfo): String {
        return when (cellInfo.radio) {
            "nr", "lte" -> {
                val rsrp = cellInfo.rsrp ?: "N/A"
                val rsrq = cellInfo.rsrq ?: "N/A"
                "📶 RSRP: $rsrp dBm | RSRQ: $rsrq dB"
            }
            "wcdma" -> {
                val rscp = cellInfo.rscp ?: "N/A"
                "📶 RSCP: $rscp dBm"
            }
            else -> "📶 Signal: N/A"
        }
    }

    private fun createColoredMarker(color: Int): Drawable {
        val base = ContextCompat.getDrawable(this, R.drawable.marker_default)!!.mutate()
        val wrapped = DrawableCompat.wrap(base)
        DrawableCompat.setTint(wrapped, color)
        return wrapped
    }

    private fun centerOnMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val myLocation = GeoPoint(location.latitude, location.longitude)
                mapView.controller.setCenter(myLocation)
                mapView.controller.setZoom(16.0)

                val myMarker = Marker(mapView)
                myMarker.position = myLocation
                myMarker.title = "Your location"
                myMarker.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)
                mapView.overlays.add(0, myMarker)
                mapView.invalidate()

                Toast.makeText(this, "📍 Centered on your location", Toast.LENGTH_SHORT).show()
            } else {
                mapView.controller.setCenter(GeoPoint(3.4516, -76.5320))
                Toast.makeText(this, "Could not get current location", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}
