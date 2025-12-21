package com.example.celltracelogger

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var etWebhook: EditText
    private lateinit var btnSaveConfig: Button
    private lateinit var btnSkip: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etApiKey = findViewById(R.id.etApiKey)
        etWebhook = findViewById(R.id.etWebhook)
        btnSaveConfig = findViewById(R.id.btnSaveConfig)
        btnSkip = findViewById(R.id.btnSkip)

        // Cargar valores existentes si los hay
        loadExistingConfig()

        btnSaveConfig.setOnClickListener {
            saveConfiguration()
        }

        btnSkip.setOnClickListener {
            skipConfiguration()
        }

        // Manejo moderno del botón atrás
        onBackPressedDispatcher.addCallback(this) {
            if (!AppConfig.isConfigured(this@SettingsActivity)) {
                Toast.makeText(
                    this@SettingsActivity,
                    "Debes configurar la app o omitir",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                finish()
            }
        }
    }

    private fun loadExistingConfig() {
        val existingApiKey = AppConfig.getApiKey(this)
        val existingWebhook = AppConfig.getWebhook(this)

        if (!existingApiKey.isNullOrEmpty()) {
            etApiKey.setText(existingApiKey)
        }

        if (!existingWebhook.isNullOrEmpty()) {
            etWebhook.setText(existingWebhook)
        }
    }

    private fun saveConfiguration() {
        val apiKey = etApiKey.text.toString().trim()
        val webhook = etWebhook.text.toString().trim()

        // Validación mínima
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "⚠️ Debes ingresar una API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // Guardar configuración
        AppConfig.saveConfig(this, apiKey, webhook)

        Toast.makeText(this, "✅ Configuración guardada", Toast.LENGTH_SHORT).show()

        // Ir a MainActivity
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun skipConfiguration() {
        // Guardar config vacía para marcar como "configurado"
        AppConfig.saveConfig(this, "", etWebhook.text.toString().trim())

        Toast.makeText(this, "⚠️ Modo solo base local activado", Toast.LENGTH_SHORT).show()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
