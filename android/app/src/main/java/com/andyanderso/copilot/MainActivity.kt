package com.andyanderso.copilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The screen. It only displays what AssistantService is doing and passes taps along.
 * It also decides when the service runs: started whenever this screen is visible, and
 * stopped when it leaves the screen if "Only while open" is selected.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var modelChip: TextView
    private lateinit var listenChip: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var scroll: ScrollView

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            AssistantService.start(this)
        } else {
            AssistantBus.phase.value = Phase.ERROR
            AssistantBus.hint.value =
                "Claude Copilot needs the microphone. Allow it in Settings → Apps → Claude Copilot → Permissions."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        orb = findViewById(R.id.orb)
        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        modelChip = findViewById(R.id.modelChip)
        listenChip = findViewById(R.id.listenChip)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.transcriptScroll)

        orb.setOnClickListener { if (hasMic()) AssistantService.tap(this) else requestPermissions() }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        listenChip.setOnClickListener {
            prefs.listenOnlyWhileOpen = !prefs.listenOnlyWhileOpen
            renderListenChip()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AssistantBus.phase.collect { renderPhase(it) } }
                launch { AssistantBus.hint.collect { hint.text = it } }
                launch {
                    AssistantBus.lastModel.collect { m ->
                        modelChip.isVisible = m != null
                        modelChip.text = m?.let { ModelRouter.shortName(it).uppercase(Locale.US) }
                    }
                }
                launch { AssistantBus.lines.collect { renderTranscript(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        renderListenChip()
        if (prefs.apiKey.isBlank()) {
            AssistantBus.hint.value = "Tap ⚙ to add your Anthropic API key."
        }
        if (hasMic()) AssistantService.start(this) else requestPermissions()
    }

    override fun onStop() {
        super.onStop()
        // "Only while open": stop listening as soon as Claude Copilot leaves the screen
        // (home button, another app, or the screen turning off).
        if (prefs.listenOnlyWhileOpen && !isChangingConfigurations) {
            AssistantService.stop(this)
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        askPermissions.launch(wanted.toTypedArray())
    }

    // ------------------------------------------------------------------ rendering

    private fun renderListenChip() {
        val whileOpen = prefs.listenOnlyWhileOpen
        listenChip.text = if (whileOpen) "●  Listens only while open" else "●  Listens in background"
        listenChip.setTextColor(if (whileOpen) Palette.TEAL else Palette.AMBER)
    }

    private fun renderPhase(p: Phase) {
        orb.phase = p
        status.setTextColor(Palette.forPhase(p))
        status.text = when (p) {
            Phase.OFF -> "PAUSED"
            Phase.IDLE -> "TAP TO TALK"
            Phase.WAITING -> "HEY CLAUDE"
            Phase.LISTENING -> "LISTENING"
            Phase.THINKING -> "THINKING"
            Phase.SPEAKING -> "SPEAKING"
            Phase.ERROR -> "CHECK THIS"
        }
    }

    private fun renderTranscript(lines: List<Line>) {
        transcript.removeAllViews()
        if (lines.isEmpty()) {
            transcript.addView(TextView(this).apply {
                text = "Your conversation will show here."
                setTextColor(Palette.MUTED)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dpi(18), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return
        }
        lines.forEach { transcript.addView(bubble(it)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(line: Line): TextView {
        val sb = SpannableStringBuilder()

        line.confidence?.let { level ->
            val start = sb.length
            sb.append(" ${level.uppercase(Locale.US)} CONFIDENCE ")
            sb.setSpan(BackgroundColorSpan(Palette.forConfidence(level)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Palette.BG), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.72f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n")
        }

        sb.append(line.text)

        if (line.sources.isNotEmpty()) {
            sb.append("\n\n")
            line.sources.forEachIndexed { i, s ->
                if (i > 0) sb.append("   ")
                val start = sb.length
                sb.append(domainOf(s.url))
                sb.setSpan(URLSpan(s.url), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.82f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        return TextView(this).apply {
            text = sb
            textSize = 16f
            setLineSpacing(0f, 1.15f)
            setTextColor(Palette.INK)
            setLinkTextColor(Palette.BLUE)
            if (line.sources.isNotEmpty()) movementMethod = LinkMovementMethod.getInstance()
            setPadding(dpi(14), dpi(11), dpi(14), dpi(11))
            maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                if (line.fromUser) {
                    setColor(Palette.USER_BUBBLE)
                } else {
                    setColor(Palette.BG)
                    setStroke(dpi(1), Palette.LINE)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (line.fromUser) Gravity.END else Gravity.START
                topMargin = dpi(8)
            }
        }
    }

    private fun domainOf(url: String): String =
        Uri.parse(url).host?.removePrefix("www.") ?: url
}
