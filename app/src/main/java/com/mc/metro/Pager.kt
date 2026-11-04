package com.mc.metro

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs

/** Horizontal two-page container: start screen ↔ app list. */
class Pager(c: Context) : ViewGroup(c) {
    var page = 0
        private set
    var locked = false
    var onPage: ((Int) -> Unit)? = null

    private val scroller = OverScroller(c, DecelerateInterpolator(2.2f))
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val minFling = ViewConfiguration.get(c).scaledMinimumFlingVelocity
    private var vt: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (scroller.isFinished && !dragging) scrollTo(page * w, 0)
    }

    private fun track(e: MotionEvent) {
        val v = vt ?: VelocityTracker.obtain().also { vt = it }
        v.addMovement(e)
    }

    private fun recycle() {
        vt?.recycle()
        vt = null
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (locked) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recycle()
                downX = e.x
                downY = e.y
                lastX = e.x
                dragging = !scroller.isFinished
                if (dragging) scroller.abortAnimation()
                track(e)
            }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                    dragging = true
                    lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                recycle()
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked) return false
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) {
                    dragging = true
                    lastX = e.x
                }
                if (dragging) {
                    val nx = (scrollX + (lastX - e.x)).toInt().coerceIn(0, (childCount - 1) * width)
                    scrollTo(nx, 0)
                    lastX = e.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle()
                dragging = false
                recycle()
            }
        }
        return true
    }

    private fun settle() {
        val v = vt?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
        val target = if (abs(v) > minFling * 4) {
            if (v < 0) page + 1 else page - 1
        } else {
            (scrollX + width / 2) / width.coerceAtLeast(1)
        }
        goTo(target.coerceIn(0, childCount - 1), true)
    }

    fun goTo(p: Int, anim: Boolean = true) {
        val changed = p != page
        page = p
        if (width == 0) return
        val dx = p * width - scrollX
        if (anim && dx != 0) {
            scroller.startScroll(scrollX, 0, dx, 0, 380)
            postInvalidateOnAnimation()
        } else {
            scroller.abortAnimation()
            scrollTo(p * width, 0)
        }
        if (changed) onPage?.invoke(p)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }
}
