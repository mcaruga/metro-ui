package com.mc.metro

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Start screen grid. Tiles are packed first-fit in [TileStore] order; children are laid out at
 * (0,0) and positioned with translation so moves can be animated and dragged freely.
 */
@SuppressLint("ViewConstructor")
class TileGrid(c: Context, private val host: Host) : ViewGroup(c) {
    interface Host {
        fun onTileClick(v: TileView)
        fun onEditChanged(editing: Boolean)
        fun scroller(): ScrollView
        fun openSettings()
    }

    var editing = false
        private set
    private var sel: TileView? = null
    private val views = ArrayList<TileView>()
    private val pos = HashMap<TileView, IntArray>()
    private val gap = 6.dp
    private val side = 12.dp
    private var unit = 0
    private var rows = 0
    private var animateLayout = false
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    private var dragView: TileView? = null
    private var dragging = false
    private var buttonHit = 0
    private var grabX = 0f
    private var grabY = 0f
    private var startRawX = 0f
    private var startRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastTarget: TileView? = null
    private var scrollDir = 0

    init {
        clipChildren = false
        clipToPadding = false
    }

    val unitSize get() = unit + gap
    /** Grid top relative to the screen viewport, and the background parallax shift. */
    var viewTop = 0f
    var bgShift = 0f

    fun invalidateTiles() = views.forEach { it.invalidate() }
    fun tileViews(): List<TileView> = views

    fun rebuild() {
        if (editing) endEdit()
        removeAllViews()
        views.clear()
        for (t in TileStore.tiles) {
            if (t.kind == Tile.APP && Apps.find(t.app) == null) continue
            val v = TileView(context, t, this)
            views += v
            addView(v)
            v.rebuild()
        }
        requestLayout()
    }

    fun refreshFaces() = views.forEach { it.rebuild() }

    fun onTileClick(v: TileView) = host.onTileClick(v)

    // ---- layout ------------------------------------------------------------------------------

    private fun pack() {
        val cols = Prefs.units
        val occ = ArrayList<BooleanArray>()
        fun row(r: Int): BooleanArray {
            while (occ.size <= r) occ += BooleanArray(cols)
            return occ[r]
        }
        fun free(r: Int, c: Int, w: Int, h: Int): Boolean {
            if (c + w > cols) return false
            for (rr in r until r + h) for (cc in c until c + w) if (row(rr)[cc]) return false
            return true
        }
        pos.clear()
        rows = 0
        for (v in views) {
            val w = min(v.tile.size.w, cols)
            val h = v.tile.size.h
            var r = 0
            var placed = false
            while (!placed) {
                for (c in 0..cols - w) {
                    if (free(r, c, w, h)) {
                        for (rr in r until r + h) for (cc in c until c + w) row(rr)[cc] = true
                        pos[v] = intArrayOf(c, r, w, h)
                        rows = maxOf(rows, r + h)
                        placed = true
                        break
                    }
                }
                if (!placed) r++
            }
        }
    }

    private fun span(n: Int) = n * unit + (n - 1) * gap

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val cols = Prefs.units
        unit = (w - side * 2 - gap * (cols - 1)) / cols
        pack()
        for (v in views) {
            val p = pos[v] ?: continue
            v.measure(
                MeasureSpec.makeMeasureSpec(span(p[2]), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(span(p[3]), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, rows * (unit + gap))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (v in views) {
            val p = pos[v] ?: continue
            val first = !v.isLaidOut
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            if (v === dragView && dragging) continue
            val tx = (side + p[0] * (unit + gap)).toFloat()
            val ty = (p[1] * (unit + gap)).toFloat()
            if (animateLayout && !first) {
                v.animate().translationX(tx).translationY(ty).setDuration(200).setStartDelay(0).start()
            } else {
                v.translationX = tx
                v.translationY = ty
            }
        }
        animateLayout = false
    }

    // ---- edit mode ---------------------------------------------------------------------------

    fun beginEdit(v: TileView) {
        if (!editing) {
            editing = true
            host.onEditChanged(true)
            views.forEach { it.invalidate() }
        }
        select(v)
        startDrag(v, v.lastRawX, v.lastRawY)
        buttonHit = 0
    }

    fun endEdit() {
        if (!editing) return
        editing = false
        dragging = false
        dragView = null
        stopAutoScroll()
        select(null)
        TileStore.save()
        host.onEditChanged(false)
        views.forEach { it.invalidate() }
    }

    private fun select(v: TileView?) {
        sel?.let { it.editSelected = false; it.invalidate() }
        sel = v
        v?.let { it.editSelected = true; it.invalidate() }
        for (t in views) {
            val s = if (!editing || t === v) 1f else 0.9f
            val a = if (!editing || t === v) 1f else 0.7f
            t.baseScale = s
            t.translationZ = if (t === v) 8.dpf else 0f
            t.an(150).scaleX(s).scaleY(s).alpha(a).rotationX(0f).rotationY(0f).start()
        }
    }

    private fun gridLoc(): IntArray = IntArray(2).also { getLocationOnScreen(it) }

    private fun startDrag(v: TileView, rawX: Float, rawY: Float) {
        dragView = v
        dragging = false
        startRawX = rawX
        startRawY = rawY
        lastRawX = rawX
        lastRawY = rawY
        lastTarget = null
        val loc = gridLoc()
        grabX = rawX - loc[0] - v.translationX
        grabY = rawY - loc[1] - v.translationY
    }

    fun onEditTouch(v: TileView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                buttonHit = if (v === sel) v.hitButton(e.x, e.y) else 0
                if (v !== sel) select(v)
                startDrag(v, e.rawX, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragView == null) return true
                if (!dragging && buttonHit == 0 && hypot(e.rawX - startRawX, e.rawY - startRawY) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    moveDrag(e.rawX, e.rawY)
                    autoScroll(e.rawY)
                }
            }
            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> drop()
                    buttonHit == 1 && v.hitButton(e.x, e.y) == 1 -> unpin(v)
                    buttonHit == 2 && v.hitButton(e.x, e.y) == 2 -> resize(v)
                }
                dragView = null
                buttonHit = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) drop()
                dragView = null
                buttonHit = 0
            }
        }
        return true
    }

    private fun moveDrag(rawX: Float, rawY: Float) {
        lastRawX = rawX
        lastRawY = rawY
        val d = dragView ?: return
        val loc = gridLoc()
        d.translationX = rawX - loc[0] - grabX
        d.translationY = rawY - loc[1] - grabY
        val col = ((d.translationX - side) / (unit + gap)).roundToInt().coerceIn(0, Prefs.units - 1)
        val row = (d.translationY / (unit + gap)).roundToInt().coerceAtLeast(0)
        val target = views.firstOrNull { o ->
            if (o === d) return@firstOrNull false
            val p = pos[o] ?: return@firstOrNull false
            col >= p[0] && col < p[0] + p[2] && row >= p[1] && row < p[1] + p[3]
        }
        if (target === lastTarget) return
        lastTarget = target
        val from = views.indexOf(d)
        val to = when {
            target != null -> views.indexOf(target)
            row >= rows -> views.size - 1
            else -> -1
        }
        if (to >= 0 && to != from) {
            views.removeAt(from)
            views.add(to, d)
            syncStore()
            animateLayout = true
            requestLayout()
        }
    }

    private fun drop() {
        dragging = false
        stopAutoScroll()
        syncStore()
        TileStore.save()
        animateLayout = true
        requestLayout()
        // requestLayout is skipped while the view is dragging; make sure it snaps home.
        post { dragView = null; animateLayout = true; requestLayout() }
    }

    private fun syncStore() {
        val hidden = TileStore.tiles.filter { t -> views.none { it.tile === t } }
        TileStore.tiles.clear()
        TileStore.tiles.addAll(views.map { it.tile })
        TileStore.tiles.addAll(hidden)
    }

    private fun unpin(v: TileView) {
        TileStore.tiles.remove(v.tile)
        views.remove(v)
        removeView(v)
        sel = null
        TileStore.save()
        animateLayout = true
        requestLayout()
        if (views.isEmpty()) endEdit()
    }

    private fun resize(v: TileView) {
        v.tile.size = v.tile.nextSize()
        TileStore.save()
        v.rebuild()
        animateLayout = true
        requestLayout()
    }

    private val scrollRun = object : Runnable {
        override fun run() {
            if (scrollDir == 0 || !dragging) return
            host.scroller().scrollBy(0, scrollDir * 12.dp)
            moveDrag(lastRawX, lastRawY)
            postOnAnimation(this)
        }
    }

    private fun autoScroll(rawY: Float) {
        val sv = host.scroller()
        val a = IntArray(2)
        sv.getLocationOnScreen(a)
        val zone = 90.dp
        val dir = when {
            rawY < a[1] + zone -> -1
            rawY > a[1] + sv.height - zone -> 1
            else -> 0
        }
        if (dir != scrollDir) {
            scrollDir = dir
            removeCallbacks(scrollRun)
            if (dir != 0) postOnAnimation(scrollRun)
        }
    }

    private fun stopAutoScroll() {
        scrollDir = 0
        removeCallbacks(scrollRun)
    }

    // ---- empty space -------------------------------------------------------------------------

    private val emptyLong = Runnable { host.openSettings() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!editing) postDelayed(emptyLong, ViewConfiguration.getLongPressTimeout().toLong() + 200)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(emptyLong)
                if (editing) endEdit()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(emptyLong)
        }
        return true
    }
}
