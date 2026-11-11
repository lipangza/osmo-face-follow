package com.example.facefollow.detector

import android.util.Log
import com.example.facefollow.model.RawDetection
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.objdetect.FaceDetectorYN

/**
 * 基于 OpenCV 官方 YuNet 模型的人脸检测器。
 *
 * 模型 face_detection_yunet_2023mar.onnx 实际有 12 个输出头
 * (cls/obj/bbox/kps × stride 8/16/32)，直接 net.forward() 只会拿到最后一个
 * kps_32（1x100x10），既没有 bbox 也没有 score —— 这是真机上每帧拿不到框的原因。
 * 因此这里改用 OpenCV 自带的 FaceDetectorYN：它在内部完成 prior 解码与 NMS，
 * 每行输出 15 个值：
 *   [x, y, w, h, score, 右眼x,右眼y, 左眼x,左眼y, 鼻尖x,鼻尖y, 左嘴角x,左嘴角y, 右嘴角x,右嘴角y]
 * 坐标位于传入图像的像素空间。
 */
class FaceDetector(
    modelPath: String,
    private val inputSize: Int = 320,
    // 0.6 -> 0.5：侧脸/快速移动时 YuNet 分数会掉到 0.5~0.6 区间，
    // 过高的阈值是「人稍偏出画面就丢」的直接原因之一。
    private val confThreshold: Float = 0.5f
) {
    private val detector: FaceDetectorYN = FaceDetectorYN.create(
        modelPath,
        "",
        Size(inputSize.toDouble(), inputSize.toDouble()),
        confThreshold,
        0.3f,   // NMS 阈值
        500,    // topK
        0,      // backend: DNN_BACKEND_DEFAULT
        0       // target: DNN_TARGET_CPU
    )

    private var logged = false
    private var rowLogged = false
    private var lastDiagMs = 0L

    fun detect(mat: Mat): RawDetection? {
        if (mat.empty() || mat.channels() != 3) return null
        // 用当前帧的真实尺寸作为输入尺寸：内部等比缩放，框坐标直接落在本帧像素空间。
        detector.setInputSize(Size(mat.cols().toDouble(), mat.rows().toDouble()))

        val faces = Mat()
        return try {
            detector.detect(mat, faces)
            val n = faces.rows()
            if (n <= 0) return null
            if (!logged) {
                logged = true
                Log.i(TAG, "FaceDetectorYN detected=$n, faces shape=${faces.rows()}x${faces.cols()}")
            }

            var best: RawDetection? = null
            var bestScore = -1f
            var maxScore = -1f
            for (i in 0 until n) {
                // Mat.get(row, col) 返回该像素的各通道值，不是整行；faces 是单通道 N×15，
                // 必须逐列取值拼出该行。
                val v = DoubleArray(15) { j -> faces.get(i, j)?.get(0) ?: 0.0 }
                if (!rowLogged) {
                    rowLogged = true
                    Log.i(TAG, "row0 = " + v.joinToString(",") { "%.3f".format(it) })
                }
                // 实测 FaceDetectorYN 的行布局是 [x, y, w, h, 5 个关键点(10 值), score]，
                // score 在最后一列而不是第 5 列。
                val score = v[14].toFloat()
                if (score > maxScore) maxScore = score
                if (score < confThreshold || score <= bestScore) continue
                bestScore = score

                val x = v[0].toFloat()
                val y = v[1].toFloat()
                val w = v[2].toFloat()
                val h = v[3].toFloat()
                val landmarks = listOf(
                    v[4].toFloat() to v[5].toFloat(),    // 右眼
                    v[6].toFloat() to v[7].toFloat(),    // 左眼
                    v[8].toFloat() to v[9].toFloat(),    // 鼻尖
                    v[10].toFloat() to v[11].toFloat(),  // 左嘴角
                    v[12].toFloat() to v[13].toFloat()   // 右嘴角
                )
                best = RawDetection(x, y, w, h, score, landmarks)
            }
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastDiagMs >= 1000L) {
                lastDiagMs = nowMs
                // 跟丢归因：n>0 但 best==null 说明「检测到了但被阈值挡掉」，
                // n==0 才是真正的画面内无脸/漏检。
                val cause = when {
                    n == 0 -> "无候选(检测本身漏检)"
                    best == null -> "有${n}个候选但全部低于阈值"
                    else -> "命中"
                }
                Log.i(TAG, "diag n=$n maxScore=$maxScore thr=$confThreshold input=${mat.cols()}x${mat.rows()} -> $cause")
            }
            best
        } catch (e: Exception) {
            Log.e(TAG, "detect failed", e)
            null
        } finally {
            faces.release()
        }
    }

    fun release() {
        // FaceDetectorYN 的 native 资源随 GC 回收，这里保持空实现以兼容调用方。
    }

    companion object {
        private const val TAG = "FaceDetector"
    }
}
