package com.wangyao.digitalwatermark.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 水印生成器（第 8 章 8.1 节：水印的生成 G）。
 *
 * 生成 64×64 的二值水印图像（1 bit/像素，共 4096 bit）：
 * 以文字图案作为有意义水印（按内容分类），可配合密钥做置乱增强安全性。
 * 水印系统三模块：生成（G）→ 嵌入（E）→ 提取/检测（D）。
 */
object WatermarkGenerator {

    /** 水印尺寸：64×64 = 4096 bit。 */
    const val SIZE = 64
    const val BIT_COUNT = SIZE * SIZE

    /** 默认水印文字（两行排版，各占一半高度）。 */
    const val DEFAULT_TEXT = "数字水印"

    /**
     * 生成二值水印位阵（行优先）：文字图案阈值化为 0/1。
     * bit = 1 表示水印前景（黑字），bit = 0 表示背景。
     *
     * @param text 水印文字（默认「数字水印」）：前一半渲染在第一行、
     *             后一半在第二行。旧实现声明了该参数却写死「数字/水印」，
     *             调用方传什么都没用；现在默认值「数字水印」按半切分
     *             仍然是「数字」「水印」两行，与旧渲染逐像素相同。
     */
    fun generateBits(text: String = DEFAULT_TEXT): BooleanArray {
        val src = text.ifEmpty { DEFAULT_TEXT }
        val half = (src.length + 1) / 2
        val line1 = src.substring(0, half)
        val line2 = src.substring(half)

        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            // 两行排版：每行各占一半高度
            textSize = SIZE * 0.34f
        }
        val centerX = SIZE / 2f
        canvas.drawText(line1, centerX, SIZE * 0.38f, paint)
        canvas.drawText(line2, centerX, SIZE * 0.80f, paint)

        val bits = BooleanArray(BIT_COUNT)
        val px = IntArray(SIZE)
        for (y in 0 until SIZE) {
            bmp.getPixels(px, 0, SIZE, 0, y, SIZE, 1)
            for (x in 0 until SIZE) {
                // 黑字（亮度低）→ 1，背景 → 0
                bits[y * SIZE + x] = (px[x] and 0xFF) < 128
            }
        }
        bmp.recycle()
        return bits
    }

    /** 将水印位阵放大渲染为可显示的灰度 Bitmap（用于 UI 预览）。 */
    fun bitsToBitmap(bits: BooleanArray, scale: Int = 4): Bitmap {
        val size = SIZE * scale
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = if (bits[(y / scale) * SIZE + (x / scale)]) 0 else 255
                px[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }
}
