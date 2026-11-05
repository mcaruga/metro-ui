package com.mc.metro

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Optional Windows Phone navigation bar (back · start · search), drawn as an accessibility
 * overlay exactly over the system navigation bar, and host of the Metro action center. Both are
 * off by default. An overlay cannot push apps up, so the bar only shows with One UI's button
 * navigation, whose bar is tall enough; with gestures it would cover the apps' bottom menus.
 */
class NavBarService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private var bar: View? = null
    private var center: ActionCenter? = null
    private lateinit var wm: WindowManager

    companion object {
        @Volatile var running = false

        /** Height of the system navigation bar; under 32 dp it is the gesture handle only. */
        fun systemBarHeight(c: Context): Int = runCatching {
            c.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        }.getOrDefault(0)

        fun buttonNavigation(c: Context) = systemBarHeight(c) >= 32.dp

        fun isEnabled(c: Context): Boolean {
            val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.split(':').any { ComponentName.unflattenFromString(it)?.packageName == c.packageName }
        }

        /** Switched on but not bound: One UI marks the service crashed after an update and never rebinds it. */
        fun stuck(c: Context) = isEnabled(c) && !running

        fun canRepair(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        private var lastRepair = 0L

        /** Rebinds a stuck service by switching it off and on again. Needs WRITE_SECURE_SETTINGS, granted once over adb. */
        fun repair(c: Context): Boolean {
            if (!canRepair(c)) return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastRepair < 20_000) return true
            lastRepair = now
            val cr = c.contentResolver
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val me = ComponentName(c, NavBarService::class.java).flattenToString()
            val others = (Settings.Secure.getString(cr, key) ?: "").split(':')
                .filter { it.isNotEmpty() && ComponentName.unflattenFromString(it)?.packageName != c.packageName }
            return runCatching {
                Settings.Secure.putString(cr, key, others.joinToString(":"))
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        Settings.Secure.putString(cr, key, (others + me).joinToString(":"))
                        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    }
                }, 800)
                true
            }.getOrDefault(false)
        }

        /** Metro's own page in the accessibility settings, where the switch can be turned off and on. */
        fun details(c: Context): Intent {
            val me = ComponentName(c, NavBarService::class.java).flattenToString()
            val i = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, me)
            val target = if (i.resolveActivity(c.packageManager) != null) i else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            return target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** One UI keeps its navigation type here: 1 swipe gestures, 0 buttons. Absent elsewhere. */
        private const val ONEUI_NAV = "navigation_bar_gesture_while_hidden"

        /**
         * Switches One UI between buttons ([buttons] true) and gestures, so the Metro bar has the
         * system bar's room under it. False when it can't (not One UI, or no WRITE_SECURE_SETTINGS).
         */
        fun setSystemButtons(c: Context, buttons: Boolean): Boolean {
            if (!canRepair(c)) return false
            val cr = c.contentResolver
            if (Settings.Global.getString(cr, ONEUI_NAV) == null) return false
            return runCatching { Settings.Global.putInt(cr, ONEUI_NAV, if (buttons) 0 else 1) }.getOrDefault(false)
        }

        private const val CHANNEL = "service"
        private const val STUCK_ID = 4401

        /** Repairs a stuck service, or tells the user how to switch it back on. */
        fun check(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (!stuck(c) || !(Prefs.actionCenter || Prefs.navBar)) {
                nm.cancel(STUCK_ID)
                return
            }
            if (repair(c)) return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "servizio Metro", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(c, 0, details(c), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_metro)
                .setContentTitle("Centro notifiche Metro fermo")
                .setContentText("dopo l'aggiornamento: tocca, spegni e riaccendi Metro")
                .setStyle(Notification.BigTextStyle().bigText("Dopo l'aggiornamento Android ha scollegato Metro. Tocca, spegni e riaccendi \"Metro barra e centro notifiche\". Per farlo fare da solo a Metro vedi Impostazioni Metro › navigazione › riattivazione automatica."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(STUCK_ID, n) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        getSystemService(NotificationManager::class.java).cancel(STUCK_ID)
        wm = getSystemService(WindowManager::class.java)
        Prefs.sp.registerOnSharedPreferenceChangeListener(this)
        sync()
        center = ActionCenter(this).also { it.start() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        hide()
        center?.stop()
        center = null
        Prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        hide()
        center?.stop()
        center = null
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == "nav_bar" || key == "style") {
            hide()
            sync()
        }
        // One UI takes a moment to swap buttons and gestures; measure the system bar again after it.
        if (key == "nav_bar") resyncLater()
        if (key == "action_center") center?.syncStrip()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Switching between gestures and buttons changes the system bar: follow it.
        hide()
        sync()
        center?.onConfigurationChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val resync = Runnable { hide(); sync() }

    private fun resyncLater() {
        main.removeCallbacks(resync)
        main.postDelayed(resync, 700)
        main.postDelayed({ if (running) resync.run() }, 1800)
    }

    private fun sync() {
        val want = Prefs.navBar && buttonNavigation(this)
        if (want && bar == null) show() else if (!want) hide()
    }

    private fun show() {
        val v = NavBarView(this) { action, long ->
            when (action) {
                0 -> performGlobalAction(if (long) GLOBAL_ACTION_RECENTS else GLOBAL_ACTION_BACK)
                1 -> performGlobalAction(if (long) GLOBAL_ACTION_RECENTS else GLOBAL_ACTION_HOME)
                2 -> startActivity(
                    Intent(this, LauncherActivity::class.java)
                        .putExtra("search", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            systemBarHeight(this),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.BOTTOM
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm.addView(v, lp)
            bar = v
        } catch (e: Exception) {
            bar = null
        }
    }

    private fun hide() {
        bar?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bar = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) center?.onWindowEvent(event)
    }
    override fun onInterrupt() {}
}

@SuppressLint("ViewConstructor")
private class NavBarView(c: Context, private val onAction: (Int, Boolean) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dpf
        strokeCap = Paint.Cap.SQUARE
    }
    private var down = -1
    private var longFired = false
    private val longRun = Runnable {
        if (down == 0 || down == 1) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onAction(down, true)
        }
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(Metro.bg)
        val third = width / 3f
        val cy = height / 2f
        val s = 18.dpf
        for (i in 0..2) {
            val cx = third * i + third / 2
            if (i == down) {
                p.style = Paint.Style.FILL
                p.color = Prefs.accent
                cv.drawCircle(cx, cy, 20.dpf, p)
                p.style = Paint.Style.STROKE
            }
            p.color = Metro.fg
            Glyphs.draw(cv, when (i) { 0 -> Glyphs.ARROW_LEFT; 1 -> Glyphs.START; else -> Glyphs.SEARCH }, cx, cy, s, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
                longFired = false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (down <= 1) postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                val i = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
                if (i == down && !longFired) onAction(i, false)
                down = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                down = -1
                invalidate()
            }
        }
        return true
    }
}

/** After an update or a reboot, gives the service time to bind, then repairs it if One UI left it unbound. */
class ServiceCheckReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val done = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { NavBarService.check(c.applicationContext) }
            done.finish()
        }, 6_000)
    }
}
