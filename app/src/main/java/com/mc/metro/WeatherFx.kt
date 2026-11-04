package com.mc.metro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Animated sky for the weather tile. Time-based (frame-rate independent), layered for depth:
 * rain with wind, splashes and lightning; soft drifting snow; sun glow with rays and flare;
 * night stars and moon; volumetric clouds and fog banks built from soft pre-rendered puffs.
 */
class WeatherFx {
    private enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY, SNOW, STORM }

    private class Drop(var x: Float, var y: Float, val z: Float)
    private class Flake(var x: Float, var y: Float, val z: Float, val ph: Float, val sway: Float)
    private class Star(val x: Float, val y: Float, val r: Float, val ph: Float, val sp: Float)
    private class Splash(val x: Float, val y: Float, val z: Float, var age: Float)
    private class Cloud(val bmp: Bitmap, var x: Float, val y: Float, val speed: Float, val alpha: Int)

    private var w = 0f
    private var h = 0f
    private var kind: Kind? = null
    private var day = true
    private val rnd = Random(1234)
    private var t = 0f
    private var last = 0L

    private var drops = emptyList<Drop>()
    private var flakes = emptyList<Flake>()
    private var stars = emptyList<Star>()
    private val splashes = ArrayList<Splash>()
    private var clouds = emptyList<Cloud>()
    private var fogs = emptyList<Cloud>()

    private var bolt: Path? = null
    private var boltLife = 0f
    private var flash = 0f
    private var nextBolt = 2.5f
    private var meteor = -1f
    private var meteorX = 0f
    private var meteorY = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var dot: Bitmap? = null
    private val wind = -0.22f

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

    fun draw(cv: Canvas, width: Float, height: Float, code: Int, isDay: Boolean) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1e9f)
        last = now
        t += dt
        val k = kindOf(code)
        if (width != w || height != h || k != kind || isDay != day) setup(width, height, k, isDay)
        mood(cv)
        when (k) {
            Kind.CLEAR -> if (day) sun(cv, 1f) else night(cv, dt)
            Kind.PARTLY -> {
                if (day) sun(cv, 0.7f) else night(cv, dt)
                clouds(cv, dt)
            }
            Kind.CLOUDY -> clouds(cv, dt)
            Kind.FOG -> fog(cv, dt)
            Kind.DRIZZLE, Kind.RAIN, Kind.HEAVY -> {
                clouds(cv, dt)
                rain(cv, dt)
            }
            Kind.STORM -> {
                clouds(cv, dt)
                rain(cv, dt)
                lightning(cv, dt)
            }
            Kind.SNOW -> {
                clouds(cv, dt)
                snow(cv, dt)
            }
        }
    }

    // ---- setup -------------------------------------------------------------------------------

    private fun setup(width: Float, height: Float, k: Kind, isDay: Boolean) {
        w = width
        h = height
        kind = k
        day = isDay
        splashes.clear()
        bolt = null
        flash = 0f
        if (dot == null) dot = softDot()
        val area = w * h / (300.dpf * 150.dpf)
        drops = if (k == Kind.DRIZZLE || k == Kind.RAIN || k == Kind.HEAVY || k == Kind.STORM) {
            val n = ((when (k) { Kind.DRIZZLE -> 45; Kind.RAIN -> 110; else -> 190 }) * area).toInt().coerceIn(20, 400)
            List(n) { Drop(rnd.nextFloat() * w * 1.3f, rnd.nextFloat() * h, rnd.nextFloat()) }
        } else emptyList()
        flakes = if (k == Kind.SNOW) {
            List((90 * area).toInt().coerceIn(25, 220)) {
                Flake(rnd.nextFloat() * w, rnd.nextFloat() * h, rnd.nextFloat(), rnd.nextFloat() * 6.28f, (3 + rnd.nextFloat() * 9).dpf)
            }
        } else emptyList()
        stars = if (!isDay && (k == Kind.CLEAR || k == Kind.PARTLY)) {
            List((60 * area).toInt().coerceIn(20, 140)) {
                Star(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.8f, (0.5f + rnd.nextFloat() * 1.2f).dpf, rnd.nextFloat() * 6.28f, 0.6f + rnd.nextFloat() * 2.2f)
            }
        } else emptyList()
        clouds = buildClouds(k)
        fogs = if (k == Kind.FOG) List(4) { i ->
            val bw = (w * 1.6f).toInt().coerceAtLeast(8)
            val bh = (h * 0.42f).toInt().coerceAtLeast(8)
            Cloud(puffs(bw, bh, 26, 0.55f), -rnd.nextFloat() * w * 0.6f, h * (0.05f + i * 0.24f), (5 + i * 3).dpf, 120 + i * 25)
        } else emptyList()
    }

    private fun buildClouds(k: Kind): List<Cloud> {
        val n = when (k) {
            Kind.CLEAR -> 0
            Kind.PARTLY -> 3
            Kind.CLOUDY -> 6
            Kind.DRIZZLE, Kind.SNOW -> 5
            else -> 7
        }
        return List(n) { i ->
            val near = i % 2 == 1
            val cw = (w * (0.55f + rnd.nextFloat() * 0.35f) * if (near) 1f else 0.75f).toInt().coerceAtLeast(8)
            val ch = (cw * (0.42f + rnd.nextFloat() * 0.12f)).toInt().coerceAtLeast(8)
            val top = if (k == Kind.PARTLY) h * (0.05f + rnd.nextFloat() * 0.45f) else -ch * 0.35f + rnd.nextFloat() * h * 0.45f
            Cloud(
                puffs(cw, ch, 24, 1f),
                rnd.nextFloat() * (w + cw) - cw, top,
                (if (near) 9f else 4f + rnd.nextFloat() * 2).dpf,
                if (near) 215 else 140,
            )
        }
    }

    /** Soft white blob made of overlapping radial gradients; tinted at draw time. */
    private fun puffs(bw: Int, bh: Int, count: Int, density: Float): Bitmap {
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until count) {
            val fx = 0.12f + rnd.nextFloat() * 0.76f
            // Higher puffs in the middle give the classic cumulus silhouette.
            val hump = sin(fx * PI.toFloat())
            val r = bh * (0.22f + rnd.nextFloat() * 0.22f) * (0.7f + hump * 0.5f)
            val cx = fx * bw
            val cy = min(bh * (0.72f - hump * 0.28f + (rnd.nextFloat() - 0.5f) * 0.12f), bh - r)
            val a = (255 * density * (0.55f + rnd.nextFloat() * 0.45f)).toInt().coerceIn(0, 255)
            // Dense core with a short soft rim: reads as a volume, not a blur.
            pp.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(a, 255, 255, 255), Color.argb(a * 9 / 10, 255, 255, 255), Color.argb(a / 3, 255, 255, 255), 0), floatArrayOf(0f, 0.62f, 0.85f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, pp)
        }
        // Darker underside.
        pp.shader = LinearGradient(0f, bh * 0.45f, 0f, bh.toFloat(), 0, 0x55000000, Shader.TileMode.CLAMP)
        pp.xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), pp)
        return b
    }

    private fun softDot(): Bitmap {
        val s = 48
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        pp.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(Color.WHITE, 0xAAFFFFFF.toInt(), 0), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, pp)
        return b
    }

    // ---- layers ------------------------------------------------------------------------------

    private fun mood(cv: Canvas) {
        val k = kind ?: return
        val (top, bottom) = when {
            !day -> 0x9908122E.toInt() to 0x55000814
            k == Kind.CLEAR -> 0x2A3B8FD8 to 0x00000000
            k == Kind.PARTLY -> 0x22406A90 to 0x00000000
            k == Kind.STORM -> 0x99101622.toInt() to 0x66000000
            k == Kind.HEAVY || k == Kind.RAIN -> 0x7724303C to 0x44000000
            k == Kind.FOG -> 0x55B8C0C8 to 0x77D8DDE2
            k == Kind.SNOW -> 0x5590A0B4 to 0x33FFFFFF
            else -> 0x5540464E to 0x22000000
        }
        p.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }

    private fun sun(cv: Canvas, strength: Float) {
        val sx = w * 0.82f
        val sy = h * 0.2f
        val m = max(w, h)
        val pulse = 1f + 0.04f * sin(t * 1.3f)
        p.shader = RadialGradient(sx, sy, m * 0.75f * pulse, intArrayOf(Color.argb((110 * strength).toInt(), 255, 236, 180), Color.argb((40 * strength).toInt(), 255, 220, 150), 0), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        // Light shafts: thin wedges with a radial falloff, slowly turning and breathing.
        val path = Path()
        p.shader = RadialGradient(sx, sy, m * 0.95f, intArrayOf(Color.argb((70 * strength).toInt(), 255, 248, 220), 0), null, Shader.TileMode.CLAMP)
        for (i in 0 until 9) {
            val a = t * 0.05f + i * (2 * PI.toFloat() / 9) + sin(i * 1.7f) * 0.2f
            val spread = 0.035f + 0.02f * sin(t * 0.6f + i * 2.1f)
            val len = m * (0.75f + 0.2f * sin(t * 0.4f + i))
            path.reset()
            path.moveTo(sx, sy)
            path.lineTo(sx + cos(a - spread) * len, sy + sin(a - spread) * len)
            path.lineTo(sx + cos(a + spread) * len, sy + sin(a + spread) * len)
            path.close()
            cv.drawPath(path, p)
        }
        val core = min(w, h) * 0.11f
        p.shader = RadialGradient(sx, sy, core * 2.2f, intArrayOf(Color.argb((255 * strength).toInt(), 255, 253, 240), Color.argb((200 * strength).toInt(), 255, 240, 190), 0), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(sx, sy, core * 2.2f, p)
        p.shader = null
        // Lens flare along the line from the sun through the tile centre.
        val dx = w / 2 - sx
        val dy = h / 2 - sy
        val flares = floatArrayOf(0.55f, 0.9f, 1.25f, 1.6f)
        val sizes = floatArrayOf(0.05f, 0.11f, 0.035f, 0.08f)
        val cols = intArrayOf(0x22FFFFFF, 0x1A8FD3FF, 0x26FFD7A0, 0x149CFFC8)
        for (i in flares.indices) {
            val fx = sx + dx * flares[i]
            val fy = sy + dy * flares[i]
            val r = min(w, h) * sizes[i]
            val a = (Color.alpha(cols[i]) * strength * (0.8f + 0.2f * sin(t + i))).toInt()
            p.shader = RadialGradient(fx, fy, r, intArrayOf(Color.argb(a, Color.red(cols[i]), Color.green(cols[i]), Color.blue(cols[i])), Color.argb(a, Color.red(cols[i]), Color.green(cols[i]), Color.blue(cols[i])), 0), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(fx, fy, r, p)
        }
        p.shader = null
    }

    private fun night(cv: Canvas, dt: Float) {
        p.shader = null
        for (s in stars) {
            val a = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * s.sp + s.ph))
            p.color = Color.argb((a * 230).toInt(), 235, 240, 255)
            cv.drawCircle(s.x, s.y, s.r, p)
        }
        val mx = w * 0.8f
        val my = h * 0.24f
        val r = min(w, h) * 0.09f
        p.shader = RadialGradient(mx, my, r * 4f, intArrayOf(0x55C8D8FF, 0x18A0B8FF, 0), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(mx, my, r * 4f, p)
        p.shader = RadialGradient(mx - r * 0.3f, my - r * 0.3f, r * 1.3f, 0xFFFFFDF2.toInt(), 0xFFD9DCE6.toInt(), Shader.TileMode.CLAMP)
        val moon = Path().apply { addCircle(mx, my, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(mx + r * 0.55f, my - r * 0.25f, r * 0.95f, Path.Direction.CW) }
        moon.op(bite, Path.Op.DIFFERENCE)
        cv.drawPath(moon, p)
        p.shader = null
        // Occasional shooting star.
        if (meteor < 0f && rnd.nextFloat() < dt / 9f) {
            meteor = 0f
            meteorX = w * (0.1f + rnd.nextFloat() * 0.5f)
            meteorY = h * rnd.nextFloat() * 0.3f
        }
        if (meteor >= 0f) {
            meteor += dt
            val life = 0.7f
            val k = meteor / life
            val hx = meteorX + k * w * 0.5f
            val hy = meteorY + k * h * 0.35f
            val a = (sin(k * PI.toFloat()) * 220).toInt().coerceAtLeast(0)
            p.shader = LinearGradient(hx, hy, hx - w * 0.15f, hy - h * 0.1f, Color.argb(a, 255, 255, 255), 0, Shader.TileMode.CLAMP)
            p.strokeWidth = 1.5f.dpf
            p.strokeCap = Paint.Cap.ROUND
            cv.drawLine(hx, hy, hx - w * 0.15f, hy - h * 0.1f, p)
            p.shader = null
            if (meteor > life) meteor = -1f
        }
    }

    private fun clouds(cv: Canvas, dt: Float) {
        val k = kind ?: return
        val tint = when {
            k == Kind.STORM -> 0xFF5D6570.toInt()
            k == Kind.HEAVY || k == Kind.RAIN -> 0xFF8A939E.toInt()
            !day -> 0xFF8C98AE.toInt()
            k == Kind.CLOUDY || k == Kind.DRIZZLE -> 0xFFC9CED4.toInt()
            else -> Color.WHITE
        }
        bp.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
        for (c in clouds) {
            c.x += c.speed * dt
            if (c.x > w) c.x = -c.bmp.width.toFloat() - rnd.nextFloat() * w * 0.3f
            bp.alpha = c.alpha
            cv.drawBitmap(c.bmp, c.x, c.y, bp)
        }
        bp.colorFilter = null
        bp.alpha = 255
    }

    private fun fog(cv: Canvas, dt: Float) {
        bp.colorFilter = PorterDuffColorFilter(0xFFE6E9EC.toInt(), PorterDuff.Mode.MULTIPLY)
        for (f in fogs) {
            f.x += f.speed * dt
            if (f.x > w * 0.2f) f.x = -f.bmp.width + w * 0.8f
            bp.alpha = f.alpha
            cv.drawBitmap(f.bmp, f.x, f.y, bp)
        }
        bp.colorFilter = null
        bp.alpha = 255
    }

    private fun rain(cv: Canvas, dt: Float) {
        val k = kind ?: return
        val heavy = k == Kind.HEAVY || k == Kind.STORM
        val drizzle = k == Kind.DRIZZLE
        val nx = wind / sqrt(1 + wind * wind)
        val ny = 1 / sqrt(1 + wind * wind)
        p.shader = null
        p.strokeCap = Paint.Cap.ROUND
        for (d in drops) {
            val v = (if (drizzle) 260f else 420f + 380f * d.z).dpf * (if (heavy) 1.25f else 1f)
            d.y += v * ny * dt
            d.x += v * nx * dt
            val len = (if (drizzle) 4f + 6f * d.z else 9f + 22f * d.z).dpf * (if (heavy) 1.2f else 1f)
            if (d.y - len > h) {
                if (d.z > 0.55f && rnd.nextFloat() < 0.6f) splashes += Splash(d.x, h - rnd.nextFloat() * h * 0.06f, d.z, 0f)
                d.y = -rnd.nextFloat() * h * 0.3f
                d.x = rnd.nextFloat() * (w * 1.3f)
            }
            val a = ((if (drizzle) 50 else 35) + 150 * d.z).toInt()
            p.strokeWidth = (0.5f + 1.3f * d.z).dpf
            p.color = Color.argb(a / 2, 210, 225, 240)
            cv.drawLine(d.x, d.y, d.x - nx * len, d.y - ny * len, p)
            p.color = Color.argb(a, 235, 242, 250)
            cv.drawLine(d.x, d.y, d.x - nx * len * 0.35f, d.y - ny * len * 0.35f, p)
        }
        // Splashes: an expanding ring plus two droplets thrown up.
        p.style = Paint.Style.STROKE
        val it = splashes.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.age += dt
            val life = 0.32f
            if (s.age > life) { it.remove(); continue }
            val k2 = s.age / life
            val a = ((1 - k2) * 160 * s.z).toInt()
            p.color = Color.argb(a, 230, 238, 248)
            p.strokeWidth = 1.dpf
            val r = (2 + 7 * k2) * s.z
            cv.drawOval(s.x - r.dpf, s.y - r.dpf * 0.3f, s.x + r.dpf, s.y + r.dpf * 0.3f, p)
            val up = sin(k2 * PI.toFloat()) * 6.dpf * s.z
            p.style = Paint.Style.FILL
            cv.drawCircle(s.x - k2 * 5.dpf, s.y - up, 0.9f.dpf, p)
            cv.drawCircle(s.x + k2 * 5.dpf, s.y - up * 0.8f, 0.9f.dpf, p)
            p.style = Paint.Style.STROKE
        }
        p.style = Paint.Style.FILL
        if (heavy) {
            // Spray haze near the ground.
            p.shader = LinearGradient(0f, h * 0.7f, 0f, h, 0, 0x33DDE6EE, Shader.TileMode.CLAMP)
            cv.drawRect(0f, h * 0.7f, w, h, p)
            p.shader = null
        }
    }

    private fun lightning(cv: Canvas, dt: Float) {
        if (t > nextBolt) {
            bolt = makeBolt()
            boltLife = 0.35f
            flash = 1f
            nextBolt = t + 3.5f + rnd.nextFloat() * 6f
        }
        if (flash > 0.01f) {
            // Two-stage flicker, then exponential fade.
            val f = flash * if (boltLife in 0.18f..0.24f) 0.3f else 1f
            p.color = Color.argb((f * 150).toInt(), 225, 232, 255)
            cv.drawRect(0f, 0f, w, h, p)
            flash *= exp(-dt * 7f)
        }
        val b = bolt ?: return
        boltLife -= dt
        if (boltLife <= 0f) { bolt = null; return }
        val a = (min(1f, boltLife / 0.2f) * 255).toInt()
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(a / 4, 170, 190, 255)
        p.strokeWidth = 7.dpf
        cv.drawPath(b, p)
        p.color = Color.argb(a / 2, 200, 215, 255)
        p.strokeWidth = 3.dpf
        cv.drawPath(b, p)
        p.color = Color.argb(a, 255, 255, 255)
        p.strokeWidth = 1.2f.dpf
        cv.drawPath(b, p)
        p.style = Paint.Style.FILL
    }

    private fun makeBolt(): Path {
        val path = Path()
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float, disp: Float, depth: Int, branch: Boolean) {
            if (depth == 0) {
                path.moveTo(x1, y1)
                path.lineTo(x2, y2)
                return
            }
            val mx = (x1 + x2) / 2 + (rnd.nextFloat() - 0.5f) * disp
            val my = (y1 + y2) / 2 + (rnd.nextFloat() - 0.5f) * disp * 0.3f
            seg(x1, y1, mx, my, disp / 2, depth - 1, branch)
            seg(mx, my, x2, y2, disp / 2, depth - 1, branch)
            if (branch && depth >= 3 && rnd.nextFloat() < 0.35f) {
                val bx = mx + (rnd.nextFloat() - 0.3f) * w * 0.25f
                val by = my + h * (0.12f + rnd.nextFloat() * 0.18f)
                seg(mx, my, bx, by, disp / 2, depth - 2, false)
            }
        }
        val x0 = w * (0.25f + rnd.nextFloat() * 0.5f)
        seg(x0, -4f, x0 + (rnd.nextFloat() - 0.5f) * w * 0.3f, h * (0.75f + rnd.nextFloat() * 0.25f), w * 0.22f, 6, true)
        return path
    }

    private fun snow(cv: Canvas, dt: Float) {
        val d = dot ?: return
        val r = RectF()
        for (f in flakes) {
            f.y += (10f + 34f * f.z).dpf * dt
            if (f.y > h + 6.dpf) {
                f.y = -6.dpf
                f.x = rnd.nextFloat() * w
            }
            val x = f.x + sin(t * (0.6f + f.z) + f.ph) * f.sway + t * 6.dpf * f.z % w
            val xx = ((x % w) + w) % w
            val s = (1.4f + 4.2f * f.z).dpf
            r.set(xx - s, f.y - s, xx + s, f.y + s)
            bp.alpha = (90 + 165 * f.z).toInt()
            cv.drawBitmap(d, null, r, bp)
        }
        bp.alpha = 255
    }
}
