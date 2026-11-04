package com.mc.metro.kb

import android.content.Context
import android.graphics.RectF
import java.io.File

class Key(
    val label: String,
    val code: Int = 0,
    val w: Float = 1f,
    val fn: Boolean = false,
    val pops: String = "",
    val text: String? = null,
) {
    val rect = RectF()
    val output: String get() = text ?: label
}

object K {
    const val SHIFT = -1
    const val SYM = -2
    const val ABC = -3
    const val ENTER = -4
    const val DEL = -5
    const val SYM2 = -6
    const val EMOJI = -7
    const val EMOJI_PAGE = -8
    const val SPACER = -99
    const val SPACE = 32
}

object Layouts {
    private val pops = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3èéêë€", 'r' to "4", 't' to "5", 'y' to "6ý", 'u' to "7ùúûü",
        'i' to "8ìíîï", 'o' to "9òóôöøœ", 'p' to "0", 'a' to "àáâäãåæ", 's' to "ßš", 'd' to "ð",
        'c' to "çćč", 'n' to "ñń", 'z' to "žź", 'l' to "ł", 'g' to "ğ",
        '"' to "“”«»", '\'' to "‘’`", '-' to "–—_", '?' to "¿", '!' to "¡", '€' to "$£¥¢",
        '(' to "[{<", ')' to "]}>", '%' to "‰", '*' to "★†", '/' to "\\", '&' to "§",
    )

    private fun keys(s: String) = s.map { Key(it.toString(), it.code, pops = pops[it] ?: "") }
    private fun spacer(w: Float) = Key("", K.SPACER, w)
    private fun del() = Key("⌫", K.DEL, 1.5f, true)

    private fun bottom(mid: Key, mode: Key) = listOf(
        mode, mid,
        Key(",", ','.code, pops = ";:'\"!?"),
        Key("", K.SPACE, 4f),
        Key(".", '.'.code, pops = "?!…-@"),
        Key("↵", K.ENTER, 1.5f, true),
    )

    fun letters(mid: Key): List<List<Key>> = listOf(
        keys("qwertyuiop"),
        listOf(spacer(0.5f)) + keys("asdfghjkl") + spacer(0.5f),
        listOf(Key("⇧", K.SHIFT, 1.5f, true)) + keys("zxcvbnm") + del(),
        bottom(mid, Key("&123", K.SYM, 1.5f, true)),
    )

    fun sym1(mid: Key): List<List<Key>> = listOf(
        keys("1234567890"),
        keys("@#€%&*-+()"),
        listOf(Key("1/2", K.SYM2, 1.5f, true)) + keys("!\"':;/?") + del(),
        bottom(mid, Key("abc", K.ABC, 1.5f, true)),
    )

    fun sym2(mid: Key): List<List<Key>> = listOf(
        keys("~`|•√π÷×{}"),
        keys("£¥$¢^°=_[]"),
        listOf(Key("2/2", K.SYM, 1.5f, true)) + keys("\\©®™✓<>") + del(),
        bottom(mid, Key("abc", K.ABC, 1.5f, true)),
    )

    private val EMOJI = listOf(
        "😀 😂 😊 😍 😘 😉 😎 🤔 😅 😢",
        "😭 😡 😱 😴 🙄 🤗 🥳 😇 🙈 🤦",
        "👍 👎 👏 🙏 💪 👋 🤞 👌 ✌️ 🤝",
        "❤️ 💔 🔥 🎉 ✨ 💯 ⭐ 🌹 🍕 ☕",
        "🎂 🎁 ⚽ 🚗 ✈️ 🏠 📷 🎵 💰 📱",
        "🐶 🐱 🌞 🌙 🌧️ ❄️ 🌊 🍀 🍷 🍺",
    ).map { r -> r.split(' ').map { Key(it, 0, text = it) } }

    fun emoji(page: Int): List<List<Key>> {
        val start = if (page == 0) 0 else 3
        return EMOJI.subList(start, start + 3) + listOf(
            listOf(
                Key("abc", K.ABC, 1.5f, true),
                Key(if (page == 0) "1/2" else "2/2", K.EMOJI_PAGE, 1.5f, true),
                Key("", K.SPACE, 4f),
                del(),
                Key("↵", K.ENTER, 1.5f, true),
            )
        )
    }

    fun numpad(phone: Boolean): List<List<Key>> {
        fun n(s: String, pops: String = "") = Key(s, s[0].code, 2.5f, pops = pops)
        return listOf(
            listOf(n("1"), n("2"), n("3"), if (phone) n("+", "*#") else n("-", "+*/=")),
            listOf(n("4"), n("5"), n("6"), Key("␣", K.SPACE, 2.5f, true)),
            listOf(n("7"), n("8"), n("9"), Key("⌫", K.DEL, 2.5f, true)),
            listOf(if (phone) n("*", "#(),;N/") else n(",", ":/"), n("0", if (phone) "+" else ""), n(".", ","), Key("↵", K.ENTER, 2.5f, true)),
        )
    }
}

/** Tiny prefix dictionary: common Italian words plus whatever the user types (stored locally). */
object Words {
    private val base: List<String> by lazy { BASE.trim().split(Regex("\\s+")) }
    private var learned: HashMap<String, Int>? = null
    private var dirty = 0

    private fun map(c: Context): HashMap<String, Int> {
        learned?.let { return it }
        val m = HashMap<String, Int>()
        try {
            File(c.filesDir, "words.txt").takeIf { it.exists() }?.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 2) m[p[0]] = p[1].toIntOrNull() ?: 1
            }
        } catch (e: Exception) {
        }
        learned = m
        return m
    }

    fun learn(c: Context, word: String) {
        if (word.length < 2 || word.any { it.isDigit() }) return
        val m = map(c)
        val k = word.lowercase()
        m[k] = (m[k] ?: 0) + 1
        if (++dirty >= 8) save(c)
    }

    fun save(c: Context) {
        val m = learned ?: return
        if (dirty == 0) return
        dirty = 0
        val snapshot = m.entries.sortedByDescending { it.value }.take(5000).joinToString("\n") { "${it.key}\t${it.value}" }
        Thread {
            try { File(c.filesDir, "words.txt").writeText(snapshot) } catch (e: Exception) { }
        }.start()
    }

    fun suggest(c: Context, prefix: String, n: Int): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        val scored = HashMap<String, Int>()
        base.forEachIndexed { i, w -> if (w.length > p.length && w.startsWith(p)) scored[w] = 1000 - i }
        map(c).forEach { (w, cnt) -> if (w.length > p.length && w.startsWith(p)) scored[w] = (scored[w] ?: 0) + 300 * cnt }
        return scored.entries.sortedByDescending { it.value }.take(n).map { matchCase(prefix, it.key) }
    }

    private fun matchCase(prefix: String, w: String) = when {
        prefix.length > 1 && prefix.all { it.isUpperCase() } -> w.uppercase()
        prefix[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
        else -> w
    }

    private const val BASE = """
        di che non il la per una sono mi ma lo ha le si con ti cosa io come da ci questo qui hai bene del tu
        se tutto me più al anche lei lui sei va mio era fare suo bisogno molto ora sì quando chi solo dove
        perché niente nel così grazie della allora sua dei voglio questa fatto essere detto stato posso
        prima vuoi casa già tempo dire signore fa ancora sempre uno due tre adesso noi loro dopo cosa
        oggi domani ieri stasera stamattina settimana mese anno giorno sera notte mattina ciao buongiorno
        buonasera buonanotte arrivo arrivederci scusa scusami prego certo perfetto ok va bene tranquillo
        amore tesoro mamma papà fratello sorella amico amica amici lavoro ufficio scuola università
        andiamo vieni vengo vado andare venire fammi sapere dimmi chiamami chiamo ti chiamo appena
        possibile momento minuti ore orario appuntamento riunione pranzo cena colazione caffè birra
        pizza mangiare bere dormire uscire tornare restare partire viaggio treno macchina auto aereo
        stazione aeroporto strada via piazza centro città paese posto luogo dentro fuori sopra sotto
        vicino lontano qualcosa qualcuno nessuno ognuno ogni alcuni tanti tante poco troppo meno
        davvero veramente forse magari comunque quindi infatti invece però cioè ovvero oppure inoltre
        bello bella belli belle brutto grande piccolo nuovo vecchio buono buona ottimo ottima migliore
        peggio facile difficile importante interessante possibile impossibile necessario sicuro sicura
        felice contento contenta stanco stanca triste arrabbiato preoccupato pronto pronta libero libera
        abbiamo avete hanno avevo aveva avevamo avrei avrebbe sarà sarebbe sarei siamo siete erano
        faccio fai facciamo fanno farò farei dico dici diciamo dicono vedo vedi vediamo vedere visto
        sento senti sentire penso pensi pensare credo credi credere so sai sappiamo sapere conosco
        capisco capito capire trovo trovato trovare prendo prendi prendere preso metto mettere messo
        lascia lasciare parlo parli parlare parliamo scrivo scrivi scrivere scritto leggo leggere letto
        aspetto aspetta aspettare aiuto aiutare grazie mille tantissimo tanto tanti auguri complimenti
        compleanno natale pasqua vacanze weekend fine inizio problema soluzione domanda risposta idea
        notizia notizie messaggio messaggi telefono numero email foto video musica film libro gioco
        partita squadra calcio palestra spesa negozio supermercato farmacia ospedale medico dottore
        soldi euro prezzo costo pagare pagato comprare comprato vendere bisogna serve servono
        lunedì martedì mercoledì giovedì venerdì sabato domenica gennaio febbraio marzo aprile maggio
        giugno luglio agosto settembre ottobre novembre dicembre primavera estate autunno inverno
        caldo freddo pioggia sole neve tempo meteo acqua mare montagna lago spiaggia
        quello quella quelli quelle questi queste altro altra altri altre stesso stessa proprio
        nostro nostra vostro vostra loro mia miei mie tuo tua tuoi tue suoi sue
        sotto senza contro verso fino durante mentre appena subito presto tardi spesso mai
        nella nelle negli nei dalla dalle dagli dai sulla sulle sugli sui alla alle agli ai degli delle
        perché però più può già così città cioè là lì giù virtù
        """
}
