package com.mc.metro

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Windows Phone 8.1 style action center: a transparent strip over the status bar catches the
 * swipe down and pulls in a Metro panel with quick actions, brightness and the notifications,
 * in place of the One UI shade. Lives inside the accessibility service (its overlay windows sit
 * above the status bar and may start activities from the background).
 */
class ActionCenter(private val svc: AccessibilityService) {
    companion object {
        @Volatile var current: ActionCenter? = null
    }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val km = svc.getSystemService(KeyguardManager::class.java)
    private var strip: View? = null
    private var panel: Panel? = null
    private var openedAt = 0L
    private var torchId: String? = null
    var torchOn = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { close(false); syncStrip() }
                else -> syncStrip()
            }
        }
    }

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, on: Boolean) {
            if (id == torchId) { torchOn = on; panel?.refreshTiles() }
        }
    }

    fun start() {
        current = this
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        svc.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        runCatching {
            val cm = svc.getSystemService(CameraManager::class.java)
            torchId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            cm.registerTorchCallback(torchCb, Live.main)
        }
        syncStrip()
    }

    fun stop() {
        close(false)
        removeStrip()
        runCatching { svc.unregisterReceiver(receiver) }
        runCatching { svc.getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCb) }
        if (current === this) current = null
    }

    /** The strip is only there in portrait, unlocked: the lock screen keeps the One UI shade. */
    fun syncStrip() {
        val want = Prefs.actionCenter && !km.isKeyguardLocked &&
            svc.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        if (want && strip == null) addStrip() else if (!want) removeStrip()
    }

    fun onConfigurationChanged() {
        close(false)
        removeStrip()
        syncStrip()
    }

    /** Leaving to another app (home, recents, a launched activity) closes the panel. */
    fun onWindowEvent(e: AccessibilityEvent) {
        if (panel == null || System.currentTimeMillis() - openedAt < 600) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == "com.android.systemui") return
        if (pkg == svc.packageName && e.className?.endsWith("Activity") != true) return
        val ime = Settings.Secure.getString(svc.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        if (pkg == ime) return
        close(false)
    }

    private fun statusHeight(): Int {
        val top = runCatching { wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top }.getOrDefault(0)
        if (top > 0) return top
        val id = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else 28.dp
    }

    private fun navHeight(): Int =
        runCatching { wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom }.getOrDefault(0)

    private fun overlayParams(h: Int, focusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun addStrip() {
        val v = StripView(svc)
        try {
            wm.addView(v, overlayParams(max(statusHeight(), 24.dp), false))
            strip = v
        } catch (e: Exception) {
            strip = null
        }
    }

    private fun removeStrip() {
        strip?.let { runCatching { wm.removeView(it) } }
        strip = null
    }

    /** Opens the panel; with [dragging] it follows the finger until [release]. */
    fun open(dragging: Boolean = false) {
        if (panel == null) {
            val p = Panel(svc, this, statusHeight(), navHeight())
            try {
            wm.addView(p, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, true))
            } catch (e: Exception) {
                return
            }
            panel = p
            openedAt = System.currentTimeMillis()
        }
        if (!dragging) panel?.settle(true)
    }

    fun drag(y: Float) = panel?.dragTo(y)
    fun release(vy: Float) = panel?.release(vy)

    fun close(animate: Boolean = true, then: (() -> Unit)? = null) {
        val p = panel ?: run { then?.invoke(); return }
        if (animate) {
            p.settle(false) { remove(p); then?.invoke() }
        } else {
            remove(p)
            then?.invoke()
        }
    }

    private fun remove(p: Panel) {
        p.detach()
        runCatching { wm.removeView(p) }
        if (panel === p) panel = null
    }

    /** Starts an activity from the service, after the panel is gone. */
    fun launch(i: Intent) {
        close {
            runCatching { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun systemShade() = close(false) { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }

    fun global(action: Int) = close { Live.main.postDelayed({ svc.performGlobalAction(action) }, 350) }

    fun setTorch(on: Boolean) {
        val id = torchId ?: return
        runCatching { svc.getSystemService(CameraManager::class.java).setTorchMode(id, on) }
    }

    /** Sends a notification's intent with the service's right to start activities. */
    fun send(pi: PendingIntent?): Boolean {
        pi ?: return false
        val opts = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        return runCatching { pi.send(svc, 0, null, null, null, null, opts) }.isSuccess
    }

    @SuppressLint("ViewConstructor")
    private inner class StripView(c: Context) : View(c) {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var y0 = 0f
        private var dragging = false
        private var longFired = false
        private var vt: VelocityTracker? = null
        private val longRun = Runnable {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            systemShade()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (km.isKeyguardLocked) {
                        post { syncStrip() }
                        return false
                    }
                    y0 = e.rawY
                    dragging = false
                    longFired = false
                    vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong() + 150)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    if (longFired) return true
                    if (!dragging && e.rawY - y0 > slop) {
                        removeCallbacks(longRun)
                        dragging = true
                        open(dragging = true)
                    } else if (!dragging && abs(e.rawY - y0) > slop) {
                        removeCallbacks(longRun)
                    }
                    if (dragging) drag(e.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longRun)
                    if (dragging) {
                        vt?.computeCurrentVelocity(1000)
                        // The new panel window can cancel the gesture mid-pull: the pull was meant, so open.
                        release(if (e.actionMasked == MotionEvent.ACTION_CANCEL) 10_000f else vt?.yVelocity ?: 0f)
                    }
                    vt?.recycle()
                    vt = null
                    dragging = false
                }
            }
            return true
        }
    }
}

private const val SLIDE_MS = 320f

/** One quick action: state, tap, and the system page a long press opens. [glyph] is also its id. */
class Quick(
    val glyph: Int,
    val label: (Boolean) -> String,
    val state: (Context, ActionCenter) -> Boolean,
    val page: String?,
    val tap: (Context, ActionCenter) -> Unit,
)

object Q {
    const val WIFI = 0
    const val BT = 1
    const val ROTATE = 2
    const val TORCH = 3
    const val DND = 4
    const val RINGER = 5
    const val PLANE = 6
    const val LOCATION = 7
    const val AUTO = 8
    const val SAVER = 9
    const val SHOT = 10
    const val LOCK = 11
    const val SUN = 12
    const val BELL = 13
    const val MUSIC = 14
    const val SCREEN_REC = 15
    const val AUDIO_REC = 16
    const val VOLUME = 17
    const val HOTSPOT = 18
    const val NFC = 19
    const val DATA = 20
    const val CAST = 21
    const val SYNC = 22
    const val CAMERA = 23
    const val CALC = 24
    const val ALARM = 25

    /** The first twelve, in this order, until the user changes them. */
    val DEFAULT = listOf(WIFI, BT, ROTATE, TORCH, DND, RINGER, PLANE, LOCATION, AUTO, SAVER, SHOT, LOCK)

    /** Quick actions shown in the panel, in the user's order. */
    fun enabled(): List<Quick> = Prefs.acTiles.mapNotNull { id -> all.firstOrNull { it.glyph == id } }

    private fun pm(c: Context) = c.packageManager
    private fun resolves(c: Context, i: Intent) = i.resolveActivity(pm(c)) != null

    /** Shazam if installed, then the Google app's song search, else Shazam on the Play Store. */
    private fun music(c: Context): Intent {
        val shazam = Intent("com.shazam.android.intent.actions.START_TAGGING").setPackage("com.shazam.android")
        val google = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
        return listOfNotNull(shazam, pm(c).getLaunchIntentForPackage("com.shazam.android"), google).firstOrNull { resolves(c, it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.shazam.android"))
    }

    private fun first(c: Context, vararg list: Intent?): Intent =
        list.filterNotNull().firstOrNull { resolves(c, it) } ?: list.filterNotNull().last()

    private fun canWrite(c: Context) = Settings.System.canWrite(c)

    private fun writePerm(c: Context) =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName))

    private fun sysInt(c: Context, k: String, d: Int) = runCatching { Settings.System.getInt(c.contentResolver, k) }.getOrDefault(d)

    val all = listOf(
        Quick(WIFI, { "Wi-Fi" }, { c, _ -> runCatching { c.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false) },
            Settings.ACTION_WIFI_SETTINGS) { _, ac -> ac.launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) },
        Quick(BT, { "Bluetooth" }, { c, _ -> runCatching { c.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false) },
            Settings.ACTION_BLUETOOTH_SETTINGS) { _, ac -> ac.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        Quick(ROTATE, { "rotazione" }, { c, _ -> sysInt(c, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 },
            Settings.ACTION_DISPLAY_SETTINGS) { c, ac ->
            if (!canWrite(c)) ac.launch(writePerm(c))
            else Settings.System.putInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (sysInt(c, Settings.System.ACCELEROMETER_ROTATION, 0) == 1) 0 else 1)
        },
        Quick(TORCH, { "torcia" }, { _, ac -> ac.torchOn }, null) { _, ac -> ac.setTorch(!ac.torchOn) },
        Quick(DND, { "non disturbare" }, { c, _ ->
            c.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        }, Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) { c, ac ->
            val ns = NotifService.instance
            if (ns == null) ac.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            else {
                val on = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
                ns.requestInterruptionFilter(if (on) NotificationListenerFilter.ALL else NotificationListenerFilter.PRIORITY)
            }
        },
        Quick(RINGER, { if (it) "vibrazione" else "suoneria" }, { c, _ ->
            c.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL
        }, Settings.ACTION_SOUND_SETTINGS) { c, _ ->
            val am = c.getSystemService(AudioManager::class.java)
            runCatching {
                am.ringerMode = if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            }
        },
        Quick(PLANE, { "modalità aereo" }, { c, _ -> Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 },
            Settings.ACTION_AIRPLANE_MODE_SETTINGS) { _, ac -> ac.launch(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) },
        Quick(LOCATION, { "posizione" }, { c, _ -> runCatching { c.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false) },
            Settings.ACTION_LOCATION_SOURCE_SETTINGS) { _, ac -> ac.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        Quick(AUTO, { "luminosità auto" }, { c, _ -> sysInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC },
            Settings.ACTION_DISPLAY_SETTINGS) { c, ac ->
            if (!canWrite(c)) ac.launch(writePerm(c))
            else {
                val auto = sysInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            }
        },
        Quick(SAVER, { "risparmio" }, { c, _ -> c.getSystemService(PowerManager::class.java).isPowerSaveMode },
            Settings.ACTION_BATTERY_SAVER_SETTINGS) { _, ac -> ac.launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
        Quick(SHOT, { "screenshot" }, { _, _ -> false }, null) { _, ac -> ac.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) },
        Quick(LOCK, { "blocca" }, { _, _ -> false }, null) { _, ac -> ac.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) },
        Quick(MUSIC, { "riconosci musica" }, { _, _ -> false }, null) { c, ac -> ac.launch(music(c)) },
        Quick(SCREEN_REC, { if (it) "ferma registrazione" else "registra schermo" }, { _, _ -> Recorder.running == Recorder.SCREEN }, null) { c, ac ->
            if (Recorder.running != null) Recorder.stop(c) else ac.launch(Recorder.intent(c, Recorder.SCREEN))
        },
        Quick(AUDIO_REC, { if (it) "ferma registrazione" else "registra audio" }, { _, _ -> Recorder.running == Recorder.AUDIO }, null) { c, ac ->
            if (Recorder.running != null) Recorder.stop(c) else ac.launch(Recorder.intent(c, Recorder.AUDIO))
        },
        Quick(VOLUME, { "volume" }, { _, _ -> false }, Settings.ACTION_SOUND_SETTINGS) { c, ac ->
            ac.close {
                c.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            }
        },
        Quick(HOTSPOT, { "hotspot" }, { _, _ -> false }, null) { c, ac ->
            ac.launch(first(c, Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"), Intent(Settings.ACTION_WIRELESS_SETTINGS)))
        },
        Quick(NFC, { "NFC" }, { c, _ -> runCatching { android.nfc.NfcAdapter.getDefaultAdapter(c)?.isEnabled == true }.getOrDefault(false) },
            Settings.ACTION_NFC_SETTINGS) { c, ac -> ac.launch(first(c, Intent(Settings.Panel.ACTION_NFC), Intent(Settings.ACTION_NFC_SETTINGS))) },
        Quick(DATA, { "dati mobili" }, { c, _ -> Settings.Global.getInt(c.contentResolver, "mobile_data", 0) == 1 },
            Settings.ACTION_DATA_ROAMING_SETTINGS) { _, ac -> ac.launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) },
        Quick(CAST, { "trasmetti" }, { _, _ -> false }, null) { _, ac -> ac.launch(Intent(Settings.ACTION_CAST_SETTINGS)) },
        Quick(SYNC, { "sincronizza" }, { _, _ -> android.content.ContentResolver.getMasterSyncAutomatically() },
            Settings.ACTION_SYNC_SETTINGS) { _, _ ->
            android.content.ContentResolver.setMasterSyncAutomatically(!android.content.ContentResolver.getMasterSyncAutomatically())
        },
        Quick(CAMERA, { "fotocamera" }, { _, _ -> false }, null) { _, ac -> ac.launch(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) },
        Quick(CALC, { "calcolatrice" }, { _, _ -> false }, null) { c, ac ->
            ac.launch(first(c, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR),
                pm(c).getLaunchIntentForPackage("com.sec.android.app.popupcalculator"), pm(c).getLaunchIntentForPackage("com.google.android.calculator")))
        },
        Quick(ALARM, { "sveglie" }, { _, _ -> false }, null) { _, ac -> ac.launch(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) },
    )

    private val path = Path()
    private val rect = RectF()

    private fun arrowHead(cv: Canvas, x: Float, y: Float, dx: Float, dy: Float, len: Float, p: Paint) {
        for (a in floatArrayOf(2.6f, -2.6f)) {
            val rx = dx * cos(a) - dy * sin(a)
            val ry = dx * sin(a) + dy * cos(a)
            cv.drawLine(x, y, x + rx * len, y + ry * len, p)
        }
    }

    /** Line glyphs on a [s]-sized box centred on (cx, cy); [p] is a stroke paint. */
    fun draw(cv: Canvas, g: Int, cx: Float, cy: Float, s: Float, p: Paint, on: Boolean) {
        path.reset()
        when (g) {
            WIFI -> {
                val oy = cy + s * 0.32f
                for (r in floatArrayOf(0.26f, 0.5f, 0.74f)) {
                    rect.set(cx - r * s, oy - r * s, cx + r * s, oy + r * s)
                    cv.drawArc(rect, -135f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                cv.drawCircle(cx, oy - s * 0.04f, s * 0.07f, p)
                p.style = Paint.Style.STROKE
            }
            BT -> {
                path.moveTo(cx - s * 0.22f, cy - s * 0.2f)
                path.lineTo(cx + s * 0.2f, cy + s * 0.2f)
                path.lineTo(cx, cy + s * 0.42f)
                path.lineTo(cx, cy - s * 0.42f)
                path.lineTo(cx + s * 0.2f, cy - s * 0.2f)
                path.lineTo(cx - s * 0.22f, cy + s * 0.2f)
                cv.drawPath(path, p)
            }
            ROTATE -> {
                val r = s * 0.36f
                rect.set(cx - r, cy - r, cx + r, cy + r)
                cv.drawArc(rect, -60f, 290f, false, p)
                val a = Math.toRadians(-60.0)
                val x = cx + r * cos(a).toFloat()
                val y = cy + r * sin(a).toFloat()
                arrowHead(cv, x, y, sin(a).toFloat(), -cos(a).toFloat(), s * 0.2f, p)
                if (!on) cv.drawLine(cx - s * 0.12f, cy - s * 0.12f, cx + s * 0.12f, cy + s * 0.12f, p)
            }
            TORCH -> {
                path.moveTo(cx - s * 0.28f, cy - s * 0.44f)
                path.lineTo(cx + s * 0.28f, cy - s * 0.44f)
                path.lineTo(cx + s * 0.28f, cy - s * 0.28f)
                path.lineTo(cx + s * 0.14f, cy - s * 0.08f)
                path.lineTo(cx + s * 0.14f, cy + s * 0.46f)
                path.lineTo(cx - s * 0.14f, cy + s * 0.46f)
                path.lineTo(cx - s * 0.14f, cy - s * 0.08f)
                path.lineTo(cx - s * 0.28f, cy - s * 0.28f)
                path.close()
                cv.drawPath(path, p)
                cv.drawLine(cx, cy + s * 0.04f, cx, cy + s * 0.18f, p)
            }
            DND -> {
                val r = s * 0.4f
                cv.drawCircle(cx, cy, r, p)
                cv.drawLine(cx - r * 0.55f, cy, cx + r * 0.55f, cy, p)
            }
            RINGER -> if (on) {
                cv.drawRect(cx - s * 0.16f, cy - s * 0.32f, cx + s * 0.16f, cy + s * 0.32f, p)
                for (d in floatArrayOf(-1f, 1f)) {
                    cv.drawLine(cx + d * s * 0.3f, cy - s * 0.18f, cx + d * s * 0.3f, cy + s * 0.18f, p)
                    cv.drawLine(cx + d * s * 0.43f, cy - s * 0.1f, cx + d * s * 0.43f, cy + s * 0.1f, p)
                }
            } else draw(cv, BELL, cx, cy, s, p, true)
            BELL -> {
                path.moveTo(cx - s * 0.34f, cy + s * 0.22f)
                path.lineTo(cx - s * 0.24f, cy + s * 0.08f)
                path.lineTo(cx - s * 0.24f, cy - s * 0.08f)
                path.quadTo(cx - s * 0.24f, cy - s * 0.38f, cx, cy - s * 0.38f)
                path.quadTo(cx + s * 0.24f, cy - s * 0.38f, cx + s * 0.24f, cy - s * 0.08f)
                path.lineTo(cx + s * 0.24f, cy + s * 0.08f)
                path.lineTo(cx + s * 0.34f, cy + s * 0.22f)
                path.close()
                cv.drawPath(path, p)
                cv.drawLine(cx - s * 0.08f, cy + s * 0.34f, cx + s * 0.08f, cy + s * 0.34f, p)
            }
            PLANE -> {
                val pts = floatArrayOf(
                    0f, -0.46f, 0.06f, -0.4f, 0.06f, -0.12f, 0.44f, 0.08f, 0.44f, 0.16f, 0.06f, 0.04f,
                    0.06f, 0.28f, 0.18f, 0.38f, 0.18f, 0.44f, 0f, 0.38f,
                )
                path.moveTo(cx, cy - s * 0.46f)
                var i = 2
                while (i < pts.size) { path.lineTo(cx + pts[i] * s, cy + pts[i + 1] * s); i += 2 }
                i = pts.size - 4
                while (i > 0) { path.lineTo(cx - pts[i] * s, cy + pts[i + 1] * s); i -= 2 }
                path.close()
                cv.drawPath(path, p)
            }
            LOCATION -> {
                val r = s * 0.28f
                val oy = cy - s * 0.12f
                rect.set(cx - r, oy - r, cx + r, oy + r)
                path.arcTo(rect, 150f, 240f)
                path.lineTo(cx, cy + s * 0.45f)
                path.close()
                cv.drawPath(path, p)
                cv.drawCircle(cx, oy, s * 0.09f, p)
            }
            AUTO, SUN -> {
                val r = s * (if (g == AUTO) 0.22f else 0.18f)
                cv.drawCircle(cx, cy, r, p)
                for (k in 0 until 8) {
                    val a = Math.PI / 4 * k
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    cv.drawLine(cx + c * (r + s * 0.1f), cy + sn * (r + s * 0.1f), cx + c * (r + s * 0.22f), cy + sn * (r + s * 0.22f), p)
                }
                if (g == AUTO) {
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = p.color
                        textSize = s * 0.3f
                        typeface = Metro.semibold
                        textAlign = Paint.Align.CENTER
                    }
                    cv.drawText("A", cx, cy + s * 0.1f, tp)
                }
            }
            SAVER -> {
                cv.drawRect(cx - s * 0.2f, cy - s * 0.32f, cx + s * 0.2f, cy + s * 0.44f, p)
                cv.drawRect(cx - s * 0.08f, cy - s * 0.44f, cx + s * 0.08f, cy - s * 0.32f, p)
                cv.drawLine(cx - s * 0.1f, cy + s * 0.06f, cx + s * 0.1f, cy + s * 0.06f, p)
                cv.drawLine(cx, cy - s * 0.04f, cx, cy + s * 0.16f, p)
            }
            SHOT -> {
                val h = s * 0.4f
                val a = s * 0.16f
                for (dx in floatArrayOf(-1f, 1f)) for (dy in floatArrayOf(-1f, 1f)) {
                    val x = cx + dx * h
                    val y = cy + dy * h
                    cv.drawLine(x, y, x - dx * a, y, p)
                    cv.drawLine(x, y, x, y - dy * a, p)
                }
                cv.drawCircle(cx, cy, s * 0.12f, p)
            }
            MUSIC -> {
                cv.drawCircle(cx - s * 0.2f, cy + s * 0.28f, s * 0.13f, p)
                cv.drawCircle(cx + s * 0.26f, cy + s * 0.18f, s * 0.13f, p)
                cv.drawLine(cx - s * 0.07f, cy + s * 0.28f, cx - s * 0.07f, cy - s * 0.36f, p)
                cv.drawLine(cx + s * 0.39f, cy + s * 0.18f, cx + s * 0.39f, cy - s * 0.46f, p)
                cv.drawLine(cx - s * 0.07f, cy - s * 0.36f, cx + s * 0.39f, cy - s * 0.46f, p)
            }
            SCREEN_REC -> {
                cv.drawCircle(cx, cy, s * 0.4f, p)
                p.style = Paint.Style.FILL
                if (on) cv.drawRect(cx - s * 0.14f, cy - s * 0.14f, cx + s * 0.14f, cy + s * 0.14f, p)
                else cv.drawCircle(cx, cy, s * 0.18f, p)
                p.style = Paint.Style.STROKE
            }
            AUDIO_REC -> {
                rect.set(cx - s * 0.14f, cy - s * 0.44f, cx + s * 0.14f, cy + s * 0.12f)
                cv.drawRoundRect(rect, s * 0.14f, s * 0.14f, p)
                rect.set(cx - s * 0.28f, cy - s * 0.2f, cx + s * 0.28f, cy + s * 0.28f)
                cv.drawArc(rect, 0f, 180f, false, p)
                cv.drawLine(cx, cy + s * 0.28f, cx, cy + s * 0.44f, p)
                if (on) { p.style = Paint.Style.FILL; cv.drawCircle(cx + s * 0.36f, cy - s * 0.36f, s * 0.08f, p); p.style = Paint.Style.STROKE }
            }
            VOLUME -> {
                path.moveTo(cx - s * 0.4f, cy - s * 0.12f)
                path.lineTo(cx - s * 0.2f, cy - s * 0.12f)
                path.lineTo(cx + s * 0.04f, cy - s * 0.34f)
                path.lineTo(cx + s * 0.04f, cy + s * 0.34f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.12f)
                path.lineTo(cx - s * 0.4f, cy + s * 0.12f)
                path.close()
                cv.drawPath(path, p)
                rect.set(cx - s * 0.1f, cy - s * 0.24f, cx + s * 0.3f, cy + s * 0.24f)
                cv.drawArc(rect, -50f, 100f, false, p)
                rect.set(cx - s * 0.2f, cy - s * 0.4f, cx + s * 0.44f, cy + s * 0.4f)
                cv.drawArc(rect, -50f, 100f, false, p)
            }
            HOTSPOT -> {
                p.style = Paint.Style.FILL
                cv.drawCircle(cx, cy, s * 0.08f, p)
                p.style = Paint.Style.STROKE
                for (r in floatArrayOf(0.24f, 0.42f)) {
                    rect.set(cx - r * s, cy - r * s, cx + r * s, cy + r * s)
                    cv.drawArc(rect, -230f, 100f, false, p)
                    cv.drawArc(rect, -50f, 100f, false, p)
                }
            }
            NFC -> {
                rect.set(cx - s * 0.38f, cy - s * 0.38f, cx + s * 0.38f, cy + s * 0.38f)
                cv.drawRoundRect(rect, s * 0.1f, s * 0.1f, p)
                cv.drawLine(cx - s * 0.16f, cy + s * 0.18f, cx - s * 0.16f, cy - s * 0.18f, p)
                cv.drawLine(cx - s * 0.16f, cy - s * 0.18f, cx + s * 0.16f, cy + s * 0.18f, p)
                cv.drawLine(cx + s * 0.16f, cy + s * 0.18f, cx + s * 0.16f, cy - s * 0.18f, p)
            }
            DATA -> {
                cv.drawLine(cx - s * 0.16f, cy + s * 0.4f, cx - s * 0.16f, cy - s * 0.4f, p)
                arrowHead(cv, cx - s * 0.16f, cy - s * 0.4f, 0f, -1f, s * 0.18f, p)
                cv.drawLine(cx + s * 0.16f, cy - s * 0.4f, cx + s * 0.16f, cy + s * 0.4f, p)
                arrowHead(cv, cx + s * 0.16f, cy + s * 0.4f, 0f, 1f, s * 0.18f, p)
            }
            CAST -> {
                cv.drawLine(cx - s * 0.42f, cy - s * 0.12f, cx - s * 0.42f, cy - s * 0.34f, p)
                cv.drawLine(cx - s * 0.42f, cy - s * 0.34f, cx + s * 0.42f, cy - s * 0.34f, p)
                cv.drawLine(cx + s * 0.42f, cy - s * 0.34f, cx + s * 0.42f, cy + s * 0.34f, p)
                cv.drawLine(cx + s * 0.42f, cy + s * 0.34f, cx + s * 0.02f, cy + s * 0.34f, p)
                for (r in floatArrayOf(0.2f, 0.38f)) {
                    rect.set(cx - s * 0.42f - r * s, cy + s * 0.34f - r * s, cx - s * 0.42f + r * s, cy + s * 0.34f + r * s)
                    cv.drawArc(rect, -90f, 90f, false, p)
                }
            }
            SYNC -> {
                val r = s * 0.34f
                rect.set(cx - r, cy - r, cx + r, cy + r)
                cv.drawArc(rect, 200f, 140f, false, p)
                cv.drawArc(rect, 20f, 140f, false, p)
                arrowHead(cv, cx + r * cos(Math.toRadians(340.0)).toFloat(), cy + r * sin(Math.toRadians(340.0)).toFloat(), -0.34f, -0.94f, s * 0.16f, p)
                arrowHead(cv, cx + r * cos(Math.toRadians(160.0)).toFloat(), cy + r * sin(Math.toRadians(160.0)).toFloat(), 0.34f, 0.94f, s * 0.16f, p)
            }
            CAMERA -> {
                cv.drawRect(cx - s * 0.42f, cy - s * 0.24f, cx + s * 0.42f, cy + s * 0.34f, p)
                cv.drawLine(cx - s * 0.16f, cy - s * 0.24f, cx - s * 0.08f, cy - s * 0.36f, p)
                cv.drawLine(cx - s * 0.08f, cy - s * 0.36f, cx + s * 0.16f, cy - s * 0.36f, p)
                cv.drawLine(cx + s * 0.16f, cy - s * 0.36f, cx + s * 0.24f, cy - s * 0.24f, p)
                cv.drawCircle(cx, cy + s * 0.05f, s * 0.15f, p)
            }
            CALC -> {
                cv.drawRect(cx - s * 0.32f, cy - s * 0.44f, cx + s * 0.32f, cy + s * 0.44f, p)
                cv.drawRect(cx - s * 0.2f, cy - s * 0.32f, cx + s * 0.2f, cy - s * 0.14f, p)
                p.style = Paint.Style.FILL
                for (i in 0..2) for (j in 0..2) cv.drawCircle(cx - s * 0.16f + i * s * 0.16f, cy + s * 0.02f + j * s * 0.14f, s * 0.035f, p)
                p.style = Paint.Style.STROKE
            }
            ALARM -> {
                cv.drawCircle(cx, cy + s * 0.04f, s * 0.36f, p)
                cv.drawLine(cx, cy + s * 0.04f, cx, cy - s * 0.18f, p)
                cv.drawLine(cx, cy + s * 0.04f, cx + s * 0.16f, cy + s * 0.12f, p)
                cv.drawLine(cx - s * 0.42f, cy - s * 0.3f, cx - s * 0.24f, cy - s * 0.44f, p)
                cv.drawLine(cx + s * 0.42f, cy - s * 0.3f, cx + s * 0.24f, cy - s * 0.44f, p)
            }
            LOCK -> {
                cv.drawRect(cx - s * 0.28f, cy - s * 0.04f, cx + s * 0.28f, cy + s * 0.42f, p)
                rect.set(cx - s * 0.18f, cy - s * 0.42f, cx + s * 0.18f, cy - s * 0.06f)
                cv.drawArc(rect, 180f, 180f, false, p)
                cv.drawLine(cx - s * 0.18f, cy - s * 0.24f, cx - s * 0.18f, cy - s * 0.04f, p)
                cv.drawLine(cx + s * 0.18f, cy - s * 0.24f, cx + s * 0.18f, cy - s * 0.04f, p)
            }
        }
    }
}

/** NotificationListenerService filter constants, named for readability. */
private object NotificationListenerFilter {
    const val ALL = android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_ALL
    const val PRIORITY = android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_PRIORITY
}

/** Square quick action: accent when on, chrome when off, glyph above a small label. */
@SuppressLint("ViewConstructor")
private class QuickTile(c: Context, val q: Quick, private val ac: ActionCenter) : View(c) {
    var on = false
    private var down = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f.dpf
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Metro.regular
        textSize = 11.5f.sp
    }

    init {
        isClickable = true
        isLongClickable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            q.tap(context, ac)
            postDelayed({ refresh() }, 250)
            postDelayed({ refresh() }, 1200)
        }
        setOnLongClickListener {
            val page = q.page ?: return@setOnLongClickListener false
            ac.launch(Intent(page))
            true
        }
    }

    fun refresh() {
        on = runCatching { q.state(context, ac) }.getOrDefault(false)
        contentDescription = q.label(on)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        cv.drawColor(if (on) Prefs.accent else Metro.chrome)
        if (down) cv.drawColor(0x33000000)
        val fg = if (on) Color.WHITE else Metro.fg
        p.color = fg
        Q.draw(cv, q.glyph, w / 2, w * 0.38f, w * 0.34f, p, on)
        tp.color = fg
        val pad = 6.dpf
        val l = StaticLayout.Builder.obtain(q.label(on), 0, q.label(on).length, tp, (w - pad * 2).toInt())
            .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        cv.save()
        cv.translate(pad, w - pad - l.height)
        l.draw(cv)
        cv.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return super.onTouchEvent(e)
    }
}

/** Metro slider: thin track, accent fill, upright thumb. */
private class MetroSlider(c: Context, private val onChange: (Float, Boolean) -> Unit) : View(c) {
    var value = 0.5f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 40.dp)

    override fun onDraw(cv: Canvas) {
        val l = 6.dpf
        val r = width - 6.dpf
        val cy = height / 2f
        val x = l + (r - l) * value
        p.color = Metro.chrome
        cv.drawRect(l, cy - 2.dpf, r, cy + 2.dpf, p)
        p.color = Prefs.accent
        cv.drawRect(l, cy - 2.dpf, x, cy + 2.dpf, p)
        p.color = Metro.fg
        cv.drawRect(x - 5.dpf, cy - 13.dpf, x + 5.dpf, cy + 13.dpf, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        value = ((e.x - 6.dpf) / (width - 12.dpf)).coerceIn(0f, 1f)
        invalidate()
        val done = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        onChange(value, done)
        return true
    }
}

/** A notification you can tap to open or swipe right to dismiss, as on Windows Phone. */
@SuppressLint("ViewConstructor")
private class NotifRow(c: Context, val clearable: Boolean, private val onTap: () -> Unit, private val onDismiss: () -> Unit) : FrameLayout(c) {
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var x0 = 0f
    private var y0 = 0f
    private var swiping = false
    private var vt: VelocityTracker? = null

    init {
        isClickable = true
        setOnClickListener { onTap() }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> if (clearable && e.rawX - x0 > slop && abs(e.rawX - x0) > abs(e.rawY - y0) * 1.5f) {
                swiping = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            x0 = e.rawX; y0 = e.rawY
            vt = VelocityTracker.obtain()
            setBackgroundColor(Metro.chrome)
        }
        vt?.addMovement(e)
        if (!swiping && clearable && e.actionMasked == MotionEvent.ACTION_MOVE &&
            e.rawX - x0 > slop && abs(e.rawX - x0) > abs(e.rawY - y0) * 1.5f
        ) {
            swiping = true
            parent?.requestDisallowInterceptTouchEvent(true)
            cancelLongPress()
        }
        if (swiping) {
            setBackgroundColor(0)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    translationX = max(0f, e.rawX - x0)
                    alpha = 1f - translationX / width * 0.8f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val fling = (vt?.xVelocity ?: 0f) > 1200f
                    if (e.actionMasked == MotionEvent.ACTION_UP && (translationX > width * 0.35f || fling)) dismiss()
                    else animate().translationX(0f).alpha(1f).setDuration(160).start()
                    swiping = false
                    vt?.recycle(); vt = null
                }
            }
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            setBackgroundColor(0)
            vt?.recycle(); vt = null
        }
        return super.onTouchEvent(e)
    }

    fun dismiss(delay: Long = 0) {
        animate().translationX(width.toFloat()).alpha(0f).setStartDelay(delay).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { onDismiss() }
            }).start()
    }
}

/** The pulled-down panel: header, quick actions, brightness, notifications, handle. */
@SuppressLint("ViewConstructor")
private class Panel(private val c: Context, private val ac: ActionCenter, statusH: Int, navH: Int) : FrameLayout(c) {
    private val sheet = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val tiles = ArrayList<QuickTile>()
    private val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(c)
    private val handle = View(c)
    private val battery = c.label("", 15f, Metro.sub, Metro.regular)
    private val clearAll = c.label("cancella tutto", 15f, Prefs.accent, Metro.regular)
    private lateinit var slider: MetroSlider
    private var anim: ValueAnimator? = null
    private var progress = 0f
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var y0 = 0f
    private var dragFrom = 0f
    private var closing = false
    private var vt: VelocityTracker? = null
    private val rows = ArrayList<NotifRow>()
    private val onNotifs: () -> Unit = { fillNotifs() }
    private val pm = c.packageManager

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0)
        sheet.setBackgroundColor(Metro.bg)
        sheet.setPadding(16.dp, statusH + 10.dp, 16.dp, navH)
        sheet.isClickable = true
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildHeader()
        buildQuick()
        buildBrightness()
        buildList()
        handle.setOnClickListener { ac.close() }
        sheet.addView(handle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 36.dp))
        handle.background = object : android.graphics.drawable.Drawable() {
            val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Metro.sub }
            override fun draw(cv: Canvas) {
                val b = bounds
                cv.drawRect(b.exactCenterX() - 22.dpf, b.exactCenterY() - 1.5f.dpf, b.exactCenterX() + 22.dpf, b.exactCenterY() + 1.5f.dpf, hp)
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(f: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        Notifs.addListener(onNotifs)
        fillNotifs()
        setProgress(0f)
        post { requestFocus() }
    }

    fun detach() {
        Notifs.removeListener(onNotifs)
        anim?.cancel()
    }

    private fun buildHeader() {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val clock = TextClock(c).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 46f
            typeface = Metro.light
            setTextColor(Metro.fg)
            includeFontPadding = false
        }
        row.addView(clock)
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(0, 0, 0, 4.dp) }
        val date = TextClock(c).apply {
            val f = DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM")
            format12Hour = f
            format24Hour = f
            textSize = 15f
            typeface = Metro.regular
            setTextColor(Metro.fg)
        }
        right.addView(date)
        right.addView(battery)
        row.addView(right, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 12.dp })
        sheet.addView(row)
        updateBattery()

        val links = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 6.dp, 0, 10.dp) }
        fun link(t: String, f: () -> Unit) = c.label(t.uppercase(), 13f, Metro.fg, Metro.semibold).apply {
            setPadding(12.dp, 8.dp, 0, 8.dp)
            letterSpacing = 0.04f
            isClickable = true
            setOnClickListener { f() }
        }
        links.addView(link("One UI") { ac.systemShade() })
        links.addView(link("tutte le impostazioni") { ac.launch(Intent(Settings.ACTION_SETTINGS)) })
        sheet.addView(links)
    }

    private fun updateBattery() {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        battery.text = if (lvl < 0) "" else "batteria ${lvl * 100 / max(scale, 1)}%" + if (plugged) " · in carica" else ""
    }

    private fun buildQuick() {
        sheet.addView(grid)
        val more = c.label("", 13f, Metro.fg, Metro.semibold).apply {
            setPadding(0, 6.dp, 0, 6.dp)
            letterSpacing = 0.04f
            gravity = Gravity.END
            isClickable = true
        }
        fun layout() {
            grid.removeAllViews()
            tiles.clear()
            val list = Q.enabled()
            val n = if (Prefs.acExpanded) list.size else 4
            list.take(n).chunked(4).forEach { chunk ->
                val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEachIndexed { i, q ->
                    val t = QuickTile(c, q, ac)
                    tiles += t
                    r.addView(t, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i < 3) rightMargin = 6.dp })
                }
                // A short last row keeps square tiles.
                for (i in chunk.size until 4) r.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { if (i < 3) rightMargin = 6.dp })
                grid.addView(r, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp })
            }
            more.text = if (Prefs.acExpanded) "RIDUCI" else "ESPANDI"
            refreshTiles()
        }
        more.setOnClickListener { Prefs.acExpanded = !Prefs.acExpanded; layout() }
        layout()
        val edit = c.label("MODIFICA", 13f, Metro.fg, Metro.semibold).apply {
            setPadding(0, 6.dp, 0, 6.dp)
            letterSpacing = 0.04f
            isClickable = true
            setOnClickListener {
                ac.launch(Intent(c, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_QUICK, true))
            }
        }
        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(edit, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(more, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun refreshTiles() = tiles.forEach { it.refresh() }

    private fun buildBrightness() {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sun = object : View(c) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f.dpf; color = Metro.fg; strokeCap = Paint.Cap.ROUND }
            override fun onDraw(cv: Canvas) = Q.draw(cv, Q.SUN, width / 2f, height / 2f, width * 0.8f, sp, true)
        }
        row.addView(sun, LinearLayout.LayoutParams(28.dp, 28.dp).apply { rightMargin = 8.dp })
        slider = MetroSlider(c) { v, _ ->
            if (!Settings.System.canWrite(c)) {
                ac.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
                return@MetroSlider
            }
            runCatching {
                val cr = c.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v.pow(2.2f) * 254 + 1).toInt())
            }
            tiles.firstOrNull { it.q.glyph == Q.AUTO }?.refresh()
        }
        val b = runCatching { Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        slider.value = ((b - 1) / 254f).coerceIn(0f, 1f).pow(1 / 2.2f)
        row.addView(slider, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp })
    }

    private fun buildList() {
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(c.label("notifiche", 26f), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        clearAll.setPadding(12.dp, 8.dp, 0, 6.dp)
        clearAll.isClickable = true
        clearAll.setOnClickListener { clearEverything() }
        head.addView(clearAll)
        sheet.addView(head)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(list)
        sheet.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun clearEverything() {
        val ns = NotifService.instance ?: return
        val gone = rows.filter { it.clearable }
        if (gone.isEmpty()) return
        gone.forEachIndexed { i, r -> r.dismiss(i * 35L) }
        postDelayed({ runCatching { ns.cancelAllNotifications() } }, gone.size * 35L + 200)
    }

    private fun appName(pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun whenText(t: Long): String {
        val d = System.currentTimeMillis() - t
        return when {
            d < 60_000 -> "ora"
            d < 3_600_000 -> "${d / 60_000} min"
            android.text.format.DateUtils.isToday(t) -> DateFormat.getTimeFormat(c).format(java.util.Date(t))
            else -> DateFormat.format("d MMM", t).toString()
        }
    }

    private fun texts(n: Notification): Pair<String, String> {
        val ex = n.extras
        val title = (ex?.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ex?.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return title to text
    }

    private fun fillNotifs() {
        list.removeAllViews()
        rows.clear()
        val ns = NotifService.instance
        if (ns == null) {
            list.addView(c.label("concedi a Metro l'accesso alle notifiche per vederle qui", 17f, Metro.sub, Metro.regular).apply {
                maxLines = 3
                setPadding(0, 12.dp, 0, 12.dp)
                isClickable = true
                setOnClickListener { ac.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            })
            clearAll.visibility = GONE
            return
        }
        val all = runCatching { ns.activeNotifications }.getOrNull().orEmpty()
        val withChildren = all.filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }.map { it.groupKey }.toSet()
        val shown = all.filter { s ->
            val summary = (s.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            val (t, x) = texts(s.notification)
            !(summary && s.groupKey in withChildren) && (t.isNotBlank() || x.isNotBlank())
        }
        val groups = shown.groupBy { it.packageName }.entries.sortedByDescending { e -> e.value.maxOf { it.postTime } }
        for ((pkg, items) in groups) {
            list.addView(appHeader(pkg, items.first()))
            items.sortedByDescending { it.postTime }.forEach { list.addView(item(it, ns)) }
        }
        clearAll.visibility = if (rows.any { it.clearable }) VISIBLE else GONE
        if (groups.isEmpty()) {
            list.addView(c.label("nessuna nuova notifica", 18f, Metro.sub).apply { setPadding(0, 12.dp, 0, 12.dp) })
        }
    }

    private fun appHeader(pkg: String, s: StatusBarNotification): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 14.dp, 0, 4.dp) }
        val icon = ImageView(c).apply {
            setBackgroundColor(Prefs.accent)
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            runCatching { setImageDrawable(s.notification.smallIcon?.loadDrawable(c)?.mutate()?.apply { setTint(Color.WHITE) }) }
        }
        row.addView(icon, LinearLayout.LayoutParams(24.dp, 24.dp).apply { rightMargin = 10.dp })
        row.addView(c.label(appName(pkg).uppercase(), 13f, Metro.fg, Metro.semibold).apply { letterSpacing = 0.04f })
        return row
    }

    private fun item(s: StatusBarNotification, ns: NotifService): View {
        val n = s.notification
        val (title, text) = texts(n)
        val clearable = s.isClearable && !s.isOngoing
        lateinit var row: NotifRow
        row = NotifRow(c, clearable, onTap = {
            val ok = ac.send(n.contentIntent)
            if (ok && (n.flags and Notification.FLAG_AUTO_CANCEL) != 0) runCatching { ns.cancelNotification(s.key) }
            ac.close()
        }, onDismiss = {
            runCatching { ns.cancelNotification(s.key) }
            list.removeView(row)
        })
        val body = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(34.dp, 6.dp, 0, 8.dp) }
        val big = runCatching { n.getLargeIcon()?.loadDrawable(c) }.getOrNull()
        if (big != null) {
            body.addView(ImageView(c).apply { setImageDrawable(big); scaleType = ImageView.ScaleType.CENTER_CROP },
                LinearLayout.LayoutParams(40.dp, 40.dp).apply { rightMargin = 10.dp; topMargin = 2.dp })
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        top.addView(c.label(title.ifBlank { appName(s.packageName) }, 17f, Metro.fg, Metro.semibold).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(c.label(whenText(s.postTime), 13f, Metro.sub, Metro.regular).apply { setPadding(8.dp, 0, 0, 2.dp) })
        col.addView(top)
        if (text.isNotBlank()) col.addView(c.label(text, 15f, Metro.sub, Metro.regular).apply { maxLines = 3 })
        val acts = n.actions?.filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }.orEmpty().take(3)
        if (acts.isNotEmpty()) {
            val ar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4.dp, 0, 0) }
            acts.forEach { a ->
                ar.addView(c.label(a.title?.toString().orEmpty().uppercase(), 13f, Prefs.accent, Metro.semibold).apply {
                    letterSpacing = 0.04f
                    setPadding(0, 8.dp, 18.dp, 4.dp)
                    isClickable = true
                    setOnClickListener { ac.send(a.actionIntent) }
                })
            }
            col.addView(ar)
        }
        body.addView(col, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(body)
        rows += row
        return row
    }

    // ---- motion -----------------------------------------------------------------------------

    private fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        val h = if (height > 0) height.toFloat() else c.resources.displayMetrics.heightPixels.toFloat()
        sheet.translationY = -h * (1f - progress)
    }

    /** Finger at screen y while pulling down from the status bar. */
    fun dragTo(y: Float) {
        anim?.cancel()
        val h = if (height > 0) height.toFloat() else c.resources.displayMetrics.heightPixels.toFloat()
        setProgress((y + 120.dp) / h)
    }

    fun release(vy: Float) {
        settle(vy > 600f || (vy > -600f && progress > 0.35f))
    }

    fun settle(open: Boolean, done: (() -> Unit)? = null) {
        anim?.cancel()
        if (!open) closing = true
        anim = ValueAnimator.ofFloat(progress, if (open) 1f else 0f).apply {
            duration = (SLIDE_MS * abs((if (open) 1f else 0f) - progress)).toLong().coerceAtLeast(80)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    if (open) { refreshTiles(); updateBattery() } else if (done == null) ac.close(false)
                    done?.invoke()
                }
            })
            start()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed && anim?.isRunning != true) setProgress(progress)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) ac.close()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    /** Upward drags outside the notification list push the panel back up. */
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (closing) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                y0 = e.rawY
                vt?.recycle()
                vt = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                val inList = e.y >= scroll.top + sheet.translationY && e.y < scroll.bottom + sheet.translationY && scroll.canScrollVertically(1)
                if (!inList && y0 - e.rawY > slop) {
                    dragFrom = progress
                    return true
                }
            }
        }
        vt?.addMovement(e)
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A tap below the sheet (while it is still sliding in) closes it.
                if (e.y > sheet.bottom + sheet.translationY) { ac.close(); return true }
                y0 = e.rawY
                dragFrom = progress
            }
            MotionEvent.ACTION_MOVE -> {
                anim?.cancel()
                setProgress(dragFrom - (y0 - e.rawY) / height)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                settle(!(vy < -600f || (vy < 600f && progress < 0.75f)))
                vt?.recycle(); vt = null
            }
        }
        return true
    }
}

