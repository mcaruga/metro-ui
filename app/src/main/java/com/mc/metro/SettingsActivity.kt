package com.mc.metro

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Metro-styled settings page ("impostazioni"). Built in code, like the rest of the UI. */
class SettingsActivity : Activity() {
    companion object {
        private const val PICK_BG = 7
    }

    private lateinit var col: LinearLayout
    private val refreshers = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    private fun build() {
        refreshers.clear()
        window.decorView.setBackgroundColor(Metro.bg)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { ic ->
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            ic.setSystemBarsAppearance(if (Prefs.light) light else 0, light)
        }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 0, 24.dp, 0)
        }
        val sv = ScrollView(this).apply {
            setBackgroundColor(Metro.bg)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(col)
        }
        sv.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(0, b.top + 12.dp, 0, b.bottom + 24.dp)
            v.post { currentFocus?.let { f -> (v as ScrollView).requestChildRectangleOnScreen(f, android.graphics.Rect(0, 0, f.width, f.height), false) } }
            ins
        }
        setContentView(sv)

        col.addView(label("METRO", 14f, Metro.fg, Metro.semibold))
        col.addView(label("impostazioni", 54f).apply { setPadding(0, 0, 0, 12.dp) })

        header("colori")
        accentRow()
        colorRow("colore scritte e icone", "nomi, testi e icone sui riquadri", Prefs.tileFg) {
            ColorDialog(this, "scritte e icone", Prefs.tileFg, Metro.FG_PRESETS, Metro.FG_NAMES) { c -> Prefs.tileFg = c; rebuild() }.show()
        }
        toggle("tema chiaro", "sfondo bianco al posto del nero", { Prefs.light }) { Prefs.light = it; rebuild() }

        header("sfondo start")
        button("immagine attraverso i riquadri", { if (Prefs.bgUri != null) "tocca per cambiarla" else "nessuna · tocca per sceglierne una" }) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            startActivityForResult(i, PICK_BG)
        }
        if (Prefs.bgUri != null) {
            button("rimuovi immagine", { "torna ai riquadri a tinta unita" }) {
                Prefs.bgUri?.let { u ->
                    try { contentResolver.releasePersistableUriPermission(android.net.Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
                }
                Prefs.bgUri = null
                rebuild()
            }
            toggle("parallasse", "l'immagine scorre più lenta dei riquadri", { Prefs.parallax }) { Prefs.parallax = it }
            choice("oscuramento", listOf("0%" to 0f, "15%" to 15f, "25%" to 25f, "40%" to 40f, "60%" to 60f), { Prefs.bgDim.toFloat() }) { Prefs.bgDim = it.toInt() }
        }

        header("dimensioni")
        val scales = listOf("80%" to 0.8f, "90%" to 0.9f, "100%" to 1f, "115%" to 1.15f, "130%" to 1.3f)
        choice("dimensione icone", scales, { Prefs.iconScale }) { Prefs.iconScale = it }
        choice("dimensione testo", scales, { Prefs.textScale }) { Prefs.textScale = it }
        choice(
            "dimensione nomi app",
            listOf("1" to 1f, "2" to 1.25f, "3" to 1.5f, "4" to 1.75f, "5" to 2f),
            { Prefs.labelScale },
        ) { Prefs.labelScale = it }
        col.addView(label("nomi più grandi sui riquadri e nella lista app, per leggere meglio", 14f, Metro.sub, Metro.regular).apply { maxLines = 3 })
        toggle("nomi sui riquadri piccoli", "mostra il nome dell'app anche sui riquadri più piccoli", { Prefs.smallLabels }) { Prefs.smallLabels = it }

        header("start")
        toggle("mostra più riquadri", "tre colonne di riquadri medi invece di due", { Prefs.moreTiles }) { Prefs.moreTiles = it }
        toggle("icone a colori nella lista app", "i riquadri di start usano sempre icone flat bianche", { Prefs.listColor }) { Prefs.listColor = it }
        toggle("animazioni", "tilt al tocco, riquadri che girano, transizioni", { Prefs.animations }) { Prefs.animations = it }
        toggle("schermata di avvio app", "splash colorato quando apri un'app", { Prefs.splash }) { Prefs.splash = it }
        button("imposta come schermata home", { if (HomeRole.isDefault(this)) "Metro è la home predefinita" else "non attiva: il gesto home apre One UI · tocca per scegliere Metro" }) {
            if (HomeRole.isDefault(this)) startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) else HomeRole.request(this)
        }
        button("ripristina riquadri", { "torna alla disposizione iniziale" }) {
            TileStore.reset(this)
            Toast.makeText(this, "riquadri ripristinati", Toast.LENGTH_SHORT).show()
        }

        header("riquadri live")
        button("permessi", { permSummary() }) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Live.photoPerm, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        button("contatori notifiche", { if (notifEnabled()) "attivati" else "tocca per concedere l'accesso alle notifiche" }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        toggle("copertine musica e video", "i riquadri delle app musica/video mostrano la copertina in riproduzione (serve l'accesso alle notifiche)", { Prefs.covers }) { Prefs.covers = it }
        choice("cambio foto", listOf("5 s" to 5f, "15 s" to 15f, "1 min" to 60f, "5 min" to 300f, "casuale" to 0f), { Prefs.photoInterval.toFloat() }) { Prefs.photoInterval = it.toInt() }
        button("fusi orari orologio", { Prefs.clockZones.joinToString(", ") { Zones.name(it) }.ifEmpty { "nessuno · tocca per aggiungere città" } }) { zonesDialog() }
        button("città meteo", { Prefs.weatherCity ?: "posizione attuale" }) { cityDialog() }
        toggle("foto della città sul meteo", "immagine della città da Wikipedia con l'animazione del tempo", { Prefs.cityPhoto }) {
            Prefs.cityPhoto = it
            Live.refresh(this, true)
        }
        addLiveRow()

        header("navigazione")
        toggle(
            "barra windows phone",
            "indietro · start · cerca in fondo allo schermo. Lascia disattivato per usare le gesture di One UI.",
            { Prefs.navBar },
        ) {
            Prefs.navBar = it
            if (it && !NavBarService.isEnabled(this)) {
                Toast.makeText(this, "attiva \"Metro barra\" in Servizi installati", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        header("tastiera")
        button("attiva tastiera metro", { if (imeEnabled()) "attivata" else "tocca e abilita \"Metro tastiera\"" }) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        button("scegli tastiera", { "passa a Metro tastiera" }) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        toggle("suggerimenti parole", null, { Prefs.kbSuggest }) { Prefs.kbSuggest = it }
        toggle("maiuscola automatica", null, { Prefs.kbAutoCap }) { Prefs.kbAutoCap = it }
        toggle("punto con doppio spazio", null, { Prefs.kbDoubleSpace }) { Prefs.kbDoubleSpace = it }
        toggle("vibrazione tasti", null, { Prefs.kbVibrate }) { Prefs.kbVibrate = it }
        toggle("suono tasti", null, { Prefs.kbSound }) { Prefs.kbSound = it }
        val test = EditText(this).apply {
            hint = "prova la tastiera qui"
            textSize = 18f
            setTextColor(Metro.bg)
            setHintTextColor(Metro.sub)
            background = ColorDrawable(Metro.fg)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        col.addView(test, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp })

        header("aggiornamenti")
        button("cerca aggiornamenti", { updateStatus ?: "versione ${BuildConfig.VERSION_NAME} · tocca per controllare" }) { checkUpdate() }
        toggle("controlla automaticamente", "dalla schermata start, ogni 3 ore", { Prefs.autoUpdate }) { Prefs.autoUpdate = it }
        button("server aggiornamenti", { Prefs.updateUrl }) { urlDialog() }

        header("info")
        col.addView(label("Metro ${BuildConfig.VERSION_NAME} · launcher, riquadri live e tastiera in stile Windows Phone", 15f, Metro.sub, Metro.regular).apply { maxLines = 3 })
    }

    private var updateStatus: String? = null

    private fun checkUpdate() {
        updateStatus = "controllo…"
        refreshers.forEach { it() }
        Thread {
            val res = try { Result.success(Updater.check()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                val r = res.getOrNull()
                updateStatus = when {
                    res.isFailure -> "server non raggiungibile (${res.exceptionOrNull()?.message})"
                    r == null -> "Metro è aggiornato (${BuildConfig.VERSION_NAME})"
                    else -> "nuova versione ${r.name} disponibile"
                }
                refreshers.forEach { it() }
                if (r != null && !isFinishing) Updater.offer(this, r)
            }
        }.start()
    }

    private fun urlDialog() {
        val d = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        d.window?.setBackgroundDrawable(ColorDrawable(Metro.scrim))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Metro.chrome)
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        box.addView(label("server aggiornamenti", 28f))
        box.addView(label("cartella con version.json e l'APK: GitHub Releases o il tuo server. HTTP solo per 192.168.1.18, altrove serve HTTPS.", 14f, Metro.sub, Metro.regular).apply { maxLines = 3 })
        val et = EditText(this).apply {
            setText(Prefs.updateUrl)
            textSize = 16f
            setTextColor(Metro.bg)
            background = ColorDrawable(Metro.fg)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp })
        val ok = label("salva", 17f, Color.WHITE, Metro.regular).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Prefs.accent)
            setOnClickListener {
                val u = et.text.toString().trim()
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    Prefs.updateUrl = u
                    updateStatus = null
                    refreshers.forEach { it() }
                    d.dismiss()
                } else {
                    Toast.makeText(this@SettingsActivity, "l'indirizzo deve iniziare con http:// o https://", Toast.LENGTH_SHORT).show()
                }
            }
        }
        box.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp).apply { topMargin = 16.dp })
        val wrap = FrameLayout(this)
        wrap.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = 120.dp })
        wrap.setOnClickListener { d.dismiss() }
        d.setContentView(wrap)
        d.show()
    }

    private fun rebuild() {
        val y = (col.parent as? ScrollView)?.scrollY ?: 0
        build()
        (col.parent as? ScrollView)?.post { (col.parent as? ScrollView)?.scrollTo(0, y) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK_BG && resultCode == RESULT_OK && uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            Prefs.bgUri = uri.toString()
            rebuild()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Live.refresh(this, true)
        refreshers.forEach { it() }
    }

    // ---- state helpers -----------------------------------------------------------------------

    private fun permSummary(): String {
        val parts = listOf(
            "calendario" to Live.has(this, Manifest.permission.READ_CALENDAR),
            "foto" to Live.has(this, Live.photoPerm),
            "posizione" to Live.hasLocation(this),
        )
        val missing = parts.filter { !it.second }.map { it.first }
        return if (missing.isEmpty()) "tutti concessi" else "mancano: " + missing.joinToString(", ")
    }

    private fun notifEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun imeEnabled(): Boolean =
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.any { it.packageName == packageName } == true

    private fun cityDialog() {
        val d = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        d.window?.setBackgroundDrawable(ColorDrawable(Metro.scrim))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Metro.chrome)
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        box.addView(label("città meteo", 28f))
        val et = EditText(this).apply {
            hint = "es. Milano"
            setText(Prefs.weatherCity ?: "")
            textSize = 18f
            setTextColor(Metro.bg)
            setHintTextColor(Metro.sub)
            background = ColorDrawable(Metro.fg)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp })
        val msg = label("", 14f, Metro.sub, Metro.regular)
        box.addView(msg)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16.dp, 0, 0) }
        fun btn(t: String, f: () -> Unit) = label(t, 17f, Metro.fg, Metro.regular).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Metro.bg)
            setOnClickListener { f() }
        }
        row.addView(btn("cerca") {
            val q = et.text.toString().trim()
            if (q.isEmpty()) return@btn
            msg.text = "ricerca…"
            Thread {
                val r = Live.geocode(q)
                runOnUiThread {
                    if (r == null) {
                        msg.text = "città non trovata"
                    } else {
                        Prefs.weatherCity = r.first
                        Prefs.weatherLat = r.second.toFloat()
                        Prefs.weatherLon = r.third.toFloat()
                        Live.clearCityPhoto()
                        Live.refresh(this, true)
                        refreshers.forEach { it() }
                        d.dismiss()
                    }
                }
            }.start()
        }, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { rightMargin = 8.dp })
        row.addView(btn("usa posizione") {
            Prefs.weatherCity = null
            Live.clearCityPhoto()
            Live.refresh(this, true)
            refreshers.forEach { it() }
            d.dismiss()
        }, LinearLayout.LayoutParams(0, 48.dp, 1f))
        box.addView(row)
        val wrap = FrameLayout(this)
        wrap.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = 120.dp })
        wrap.setOnClickListener { d.dismiss() }
        d.setContentView(wrap)
        d.show()
    }

    // ---- row builders ------------------------------------------------------------------------

    private fun header(t: String) {
        col.addView(label(t, 30f).apply { setPadding(0, 28.dp, 0, 6.dp) })
    }

    private fun toggle(title: String, desc: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10.dp, 0, 10.dp) }
        box.addView(label(title, 20f, Metro.fg, Metro.regular))
        if (desc != null) box.addView(label(desc, 14f, Metro.sub, Metro.regular).apply { maxLines = 3 })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val state = label("", 17f, Metro.sub, Metro.regular)
        val t = MetroToggle(this)
        row.addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(t)
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
        fun sync() {
            t.set(get())
            state.text = if (get()) "Attivato" else "Disattivato"
        }
        t.onChange = { v ->
            set(v)
            state.text = if (v) "Attivato" else "Disattivato"
        }
        box.setOnClickListener { t.performClick() }
        sync()
        refreshers += { if (t.checked != get()) sync() }
        col.addView(box)
    }

    private fun button(title: String, summary: () -> String, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10.dp, 0, 10.dp)
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(label(title, 20f, Metro.fg, Metro.regular))
        val s = label(summary(), 15f, Prefs.accent, Metro.regular).apply { maxLines = 2 }
        box.addView(s)
        refreshers += { s.text = summary() }
        col.addView(box)
    }

    /** Windows Phone style row of options; the chosen one is filled with the accent. */
    private fun choice(title: String, options: List<Pair<String, Float>>, get: () -> Float, set: (Float) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10.dp, 0, 10.dp) }
        box.addView(label(title, 20f, Metro.fg, Metro.regular))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cells = ArrayList<TextView>()
        fun paint() {
            val cur = get()
            options.forEachIndexed { i, o ->
                val on = kotlin.math.abs(o.second - cur) < 0.01f
                cells[i].setBackgroundColor(if (on) Prefs.accent else Metro.chrome)
                cells[i].setTextColor(if (on) Color.WHITE else Metro.fg)
            }
        }
        for (o in options) {
            val tv = TextView(this).apply {
                text = o.first
                textSize = 15f
                typeface = Metro.regular
                gravity = Gravity.CENTER
                setOnClickListener {
                    set(o.second)
                    paint()
                }
            }
            cells += tv
            row.addView(tv, LinearLayout.LayoutParams(0, 44.dp, 1f).apply { rightMargin = 6.dp })
        }
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
        paint()
        col.addView(box)
    }

    private fun accentRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp, 0, 10.dp)
            isClickable = true
            setOnClickListener {
                ColorDialog(this@SettingsActivity, "colore principale", Prefs.accent, Metro.ACCENTS, Metro.NAMES) { c -> Prefs.accent = c; rebuild() }.show()
            }
        }
        val sw = View(this).apply { setBackgroundColor(Prefs.accent) }
        row.addView(sw, LinearLayout.LayoutParams(56.dp, 56.dp))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14.dp, 0, 0, 0) }
        txt.addView(label("colore principale", 20f, Metro.fg, Metro.regular))
        txt.addView(label(Metro.accentName(Prefs.accent), 15f, Prefs.accent, Metro.regular))
        row.addView(txt)
        col.addView(row)
    }

    /** Swatch row for the tile text colour: a sample tile in the accent with "Aa" in that colour. */
    private fun colorRow(title: String, desc: String, c: Int, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp, 0, 10.dp)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val sw = label("Aa", 22f, c, Metro.regular).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Prefs.accent)
        }
        row.addView(sw, LinearLayout.LayoutParams(56.dp, 56.dp))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14.dp, 0, 0, 0) }
        txt.addView(label(title, 20f, Metro.fg, Metro.regular))
        txt.addView(label(Metro.fgName(c) + " · " + desc, 15f, Metro.sub, Metro.regular).apply { maxLines = 2 })
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
    }

    /** Extra cities on the clock tile: current ones (tap to remove) and a search over all zones. */
    private fun zonesDialog() {
        val d = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        d.window?.setBackgroundDrawable(ColorDrawable(Metro.bg))
        d.window?.setDecorFitsSystemWindows(false)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 0, 24.dp, 0) }
        box.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(24.dp, b.top + 12.dp, 24.dp, b.bottom + 12.dp)
            ins
        }
        box.addView(label("OROLOGIO", 14f, Metro.fg, Metro.semibold))
        box.addView(label("fusi orari", 40f).apply { setPadding(0, 0, 0, 4.dp) })
        box.addView(label("fino a ${Zones.MAX} città · si vedono sul riquadro largo e grande, sul medio girano", 14f, Metro.sub, Metro.regular).apply { maxLines = 3 })
        val current = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8.dp, 0, 8.dp) }
        box.addView(current)
        val et = EditText(this).apply {
            hint = "cerca città, es. New York"
            textSize = 18f
            setTextColor(Metro.bg)
            setHintTextColor(Metro.sub)
            background = ColorDrawable(Metro.fg)
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(results); isVerticalScrollBarEnabled = false }
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 8.dp })

        fun row(main: String, sub: String, onTap: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
            isClickable = true
            setOnClickListener { onTap() }
            addView(label(main, 20f, Metro.fg, Metro.regular).apply { setSingleLine() })
            addView(label(sub, 14f, Prefs.accent, Metro.regular).apply { setSingleLine() })
        }
        lateinit var refresh: () -> Unit
        refresh = {
            current.removeAllViews()
            for (z in Prefs.clockZones) {
                current.addView(row("${Zones.name(z)}  ${Zones.time(this, z)}", Zones.relative(z) + " · tocca per togliere") {
                    Prefs.clockZones = Prefs.clockZones - z
                    refresh()
                })
            }
            if (Prefs.clockZones.isEmpty()) current.addView(label("nessuna città aggiunta", 15f, Metro.sub, Metro.regular))
            results.removeAllViews()
            val q = Apps.normalize(et.text.toString().trim())
            val full = Prefs.clockZones.size >= Zones.MAX
            if (q.isNotEmpty() && !full) {
                Zones.all.asSequence()
                    .filter { (id, n) -> id !in Prefs.clockZones && (Apps.normalize(n).contains(q) || Apps.normalize(id).contains(q)) }
                    .sortedBy { if (Apps.normalize(it.second).startsWith(q)) 0 else 1 }
                    .take(40)
                    .forEach { (id, n) ->
                        results.addView(row(n, "${Zones.time(this, id)} · ${Zones.relative(id)} · ${id.substringBefore('/')}") {
                            Prefs.clockZones = Prefs.clockZones + id
                            et.setText("")
                            refresh()
                        })
                    }
            } else if (full) {
                results.addView(label("massimo ${Zones.MAX} città: toglierne una per aggiungerne un'altra", 15f, Metro.sub, Metro.regular).apply { maxLines = 2 })
            }
            refreshers.forEach { it() }
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
        d.setContentView(box)
        d.show()
    }

    private fun addLiveRow() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10.dp, 0, 10.dp) }
        box.addView(label("aggiungi riquadro", 20f, Metro.fg, Metro.regular))
        box.addView(label("\"vuoto\" lascia vedere lo sfondo: spostalo e ridimensionalo come gli altri", 14f, Metro.sub, Metro.regular).apply { maxLines = 3 })
        val grid = GridLayout(this).apply { columnCount = 4 }
        val kinds = listOf(Tile.CLOCK, Tile.CALENDAR, Tile.PHOTOS, Tile.WEATHER, Tile.EMPTY)
        for (k in kinds) {
            val tv = TextView(this).apply {
                text = Tile.liveLabel(k)
                textSize = 13f
                typeface = Metro.regular
                setTextColor(Color.WHITE)
                gravity = Gravity.BOTTOM or Gravity.START
                setPadding(6.dp, 0, 0, 6.dp)
                if (k == Tile.EMPTY) {
                    setTextColor(Metro.fg)
                    background = android.graphics.drawable.GradientDrawable().apply { setStroke(2.dp, Metro.sub, 8.dpf, 6.dpf) }
                } else {
                    setBackgroundColor(Prefs.accent)
                }
                setOnClickListener {
                    TileStore.pin(Tile(k, null, if (k == Tile.CLOCK || k == Tile.WEATHER) TSize.WIDE else TSize.MEDIUM))
                    Toast.makeText(this@SettingsActivity, "${Tile.liveLabel(k)} aggiunto a start", Toast.LENGTH_SHORT).show()
                }
            }
            grid.addView(tv, GridLayout.LayoutParams().apply { width = 72.dp; height = 72.dp; setMargins(0, 8.dp, 8.dp, 0) })
        }
        box.addView(grid)
        col.addView(box)
    }
}

/** Colour picker: preset swatches plus a free HSV / hex colour. */
class ColorDialog(
    private val a: Activity,
    private val title: String,
    private val current: Int,
    private val presets: IntArray,
    private val names: Array<String>,
    private val done: (Int) -> Unit,
) : Dialog(a, android.R.style.Theme_Material_NoActionBar) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Metro.bg))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 0, 20.dp, 0) }
        val sv = ScrollView(context).apply { addView(col); isVerticalScrollBarEnabled = false; clipToPadding = false }
        window?.setDecorFitsSystemWindows(false)
        sv.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(0, b.top + 12.dp, 0, b.bottom + 16.dp)
            ins
        }
        setContentView(sv)
        col.addView(context.label("COLORI", 14f, Metro.fg, Metro.semibold))
        col.addView(context.label(title, 40f).apply { setPadding(0, 0, 0, 12.dp) })

        val grid = GridLayout(context).apply { columnCount = 4 }
        val w = a.resources.displayMetrics.widthPixels
        val size = (w - 40.dp) / 4 - 10.dp
        for (i in presets.indices) {
            val c = presets[i]
            val cell = FrameLayout(context).apply {
                // A swatch the same shade as the page (black on black) gets an outline to stay visible.
                if (kotlin.math.abs(Color.luminance(c) - Color.luminance(Metro.bg)) < 0.08f) {
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(c); setStroke(1.dp, Metro.sub) }
                } else {
                    setBackgroundColor(c)
                }
                setOnClickListener { pick(c) }
                contentDescription = names[i]
            }
            if (c == (current or 0xFF000000.toInt())) {
                cell.addView(object : View(context) {
                    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = 3.dpf
                        color = if (Color.luminance(c) > 0.6f) Color.BLACK else Color.WHITE
                    }
                    override fun onDraw(cv: android.graphics.Canvas) {
                        cv.drawRect(1.5f.dpf, 1.5f.dpf, width - 1.5f.dpf, height - 1.5f.dpf, p)
                        Glyphs.draw(cv, Glyphs.CHECK, width / 2f, height / 2f, width * 0.35f, p)
                    }
                })
            }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = size; height = size; setMargins(5.dp, 5.dp, 5.dp, 5.dp) })
        }
        col.addView(grid)

        col.addView(context.label("personalizzato", 28f).apply { setPadding(0, 24.dp, 0, 10.dp) })
        val preview = View(context).apply { setBackgroundColor(current) }
        val hex = EditText(context).apply {
            setText(Metro.hex(current))
            textSize = 18f
            setTextColor(Metro.bg)
            background = ColorDrawable(Metro.fg)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        var syncing = false
        val hsv = HsvView(context, current) { c ->
            preview.setBackgroundColor(c)
            syncing = true
            hex.setText(Metro.hex(c))
            syncing = false
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val t = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (t.length == 6) runCatching { Color.parseColor("#$t") }.getOrNull()?.let {
                    hsv.set(it)
                    preview.setBackgroundColor(it)
                }
            }
        })
        col.addView(hsv)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 14.dp, 0, 0) }
        row.addView(preview, LinearLayout.LayoutParams(48.dp, 48.dp))
        row.addView(hex, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { leftMargin = 12.dp; rightMargin = 12.dp })
        val ok = context.label("applica", 18f, Color.WHITE, Metro.regular).apply {
            gravity = Gravity.CENTER
            setPadding(18.dp, 0, 18.dp, 0)
            setBackgroundColor(0xFF555555.toInt())
            setOnClickListener {
                val t = hex.text.toString().trim().removePrefix("#")
                val c = runCatching { Color.parseColor("#$t") }.getOrDefault(hsv.color)
                pick(c)
            }
        }
        row.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48.dp))
        col.addView(row)
    }

    private fun pick(c: Int) {
        dismiss()
        done(c)
    }
}
