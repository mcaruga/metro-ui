package com.mc.metro.kb

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.inputmethod.ExtractedTextRequest
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.mc.metro.Prefs
import com.mc.metro.SettingsActivity

class MetroIme : InputMethodService() {
    private lateinit var kv: KeyboardView
    private val composing = StringBuilder()
    private var mode = ABC
    private var emojiCat = 1
    private var emojiPage = 0
    private var clipPage = 0
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { grabClip() }
    private val clearStatus = Runnable { if (::kv.isInitialized) kv.status = null }
    private var suggestOn = true
    private var autoCap = true
    private var autoShifted = false
    private var numeric = false
    private var phone = false
    private var lastSpace = 0L
    private var lastShift = 0L
    private var mid = Key("☺", K.EMOJI, fn = true)

    companion object {
        private const val ABC = 0
        private const val SYM1 = 1
        private const val SYM2 = 2
        private const val EMOJI = 3
        private const val NUM = 4
        private const val CLIP = 5
        private const val EDIT = 6
        private const val TRANS = 7
    }

    override fun onCreate() {
        super.onCreate()
        KbSound.init(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    /** The system bar under the keyboard (gesture handle, hide and switch buttons) in the keyboard's colour. */
    private fun paintNavBar() {
        val w = window?.window ?: return
        // One UI draws its own bar under the keyboard over the window background: paint both.
        w.decorView.setBackgroundColor(kv.bgColor)
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(kv.bgColor))
        w.navigationBarColor = kv.bgColor
        w.isNavigationBarContrastEnforced = false
        val flag = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        w.insetsController?.setSystemBarsAppearance(if (Prefs.light) flag else 0, flag)
    }

    /** Keeps copied text for the clipboard panel; clips marked sensitive (passwords) are skipped. */
    private fun grabClip() {
        runCatching {
            val clip = clipboard?.primaryClip ?: return
            if (clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
            val t = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
            Clips.add(t)
        }
    }

    override fun onCreateInputView(): View {
        kv = KeyboardView(this, this)
        setLayout()
        return kv
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.clear()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME
        phone = cls == InputType.TYPE_CLASS_PHONE
        val text = cls == InputType.TYPE_CLASS_TEXT
        val password = text && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val email = text && (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        val uri = text && v == InputType.TYPE_TEXT_VARIATION_URI
        val noSuggest = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        suggestOn = Prefs.kbSuggest && text && !password && !email && !uri && !noSuggest
        autoCap = Prefs.kbAutoCap && text && !password && !email && !uri
        mid = when {
            email -> Key("@", '@'.code)
            uri -> Key("/", '/'.code, pops = ":#?=")
            else -> Key("☺", K.EMOJI, fn = true)
        }
        mode = if (numeric || phone) NUM else ABC
        kv.status = null
        kv.applyTheme()
        paintNavBar()
        kv.enterLabel = enterLabel(info)
        kv.shift = 0
        autoShifted = false
        setLayout()
        kv.suggestions = emptyList()
        updateShift()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        finishComposing()
        Words.save(this)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oss: Int, ose: Int, nss: Int, nse: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce)
        if (composing.isNotEmpty() && (nss != ce || nse != ce)) {
            composing.clear()
            currentInputConnection?.finishComposingText()
            kv.suggestions = emptyList()
        }
        if (composing.isEmpty()) updateShift()
    }

    private fun enterLabel(info: EditorInfo): String {
        if ((info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) return "↵"
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "vai"
            EditorInfo.IME_ACTION_SEARCH -> "cerca"
            EditorInfo.IME_ACTION_SEND -> "invia"
            EditorInfo.IME_ACTION_NEXT -> "avanti"
            EditorInfo.IME_ACTION_DONE -> "fine"
            EditorInfo.IME_ACTION_PREVIOUS -> "indietro"
            else -> "↵"
        }
    }

    private fun setLayout() {
        if (!::kv.isInitialized) return
        kv.letterMode = mode == ABC
        kv.rows = when (mode) {
            SYM1 -> Layouts.sym1(mid)
            SYM2 -> Layouts.sym2(mid)
            EMOJI -> Layouts.emoji(emojiCat, emojiPage)
            NUM -> Layouts.numpad(phone)
            CLIP -> Layouts.clips(Clips.list(), clipPage)
            EDIT -> Layouts.edit()
            TRANS -> Layouts.translate(Prefs.kbLang)
            else -> Layouts.letters(mid, Prefs.kbNumberRow, Prefs.kbHints, Prefs.kbEmojiRow && suggestOn)
        }
        kv.activeTool = when (mode) { EMOJI -> 0; CLIP -> 1; EDIT -> 2; TRANS -> 3; else -> -1 }
    }

    private fun updateShift() {
        if (!::kv.isInitialized || kv.shift == 2 || mode != ABC) return
        val ic = currentInputConnection ?: return
        val caps = autoCap && ic.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) != 0
        if (caps) {
            kv.shift = 1
            autoShifted = true
        } else if (autoShifted) {
            kv.shift = 0
            autoShifted = false
        }
    }

    fun onKey(k: Key) {
        currentInputConnection ?: return
        when (k.code) {
            K.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                autoShifted = false
                kv.shift = when {
                    kv.shift == 2 -> 0
                    kv.shift == 1 && now - lastShift < 400 -> 2
                    kv.shift == 1 -> 0
                    else -> 1
                }
                lastShift = now
            }
            K.DEL -> backspace()
            K.ENTER -> enter()
            K.SYM -> { mode = SYM1; setLayout() }
            K.SYM2 -> { mode = SYM2; setLayout() }
            K.ABC -> { mode = ABC; setLayout(); updateShift() }
            K.EMOJI -> openEmoji()
            K.EMOJI_CAT -> { emojiCat = k.output.toInt(); emojiPage = 0; setLayout() }
            K.PREV, K.NEXT -> page(if (k.code == K.NEXT) 1 else -1)
            K.CLIP -> { finishComposing(); currentInputConnection?.commitText(k.output, 1) }
            K.CLIP_CLEAR -> { Clips.clear(); clipPage = 0; setLayout() }
            K.LANG -> translate(k.output)
            K.SELECT_ALL -> context(android.R.id.selectAll)
            K.COPY -> context(android.R.id.copy)
            K.CUT -> context(android.R.id.cut)
            K.PASTE -> context(android.R.id.paste)
            K.HOME -> jump(false)
            K.END -> jump(true)
            K.LEFT -> moveCursor(-1)
            K.RIGHT -> moveCursor(1)
            K.SEL_LEFT -> extend(-1)
            K.SEL_RIGHT -> extend(1)
            K.UNDO -> ctrl(KeyEvent.KEYCODE_Z, false)
            K.REDO -> ctrl(KeyEvent.KEYCODE_Z, true)
            K.SPACE -> space()
            else -> {
                if (k.code == 0) Emoji.addRecent(k.output)
                typeText(if (kv.shift > 0 && mode == ABC) k.output.uppercase() else k.output)
            }
        }
    }

    /** Toolbar taps; tapping the open panel again goes back to the letters. */
    fun onTool(i: Int) {
        val target = when (i) { 0 -> EMOJI; 1 -> CLIP; 2 -> EDIT; 3 -> TRANS; else -> -1 }
        when {
            i == 4 -> startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            i == 5 -> requestHideSelf(0)
            target == mode -> { mode = ABC; setLayout(); updateShift() }
            target == EMOJI -> openEmoji()
            target == CLIP -> { finishComposing(); grabClip(); clipPage = 0; mode = CLIP; setLayout() }
            else -> { finishComposing(); mode = target; setLayout() }
        }
    }

    private fun openEmoji() {
        finishComposing()
        emojiCat = if (Emoji.recent().isEmpty()) 1 else 0
        emojiPage = 0
        mode = EMOJI
        setLayout()
    }

    /** ◀ ▶ in the emoji and clipboard panels; emoji pages run on into the next category. */
    private fun page(d: Int) {
        if (mode == CLIP) {
            val pages = (Clips.list().size + Layouts.CLIP_ROWS - 1) / Layouts.CLIP_ROWS
            if (pages > 0) clipPage = (clipPage + d + pages) % pages
        } else if (mode == EMOJI) {
            val per = Layouts.EMOJI_COLS * Layouts.EMOJI_ROWS
            fun pages(c: Int) = ((Emoji.category(c).size + per - 1) / per).coerceAtLeast(1)
            emojiPage += d
            if (emojiPage >= pages(emojiCat)) {
                emojiCat = (emojiCat + 1) % Emoji.ICONS.size
                emojiPage = 0
            } else if (emojiPage < 0) {
                emojiCat = (emojiCat - 1 + Emoji.ICONS.size) % Emoji.ICONS.size
                emojiPage = pages(emojiCat) - 1
            }
        }
        setLayout()
    }

    private fun context(id: Int) {
        finishComposing()
        currentInputConnection?.performContextMenuAction(id)
    }

    private fun extracted() = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)

    private fun jump(end: Boolean) {
        finishComposing()
        val ic = currentInputConnection ?: return
        val pos = if (end) extracted()?.text?.length ?: return else 0
        ic.setSelection(pos, pos)
    }

    /** Grows or shrinks the selection by one character from its moving end. */
    private fun extend(d: Int) {
        finishComposing()
        val ic = currentInputConnection ?: return
        val t = extracted() ?: return
        val len = t.text?.length ?: return
        val end = (t.selectionEnd + d).coerceIn(0, len)
        ic.setSelection(t.selectionStart, end)
    }

    private fun ctrl(code: Int, shift: Boolean) {
        finishComposing()
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or (if (shift) KeyEvent.META_SHIFT_ON else 0)
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** Translates the selection, or the whole field, and replaces it with the result. */
    private fun translate(to: String) {
        finishComposing()
        val ic = currentInputConnection ?: return
        Prefs.kbLang = to
        setLayout()
        val sel = ic.getSelectedText(0)?.toString()
        val whole = extracted()?.text?.toString()
        val src = (if (!sel.isNullOrBlank()) sel else whole)?.take(1500)
        if (src.isNullOrBlank()) {
            flash("scrivi o seleziona un testo da tradurre")
            return
        }
        kv.removeCallbacks(clearStatus)
        kv.status = "traduco in " + Translate.name(to) + "…"
        Translate.run(src, to) { out ->
            val c = currentInputConnection
            if (out == null || c == null) {
                flash("traduzione non riuscita: controlla la connessione")
                return@run
            }
            if (sel.isNullOrBlank()) c.setSelection(0, whole?.length ?: 0)
            c.commitText(out, 1)
            flash("tradotto in " + Translate.name(to))
            mode = ABC
            setLayout()
        }
    }

    private fun flash(msg: String) {
        kv.removeCallbacks(clearStatus)
        kv.status = msg
        kv.postDelayed(clearStatus, 2500)
    }

    fun typeText(s: String) {
        val ic = currentInputConnection ?: return
        val word = s.isNotEmpty() && s.codePoints().allMatch { Character.isLetterOrDigit(it) }
        if (suggestOn && word) {
            composing.append(s)
            ic.setComposingText(composing, 1)
            updateSuggestions()
        } else {
            finishComposing()
            ic.commitText(s, 1)
            kv.suggestions = emptyList()
        }
        if (kv.shift == 1) {
            kv.shift = 0
            autoShifted = false
        }
        lastSpace = 0
    }

    private fun space() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (Prefs.kbDoubleSpace && composing.isEmpty() && now - lastSpace < 600) {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && Character.isLetterOrDigit(before[0])) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpace = 0
                return
            }
        }
        finishComposing()
        ic.commitText(" ", 1)
        lastSpace = now
        kv.suggestions = emptyList()
        if (mode == SYM1 || mode == SYM2) { mode = ABC; setLayout() }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
            updateSuggestions()
            return
        }
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        finishComposing()
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multi = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!multi && !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendKeyChar('\n')
        }
    }

    private fun finishComposing() {
        if (composing.isEmpty()) return
        Words.learn(this, composing.toString())
        currentInputConnection?.finishComposingText()
        composing.clear()
    }

    private fun updateSuggestions() {
        kv.suggestions = if (suggestOn && composing.isNotEmpty()) Words.suggest(this, composing.toString(), 3) else emptyList()
    }

    fun pickSuggestion(i: Int) {
        val w = kv.suggestions.getOrNull(i) ?: return
        val ic = currentInputConnection ?: return
        composing.clear()
        composing.append(w)
        ic.setComposingText(w, 1)
        finishComposing()
        ic.commitText(" ", 1)
        kv.suggestions = emptyList()
        lastSpace = 0
    }

    fun moveCursor(dir: Int) {
        finishComposing()
        sendDownUpKeyEvents(if (dir > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
    }

    fun showPicker() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    fun capsLock() {
        autoShifted = false
        kv.shift = 2
    }
}
