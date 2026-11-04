package com.mc.metro

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Screen and audio recording started from the action center. */
object Recorder {
    const val SCREEN = "screen"
    const val AUDIO = "audio"
    const val EXTRA_KIND = "kind"

    /** What is being recorded now, or null. */
    @Volatile var running: String? = null

    fun intent(c: Context, kind: String) = Intent(c, RecordActivity::class.java).putExtra(EXTRA_KIND, kind)

    fun stop(c: Context) {
        RecorderService.instance?.finish() ?: run { running = null }
    }
}

/** Invisible step that asks for the microphone and, for the screen, the system capture consent. */
class RecordActivity : Activity() {
    private var kind = Recorder.AUDIO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra(Recorder.EXTRA_KIND) ?: Recorder.AUDIO
        if (savedInstanceState != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            next()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        val ok = res.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (kind == Recorder.AUDIO && !ok) {
            Toast.makeText(this, "serve il microfono per registrare l'audio", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        next()
    }

    private fun next() {
        if (kind == Recorder.SCREEN) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 2)
        } else {
            startForegroundService(Intent(this, RecorderService::class.java).putExtra(Recorder.EXTRA_KIND, kind))
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, RecorderService::class.java)
                    .putExtra(Recorder.EXTRA_KIND, Recorder.SCREEN)
                    .putExtra(RecorderService.EXTRA_CODE, resultCode)
                    .putExtra(RecorderService.EXTRA_DATA, data)
            )
        }
        finish()
    }
}

/** Foreground service that records to Movies/Metro or Recordings/Metro. */
class RecorderService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "com.mc.metro.STOP_RECORDING"
        private const val CHANNEL = "recording"
        private const val ID = 4301
        private const val DONE_ID = 4302
        @Volatile var instance: RecorderService? = null
    }

    private var rec: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var out: Uri? = null
    private var fd: ParcelFileDescriptor? = null
    private var kind = Recorder.AUDIO
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (rec != null) return START_NOT_STICKY
        instance = this
        kind = intent?.getStringExtra(Recorder.EXTRA_KIND) ?: Recorder.AUDIO
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = (if (kind == Recorder.SCREEN) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0) or
            (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try {
            startForeground(ID, ongoing(), type)
            if (kind == Recorder.SCREEN) startScreen(intent!!, mic) else startAudio()
            Recorder.running = kind
        } catch (e: Exception) {
            Toast.makeText(this, "registrazione non riuscita", Toast.LENGTH_LONG).show()
            cleanup(false)
        }
        return START_NOT_STICKY
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Registrazioni", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun ongoing(): Notification {
        channel()
        val stop = PendingIntent.getService(this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle(if (kind == Recorder.SCREEN) "Registrazione dello schermo" else "Registrazione audio")
            .setContentText("tocca per fermare e salvare")
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "ferma", stop).build())
            .build()
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun open(video: Boolean): ParcelFileDescriptor {
        val v = ContentValues().apply {
            if (video) {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Metro_${stamp()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Metro")
            } else {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Metro_${stamp()}.m4a")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                val dir = if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Metro")
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(coll, v) ?: throw IllegalStateException("no media uri")
        out = uri
        return contentResolver.openFileDescriptor(uri, "w")!!.also { fd = it }
    }

    private fun startAudio() {
        val f = open(false)
        rec = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(f.fileDescriptor)
            prepare()
            start()
        }
    }

    private fun startScreen(i: Intent, mic: Boolean) {
        val code = i.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent = i.getParcelableExtra(EXTRA_DATA) ?: throw IllegalStateException("no consent")
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            ?: throw IllegalStateException("no projection")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { finish() } }
        }, main)
        val dm = resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        if (w > 1080) { h = h * 1080 / w; w = 1080 }
        w -= w % 2
        h -= h % 2
        val f = open(true)
        val r = MediaRecorder(this).apply {
            if (mic) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (mic) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
            }
            setVideoSize(w, h)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(6_000_000)
            setOutputFile(f.fileDescriptor)
            prepare()
        }
        rec = r
        display = p.createVirtualDisplay("metro-rec", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        r.start()
    }

    /** Stops, saves and tells the user where the file is. */
    fun finish() {
        if (rec == null && projection == null) { cleanup(false); return }
        val ok = runCatching { rec?.stop() }.isSuccess
        cleanup(ok)
    }

    private fun cleanup(saved: Boolean) {
        runCatching { rec?.release() }
        rec = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { fd?.close() }
        fd = null
        val uri = out
        out = null
        if (uri != null) {
            if (saved) {
                runCatching { contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
                notifySaved(uri)
            } else {
                runCatching { contentResolver.delete(uri, null, null) }
            }
        }
        Recorder.running = null
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifySaved(uri: Uri) {
        channel()
        val video = kind == Recorder.SCREEN
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (video) "video/mp4" else "audio/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 1, view, PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle(if (video) "Registrazione dello schermo salvata" else "Registrazione audio salvata")
            .setContentText(if (video) "in Video › Movies/Metro · tocca per guardarla" else "in Registrazioni/Metro · tocca per ascoltarla")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(DONE_ID, n) }
    }

    override fun onDestroy() {
        if (rec != null) finish()
        super.onDestroy()
    }
}
