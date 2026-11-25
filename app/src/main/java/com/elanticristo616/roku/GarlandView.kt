package com.elanticristo616.roku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/** Guirnalda navideña: cable ondulado con foquitos de colores que parpadean. */
class GarlandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val bulbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bulbColors = intArrayOf(
        0xFFCD2D32.toInt(), // rojo
        0xFFFFD54F.toInt(), // dorado
        0xFF4FC3F7.toInt(), // azul claro
        0xFF81C784.toInt(), // verde claro
        0xFFFF8A65.toInt()  // naranja
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = System.currentTimeMillis() / 1000f
        val bulbs = 14
        val step = w / bulbs
        var prevX = 0f
        var prevY = h * 0.25f
        for (i in 0..bulbs) {
            val x = i * step
            val y = h * 0.25f + sin(i * 0.9f) * h * 0.28f
            canvas.drawLine(prevX, prevY, x, y, cablePaint)
            prevX = x
            prevY = y
        }
        for (i in 0 until bulbs) {
            val x = (i + 0.5f) * step
            val y = h * 0.25f + sin((i + 0.5f) * 0.9f) * h * 0.28f + h * 0.22f
            val phase = sin(t * 2f + i * 1.3f) * 0.5f + 0.5f
            val color = bulbColors[i % bulbColors.size]
            glowPaint.color = color
            glowPaint.alpha = (40 + phase * 60).toInt()
            canvas.drawCircle(x, y, 16f, glowPaint)
            bulbPaint.color = color
            bulbPaint.alpha = (150 + phase * 105).toInt()
            canvas.drawCircle(x, y, 8f, bulbPaint)
        }
        postInvalidateOnAnimation()
    }
}
