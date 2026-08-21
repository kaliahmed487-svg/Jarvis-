package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.assistant.core.VisualizerMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pitch-black-friendly, single-element UI: a glowing ring that idles with a
 * slow "breathing" pulse and deforms into a fluid waveform when the mic or
 * TTS engine reports amplitude. No text, no buttons — state is communicated
 * purely through color, motion and shape.
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var mode: VisualizerMode = VisualizerMode.IDLE
    private var targetAmplitude = 0f
    private var smoothedAmplitude = 0f

    // Rolling ring buffer of recent amplitude samples so the waveform looks continuous
    // rather than jumping to a single instantaneous value every frame.
    private val sampleCount = 64
    private val samples = FloatArray(sampleCount)
    private var writeIndex = 0

    private var phase = 0f // drives idle rotation / wave crawl

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val wavePath = Path()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16 // ~60fps driver; we don't use the animated value itself, just the ticks
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { onTick() }
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    fun setMode(newMode: VisualizerMode) {
        mode = newMode
    }

    /** Feed a new 0f..1f amplitude sample (mic RMS while listening, synthetic envelope while speaking). */
    fun pushAmplitude(level: Float) {
        targetAmplitude = level.coerceIn(0f, 1f)
    }

    private fun onTick() {
        phase += 0.045f
        if (phase > 1000f) phase = 0f

        // Ease amplitude toward target so waves feel fluid, not jittery.
        smoothedAmplitude += (targetAmplitude - smoothedAmplitude) * 0.25f

        val idleBreath = when (mode) {
            VisualizerMode.IDLE -> (sin(phase * 0.6) * 0.05 + 0.12).toFloat()
            else -> 0f
        }
        val sample = when (mode) {
            VisualizerMode.IDLE -> idleBreath
            VisualizerMode.THINKING -> 0.18f + Random.nextFloat() * 0.06f
            VisualizerMode.LISTENING, VisualizerMode.SPEAKING -> smoothedAmplitude
        }
        samples[writeIndex] = sample
        writeIndex = (writeIndex + 1) % sampleCount

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) * 0.20f

        val (r, g, b) = colorForMode(mode)
        val accent = Color.rgb(r, g, b)

        // Outer glow
        glowPaint.shader = RadialGradient(
            cx, cy, baseRadius * 3.2f,
            Color.argb(90, r, g, b), Color.argb(0, r, g, b),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, baseRadius * 3.2f, glowPaint)

        // Waveform ring — points sampled around the circle, radius modulated by the sample buffer.
        wavePath.reset()
        val points = 128
        for (i in 0..points) {
            val angle = (i.toDouble() / points) * 2 * PI
            val sampleIdx = (((angle / (2 * PI)) * sampleCount).toInt() + writeIndex) % sampleCount
            val amp = samples[sampleIdx]
            val wobble = if (mode == VisualizerMode.IDLE) {
                sin(angle * 6 + phase * 4) * 0.02
            } else {
                sin(angle * 10 + phase * 10) * amp * 0.35
            }
            val radius = baseRadius * (1f + amp * 1.6f + wobble.toFloat())
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }
        wavePath.close()

        ringPaint.color = accent
        ringPaint.alpha = 230
        canvas.drawPath(wavePath, ringPaint)

        // Solid core
        corePaint.shader = RadialGradient(
            cx, cy, baseRadius,
            Color.argb(255, minOf(255, r + 60), minOf(255, g + 60), minOf(255, b + 60)),
            Color.argb(160, r, g, b),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, baseRadius * 0.55f, corePaint)
    }

    /** Distinct hues so the state is legible at a glance even with no text on screen. */
    private fun colorForMode(mode: VisualizerMode): Triple<Int, Int, Int> = when (mode) {
        VisualizerMode.IDLE -> Triple(0, 168, 255)       // cool cyan-blue, resting
        VisualizerMode.LISTENING -> Triple(0, 255, 200)  // brighter aqua, alert
        VisualizerMode.THINKING -> Triple(150, 90, 255)  // violet, processing
        VisualizerMode.SPEAKING -> Triple(255, 170, 0)   // warm amber, talking
    }
}
