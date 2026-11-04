package com.mc.metro

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import java.io.File
import java.io.FileOutputStream

/**
 * Cover art of what music/video apps are playing, read from their media sessions (needs the
 * notification-access grant). The last cover per app is kept on disk so the tile survives restarts.
 */
object Media {
    class Info(val art: Bitmap, val title: String?, val artist: String?)

    val now = HashMap<String, Info>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val watched = HashMap<String, MediaController.Callback>()
    private var msm: MediaSessionManager? = null
    private var dir: File? = null

    fun addListener(f: () -> Unit) { listeners += f }
    fun removeListener(f: () -> Unit) { listeners -= f }
    private fun changed() = listeners.toList().forEach { it() }

    fun init(c: Context) {
        val app = c.applicationContext
        if (dir == null) {
            dir = File(app.filesDir, "covers").apply { mkdirs() }
            dir?.listFiles()?.forEach { f ->
                val pkg = f.nameWithoutExtension
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let { now[pkg] = Info(it, null, null) }
            }
        }
        if (msm != null) return
        val m = app.getSystemService(MediaSessionManager::class.java) ?: return
        val cn = ComponentName(app, NotifService::class.java)
        try {
            m.addOnActiveSessionsChangedListener({ list -> watch(list) }, cn, Live.main)
            watch(m.getActiveSessions(cn))
            msm = m
        } catch (e: SecurityException) {
            // Notification access not granted yet; retried when the listener connects.
        }
    }

    private fun watch(list: List<MediaController>?) {
        list ?: return
        for (mc in list) {
            val pkg = mc.packageName
            if (watched.containsKey(pkg)) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(md: MediaMetadata?) = take(pkg, md)
                override fun onSessionDestroyed() { watched.remove(pkg) }
            }
            mc.registerCallback(cb, Live.main)
            watched[pkg] = cb
            take(pkg, mc.metadata)
        }
    }

    private fun take(pkg: String, md: MediaMetadata?) {
        md ?: return
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return
        val small = if (art.width > 512) Bitmap.createScaledBitmap(art, 512, 512 * art.height / art.width, true) else art
        now[pkg] = Info(
            small,
            md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM),
        )
        val d = dir
        if (d != null) Thread {
            try { FileOutputStream(File(d, "$pkg.jpg")).use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (e: Exception) { }
        }.start()
        changed()
    }
}
