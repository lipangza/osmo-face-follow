package com.example.facefollow.eyelock

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.hypot

/**
 * 眼部状态检测器：MediaPipe Face Landmarker（Face Mesh 468 点 + blendshape 分类头）。
 *
 * 为什么不用现有 YuNet：YuNet 只给 5 个关键点（双眼中心/鼻尖/两嘴角），
 * 没有上下眼睑轮廓，几何上算不出 EAR；而闭眼时“眼中心点”几乎不变，
 * 用 5 点做任何距离比都会得到常量，无法判睁闭。
 *
 * 模型与许可证：
 *  - 模型文件：assets/face_landmarker.task（3.58 MB，float16 版本 1）
 *    来源：https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
 *    许可证：Apache License 2.0（MediaPipe / Google）
 *  - 运行时：com.google.mediapipe:tasks-vision:0.10.20（Apache-2.0）
 *
 * 该任务包内置「人脸检测 + 468 点网格 + 52 个 blendshape」，其中
 * eyeBlinkLeft / eyeBlinkRight 就是训练好的睁闭眼分类分数，比纯几何 EAR 更稳。
 * 这里两者都用：任一判据成立即认为该眼闭合，并在日志里同时输出，便于真机调阈值。
 */
class EyeStateDetector(
    private val context: Context,
    modelAsset: String = DEFAULT_MODEL_ASSET,
    private val earClosedThreshold: Float = 0.18f,
    private val blinkClosedThreshold: Float = 0.60f
) {
    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelAsset)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .build()
    )

    private var loggedOnce = false

    /**
     * 同步检测一帧。必须在后台线程调用（IMAGE 模式是阻塞的）。
     * @param bitmap 正立（已按 rotationDegrees 旋转）的人脸图，建议 640x480
     */
    fun detect(bitmap: Bitmap): EyeState {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: FaceLandmarkerResult = try {
            landmarker.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "FaceLandmarker.detect failed: ${e.message}", e)
            return EyeState(facePresent = false)
        }

        val landmarks: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>> =
            result.faceLandmarks()
        if (landmarks.isEmpty() || landmarks[0].size < 400) {
            return EyeState(facePresent = false)
        }
        val lm = landmarks[0]
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val earRight = ear(lm, w, h, RIGHT_EYE_H, RIGHT_EYE_V1, RIGHT_EYE_V2)
        val earLeft = ear(lm, w, h, LEFT_EYE_H, LEFT_EYE_V1, LEFT_EYE_V2)

        var blinkLeft = -1f
        var blinkRight = -1f
        result.faceBlendshapes().orElse(null)?.firstOrNull()?.forEach { cat ->
            when (cat.categoryName()) {
                "eyeBlinkLeft" -> blinkLeft = cat.score()
                "eyeBlinkRight" -> blinkRight = cat.score()
            }
        }

        // 脸部特征宽度：外眼角 33 <-> 263 的像素距离 / 图像宽度
        val eyeSpanX = (lm[LEFT_EYE_H[0]].x() - lm[RIGHT_EYE_H[0]].x()) * w
        val eyeSpanY = (lm[LEFT_EYE_H[0]].y() - lm[RIGHT_EYE_H[0]].y()) * h
        val faceWidthRatio = hypot(eyeSpanX, eyeSpanY) / w

        val rightClosed = (blinkRight >= blinkClosedThreshold) || (earRight in 0.01f..earClosedThreshold)
        val leftClosed = (blinkLeft >= blinkClosedThreshold) || (earLeft in 0.01f..earClosedThreshold)

        if (!loggedOnce) {
            loggedOnce = true
            Log.i(TAG, "FaceLandmarker OK: landmarks=${lm.size} " +
                "blendshapes=${result.faceBlendshapes().orElse(null)?.firstOrNull()?.size ?: 0} " +
                "earR=%.3f earL=%.3f".format(earRight, earLeft))
        }

        return EyeState(
            facePresent = true,
            earLeft = earLeft,
            earRight = earRight,
            blinkLeft = blinkLeft,
            blinkRight = blinkRight,
            leftClosed = leftClosed,
            rightClosed = rightClosed,
            faceWidthRatio = faceWidthRatio
        )
    }

    /** 6 点 EAR；坐标先换算成像素，避免宽高比把纵向距离压扁。 */
    private fun ear(
        lm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        w: Float,
        h: Float,
        horizontal: IntArray,
        v1: IntArray,
        v2: IntArray
    ): Float {
        fun px(i: Int) = floatArrayOf(lm[i].x() * w, lm[i].y() * h)
        fun dist(a: FloatArray, b: FloatArray) = hypot((a[0] - b[0]).toDouble(), (a[1] - b[1]).toDouble())

        val hd = dist(px(horizontal[0]), px(horizontal[1]))
        if (hd <= 0.0001) return 0f
        val d1 = dist(px(v1[0]), px(v1[1]))
        val d2 = dist(px(v2[0]), px(v2[1]))
        return ((d1 + d2) / (2.0 * hd)).toFloat()
    }

    fun release() {
        try {
            landmarker.close()
        } catch (e: Exception) {
            Log.w(TAG, "landmarker.close: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "EyeStateDetector"
        const val DEFAULT_MODEL_ASSET = "face_landmarker.task"

        // MediaPipe Face Mesh 标准眼周索引
        private val RIGHT_EYE_H = intArrayOf(33, 133)
        private val RIGHT_EYE_V1 = intArrayOf(159, 145)
        private val RIGHT_EYE_V2 = intArrayOf(158, 153)
        private val LEFT_EYE_H = intArrayOf(362, 263)
        private val LEFT_EYE_V1 = intArrayOf(386, 374)
        private val LEFT_EYE_V2 = intArrayOf(385, 380)
    }
}
