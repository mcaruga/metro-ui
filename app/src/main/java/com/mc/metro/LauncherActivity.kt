package com.mc.metro

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.random.Random

class LauncherActivity : Activity(), TileGrid.Host, AppListPage.Host {
    private lateinit var root: FrameLayout
    private lateinit var pager: Pager
    private lateinit var startScroll: ScrollView
    private lateinit var grid: TileGrid
    private lateinit var appsPage: AppListPage
    private var splash: View? = null
    private var menu: View? = null
    private var builtStyle = -1
    private var builtTiles = -1
    private var returning = false
    private var launching = false
    private var visible = false
    private var bottomInset = 0
    private val h = Handler(Looper.getMainLooper())

    private val appsListener: () -> Unit = {
        grid.rebuild()
        builtTiles = TileStore.version
        appsPage.reload()
    }
    private val liveListener: () -> Unit = { grid.refreshFaces() }

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = grid.refreshFaces()
    }

    private val flipper = object : Runnable {
        override fun run() {
            flipRandom()
            h.postDelayed(this, 2200L + Random.nextLong(2600L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Apps.init(this)
        TileStore.load(this)
        build()
        Apps.addListener(appsListener)
        Live.addListener(liveListener)
        Notifs.addListener(liveListener)
        Media.init(this)
        Media.addListener(liveListener)
        if (!Prefs.askedPerms) {
            Prefs.askedPerms = true
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Live.photoPerm, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        handleIntent(intent)
    }

    override fun onDestroy() {
        Apps.removeListener(appsListener)
        Live.removeListener(liveListener)
        Notifs.removeListener(liveListener)
        Media.removeListener(liveListener)
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun build() {
        builtStyle = Prefs.style
        builtTiles = TileStore.version
        window.decorView.setBackgroundColor(Metro.bg)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { ic ->
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            ic.setSystemBarsAppearance(if (Prefs.light) light else 0, light)
        }

        root = FrameLayout(this).apply { setBackgroundColor(Metro.bg) }
        grid = TileGrid(this, this)

        val startCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setOnClickListener { grid.endEdit() }
        }
        startCol.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val arrowRow = FrameLayout(this)
        val arrow = CircleButton(this, Glyphs.ARROW_RIGHT).apply { setOnClickListener { pager.goTo(1) } }
        arrowRow.addView(arrow, FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = 12.dp })
        startCol.addView(arrowRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 84.dp))

        startScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
            addView(startCol)
        }
        startScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateWall() }
        appsPage = AppListPage(this, this)
        pager = Pager(this).apply {
            addView(startScroll)
            addView(appsPage)
            onPage = { p -> if (p == 0) appsPage.reset() }
        }
        root.addView(pager, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            bottomInset = b.bottom
            startScroll.setPadding(0, b.top + 28.dp, 0, b.bottom)
            startScroll.post { loadWall() }
            appsPage.setInsets(b.top, max(b.bottom, ime.bottom))
            ins
        }
        setContentView(root)
        grid.rebuild()
        appsPage.reload()
    }

    private fun loadWall() {
        Wall.load(this, root.width, (root.height * 1.4f).toInt()) { updateWall() }
        updateWall()
    }

    /** WP 8.1: the image scrolls slower than the tiles. */
    private fun updateWall() {
        val y = startScroll.scrollY
        grid.viewTop = (startScroll.paddingTop - y).toFloat()
        val b = Wall.bitmap
        grid.bgShift = if (b != null && Prefs.parallax) {
            val maxScroll = (startScroll.getChildAt(0).height + startScroll.paddingTop + startScroll.paddingBottom - startScroll.height).coerceAtLeast(1)
            val extra = (b.height - startScroll.height).coerceAtLeast(0)
            y * minOf(0.5f, extra.toFloat() / maxScroll)
        } else 0f
        if (b != null) grid.invalidateTiles()
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (builtStyle != Prefs.style) {
            recreate()
            return
        }
        if (builtTiles != TileStore.version) {
            grid.rebuild()
            builtTiles = TileStore.version
        }
        val f = IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(tick, f, Context.RECEIVER_EXPORTED) else registerReceiver(tick, f)
        Live.refresh(this)
        grid.refreshFaces()
        if (returning || launching) {
            returning = false
            launching = false
            restoreFromLaunch(true)
        }
        h.removeCallbacks(flipper)
        h.postDelayed(flipper, 1800)
        Live.fx = true
        // Safety net: straighten any tile an interrupted animation left crooked.
        h.postDelayed({ if (!launching && !grid.editing) grid.tileViews().forEach { it.settle() } }, 900)
        Updater.autoCheck(this)
        h.postDelayed({ if (!isFinishing) NavBarService.check(this) }, 4000)
        if (!Prefs.askedHome && !HomeRole.isDefault(this)) {
            // Once: without this the home gesture still goes to One UI Home.
            Prefs.askedHome = true
            h.postDelayed({ if (!isFinishing) HomeRole.request(this) }, 1200)
        } else if (!Prefs.askedNotif && Build.VERSION.SDK_INT >= 33) {
            // Once, for the "new version available" notification.
            Prefs.askedNotif = true
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                h.postDelayed({ if (!isFinishing) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9) }, 1200)
            }
        }
        grid.invalidateTiles()
    }

    override fun onPause() {
        try { unregisterReceiver(tick) } catch (e: Exception) { }
        Live.fx = false
        h.removeCallbacks(flipper)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("search", false) == true) {
            closeMenu()
            grid.endEdit()
            h.post {
                pager.goTo(1, visible)
                appsPage.openSearch()
            }
            return
        }
        if (i?.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_HOME)) {
            val wasHere = visible && !returning
            closeMenu()
            grid.endEdit()
            appsPage.reset()
            if (pager.page != 0) pager.goTo(0, wasHere)
            else if (wasHere) startScroll.smoothScrollTo(0, 0)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            menu != null -> closeMenu()
            grid.editing -> grid.endEdit()
            appsPage.onBack() -> {}
            pager.page != 0 -> pager.goTo(0)
            else -> startScroll.smoothScrollTo(0, 0)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Live.refresh(this, true)
        grid.refreshFaces()
    }

    // ---- TileGrid.Host -----------------------------------------------------------------------

    override fun scroller(): ScrollView = startScroll

    override fun onEditChanged(editing: Boolean) {
        pager.locked = editing
    }

    override fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onTileClick(v: TileView) {
        val e = v.app
        when (v.tile.kind) {
            Tile.APP -> if (e != null) launchApp(e, v)
            // No location needed: without a city or GPS the forecast uses the network position.
            Tile.WEATHER -> when {
                Live.weather == null -> Live.refresh(this, true)
                e != null -> launchApp(e, v)
                else -> { Live.refresh(this, true); v.flip() }
            }
            Tile.PHOTOS -> when {
                !Live.has(this, Live.photoPerm) -> requestPermissions(arrayOf(Live.photoPerm), 3)
                // First tap: the user picks the gallery (Samsung, Google Photos, …) instead of us guessing.
                Prefs.photoApp == null && Apps.photoViewers(this).size > 1 -> PhotoAppDialog(this) { pick ->
                    Prefs.photoApp = pick.pkg
                    TileStore.touch() // the tile takes the new app's glyph when start is rebuilt on return
                    val uri = v.photoUri
                    if (uri != null) openPhoto(uri, pick, v) else launchApp(pick, v)
                }.show()
                v.photoUri != null -> openPhoto(v.photoUri!!, e, v)
                e != null -> launchApp(e, v)
            }
            Tile.EMPTY -> {}
            Tile.CALENDAR -> when {
                !Live.has(this, Manifest.permission.READ_CALENDAR) -> requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 4)
                e != null -> launchApp(e, v)
            }
            else -> if (e != null) launchApp(e, v)
        }
    }

    // ---- AppListPage.Host --------------------------------------------------------------------

    override fun launch(e: AppEntry, from: View) = launchApp(e, from)

    override fun open(i: Intent, from: View) {
        launchWith(from, null) {
            returning = true
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
            } catch (ex: Exception) {
                Toast.makeText(this, "nessuna app per aprire la ricerca", Toast.LENGTH_SHORT).show()
                returning = false
                launching = false
                restoreFromLaunch(true)
            }
        }
    }

    override fun appMenu(e: AppEntry, from: View) {
        val pinned = TileStore.isPinned(e.key)
        showMenu(
            from,
            listOf(
                (if (pinned) "rimuovi da start" else "aggiungi a start") to {
                    if (pinned) TileStore.unpinApp(e.key) else TileStore.pin(Tile(Tile.APP, e.key, TSize.MEDIUM))
                    grid.rebuild()
                    builtTiles = TileStore.version
                    if (!pinned) {
                        pager.goTo(0)
                        h.postDelayed({ startScroll.smoothScrollTo(0, grid.height) }, 420)
                    }
                },
                "info app" to { Apps.details(this, e) },
                "disinstalla" to {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)))
                },
            ),
        )
    }

    // ---- launch, turnstile, splash -----------------------------------------------------------

    private fun launchApp(e: AppEntry, from: View) {
        val r = Rect()
        from.getGlobalVisibleRect(r)
        launchWith(from, e) { start(e, r) }
    }

    /** Turnstile out, optional splash with [splashFor]'s glyph, then [go]. */
    private fun launchWith(from: View, splashFor: AppEntry?, go: () -> Unit) {
        if (launching) return
        val splash = Prefs.splash && splashFor != null
        if (!Prefs.animations) {
            if (splash) showSplash(splashFor!!)
            go()
            return
        }
        launching = true
        val out = turnstileOut(from)
        h.postDelayed({
            if (splash) showSplash(splashFor!!)
            h.postDelayed(go, if (splash) 200L else 0L)
        }, out)
    }

    /**
     * The photo shown on the photo tile, in the gallery (the tile's app first, any viewer otherwise).
     * With the tile's app, the photo opens on top of the gallery's own home, so back leads to all
     * the other photos and albums instead of out of the app.
     */
    private fun openPhoto(uri: Uri, gallery: AppEntry?, from: View) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val own = gallery != null && gallery.user == android.os.Process.myUserHandle() &&
            Intent(view).setPackage(gallery.pkg).resolveActivity(packageManager) != null
        val stack = if (own) arrayOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(gallery!!.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").setPackage(gallery.pkg)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        ) else arrayOf(view)
        launchWith(from, gallery) {
            returning = true
            try {
                startActivities(stack, ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
            } catch (ex: Exception) {
                Toast.makeText(this, "nessuna app per aprire la foto", Toast.LENGTH_SHORT).show()
                returning = false
                launching = false
                restoreFromLaunch(true)
            }
        }
    }

    private fun start(e: AppEntry, r: Rect) {
        returning = true
        try {
            val o = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
            Apps.launch(this, e, r, o.toBundle())
        } catch (ex: Exception) {
            Toast.makeText(this, "impossibile aprire ${e.label}", Toast.LENGTH_SHORT).show()
            returning = false
            launching = false
            restoreFromLaunch(true)
        }
    }

    /** Tiles swing out around the left edge, the tapped one last. Returns total duration. */
    private fun turnstileOut(from: View): Long {
        if (pager.page == 1) {
            appsPage.turnstileOut()
            return 230
        }
        val sy = startScroll.scrollY - startScroll.paddingTop
        val vh = startScroll.height
        val u = grid.unitSize.coerceAtLeast(1)
        var maxDelay = 0L
        for (v in grid.tileViews()) {
            if (v.translationY + v.height < sy || v.translationY > sy + vh) continue
            if (v === from) continue
            val row = ((v.translationY - sy).coerceAtLeast(0f) / u).toLong()
            val col = (v.translationX / u).toLong()
            val d = (row * 22 + col * 10).coerceAtMost(170)
            maxDelay = max(maxDelay, d)
            swing(v, d)
        }
        if (from is TileView) swing(from, maxDelay + 50)
        return maxDelay + 50 + 200
    }

    private fun swing(v: View, delay: Long) {
        v.animate().cancel()
        v.cameraDistance = 9000 * DENSITY
        v.pivotX = -v.translationX
        v.pivotY = v.height / 2f
        // Also undo any tilt still in progress: the cancel above stops its way back.
        val base = (v as? TileView)?.baseScale ?: 1f
        v.an(200, delay, AccelerateInterpolator()).rotationY(80f).rotationX(0f).scaleX(base).scaleY(base).alpha(0f).start()
    }

    private fun restoreFromLaunch(anim: Boolean) {
        splash?.let { root.removeView(it) }
        splash = null
        appsPage.restore(anim && pager.page == 1)
        val sy = startScroll.scrollY - startScroll.paddingTop
        val u = grid.unitSize.coerceAtLeast(1)
        for (v in grid.tileViews()) {
            if (anim && Prefs.animations && v.rotationY != 0f) {
                val row = ((v.translationY - sy).coerceAtLeast(0f) / u).toLong().coerceAtMost(8)
                v.an(260, row * 25).rotationY(0f).rotationX(0f).scaleX(v.baseScale).scaleY(v.baseScale).alpha(1f)
                    .withEndAction { v.pivotX = v.width / 2f }.start()
            } else {
                v.resetTransforms()
                v.pivotX = v.width / 2f
            }
        }
    }

    private fun showSplash(e: AppEntry) {
        splash?.let { root.removeView(it) }
        val v = FrameLayout(this).apply { setBackgroundColor(Prefs.accent); isClickable = true }
        val size = (96 * Prefs.iconScale).dp
        val iv = ImageView(this).apply {
            setImageBitmap(Apps.icon(e, size, true))
            if (Prefs.tileFg != android.graphics.Color.WHITE) setColorFilter(Prefs.tileFg, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        v.addView(iv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (Prefs.animations) {
            v.alpha = 0f
            v.an(120).alpha(1f).start()
            iv.scaleX = 0.8f
            iv.scaleY = 0.8f
            iv.an(220).scaleX(1f).scaleY(1f).start()
        }
        splash = v
    }

    private fun flipRandom() {
        if (pager.page != 0 || grid.editing || launching || menu != null) return
        grid.tileViews().forEach { it.settle() }
        val sy = startScroll.scrollY - startScroll.paddingTop
        val vh = startScroll.height
        grid.tileViews()
            .filter { it.canFlip() && it.translationY + it.height > sy && it.translationY < sy + vh }
            .randomOrNull()
            ?.flip()
    }

    // ---- Metro context menu ------------------------------------------------------------------

    private fun showMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
        closeMenu()
        val ov = FrameLayout(this).apply {
            setBackgroundColor(Metro.scrim)
            isClickable = true
            setOnClickListener { closeMenu() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Metro.fg)
            setPadding(0, 10.dp, 0, 10.dp)
            isClickable = true
        }
        for ((t, f) in items) {
            panel.addView(TextView(this).apply {
                text = t
                textSize = 21f
                typeface = Metro.regular
                setTextColor(Metro.bg)
                setPadding(22.dp, 12.dp, 22.dp, 12.dp)
                setOnClickListener {
                    closeMenu()
                    f()
                }
            })
        }
        ov.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(ov, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        menu = ov
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val a = IntArray(2)
        anchor.getLocationInWindow(a)
        val r = IntArray(2)
        root.getLocationInWindow(r)
        val ph = panel.measuredHeight
        var y = a[1] - r[1] + anchor.height
        if (y + ph > root.height - bottomInset) y = a[1] - r[1] - ph
        panel.translationY = y.coerceAtLeast(0).toFloat()
        if (Prefs.animations) {
            ov.alpha = 0f
            ov.an(120).alpha(1f).start()
            panel.pivotY = 0f
            panel.scaleY = 0.85f
            panel.an(160).scaleY(1f).start()
        }
    }

    private fun closeMenu() {
        menu?.let { root.removeView(it) }
        menu = null
    }
}
