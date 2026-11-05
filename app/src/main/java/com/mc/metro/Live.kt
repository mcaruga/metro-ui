package com.mc.metro

import android.Manifest
import android.app.Notification
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Size
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** Data behind the live tiles: calendar, photos, weather. Loaded off the main thread. */
object Live {
    val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newFixedThreadPool(2)
    private val listeners = LinkedHashSet<() -> Unit>()

    class Event(val title: String, val begin: Long, val end: Long, val location: String?, val allDay: Boolean)
    class Weather(
        val temp: Int, val code: Int, val max: Int, val min: Int,
        val tMax: Int, val tMin: Int, val tCode: Int, val city: String?,
        val isDay: Boolean = true,
        val at: Long = System.currentTimeMillis(),
    ) {
        fun toJson(): String = JSONObject()
            .put("t", temp).put("c", code).put("mx", max).put("mn", min)
            .put("tx", tMax).put("tn", tMin).put("tc", tCode).put("city", city).put("d", isDay).put("at", at)
            .toString()

        companion object {
            fun fromJson(s: String): Weather? = runCatching {
                val j = JSONObject(s)
                Weather(
                    j.getInt("t"), j.getInt("c"), j.getInt("mx"), j.getInt("mn"),
                    j.getInt("tx"), j.getInt("tn"), j.getInt("tc"), j.optString("city").ifEmpty { null },
                    j.optBoolean("d", true), j.getLong("at"),
                )
            }.getOrNull()
        }
    }

    @Volatile var events: List<Event> = emptyList()
    @Volatile var photos: List<Uri> = emptyList()
    @Volatile var weather: Weather? = null
    @Volatile var cityPhoto: Bitmap? = null
    private var cityPhotoName: String? = null
    /** True while the start screen is visible: weather animation runs only then. */
    @Volatile var fx = false
    @Volatile private var lastWeather = 0L

    fun addListener(f: () -> Unit) { listeners += f }
    fun removeListener(f: () -> Unit) { listeners -= f }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    fun has(c: Context, perm: String) = c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    val photoPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Last forecast from disk, so the tile has a temperature before (or without) the network. */
    private fun restoreWeather() {
        if (weather != null) return
        val w = Prefs.weatherCache?.let { Weather.fromJson(it) } ?: return
        if (System.currentTimeMillis() - w.at < 12 * 60 * 60_000L) weather = w
    }

    fun refresh(c: Context, forceWeather: Boolean = false) {
        val app = c.applicationContext
        restoreWeather()
        bg.execute {
            loadCalendar(app)
            loadPhotos(app)
            changed()
        }
        val now = System.currentTimeMillis()
        if (forceWeather || now - lastWeather > 30 * 60_000L) {
            lastWeather = now
            bg.execute { loadWeather(app) }
        }
    }

    private fun loadCalendar(c: Context) {
        if (!has(c, Manifest.permission.READ_CALENDAR)) {
            events = emptyList()
            return
        }
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - 86_400_000L)
        ContentUris.appendId(b, now + 7 * 86_400_000L)
        val out = ArrayList<Event>()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY,
        )
        try {
            c.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                while (cur.moveToNext() && out.size < 3) {
                    val end = cur.getLong(2)
                    if (end < now) continue
                    val title = cur.getString(0)?.takeIf { it.isNotBlank() } ?: "(senza titolo)"
                    out += Event(title, cur.getLong(1), end, cur.getString(3)?.takeIf { it.isNotBlank() }, cur.getInt(4) == 1)
                }
            }
        } catch (e: Exception) {
        }
        events = out
    }

    private fun loadPhotos(c: Context) {
        if (!has(c, photoPerm)) {
            photos = emptyList()
            return
        }
        val out = ArrayList<Uri>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        try {
            c.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
                while (cur.moveToNext() && out.size < 5000) out += ContentUris.withAppendedId(uri, cur.getLong(0))
            }
        } catch (e: Exception) {
        }
        photos = out
    }

    /** A random photo scaled to about w x h, with its MediaStore uri (to open it on tap). */
    fun loadPhoto(c: Context, w: Int, h: Int, cb: (Uri?, Bitmap?) -> Unit) {
        val list = photos
        if (list.isEmpty()) {
            cb(null, null)
            return
        }
        val uri = list.random()
        val s = max(w, h).coerceAtLeast(64)
        bg.execute {
            val b = try { c.contentResolver.loadThumbnail(uri, Size(s, s), null) } catch (e: Exception) { null }
            main.post { cb(uri, b) }
        }
    }

    fun hasLocation(c: Context) = has(c, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Where to forecast without needing GPS: the last position the phone already knows (no
     * request, nothing switched on), else an approximate one from the internet connection.
     */
    private fun place(c: Context): Pair<Location, String?>? {
        location(c)?.let { return it to null }
        ipPlace()?.let { return it }
        // Offline or blocked: the last place that worked.
        if (Prefs.lastPlaceLat != 0f || Prefs.lastPlaceLon != 0f) {
            return Location("saved").apply { latitude = Prefs.lastPlaceLat.toDouble(); longitude = Prefs.lastPlaceLon.toDouble() } to Prefs.lastPlaceCity
        }
        return null
    }

    /** City-level position from the public IP (no permission, no GPS). */
    private fun ipPlace(): Pair<Location, String?>? {
        val sources = listOf(
            "https://ipwho.is/?fields=success,city,latitude,longitude" to { j: JSONObject -> j.optBoolean("success", false) },
            "https://ipapi.co/json/" to { j: JSONObject -> !j.optBoolean("error", false) },
        )
        for ((url, ok) in sources) {
            try {
                val j = JSONObject(http(url))
                if (!ok(j) || !j.has("latitude")) continue
                val l = Location("ip").apply { latitude = j.getDouble("latitude"); longitude = j.getDouble("longitude") }
                return l to j.optString("city").ifEmpty { null }
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun location(c: Context): Location? {
        if (!hasLocation(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        if (!lm.isLocationEnabled) return null
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, "fused", LocationManager.GPS_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                val b = best
                if (l != null && (b == null || l.time > b.time)) best = l
            } catch (e: Exception) {
            }
        }
        // Older than 3 hours: the phone may have moved, prefer the network position.
        if (best != null && System.currentTimeMillis() - best!!.time > 3 * 60 * 60_000L) best = null
        if (best == null) {
            val latch = CountDownLatch(1)
            var got: Location? = null
            try {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, { it.run() }) { l ->
                    got = l
                    latch.countDown()
                }
                latch.await(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
            }
            best = got
        }
        return best
    }

    /** Fetches the forecast now. Network: call off the main thread (the background job does). */
    fun loadWeather(c: Context) {
        val manual = Prefs.weatherCity
        var placeCity: String? = null
        val loc = if (manual != null) {
            Location("manual").apply { latitude = Prefs.weatherLat.toDouble(); longitude = Prefs.weatherLon.toDouble() }
        } else {
            place(c)?.let { placeCity = it.second; it.first }
        }
        if (loc == null) {
            lastWeather = 0
            changed()
            return
        }
        try {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2",
                    loc.latitude, loc.longitude,
                )
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val txt = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
            val j = JSONObject(txt)
            val cur = j.getJSONObject("current")
            val d = j.getJSONObject("daily")
            val mx = d.getJSONArray("temperature_2m_max")
            val mn = d.getJSONArray("temperature_2m_min")
            val wc = d.getJSONArray("weather_code")
            val city = manual ?: try {
                @Suppress("DEPRECATION")
                Geocoder(c, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            } catch (e: Exception) {
                null
            } ?: placeCity
            weather = Weather(
                cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"),
                mx.getDouble(0).roundToInt(), mn.getDouble(0).roundToInt(),
                mx.getDouble(1).roundToInt(), mn.getDouble(1).roundToInt(), wc.getInt(1), city,
                cur.optInt("is_day", 1) == 1,
            )
            lastWeather = System.currentTimeMillis()
            Prefs.weatherCache = weather!!.toJson()
            if (manual == null) {
                Prefs.lastPlaceLat = loc.latitude.toFloat()
                Prefs.lastPlaceLon = loc.longitude.toFloat()
                Prefs.lastPlaceCity = city
            }
            changed()
            if (Prefs.cityPhoto && city != null) loadCityPhoto(c, city)
        } catch (e: Exception) {
            lastWeather = 0
        }
    }

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "MetroLauncher/${BuildConfig.VERSION_NAME} (personal Android launcher)")
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }

    /** City photo for the weather tile: the lead image of the city's Wikipedia page, cached on disk. */
    private fun loadCityPhoto(c: Context, city: String) {
        if (city == cityPhotoName && cityPhoto != null) return
        val f = java.io.File(c.cacheDir, "city_" + city.lowercase().replace(Regex("[^a-z0-9]"), "_") + ".jpg")
        try {
            if (!f.exists()) {
                val lang = if (Locale.getDefault().language == "it") "it" else "en"
                val title = java.net.URLEncoder.encode(city.replace(' ', '_'), "UTF-8")
                val j = JSONObject(http("https://$lang.wikipedia.org/api/rest_v1/page/summary/$title"))
                val src = j.optJSONObject("originalimage")?.optString("source")?.takeIf { it.isNotEmpty() }
                    ?: j.optJSONObject("thumbnail")?.optString("source") ?: return
                val conn = URL(src).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "MetroLauncher/${BuildConfig.VERSION_NAME} (personal Android launcher)")
                conn.inputStream.use { inp -> java.io.FileOutputStream(f).use { inp.copyTo(it) } }
                conn.disconnect()
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
            opts.inSampleSize = max(1, opts.outWidth / 1100)
            opts.inJustDecodeBounds = false
            val b = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts) ?: return
            cityPhoto = b
            cityPhotoName = city
            changed()
        } catch (e: Exception) {
            f.delete()
        }
    }

    /** Looks a city up by name (Open-Meteo geocoding). Returns name, lat, lon or null. */
    fun geocode(name: String): Triple<String, Double, Double>? = try {
        val q = java.net.URLEncoder.encode(name.trim(), "UTF-8")
        val lang = Locale.getDefault().language
        val r = JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=$lang"))
            .optJSONArray("results")?.optJSONObject(0)
        if (r == null) null else Triple(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    } catch (e: Exception) {
        null
    }

    fun clearCityPhoto() {
        cityPhoto = null
        cityPhotoName = null
    }

    fun wmo(code: Int): String = when (code) {
        0 -> "sereno"
        1 -> "poco nuvoloso"
        2 -> "parzialmente nuvoloso"
        3 -> "nuvoloso"
        45, 48 -> "nebbia"
        in 51..57 -> "pioviggine"
        in 61..67 -> "pioggia"
        in 71..77 -> "neve"
        in 80..82 -> "rovesci"
        85, 86 -> "rovesci di neve"
        in 95..99 -> "temporale"
        else -> ""
    }

    fun wmoGlyph(code: Int): String = when {
        code <= 1 -> "☀"
        code == 2 -> "⛅"
        code == 3 || code == 45 || code == 48 -> "☁"
        code in 71..77 || code == 85 || code == 86 -> "❄"
        code >= 95 -> "⚡"
        else -> "☂"
    } + "︎"
}

/** Per-app notification counts and the latest message, for badges and tile back faces. */
object Notifs {
    class Info(val title: String, val text: String)

    val counts = HashMap<String, Int>()
    val last = HashMap<String, Info>()
    @Volatile var connected = false
    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(f: () -> Unit) { listeners += f }
    fun removeListener(f: () -> Unit) { listeners -= f }

    fun set(list: Array<StatusBarNotification>?) {
        val cnt = HashMap<String, Int>()
        val lst = HashMap<String, Info>()
        val time = HashMap<String, Long>()
        list?.forEach { s ->
            val n = s.notification ?: return@forEach
            if (s.isOngoing || !s.isClearable) return@forEach
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return@forEach
            val p = s.packageName
            cnt[p] = (cnt[p] ?: 0) + 1
            if (s.postTime >= (time[p] ?: 0L)) {
                time[p] = s.postTime
                val ex = n.extras
                val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (ex?.getCharSequence(Notification.EXTRA_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
                if (title.isNotEmpty() || text.isNotEmpty()) lst[p] = Info(title, text)
            }
        }
        Live.main.post {
            counts.clear(); counts.putAll(cnt)
            last.clear(); last.putAll(lst)
            listeners.toList().forEach { it() }
        }
    }
}

class NotifService : NotificationListenerService() {
    companion object {
        /** The connected listener, used by the action center to list and dismiss notifications. */
        @Volatile var instance: NotifService? = null
    }

    override fun onListenerConnected() {
        Notifs.connected = true
        instance = this
        Media.init(this)
        push()
        watch()
    }

    override fun onListenerDisconnected() {
        Notifs.connected = false
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        push()
        watch()
    }

    private var lastWatch = 0L

    /**
     * The listener is rebound on its own when Metro's process is restarted, the accessibility
     * service not always: it keeps an eye on it while the user is in other apps.
     */
    private fun watch() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWatch < 30_000 || !NavBarService.stuck(this)) return
        lastWatch = now
        // Give a service that is just binding time to connect before switching it off and on.
        Live.main.postDelayed({ runCatching { NavBarService.check(applicationContext) } }, 8_000)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = push()

    private fun push() {
        try { Notifs.set(activeNotifications) } catch (e: Exception) { }
    }
}

/** Hourly forecast refresh in the background, so the weather tile is current when Start opens. */
class WeatherJob : android.app.job.JobService() {
    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        Thread {
            runCatching { Live.loadWeather(applicationContext) }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: android.app.job.JobParameters) = true

    companion object {
        private const val JOB_ID = 4202

        fun schedule(c: Context) {
            val js = c.getSystemService(android.app.job.JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                android.app.job.JobInfo.Builder(JOB_ID, android.content.ComponentName(c, WeatherJob::class.java))
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60_000L, 15 * 60_000L)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
