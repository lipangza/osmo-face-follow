package com.example.facefollow.detector

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 准备 YuNet ONNX 模型文件：
 *   1) 若已下载则直接返回；
 *   2) 否则尝试从 assets/ 拷贝(把手动下载的模型放进 app/src/main/assets/ 即可)；
 *   3) 否则尝试首次启动联网下载(需 INTERNET 权限)。
 * 三者都不行时返回 null，由调用方提示用户。
 */
object ModelPrep {
    const val MODEL_NAME = "face_detection_yunet_2023mar.onnx"
    private const val MODEL_URL = "https://hf-mirror.com/opencv/opencv_zoo/resolve/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
    private val MODEL_URL_FALLBACKS = listOf(
        "https://raw.githubusercontent.com/opencv/opencv_zoo/master/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
    )
    private const val MIN_SIZE = 100_000L
    private const val TAG = "ModelPrep"

    fun ensureModel(context: Context, onStatus: (String) -> Unit): String? {
        val outFile = File(context.filesDir, MODEL_NAME)
        if (outFile.exists() && outFile.length() > MIN_SIZE) {
            return outFile.absolutePath
        }
        onStatus("正在准备人脸检测模型…")

        // 2) assets
        try {
            context.assets.open(MODEL_NAME).use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            }
            if (outFile.exists() && outFile.length() > MIN_SIZE) {
                onStatus("已从 assets 载入模型")
                return outFile.absolutePath
            }
        } catch (e: Exception) {
            Log.i(TAG, "no bundled asset model: ${e.message}")
        }

        // 3) 联网下载
        try {
            onStatus("正在下载模型(首次约 0.3MB)…")
            val urls = listOf(MODEL_URL) + MODEL_URL_FALLBACKS
            var ok = false
            for (u in urls) {
                try {
                    download(u, outFile)
                    if (outFile.exists() && outFile.length() > MIN_SIZE) { ok = true; break }
                } catch (e: Exception) {
                    Log.w(TAG, "download from $u failed: ${e.message}")
                }
            }
            if (ok) {
                onStatus("模型下载完成")
                return outFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
        }

        onStatus("模型缺失：请按 README 手动放入 assets 或检查网络")
        return null
    }

    private fun download(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.inputStream.use { inp ->
            FileOutputStream(out).use { fos -> inp.copyTo(fos) }
        }
        conn.disconnect()
    }
}
