package com.mc.metro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

enum class TSize(val w: Int, val h: Int) {
    SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2), LARGE(4, 4)
}

class Tile(val kind: String, val app: String?, var size: TSize, var color: Int = 0) {
    val isLive get() = kind != APP

    fun nextSize(): TSize = when (size) {
        TSize.SMALL -> TSize.MEDIUM
        TSize.MEDIUM -> TSize.WIDE
        TSize.WIDE -> if (isLive) TSize.LARGE else TSize.SMALL
        TSize.LARGE -> TSize.SMALL
    }

    fun toJson(): JSONObject = JSONObject().put("k", kind).put("a", app).put("s", size.name).put("c", color)

    companion object {
        const val APP = "app"
        const val CLOCK = "clock"
        const val CALENDAR = "calendar"
        const val PHOTOS = "photos"
        const val WEATHER = "weather"
        /** Blank spacer that lets the background image show through. */
        const val EMPTY = "empty"

        fun fromJson(o: JSONObject) = Tile(
            o.getString("k"),
            o.optString("a").ifEmpty { null },
            runCatching { TSize.valueOf(o.getString("s")) }.getOrDefault(TSize.MEDIUM),
            o.optInt("c"),
        )

        fun liveLabel(kind: String) = when (kind) {
            CLOCK -> "orologio"
            CALENDAR -> "calendario"
            PHOTOS -> "foto"
            WEATHER -> "meteo"
            EMPTY -> "vuoto"
            else -> ""
        }
    }
}

object TileStore {
    val tiles = ArrayList<Tile>()
    /** Bumped on every structural change so the launcher knows to rebuild. */
    var version = 0
        private set
    private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val s = Prefs.tilesJson
        if (s != null) {
            runCatching {
                val a = JSONArray(s)
                for (i in 0 until a.length()) tiles += Tile.fromJson(a.getJSONObject(i))
            }
        } else {
            tiles += defaults(c)
            save()
        }
    }

    fun save() {
        val a = JSONArray()
        tiles.forEach { a.put(it.toJson()) }
        Prefs.tilesJson = a.toString()
        version++
    }

    fun reset(c: Context) {
        tiles.clear()
        tiles += defaults(c)
        save()
    }

    fun isPinned(appKey: String) = tiles.any { it.app == appKey }
    fun hasLive(kind: String) = tiles.any { it.kind == kind }

    fun pin(t: Tile) {
        tiles += t
        save()
    }

    fun unpinApp(appKey: String) {
        tiles.removeAll { it.app == appKey }
        save()
    }

    /** The app a live tile opens on tap (and whose glyph it shows). */
    fun liveApp(c: Context, kind: String): AppEntry? = when (kind) {
        Tile.CLOCK -> Apps.resolve(c, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            ?: Apps.byPkg("com.sec.android.app.clockpackage")
        Tile.CALENDAR -> Apps.resolve(c, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            ?: Apps.byPkg("com.samsung.android.calendar")
        Tile.PHOTOS -> Apps.resolve(c, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
            ?: Apps.byPkg("com.sec.android.gallery3d") ?: Apps.byPkg("com.google.android.apps.photos")
        Tile.WEATHER -> Apps.byPkg("com.sec.android.daemonapp") ?: Apps.byPkg("com.google.android.apps.weather")
        else -> null
    }

    private fun defaults(c: Context): List<Tile> {
        val out = ArrayList<Tile>()
        fun app(e: AppEntry?, s: TSize) {
            if (e != null && out.none { it.app == e.key }) out += Tile(Tile.APP, e.key, s)
        }
        app(Apps.resolve(c, Intent(Intent.ACTION_DIAL)) ?: Apps.byPkg("com.samsung.android.dialer"), TSize.MEDIUM)
        app(Apps.byPkg(Telephony.Sms.getDefaultSmsPackage(c)), TSize.MEDIUM)
        out += Tile(Tile.CLOCK, null, TSize.WIDE)
        out += Tile(Tile.PHOTOS, null, TSize.MEDIUM)
        out += Tile(Tile.CALENDAR, null, TSize.MEDIUM)
        out += Tile(Tile.WEATHER, null, TSize.WIDE)
        app(
            Apps.resolve(c, Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")))
                ?: Apps.byPkg("com.android.chrome") ?: Apps.byPkg("com.sec.android.app.sbrowser"),
            TSize.SMALL,
        )
        app(Apps.resolve(c, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) ?: Apps.byPkg("com.sec.android.app.camera"), TSize.SMALL)
        app(Apps.resolve(c, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))) ?: Apps.byPkg("com.google.android.gm"), TSize.SMALL)
        app(Apps.byPkg("com.android.vending"), TSize.SMALL)
        app(Apps.byPkg("com.whatsapp"), TSize.MEDIUM)
        app(Apps.byPkg("com.google.android.apps.maps"), TSize.MEDIUM)
        app(Apps.byPkg("com.android.settings") ?: Apps.resolve(c, Intent(Settings.ACTION_SETTINGS)), TSize.SMALL)
        app(Apps.all.firstOrNull { it.pkg == c.packageName }, TSize.SMALL)
        return out
    }
}
