package com.mc.metro

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Web and store searches offered under the matching apps in the app list search. */
object WebSearch {
    class Engine(val name: String, val short: String, val url: String)

    val ENGINES = listOf(
        Engine("Google", "Google", "https://www.google.com/search?q="),
        Engine("Bing", "Bing", "https://www.bing.com/search?q="),
        Engine("DuckDuckGo", "Duck", "https://duckduckgo.com/?q="),
        Engine("Ecosia", "Ecosia", "https://www.ecosia.org/search?q="),
        Engine("Brave", "Brave", "https://search.brave.com/search?q="),
    )

    fun engine() = ENGINES.getOrElse(Prefs.searchEngine) { ENGINES[0] }

    class Row(val query: String, val where: String, val glyph: Int, private val uri: (String) -> String) {
        fun intent(c: Context): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri(Uri.encode(query))))
    }

    fun rows(q: String): List<Row> {
        if (q.isEmpty()) return emptyList()
        val e = engine()
        return listOf(
            Row(q, "cerca su ${e.name}", Glyphs.SEARCH) { e.url + it },
            Row(q, "cerca nel Play Store", Glyphs.SEARCH) { "market://search?q=$it" },
        )
    }
}
