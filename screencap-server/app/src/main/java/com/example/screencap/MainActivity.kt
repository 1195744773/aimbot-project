package com.example.screencap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_POST_NOTIFICATIONS = 1002
        var savedCode = 0
        var savedData: Intent? = null
        var serverStarted = false
    }

    private lateinit var etSize: EditText
    private lateinit var etFps: EditText
    private lateinit var etBitrate: EditText
    private lateinit var etModelPath: EditText
    private lateinit var etConf: EditText
    private lateinit var etIou: EditText
    private lateinit var etLineThick: EditText
    private lateinit var etSkipFrames: EditText
    private lateinit var etTargetPriority: EditText
    private lateinit var etSizeTolerance: EditText
    private lateinit var swBody: Switch
    private lateinit var swHead: Switch
    private lateinit var tvStatus: TextView
    private lateinit var btnGrant: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private val config = ScreenCapConfig()

    private fun setFieldsEnabled(enabled: Boolean) {
        etSize.isEnabled = enabled
        etFps.isEnabled = enabled
        etBitrate.isEnabled = enabled
        etModelPath.isEnabled = enabled
        etConf.isEnabled = enabled
        etIou.isEnabled = enabled
        etLineThick.isEnabled = enabled
        etSkipFrames.isEnabled = enabled
        etTargetPriority.isEnabled = enabled
        etSizeTolerance.isEnabled = enabled
        swBody.isEnabled = enabled
        swHead.isEnabled = enabled
        btnGrant.isEnabled = enabled
        btnStart.isEnabled = enabled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = config.get()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply { text = "ScreenCap Server"; textSize = 24f })

        fun addField(label: String, value: String, inputType: Int): EditText {
            root.addView(TextView(this).apply { text = label; setPadding(0, 24, 0, 0) })
            return EditText(this).apply { setText(value); this.inputType = inputType }.also { root.addView(it) }
        }

        etSize = addField("Crop size (px):", cfg.cropSize.toString(), InputType.TYPE_CLASS_NUMBER)
        etFps = addField("Frame rate (0=unlimited):", cfg.frameRate.toString(), InputType.TYPE_CLASS_NUMBER)
        etBitrate = addField("Bitrate (Mbps):", cfg.bitrate.toString(), InputType.TYPE_CLASS_NUMBER)
        etModelPath = addField("Model path:", cfg.modelPath, InputType.TYPE_CLASS_TEXT)
        etConf = addField("Confidence threshold (0.0-1.0):", cfg.confThreshold.toString(), InputType.TYPE_NUMBER_FLAG_DECIMAL)
        etIou = addField("NMS IoU threshold (0.0-1.0):", cfg.iouThreshold.toString(), InputType.TYPE_NUMBER_FLAG_DECIMAL)
        etLineThick = addField("Line thickness (px):", cfg.lineThickness.toString(), InputType.TYPE_CLASS_NUMBER)
        etSkipFrames = addField("Skip frames (0=disabled):", cfg.skipFrames.toString(), InputType.TYPE_CLASS_NUMBER)
        etTargetPriority = addField("Target priority (e.g. 0,1,2):", cfg.targetPriority.joinToString(","), InputType.TYPE_CLASS_TEXT)
        etSizeTolerance = addField("Size tolerance (0.0-1.0):", cfg.sizeTolerance.toString(), InputType.TYPE_NUMBER_FLAG_DECIMAL)
        swBody = Switch(this).apply { text = "Detect body"; isChecked = cfg.detectBody }.also { root.addView(it) }
        swHead = Switch(this).apply { text = "Detect head"; isChecked = cfg.detectHead }.also { root.addView(it) }

        btnGrant = Button(this).apply {
            text = "1. Grant permission"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
                }
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            }
        }
        root.addView(btnGrant)

        btnStart = Button(this).apply {
            text = "2. Start server"
            setOnClickListener {
                try {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
                        tvStatus.text = "Grant notification permission first, then retry"
                        return@setOnClickListener
                    }
                    if (savedCode != Activity.RESULT_OK) { tvStatus.text = "Grant screen capture permission first!"; return@setOnClickListener }
                    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                        tvStatus.text = "Need All files access, toggle it on"
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        })
                        return@setOnClickListener
                    }
                    val sz = etSize.text.toString().toIntOrNull() ?: 416
                    val fps = etFps.text.toString().toIntOrNull() ?: 30
                    val br = etBitrate.text.toString().toIntOrNull() ?: 5
                    val modelPath = etModelPath.text.toString()
                    val conf = etConf.text.toString().toFloatOrNull() ?: 0.7f
                    val iou = etIou.text.toString().toFloatOrNull() ?: 0.45f
                    val detectBody = swBody.isChecked
                    val detectHead = swHead.isChecked
                    val lineThick = etLineThick.text.toString().toIntOrNull() ?: 2
                    val skipFrames = etSkipFrames.text.toString().toIntOrNull() ?: 1
                    val priorityStr = etTargetPriority.text.toString().trim()
                    val targetPriority = if (priorityStr.isEmpty()) listOf(0, 1, 2) else {
                        priorityStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                    }
                    val sizeTol = etSizeTolerance.text.toString().toFloatOrNull() ?: 0.15f
                    config.update(sz, fps, br, modelPath, conf, iou, detectBody, detectHead, lineThick, skipFrames, targetPriority, sizeTol)

                    CaptureService.pendingCode = savedCode
                    CaptureService.pendingData = savedData
                    startForegroundService(Intent(this@MainActivity, CaptureService::class.java))
                    serverStarted = true
                    tvStatus.text = "Server running (${sz}px, ${fps}fps)"
                    setFieldsEnabled(false)
                } catch (e: Exception) {
                    tvStatus.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Start failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnStart)

        btnStop = Button(this).apply {
            text = "Stop server"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                serverStarted = false
                tvStatus.text = "Server stopped"
                setFieldsEnabled(true)
            }
        }
        root.addView(btnStop)

        tvStatus = TextView(this).apply {
            text = when {
                serverStarted -> "Server running on port 3842"
                savedCode == Activity.RESULT_OK -> "Permission granted"
                else -> "Ready"
            }
            setPadding(0, 32, 0, 0)
        }
        root.addView(tvStatus)

        if (serverStarted) setFieldsEnabled(false)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // refresh UI after returning from lock screen / settings
        if (savedCode != Activity.RESULT_OK) {
            serverStarted = false
            setFieldsEnabled(true)
            tvStatus.text = "Permission revoked — Grant + Start Server again"
        }
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intentData: Intent?) {
        super.onActivityResult(requestCode, resultCode, intentData)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            savedCode = resultCode; savedData = intentData
            tvStatus.text = "Permission granted"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            tvStatus.text = if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                "Notifications permission granted" else "Notifications permission denied"
        }
    }
}
