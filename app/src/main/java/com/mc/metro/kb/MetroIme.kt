package com.mc.metro.kb

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.mc.metro.NavBarService
import com.mc.metro.Prefs

class MetroIme : InputMethodService() {
    private lateinit var kv: KeyboardView
    private val composing = StringBuilder()
    private var mode = ABC
    private var emojiPage = 0
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
    }

    override fun onCreate() {
        super.onCreate()
        KbSound.init(this)
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
        kv.applyTheme()
        kv.enterLabel = enterLabel(info)
        kv.extraBottom = if (Prefs.navBar && NavBarService.running) NavBarService.height else 0
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
            EMOJI -> Layouts.emoji(emojiPage)
            NUM -> Layouts.numpad(phone)
            else -> Layouts.letters(mid)
        }
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
            K.EMOJI -> { finishComposing(); mode = EMOJI; setLayout() }
            K.EMOJI_PAGE -> { emojiPage = 1 - emojiPage; setLayout() }
            K.SPACE -> space()
            else -> typeText(if (kv.shift > 0 && mode == ABC) k.output.uppercase() else k.output)
        }
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
