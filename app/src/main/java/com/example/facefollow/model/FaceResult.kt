package com.example.facefollow.model

/**
 * 人脸跟随链路第三阶段的结果：画面中心归一化偏移 (dx, dy) + confidence + 近似头部姿态。
 *
 * 所有坐标为「传感器正立图像空间」(imageWidth x imageHeight)：
 *  - offsetX / offsetY 已归一化到 [-1, 1]，0 表示人脸处于画面正中。
 *    + offsetX > 0  -> 人脸在画面右侧
 *    + offsetY > 0  -> 人脸在画面下方 (图像坐标系 y 向下)
 *  - confidence 为检测置信度 [0,1]。
 *  - yaw/pitch/roll 为「近似」头部姿态(角度)，由 5 个关键点几何推算，非精确 3D 姿态。
 *  - sizeRatio = 人脸框面积 / 画面面积，可作为远近(景深)的粗略代理。
 *
 * 注意：dx/dy 的符号约定取决于前端是否对预览镜像。本工程在 MainActivity 中对
 * 前置摄像头做了镜像显示，但偏移量仍按原始传感器空间计算，接入云台时再按需取反。
 */
data class FaceResult(
    val present: Boolean,
    val imageWidth: Int,
    val imageHeight: Int,
    // 归一化中心偏移
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val confidence: Float = 0f,
    // 人脸框(图像像素)
    val boxX: Float = 0f,
    val boxY: Float = 0f,
    val boxW: Float = 0f,
    val boxH: Float = 0f,
    // 5 个关键点 [右眼, 左眼, 鼻尖, 左嘴角, 右嘴角] (图像像素)
    val landmarks: List<Pair<Float, Float>> = emptyList(),
    // 近似头部姿态(角度)
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    // 远近代理
    val sizeRatio: Float = 0f,
    /** 该帧在相机管线的采集时间戳（纳秒，来自 ImageProxy.imageInfo.timestamp） */
    val frameTimestampNs: Long = 0,
    /** 本帧与上一分析帧的真实间隔（毫秒）；首帧为 0 */
    val dtMs: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)
