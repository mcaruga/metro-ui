package com.mc.metro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlin.math.max

/** Start background image, seen through the tiles (Windows Phone 8.1 style). */
object Wall {
    var bitmap: Bitmap? = null
        private set
    private var loadedKey: String? = null

    fun load(c: Context, w: Int, h: Int, done: () -> Unit) {
        val u = Prefs.bgUri
        if (u == null || w <= 0 || h <= 0) {
            val had = bitmap != null
            bitmap = null
            loadedKey = null
            if (had) done()
            return
        }
        val key = "$u|$w|$h"
        if (key == loadedKey && bitmap != null) return
        loadedKey = key
        val app = c.applicationContext
        Thread {
            val b = try { decode(app, Uri.parse(u), w, h) } catch (e: Exception) { null }
            Live.main.post {
                if (loadedKey == key) {
                    bitmap = b
                    done()
                }
            }
        }.start()
    }

    private fun decode(c: Context, uri: Uri, w: Int, h: Int): Bitmap {
        val src = ImageDecoder.createSource(c.contentResolver, uri)
        val raw = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val s = max(1, minOf(info.size.width / w, info.size.height / h))
            dec.setTargetSampleSize(s)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // Centre-crop to exactly w × h.
        val scale = max(w.toFloat() / raw.width, h.toFloat() / raw.height)
        val dw = raw.width * scale
        val dh = raw.height * scale
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(raw, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        if (raw !== out) raw.recycle()
        return out
    }
}
