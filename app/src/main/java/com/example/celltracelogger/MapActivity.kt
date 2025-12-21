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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map)

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

        loadCellDatabase()
        loadDetectedCells()
        centerOnMyLocation()
    }

    private fun loadCellDatabase() {
        thread {
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
                Log.i("MapActivity", "Base cargada: ${cellDatabase.size} celdas")
                runOnUiThread { showDetectedCellsOnMap() }
            } catch (e: Exception) {
                Log.e("MapActivity", "Error cargando CSV", e)
            }
        }
    }

    private fun loadDetectedCells() {
        thread {
            try {
                val filesDir = getExternalFilesDir(null) ?: return@thread
                val files = filesDir.listFiles { _, name ->
                    name.startsWith("celltrace_events_") && name.endsWith(".ndjson")
                } ?: return@thread

                files.forEach { file ->
                    file.readLines().forEach { line ->
                        try {
                            val json = JSONObject(line)
                            val mcc = json.optString("mcc", "")
                            val mnc = json.optString("mnc", "")
                            val lac = json.optString("lac", "")
                            val cid = json.optString("cellid", "")
                            val radio = json.optString("radio", "unknown")

                            // Extraer info de señal según tipo de red
                            val rsrp = json.optString("rsrp", null)
                            val rsrq = json.optString("rsrq", null)
                            val rscp = json.optString("rscp", null)

                            if (mcc.isNotEmpty() && mnc.isNotEmpty() && lac.isNotEmpty() && cid.isNotEmpty()) {
                                val key = "$mcc-$mnc-$lac-$cid"

                                // Guardar info completa de la celda
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
                            Log.w("MapActivity", "Error parseando línea: ${e.message}")
                        }
                    }
                }

                Log.i("MapActivity", "Celdas detectadas únicas: ${detectedCellsInfo.size}")

                runOnUiThread {
                    tvMapInfo.text = "${detectedCellsInfo.size} celdas detectadas, consultando ubicaciones..."
                    showDetectedCellsOnMap()
                }
            } catch (e: Exception) {
                Log.e("MapActivity", "Error cargando celdas detectadas", e)
            }
        }
    }

    private fun showDetectedCellsOnMap() {
        if (detectedCellsInfo.isEmpty()) return

        thread {
            mapView.overlays.clear()

            var foundInCSV = 0
            var foundInAPI = 0
            var notFound = 0

            detectedCellsInfo.values.forEach { cellInfo ->
                val location = getCellLocation(cellInfo)

                if (location != null) {
                    val (lat, lon) = location

                    runOnUiThread {
                        val marker = Marker(mapView)
                        marker.position = GeoPoint(lat, lon)

                        // ✅ Color según tipo de red
                        val (color, networkType) = when (cellInfo.radio) {
                            "nr" -> Color.RED to "5G NR"
                            "lte" -> Color.BLUE to "4G LTE"
                            "wcdma" -> Color.GREEN to "3G WCDMA"
                            else -> Color.GRAY to "Desconocido"
                        }

                        marker.icon = createColoredMarker(color)

                        // ✅ Título con tipo de red
                        marker.title = "$networkType - Cell ${cellInfo.cid}"

                        // ✅ Info detallada con señal
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
                            foundInCSV++
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
                val total = foundInCSV + foundInAPI
                tvMapInfo.text = """
                    📍 $total antenas mostradas
                    💾 $foundInCSV de CSV local
                    🌐 $foundInAPI de Unwired Labs API
                    ❌ $notFound no encontradas
                """.trimIndent()

                Log.i("MapActivity", "Marcadores: $total total, $foundInCSV CSV, $foundInAPI API, $notFound no encontradas")
            }
        }
    }

    private fun getCellLocation(cellInfo: DetectedCellInfo): Pair<Double, Double>? {
        // 1. Buscar en CSV local
        val localResult = cellDatabase[cellInfo.key]
        if (localResult != null) {
            Log.d("MapActivity", "✓ Celda ${cellInfo.key} encontrada en CSV local")
            return localResult
        }

        // 2. Consultar Unwired Labs API
        val apiKey = AppConfig.getApiKey(this)
        if (apiKey.isNullOrEmpty()) {
            Log.d("MapActivity", "⚠ No hay API key configurada")
            return null
        }

        return try {
            Log.d("MapActivity", "⚠ Consultando API para ${cellInfo.key}...")
            val location = queryUnwiredLabsAPI(apiKey, cellInfo)

            if (location != null) {
                // Guardar en cache para futuras consultas
                cellDatabase[cellInfo.key] = location
                Log.i("MapActivity", "✓ Celda obtenida de Unwired Labs API")
            } else {
                Log.w("MapActivity", "✗ Celda no encontrada en API")
            }

            location
        } catch (e: Exception) {
            Log.e("MapActivity", "Error consultando API: ${e.message}", e)
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
            else -> "📶 Señal: N/A"
        }
    }

    private fun createColoredMarker(color: Int): android.graphics.drawable.Drawable {
        val drawable = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)!!.mutate()
        drawable.setTint(color)
        return drawable
    }

    private fun centerOnMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permiso de ubicación no concedido", Toast.LENGTH_SHORT).show()
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val myLocation = GeoPoint(location.latitude, location.longitude)
                mapView.controller.setCenter(myLocation)
                mapView.controller.setZoom(16.0)

                val myMarker = Marker(mapView)
                myMarker.position = myLocation
                myMarker.title = "Tu ubicación"
                myMarker.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)
                mapView.overlays.add(0, myMarker)
                mapView.invalidate()

                Toast.makeText(this, "📍 Centrado en tu ubicación", Toast.LENGTH_SHORT).show()
            } else {
                mapView.controller.setCenter(GeoPoint(3.4516, -76.5320))
                Toast.makeText(this, "No se pudo obtener ubicación actual", Toast.LENGTH_SHORT).show()
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
