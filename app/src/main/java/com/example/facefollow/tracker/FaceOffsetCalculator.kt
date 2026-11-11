package com.example.facefollow.tracker

import com.example.facefollow.model.FaceResult
import com.example.facefollow.model.RawDetection
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI

/**
 * 链路第三阶段：把检测结果换算成「画面中心归一化偏移 (dx, dy) + confidence + 近似头部姿态」。
 *
 * 等价于原 DJI Mimo 中「跟踪引擎给出画面中心归一化偏移 + confidence」这一步。
 * 偏移量定义：以画面中心为原点，向右/向下为正，范围 [-1, 1]。
 *
 * 头部姿态为「近似」值：由 5 个关键点几何关系推算，仅用于演示，不是精确 3D 头部姿态解算。
 */
class FaceOffsetCalculator {

    fun compute(raw: RawDetection, imgW: Int, imgH: Int): FaceResult {
        val cx = raw.x + raw.w / 2f
        val cy = raw.y + raw.h / 2f

        val offsetX = ((cx - imgW / 2f) / (imgW / 2f)).coerceIn(-1f, 1f)
        val offsetY = ((cy - imgH / 2f) / (imgH / 2f)).coerceIn(-1f, 1f)
        val sizeRatio = (raw.w * raw.h) / (imgW.toFloat() * imgH.toFloat())

        val (yaw, pitch, roll) = estimatePose(raw.landmarks)

        return FaceResult(
            present = true,
            imageWidth = imgW,
            imageHeight = imgH,
            offsetX = offsetX,
            offsetY = offsetY,
            confidence = raw.score,
            boxX = raw.x,
            boxY = raw.y,
            boxW = raw.w,
            boxH = raw.h,
            landmarks = raw.landmarks,
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            sizeRatio = sizeRatio
        )
    }

    /**
     * 由 5 关键点推算近似头部姿态(角度)：
     *  - roll  : 双眼连线的倾斜角
     *  - yaw   : 鼻尖相对双眼中点的水平偏移 / 眼间距 (启发式缩放)
     *  - pitch : 鼻尖相对双眼中点的垂直偏移 / 眼间距 (启发式缩放)
     */
    private fun estimatePose(lm: List<Pair<Float, Float>>): Triple<Float, Float, Float> {
        if (lm.size < 3) return Triple(0f, 0f, 0f)
        val re = lm[0]
        val le = lm[1]
        val nose = lm[2]

        val dxEye = (le.first - re.first).toDouble()
        val dyEye = (le.second - re.second).toDouble()
        val roll = Math.toDegrees(atan2(dyEye, dxEye)).toFloat()

        val eyeMidX = (re.first + le.first) / 2f
        val eyeMidY = (re.second + le.second) / 2f
        val interEye = hypot(dxEye, dyEye).toFloat()
        if (interEye <= 0f) return Triple(0f, 0f, roll)

        val yaw = ((nose.first - eyeMidX) / interEye) * 40f
        val pitch = ((nose.second - eyeMidY) / interEye) * 40f
        return Triple(yaw, pitch, roll)
    }
}
