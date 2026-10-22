package com.andyanderso.copilot

import android.content.Context
import android.graphics.Color
import kotlin.math.roundToInt

/** Same dashboard palette as the web app. */
object Palette {
    val BG = Color.parseColor("#0C0F14")
    val BG2 = Color.parseColor("#11151C")
    val LINE = Color.parseColor("#1E242E")
    val INK = Color.parseColor("#ECEFF3")
    val MUTED = Color.parseColor("#7C8794")
    val AMBER = Color.parseColor("#F3A93C")   // waiting / thinking
    val TEAL = Color.parseColor("#2FD3A5")    // listening, high confidence
    val BLUE = Color.parseColor("#5AA2FF")    // speaking, links
    val ROSE = Color.parseColor("#FF6B6B")    // error, low confidence
    val USER_BUBBLE = Color.parseColor("#1B222C")

    fun forPhase(phase: Phase): Int = when (phase) {
        Phase.LISTENING -> TEAL
        Phase.SPEAKING -> BLUE
        Phase.ERROR -> ROSE
        Phase.IDLE, Phase.OFF -> blend(AMBER, MUTED, 0.55f)
        else -> AMBER
    }

    fun forConfidence(level: String): Int = when (level) {
        "high" -> TEAL
        "low" -> ROSE
        else -> AMBER
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Mix [a] toward [b] by [t] (0 = all a, 1 = all b). */
    fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
    )
}

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density
fun Context.dpi(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
