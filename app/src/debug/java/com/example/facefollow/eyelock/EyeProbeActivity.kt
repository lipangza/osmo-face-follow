package com.example.facefollow.eyelock

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.facefollow.util.YuvToMat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors

/**
 * 真机自测页（仅 debug 构建）：前置相机 -> MediaPipe EyeStateDetector -> 屏幕显示 EAR/blink。
 * 不接主界面、不改 MainActivity，只用于验证「检测层」在真机上确实能判睁闭。
 *
 * adb 启动：
 *   adb shell am start -n com.example.facefollow/com.example.facefollow.eyelock.EyeProbeActivity
 */
class EyeProbeActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var readout: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var detector: EyeStateDetector? = null
    private var lastMs = 0L

    private val gate = EyeClosureGate()
    private var closedMsShown = 0L
    private var triggerCount = 0
    private var lastClosedState = false
    private var lastLogMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initDebug()

        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        readout = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(24, 24, 24, 24)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "初始化…"
        }
        root.addView(readout, FrameLayout.LayoutParams(-1, -2).apply { gravity = android.view.Gravity.BOTTOM })
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
            return
        }
        startPipeline()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else {
            readout.text = "无相机权限，无法验证检测层"
        }
    }

    private fun startPipeline() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            detector = EyeStateDetector(this@EyeProbeActivity)
            Log.i(TAG, "EyeStateDetector ready")

            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            // 5fps 足够判定，避免 MediaPipe 把 CPU 拉满
            if (now - lastMs < 200) return
            lastMs = now

            var mat = YuvToMat.imageProxyToMat(image)
            val rot = when (image.imageInfo.rotationDegrees) {
                90 -> Core.ROTATE_90_CLOCKWISE
                180 -> Core.ROTATE_180
                270 -> Core.ROTATE_90_COUNTERCLOCKWISE
                else -> null
            }
            if (rot != null) {
                val r = Mat()
                Core.rotate(mat, r, rot)
                mat.release()
                mat = r
            }
            val rgba = Mat()
            Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            mat.release()
            rgba.release()

            val state = detector?.detect(bmp)
            bmp.recycle()
            if (state == null) return

            val triggered = gate.onSample(state.bothClosed && state.faceWidthRatio >= 0.08f, now)
            if (triggered) triggerCount++
            closedMsShown = gate.closedDurationMs(now)

            val line = buildString {
                append(state.summary()).append("\n")
                append("连续闭眼=%.1fs  模拟触发次数=%d\n".format(closedMsShown / 1000f, triggerCount))
                append("判定阈值: EAR<0.18 或 blink>0.60；保持>=1.35s 才触发")
            }
            // 每次睁闭状态翻转 + 每秒各打一条，供真机取证
            if (state.bothClosed != lastClosedState || now - lastLogMs >= 1000L) {
                lastClosedState = state.bothClosed
                lastLogMs = now
                Log.i(TAG, "eye: ${state.summary()} closedMs=$closedMsShown triggered=$triggered")
            }
            runOnUiThread { readout.text = line }
        } catch (e: Exception) {
            Log.e(TAG, "analyze error", e)
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.release()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "EyeProbeActivity"
    }
}
