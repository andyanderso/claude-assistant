package com.andyanderso.claudecopilot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/**
 * The big status orb. Readable at a glance: color says what's happening,
 * the pulse speed says how active it is.
 *   amber, slow breath = waiting for "Hey Claude"
 *   teal, quick pulse  = listening to you
 *   dim amber          = thinking
 *   blue               = speaking
 *   rose               = problem
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var phase: Phase = Phase.OFF
        set(value) {
            if (field == value) return
            field = value
            restartPulse()
            invalidate()
        }

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
    }

    private var pulse = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isClickable = true
        contentDescription = "Tap to talk"
    }

    private fun restartPulse() {
        val ms = when (phase) {
            Phase.WAITING -> 2200L
            Phase.LISTENING -> 550L
            Phase.THINKING -> 900L
            Phase.SPEAKING -> 350L
            else -> 0L
        }
        animator.cancel()
        if (ms == 0L || !isAttachedToWindow) {
            pulse = 0f
            return
        }
        animator.duration = ms
        animator.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartPulse()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f
        if (base <= 0f) return
        val c = Palette.forPhase(phase)

        // Soft glow behind everything
        val glowR = base * 0.95f
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(Palette.withAlpha(c, 90), Palette.withAlpha(c, 0)), null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)

        // Outer ring: ripples outward while listening, barely moves otherwise
        val ripple = if (phase == Phase.LISTENING) 1f else 0.25f
        ringPaint.color = Palette.withAlpha(c, (80 * (1f - pulse * 0.6f * ripple)).toInt())
        canvas.drawCircle(cx, cy, base * (0.78f + 0.16f * pulse * ripple), ringPaint)

        // The orb: lit from slightly above, darker at the rim
        val r = base * 0.5f * (0.97f + 0.06f * pulse)
        orbPaint.shader = RadialGradient(
            cx, cy - r * 0.25f, r * 1.3f,
            intArrayOf(Palette.blend(c, Color.WHITE, 0.25f), c, Palette.blend(c, Color.BLACK, 0.55f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        orbPaint.alpha = if (phase == Phase.THINKING) 150 else 255
        canvas.drawCircle(cx, cy, r, orbPaint)
    }
}
