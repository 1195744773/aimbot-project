package com.example.screencap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    private var running = false

    private val config = ScreenCapConfig()
    private var cropSize = 416
    private var displayW = 0
    private var displayH = 0
    private var cropX = 0
    private var cropY = 0

    private var touchHelperPath = ""
    private var displayDensity = 0
    private var frameHandler: HandlerThread? = null
    private var lastFrameMs = 0L
    private var frameIntervalMs = 0
    private var lastCallbackNs = 0L
    private val latestFrame = AtomicReference<ByteBuffer>(null)
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "CaptureService"
        var pendingCode = 0
        var pendingData: Intent? = null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            val ch = NotificationChannel("cap", "ScreenCap", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
            val n = Notification.Builder(this, "cap").setContentTitle("Screen Capture").setSmallIcon(android.R.drawable.ic_menu_camera).build()
            startForeground(1, n)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e::class.simpleName}: ${e.message}")
        }

        // extract touchhelper
        touchHelperPath = File(filesDir, "touchhelper").absolutePath
        val f = File(touchHelperPath)
        if (!f.exists()) {
            try {
                assets.open("touchhelper").use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
                f.setExecutable(true)
                Log.d(TAG, "touchhelper extracted")
            } catch (e: Exception) {
                Log.e(TAG, "touchhelper extract failed: ${e.message}")
            }
        } else {
            Log.d(TAG, "touchhelper already exists")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val code = pendingCode.also { pendingCode = 0 }
            val data = pendingData.also { pendingData = null }
            val cfg = config.get()
            cropSize = cfg.cropSize
            frameIntervalMs = if (cfg.frameRate > 0) 1000 / cfg.frameRate else 0

            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            displayW = metrics.widthPixels
            displayH = metrics.heightPixels
            displayDensity = metrics.densityDpi

            if (cropSize > minOf(displayW, displayH)) cropSize = minOf(displayW, displayH)
            cropX = (displayW - cropSize) / 2
            cropY = (displayH - cropSize) / 2

            if (code != 0 && data != null) {
                Log.d(TAG, "Creating MediaProjection code=$code")
                projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager)
                    .getMediaProjection(code, data)
                if (Build.VERSION.SDK_INT >= 29) {
                    projection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection stopped — cleaning up capture, service stays alive")
                            MainActivity.savedCode = 0
                            MainActivity.savedData = null
                            MainActivity.serverStarted = false
                            stopCapture()
                        }
                    }, Handler(Looper.getMainLooper()))
                }
            } else {
                Log.w(TAG, "No permission data: code=$code data=${data != null}")
            }

            if (projection != null) {
                Log.d(TAG, "Starting capture displayW=$displayW displayH=$displayH cropSize=$cropSize")
                startCapture()
                // PARTIAL_WAKE_LOCK keeps CPU alive so the TCP server can accept
                // reconnections even with screen off. (MediaProjection will still
                // be revoked by HyperOS, but the service stays alive.)
                try {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CaptureService:capture")
                    wl.acquire()
                    wakeLock = wl
                } catch (e: Exception) {
                    Log.e(TAG, "wakeLock acquire: ${e.message}")
                }
                // recreate virtual display when screen turns back on
                try {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            try {
                                when (intent.action) {
                                    Intent.ACTION_SCREEN_ON -> {
                                        Log.d(TAG, "Screen ON — recreating virtual display")
                                        try {
                                            virtualDisplay?.release()
                                        } catch (_: Exception) {}
                                        virtualDisplay = null
                                        val surf = imageReader?.surface
                                        if (surf != null && projection != null) {
                                            virtualDisplay = projection?.createVirtualDisplay(
                                                "ScreenCapture", displayW, displayH, displayDensity,
                                                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                                surf, null, null
                                            )
                                            Log.d(TAG, "VirtualDisplay recreated: ${virtualDisplay != null}")
                                        } else {
                                            Log.w(TAG, "Cannot recreate VD — surf=${surf != null} proj=${projection != null}")
                                        }
                                    }
                                    Intent.ACTION_SCREEN_OFF -> {
                                        Log.d(TAG, "Screen OFF")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "screenReceiver onReceive: ${e::class.simpleName}: ${e.message}")
                            }
                        }
                    }
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    }
                    registerReceiver(receiver, filter)
                    screenReceiver = receiver
                } catch (e: Exception) {
                    Log.e(TAG, "screenReceiver: ${e.message}")
                }
                startServer()
            } else {
                Log.e(TAG, "projection is null, cannot capture")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand crashed: ${e::class.simpleName}: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    private fun stopCapture() {
        Log.d(TAG, "stopCapture")
        try { frameHandler?.quitSafely(); frameHandler = null } catch (_: Exception) {}
        try { virtualDisplay?.release(); virtualDisplay = null } catch (_: Exception) {}
        try { imageReader?.close(); imageReader = null } catch (_: Exception) {}
        latestFrame.set(null)
    }

    private fun startCapture() {
        stopCapture()  // clean up any previous capture
        running = true
        Log.d(TAG, "Creating ImageReader ${displayW}x${displayH} crop=${cropSize}")
        imageReader = ImageReader.newInstance(displayW, displayH, PixelFormat.RGBA_8888, 3)
        frameHandler = HandlerThread("capture-frames").apply { start() }
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.nanoTime()
            if (lastCallbackNs != 0L) {
                val gap = (now - lastCallbackNs) / 1000000
                if (gap > 50) Log.w(TAG, "IMAGE_READER_GAP: ${gap}ms since last callback")
            }
            lastCallbackNs = now
            if (!running) return@setOnImageAvailableListener
            try {
                val img = reader.acquireLatestImage() ?: run { Log.d(TAG, "acquireLatestImage null"); return@setOnImageAvailableListener }
                sendFrame(img)
                img.close()
            } catch (e: Exception) {
                Log.e(TAG, "onImageAvailable: ${e::class.simpleName}: ${e.message}")
            }
        }, Handler(frameHandler!!.looper))

        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenCapture",
            displayW, displayH, displayDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        if (virtualDisplay == null) {
            Log.e(TAG, "createVirtualDisplay returned null!")
        } else {
            Log.d(TAG, "createVirtualDisplay success")
        }
    }

    private var frameCount = 0L
    private var frameSkipCount = 0L

    private fun sendFrame(image: Image) {
        val t0 = System.nanoTime()
        if (frameIntervalMs > 0) {
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < frameIntervalMs) {
                frameSkipCount++
                return
            }
            lastFrameMs = now
        }
        try {
            val buffer = image.planes[0].buffer
            val rowStride = image.planes[0].rowStride

            val frameSize = cropSize * cropSize * 4  // RGBA
            val buf = ByteBuffer.allocate(4 + 8 + frameSize)
            buf.putInt(frameSize)
            buf.putLong(System.nanoTime())
            val rowBytes = cropSize * 4
            val srcOffset = cropY * rowStride + cropX * 4
            val rowBuf = ByteArray(rowBytes)
            for (row in 0 until cropSize) {
                buffer.position(srcOffset + row * rowStride)
                buffer.get(rowBuf)
                buf.put(rowBuf)
            }

            buf.flip()
            latestFrame.set(buf)
            frameCount++
            if (frameCount % 30 == 0L) {
                val t1 = System.nanoTime()
                Log.d(TAG, String.format("sendFrame: rgba crop=%d total=%dus", cropSize, (t1 - t0) / 1000))
            }
            if (frameCount % 300 == 0L) Log.d(TAG, "Sent $frameCount frames")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "buffer inaccessible (shutting down?): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "sendFrame error: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun startServer() {
        Thread {
            try {
                serverSocket = ServerSocket(3842)
                Log.d(TAG, "ServerSocket listening on 3842")
                while (running) {
                    val sock = serverSocket?.accept() ?: continue
                    clientSocket = sock
                    Log.d(TAG, "Client connected")
                    lastFrameMs = 0L  // ensure first frame is sent immediately

                    // Touch command reader thread
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val parts = line!!.trim().split(" ")
                                if (parts.size >= 2) {
                                    val x = parts[0].toIntOrNull() ?: continue
                                    val y = parts[1].toIntOrNull() ?: continue
                                    val sx = cropX + x
                                    val sy = cropY + y
                                    execTouch(sx, sy)
                                }
                            }
                        } catch (_: Exception) { }
                        Log.d(TAG, "Touch reader exited")
                    }.apply { name = "touch-reader" }.start()

                    drain@ while (running) {
                        val buf = latestFrame.getAndSet(null)
                        if (buf != null) {
                            try {
                                val os = sock.getOutputStream()
                                os.write(buf.array(), 0, buf.limit())
                                os.flush()
                            } catch (e: Exception) {
                                Log.e(TAG, "write error: ${e::class.simpleName}: ${e.message}")
                                break@drain
                            }
                        } else {
                            try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "tcp-server error: ${e::class.simpleName} msg=${e.message}")
            }
        }.apply { name = "tcp-server" }.start()
    }

    private fun execTouch(x: Int, y: Int) {
        try {
            Log.d(TAG, "execTouch $x $y")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "$touchHelperPath $x $y"))
            val ret = proc.waitFor()
            if (ret != 0) Log.e(TAG, "touchhelper exit code $ret")
        } catch (e: Exception) {
            Log.e(TAG, "execTouch error: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        running = false
        // Invalidate saved permission data so next start re-prompts the user
        MainActivity.savedCode = 0
        MainActivity.savedData = null
        stopCapture()
        try { serverSocket?.close() } catch (e: Exception) { Log.e(TAG, "serverSocket: ${e.message}") }
        try { clientSocket?.close() } catch (e: Exception) { Log.e(TAG, "clientSocket: ${e.message}") }
        try { projection?.stop() } catch (e: Exception) { Log.e(TAG, "projection: ${e.message}") }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { Log.e(TAG, "stopForeground: ${e.message}") }
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { Log.e(TAG, "wakeLock: ${e.message}") }
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { Log.e(TAG, "screenReceiver: ${e.message}") }
        super.onDestroy()
    }

}
