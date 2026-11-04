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
    /** Small symbol drawn in the corner; a long press types it. */
    val hint: String = "",
    /** Selected state (current emoji category, last translation language). */
    val on: Boolean = false,
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
    const val PREV = -8
    const val NEXT = -9
    const val EMOJI_CAT = -10
    const val CLIP = -11
    const val CLIP_CLEAR = -12
    const val LANG = -13
    const val SELECT_ALL = -20
    const val COPY = -21
    const val CUT = -22
    const val PASTE = -23
    const val HOME = -24
    const val END = -25
    const val LEFT = -26
    const val RIGHT = -27
    const val UNDO = -28
    const val REDO = -29
    const val SEL_LEFT = -30
    const val SEL_RIGHT = -31
    const val NONE = -98
    const val SPACER = -99
    const val SPACE = 32
}

object Layouts {
    private val pops = mapOf(
        'e' to "èéêë", 'y' to "ý", 'u' to "ùúûü", 'i' to "ìíîï", 'o' to "òóôöøœ",
        'a' to "àáâäãåæ", 's' to "ßš", 'd' to "ð", 'c' to "çćč", 'n' to "ñń", 'z' to "žź", 'l' to "ł", 'g' to "ğ",
        '"' to "“”«»", '\'' to "‘’`", '-' to "–—_", '?' to "¿", '!' to "¡", '€' to "$£¥¢",
        '(' to "[{<", ')' to "]}>", '%' to "‰", '*' to "★†", '/' to "\\", '&' to "§",
        '1' to "¹½⅓¼", '2' to "²⅔", '3' to "³¾", '0' to "°∅",
    )

    /** Symbol on each letter, as on the Samsung keyboard: a long press types it. */
    private val hints = mapOf(
        'q' to "+", 'w' to "-", 'e' to "€", 'r' to "÷", 't' to "/", 'y' to "=", 'u' to "<", 'i' to ">", 'o' to "[", 'p' to "]",
        'a' to "@", 's' to "$", 'd' to "#", 'f' to "%", 'g' to "^", 'h' to "&", 'j' to "*", 'k' to "(", 'l' to ")",
        'z' to "_", 'x' to "'", 'c' to "\"", 'v' to ":", 'b' to ";", 'n' to "!", 'm' to "?",
    )
    private const val DIGITS = "1234567890"

    private fun keys(s: String) = s.map { Key(it.toString(), it.code, pops = pops[it] ?: "") }
    private fun spacer(w: Float) = Key("", K.SPACER, w)
    private fun del(w: Float = 1.5f) = Key("⌫", K.DEL, w, true)
    private fun abc(w: Float = 1.5f) = Key("abc", K.ABC, w, true)
    private fun enter(w: Float = 1.5f) = Key("↵", K.ENTER, w, true)

    /** Letters; without the number row the top row's long press also gives the digit. */
    private fun letterKeys(s: String, numberRow: Boolean, showHints: Boolean) = s.map { ch ->
        val digit = if (!numberRow && s.startsWith("q")) DIGITS.getOrNull(s.indexOf(ch))?.toString().orEmpty() else ""
        val h = hints[ch].orEmpty()
        Key(ch.toString(), ch.code, pops = h + digit + (pops[ch] ?: ""), hint = if (showHints) h else "")
    }

    private fun bottom(mid: Key, mode: Key) = listOf(
        mode, mid,
        Key(",", ','.code, pops = ";:'\"!?"),
        Key("", K.SPACE, 4f),
        Key(".", '.'.code, pops = "?!…-@"),
        enter(),
    )

    fun letters(mid: Key, numberRow: Boolean, showHints: Boolean, emojiRow: Boolean): List<List<Key>> =
        (if (emojiRow) listOf(Emoji.row(10).map { Key(it, 0, text = it) }) else emptyList()) +
        (if (numberRow) listOf(keys(DIGITS)) else emptyList()) + listOf(
            letterKeys("qwertyuiop", numberRow, showHints),
            listOf(spacer(0.5f)) + letterKeys("asdfghjkl", numberRow, showHints) + spacer(0.5f),
            listOf(Key("⇧", K.SHIFT, 1.5f, true)) + letterKeys("zxcvbnm", numberRow, showHints) + del(),
            bottom(mid, Key("!#1", K.SYM, 1.5f, true)),
        )

    fun sym1(mid: Key): List<List<Key>> = listOf(
        keys("1234567890"),
        keys("@#€%&*-+()"),
        listOf(Key("1/2", K.SYM2, 1.5f, true)) + keys("!\"':;/?") + del(),
        bottom(mid, abc()),
    )

    fun sym2(mid: Key): List<List<Key>> = listOf(
        keys("~`|•√π÷×{}"),
        keys("£¥$¢^°=_[]"),
        listOf(Key("2/2", K.SYM, 1.5f, true)) + keys("\\©®™✓<>") + del(),
        bottom(mid, abc()),
    )

    const val EMOJI_COLS = 8
    const val EMOJI_ROWS = 3

    /** Three rows of emoji, the category tabs, then abc · ◀ · space · ▶ · delete. */
    fun emoji(cat: Int, page: Int): List<List<Key>> {
        val all = Emoji.category(cat)
        val per = EMOJI_COLS * EMOJI_ROWS
        val shown = all.drop(page * per).take(per)
        val grid = List(EMOJI_ROWS) { r ->
            List(EMOJI_COLS) { c ->
                val e = shown.getOrNull(r * EMOJI_COLS + c)
                if (e == null) spacer(1f) else Key(e, 0, text = e)
            }
        }
        val empty = cat == 0 && all.isEmpty()
        val rows = if (empty) listOf(listOf(Key("le emoji usate di recente appariranno qui", K.NONE, 8f))) + grid.drop(1) else grid
        val tabs = Emoji.ICONS.mapIndexed { i, ic -> Key(ic, K.EMOJI_CAT, text = i.toString(), on = i == cat) }
        return rows + listOf(tabs) + listOf(
            listOf(abc(), Key("◀", K.PREV, 1.2f, true), Key("", K.SPACE, 4f), Key("▶", K.NEXT, 1.2f, true), del())
        )
    }

    const val CLIP_ROWS = 3

    fun clips(list: List<String>, page: Int): List<List<Key>> {
        val shown = list.drop(page * CLIP_ROWS).take(CLIP_ROWS)
        val rows = if (shown.isEmpty()) {
            listOf(listOf(Key("nessun testo copiato: quello che copi appare qui", K.NONE, 10f)))
        } else {
            shown.map { listOf(Key(it.replace('\n', ' '), K.CLIP, 10f, text = it)) }
        }
        val pages = (list.size + CLIP_ROWS - 1) / CLIP_ROWS
        return rows + List(CLIP_ROWS - rows.size) { listOf(spacer(10f)) } + listOf(
            listOf(
                abc(2f),
                Key("◀", K.PREV, 1.5f, true),
                Key(if (pages > 1) "${page + 1}/$pages" else "", K.NONE, 1.5f, true),
                Key("▶", K.NEXT, 1.5f, true),
                Key("svuota", K.CLIP_CLEAR, 2f, true),
                del(1.5f),
            )
        )
    }

    fun edit(): List<List<Key>> {
        fun e(l: String, c: Int) = Key(l, c, 2.5f, true)
        return listOf(
            listOf(e("tutto", K.SELECT_ALL), e("copia", K.COPY), e("taglia", K.CUT), e("incolla", K.PASTE)),
            listOf(e("inizio", K.HOME), e("◀", K.LEFT), e("▶", K.RIGHT), e("fine", K.END)),
            listOf(e("annulla", K.UNDO), e("ripeti", K.REDO), e("◀ sel.", K.SEL_LEFT), e("sel. ▶", K.SEL_RIGHT)),
            listOf(abc(3f), del(3.5f), enter(3.5f)),
        )
    }

    fun translate(last: String): List<List<Key>> {
        val langs = Translate.LANGS.map { (code, name) -> Key(name, K.LANG, 2.5f, text = code, on = code == last) }
        return langs.chunked(4) + listOf(listOf(abc(3f), del(3.5f), enter(3.5f)))
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
