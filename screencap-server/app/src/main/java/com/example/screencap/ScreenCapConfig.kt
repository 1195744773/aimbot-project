package com.example.screencap

import android.util.Log
import org.json.JSONObject
import java.io.File

class ScreenCapConfig {
    data class Config(
        val cropSize: Int = 416,
        val frameRate: Int = 30,
        val bitrate: Int = 5,
        val modelPath: String = "",
        val confThreshold: Float = 0.7f,
        val iouThreshold: Float = 0.45f,
        val detectBody: Boolean = true,
        val detectHead: Boolean = true,
        val lineThickness: Int = 2
    )

    private var config = Config()
    private val configDir = File("/storage/emulated/0/ScreenCapServer")
    private val configFile = File(configDir, "config.json")

    init {
        loadConfig()
    }

    fun get(): Config = config

    fun update(cropSize: Int, frameRate: Int, bitrate: Int, modelPath: String,
               confThreshold: Float, iouThreshold: Float, detectBody: Boolean, detectHead: Boolean, lineThickness: Int = 2) {
        config = Config(cropSize, frameRate, bitrate, modelPath, confThreshold, iouThreshold, detectBody, detectHead, lineThickness)
        val json = JSONObject().apply {
            put("crop_size", cropSize)
            put("frame_rate", frameRate)
            put("bitrate", bitrate)
            put("model_path", modelPath)
            put("conf_threshold", confThreshold)
            put("iou_threshold", iouThreshold)
            put("detect_body", detectBody)
            put("detect_head", detectHead)
            put("line_thickness", lineThickness)
        }.toString(2)
        try {
            configDir.mkdirs()
            configFile.writeText(json)
            Log.d(TAG, "Config saved: $json")
        } catch (e: Exception) {
            Log.w(TAG, "Direct write failed: ${e.message}")
            tryFallback(json)
        }
    }

    private fun tryFallback(json: String) {
        val compact = JSONObject(json).toString()
        try {
            val cmd = "mkdir -p '${configDir.absolutePath}' && echo '$compact' > '${configFile.absolutePath}'"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            proc.waitFor()
            if (proc.exitValue() == 0) {
                Log.d(TAG, "Config saved via su")
            } else {
                Log.e(TAG, "su failed: ${proc.errorStream.bufferedReader().readText()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "su exec failed: ${e.message}")
        }
    }

    private fun loadConfig() {
        try {
            if (!configFile.exists()) return
            val json = JSONObject(configFile.readText())
            config = Config(
                cropSize = json.optInt("crop_size", 416),
                frameRate = json.optInt("frame_rate", 30),
                bitrate = json.optInt("bitrate", 5).let { if (it > 1000000) it / 1000000 else it },
                modelPath = json.optString("model_path", ""),
                confThreshold = json.optDouble("conf_threshold", 0.7).toFloat(),
                iouThreshold = json.optDouble("iou_threshold", 0.45).toFloat(),
                detectBody = json.optBoolean("detect_body", true),
                detectHead = json.optBoolean("detect_head", true),
                lineThickness = json.optInt("line_thickness", 2)
            )
            Log.d(TAG, "Config loaded: $config")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ScreenCapConfig"
    }
}
