package com.example.celltracelogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvMapInfo: TextView
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val cellDatabase = mutableMapOf<String, Pair<Double, Double>>()
    private val detectedCells = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configurar osmdroid
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
        tvMapInfo = findViewById(R.id.tvMapInfo)
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnCenterLocation = findViewById<Button>(R.id.btnCenterLocation)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Configurar mapa
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
                        val mccMatch = Regex("\"mcc\":(\\d+)").find(line)
                        val mncMatch = Regex("\"mnc\":(\\d+)").find(line)
                        val lacMatch = Regex("\"lac\":(\\d+)").find(line)
                        val cidMatch = Regex("\"cellid\":(\\d+)").find(line)

                        if (mccMatch != null && mncMatch != null && lacMatch != null && cidMatch != null) {
                            val mcc = mccMatch.groupValues[1]
                            val mnc = mncMatch.groupValues[1]
                            val lac = lacMatch.groupValues[1]
                            val cid = cidMatch.groupValues[1]

                            // ✅ Intenta múltiples formatos
                            detectedCells.add("$mcc-$mnc-$lac-$cid")

                            // Para WCDMA, el cellid puede estar como LAC+CID compuesto
                            // Agrega variantes para mejorar búsqueda
                            val shortCid = cid.takeLast(5) // últimos 5 dígitos
                            detectedCells.add("$mcc-$mnc-$lac-$shortCid")
                        }
                    }
                }

                Log.i("MapActivity", "Celdas detectadas únicas: ${detectedCells.size}")
                Log.i("MapActivity", "Ejemplos: ${detectedCells.take(5)}")

                runOnUiThread {
                    tvMapInfo.text = "${detectedCells.size} claves de búsqueda generadas"
                    showDetectedCellsOnMap()
                }
            } catch (e: Exception) {
                Log.e("MapActivity", "Error cargando celdas detectadas", e)
            }
        }
    }


    private fun showDetectedCellsOnMap() {
        if (cellDatabase.isEmpty() || detectedCells.isEmpty()) return

        mapView.overlays.clear()

        var count = 0
        detectedCells.forEach { key ->
            val location = cellDatabase[key]
            if (location != null) {
                val (lat, lon) = location
                val marker = Marker(mapView)
                marker.position = GeoPoint(lat, lon)
                marker.title = "Antena ${key.split("-").last()}"
                marker.snippet = "MCC-MNC-LAC-CI: $key\nClick para detalles"
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                marker.setOnMarkerClickListener { clickedMarker, _ ->
                    clickedMarker.showInfoWindow()
                    Toast.makeText(this, "Antena: ${clickedMarker.title}", Toast.LENGTH_SHORT).show()
                    true
                }

                mapView.overlays.add(marker)
                count++
            }
        }

        mapView.invalidate()
        tvMapInfo.text = "$count antenas mostradas en el mapa"
        Log.i("MapActivity", "$count marcadores agregados")
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

                // Marcador de tu ubicación
                val myMarker = Marker(mapView)
                myMarker.position = myLocation
                myMarker.title = "Tu ubicación"
                myMarker.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)
                mapView.overlays.add(0, myMarker)
                mapView.invalidate()

                Toast.makeText(this, "📍 Centrado en tu ubicación", Toast.LENGTH_SHORT).show()
            } else {
                // Si no hay ubicación, centrar en Cali, Colombia
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
