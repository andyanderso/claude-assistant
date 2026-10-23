package com.andyanderso.claudecopilot

import android.content.Context
import android.content.SharedPreferences

/**
 * Every user setting. Stored in app-private SharedPreferences, which other apps can't read.
 * Backups are disabled in the manifest so the API key never leaves the phone that way.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("copilot", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** "auto" (pick per question) or an explicit model id. */
    var model: String
        get() = sp.getString("model", "auto") ?: "auto"
        set(v) = sp.edit().putString("model", v).apply()

    /** Let Claude search the web and cite sources. */
    var grounding: Boolean
        get() = sp.getBoolean("grounding", true)
        set(v) = sp.edit().putBoolean("grounding", v).apply()

    /**
     * true  = listen for "Hey Claude" only while the app is open on screen (the default).
     * false = keep listening in the background, with a notification, until you tap Stop.
     */
    var listenOnlyWhileOpen: Boolean
        get() = sp.getBoolean("listen_only_while_open", true)
        set(v) = sp.edit().putBoolean("listen_only_while_open", v).apply()

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean("wake_word_enabled", true)
        set(v) = sp.edit().putBoolean("wake_word_enabled", v).apply()

    /**
     * Wake-word score needed to trigger, 0..1. openWakeWord models are tuned around 0.5.
     * Lower = triggers more easily (more false wakes); higher = stricter (more misses).
     */
    var wakeThreshold: Float
        get() = sp.getFloat("wake_threshold", 0.5f)
        set(v) = sp.edit().putFloat("wake_threshold", v).apply()

    var systemPrompt: String
        get() = sp.getString("system_prompt", null) ?: ClaudeClient.DEFAULT_SYSTEM
        set(v) = sp.edit().putString("system_prompt", v.ifBlank { ClaudeClient.DEFAULT_SYSTEM }).apply()
}
