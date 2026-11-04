package com.mc.metro

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class Face(
    val type: Int,
    val big: String? = null,
    val title: String? = null,
    val lines: List<String> = emptyList(),
    val image: Bitmap? = null,
    val count: Int = 0,
    val corner: String? = null,
) {
    companion object {
        const val ICON = 0
        const val TEXT = 1
        const val IMAGE = 2
    }
}

@SuppressLint("ViewConstructor")
class TileView(c: Context, val tile: Tile, private val grid: TileGrid) : View(c) {
    var app: AppEntry? = null
        private set
    private var faces: List<Face> = emptyList()
    private var face = 0
    private var photo: Bitmap? = null
    private var photoPrev: Bitmap? = null
    private var photoStart = 0L
    private var pan = floatArrayOf(0f, 0f)
    private var photoLoading = false
    /** The photo on screen, opened by a tap on the photo tile. */
    var photoUri: android.net.Uri? = null
        private set
    private var fx: WeatherFx? = null
    private val photoTimer = Runnable { nextPhoto() }
    var editSelected = false
    var baseScale = 1f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Flat white glyphs drawn in the chosen tile text colour. */
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        if (Prefs.tileFg != android.graphics.Color.WHITE) {
            colorFilter = android.graphics.PorterDuffColorFilter(Prefs.tileFg, android.graphics.PorterDuff.Mode.SRC_IN)
        }
    }
    private val fg = Prefs.tileFg
    /** Text over photos stays white: it sits on a dark gradient or shadow. */
    private val overPhoto get() = tile.kind == Tile.WEATHER && Prefs.cityPhoto && Live.cityPhoto != null
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pressing = false
    private var flipping = false
    var lastRawX = 0f
        private set
    var lastRawY = 0f
        private set

    private val label: String
        get() = if (tile.kind == Tile.APP) app?.label.orEmpty() else Tile.liveLabel(tile.kind)
    private val small get() = tile.size == TSize.SMALL

    init {
        cameraDistance = 9000 * DENSITY
        outlineProvider = null
        isHapticFeedbackEnabled = true
        contentDescription = label
    }

    fun rebuild() {
        app = if (tile.kind == Tile.APP) Apps.find(tile.app) else TileStore.liveApp(context, tile.kind)
        faces = buildFaces()
        if (face >= faces.size) face = 0
        contentDescription = label
        invalidate()
    }

    fun canFlip() = !flipping && tile.kind != Tile.PHOTOS && !zoneBoard && faces.size > 1

    /** Wide/large clock with extra time zones: one board, no flipping. */
    private val zoneBoard get() = tile.kind == Tile.CLOCK && (tile.size == TSize.WIDE || tile.size == TSize.LARGE) && Prefs.clockZones.isNotEmpty()

    // ---- faces -------------------------------------------------------------------------------

    private fun buildFaces(): List<Face> = when (tile.kind) {
        Tile.CLOCK -> clockFaces()
        Tile.CALENDAR -> calendarFaces()
        Tile.WEATHER -> weatherFaces()
        Tile.PHOTOS -> photoFaces()
        Tile.EMPTY -> emptyList()
        else -> appFaces()
    }

    private fun appFaces(): List<Face> {
        val pkg = app?.pkg ?: return listOf(Face(Face.ICON))
        val count = Notifs.counts[pkg] ?: 0
        val info = Notifs.last[pkg]
        val out = arrayListOf(Face(Face.ICON, count = count))
        if (info != null && count > 0 && !small) {
            out += Face(Face.TEXT, title = info.title.ifEmpty { label }, lines = listOf(info.text), count = count)
        }
        // Music/video apps: the cover of what they are (or were last) playing.
        val cover = if (Prefs.covers && !small) Media.now[pkg] else null
        if (cover != null) out.add(0, Face(Face.IMAGE, image = cover.art, title = cover.title, lines = listOfNotNull(cover.artist)))
        return out
    }

    private fun clockFaces(): List<Face> {
        val now = Date()
        val time = DateFormat.getTimeFormat(context).format(now)
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("d MMMM", Locale.getDefault()).format(now)
        if (small) return listOf(Face(Face.TEXT, big = time))
        val out = arrayListOf(Face(Face.TEXT, big = time, lines = listOf(day, date)))
        val alarm = try { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock } catch (e: Exception) { null }
        if (alarm != null) {
            val at = Date(alarm.triggerTime)
            val wd = SimpleDateFormat("EEEE", Locale.getDefault()).format(at)
            out += Face(Face.TEXT, big = DateFormat.getTimeFormat(context).format(at), lines = listOf("sveglia", wd))
        }
        // Medium tile: each extra zone is a face of its own.
        for (z in Prefs.clockZones) {
            out += Face(Face.TEXT, big = Zones.time(context, z), lines = listOf(Zones.name(z), Zones.relative(z)))
        }
        return out
    }

    private fun calendarFaces(): List<Face> {
        val cal = Calendar.getInstance()
        val dayNum = cal.get(Calendar.DAY_OF_MONTH).toString()
        val now = Date()
        val wd = SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
        if (small) return listOf(Face(Face.TEXT, big = dayNum))
        val out = ArrayList<Face>()
        if (!Live.has(context, android.Manifest.permission.READ_CALENDAR)) {
            out += Face(Face.TEXT, big = dayNum, lines = listOf(wd, "tocca per mostrare gli appuntamenti"))
            return out
        }
        for (e in Live.events.take(2)) {
            out += Face(Face.TEXT, title = e.title, lines = listOfNotNull(whenText(e), e.location), corner = "$dayNum ${wd.take(3)}")
        }
        out += Face(Face.TEXT, big = dayNum, lines = listOf(wd, SimpleDateFormat("MMMM", Locale.getDefault()).format(now)))
        return out
    }

    private fun whenText(e: Live.Event): String {
        val tf = DateFormat.getTimeFormat(context)
        if (e.allDay) return "tutto il giorno"
        val b = Date(e.begin)
        val prefix = when {
            DateUtils.isToday(e.begin) -> ""
            DateUtils.isToday(e.begin - DateUtils.DAY_IN_MILLIS) -> "domani "
            else -> SimpleDateFormat("EEE ", Locale.getDefault()).format(b)
        }
        return prefix + tf.format(b) + " – " + tf.format(Date(e.end))
    }

    private fun weatherFaces(): List<Face> {
        val w = Live.weather
        if (w == null) {
            val msg = "aggiornamento…"
            return listOf(if (small) Face(Face.TEXT, big = "--°") else Face(Face.TEXT, big = "--°", lines = listOf(msg)))
        }
        val glyph = Live.wmoGlyph(w.code)
        if (small) return listOf(Face(Face.TEXT, big = "${w.temp}°"))
        return listOf(
            Face(Face.TEXT, big = "${w.temp}° $glyph", lines = listOfNotNull(Live.wmo(w.code), w.city)),
            Face(
                Face.TEXT, title = w.city ?: "meteo",
                lines = listOf("oggi  ${w.max}° / ${w.min}°", "domani  ${w.tMax}° / ${w.tMin}°  ${Live.wmo(w.tCode)}"),
            ),
        )
    }

    private fun photoFaces(): List<Face> {
        val b = photo
        if (b != null) return listOf(Face(Face.IMAGE, image = b))
        if (!Live.has(context, Live.photoPerm)) {
            return listOf(if (small) Face(Face.ICON) else Face(Face.TEXT, title = "foto", lines = listOf("tocca per consentire l'accesso")))
        }
        if (Live.photos.isNotEmpty() && !photoLoading) post { nextPhoto() }
        return listOf(Face(Face.ICON))
    }

    // ---- flip --------------------------------------------------------------------------------

    fun flip() {
        if (grid.editing || !isAttachedToWindow || flipping || pressing) return
        if (tile.kind == Tile.PHOTOS) {
            nextPhoto()
            return
        }
        if (faces.size < 2) return
        turn { face = (face + 1) % faces.size }
    }

    // ---- photo tile: random photo on a timer, cross-fade + slow zoom and pan ------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (tile.kind == Tile.PHOTOS) schedulePhoto(if (photo == null) 600 else nextInterval())
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(photoTimer)
        flipAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun nextInterval(): Long =
        if (Prefs.photoInterval > 0) Prefs.photoInterval * 1000L else 5000L + kotlin.random.Random.nextLong(25_000L)

    private fun schedulePhoto(delay: Long) {
        removeCallbacks(photoTimer)
        postDelayed(photoTimer, delay)
    }

    private fun nextPhoto() {
        if (!isAttachedToWindow || photoLoading) return
        if (grid.editing || (!Live.fx && photo != null) || width == 0) {
            schedulePhoto(3000)
            return
        }
        photoLoading = true
        Live.loadPhoto(context, (width * 1.3f).toInt(), (height * 1.3f).toInt()) { u, b ->
            photoLoading = false
            if (b != null) {
                photoUri = u
                photoPrev = photo
                photo = b
                photoStart = android.os.SystemClock.uptimeMillis()
                val a = kotlin.random.Random.nextFloat() * 6.283f
                pan = floatArrayOf(cos(a), sin(a))
                faces = buildFaces()
                face = 0
                invalidate()
            }
            schedulePhoto(nextInterval())
        }
    }

    private fun drawCover(cv: Canvas, b: Bitmap, zoom: Float, px: Float, py: Float, alpha: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sc = max(w / b.width, h / b.height) * zoom
        val dw = b.width * sc
        val dh = b.height * sc
        val x = (w - dw) / 2 + px * (dw - w) / 2
        val y = (h - dh) / 2 + py * (dh - h) / 2
        bp.alpha = alpha
        cv.drawBitmap(b, null, RectF(x, y, x + dw, y + dh), bp)
        bp.alpha = 255
    }

    private fun drawPhoto(cv: Canvas, f: Face) {
        val b = photo ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val anim = Prefs.animations
        val fade = if (anim) ((now - photoStart) / 1200f).coerceIn(0f, 1f) else 1f
        val k = if (anim) ((now - photoStart) / 20_000f).coerceIn(0f, 1f) else 0f
        val prev = photoPrev
        if (prev != null && fade < 1f) drawCover(cv, prev, 1.14f, 0f, 0f, 255)
        // Ken Burns: zoom from 1.0 to 1.14 while panning across the photo.
        drawCover(cv, b, 1f + 0.14f * k, pan[0] * (k * 2 - 1), pan[1] * (k * 2 - 1), (fade * 255).toInt())
        if (!small) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.shader = LinearGradient(0f, h * 0.6f, 0f, h, 0, 0x88000000.toInt(), Shader.TileMode.CLAMP)
            cv.drawRect(0f, h * 0.6f, w, h, p)
            p.shader = null
            footer(cv, f, false, true)
        }
        if (anim && Live.fx && isShown && (fade < 1f || k < 1f)) postInvalidateDelayed(33)
    }

    /**
     * Flip drawn on the canvas with its own animator, so the tilt and the launch turnstile
     * (which animate the view itself) can never interrupt it and leave the tile half-turned.
     */
    private fun turn(swap: () -> Unit) {
        if (!Prefs.animations || width == 0) {
            swap(); invalidate(); return
        }
        flipping = true
        var swapped = false
        flipAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                if (v >= 0.5f && !swapped) { swapped = true; swap() }
                flipDeg = if (v < 0.5f) v * 180f else (v - 1f) * 180f
                invalidate()
            }
            // Runs after a cancel too: the tile always ends flat, on the new face.
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!swapped) { swapped = true; swap() }
                    flipDeg = 0f
                    flipping = false
                    flipAnim = null
                    invalidate()
                }
            })
            start()
        }
    }

    private var flipDeg = 0f
    private var flipAnim: android.animation.ValueAnimator? = null
    private val cam = android.graphics.Camera()
    private val camM = android.graphics.Matrix()

    private fun applyFlip(cv: Canvas) {
        if (flipDeg == 0f) return
        cam.save()
        cam.setLocation(0f, 0f, -9000f / 160f)
        cam.rotateX(flipDeg)
        cam.getMatrix(camM)
        cam.restore()
        camM.preTranslate(-width / 2f, -height / 2f)
        camM.postTranslate(width / 2f, height / 2f)
        cv.concat(camM)
    }

    /** Straightens a tile left tilted or turned by an interrupted animation. */
    fun settle() {
        if (pressing || grid.editing) return
        if (rotationX != 0f || rotationY != 0f || scaleX != baseScale || alpha != 1f) resetTransforms()
        if (flipping && flipAnim == null) flipping = false
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = null
        if (tile.kind == Tile.WEATHER) tp.setShadowLayer(3.dpf, 0f, 1.dpf, 0x99000000.toInt()) else tp.clearShadowLayer()
        // The grid doesn't clip children (tilt/flip draw outside), so clip the content here.
        cv.clipRect(0f, 0f, w, h)
        applyFlip(cv)
        val wall = Wall.bitmap
        if (tile.kind == Tile.EMPTY) {
            drawEmpty(cv, wall)
            return
        }
        if (wall != null) {
            // Same image behind every tile, fixed to the screen and shifted by the parallax.
            cv.drawBitmap(wall, -translationX, -(translationY + grid.viewTop + grid.bgShift), bp)
            p.color = (Prefs.bgDim * 255 / 100) shl 24
            cv.drawRect(0f, 0f, w, h, p)
        } else {
            p.color = if (tile.color != 0) tile.color else Prefs.accent
            cv.drawRect(0f, 0f, w, h, p)
        }
        if (tile.kind == Tile.WEATHER) drawWeatherBack(cv)
        if (zoneBoard) {
            drawZoneBoard(cv)
            if (editSelected) drawEditButtons(cv)
            return
        }
        val f = faces.getOrNull(face) ?: Face(Face.ICON)
        when (f.type) {
            Face.IMAGE -> if (tile.kind == Tile.PHOTOS) drawPhoto(cv, f) else drawImage(cv, f)
            Face.TEXT -> drawText(cv, f)
            else -> drawIcon(cv, f)
        }
        if (editSelected) drawEditButtons(cv)
    }

    private fun iconBmp(size: Int): Bitmap? = app?.let { Apps.icon(it, size, true) }

    // ---- empty tile: a window onto the background ---------------------------------------------

    private fun drawEmpty(cv: Canvas, wall: Bitmap?) {
        if (wall != null) cv.drawBitmap(wall, -translationX, -(translationY + grid.viewTop + grid.bgShift), bp)
        if (grid.editing) {
            // Invisible otherwise: outline it so it can be grabbed, moved and resized.
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.dpf
            p.color = Metro.fg
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8.dpf, 6.dpf), 0f)
            cv.drawRect(1.dpf, 1.dpf, width - 1.dpf, height - 1.dpf, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL
        }
        if (editSelected) drawEditButtons(cv)
    }

    // ---- clock with time zones ---------------------------------------------------------------

    private fun drawZoneBoard(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10.dpf
        val ts = Prefs.textScale
        val now = Date()
        val zones = Prefs.clockZones
        tp.color = fg
        val time = DateFormat.getTimeFormat(context).format(now)
        val alarm = nextAlarm()
        if (tile.size == TSize.LARGE) {
            tp.typeface = Metro.light
            tp.textSize = min(h * 0.2f, 76.sp) * ts
            var y = pad - tp.ascent() * 0.92f
            cv.drawText(time, pad - tp.textSize * 0.04f, y, tp)
            y += tp.descent()
            y = infoLines(cv, SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), alarm, pad, y, w - 2 * pad)
            y += 8.dpf
            divider(cv, 0f, y, w, y)
            val shown = zones.take(Zones.MAX)
            if (shown.size <= 2) {
                val rowH = (h - y) / shown.size
                for ((i, z) in shown.withIndex()) {
                    val top = y + i * rowH
                    if (i > 0) divider(cv, 0f, top, w, top)
                    zoneRow(cv, z, pad, top, w - pad, rowH)
                }
            } else {
                // 2 x 2 board: the dark lines frame each city like a small tile.
                val cw = w / 2
                val ch = (h - y) / 2
                divider(cv, cw, y, cw, h)
                divider(cv, 0f, y + ch, w, y + ch)
                for ((i, z) in shown.withIndex()) zoneCell(cv, z, (i % 2) * cw, y + (i / 2) * ch, cw, ch)
            }
        } else {
            // Wide: home time on the left, zones stacked on the right of a dark divider.
            val split = w * 0.5f
            tp.typeface = Metro.light
            tp.textSize = min(h * 0.34f, 64.sp) * ts
            while (tp.measureText(time) > split - 2 * pad && tp.textSize > 12.sp) tp.textSize *= 0.92f
            var y = pad - tp.ascent() * 0.92f
            cv.drawText(time, pad - tp.textSize * 0.04f, y, tp)
            y += tp.descent()
            infoLines(cv, SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now), alarm, pad, y, split - 2 * pad)
            divider(cv, split, 0f, split, h)
            val shown = zones.take(2)
            val rowH = h / shown.size
            for ((i, z) in shown.withIndex()) {
                val top = i * rowH
                if (i > 0) divider(cv, split, top, w, top)
                // Half width is narrow: city above, time below, so neither gets cut.
                zoneCell(cv, z, split, top, w - split, rowH)
            }
        }
    }

    /** Next alarm set in the clock app ("07:30 gio"); Android only exposes the next one. */
    private fun nextAlarm(): String? {
        val a = try { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock } catch (e: Exception) { null } ?: return null
        val at = Date(a.triggerTime)
        val t = DateFormat.getTimeFormat(context).format(at)
        return when {
            DateUtils.isToday(a.triggerTime) -> t
            DateUtils.isToday(a.triggerTime - DateUtils.DAY_IN_MILLIS) -> "$t domani"
            else -> t + " " + SimpleDateFormat("EEE", Locale.getDefault()).format(at)
        }
    }

    /** Date, then the alarm with a small clock glyph, under the big time. Returns the new y. */
    private fun infoLines(cv: Canvas, date: String, alarm: String?, x: Float, y0: Float, avail: Float): Float {
        var y = y0
        tp.color = fg
        tp.typeface = Metro.regular
        tp.textSize = 15.sp * Prefs.textScale
        y += -tp.ascent()
        cv.drawText(TextUtils.ellipsize(date, tp, avail, TextUtils.TruncateAt.END).toString(), x, y, tp)
        y += tp.descent()
        if (alarm != null) {
            tp.typeface = Metro.semibold
            y += -tp.ascent() + 2.dpf
            val r = tp.textSize * 0.38f
            alarmGlyph(cv, x + r, y - tp.textSize * 0.34f, r)
            cv.drawText(TextUtils.ellipsize(alarm, tp, avail - 2 * r - 6.dpf, TextUtils.TruncateAt.END).toString(), x + 2 * r + 6.dpf, y, tp)
            y += tp.descent()
        }
        return y
    }

    private fun alarmGlyph(cv: Canvas, cx: Float, cy: Float, r: Float) {
        p.color = fg
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.22f
        p.strokeCap = Paint.Cap.ROUND
        cv.drawCircle(cx, cy, r, p)
        cv.drawLine(cx, cy, cx, cy - r * 0.6f, p)
        cv.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, p)
        // Bells.
        cv.drawLine(cx - r * 0.95f, cy - r * 0.95f, cx - r * 0.45f, cy - r * 1.3f, p)
        cv.drawLine(cx + r * 0.95f, cy - r * 0.95f, cx + r * 0.45f, cy - r * 1.3f, p)
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
    }

    /** Soft dark line between the parts of the clock board. */
    private fun divider(cv: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        p.color = 0x47000000
        val t = 2.dpf
        if (x0 == x1) cv.drawRect(x0 - t / 2, y0, x0 + t / 2, y1, p) else cv.drawRect(x0, y0 - t / 2, x1, y0 + t / 2, p)
    }

    /** Largest size (capped) at which [s] fits in maxW, scaled by "dimensione fusi orari". */
    private fun fit(s: String, maxW: Float, maxSize: Float): Float {
        tp.textSize = maxSize
        val mw = tp.measureText(s)
        val size = if (mw > maxW) maxSize * maxW / mw else maxSize
        return size * Prefs.zoneScale
    }

    /** Row: city and "oggi · +6 h" on the left, the time as large as the row allows on the right. */
    private fun zoneRow(cv: Canvas, z: String, x0: Float, top: Float, x1: Float, rowH: Float) {
        val ts = Prefs.textScale
        val t = Zones.time(context, z)
        tp.color = fg
        tp.typeface = Metro.light
        tp.textSize = fit(t, (x1 - x0) * 0.6f, min(rowH * 0.56f, 72.sp))
        val tw = tp.measureText(t)
        val cy = top + rowH / 2
        cv.drawText(t, x1 - tw, cy - (tp.ascent() + tp.descent()) / 2, tp)
        val avail = (x1 - tw - 8.dpf - x0).coerceAtLeast(0f)
        tp.typeface = Metro.semibold
        tp.textSize = min(18.sp * ts, rowH * 0.26f) * Prefs.zoneScale.coerceAtLeast(0.85f)
        cv.drawText(TextUtils.ellipsize(Zones.name(z), tp, avail, TextUtils.TruncateAt.END).toString(), x0, cy - 1.dpf, tp)
        tp.typeface = Metro.regular
        tp.textSize *= 0.8f
        tp.alpha = 190
        cv.drawText(TextUtils.ellipsize(Zones.relative(z), tp, avail, TextUtils.TruncateAt.END).toString(), x0, cy - 1.dpf + tp.fontSpacing, tp)
        tp.alpha = 255
    }

    /** Cell of the 2 x 2 board: city on top, big time, "oggi · +6 h" below. */
    private fun zoneCell(cv: Canvas, z: String, x: Float, y: Float, cw: Float, ch: Float) {
        val pad = 10.dpf
        val ts = Prefs.textScale
        val avail = cw - 2 * pad
        tp.color = fg
        tp.typeface = Metro.semibold
        tp.textSize = min(17.sp * ts, ch * 0.17f)
        var yy = y + pad - tp.ascent()
        cv.drawText(TextUtils.ellipsize(Zones.name(z), tp, avail, TextUtils.TruncateAt.END).toString(), x + pad, yy, tp)
        val t = Zones.time(context, z)
        tp.typeface = Metro.light
        tp.textSize = fit(t, avail, min(ch * 0.42f, 60.sp))
        yy += -tp.ascent() * 0.95f + 2.dpf
        cv.drawText(t, x + pad - tp.textSize * 0.03f, yy, tp)
        tp.typeface = Metro.regular
        tp.textSize = min(13.sp * ts, ch * 0.13f)
        tp.alpha = 190
        cv.drawText(TextUtils.ellipsize(Zones.relative(z), tp, avail, TextUtils.TruncateAt.END).toString(), x + pad, y + ch - pad, tp)
        tp.alpha = 255
    }

    // ---- app icon + name ---------------------------------------------------------------------

    /** App name, bigger with the "dimensione nomi" setting; wraps to two lines on larger tiles. */
    private fun nameLayout(avail: Float, onImage: Boolean = false): StaticLayout? {
        val l = label
        if (l.isEmpty() || tile.kind == Tile.CLOCK || avail <= 0f) return null
        if (small && (tile.kind != Tile.APP || !Prefs.smallLabels)) return null
        val np = TextPaint(tp).apply {
            typeface = Metro.regular
            textSize = (if (small) 11.sp else 13.sp) * Prefs.textScale * Prefs.labelScale
            color = if (overPhoto || onImage) android.graphics.Color.WHITE else fg
        }
        if (overPhoto) np.setShadowLayer(3.dpf, 0f, 1.dpf, 0x99000000.toInt()) else np.clearShadowLayer()
        return StaticLayout.Builder.obtain(l, 0, l.length, np, avail.toInt())
            .setMaxLines(if (small) 1 else 2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.92f)
            .build()
    }

    private fun drawName(cv: Canvas, sl: StaticLayout, x: Float) {
        val pad = if (small) 5.dpf else 8.dpf
        cv.save()
        cv.translate(x, height - pad - sl.height + sl.getLineDescent(sl.lineCount - 1) * 0.4f)
        sl.draw(cv)
        cv.restore()
    }

    private fun drawIcon(cv: Canvas, f: Face) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = if (small) 5.dpf else 8.dpf
        val name = nameLayout(w - 2 * pad)
        val nameH = name?.height?.toFloat() ?: 0f
        // Icon centred in the space left above the name.
        val area = if (name != null) h - nameH - pad * 1.5f else h
        var ib = (min(w, h) * (if (small) 0.5f else 0.38f) * Prefs.iconScale).coerceAtMost(min(w, h) * 0.85f)
        if (name != null) ib = min(ib, area * (if (small) 0.78f else 0.7f))
        val ibi = ib.toInt().coerceAtLeast(1)
        val cy = when {
            name != null && small -> area / 2 + 2.dpf
            name != null -> min(h / 2 - 6.dpf, area / 2 + 4.dpf)
            small -> h / 2
            else -> h / 2 - 6.dpf
        }
        val bmp = iconBmp(ibi)
        if (f.count > 0) {
            tp.typeface = Metro.light
            tp.color = fg
            tp.textSize = ibi * 0.85f
            val txt = if (f.count > 99) "99+" else f.count.toString()
            val tw = tp.measureText(txt)
            val total = ibi + 6.dpf + tw
            val x0 = (w - total) / 2
            if (bmp != null) cv.drawBitmap(bmp, x0, cy - ibi / 2f, ip)
            cv.drawText(txt, x0 + ibi + 6.dpf, cy - (tp.ascent() + tp.descent()) / 2, tp)
        } else if (bmp != null) {
            cv.drawBitmap(bmp, (w - ibi) / 2, cy - ibi / 2f, ip)
        } else {
            tp.typeface = Metro.light
            tp.color = fg
            tp.textSize = ibi.toFloat()
            val g = when (tile.kind) { Tile.PHOTOS -> "▣"; Tile.WEATHER -> "☁︎"; Tile.CALENDAR -> "▦"; else -> "◷" }
            cv.drawText(g, (w - tp.measureText(g)) / 2, cy - (tp.ascent() + tp.descent()) / 2, tp)
        }
        if (small) name?.let { drawName(cv, it, pad) } else footer(cv, f, false)
    }

    private fun drawText(cv: Canvas, f: Face) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10.dpf
        val avail = w - 2 * pad
        tp.color = if (overPhoto) android.graphics.Color.WHITE else fg
        if (small) {
            val s = f.big ?: f.title ?: ""
            tp.typeface = Metro.light
            tp.textSize = h * 0.36f * Prefs.textScale
            while (tp.measureText(s) > avail && tp.textSize > 8.sp) tp.textSize *= 0.9f
            cv.drawText(s, (w - tp.measureText(s)) / 2, h / 2 - (tp.ascent() + tp.descent()) / 2, tp)
            return
        }
        var y = pad
        // Keep the bottom free for the app name, which can be large and two lines.
        val reserve = max(26.dpf, (nameLayout(avail)?.height ?: 0) + 12.dpf)
        if (f.big != null) {
            tp.typeface = Metro.light
            tp.textSize = if (tile.size == TSize.LARGE) min(h * 0.18f, 72.sp) * Prefs.textScale else min(h * 0.32f, 64.sp) * Prefs.textScale
            while (tp.measureText(f.big) > avail && tp.textSize > 10.sp) tp.textSize *= 0.92f
            y += -tp.ascent() * 0.92f
            cv.drawText(f.big, pad - tp.textSize * 0.04f, y, tp)
            y += tp.descent()
        }
        if (f.title != null) {
            tp.typeface = Metro.semibold
            tp.textSize = 17.sp * Prefs.textScale
            y += -tp.ascent()
            cv.drawText(TextUtils.ellipsize(f.title, tp, avail, TextUtils.TruncateAt.END).toString(), pad, y, tp)
            y += tp.descent() + 2.dpf
        }
        if (f.lines.isNotEmpty()) {
            tp.typeface = Metro.regular
            tp.textSize = 14.sp * Prefs.textScale
            var left = ((h - reserve - y) / tp.fontSpacing).toInt()
            for (line in f.lines) {
                if (left <= 0 || line.isEmpty()) continue
                val sl = StaticLayout.Builder.obtain(line, 0, line.length, tp, avail.toInt().coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(left)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build()
                cv.save()
                cv.translate(pad, y)
                sl.draw(cv)
                cv.restore()
                y += sl.height
                left -= sl.lineCount
            }
        }
        footer(cv, f, true)
    }

    private fun drawImage(cv: Canvas, f: Face) {
        val w = width.toFloat()
        val h = height.toFloat()
        val b = f.image ?: return
        val scale = max(w / b.width, h / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        cv.drawBitmap(b, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), bp)
        if (!small) {
            p.shader = LinearGradient(0f, h * 0.45f, 0f, h, 0, 0xAA000000.toInt(), Shader.TileMode.CLAMP)
            cv.drawRect(0f, h * 0.45f, w, h, p)
            p.shader = null
            val t = listOfNotNull(f.title, f.lines.firstOrNull()).joinToString(" · ")
            if (t.isNotEmpty()) {
                tp.typeface = Metro.semibold
                tp.textSize = 14.sp * Prefs.textScale
                tp.color = 0xFFFFFFFF.toInt()
                val y = h - 8.dpf - 13.sp * Prefs.textScale - 8.dpf
                cv.drawText(TextUtils.ellipsize(t, tp, w - 20.dpf, TextUtils.TruncateAt.END).toString(), 10.dpf, y, tp)
            }
            footer(cv, f, false, true)
        }
    }

    // ---- weather: city photo + animated sky ---------------------------------------------------

    private fun rnd(i: Int, k: Int): Float = (((i * 73856093) xor (k * 19349663)) and 0xFFFF) / 65535f

    private fun drawWeatherBack(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val photo = if (Prefs.cityPhoto) Live.cityPhoto else null
        if (photo != null) {
            val sc = max(w / photo.width, h / photo.height)
            val dw = photo.width * sc
            val dh = photo.height * sc
            cv.drawBitmap(photo, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), bp)
            if (!Prefs.animations) {
                p.color = 0x59000000
                cv.drawRect(0f, 0f, w, h, p)
            }
        }
        val wx = Live.weather ?: return
        if (!Prefs.animations) return
        val f = fx ?: WeatherFx().also { fx = it }
        f.draw(cv, w, h, wx.code, wx.isDay, photo != null)
        if (Live.fx && isShown) postInvalidateOnAnimation()
    }

    /** Label bottom-left; small icon, count or corner text bottom-right. */
    private fun footer(cv: Canvas, f: Face, withIcon: Boolean, onImage: Boolean = false) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 8.dpf
        var right = w - pad
        if (f.corner != null) {
            tp.typeface = Metro.regular
            tp.textSize = 15.sp * Prefs.textScale
            tp.color = if (overPhoto || onImage) android.graphics.Color.WHITE else fg
            val cw = tp.measureText(f.corner)
            cv.drawText(f.corner, right - cw, h - pad, tp)
            right -= cw + 6.dpf
        } else if (withIcon) {
            val s = (22 * Prefs.iconScale).dp
            val bmp = iconBmp(s)
            if (bmp != null) {
                cv.drawBitmap(bmp, right - s, h - pad - s + 3.dpf, if (overPhoto || onImage) bp else ip)
                right -= s + 6.dpf
            }
            if (f.count > 0) {
                tp.typeface = Metro.regular
                tp.textSize = 15.sp * Prefs.textScale
                val t = f.count.toString()
                val cw = tp.measureText(t)
                cv.drawText(t, right - cw, h - pad, tp)
                right -= cw + 6.dpf
            }
        }
        nameLayout(right - pad, onImage)?.let { drawName(cv, it, pad) }
    }

    private fun drawEditButtons(cv: Canvas) {
        val r = 13.dpf
        val o = 15.dpf
        p.style = Paint.Style.FILL
        p.color = Metro.fg
        cv.drawCircle(width - o, o, r, p)
        cv.drawCircle(width - o, height - o, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.dpf
        p.color = Metro.bg
        val a = 5.dpf
        // unpin: a cross
        cv.drawLine(width - o - a, o - a, width - o + a, o + a, p)
        cv.drawLine(width - o + a, o - a, width - o - a, o + a, p)
        // resize: arrow towards the next size (diagonal down = grow, up = shrink)
        val grow = tile.nextSize().w * tile.nextSize().h > tile.size.w * tile.size.h
        val cx = width - o
        val cy = height - o
        val d = if (grow) 1f else -1f
        cv.drawLine(cx - a * d, cy - a * d, cx + a * d, cy + a * d, p)
        cv.drawLine(cx + a * d, cy + a * d, cx + a * d, cy - a * 0.2f * d, p)
        cv.drawLine(cx + a * d, cy + a * d, cx - a * 0.2f * d, cy + a * d, p)
        p.style = Paint.Style.FILL
    }

    /** 1 = unpin, 2 = resize, 0 = tile body. */
    fun hitButton(x: Float, y: Float): Int {
        val o = 15.dpf
        val r = 22.dpf
        if (hypot(x - (width - o), y - o) < r) return 1
        if (hypot(x - (width - o), y - (height - o)) < r) return 2
        return 0
    }

    // ---- touch (tilt, tap, long press) -------------------------------------------------------

    private val longPress = Runnable {
        if (pressing) {
            pressing = false
            untilt()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            grid.beginEdit(this)
        }
    }

    private fun tilt(x: Float, y: Float) {
        if (!Prefs.animations) {
            alpha = 0.8f
            return
        }
        pivotX = width / 2f
        pivotY = height / 2f
        val dx = ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
        val dy = ((y - height / 2f) / (height / 2f)).coerceIn(-1f, 1f)
        val s = baseScale * if (abs(dx) < 0.35f && abs(dy) < 0.35f) 0.94f else 0.98f
        val deg = if (small) 14f else 9f
        an(70).rotationY(dx * deg).rotationX(-dy * deg).scaleX(s).scaleY(s).start()
    }

    private fun untilt() {
        alpha = 1f
        an(160).rotationX(0f).rotationY(0f).scaleX(baseScale).scaleY(baseScale).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastRawX = e.rawX
        lastRawY = e.rawY
        if (grid.editing) return grid.onEditTouch(this, e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (flipping) return true
                downX = e.x
                downY = e.y
                pressing = true
                tilt(e.x, e.y)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (pressing && hypot(e.x - downX, e.y - downY) > slop) {
                pressing = false
                removeCallbacks(longPress)
                untilt()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (pressing) {
                    pressing = false
                    untilt()
                    playSoundEffect(SoundEffectConstants.CLICK)
                    grid.onTileClick(this)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (pressing) untilt()
                pressing = false
            }
        }
        return true
    }

    fun resetTransforms() {
        animate().cancel()
        flipAnim?.cancel()
        rotationX = 0f
        rotationY = 0f
        alpha = 1f
        scaleX = baseScale
        scaleY = baseScale
        flipping = false
    }
}
