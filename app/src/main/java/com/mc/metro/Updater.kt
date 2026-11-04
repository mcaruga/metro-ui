package com.mc.metro

import android.app.Activity
import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Over-the-air updates from the user's own server. The server only hosts static files:
 * `version.json` ({versionCode, versionName, apk, sha256, notes}) next to the APK.
 * Android itself refuses an update not signed with the same key as the installed app.
 */
object Updater {
    class Release(val code: Int, val name: String, val apk: URL, val sha256: String?, val notes: String)

    private const val AUTO_EVERY = 3 * 60 * 60 * 1000L
    private const val JOB_ID = 4201
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4201
    const val EXTRA_UPDATE = "com.mc.metro.UPDATE"
    @Volatile private var busy = false

    private fun base(): String = Prefs.updateUrl.trim().let { if (it.endsWith("/")) it else "$it/" }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", "MetroLauncher/${BuildConfig.VERSION_NAME}")
        useCaches = false
    }

    /** Latest release on the server, or null if it is not newer than this build. Network: call off the main thread. */
    fun check(): Release? {
        val u = URL(URL(base()), "version.json")
        val c = open(u)
        val j = try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
        val r = Release(
            j.getInt("versionCode"),
            j.optString("versionName", j.getInt("versionCode").toString()),
            URL(u, j.getString("apk")),
            j.optString("sha256").ifEmpty { null },
            j.optString("notes"),
        )
        return if (r.code > BuildConfig.VERSION_CODE) r else null
    }

    /**
     * Background check every 3 hours (with network), also when Metro is not open: a new version
     * posts a notification. Called at app start; cancelled when automatic checks are off.
     */
    fun schedule(c: Context) {
        val js = c.getSystemService(android.app.job.JobScheduler::class.java) ?: return
        if (!Prefs.autoUpdate) {
            js.cancel(JOB_ID)
            return
        }
        if (js.getPendingJob(JOB_ID) != null) return
        val job = android.app.job.JobInfo.Builder(JOB_ID, android.content.ComponentName(c, UpdateJob::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(AUTO_EVERY, 60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }

    /** Job body: notify once per new version. Network: off the main thread. */
    fun backgroundCheck(c: Context) {
        val r = try { check() } catch (e: Exception) { null } ?: return
        if (r.code <= Prefs.notifiedUpdate) return
        notify(c, r)
        Prefs.notifiedUpdate = r.code
    }

    fun notify(c: Context, r: Release) {
        val nm = c.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "aggiornamenti Metro", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val open = Intent(c, SettingsActivity::class.java)
            .putExtra(EXTRA_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setColor(Prefs.accent)
            .setContentTitle("Metro ${r.name} disponibile")
            .setContentText(r.notes.ifBlank { "tocca per scaricare e installare l'aggiornamento" })
            .setStyle(android.app.Notification.BigTextStyle().bigText(
                (if (r.notes.isNotBlank()) r.notes + "\n\n" else "") + "Tocca per scaricare e installare."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** Clears the notification once this version is the latest one installed. */
    fun clearNotification(c: Context) {
        if (Prefs.notifiedUpdate <= BuildConfig.VERSION_CODE) c.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /** Silent background check from the start screen; offers the update when there is one. */
    fun autoCheck(a: Activity) {
        if (!Prefs.autoUpdate || busy) return
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck < AUTO_EVERY) return
        Prefs.lastUpdateCheck = now
        Thread {
            val r = try { check() } catch (e: Exception) { null } ?: return@Thread
            a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) offer(a, r) }
        }.start()
    }

    /** Metro-style prompt: "aggiorna" downloads, verifies and hands the APK to the system installer. */
    fun offer(a: Activity, r: Release) {
        val d = Dialog(a, android.R.style.Theme_Material_NoActionBar)
        d.window?.setBackgroundDrawable(ColorDrawable(Metro.scrim))
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Metro.chrome)
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        box.addView(a.label("aggiornamento", 28f))
        box.addView(a.label("Metro ${r.name} è disponibile. Hai la ${BuildConfig.VERSION_NAME}.", 16f, Metro.fg, Metro.regular).apply {
            maxLines = 3
            setPadding(0, 10.dp, 0, 0)
        })
        if (r.notes.isNotBlank()) box.addView(a.label(r.notes, 14f, Metro.sub, Metro.regular).apply {
            maxLines = 8
            setPadding(0, 8.dp, 0, 0)
        })
        val msg = a.label("", 14f, Metro.sub, Metro.regular).apply { setPadding(0, 8.dp, 0, 0) }
        box.addView(msg)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16.dp, 0, 0) }
        fun btn(t: String, bg: Int, f: () -> Unit) = a.label(t, 17f, if (bg == Metro.bg) Metro.fg else android.graphics.Color.WHITE, Metro.regular).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setOnClickListener { f() }
        }
        row.addView(btn("aggiorna", Prefs.accent) { install(a, r) { msg.text = it } },
            LinearLayout.LayoutParams(0, 48.dp, 1f).apply { rightMargin = 8.dp })
        row.addView(btn("più tardi", Metro.bg) { d.dismiss() }, LinearLayout.LayoutParams(0, 48.dp, 1f))
        box.addView(row)
        val wrap = FrameLayout(a)
        wrap.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = 120.dp })
        wrap.setOnClickListener { if (!busy) d.dismiss() }
        d.setContentView(wrap)
        d.show()
    }

    /** Download + verify + install. [status] gets Italian progress text on the main thread. */
    fun install(a: Activity, r: Release, status: (String) -> Unit) {
        if (busy) return
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "consenti a Metro di installare app, poi tocca di nuovo aggiorna", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            return
        }
        busy = true
        status("scaricamento…")
        Thread {
            try {
                val f = download(a, r) { p -> a.runOnUiThread { status("scaricamento $p%") } }
                a.runOnUiThread { status("installazione…") }
                commit(a, f)
            } catch (e: Exception) {
                a.runOnUiThread { status("errore: " + (e.message ?: e.javaClass.simpleName)) }
            } finally {
                busy = false
            }
        }.start()
    }

    private fun download(c: Context, r: Release, progress: (Int) -> Unit): File {
        val dir = File(c.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "metro.apk")
        val conn = open(r.apk)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var last = -1
            conn.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else -1
                        if (p != last && p >= 0) { last = p; progress(p) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !hash.equals(r.sha256, true)) {
            f.delete()
            throw java.io.IOException("file corrotto (sha256 diverso)")
        }
        val info = c.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
        if (info?.packageName != c.packageName) {
            f.delete()
            throw java.io.IOException("il file non è un aggiornamento di Metro")
        }
        return f
    }

    private fun commit(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(f.length())
            // Self-updates may skip the confirmation on Android 12+; the system asks anyway when it must.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("metro.apk", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val i = Intent(c, UpdateReceiver::class.java)
            val p = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(p.intentSender)
        }
    }
}

/** Periodic background check scheduled by [Updater.schedule]. */
class UpdateJob : android.app.job.JobService() {
    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        Thread {
            Updater.backgroundCheck(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: android.app.job.JobParameters) = true
}

/** Result of the install session: shows the system confirmation when needed, reports failures. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "codice $st"
                val why = if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                    "firma diversa: reinstalla Metro una volta via USB" else m
                Toast.makeText(c, "aggiornamento non riuscito: $why", Toast.LENGTH_LONG).show()
            }
        }
    }
}
