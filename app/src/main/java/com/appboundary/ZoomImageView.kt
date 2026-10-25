package com.appboundary

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.ImageView

/** ImageView that can be zoomed and panned. Touch gestures are fed in by [MediaPager]. */
class ZoomImageView(context: Context) : ImageView(context) {

    private val m = Matrix()
    private val rect = RectF()
    private var userScale = 1f
    private var ready = false

    init {
        scaleType = ScaleType.MATRIX
        ready = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (ready) resetFit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ready) resetFit()
    }

    fun isZoomed(): Boolean = userScale > 1.02f

    /** Fits the whole picture on screen, centered. */
    fun resetFit() {
        userScale = 1f
        val d = drawable
        m.reset()
        if (d != null && width > 0 && height > 0 && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
            val dw = d.intrinsicWidth.toFloat()
            val dh = d.intrinsicHeight.toFloat()
            val s = minOf(width / dw, height / dh)
            m.postScale(s, s)
            m.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        }
        imageMatrix = m
    }

    fun scaleBy(factor: Float, fx: Float, fy: Float) {
        if (drawable == null) return
        val target = (userScale * factor).coerceIn(1f, MAX_ZOOM)
        val f = target / userScale
        m.postScale(f, f, fx, fy)
        userScale = target
        clamp()
        imageMatrix = m
    }

    fun toggleZoom(fx: Float, fy: Float) {
        if (isZoomed()) resetFit() else scaleBy(DOUBLE_TAP_ZOOM, fx, fy)
    }

    /** Moves the picture by [dx],[dy]. Returns the part of [dx] that could not be applied (it hit an edge). */
    fun panBy(dx: Float, dy: Float): Float {
        if (drawable == null) return dx
        val before = left()
        m.postTranslate(dx, dy)
        clamp()
        imageMatrix = m
        return dx - (left() - before)
    }

    private fun left(): Float {
        val d = drawable ?: return 0f
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(rect)
        return rect.left
    }

    /** Keeps the picture inside the view (or centered when smaller than it). */
    private fun clamp() {
        val d = drawable ?: return
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) {
            dx = (width - rect.width()) / 2f - rect.left
        } else if (rect.left > 0f) {
            dx = -rect.left
        } else if (rect.right < width) {
            dx = width - rect.right
        }
        if (rect.height() <= height) {
            dy = (height - rect.height()) / 2f - rect.top
        } else if (rect.top > 0f) {
            dy = -rect.top
        } else if (rect.bottom < height) {
            dy = height - rect.bottom
        }
        m.postTranslate(dx, dy)
    }

    companion object {
        private const val MAX_ZOOM = 5f
        private const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
