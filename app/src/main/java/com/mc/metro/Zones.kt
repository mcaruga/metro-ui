package com.mc.metro

import android.content.Context
import android.icu.text.TimeZoneNames
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Extra time zones for the clock tile: localized city names, times and offsets. */
object Zones {
    const val MAX = 4
    private val names by lazy { TimeZoneNames.getInstance(Locale.getDefault()) }
    private val regions = listOf("Africa/", "America/", "Antarctica/", "Asia/", "Atlantic/", "Australia/", "Europe/", "Indian/", "Pacific/")

    fun name(id: String): String =
        runCatching { names.getExemplarLocationName(id) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: id.substringAfterLast('/').replace('_', ' ')

    fun time(c: Context, id: String): String {
        val f = DateFormat.getTimeFormat(c)
        f.timeZone = TimeZone.getTimeZone(id)
        return f.format(Date())
    }

    /** "domani · +6 h", "oggi · -5 h 30", "oggi · stessa ora". */
    fun relative(id: String): String {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getTimeZone(id)
        val diff = (tz.getOffset(now) - TimeZone.getDefault().getOffset(now)) / 60_000
        val off = if (diff == 0) "stessa ora" else {
            val h = abs(diff) / 60
            val m = abs(diff) % 60
            (if (diff > 0) "+" else "-") + "$h h" + if (m != 0) " $m" else ""
        }
        val here = Calendar.getInstance()
        val there = Calendar.getInstance(tz)
        val d = (there.get(Calendar.YEAR) * 400 + there.get(Calendar.DAY_OF_YEAR)) -
            (here.get(Calendar.YEAR) * 400 + here.get(Calendar.DAY_OF_YEAR))
        val day = when {
            d > 0 -> "domani"
            d < 0 -> "ieri"
            else -> "oggi"
        }
        return "$day · $off"
    }

    /** Every city time zone as (id, name), sorted by name, one entry per name. */
    val all: List<Pair<String, String>> by lazy {
        TimeZone.getAvailableIDs()
            .filter { id -> regions.any { id.startsWith(it) } }
            .map { it to name(it) }
            .distinctBy { it.second }
            .sortedBy { Apps.normalize(it.second) }
    }
}
