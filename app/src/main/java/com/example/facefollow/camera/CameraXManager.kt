package com.example.facefollow.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.facefollow.detector.FaceDetector
import com.example.facefollow.model.FaceResult
import com.example.facefollow.model.OffsetListener
import com.example.facefollow.tracker.FaceOffsetCalculator
import com.example.facefollow.ui.OverlayView
import com.example.facefollow.util.YuvToMat
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors

/**
 * 链路第一阶段「画面采集」：用 CameraX 同时驱动预览与逐帧分析。
 * 每帧：YUV -> Mat -> 旋转正立 -> OpenCV 人脸检测 -> 偏移量计算 -> 主线程回调。
 *
 * 坐标空间说明：
 * - 检测图像空间：ImageAnalysis 目标分辨率(640x480)，已按传感器旋转正立(0°)，左上角原点。
 * - PreviewView 显示空间：PreviewView 内部 TextureView/SurfaceView 的显示坐标系，
 *   PreviewView 会自动处理旋转、FIT_CENTER 缩放、前置镜像。
 * - OverlayView 绘制空间：与 PreviewView 同尺寸的 View 坐标系。
 * 本类负责计算「检测图像空间 -> OverlayView 显示空间」的变换矩阵并传给 OverlayView。
 */
class CameraXManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val overlayView: OverlayView,
    private val listener: OffsetListener,
    private val detector: FaceDetector,
    private val calculator: FaceOffsetCalculator,
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    private var provider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var previewSizeReported = false
    private var lastDiagMs = 0L

    // 检测图像空间目标尺寸（4:3，与 PreviewView 的预览流保持一致）
    private val detectionImageWidth = 640
    private val detectionImageHeight = 480

    /**
     * PreviewView 的 scaleType 只能在主线程读取，分析线程直接调用会抛
     * IllegalStateException("Not in application's main thread")，
     * 该异常会打断当帧分析（每秒固定丢一帧）。这里在主线程缓存一份快照。
     */
    @Volatile
    private var previewScaleTypeName: String = "?"

    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

    /** 检出率统计窗口 */
    private var rateFrames = 0
    private var rateDetected = 0
    private var rateWindowStartMs = 0L

    /**
     * 闭眼检测接线点：每帧把「已正立」的位图交给上层（闭眼模块）。
     * 为 null 时不产生任何额外开销；由 MainActivity 在功能开启时设置。
     * 回调在分析线程执行，位图用完即回收，接收方不要持有引用。
     */
    var onEyeFrame: ((android.graphics.Bitmap, Long) -> Unit)? = null
    private var lastEyeFrameMs = 0L

    // 分析节流：目标帧率 + 上一帧时间戳（纳秒）
    private val targetFps = 30
    private val targetIntervalNs = 1_000_000_000L / targetFps
    private var lastAcceptedNs = 0L

    fun start() {
        cameraProviderFuture.addListener({
            provider = cameraProviderFuture.get()
            bind()
            registerDisplayListener()
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 屏幕旋转的兜底通道：即使 Activity 没有收到 onConfigurationChanged，
     * 也保证 ImageAnalysis/Preview 的 targetRotation 跟随显示方向，
     * 否则 ImageProxy.rotationDegrees 会停在绑定时的旧值。
     */
    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                    mainHandler.post { updateTargetRotation() }
                }
            }
        }
        dm.registerDisplayListener(l, mainHandler)
        displayListener = l
    }

    private fun bind() {
        val p = provider ?: return
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        // PreviewView 自己处理镜像与旋转；叠加层只记录是否需要镜像，每帧按图像实际尺寸现算变换。
        overlayView.setMirror(mirror)
        // PreviewView 只支持 FIT_CENTER / FILL_CENTER 两种，叠加层必须用同一套缩放公式。
        overlayView.setFillMode(previewView.scaleType == androidx.camera.view.PreviewView.ScaleType.FILL_CENTER)

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        previewUseCase = preview
        previewSizeReported = false
        previewScaleTypeName = previewView.scaleType.name

        // 关键：这里不能用已废弃的 setTargetResolution(640,480)。
        // 该 API 的分辨率选择随 targetRotation 变化：横屏拿到 640x480(4:3)，
        // 竖屏却会落到 1080x1080(1:1) —— 检测图变成 4:3 画面中心的方形裁切，
        // 与预览的取景范围不一致，画面左右两侧的人脸直接落在检测图之外，
        // 表现为「横屏正常、竖屏抓不到脸」；同时叠加层的比例也会算错。
        // 改为按宽高比锁定 4:3，使横竖屏拿到同一套取景范围。
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    )
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            Size(detectionImageWidth, detectionImageHeight),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, analyzer)
        analysisUseCase = analysis
        // 本 Activity 自己处理 configChanges，旋转时不会重建相机；必须手动把 targetRotation
        // 跟上当前显示方向，否则 ImageProxy.rotationDegrees 会一直停在绑定时的旧值（竖屏检出失败）。
        updateTargetRotation()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (e: Exception) {
            Log.e(TAG, "camera bind failed", e)
        }
    }

    /** 屏幕方向变化时调用：把 Preview/Analysis 的 targetRotation 对齐当前显示方向。 */
    fun updateTargetRotation() {
        // 读显示方向必须在主线程（Activity 的 onConfigurationChanged 里已保证），
        // DisplayManager 回调也走 mainHandler。
        val displayRotation = previewView.display?.rotation ?: return
        previewUseCase?.targetRotation = displayRotation
        analysisUseCase?.targetRotation = displayRotation
        previewScaleTypeName = previewView.scaleType.name
        // 旋转后预览分辨率可能变化，重新上报一次
        previewSizeReported = false
        Log.i(TAG, "updateTargetRotation displayRotation=$displayRotation (${displayRotationName(displayRotation)})")
    }

    private fun displayRotationName(r: Int): String = when (r) {
        android.view.Surface.ROTATION_0 -> "0"
        android.view.Surface.ROTATION_90 -> "90"
        android.view.Surface.ROTATION_180 -> "180"
        android.view.Surface.ROTATION_270 -> "270"
        else -> "?"
    }

    private val analyzer = ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
        // 帧时间戳来自相机管线，用于给上层提供真实 dt；同时按目标帧率节流，避免空跑。
        if (!previewSizeReported) {
            previewSizeReported = true
            previewUseCase?.resolutionInfo?.resolution?.let { res ->
                mainHandler.post { overlayView.setPreviewSize(res.width, res.height) }
            }
        }

        val frameNs = imageProxy.imageInfo.timestamp
        if (lastAcceptedNs != 0L && frameNs - lastAcceptedNs < targetIntervalNs) {
            imageProxy.close()
            return@Analyzer
        }
        val dtMs = if (lastAcceptedNs == 0L) 0f else (frameNs - lastAcceptedNs) / 1_000_000f
        lastAcceptedNs = frameNs

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            var mat = YuvToMat.imageProxyToMat(imageProxy)

            // 旋转到正立（传感器 -> 正立图像空间）
            val rot = when (rotationDegrees) {
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

            // 横/竖屏对照诊断：rotationDegrees / 输入图宽高 / View 宽高 / scaleType
            val nowDiag = android.os.SystemClock.elapsedRealtime()
            if (nowDiag - lastDiagMs >= 1000L) {
                lastDiagMs = nowDiag
                // 注意：previewView.scaleType 只能在主线程读，这里用主线程缓存的快照。
                Log.i(TAG, "diag rot=$rotationDegrees raw=${imageProxy.width}x${imageProxy.height} " +
                    "upright=${mat.width()}x${mat.height()} view=${overlayView.width}x${overlayView.height} " +
                    "scaleType=$previewScaleTypeName")
            }

            val raw = detector.detect(mat)
            val imgW = mat.width()
            val imgH = mat.height()

            // 闭眼检测：把同一帧正立位图交给上层（节流到 ~10fps，避免 MediaPipe 拖垮分析线程）
            onEyeFrame?.let { cb ->
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nowMs - lastEyeFrameMs >= EYE_FRAME_INTERVAL_MS) {
                    lastEyeFrameMs = nowMs
                    try {
                        val rgba = Mat()
                        Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
                        val bmp = android.graphics.Bitmap.createBitmap(
                            rgba.cols(), rgba.rows(), android.graphics.Bitmap.Config.ARGB_8888
                        )
                        Utils.matToBitmap(rgba, bmp)
                        rgba.release()
                        cb(bmp, nowMs)
                        bmp.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "eye frame convert failed: ${e.message}")
                    }
                }
            }

            mat.release()

            // 检出率统计：按 1 秒窗口输出「分析帧率 / 检出数」，用于判断跟丢是
            // 检测帧率不够（快速移动）还是检测本身漏检（FaceDetector 的 diag 会有归因）。
            rateFrames++
            if (raw != null) rateDetected++
            val nowStat = android.os.SystemClock.elapsedRealtime()
            if (rateWindowStartMs == 0L) rateWindowStartMs = nowStat
            val statDt = nowStat - rateWindowStartMs
            if (statDt >= 1000L) {
                Log.i(TAG, "detectRate ${rateDetected}/${rateFrames} in ${statDt}ms " +
                    "(${"%.1f".format(rateFrames * 1000f / statDt)}fps) upright=${imgW}x${imgH} " +
                    "rot=$rotationDegrees scaleType=$previewScaleTypeName")
                rateFrames = 0
                rateDetected = 0
                rateWindowStartMs = nowStat
            }

            val result: FaceResult = if (raw != null) {
                calculator.compute(raw, imgW, imgH)
            } else {
                FaceResult(present = false, imageWidth = imgW, imageHeight = imgH)
            }

            mainHandler.post { listener.onFaceOffset(result.copy(frameTimestampNs = frameNs, dtMs = dtMs)) }
        } catch (e: Exception) {
            Log.e(TAG, "analyze error", e)
        } finally {
            imageProxy.close()
        }
    }

    fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        bind()
    }

    fun release() {
        displayListener?.let {
            context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.unregisterDisplayListener(it)
        }
        displayListener = null
        provider?.unbindAll()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXManager"

        /** 闭眼检测节流：~10fps 足够判定，且不拖慢 30fps 的主检测 */
        private const val EYE_FRAME_INTERVAL_MS = 100L
    }
}
