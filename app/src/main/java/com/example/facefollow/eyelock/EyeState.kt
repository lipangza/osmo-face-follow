package com.example.facefollow.eyelock

/**
 * 单帧眼部状态。坐标与 EAR 都在「正立图像空间」内计算，与检测器输入位图一致。
 *
 * EAR(eye aspect ratio) 取自 MediaPipe Face Mesh 的 6 个眼部轮廓点：
 *   EAR = (|p2-p6| + |p3-p5|) / (2 * |p1-p4|)
 * 睁眼约 0.25~0.35，闭眼约 0.05~0.15。
 *
 * blinkLeft/blinkRight 来自 MediaPipe 的 blendshape 分类头（训练好的睁闭眼分类器），
 * 当模型未输出 blendshape 时为 -1，此时只用 EAR 判定。
 */
data class EyeState(
    val facePresent: Boolean,
    val earLeft: Float = 0f,
    val earRight: Float = 0f,
    val blinkLeft: Float = -1f,
    val blinkRight: Float = -1f,
    val leftClosed: Boolean = false,
    val rightClosed: Boolean = false,
    /** 人脸框宽度 / 图像宽度，用于过滤远处背景里的“小人脸” */
    val faceWidthRatio: Float = 0f
) {
    val bothClosed: Boolean get() = facePresent && leftClosed && rightClosed

    fun summary(): String = if (!facePresent) {
        "无人脸"
    } else {
        "EAR L=%.3f R=%.3f  blink L=%.2f R=%.2f  closed=%s/%s".format(
            earLeft, earRight, blinkLeft, blinkRight,
            if (leftClosed) "1" else "0", if (rightClosed) "1" else "0"
        )
    }
}
