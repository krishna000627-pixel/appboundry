package com.appboundary

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.VideoView
import kotlin.math.abs

/**
 * Full-screen gallery pager: swipe left/right between items (finger-following, with fling),
 * pinch or double-tap to zoom, drag up/down to close, single tap for the controls.
 * Three pages (previous, current, next) are recycled.
 */
class MediaPager @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    class Page(context: Context) : FrameLayout(context) {
        val image = ZoomImageView(context)
        val video = VideoView(context)
        var isVideo = false
        var videoReady = false
        var token = ""

        init {
            addView(
                image,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                video,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
            video.visibility = View.GONE
        }

        /** Stops any video and shows the picture again, unzoomed. */
        fun resetState() {
            try {
                video.setOnPreparedListener(null)
                video.setOnCompletionListener(null)
                video.stopPlayback()
            } catch (e: Exception) {
            }
            videoReady = false
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.resetFit()
        }

        fun clear() {
            resetState()
            image.setImageDrawable(null)
            isVideo = false
            token = ""
        }
    }

    interface Host {
        fun count(): Int

        /** Load the content for [index] into [page]; it may finish later, so check page.token. */
        fun bind(page: Page, index: Int)
        fun onTap()
        fun onSettled(index: Int)
        fun onDragProgress(progress: Float)
        fun onDismiss()
    }

    var host: Host? = null
    var index = 0
        private set

    // previous, current, next
    private val slots = Array(3) { Page(context) }
    private var dragX = 0f
    private var dragY = 0f
    private var axis = AXIS_NONE
    private var animating = false
    private var multi = false
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val p = slots[1]
                if (!p.isVideo) p.image.scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    ).also { it.isQuickScaleEnabled = false }

    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                host?.onTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = slots[1]
                if (!p.isVideo) p.image.toggleZoom(e.x, e.y)
                return true
            }
        }
    )

    init {
        for (p in slots) {
            addView(
                p,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    // ---- public API ---------------------------------------------------------

    fun currentPage(): Page = slots[1]

    /** Shows item [start] and prepares its neighbours. */
    fun open(start: Int) {
        val n = host?.count() ?: 0
        if (n == 0) return
        index = start.coerceIn(0, n - 1)
        dragX = 0f
        dragY = 0f
        animating = false
        for (i in 0..2) bindSlot(i)
        layoutSlots()
        host?.onSettled(index)
    }

    fun release() {
        for (p in slots) p.clear()
        dragX = 0f
        dragY = 0f
        animating = false
    }

    // ---- layout -------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutSlots()
    }

    private fun bindSlot(i: Int) {
        val idx = index + i - 1
        val n = host?.count() ?: 0
        if (idx in 0 until n) host?.bind(slots[i], idx) else slots[i].clear()
    }

    private fun progress(): Float =
        if (height <= 0) 0f else (abs(dragY) / (height * 0.5f)).coerceIn(0f, 1f)

    private fun layoutSlots() {
        val n = host?.count() ?: 0
        val w = width.toFloat()
        val p = progress()
        for (i in 0..2) {
            val page = slots[i]
            val idx = index + i - 1
            page.visibility = if (idx in 0 until n) View.VISIBLE else View.INVISIBLE
            page.translationX = (i - 1) * w + dragX
            page.translationY = if (i == 1) dragY else 0f
            val s = if (i == 1) 1f - 0.12f * p else 1f
            page.scaleX = s
            page.scaleY = s
        }
    }

    // ---- touch --------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (animating) return true
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                axis = AXIS_NONE
                multi = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep)
                lastY = e.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (scaler.isInProgress || e.pointerCount > 1) {
                    lastX = e.x
                    lastY = e.y
                    return true
                }
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x
                lastY = e.y
                onDrag(slots[1], e, dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(e)
                finishGesture(true)
            }
            MotionEvent.ACTION_CANCEL -> finishGesture(false)
        }
        return true
    }

    private fun resist(dx: Float): Float {
        val n = host?.count() ?: 0
        val target = dragX + dx
        return if ((target > 0f && index == 0) || (target < 0f && index >= n - 1)) dx * 0.4f else dx
    }

    private fun onDrag(page: Page, e: MotionEvent, dx0: Float, dy0: Float) {
        var dx = dx0
        var dy = dy0
        val zoomed = !page.isVideo && page.image.isZoomed()
        if (zoomed) {
            if (dragX != 0f) {
                // handing over to the neighbouring page; stops when the finger comes back
                val target = dragX + dx
                dragX = if (target * dragX <= 0f) 0f else target
                layoutSlots()
                return
            }
            val left = page.image.panBy(dx, dy)
            if (abs(left) > 0.5f) {
                axis = AXIS_H
                dragX += resist(left)
                layoutSlots()
            }
            return
        }
        if (multi) return
        if (axis == AXIS_NONE) {
            val tx = e.x - downX
            val ty = e.y - downY
            if (abs(tx) < slop && abs(ty) < slop) return
            axis = if (abs(tx) > abs(ty)) AXIS_H else AXIS_V
            dx = tx
            dy = ty
        }
        if (axis == AXIS_H) {
            dragX += resist(dx)
        } else {
            dragY += dy
            host?.onDragProgress(progress())
        }
        layoutSlots()
    }

    private fun finishGesture(up: Boolean) {
        val t = tracker
        var vx = 0f
        var vy = 0f
        if (up && t != null) {
            t.computeCurrentVelocity(1000)
            vx = t.xVelocity
            vy = t.yVelocity
        }
        t?.recycle()
        tracker = null
        when {
            dragX != 0f -> settleHorizontal(vx)
            dragY != 0f -> settleVertical(vy)
        }
        axis = AXIS_NONE
    }

    private fun settleHorizontal(vx: Float) {
        val w = width.toFloat()
        val n = host?.count() ?: 0
        val next = dragX < 0f && index < n - 1 && (-dragX > w * 0.25f || vx < -900f)
        val prev = dragX > 0f && index > 0 && (dragX > w * 0.25f || vx > 900f)
        when {
            next -> animateTo(-w, 0f) { advance(1) }
            prev -> animateTo(w, 0f) { advance(-1) }
            else -> animateTo(0f, 0f) { }
        }
    }

    private fun settleVertical(vy: Float) {
        val h = height.toFloat()
        if (abs(dragY) > h * 0.15f || abs(vy) > 1500f) {
            val to = if (dragY > 0f) h else -h
            animateTo(0f, to) {
                host?.onDismiss()
                dragY = 0f
                layoutSlots()
                host?.onDragProgress(0f)
            }
        } else {
            animateTo(0f, 0f) { host?.onDragProgress(0f) }
        }
    }

    private fun animateTo(toX: Float, toY: Float, end: () -> Unit) {
        val fromX = dragX
        val fromY = dragY
        animating = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                val f = it.animatedValue as Float
                dragX = fromX + (toX - fromX) * f
                dragY = fromY + (toY - fromY) * f
                layoutSlots()
                if (fromY != toY) host?.onDragProgress(progress())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animating = false
                    end()
                }
            })
            start()
        }
    }

    /** Moves one page forward (+1) or back (-1) and recycles the page that fell off the end. */
    private fun advance(delta: Int) {
        val n = host?.count() ?: 0
        val newIndex = index + delta
        if (newIndex !in 0 until n) {
            dragX = 0f
            layoutSlots()
            return
        }
        if (delta > 0) {
            val old = slots[0]
            slots[0] = slots[1]
            slots[1] = slots[2]
            slots[2] = old
        } else {
            val old = slots[2]
            slots[2] = slots[1]
            slots[1] = slots[0]
            slots[0] = old
        }
        index = newIndex
        dragX = 0f
        dragY = 0f
        if (delta > 0) {
            slots[0].resetState()
            bindSlot(2)
        } else {
            slots[2].resetState()
            bindSlot(0)
        }
        layoutSlots()
        host?.onSettled(index)
    }

    companion object {
        private const val AXIS_NONE = 0
        private const val AXIS_H = 1
        private const val AXIS_V = 2
    }
}
