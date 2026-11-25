package com.elanticristo616.roku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/** Nieve animada: copos cayendo con vaivén, sobre fondo transparente. */
class SnowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Flake(
        var x: Float, var y: Float, var r: Float, var speed: Float,
        var sway: Float, var phase: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private var flakes: List<Flake> = emptyList()
    private var seeded = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val rnd = Random(42)
            flakes = List(60) {
                Flake(
                    x = rnd.nextFloat() * w,
                    y = rnd.nextFloat() * h,
                    r = 2f + rnd.nextFloat() * 5f,
                    speed = 0.6f + rnd.nextFloat() * 1.6f,
                    sway = 12f + rnd.nextFloat() * 22f,
                    phase = rnd.nextFloat() * 6.28f
                )
            }
            seeded = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!seeded) return
        val w = width.toFloat()
        val h = height.toFloat()
        val t = System.currentTimeMillis() / 1000f
        for (f in flakes) {
            val x = f.x + sin(t + f.phase) * f.sway
            var y = f.y + t * f.speed * 30f
            y %= (h + 20f)
            paint.alpha = 200
            canvas.drawCircle(x % (w + 20f), y - 10f, f.r, paint)
        }
        postInvalidateOnAnimation()
    }
}
