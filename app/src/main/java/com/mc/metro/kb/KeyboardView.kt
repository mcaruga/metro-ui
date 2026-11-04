package com.mc.metro.kb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.mc.metro.Metro
import com.mc.metro.Prefs
import com.mc.metro.dp
import com.mc.metro.dpf
import com.mc.metro.sp
import kotlin.math.abs

/** Windows Phone style keyboard: flat grey keys, accent highlight, preview above the finger. */
@SuppressLint("ViewConstructor")
class KeyboardView(c: Context, private val ime: MetroIme) : View(c) {
    var rows: List<List<Key>> = emptyList()
        set(v) { field = v; layoutKeys(); requestLayout(); invalidate() }
    var shift = 0
        set(v) { field = v; invalidate() }
    var enterLabel = "↵"
        set(v) { field = v; invalidate() }
    var suggestions: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var extraBottom = 0
        set(v) { field = v; requestLayout() }
    var letterMode = true

    private val stripH = 44.dp
    private val rowH = 54.dp
    private val gap = 4.dpf
    private var sysBottom = 0

    private var bgColor = 0
    private var keyColor = 0
    private var fnColor = 0
    private var textColor = 0
    private var subColor = 0
    private var accent = 0

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val vib = c.getSystemService(Vibrator::class.java)
    private val audio = c.getSystemService(AudioManager::class.java)

    private var active: Key? = null
    private var activeId = -1
    private var downX = 0f
    private var accX = 0f
    private var spaceDrag = false
    private var popKeys: List<String>? = null
    private var popSel = 0
    private val popRect = RectF()
    private var stripDown = -1
    private val stripRects = ArrayList<RectF>()

    private val longRun = Runnable { onLong() }
    private val repeatRun = object : Runnable {
        override fun run() {
            val k = active ?: return
            if (k.code == K.DEL) {
                ime.onKey(k)
                postDelayed(this, 55)
            }
        }
    }

    init {
        applyTheme()
    }

    fun applyTheme() {
        val light = Prefs.light
        bgColor = if (light) 0xFFD6D6D6.toInt() else 0xFF000000.toInt()
        keyColor = if (light) 0xFFFFFFFF.toInt() else 0xFF333333.toInt()
        fnColor = if (light) 0xFFBDBDBD.toInt() else 0xFF1E1E1E.toInt()
        textColor = if (light) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        subColor = if (light) 0xFF6A6A6A.toInt() else 0xFF8A8A8A.toInt()
        accent = Prefs.accent
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val b = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        if (b != sysBottom) {
            sysBottom = b
            requestLayout()
        }
        return insets
    }

    override fun onMeasure(ws: Int, hs: Int) {
        setMeasuredDimension(MeasureSpec.getSize(ws), stripH + rows.size * rowH + sysBottom + extraBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutKeys()

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f) return
        var top = stripH.toFloat()
        for (row in rows) {
            val total = row.sumOf { it.w.toDouble() }.toFloat()
            val unit = w / total
            var x = 0f
            for (k in row) {
                k.rect.set(x + gap / 2, top + gap / 2, x + k.w * unit - gap / 2, top + rowH - gap / 2)
                x += k.w * unit
            }
            top += rowH
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in rows) for (k in row) {
            if (k.code == K.SPACER) continue
            if (x >= k.rect.left - gap / 2 && x < k.rect.right + gap / 2 && y >= k.rect.top - gap / 2 && y < k.rect.bottom + gap / 2) return k
        }
        // Edge spacers belong to the nearest key of their row.
        for (row in rows) {
            val r = row.firstOrNull { it.code != K.SPACER } ?: continue
            if (y >= r.rect.top - gap / 2 && y < r.rect.bottom + gap / 2) {
                return row.filter { it.code != K.SPACER }.minByOrNull { abs(it.rect.centerX() - x) }
            }
        }
        return null
    }

    private fun stripAt(x: Float): Int = stripRects.indexOfFirst { x >= it.left && x < it.right }

    private fun display(k: Key): String = when (k.code) {
        K.SHIFT -> if (shift == 2) "⇪" else "⇧"
        K.ENTER -> enterLabel
        K.SPACE -> if (k.w >= 4f) "italiano" else k.label
        else -> if (shift > 0 && letterMode && k.code > 0 && k.label.length == 1 && k.label[0].isLetter()) k.label.uppercase() else k.label
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(cv: Canvas) {
        cv.drawColor(bgColor)
        drawStrip(cv)
        for (row in rows) for (k in row) {
            if (k.code == K.SPACER) continue
            val pressed = k === active && popKeys == null && !(k.code == K.SPACE && spaceDrag)
            val lock = k.code == K.SHIFT && shift == 2
            p.color = when {
                pressed -> accent
                lock -> textColor
                k.fn -> fnColor
                else -> keyColor
            }
            cv.drawRect(k.rect, p)
            val label = display(k)
            tp.color = when {
                pressed -> 0xFFFFFFFF.toInt()
                lock -> bgColor
                k.code == K.SHIFT && shift == 1 -> accent
                k.code == K.SPACE -> subColor
                else -> textColor
            }
            tp.typeface = if (k.fn || k.code == K.SPACE) Metro.regular else Metro.light
            tp.textSize = when {
                k.code == K.SPACE -> 13.sp
                k.code == K.SHIFT || k.code == K.DEL -> 22.sp
                k.fn || label.length > 2 -> 15.sp
                k.code == 0 -> 22.sp
                else -> 24.sp
            }
            cv.drawText(label, k.rect.centerX(), k.rect.centerY() - (tp.ascent() + tp.descent()) / 2, tp)
        }
        val a = active
        if (a != null && popKeys == null && !a.fn && a.code != K.SPACE && a.code != K.SPACER && a.code != K.DEL) {
            val r = RectF(a.rect.left - 4.dpf, a.rect.top - rowH - 2.dpf, a.rect.right + 4.dpf, a.rect.top - 4.dpf)
            if (r.left < 0) r.offset(-r.left, 0f)
            if (r.right > width) r.offset(width - r.right, 0f)
            if (r.top < 0) r.offset(0f, -r.top)
            p.color = accent
            cv.drawRect(r, p)
            tp.color = 0xFFFFFFFF.toInt()
            tp.typeface = Metro.light
            tp.textSize = 30.sp
            cv.drawText(display(a), r.centerX(), r.centerY() - (tp.ascent() + tp.descent()) / 2, tp)
        }
        popKeys?.let { keys ->
            val cw = popRect.width() / keys.size
            for (i in keys.indices) {
                val r = RectF(popRect.left + i * cw, popRect.top, popRect.left + (i + 1) * cw, popRect.bottom)
                p.color = if (i == popSel) accent else textColor
                cv.drawRect(r, p)
                tp.color = if (i == popSel) 0xFFFFFFFF.toInt() else bgColor
                tp.typeface = Metro.light
                tp.textSize = 24.sp
                cv.drawText(keys[i], r.centerX(), r.centerY() - (tp.ascent() + tp.descent()) / 2, tp)
            }
        }
    }

    private fun drawStrip(cv: Canvas) {
        stripRects.clear()
        if (suggestions.isEmpty()) return
        tp.textSize = 18.sp
        tp.typeface = Metro.regular
        val slot = width / 3f
        for ((i, s) in suggestions.take(3).withIndex()) {
            val r = RectF(i * slot, 0f, (i + 1) * slot, stripH.toFloat())
            stripRects += r
            if (i == stripDown) {
                p.color = accent
                cv.drawRect(r, p)
            }
            tp.color = if (i == stripDown) 0xFFFFFFFF.toInt() else textColor
            val t = TextUtils.ellipsize(s, tp, slot - 12.dpf, TextUtils.TruncateAt.END).toString()
            cv.drawText(t, r.centerX(), r.centerY() - (tp.ascent() + tp.descent()) / 2, tp)
        }
    }

    // ---- touch -------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (active != null && e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) release(true)
                val x = e.getX(i)
                val y = e.getY(i)
                if (y < stripH) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        stripDown = stripAt(x)
                        invalidate()
                    }
                    return true
                }
                val k = keyAt(x, y) ?: return true
                active = k
                activeId = e.getPointerId(i)
                downX = x
                accX = 0f
                spaceDrag = false
                feedback(k)
                if (k.code == K.DEL) {
                    ime.onKey(k)
                    postDelayed(repeatRun, 420)
                } else {
                    postDelayed(longRun, 380)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (stripDown >= 0) return true
                val idx = e.findPointerIndex(activeId)
                if (idx < 0) return true
                val x = e.getX(idx)
                val y = e.getY(idx)
                val k = active ?: return true
                when {
                    popKeys != null -> {
                        val n = popKeys!!.size
                        popSel = (((x - popRect.left) / popRect.width()) * n).toInt().coerceIn(0, n - 1)
                        invalidate()
                    }
                    k.code == K.SPACE -> {
                        if (!spaceDrag && abs(x - downX) > 16.dp) {
                            spaceDrag = true
                            removeCallbacks(longRun)
                            invalidate()
                        }
                        if (spaceDrag) {
                            val step = 11.dpf
                            var d = x - downX - accX
                            while (abs(d) >= step) {
                                val dir = if (d > 0) 1 else -1
                                ime.moveCursor(dir)
                                accX += dir * step
                                d -= dir * step
                            }
                        }
                    }
                    k.code != K.DEL -> {
                        val nk = keyAt(x, y)
                        if (nk != null && nk !== k && nk.code != K.DEL && y >= stripH) {
                            removeCallbacks(longRun)
                            active = nk
                            postDelayed(longRun, 380)
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (stripDown >= 0 && e.actionMasked == MotionEvent.ACTION_UP) {
                    val s = stripAt(e.x)
                    if (s == stripDown && e.y < stripH * 2) ime.pickSuggestion(s)
                    stripDown = -1
                    invalidate()
                    return true
                }
                if (e.getPointerId(i) == activeId) release(true)
            }
            MotionEvent.ACTION_CANCEL -> {
                stripDown = -1
                release(false)
            }
        }
        return true
    }

    private fun release(commit: Boolean) {
        removeCallbacks(longRun)
        removeCallbacks(repeatRun)
        val k = active
        val pk = popKeys
        active = null
        activeId = -1
        popKeys = null
        if (commit && k != null) {
            when {
                pk != null -> ime.typeText(pk[popSel])
                k.code == K.DEL || k.code == K.SPACER -> {}
                k.code == K.SPACE && spaceDrag -> {}
                else -> ime.onKey(k)
            }
        }
        spaceDrag = false
        invalidate()
    }

    private fun onLong() {
        val k = active ?: return
        when {
            k.code == K.SPACE -> {
                if (!spaceDrag) {
                    active = null
                    invalidate()
                    ime.showPicker()
                }
            }
            k.code == K.SHIFT -> {
                active = null
                ime.capsLock()
            }
            k.pops.isNotEmpty() -> {
                val list = k.pops.codePoints().toArray().map { String(Character.toChars(it)) }
                    .map { if (shift > 0 && letterMode) it.uppercase() else it }
                val cw = k.rect.width().coerceAtLeast(36.dpf)
                val total = cw * list.size
                val left = (k.rect.centerX() - cw / 2).coerceIn(0f, (width - total).coerceAtLeast(0f))
                val top = (k.rect.top - rowH).coerceAtLeast(0f)
                popRect.set(left, top, left + total, top + rowH - gap)
                popKeys = list
                popSel = 0
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
    }

    private fun feedback(k: Key) {
        if (Prefs.kbVibrate) {
            try { vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) } catch (e: Exception) { }
        }
        if (Prefs.kbSound) KbSound.play(
            when (k.code) {
                K.DEL -> KbSound.DEL
                K.SPACE, K.ENTER -> KbSound.SPACE
                else -> KbSound.KEY
            }
        )
    }
}
