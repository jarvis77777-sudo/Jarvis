package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The glowing blue Jarvis orb. Reacts to [JarvisBus.State]. */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var state: JarvisBus.State = JarvisBus.State.OFF
        set(value) {
            field = value
            invalidate()
        }

    private var phase = 0f // loops 0..1
    private var baseR = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF7FDBFF.toInt()
    }
    private val arcRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseR = min(w, h) * 0.22f
        if (baseR <= 0f) return
        // Gradients are centred on (0,0); onDraw translates the canvas to the middle.
        glowPaint.shader = RadialGradient(
            0f, 0f, baseR * 2.3f,
            intArrayOf(0x9900B4FF.toInt(), 0x3300B4FF, 0x0000B4FF),
            floatArrayOf(0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.shader = RadialGradient(
            0f, 0f, baseR,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF6FE3FF.toInt(), 0xFF0A8CFF.toInt(), 0xFF0450A0.toInt()),
            floatArrayOf(0f, 0.25f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (baseR <= 0f) return
        val t = phase * 2f * PI.toFloat()

        // amplitude, pulse frequency (integer so the loop is seamless), ring spin speed, brightness
        val amp: Float
        val pulse: Int
        val spin: Int
        val alpha: Int
        when (state) {
            JarvisBus.State.OFF -> { amp = 0.015f; pulse = 1; spin = 0; alpha = 110 }
            JarvisBus.State.IDLE -> { amp = 0.04f; pulse = 1; spin = 1; alpha = 255 }
            JarvisBus.State.LISTENING -> { amp = 0.10f; pulse = 3; spin = 2; alpha = 255 }
            JarvisBus.State.THINKING -> { amp = 0.05f; pulse = 2; spin = 5; alpha = 255 }
            JarvisBus.State.SPEAKING -> { amp = 0.13f; pulse = 5; spin = 2; alpha = 255 }
        }
        val scale = 1f + amp * sin(t * pulse)

        glowPaint.alpha = alpha
        corePaint.alpha = alpha

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)

        canvas.drawCircle(0f, 0f, baseR * 2.3f, glowPaint)
        canvas.drawCircle(0f, 0f, baseR, corePaint)

        ringPaint.strokeWidth = baseR * 0.05f
        ringPaint.alpha = (alpha * 0.35f).toInt()
        canvas.drawCircle(0f, 0f, baseR * 1.3f, ringPaint)

        ringPaint.alpha = alpha
        for (i in 0..2) {
            val r = baseR * (1.3f + 0.28f * i)
            val dir = if (i % 2 == 0) 1f else -1f
            val start = phase * 360f * spin * dir + i * 40f
            arcRect.set(-r, -r, r, r)
            val sweep = 70f + i * 25f
            canvas.drawArc(arcRect, start, sweep, false, ringPaint)
            canvas.drawArc(arcRect, start + 180f, sweep, false, ringPaint)
        }
        canvas.restore()
    }
}
