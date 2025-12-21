package com.example.celltracelogger

import android.content.Context

object AppConfig {
    private const val PREFS_NAME = "celltrace_config"
    private const val KEY_API_KEY = "unwired_api_key"
    private const val KEY_WEBHOOK = "discord_webhook"

    fun isConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_API_KEY)
    }

    fun saveConfig(context: Context, apiKey: String, webhook: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_WEBHOOK, webhook)
            .apply()
    }

    fun getApiKey(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)
    }

    fun getWebhook(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WEBHOOK, null)
    }
}
