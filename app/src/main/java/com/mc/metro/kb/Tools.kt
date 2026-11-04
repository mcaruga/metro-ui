package com.mc.metro.kb

import android.os.Handler
import android.os.Looper
import com.mc.metro.BuildConfig
import com.mc.metro.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Clipboard history kept by the keyboard (text only, never clips marked sensitive). */
object Clips {
    private const val MAX = 20

    fun list(): List<String> = runCatching {
        val a = JSONArray(Prefs.kbClips ?: "[]")
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    fun add(s: String) {
        val t = s.take(2000)
        if (t.isBlank()) return
        val l = listOf(t) + list().filter { it != t }
        Prefs.kbClips = JSONArray(l.take(MAX)).toString()
    }

    fun clear() {
        Prefs.kbClips = null
    }
}

/** Emoji by category; category 0 is the recently used ones. */
object Emoji {
    val ICONS = listOf("🕘", "😀", "👋", "❤️", "🐶", "🍕", "⚽", "💡", "🔣")
    private const val RECENT_MAX = 24

    private val CATS = listOf(
        "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 🙈 🙉 🙊",
        "👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 💪 🦵 🦶 👂 👃 🧠 👀 👅 👄 💋 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙋 🙆 🙅 🤷 🤦 🙇 💁 👮 👷 🤰 👫 👪 💑 💏 🏃 🚶 💃 🕺",
        "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 💌 💯 💢 💥 💫 💦 💨 💬 💭 💤 ✨ 🌟 ⭐ 🔥 🎉 🎊 🎈",
        "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐢 🐍 🐙 🦀 🐠 🐬 🐳 🦈 🐘 🦒 🐪 🐄 🐖 🐑 🐐 🐕 🐈 🌵 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂 🌷 🌹 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌙 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ 🌈 🌊 💧 🌍",
        "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥕 🌽 🌶️ 🥔 🧄 🧅 🥐 🍞 🥖 🧀 🥚 🍳 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍣 🍱 🍤 🍚 🍙 🍦 🍰 🎂 🧁 🍫 🍬 🍭 🍩 🍪 ☕ 🍵 🥤 🧃 🍺 🍻 🥂 🍷 🍸 🍹 🍾",
        "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 ⛳ 🎣 🏊 🚴 🏋️ 🧘 🎿 🏂 🏆 🥇 🥈 🥉 🏅 🎫 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎸 🎮 🎲 🧩 🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚚 🚲 🛵 🏍️ 🚂 🚆 🚇 ✈️ 🛫 🛬 🚀 🛳️ ⛵ ⚓ ⛽ 🚦 🗺️ 🗼 🏰 🎡 🏖️ 🏝️ ⛰️ 🏔️ 🏕️ 🏠 🏡 🏢 🏥 🏦 🏨 🏫 ⛪",
        "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 📷 📸 📹 🎥 📞 ☎️ 📺 📻 ⏰ ⌛ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💶 💳 💎 ⚖️ 🔧 🔨 🛠️ 🔩 ⚙️ 🛡️ 💊 💉 🩹 🌡️ 🧹 🧺 🧻 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🎁 🎀 🛒 📦 ✉️ 📩 📝 📁 📅 📌 📎 ✂️ 🔒 🔓 🔔 📚 📖 ✏️ 🖊️",
        "✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ❓ ❗ ‼️ ⁉️ ♻️ ⚠️ 🚫 ⛔ 📵 🆗 🆕 🆒 🆓 🔝 🔜 ▶️ ⏸️ ⏹️ ⏭️ ⏮️ 🔀 🔁 ⬆️ ⬇️ ⬅️ ➡️ ↩️ ↪️ 🔄 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟩 🟦 ⬛ ⬜ 🔶 🔷 ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🏳️ 🏴 🏁 🇮🇹 🇪🇺 🇨🇭 🇬🇧 🇺🇸 🇫🇷 🇩🇪 🇪🇸",
    ).map { it.split(' ') }

    fun recent(): List<String> = Prefs.kbRecentEmoji?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()

    fun addRecent(e: String) {
        val l = listOf(e) + recent().filter { it != e }
        Prefs.kbRecentEmoji = l.take(RECENT_MAX).joinToString(" ")
    }

    fun category(i: Int): List<String> = if (i == 0) recent() else CATS.getOrElse(i - 1) { emptyList() }
}

/** Online translation of the field's text, only when the user asks for it. */
object Translate {
    val LANGS = listOf(
        "en" to "inglese", "it" to "italiano", "es" to "spagnolo", "fr" to "francese",
        "de" to "tedesco", "pt" to "portoghese", "ro" to "rumeno", "zh-CN" to "cinese",
    )
    private val main = Handler(Looper.getMainLooper())

    fun name(code: String) = LANGS.firstOrNull { it.first == code }?.second ?: code

    fun run(text: String, to: String, done: (String?) -> Unit) {
        Thread {
            val r = runCatching { google(text, to) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { myMemory(text, to) }.getOrNull()?.takeIf { it.isNotBlank() }
            main.post { done(r) }
        }.start()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Metro/" + BuildConfig.VERSION_NAME)
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun google(text: String, to: String): String {
        val a = JSONArray(get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$to&dt=t&q=" + q(text)))
        val parts = a.getJSONArray(0)
        return buildString { for (i in 0 until parts.length()) append(parts.getJSONArray(i).optString(0)) }
    }

    private fun myMemory(text: String, to: String): String {
        val from = if (to == "it") "en" else "it"
        val o = JSONObject(get("https://api.mymemory.translated.net/get?langpair=$from|$to&q=" + q(text)))
        return o.getJSONObject("responseData").getString("translatedText")
    }
}
