package com.mc.metro

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated sky for the weather tile, in the calm, layered style of modern phone weather apps:
 * a graded sky per condition and time of day, soft blurred clouds drifting at three depths,
 * a sun with bloom and faint rays, a moon with stars, fine rain with depth and drops sliding
 * on the glass, bokeh snow, drifting fog, and lightning that lights the clouds from inside.
 * Time-based, so it runs at the same speed at any frame rate.
 */
class WeatherFx {
    private enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY, SNOW, STORM }

    private class Cloud(val bmp: Bitmap, var x: Float, val y: Float, val speed: Float, val alpha: Int, val scale: Float)
    private class Streak(var x: Float, var y: Float, val z: Float, val len: Float)
    private class Bead(var x: Float, var y: Float, var r: Float, var vy: Float, var hold: Float)
    private class Flake(var x: Float, var y: Float, val z: Float, val ph: Float)
    private class Star(val x: Float, val y: Float, val r: Float, val ph: Float, val sp: Float)

    private var w = 0f
    private var h = 0f
    private var kind: Kind? = null
    private var day = true
    private var photo = false
    private val rnd = Random(7)
    private var t = 0f
    private var last = 0L

    private var sky: Shader? = null
    private var clouds = emptyList<Cloud>()
    private var fog = emptyList<Cloud>()
    private var streaks = emptyList<Streak>()
    private val beads = ArrayList<Bead>()
    private var flakes = emptyList<Flake>()
    private var stars = emptyList<Star>()
    private var bead: Bitmap? = null
    private var flakeSharp: Bitmap? = null
    private var flakeSoft: Bitmap? = null

    private var flash = 0f
    private var flashX = 0f
    private var nextFlash = 3f
    private var bolt: Path? = null
    private var boltLife = 0f
    private var meteor = -1f
    private var meteorX = 0f
    private var meteorY = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val ray = Path()

    private fun kindOf(code: Int): Kind = when {
        code <= 1 -> Kind.CLEAR
        code == 2 -> Kind.PARTLY
        code == 3 -> Kind.CLOUDY
        code == 45 || code == 48 -> Kind.FOG
        code in 51..57 -> Kind.DRIZZLE
        code in 61..63 || code == 80 || code == 66 -> Kind.RAIN
        code in 64..67 || code in 81..82 -> Kind.HEAVY
        code in 71..77 || code == 85 || code == 86 -> Kind.SNOW
        code >= 95 -> Kind.STORM
        else -> Kind.CLOUDY
    }

    /** Draws one frame. [photoBehind]: a city photo is already on the canvas, tint it instead of covering it. */
    fun draw(cv: Canvas, width: Float, height: Float, code: Int, isDay: Boolean, photoBehind: Boolean = false) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1e9f)
        last = now
        t += dt
        val k = kindOf(code)
        if (width != w || height != h || k != kind || isDay != day || photoBehind != photo) setup(width, height, k, isDay, photoBehind)

        skyLayer(cv)
        when {
            k == Kind.CLEAR || k == Kind.PARTLY -> if (day) sun(cv, if (k == Kind.CLEAR) 1f else 0.7f) else night(cv, dt, 1f)
            k == Kind.SNOW && !day -> night(cv, dt, 0.4f)
        }
        if (k == Kind.STORM) lightning(cv, dt, before = true)
        drawClouds(cv, clouds, dt)
        if (k == Kind.FOG) drawClouds(cv, fog, dt)
        when (k) {
            Kind.DRIZZLE, Kind.RAIN, Kind.HEAVY, Kind.STORM -> { rain(cv, dt); glass(cv, dt) }
            Kind.SNOW -> snow(cv, dt)
            else -> {}
        }
        if (k == Kind.STORM) lightning(cv, dt, before = false)
        // Bottom shade keeps the temperature and city readable on any sky.
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h, 0, 0x55000000, Shader.TileMode.CLAMP)
        cv.drawRect(0f, h * 0.45f, w, h, p)
        p.shader = null
    }

    // ---- setup -------------------------------------------------------------------------------

    private fun palette(k: Kind, d: Boolean): IntArray = when (k) {
        Kind.CLEAR -> if (d) intArrayOf(0xFF2A78D6.toInt(), 0xFF5DA5EC.toInt(), 0xFFA9D4F7.toInt()) else intArrayOf(0xFF070F24.toInt(), 0xFF12244A.toInt(), 0xFF2A4677.toInt())
        Kind.PARTLY -> if (d) intArrayOf(0xFF3D82CC.toInt(), 0xFF72A9DD.toInt(), 0xFFB7D3EC.toInt()) else intArrayOf(0xFF0C1630.toInt(), 0xFF1A2B4E.toInt(), 0xFF34486D.toInt())
        Kind.CLOUDY -> if (d) intArrayOf(0xFF5F7489.toInt(), 0xFF8899AA.toInt(), 0xFFB9C5D0.toInt()) else intArrayOf(0xFF141A24.toInt(), 0xFF252E3B.toInt(), 0xFF3C4757.toInt())
        Kind.FOG -> if (d) intArrayOf(0xFF7F8A94.toInt(), 0xFFA3ACB4.toInt(), 0xFFCCD2D7.toInt()) else intArrayOf(0xFF22272E.toInt(), 0xFF353B44.toInt(), 0xFF4F5661.toInt())
        Kind.DRIZZLE, Kind.RAIN -> if (d) intArrayOf(0xFF3F4E5E.toInt(), 0xFF5C6C7C.toInt(), 0xFF8494A3.toInt()) else intArrayOf(0xFF0F151D.toInt(), 0xFF1C2530.toInt(), 0xFF323E4C.toInt())
        Kind.HEAVY -> if (d) intArrayOf(0xFF2F3A46.toInt(), 0xFF46525F.toInt(), 0xFF67737F.toInt()) else intArrayOf(0xFF0A0E14.toInt(), 0xFF161C24.toInt(), 0xFF29313B.toInt())
        Kind.SNOW -> if (d) intArrayOf(0xFF7D95AE.toInt(), 0xFFA9BCCE.toInt(), 0xFFE0E8EF.toInt()) else intArrayOf(0xFF1B2538.toInt(), 0xFF314058.toInt(), 0xFF5A6981.toInt())
        Kind.STORM -> if (d) intArrayOf(0xFF1E2530.toInt(), 0xFF323B48.toInt(), 0xFF4E5764.toInt()) else intArrayOf(0xFF080A10.toInt(), 0xFF12161E.toInt(), 0xFF232832.toInt())
    }

    private fun setup(width: Float, height: Float, k: Kind, isDay: Boolean, photoBehind: Boolean) {
        w = width
        h = height
        kind = k
        day = isDay
        photo = photoBehind
        sky = LinearGradient(0f, 0f, 0f, h, palette(k, isDay), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        clouds = buildClouds(k)
        fog = if (k == Kind.FOG) buildFog() else emptyList()
        val rainy = k == Kind.DRIZZLE || k == Kind.RAIN || k == Kind.HEAVY || k == Kind.STORM
        val n = when (k) { Kind.DRIZZLE -> 45; Kind.RAIN -> 90; Kind.HEAVY, Kind.STORM -> 150; else -> 0 }
        streaks = List(n) {
            val z = 0.25f + rnd.nextFloat() * 0.75f
            Streak(rnd.nextFloat() * w, rnd.nextFloat() * h, z, (8f + 26f * z * z).dpf)
        }
        beads.clear()
        if (rainy) {
            bead = bead ?: beadBitmap()
            repeat(if (k == Kind.DRIZZLE) 6 else 11) { beads += newBead(true) }
        }
        flakes = if (k == Kind.SNOW) List(70) { Flake(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.2f + rnd.nextFloat() * 0.8f, rnd.nextFloat() * 6.28f) } else emptyList()
        if (k == Kind.SNOW) {
            flakeSharp = flakeSharp ?: disc(10.dp, 0f)
            flakeSoft = flakeSoft ?: disc(28.dp, 7.dpf)
        }
        stars = if (!isDay) List(46) {
            Star(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.7f, (0.5f + rnd.nextFloat() * 1.1f).dpf, rnd.nextFloat() * 6.28f, 0.6f + rnd.nextFloat() * 1.6f)
        } else emptyList()
    }

    /**
     * One cumulus, rendered at half size: a soft row of small lobes for the base, bigger
     * lobes billowing on top, blurred together; lit from above and shaded underneath.
     */
    private fun cloudBitmap(cw: Int, ch: Int, lit: Int, shade: Int, seed: Int): Bitmap {
        val r = Random(seed)
        val bw = (cw / 2).coerceAtLeast(8)
        val bh = (ch / 2).coerceAtLeast(8)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; maskFilter = BlurMaskFilter(bh * 0.07f, BlurMaskFilter.Blur.NORMAL) }
        val base = bh * 0.8f
        val baseN = 6
        for (i in 0 until baseN) {
            val f = i / (baseN - 1f)
            val rad = bh * (0.13f + 0.04f * r.nextFloat())
            c.drawCircle(bw * (0.14f + 0.72f * f), base - rad * 0.9f, rad, lp)
        }
        val tops = 3 + r.nextInt(3)
        for (i in 0 until tops) {
            val f = (i + 0.5f) / tops
            val bump = sin(f * PI).toFloat()
            // Sized to stay inside the bitmap (plus blur), so no lobe gets a flat top.
            val rad = bh * (0.14f + 0.13f * bump) * (0.88f + 0.24f * r.nextFloat())
            val cx = bw * (0.2f + 0.6f * f) + (r.nextFloat() - 0.5f) * bw * 0.08f
            c.drawCircle(cx, base - bh * 0.12f - rad * 0.6f, rad, lp)
        }
        // Light from above, shadow under the belly; only inside the silhouette.
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, bh * 0.15f, 0f, base, lit, shade, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), sp)
        sp.shader = RadialGradient(bw * 0.38f, bh * 0.25f, bh * 0.45f, 0x55FFFFFF, 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), sp)
        // Soften the underside so it melts into the sky.
        val fade = Paint().apply {
            shader = LinearGradient(0f, base - bh * 0.18f, 0f, base + bh * 0.05f, Color.BLACK, 0x66000000, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), fade)
        return b
    }

    private fun buildClouds(k: Kind): List<Cloud> {
        data class Spec(val count: Int, val lit: Int, val shade: Int)
        val night = !day
        val s = when (k) {
            Kind.CLEAR -> Spec(1, 0xF2FFFFFF.toInt(), 0xCCE6EEF6.toInt())
            Kind.PARTLY -> Spec(3, if (night) 0xFF8D9AB0.toInt() else 0xFFFFFFFF.toInt(), if (night) 0xFF4A5670.toInt() else 0xFFD3DEEA.toInt())
            Kind.CLOUDY -> Spec(6, if (night) 0xFF6B7584.toInt() else 0xFFF1F4F7.toInt(), if (night) 0xFF323A46.toInt() else 0xFFA9B5C1.toInt())
            Kind.SNOW -> Spec(4, if (night) 0xFF9AA8BC.toInt() else 0xFFF8FAFC.toInt(), if (night) 0xFF56637A.toInt() else 0xFFC4D0DC.toInt())
            Kind.DRIZZLE, Kind.RAIN -> Spec(6, if (night) 0xFF4E5866.toInt() else 0xFFB5BFC9.toInt(), if (night) 0xFF20262F.toInt() else 0xFF65717E.toInt())
            Kind.HEAVY -> Spec(7, if (night) 0xFF3E4651.toInt() else 0xFF96A0AA.toInt(), if (night) 0xFF181C22.toInt() else 0xFF4C5662.toInt())
            Kind.STORM -> Spec(7, if (night) 0xFF353B45.toInt() else 0xFF7C858F.toInt(), if (night) 0xFF0E1015.toInt() else 0xFF323942.toInt())
            Kind.FOG -> Spec(0, 0, 0)
        }
        if (s.count == 0) return emptyList()
        val out = ArrayList<Cloud>()
        // Three depths: far clouds small, faint and slow; near ones big, solid and faster.
        for (i in 0 until s.count) {
            val depth = i % 3
            val scale = floatArrayOf(0.62f, 0.85f, 1.1f)[depth]
            val cw = (w * 0.75f * scale).toInt()
            val ch = (h * 0.55f * scale).toInt()
            val bmp = cloudBitmap(cw, ch, s.lit, s.shade, 31 * i + k.ordinal)
            val y = when (k) {
                Kind.CLEAR, Kind.PARTLY -> h * (0.05f + 0.22f * rnd.nextFloat())
                else -> -ch * 0.25f + h * 0.32f * rnd.nextFloat()
            }
            val speed = floatArrayOf(3f, 6f, 10f)[depth].dpf * (if (k == Kind.STORM) 1.8f else 1f)
            val alpha = when (depth) { 0 -> 150; 1 -> 205; else -> 240 } * (if (k == Kind.CLEAR) 0.55f else 1f)
            out += Cloud(bmp, rnd.nextFloat() * (w + cw) - cw * 0.5f, y, speed, alpha.toInt(), 1f)
        }
        return out.sortedBy { it.alpha }
    }

    private fun buildFog(): List<Cloud> = List(5) { i ->
        val bw = (w * 0.8f).toInt().coerceAtLeast(8)
        val bh = (h * 0.22f).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; maskFilter = BlurMaskFilter(bh * 0.3f, BlurMaskFilter.Blur.NORMAL) }
        c.drawOval(RectF(bw * 0.05f, bh * 0.3f, bw * 0.95f, bh * 0.7f), fp)
        // Scale 2 marks a fog band: drawn at full size, not doubled like the half-size clouds.
        Cloud(b, rnd.nextFloat() * w * 1.5f - w * 0.25f, h * (0.15f + 0.17f * i), (4f + 3f * i).dpf, 70 + 22 * (i % 3), 2f)
    }

    private fun beadBitmap(): Bitmap {
        val s = 24.dp
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = s / 2f - 1
        val dp = Paint(Paint.ANTI_ALIAS_FLAG)
        // Water lens: darker rim at the bottom, bright crescent on top, sparkle top-left.
        dp.shader = RadialGradient(r, r * 0.8f, r * 1.1f, intArrayOf(0x22FFFFFF, 0x33FFFFFF, 0x66101820), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(r + 1, r + 1, r, dp)
        dp.shader = LinearGradient(0f, 0f, 0f, s * 0.5f, 0x88FFFFFF.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawOval(RectF(s * 0.22f, s * 0.1f, s * 0.78f, s * 0.42f), dp)
        dp.shader = null
        dp.color = 0xDDFFFFFF.toInt()
        c.drawCircle(s * 0.36f, s * 0.3f, s * 0.07f, dp)
        return b
    }

    private fun newBead(anywhere: Boolean) = Bead(
        rnd.nextFloat() * w,
        if (anywhere) rnd.nextFloat() * h else -10f,
        (2.5f + rnd.nextFloat() * 4.5f).dpf,
        0f,
        1f + rnd.nextFloat() * 6f,
    )

    private fun disc(size: Int, blur: Float): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            if (blur > 0) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f - blur - 1, dp)
        return b
    }

    // ---- layers ------------------------------------------------------------------------------

    private fun skyLayer(cv: Canvas) {
        p.shader = sky
        // Over a city photo the sky only tints it, so the place stays recognisable.
        p.alpha = if (photo) 95 else 255
        cv.drawRect(0f, 0f, w, h, p)
        p.alpha = 255
        p.shader = null
    }

    private fun sun(cv: Canvas, strength: Float) {
        val cx = w * 0.8f
        val cy = h * 0.2f
        val breathe = 1f + 0.04f * sin(t * 1.1f)
        val halo = h * 1.1f * breathe
        p.shader = RadialGradient(
            cx, cy, halo,
            intArrayOf(((0x70 * strength).toInt() shl 24) or 0xFFF4D6, ((0x22 * strength).toInt() shl 24) or 0xFFE9B8, 0),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        cv.drawCircle(cx, cy, halo, p)
        // Faint rays turning very slowly.
        cv.save()
        cv.rotate(t * 2.2f, cx, cy)
        for (i in 0 until 7) {
            val a = (i / 7f) * 2 * PI.toFloat()
            val len = h * (0.9f + 0.25f * sin(t * 0.7f + i))
            val wa = 0.07f
            ray.reset()
            ray.moveTo(cx, cy)
            ray.lineTo(cx + cos(a - wa) * len, cy + sin(a - wa) * len)
            ray.lineTo(cx + cos(a + wa) * len, cy + sin(a + wa) * len)
            ray.close()
            p.shader = RadialGradient(cx, cy, len, ((0x26 * strength).toInt() shl 24) or 0xFFFFFF, 0, Shader.TileMode.CLAMP)
            cv.drawPath(ray, p)
        }
        cv.restore()
        val core = h * 0.12f
        p.shader = RadialGradient(cx, cy, core * 1.6f, intArrayOf(Color.WHITE, 0xFFFFF6DC.toInt(), 0x00FFF0C0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, core * 1.6f, p)
        p.shader = null
        // Lens flare along the diagonal.
        for ((f, r, a) in listOf(Triple(0.35f, 0.05f, 0x22), Triple(0.6f, 0.025f, 0x30), Triple(0.85f, 0.08f, 0x14))) {
            p.color = ((a * strength).toInt() shl 24) or 0xFFFFFF
            cv.drawCircle(cx + (w * 0.2f - cx) * f, cy + (h * 0.85f - cy) * f, h * r, p)
        }
    }

    private fun night(cv: Canvas, dt: Float, amount: Float) {
        for (s in stars) {
            val a = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * s.sp + s.ph))) * amount
            p.color = ((a * 220).toInt() shl 24) or 0xFFFFFF
            cv.drawCircle(s.x, s.y, s.r, p)
        }
        val cx = w * 0.8f
        val cy = h * 0.24f
        val r = h * 0.1f
        p.shader = RadialGradient(cx, cy, r * 5, intArrayOf(((0x40 * amount).toInt() shl 24) or 0xD8E4FF, 0), null, Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 5, p)
        p.shader = null
        val moon = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(cx - r * 0.42f, cy - r * 0.22f, r * 0.9f, Path.Direction.CW) }
        moon.op(bite, Path.Op.DIFFERENCE)
        p.color = ((amount * 255).toInt() shl 24) or 0xF5F1E4
        cv.drawPath(moon, p)
        if (amount < 1f) return
        // Now and then a shooting star.
        if (meteor < 0f && rnd.nextFloat() < dt / 14f) {
            meteor = 0f
            meteorX = w * (0.25f + 0.5f * rnd.nextFloat())
            meteorY = h * 0.08f
        }
        if (meteor >= 0f) {
            meteor += dt / 0.8f
            val k = meteor
            val x = meteorX - k * w * 0.35f
            val y = meteorY + k * h * 0.35f
            p.shader = LinearGradient(x, y, x + w * 0.12f, y - h * 0.12f, 0xCCFFFFFF.toInt(), 0, Shader.TileMode.CLAMP)
            p.strokeWidth = 1.5f.dpf
            p.style = Paint.Style.STROKE
            p.alpha = ((1f - k) * 255).toInt().coerceIn(0, 255)
            cv.drawLine(x, y, x + w * 0.12f, y - h * 0.12f, p)
            p.style = Paint.Style.FILL
            p.shader = null
            p.alpha = 255
            if (meteor >= 1f) meteor = -1f
        }
    }

    private fun drawClouds(cv: Canvas, list: List<Cloud>, dt: Float) {
        for (c in list) {
            val band = c.scale > 1.5f
            val dw = c.bmp.width * (if (band) 1.8f else 2f)
            val dh = c.bmp.height * (if (band) 1f else 2f)
            c.x += c.speed * dt
            if (c.x > w + 4) c.x = -dw
            // Over the city photo clouds are a veil, not a wall.
            val base = if (photo) c.alpha * 9 / 20 else c.alpha
            bp.alpha = if (flash > 0f) min(255, base + (flash * 60).toInt()) else base
            rect.set(c.x, c.y, c.x + dw, c.y + dh)
            cv.drawBitmap(c.bmp, null, rect, bp)
        }
        bp.alpha = 255
    }

    private fun rain(cv: Canvas, dt: Float) {
        val heavy = kind == Kind.HEAVY || kind == Kind.STORM
        val slant = 0.16f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (d in streaks) {
            val v = (520f + 700f * d.z).dpf * (if (kind == Kind.DRIZZLE) 0.6f else 1f)
            d.y += v * dt
            d.x -= v * slant * dt
            if (d.y - d.len > h) {
                d.y = -rnd.nextFloat() * h * 0.3f
                d.x = rnd.nextFloat() * (w * 1.2f)
            }
            if (d.x < -20) d.x += w + 40
            // Near drops are wider and softer, far ones thin and faint: depth of field.
            p.strokeWidth = (0.6f + 1.6f * d.z).dpf
            val a = ((if (heavy) 0.32f else 0.26f) * (0.45f + 0.55f * d.z) * 255).toInt()
            val tx = d.x + d.len * slant
            val ty = d.y - d.len
            p.color = ((a / 3) shl 24) or 0xE6F0FF
            cv.drawLine(tx, ty, (tx + d.x) / 2, (ty + d.y) / 2, p)
            p.color = (a shl 24) or 0xE6F0FF
            cv.drawLine((tx + d.x) / 2, (ty + d.y) / 2, d.x, d.y, p)
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        // Mist rising off the ground.
        p.shader = LinearGradient(0f, h * 0.65f, 0f, h, 0, if (heavy) 0x30E8EEF4 else 0x1EE8EEF4, Shader.TileMode.CLAMP)
        cv.drawRect(0f, h * 0.65f, w, h, p)
        p.shader = null
    }

    /** Drops on the glass: they sit, swell, then slide down and are replaced at the top. */
    private fun glass(cv: Canvas, dt: Float) {
        val b = bead ?: return
        for (i in beads.indices) {
            val d = beads[i]
            if (d.hold > 0f) {
                d.hold -= dt
                d.r += dt * 0.25f.dpf
            } else {
                d.vy = min(d.vy + 260f.dpf * dt, 140f.dpf)
                d.y += d.vy * dt
                d.x += sin(d.y * 0.05f) * 0.3f
                // Faint wet track, fading upwards.
                trail.strokeWidth = d.r * 0.35f
                trail.shader = LinearGradient(0f, d.y - d.r * 5, 0f, d.y, 0, 0x18FFFFFF, Shader.TileMode.CLAMP)
                cv.drawLine(d.x, d.y - d.r * 5, d.x, d.y - d.r, trail)
                trail.shader = null
            }
            rect.set(d.x - d.r, d.y - d.r, d.x + d.r, d.y + d.r)
            bp.alpha = 230
            cv.drawBitmap(b, null, rect, bp)
            if (d.y - d.r > h) beads[i] = newBead(false)
        }
        bp.alpha = 255
    }

    private fun snow(cv: Canvas, dt: Float) {
        val sharp = flakeSharp ?: return
        val soft = flakeSoft ?: return
        for (f in flakes) {
            f.y += (14f + 46f * f.z).dpf * dt
            f.x += sin(t * (0.6f + f.z) + f.ph) * 14f.dpf * dt
            if (f.y > h + 20) { f.y = -20f; f.x = rnd.nextFloat() * w }
            // Near flakes: large and out of focus (bokeh); far ones small and crisp.
            val near = f.z > 0.75f
            val s = if (near) (10f + 14f * f.z).dpf else (2f + 4f * f.z).dpf
            bp.alpha = if (near) 120 else (150 + 100 * f.z).toInt()
            rect.set(f.x - s / 2, f.y - s / 2, f.x + s / 2, f.y + s / 2)
            cv.drawBitmap(if (near) soft else sharp, null, rect, bp)
        }
        bp.alpha = 255
    }

    /** [before]: glow behind the clouds; after: the bolt and the flash over everything. */
    private fun lightning(cv: Canvas, dt: Float, before: Boolean) {
        if (before) {
            nextFlash -= dt
            if (nextFlash <= 0f) {
                flash = 1f
                flashX = w * (0.15f + 0.7f * rnd.nextFloat())
                nextFlash = 4f + rnd.nextFloat() * 6f
                bolt = if (rnd.nextFloat() < 0.55f) makeBolt(flashX) else null
                boltLife = 0.22f
            }
            if (flash > 0f) {
                // Double pulse, like real lightning.
                val pulse = if (flash > 0.6f) (flash - 0.6f) / 0.4f else flash / 0.6f * 0.55f
                p.shader = RadialGradient(flashX, h * 0.15f, w * 0.7f, ((min(1f, pulse) * 140).toInt() shl 24) or 0xDDE6FF, 0, Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, p)
                p.shader = null
                flash -= dt * 2.2f
            }
            return
        }
        val path = bolt ?: return
        if (boltLife <= 0f) return
        boltLife -= dt
        val a = (boltLife / 0.22f).coerceIn(0f, 1f)
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.color = ((a * 70).toInt() shl 24) or 0xB9C8FF
        p.strokeWidth = 7.dpf
        cv.drawPath(path, p)
        p.color = ((a * 255).toInt() shl 24) or 0xFFFFFF
        p.strokeWidth = 1.6f.dpf
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = ((a * 40).toInt() shl 24) or 0xFFFFFF
        cv.drawRect(0f, 0f, w, h, p)
    }

    private fun makeBolt(x0: Float): Path {
        val path = Path()
        var x = x0
        var y = h * 0.12f
        path.moveTo(x, y)
        val end = h * (0.75f + 0.2f * rnd.nextFloat())
        while (y < end) {
            y += h * (0.06f + 0.06f * rnd.nextFloat())
            x += (rnd.nextFloat() - 0.5f) * w * 0.08f
            path.lineTo(x, y)
            if (rnd.nextFloat() < 0.25f) {
                val bx = x + (rnd.nextFloat() - 0.5f) * w * 0.12f
                path.lineTo(bx, y + h * 0.08f)
                path.moveTo(x, y)
            }
        }
        return path
    }
}
