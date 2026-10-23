package com.andyanderso.claudecopilot

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.Locale
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {

    private val models = listOf(
        "auto" to "Auto — pick per question",
        ClaudeClient.HAIKU to "Haiku — fastest",
        ClaudeClient.SONNET to "Sonnet — balanced",
        ClaudeClient.OPUS to "Opus — most capable"
    )

    // Sensitivity slider: 0..60 maps to a wake threshold of 0.80 (strict) down to 0.20 (sensitive).
    private fun thresholdFor(progress: Int) = 0.80f - progress / 100f
    private fun progressFor(threshold: Float) = ((0.80f - threshold) * 100).roundToInt().coerceIn(0, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)

        val apiKey = findViewById<EditText>(R.id.apiKey)
        val model = findViewById<Spinner>(R.id.model)
        val grounding = findViewById<Switch>(R.id.grounding)
        val modeWhileOpen = findViewById<RadioButton>(R.id.modeWhileOpen)
        val modeAlways = findViewById<RadioButton>(R.id.modeAlways)
        val wakeEnabled = findViewById<Switch>(R.id.wakeEnabled)
        val sensitivity = findViewById<SeekBar>(R.id.sensitivity)
        val sensitivityLabel = findViewById<TextView>(R.id.sensitivityLabel)
        val systemPrompt = findViewById<EditText>(R.id.systemPrompt)

        apiKey.setText(prefs.apiKey)

        model.adapter = ArrayAdapter(this, R.layout.spinner_item, models.map { it.second }).also {
            it.setDropDownViewResource(R.layout.spinner_item)
        }
        model.setSelection(models.indexOfFirst { it.first == prefs.model }.coerceAtLeast(0))

        grounding.isChecked = prefs.grounding
        if (prefs.listenOnlyWhileOpen) modeWhileOpen.isChecked = true else modeAlways.isChecked = true
        wakeEnabled.isChecked = prefs.wakeWordEnabled

        fun showThreshold(progress: Int) {
            sensitivityLabel.text = String.format(
                Locale.US, "Wake sensitivity — threshold %.2f (higher = fewer false wakes, more misses)",
                thresholdFor(progress)
            )
        }
        sensitivity.max = 60
        sensitivity.progress = progressFor(prefs.wakeThreshold)
        showThreshold(sensitivity.progress)
        sensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = showThreshold(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        systemPrompt.setText(prefs.systemPrompt)
        findViewById<Button>(R.id.resetPrompt).setOnClickListener {
            systemPrompt.setText(ClaudeClient.DEFAULT_SYSTEM)
        }

        findViewById<Button>(R.id.clearConversation).setOnClickListener {
            AssistantBus.clear()
            Toast.makeText(this, "Conversation cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.apiKey = apiKey.text.toString()
            prefs.model = models[model.selectedItemPosition.coerceIn(0, models.lastIndex)].first
            prefs.grounding = grounding.isChecked
            prefs.listenOnlyWhileOpen = modeWhileOpen.isChecked
            prefs.wakeWordEnabled = wakeEnabled.isChecked
            prefs.wakeThreshold = thresholdFor(sensitivity.progress)
            prefs.systemPrompt = systemPrompt.text.toString()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()   // MainActivity restarts the service with the new settings when it comes back
        }
    }
}
