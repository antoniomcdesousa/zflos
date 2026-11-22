package com.zfl.os

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

/** A crisp 9x9 grid that stays sharp at any size. Your digits black, filled-in digits blue. */
class SolutionView(c: Context, private val v: IntArray, private val auto: BooleanArray) : View(c) {
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pl = Paint()

    init {
        pt.textAlign = Paint.Align.CENTER
        pt.typeface = Typeface.DEFAULT_BOLD
        pl.color = 0xFF222222.toInt()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val n = width.toFloat()
        val u = n / 9f
        c.drawColor(Color.WHITE)
        pt.textSize = u * 0.74f
        val fm = pt.fontMetrics
        for (i in 0 until 81) {
            val d = v[i]
            if (d == 0) continue
            pt.color = if (auto[i]) 0xFF1A5FD0.toInt() else 0xFF111111.toInt()
            val cx = (i % 9 + 0.5f) * u
            val cy = (i / 9 + 0.5f) * u - (fm.ascent + fm.descent) / 2f
            c.drawText(d.toString(), cx, cy, pt)
        }
        for (k in 0..9) {
            pl.strokeWidth = if (k % 3 == 0) max(3f, n / 70f) else max(1f, n / 260f)
            c.drawLine(k * u, 0f, k * u, n, pl)
            c.drawLine(0f, k * u, n, k * u, pl)
        }
    }
}
