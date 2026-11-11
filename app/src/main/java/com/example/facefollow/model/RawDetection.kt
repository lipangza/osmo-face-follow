package com.example.facefollow.model

/**
 * 检测器输出的原始检测结果（图像像素坐标，已还原到正立图像空间）。
 * 关键点顺序固定为 [右眼, 左眼, 鼻尖, 左嘴角, 右嘴角]。
 */
data class RawDetection(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val score: Float,
    val landmarks: List<Pair<Float, Float>>
)
