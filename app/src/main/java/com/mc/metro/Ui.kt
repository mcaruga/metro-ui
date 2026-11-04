package com.mc.metro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

fun Context.label(text: CharSequence, size: Float, color: Int = Metro.fg, tf: android.graphics.Typeface = Metro.light): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = tf
        includeFontPadding = true
        ellipsize = TextUtils.TruncateAt.END
    }

/** Simple line glyphs used by the round buttons and the navigation bar. */
object Glyphs {
    const val ARROW_RIGHT = 0
    const val ARROW_LEFT = 1
    const val SEARCH = 2
    const val START = 3
    const val CHECK = 4

    fun draw(cv: Canvas, kind: Int, cx: Float, cy: Float, s: Float, p: Paint) {
        val h = s / 2
        when (kind) {
            ARROW_RIGHT, ARROW_LEFT -> {
                val d = if (kind == ARROW_RIGHT) 1f else -1f
                cv.drawLine(cx - h * d, cy, cx + h * d, cy, p)
                cv.drawLine(cx + h * d, cy, cx + (h - s * 0.4f) * d, cy - s * 0.4f, p)
                cv.drawLine(cx + h * d, cy, cx + (h - s * 0.4f) * d, cy + s * 0.4f, p)
            }
            SEARCH -> {
                val r = s * 0.32f
                cv.drawCircle(cx - s * 0.1f, cy - s * 0.1f, r, p)
                cv.drawLine(cx + r * 0.6f, cy + r * 0.6f, cx + h, cy + h, p)
            }
            START -> Logos.draw(cv, Prefs.startLogo, cx, cy, s, p)
            CHECK -> {
                cv.drawLine(cx - h, cy, cx - h * 0.2f, cy + h * 0.7f, p)
                cv.drawLine(cx - h * 0.2f, cy + h * 0.7f, cx + h, cy - h * 0.6f, p)
            }
        }
    }
}

/** Start button logos: the Windows Phone squares and simple filled shapes. */
object Logos {
    val NAMES = arrayOf(
        "finestra", "cerchio", "quadrato", "rombo", "triangolo", "esagono", "stella", "cuore",
        "casa", "fulmine", "goccia", "luna", "fiore", "più", "anello", "griglia",
    )
    private val path = Path()
    private val cut = Path()

    private fun poly(cx: Float, cy: Float, h: Float, vararg pts: Float) {
        path.moveTo(cx + pts[0] * h, cy + pts[1] * h)
        var i = 2
        while (i < pts.size) { path.lineTo(cx + pts[i] * h, cy + pts[i + 1] * h); i += 2 }
        path.close()
    }

    private fun star(cx: Float, cy: Float, r: Float, inner: Float, n: Int) {
        for (k in 0 until n * 2) {
            val a = -Math.PI / 2 + Math.PI / n * k
            val rr = if (k % 2 == 0) r else inner
            val x = cx + rr * kotlin.math.cos(a).toFloat()
            val y = cy + rr * kotlin.math.sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    /** Filled logo [id] in an [s]-sized box centred on (cx, cy); keeps [p]'s style. */
    fun draw(cv: Canvas, id: Int, cx: Float, cy: Float, s: Float, p: Paint) {
        val old = p.style
        p.style = Paint.Style.FILL
        val h = s / 2
        path.reset()
        cut.reset()
        when (id) {
            1 -> path.addCircle(cx, cy, h * 0.95f, Path.Direction.CW)
            2 -> path.addRect(cx - h * 0.85f, cy - h * 0.85f, cx + h * 0.85f, cy + h * 0.85f, Path.Direction.CW)
            3 -> poly(cx, cy, h, 0f, -1.05f, 1.05f, 0f, 0f, 1.05f, -1.05f, 0f)
            4 -> poly(cx, cy, h, 0f, -1f, 1.05f, 0.85f, -1.05f, 0.85f)
            5 -> star(cx, cy, h, h, 3)
            6 -> star(cx, cy + h * 0.08f, h * 1.05f, h * 0.44f, 5)
            7 -> {
                path.moveTo(cx, cy + h * 0.9f)
                path.cubicTo(cx - h * 1.5f, cy - h * 0.1f, cx - h * 0.6f, cy - h * 1.1f, cx, cy - h * 0.4f)
                path.cubicTo(cx + h * 0.6f, cy - h * 1.1f, cx + h * 1.5f, cy - h * 0.1f, cx, cy + h * 0.9f)
                path.close()
            }
            8 -> {
                poly(cx, cy, h, 0f, -1f, 1f, -0.05f, 0.72f, -0.05f, 0.72f, 0.95f, -0.72f, 0.95f, -0.72f, -0.05f, -1f, -0.05f)
                cut.addRect(cx - h * 0.2f, cy + h * 0.35f, cx + h * 0.2f, cy + h * 0.95f, Path.Direction.CW)
                path.op(cut, Path.Op.DIFFERENCE)
            }
            9 -> poly(cx, cy, h, 0.3f, -1f, -0.6f, 0.12f, -0.05f, 0.12f, -0.3f, 1f, 0.6f, -0.16f, 0.05f, -0.16f)
            10 -> {
                poly(cx, cy, h, 0f, -1f, 0.6f, 0.05f, -0.6f, 0.05f)
                cut.addCircle(cx, cy + h * 0.3f, h * 0.68f, Path.Direction.CW)
                path.op(cut, Path.Op.UNION)
            }
            11 -> {
                path.addCircle(cx, cy, h * 0.95f, Path.Direction.CW)
                cut.addCircle(cx + h * 0.5f, cy - h * 0.35f, h * 0.78f, Path.Direction.CW)
                path.op(cut, Path.Op.DIFFERENCE)
            }
            12 -> {
                val r = h * 0.46f
                path.addCircle(cx - r, cy, r, Path.Direction.CW)
                path.addCircle(cx + r, cy, r, Path.Direction.CW)
                path.addCircle(cx, cy - r, r, Path.Direction.CW)
                path.addCircle(cx, cy + r, r, Path.Direction.CW)
            }
            13 -> {
                val t = h * 0.3f
                path.addRect(cx - t, cy - h, cx + t, cy + h, Path.Direction.CW)
                cut.addRect(cx - h, cy - t, cx + h, cy + t, Path.Direction.CW)
                path.op(cut, Path.Op.UNION)
            }
            14 -> {
                path.addCircle(cx, cy, h * 0.95f, Path.Direction.CW)
                cut.addCircle(cx, cy, h * 0.52f, Path.Direction.CW)
                path.op(cut, Path.Op.DIFFERENCE)
            }
            15 -> {
                val g = s * 0.08f
                val q = (s - g * 2) / 3
                for (i in 0..2) for (j in 0..2) {
                    val x = cx - h + i * (q + g)
                    val y = cy - h + j * (q + g)
                    path.addRect(x, y, x + q, y + q, Path.Direction.CW)
                }
            }
            else -> {
                val g = s * 0.08f
                val q = (s - g) / 2
                path.addRect(cx - h, cy - h, cx - h + q, cy - h + q, Path.Direction.CW)
                path.addRect(cx + g / 2, cy - h, cx + h, cy - h + q, Path.Direction.CW)
                path.addRect(cx - h, cy + g / 2, cx - h + q, cy + h, Path.Direction.CW)
                path.addRect(cx + g / 2, cy + g / 2, cx + h, cy + h, Path.Direction.CW)
            }
        }
        cv.drawPath(path, p)
        p.style = old
    }
}

/** Metro round outline button (the start screen arrow, app list search). */
class CircleButton(c: Context, private val glyph: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dpf
        strokeCap = Paint.Cap.SQUARE
    }
    private var down = false

    init {
        isClickable = true
        contentDescription = when (glyph) { Glyphs.SEARCH -> "cerca"; Glyphs.ARROW_LEFT -> "indietro"; else -> "tutte le app" }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(48.dp, 48.dp)

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4.dpf
        if (down) {
            p.style = Paint.Style.FILL
            p.color = Metro.fg
            cv.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE
            p.color = Metro.bg
        } else {
            p.color = Metro.fg
            cv.drawCircle(cx, cy, r, p)
        }
        Glyphs.draw(cv, glyph, cx, cy, r * 0.9f, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return super.onTouchEvent(e)
    }
}

/** Windows Phone 8 style toggle: outlined track, accent fill, solid thumb. */
class MetroToggle(c: Context) : View(c) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var pos = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener { set(!checked, true) }
    }

    fun set(v: Boolean, user: Boolean = false) {
        checked = v
        if (user) {
            val from = pos
            val to = if (v) 1f else 0f
            android.animation.ValueAnimator.ofFloat(from, to).apply {
                duration = 140
                interpolator = DecelerateInterpolator()
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
            onChange?.invoke(v)
        } else {
            pos = if (v) 1f else 0f
            invalidate()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(64.dp, 28.dp)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = 2.dpf
        val thumb = 12.dpf
        val inset = 5.dpf
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = Metro.fg
        cv.drawRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, p)
        val tx = sw / 2 + pos * (w - thumb - sw)
        p.style = Paint.Style.FILL
        if (pos > 0f) {
            p.color = Prefs.accent
            cv.drawRect(inset, inset, max(inset, tx), h - inset, p)
        }
        p.color = Metro.fg
        cv.drawRect(tx, 0f, tx + thumb, h, p)
    }
}

/** Saturation/value square plus hue strip, for a custom accent colour. */
class HsvView(c: Context, initial: Int, private val onChange: (Int) -> Unit) : View(c) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sv = RectF()
    private val hue = RectF()
    private var dragHue = false

    val color: Int get() = Color.HSVToColor(hsv)

    fun set(c: Int) {
        Color.colorToHSV(c, hsv)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), 220.dp)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val hueW = 36.dpf
        sv.set(0f, 0f, w - hueW - 16.dpf, h.toFloat())
        hue.set(w - hueW, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(cv: Canvas) {
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val sat = LinearGradient(sv.left, 0f, sv.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        val value = LinearGradient(0f, sv.top, 0f, sv.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        p.shader = ComposeShader(sat, value, PorterDuff.Mode.MULTIPLY)
        cv.drawRect(sv, p)
        val stops = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        p.shader = LinearGradient(0f, hue.top, 0f, hue.bottom, stops, null, Shader.TileMode.CLAMP)
        cv.drawRect(hue, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.dpf
        p.color = Color.WHITE
        val x = sv.left + hsv[1] * sv.width()
        val y = sv.top + (1 - hsv[2]) * sv.height()
        cv.drawCircle(x, y, 9.dpf, p)
        p.color = Color.BLACK
        p.strokeWidth = 1.dpf
        cv.drawCircle(x, y, 11.dpf, p)
        val hy = hue.top + hsv[0] / 360f * hue.height()
        p.color = Color.WHITE
        p.strokeWidth = 4.dpf
        cv.drawRect(hue.left - 3.dpf, hy - 3.dpf, hue.right + 3.dpf, hy + 3.dpf, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            dragHue = e.x >= hue.left - 8.dpf
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (dragHue) {
            hsv[0] = ((e.y - hue.top) / hue.height()).coerceIn(0f, 1f) * 359.9f
        } else {
            hsv[1] = ((e.x - sv.left) / sv.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((e.y - sv.top) / sv.height()).coerceIn(0f, 1f)
        }
        invalidate()
        onChange(color)
        return true
    }
}
