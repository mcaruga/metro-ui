package com.mc.metro

import android.animation.TimeInterpolator
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.animation.DecelerateInterpolator

var DENSITY = 1f
var SCALED = 1f
val Number.dp: Int get() = (toFloat() * DENSITY + 0.5f).toInt()
val Number.dpf: Float get() = toFloat() * DENSITY
val Number.sp: Float get() = toFloat() * SCALED

/** Animator with every sticky property reset, so earlier delays/interpolators never leak. */
fun View.an(duration: Long, delay: Long = 0, interp: TimeInterpolator = DecelerateInterpolator()): ViewPropertyAnimator =
    animate().setDuration(duration).setStartDelay(delay).setInterpolator(interp)

class MetroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val dm = resources.displayMetrics
        DENSITY = dm.density
        SCALED = dm.density * resources.configuration.fontScale
        Prefs.init(this)
        Metro.init(this)
        // Never let the update machinery take the launcher down.
        runCatching {
            Updater.clearNotification(this)
            Updater.schedule(this)
        }
    }
}

object Prefs {
    lateinit var sp: SharedPreferences
    fun init(c: Context) {
        sp = c.getSharedPreferences("metro", Context.MODE_PRIVATE)
    }

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** Bumped whenever a change needs the launcher UI rebuilt. */
    val style: Int get() = sp.getInt("style", 0)
    private fun bump() = sp.edit().putInt("style", style + 1).apply()

    var accent: Int
        get() = sp.getInt("accent", 0xFFE51400.toInt())
        set(v) { sp.edit().putInt("accent", v or 0xFF000000.toInt()).apply(); bump() }
    var light: Boolean
        get() = b("light", false)
        set(v) { put("light", v); bump() }
    var moreTiles: Boolean
        get() = b("more_tiles", false)
        set(v) { put("more_tiles", v); bump() }
    /** Start tiles are always flat; this only affects the app list. */
    var listColor: Boolean
        get() = b("list_color", true)
        set(v) { put("list_color", v); bump() }
    var iconScale: Float
        get() = sp.getFloat("icon_scale", 1f)
        set(v) { sp.edit().putFloat("icon_scale", v).apply(); bump() }
    var textScale: Float
        get() = sp.getFloat("text_scale", 1f)
        set(v) { sp.edit().putFloat("text_scale", v).apply(); bump() }
    /** App names on tiles and in the app list; up to 2x for people who need large text. */
    var labelScale: Float
        get() = sp.getFloat("label_scale", 1f)
        set(v) { sp.edit().putFloat("label_scale", v).apply(); bump() }
    var smallLabels: Boolean
        get() = b("small_labels", true)
        set(v) { put("small_labels", v); bump() }
    /** Colour of text and glyphs on the tiles. */
    var tileFg: Int
        get() = sp.getInt("tile_fg", Color.WHITE)
        set(v) { sp.edit().putInt("tile_fg", v or 0xFF000000.toInt()).apply(); bump() }
    /** Extra time zones on the clock tile, TimeZone ids separated by ','. */
    var clockZones: List<String>
        get() = sp.getString("clock_zones", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        set(v) = sp.edit().putString("clock_zones", v.joinToString(",")).apply()
    var bgUri: String?
        get() = sp.getString("bg_uri", null)
        set(v) { sp.edit().putString("bg_uri", v).apply(); bump() }
    var parallax: Boolean
        get() = b("parallax", true)
        set(v) = put("parallax", v)
    /** Darkening over the background image, 0..100. */
    var bgDim: Int
        get() = sp.getInt("bg_dim", 25)
        set(v) { sp.edit().putInt("bg_dim", v).apply(); bump() }
    /** Album/video covers from media sessions on the music and video app tiles. */
    var covers: Boolean
        get() = b("covers", true)
        set(v) = put("covers", v)
    var cityPhoto: Boolean
        get() = b("city_photo", true)
        set(v) = put("city_photo", v)
    /** Manual weather city; null = current position. */
    var weatherCity: String?
        get() = sp.getString("w_city", null)
        set(v) = sp.edit().putString("w_city", v).apply()
    var weatherLat: Float
        get() = sp.getFloat("w_lat", 0f)
        set(v) = sp.edit().putFloat("w_lat", v).apply()
    var weatherLon: Float
        get() = sp.getFloat("w_lon", 0f)
        set(v) = sp.edit().putFloat("w_lon", v).apply()
    /** Photo tile change interval in seconds; 0 = random (5–30 s). */
    var photoInterval: Int
        get() = sp.getInt("photo_interval", 0)
        set(v) = sp.edit().putInt("photo_interval", v).apply()
    var animations: Boolean
        get() = b("animations", true)
        set(v) = put("animations", v)
    var splash: Boolean
        get() = b("splash", true)
        set(v) = put("splash", v)
    var navBar: Boolean
        get() = b("nav_bar", false)
        set(v) = put("nav_bar", v)
    var askedHome: Boolean
        get() = b("asked_home", false)
        set(v) = put("asked_home", v)
    var askedPerms: Boolean
        get() = b("asked_perms", false)
        set(v) = put("asked_perms", v)
    var tilesJson: String?
        get() = sp.getString("tiles", null)
        set(v) = sp.edit().putString("tiles", v).apply()

    var kbVibrate: Boolean
        get() = b("kb_vibrate", true)
        set(v) = put("kb_vibrate", v)
    var kbSound: Boolean
        get() = b("kb_sound", true)
        set(v) = put("kb_sound", v)
    var kbSuggest: Boolean
        get() = b("kb_suggest", true)
        set(v) = put("kb_suggest", v)
    var kbAutoCap: Boolean
        get() = b("kb_autocap", true)
        set(v) = put("kb_autocap", v)
    var kbDoubleSpace: Boolean
        get() = b("kb_double_space", true)
        set(v) = put("kb_double_space", v)

    /** Folder on the update server holding version.json and the APK. */
    var updateUrl: String
        get() = sp.getString("update_url", null) ?: "https://github.com/mcaruga/metro-ui-download/releases/latest/download/"
        set(v) = sp.edit().putString("update_url", v).apply()
    var autoUpdate: Boolean
        get() = b("auto_update", true)
        set(v) = put("auto_update", v)
    /** Highest version already announced with a notification (one notification per version). */
    var notifiedUpdate: Int
        get() = sp.getInt("notified_update", 0)
        set(v) = sp.edit().putInt("notified_update", v).apply()
    var askedNotif: Boolean
        get() = b("asked_notif", false)
        set(v) = put("asked_notif", v)
    var lastUpdateCheck: Long
        get() = sp.getLong("update_checked", 0L)
        set(v) = sp.edit().putLong("update_checked", v).apply()

    /** Width of the start grid in small-tile units. */
    val units: Int get() = if (moreTiles) 6 else 4
}

object Metro {
    val ACCENTS = intArrayOf(
        0xFFA4C400.toInt(), 0xFF60A917.toInt(), 0xFF008A00.toInt(), 0xFF00ABA9.toInt(),
        0xFF1BA1E2.toInt(), 0xFF0050EF.toInt(), 0xFF6A00FF.toInt(), 0xFFAA00FF.toInt(),
        0xFFF472D0.toInt(), 0xFFD80073.toInt(), 0xFFA20025.toInt(), 0xFFE51400.toInt(),
        0xFFFA6800.toInt(), 0xFFF0A30A.toInt(), 0xFFE3C800.toInt(), 0xFF825A2C.toInt(),
        0xFF6D8764.toInt(), 0xFF647687.toInt(), 0xFF76608A.toInt(), 0xFF87794E.toInt(),
    )
    val NAMES = arrayOf(
        "lime", "verde", "smeraldo", "verde acqua", "ciano", "cobalto", "indaco", "viola",
        "rosa", "magenta", "cremisi", "rosso", "arancione", "ambra", "giallo", "marrone",
        "oliva", "acciaio", "malva", "tortora",
    )

    lateinit var light: Typeface
    lateinit var regular: Typeface
    lateinit var semibold: Typeface

    fun init(c: Context) {
        // Drop Selawik (OFL, Segoe-like) into assets/fonts/ to use it; otherwise system sans.
        light = asset(c, "fonts/selawkl.ttf") ?: asset(c, "fonts/selawksl.ttf")
            ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
        regular = asset(c, "fonts/selawk.ttf") ?: Typeface.create("sans-serif", Typeface.NORMAL)
        semibold = asset(c, "fonts/selawksb.ttf") ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun asset(c: Context, path: String): Typeface? =
        try { Typeface.createFromAsset(c.assets, path) } catch (e: Exception) { null }

    val bg get() = if (Prefs.light) Color.WHITE else Color.BLACK
    val fg get() = if (Prefs.light) Color.BLACK else Color.WHITE
    val sub get() = if (Prefs.light) 0xFF717171.toInt() else 0xFF9A9A9A.toInt()
    val chrome get() = if (Prefs.light) 0xFFE2E2E2.toInt() else 0xFF222222.toInt()
    val scrim get() = if (Prefs.light) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()

    fun accentName(c: Int): String {
        val i = ACCENTS.indexOf(c or 0xFF000000.toInt())
        return if (i >= 0) NAMES[i] else "personalizzato " + hex(c)
    }

    /** Tile text/icon colours: neutrals first, then the accents. */
    val FG_PRESETS = intArrayOf(Color.WHITE, Color.BLACK, 0xFFDADADA.toInt(), 0xFF3A3A3A.toInt()) + ACCENTS
    val FG_NAMES = arrayOf("bianco", "nero", "grigio chiaro", "grigio scuro") + NAMES

    fun fgName(c: Int): String {
        val i = FG_PRESETS.indexOf(c or 0xFF000000.toInt())
        return if (i >= 0) FG_NAMES[i] else "personalizzato " + hex(c)
    }

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
}
