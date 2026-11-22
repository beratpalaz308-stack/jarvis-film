package com.jarvis.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.view.View

class CoreView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var bootStart = 0L
    private var music = false
    private var lastMusic = 0L
    var forcedMode: String? = null

    fun playBoot() {
        bootStart = System.currentTimeMillis()
    }

    private fun tri(cx: Float, cy: Float, rad: Float, rot: Float) {
        path.reset()
        for (i in 0 until 3) {
            val ang = Math.toRadians((-90.0 + rot + i * 120.0))
            val x = cx + (rad * Math.cos(ang)).toFloat()
            val y = cy + (rad * Math.sin(ang)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val t = (now % 100000L) / 1000f
        val tt = t.toDouble()
        val mode = forcedMode ?: Bus.mode
        val low = Bus.lowBattery
        if (now - lastMusic > 2000L) {
            lastMusic = now
            music = try {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
            } catch (e: Exception) {
                false
            }
        }
        var boot = 1f
        if (bootStart > 0L) {
            boot = ((now - bootStart) / 3000f).coerceIn(0f, 1f)
            if (boot >= 1f) bootStart = 0L
        }
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.92f
        val col = if (mode == "ERROR") 0xFFFF3B30.toInt() else 0xFF00E5FF.toInt()

        c.save()
        if (mode == "ERROR") c.translate((Math.sin(tt * 70.0) * r * 0.03).toFloat(), 0f)
        if (music && mode == "IDLE") {
            val s = 1f + 0.035f * Math.sin(tt * 11.0).toFloat()
            c.scale(s, s, cx, cy)
        }
        val a = (255 * boot).toInt().coerceIn(0, 255)

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = col
        p.alpha = a
        p.strokeWidth = r * 0.03f
        c.drawCircle(cx, cy, r, p)
        p.alpha = (a * 0.7f).toInt()
        c.drawCircle(cx, cy, r * 0.62f, p)

        p.alpha = a
        p.strokeWidth = r * 0.05f
        rect.set(cx - r * 0.82f, cy - r * 0.82f, cx + r * 0.82f, cy + r * 0.82f)
        val base = when (mode) {
            "THINK" -> 220f
            "WAKE" -> 130f
            else -> 38f
        }
        val speed = if (low) 0f else base * (0.2f + 0.8f * boot)
        for (k in 0 until 3) c.drawArc(rect, t * speed + k * 120f, 60f, false, p)

        p.strokeWidth = r * 0.035f
        p.alpha = a
        tri(cx, cy, r * 0.46f, 0f)
        c.drawPath(path, p)
        tri(cx, cy, r * 0.30f, 180f)
        c.drawPath(path, p)

        p.style = Paint.Style.FILL
        val breathe = 0.55f + 0.45f * Math.sin(tt * 1.1).toFloat()
        val glow = when (mode) {
            "WAKE" -> 1f
            "SPEAK" -> 0.8f + 0.2f * Math.sin(tt * 9.0).toFloat()
            "THINK" -> 0.7f
            else -> breathe
        }
        p.alpha = (255 * glow * boot).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r * (0.10f + 0.04f * glow), p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.02f
        when (mode) {
            "WAKE" -> {
                val k = (t * 1.4f) % 1f
                p.alpha = ((1f - k) * 200f).toInt().coerceIn(0, 255)
                c.drawCircle(cx, cy, r * (0.3f + 0.75f * k), p)
            }
            "SPEAK" -> {
                for (i in 0 until 3) {
                    val k = (t * 1.2f + i / 3f) % 1f
                    p.alpha = ((1f - k) * 180f).toInt().coerceIn(0, 255)
                    c.drawCircle(cx, cy, r * (0.35f + 0.6f * k), p)
                }
            }
            "THINK" -> {
                val y = cy - r + ((t * 0.9f) % 1f) * 2f * r
                p.alpha = 190
                c.drawLine(cx - r, y, cx + r, y, p)
            }
        }
        c.restore()

        if (low) postInvalidateDelayed(100L) else postInvalidateOnAnimation()
    }
}

class DataRainView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random(7)
    private var cols = 0
    private var ys = FloatArray(0)
    private var sp = FloatArray(0)
    private var last = 0L
    private val chars = "01ABCDEF<>/|=+-"

    init {
        p.typeface = Typeface.MONOSPACE
        p.color = 0xFF00E5FF.toInt()
        p.textSize = 22f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cols = maxOf(1, w / 28)
        ys = FloatArray(cols) { rnd.nextFloat() * h }
        sp = FloatArray(cols) { 80f + rnd.nextFloat() * 160f }
    }

    override fun onDraw(c: Canvas) {
        if (Bus.lowBattery || cols == 0) {
            postInvalidateDelayed(500L)
            return
        }
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1.0e9f).coerceAtMost(0.1f)
        last = now
        for (i in 0 until cols) {
            ys[i] += sp[i] * dt
            if (ys[i] > height + 140f) {
                ys[i] = -rnd.nextFloat() * 200f
                sp[i] = 80f + rnd.nextFloat() * 160f
            }
            for (k in 0 until 6) {
                val y = ys[i] - k * 24f
                p.alpha = (70 - k * 11).coerceAtLeast(0)
                c.drawText(chars[rnd.nextInt(chars.length)].toString(), i * 28f + 4f, y, p)
            }
        }
        postInvalidateOnAnimation()
    }
}

object CoreDraw {
    fun bitmap(ctx: Context, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF05080F.toInt())
        val v = CoreView(ctx)
        v.forcedMode = "IDLE"
        v.layout(0, 0, size, size)
        v.draw(c)
        return bmp
    }
}
