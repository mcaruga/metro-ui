package com.mc.metro

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
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
        if (key == "action_center") center?.syncStrip()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Switching between gestures and buttons changes the system bar: follow it.
        hide()
        sync()
        center?.onConfigurationChanged()
    }

    private fun sync() {
        val want = Prefs.navBar && buttonNavigation(this)
        if (want && bar == null) show() else if (!want) hide()
    }

    private fun show() {
        val v = NavBarView(this) { action, long ->
            when (action) {
                0 -> performGlobalAction(if (long) GLOBAL_ACTION_RECENTS else GLOBAL_ACTION_BACK)
                1 -> performGlobalAction(GLOBAL_ACTION_HOME)
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
        if (down == 0) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onAction(0, true)
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
                if (down == 0) postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong())
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
