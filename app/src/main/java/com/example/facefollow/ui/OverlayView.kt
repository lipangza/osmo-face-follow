package com.example.facefollow.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.facefollow.model.FaceResult

/**
 * 在预览之上叠加绘制：中心十字准星、人脸框、关键点、以及「中心 -> 人脸中心」的偏移矢量。
 *
 * 每帧用 FaceResult 自带的正立图像尺寸(imageWidth x imageHeight)现算
 * 「图像空间 -> View 空间」变换，等价于 PreviewView 的 FIT_CENTER + 前置水平镜像。
 * 不再使用固定的 640x480：前置摄像头旋转 90/270 时正立图是 480x640，
 * 用固定尺寸会让框整体缩一半、偏一半。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var result: FaceResult? = null
    private var mirror = true

    /** PreviewView 的 scaleType：true=FILL_CENTER(裁切)，false=FIT_CENTER(留黑边)。 */
    private var fillMode = true
    private var lastLogMs = 0L
    private var previewW = 0
    private var previewH = 0

    private val centerPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val boxPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val lmPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 5f
    }
    private val vecPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
    }

    private val tmpRectF = RectF()
    private val tmpPts = FloatArray(2)
    private val matrix = Matrix()

    fun setResult(r: FaceResult?) {
        result = r
        invalidate()
    }

    /** 前置摄像头预览是水平镜像的，叠加层要跟着镜像，否则框会左右反。 */
    fun setMirror(m: Boolean) {
        mirror = m
        invalidate()
    }

    /** 与 PreviewView.getScaleType() 保持一致：FILL_CENTER 要按「放大并裁切」算，不能用 FIT 的公式。 */
    fun setFillMode(fill: Boolean) {
        fillMode = fill
        invalidate()
    }

    fun setPreviewSize(w: Int, h: Int) {
        previewW = w
        previewH = h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 中心十字准星（View 坐标系）
        canvas.drawLine(w / 2 - 24, h / 2, w / 2 + 24, h / 2, centerPaint)
        canvas.drawLine(w / 2, h / 2 - 24, w / 2, h / 2 + 24, centerPaint)

        val r = result ?: return
        if (!r.present || r.imageWidth == 0 || r.imageHeight == 0 || w <= 0f || h <= 0f) return

        val scale = updateTransform(r.imageWidth.toFloat(), r.imageHeight.toFloat(), w, h)

        // 人脸框：图像空间 RectF -> View 空间
        tmpRectF.set(r.boxX, r.boxY, r.boxX + r.boxW, r.boxY + r.boxH)
        matrix.mapRect(tmpRectF)

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogMs >= 1000L) {
            lastLogMs = now
            Log.i(TAG, "img=${r.imageWidth}x${r.imageHeight} preview=${previewW}x${previewH} " +
                "box=(${r.boxX},${r.boxY},${r.boxW},${r.boxH}) view=${w.toInt()}x${h.toInt()} " +
                "scaleType=${if (fillMode) "FILL_CENTER" else "FIT_CENTER"} scale=$scale " +
                "mapped=(${tmpRectF.left},${tmpRectF.top},${tmpRectF.right},${tmpRectF.bottom}) mirror=$mirror")
        }
        canvas.drawRect(tmpRectF, boxPaint)

        // 关键点：图像空间点 -> View 空间
        for (lm in r.landmarks) {
            tmpPts[0] = lm.first
            tmpPts[1] = lm.second
            matrix.mapPoints(tmpPts)
            canvas.drawCircle(tmpPts[0], tmpPts[1], 4f, lmPaint)
        }

        // 偏移矢量：View 中心 -> 人脸框中心（View 空间）
        tmpPts[0] = r.boxX + r.boxW / 2f
        tmpPts[1] = r.boxY + r.boxH / 2f
        matrix.mapPoints(tmpPts)
        canvas.drawLine(w / 2, h / 2, tmpPts[0], tmpPts[1], vecPaint)
    }

    /**
     * 等比缩放 + 居中，前置水平镜像：
     *  - FILL_CENTER：scale = max(viewW/imgW, viewH/imgH)，图放大到铺满 View，超出部分裁掉；
     *  - FIT_CENTER ：scale = min(viewW/imgW, viewH/imgH)，图完整显示，四周留黑边。
     * 缩放为负时 offset 也为负，等价于居中裁切。
     */
    private fun updateTransform(imgW: Float, imgH: Float, viewW: Float, viewH: Float): Float {
        val scale = if (fillMode) maxOf(viewW / imgW, viewH / imgH) else minOf(viewW / imgW, viewH / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val offsetX = (viewW - drawW) / 2f
        val offsetY = (viewH - drawH) / 2f
        matrix.reset()
        matrix.postScale(scale, scale)
        if (mirror) matrix.postScale(-1f, 1f, drawW / 2f, drawH / 2f)
        matrix.postTranslate(offsetX, offsetY)
        return scale
    }

    companion object {
        private const val TAG = "OverlayView"
    }
}
