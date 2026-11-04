package com.mc.metro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/** Windows Phone 8.1 style app list: letter headers, jump grid, search. */
@SuppressLint("ViewConstructor")
class AppListPage(c: Context, private val host: Host) : FrameLayout(c) {
    interface Host {
        fun launch(e: AppEntry, from: View)
        fun appMenu(e: AppEntry, from: View)
    }

    private val list = ListView(c)
    private val search = EditText(c)
    private val searchBtn = CircleButton(c, Glyphs.SEARCH)
    private val column = LinearLayout(c)
    private var rows: List<Any> = emptyList()
    private val adapter = Adapter()
    private var jump: View? = null
    private var searching = false
    private var topInset = 0
    private var bottomInset = 0

    init {
        column.orientation = LinearLayout.VERTICAL
        val bar = FrameLayout(c)
        bar.addView(searchBtn, LayoutParams(48.dp, 48.dp, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = 12.dp })
        search.apply {
            visibility = View.GONE
            hint = "cerca"
            textSize = 19f
            typeface = Metro.regular
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Metro.bg)
            setHintTextColor(Metro.sub)
            background = ColorDrawable(Metro.fg)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = reload()
            })
            setOnEditorActionListener { _, _, _ ->
                (rows.firstOrNull { it is AppEntry } as? AppEntry)?.let { host.launch(it, this) }
                true
            }
        }
        bar.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, 48.dp, Gravity.CENTER_VERTICAL).apply {
            leftMargin = 16.dp; rightMargin = 16.dp
        })
        searchBtn.setOnClickListener { openSearch() }
        column.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp))

        list.apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            cacheColorHint = 0
            clipToPadding = false
            adapter = this@AppListPage.adapter
            setOnItemClickListener { _, view, position, _ ->
                when (val r = rows[position]) {
                    is String -> showJump()
                    is AppEntry -> host.launch(r, view)
                }
            }
            setOnItemLongClickListener { _, view, position, _ ->
                val r = rows[position] as? AppEntry ?: return@setOnItemLongClickListener false
                host.appMenu(r, view)
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, state: Int) {
                    if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) untiltAll()
                }
                override fun onScroll(v: AbsListView?, a: Int, b: Int, c: Int) {}
            })
            setOnTouchListener { _, e -> tiltTouch(e); false }
        }
        column.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        column.setPadding(0, top + 8.dp, 0, 0)
        list.setPadding(0, 0, 0, bottom + 16.dp)
        jump?.setPadding(0, top, 0, bottom)
    }

    fun reload() {
        rows = build(search.text?.toString().orEmpty())
        adapter.notifyDataSetChanged()
    }

    private fun build(q: String): List<Any> {
        if (q.isNotBlank()) {
            val n = Apps.normalize(q.trim())
            return Apps.all.filter { it.norm.contains(n) }.sortedBy { if (it.norm.startsWith(n)) 0 else 1 }
        }
        val out = ArrayList<Any>()
        var last = ""
        for (a in Apps.all) {
            if (a.letter != last) {
                out += a.letter
                last = a.letter
            }
            out += a
        }
        return out
    }

    // ---- search ------------------------------------------------------------------------------

    fun openSearch() {
        searching = true
        searchBtn.visibility = View.GONE
        search.visibility = View.VISIBLE
        search.requestFocus()
        post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(search, 0) }
    }

    private fun closeSearch() {
        searching = false
        search.setText("")
        search.visibility = View.GONE
        searchBtn.visibility = View.VISIBLE
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ---- jump grid ---------------------------------------------------------------------------

    private fun showJump() {
        if (jump != null) return
        val have = rows.filterIsInstance<String>().toSet()
        val ov = FrameLayout(context).apply {
            setBackgroundColor(if (Prefs.light) 0xF2FFFFFF.toInt() else 0xF2000000.toInt())
            isClickable = true
            setOnClickListener { hideJump() }
            setPadding(0, topInset, 0, bottomInset)
        }
        val grid = GridLayout(context).apply {
            columnCount = 4
            setPadding(12.dp, 24.dp, 12.dp, 24.dp)
        }
        val size = ((width - 24.dp) / 4) - 8.dp
        for (ch in "#abcdefghijklmnopqrstuvwxyz") {
            val l = ch.toString()
            val on = l in have
            val tv = TextView(context).apply {
                text = l
                textSize = 34f
                typeface = Metro.light
                gravity = Gravity.BOTTOM or Gravity.START
                setPadding(8.dp, 0, 0, 2.dp)
                setTextColor(if (on) 0xFFFFFFFF.toInt() else Metro.sub)
                setBackgroundColor(if (on) Prefs.accent else Metro.chrome)
                if (on) setOnClickListener { jumpTo(l) } else isClickable = true
            }
            grid.addView(tv, GridLayout.LayoutParams().apply {
                width = size; height = size
                setMargins(4.dp, 4.dp, 4.dp, 4.dp)
            })
        }
        val sv = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(grid) }
        ov.addView(sv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(ov, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        jump = ov
        if (Prefs.animations) {
            ov.alpha = 0f
            ov.scaleX = 1.15f
            ov.scaleY = 1.15f
            ov.an(180).alpha(1f).scaleX(1f).scaleY(1f).start()
        }
    }

    private fun jumpTo(l: String) {
        val i = rows.indexOf(l)
        if (i >= 0) list.setSelectionFromTop(i, 0)
        hideJump()
    }

    private fun hideJump() {
        val j = jump ?: return
        jump = null
        if (Prefs.animations) j.an(140).alpha(0f).scaleX(1.1f).scaleY(1.1f).withEndAction { removeView(j) }.start()
        else removeView(j)
    }

    fun onBack(): Boolean {
        if (jump != null) { hideJump(); return true }
        if (searching) { closeSearch(); return true }
        return false
    }

    fun reset() {
        if (jump != null) { jump?.let { removeView(it) }; jump = null }
        if (searching) closeSearch()
    }

    // ---- launch animation --------------------------------------------------------------------

    fun turnstileOut() {
        list.pivotX = 0f
        list.pivotY = list.height / 2f
        list.cameraDistance = 9000 * DENSITY
        list.an(220, 0, android.view.animation.AccelerateInterpolator()).rotationY(-70f).alpha(0f).start()
    }

    fun restore(anim: Boolean) {
        list.animate().cancel()
        if (anim && list.rotationY != 0f) {
            list.an(260).rotationY(0f).alpha(1f).start()
        } else {
            list.rotationY = 0f
            list.alpha = 1f
        }
    }

    // ---- tilt on rows ------------------------------------------------------------------------

    private var tilted: View? = null
    private var tiltX = 0f
    private var tiltY = 0f

    private fun tiltTouch(e: MotionEvent) {
        if (!Prefs.animations) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tiltX = e.x
                tiltY = e.y
                val p = list.pointToPosition(e.x.toInt(), e.y.toInt())
                val v = if (p >= 0) list.getChildAt(p - list.firstVisiblePosition) else null
                if (v != null) {
                    tilted = v
                    v.pivotX = v.width / 2f
                    v.pivotY = v.height / 2f
                    v.cameraDistance = 9000 * DENSITY
                    val dx = (e.x - v.width / 2f) / (v.width / 2f)
                    v.an(70).rotationY(dx * 5f).scaleX(0.98f).scaleY(0.98f).start()
                }
            }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(e.x - tiltX, e.y - tiltY) > 12.dp) untiltAll()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> untiltAll()
        }
    }

    private fun untiltAll() {
        tilted?.an(150)?.rotationY(0f)?.scaleX(1f)?.scaleY(1f)?.start()
        tilted = null
    }

    // ---- adapter -----------------------------------------------------------------------------

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
        override fun isEnabled(position: Int) = true

        override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
            val r = rows[position]
            return if (r is String) {
                val v = (convert as? LetterRow) ?: LetterRow(context)
                v.letter = r
                v
            } else {
                val e = r as AppEntry
                val v = (convert as? AppRow) ?: AppRow(context)
                v.bind(e)
                v
            }
        }
    }

    private class LetterRow(c: Context) : View(c) {
        var letter = ""
            set(v) { field = v; contentDescription = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (66 * Prefs.textScale).dp)

        override fun onDraw(cv: Canvas) {
            val s = 50.dpf * Prefs.textScale
            val x = 20.dpf
            val y = (height - s) / 2
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.dpf
            p.color = Prefs.accent
            cv.drawRect(x + 1.dpf, y + 1.dpf, x + s - 1.dpf, y + s - 1.dpf, p)
            p.style = Paint.Style.FILL
            p.typeface = Metro.light
            p.textSize = 30.sp * Prefs.textScale
            cv.drawText(letter, x + 6.dpf * Prefs.textScale, y + s - 8.dpf * Prefs.textScale, p)
        }
    }

    private class AppRow(c: Context) : LinearLayout(c) {
        private val icon = ImageView(c)
        private val name = c.label("", 21f * Prefs.textScale)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 0, 16.dp, 0)
            minimumHeight = (66 * maxOf(Prefs.textScale, Prefs.iconScale)).dp
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = 8.dp
            icon.setPadding(pad, pad, pad, pad)
            addView(icon, LayoutParams((50 * Prefs.iconScale).dp, (50 * Prefs.iconScale).dp))
            name.setSingleLine()
            addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 14.dp })
        }

        fun bind(e: AppEntry) {
            val flat = !Prefs.listColor
            val pad = if (flat) 8.dp else 1.dp
            icon.setBackgroundColor(if (flat) Prefs.accent else 0)
            icon.setPadding(pad, pad, pad, pad)
            icon.setImageBitmap(Apps.icon(e, ((if (flat) 34 else 48) * Prefs.iconScale).dp, flat))
            name.text = e.label
            contentDescription = e.label
        }
    }
}
