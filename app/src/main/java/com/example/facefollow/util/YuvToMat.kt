package com.example.facefollow.util

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

/**
 * 把 CameraX 的 YUV_420_888 ImageProxy 转成 OpenCV BGR Mat。
 * 走 NV21 -> JPEG -> Bitmap -> Mat 的标准路径，兼容各种 pixelStride/rowStride 布局。
 */
object YuvToMat {

    fun imageProxyToMat(image: ImageProxy): Mat {
        val width = image.width
        val height = image.height
        val nv21 = yuv420ToNv21(image)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val bytes = out.toByteArray()

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        bitmap.recycle()
        return mat
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val planes = image.planes

        val y = planes[0]
        val u = planes[1]
        val v = planes[2]

        val yBuf = y.buffer
        val uBuf = u.buffer
        val vBuf = v.buffer

        val nv21 = ByteArray(width * height * 3 / 2)

        // Y 平面
        var offset = 0
        for (row in 0 until height) {
            yBuf.position(row * y.rowStride)
            for (col in 0 until width) {
                nv21[offset++] = yBuf.get()
                if (y.pixelStride > 1) yBuf.get() // 极少数 Y 像素步长为 2 时跳过
            }
        }

        // UV 平面 (NV21: 每对像素先 V 后 U)
        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            var uPos = row * u.rowStride
            var vPos = row * v.rowStride
            for (col in 0 until uvWidth) {
                nv21[offset++] = vBuf.get(vPos)
                nv21[offset++] = uBuf.get(uPos)
                uPos += u.pixelStride
                vPos += v.pixelStride
            }
        }
        return nv21
    }
}
