package com.mc.metro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.text.Normalizer

class AppEntry(val info: LauncherActivityInfo) {
    val component: ComponentName = info.componentName
    val pkg: String = component.packageName
    val user: UserHandle = info.user
    val label: String = info.label?.toString()?.trim().orEmpty().ifEmpty { pkg }
    val key: String = component.flattenToShortString() + "@" + user.hashCode()
    val norm: String = Apps.normalize(label)
    val letter: String = norm.firstOrNull()?.takeIf { it in 'a'..'z' }?.toString() ?: "#"
}

object Apps {
    var all: List<AppEntry> = emptyList()
        private set
    private val icons = HashMap<String, Bitmap>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var la: LauncherApps? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(c: Context) {
        if (la != null) return
        val app = c.applicationContext
        val l = app.getSystemService(LauncherApps::class.java)
        la = l
        load(app)
        l.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = changed(app)
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = changed(app)
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = changed(app)
            override fun onPackagesAvailable(p: Array<out String>?, user: UserHandle?, replacing: Boolean) = changed(app)
            override fun onPackagesUnavailable(p: Array<out String>?, user: UserHandle?, replacing: Boolean) = changed(app)
        }, main)
    }

    private fun changed(c: Context) {
        load(c)
        listeners.toList().forEach { it() }
    }

    fun addListener(f: () -> Unit) { listeners += f }
    fun removeListener(f: () -> Unit) { listeners -= f }

    fun load(c: Context) {
        val l = la ?: c.getSystemService(LauncherApps::class.java)
        val um = c.getSystemService(UserManager::class.java)
        val out = ArrayList<AppEntry>()
        for (u in um.userProfiles) {
            for (i in l.getActivityList(null, u)) {
                val cn = i.componentName
                if (cn.packageName == c.packageName && cn.className.endsWith(".LauncherActivity")) continue
                out += AppEntry(i)
            }
        }
        val col = Collator.getInstance().apply { strength = Collator.PRIMARY }
        // "#" first, then a..z, collated by label inside each letter.
        all = out.sortedWith(
            compareBy<AppEntry> { if (it.letter == "#") 0 else 1 }
                .thenBy { it.letter }
                .thenComparator { a, b -> col.compare(a.label, b.label) }
        )
        icons.clear()
        mono.clear()
    }

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun find(key: String?): AppEntry? {
        if (key == null) return null
        all.firstOrNull { it.key == key }?.let { return it }
        // Same component, different user hash (e.g. after a restore): match the component only.
        val comp = key.substringBefore('@')
        return all.firstOrNull { it.component.flattenToShortString() == comp }
    }

    fun byPkg(pkg: String?): AppEntry? = if (pkg == null) null else all.firstOrNull { it.pkg == pkg }

    fun resolve(c: Context, intent: Intent): AppEntry? = try {
        val pkg = c.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (pkg == null || pkg == "android") null else byPkg(pkg)
    } catch (e: Exception) {
        null
    }

    private val mono = HashMap<String, Boolean>()

    fun hasMono(e: AppEntry): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return mono.getOrPut(e.key) {
            val d = try { e.info.getIcon(0) } catch (t: Throwable) { null }
            d is AdaptiveIconDrawable && d.monochrome != null
        }
    }

    /**
     * Icon bitmap. With [flat] it is a white Metro glyph: the app's themed (monochrome) layer
     * when it has one, otherwise a glyph generated from the coloured icon.
     */
    fun icon(e: AppEntry, size: Int, flat: Boolean): Bitmap {
        val s = size.coerceAtLeast(1)
        val k = "${e.key}|$s|$flat"
        icons[k]?.let { return it }
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val d = try { e.info.getIcon(0) } catch (t: Throwable) { null }
        val pad = s / 4
        val m = if (flat && Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) d.monochrome else null
        when {
            d == null -> {}
            m != null -> {
                val md = m.mutate()
                md.setBounds(-pad, -pad, s + pad, s + pad)
                md.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                md.draw(cv)
            }
            flat -> {
                if (d is AdaptiveIconDrawable && d.foreground != null) {
                    // Foreground only: the background layer is just the coloured plate.
                    val fg = d.foreground.mutate()
                    fg.setBounds(-pad, -pad, s + pad, s + pad)
                    fg.draw(cv)
                } else {
                    d.setBounds(0, 0, s, s)
                    d.draw(cv)
                }
                flatten(bmp)
            }
            else -> {
                d.setBounds(0, 0, s, s)
                d.draw(cv)
            }
        }
        icons[k] = bmp
        return bmp
    }

    /**
     * Turns a coloured icon into a white glyph in place. A small shape on transparency becomes a
     * silhouette; a filled shape (circle/square plate with a symbol on it) keeps only the pixels
     * that contrast with the plate, so e.g. a white camera on a blue disc becomes a white camera.
     */
    private fun flatten(b: Bitmap) {
        val n = b.width * b.height
        val px = IntArray(n)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val lum = FloatArray(n)
        var cover = 0f
        var lumSum = 0f
        var opaque = 0
        for (i in 0 until n) {
            val c = px[i]
            val a = (c ushr 24) / 255f
            lum[i] = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
            cover += a
            if (a > 0.5f) {
                lumSum += lum[i]
                opaque++
            }
        }
        // The glyph area is the central ~2/3 of the bitmap; judge coverage against that.
        val filled = opaque > 0 && cover / (n * 0.45f) > 0.6f
        if (filled) {
            // Plate colour = opaque pixels on the outer edge of the shape.
            val w = b.width
            val h = b.height
            fun alphaAt(x: Int, y: Int) = if (x < 0 || y < 0 || x >= w || y >= h) 0 else px[y * w + x] ushr 24
            var edgeSum = 0f
            var edgeN = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (alphaAt(x, y) < 200) continue
                if (alphaAt(x - 3, y) < 40 || alphaAt(x + 3, y) < 40 || alphaAt(x, y - 3) < 40 || alphaAt(x, y + 3) < 40) {
                    edgeSum += lum[y * w + x]
                    edgeN++
                }
            }
            val plate = if (edgeN > 0) edgeSum / edgeN else lumSum / opaque
            var bright = 0
            for (i in 0 until n) if ((px[i] ushr 24) > 150 && lum[i] > plate + 0.18f) bright++
            // Light shape (already a white glyph): keep it and punch out its dark details.
            // Coloured plate: keep the light symbol on it; with none, fall back to the silhouette.
            val mode = when {
                plate > 0.78f -> 0
                bright > opaque * 0.03f -> 1
                else -> 2
            }
            for (i in 0 until n) {
                val a = (px[i] ushr 24) / 255f
                val t = when (mode) {
                    0 -> ((lum[i] - (plate - 0.3f)) / 0.12f).coerceIn(0f, 1f)
                    1 -> ((lum[i] - plate - 0.12f) / 0.12f).coerceIn(0f, 1f)
                    else -> 1f
                }
                // Shadows and soft edges are semi-transparent: keep only solid pixels crisp.
                val solid = ((a - 0.55f) / 0.25f).coerceIn(0f, 1f)
                px[i] = ((solid * t * 255f).toInt() shl 24) or 0xFFFFFF
            }
        } else {
            for (i in 0 until n) px[i] = (px[i] and 0xFF000000.toInt()) or 0xFFFFFF
        }
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    }

    fun launch(c: Context, e: AppEntry, src: Rect?, opts: Bundle?) {
        (la ?: c.getSystemService(LauncherApps::class.java)).startMainActivity(e.component, e.user, src, opts)
    }

    fun details(c: Context, e: AppEntry) {
        (la ?: c.getSystemService(LauncherApps::class.java)).startAppDetailsActivity(e.component, e.user, null, null)
    }
}
