package com.wangyao.digitalwatermark.core

/**
 * LSB 空间域水印（第 8 章 8.2 节：最低有效位法）。
 *
 * 原理（式 8-6）：像素 8 位灰度 g = Σ bi·2^i，最低位平面（LSB）能量极小，
 * 修改后视觉不可察觉。嵌入 = 用水印比特替换载体像素 LSB；提取 = 直接读取 LSB，
 * 可实现盲检测（式 8-4：Ŵ = D(IW, K)）。
 *
 * 鲁棒性改进（8.5 节对策 a/b）：把 4096 bit 水印在整帧亮度平面上周期平铺
 * （1080p 每比特重复约 505 次），提取时先做帧内逐比特多数投票（空间冗余），
 * 多帧提取时再跨帧累加投票（帧间冗余）。
 *
 * 本类直接操作视频帧的 Y（亮度）平面：ByteArray 行优先、无行距（stride=width）。
 */
object LsbWatermark {

    /**
     * LSB 嵌入（式 8-1：IW = E(I, W, K)，此处 K 为平铺周期 4096）。
     * 将水印比特平铺到亮度平面每个像素的最低有效位。
     */
    fun embed(luma: ByteArray, width: Int, height: Int, bits: BooleanArray) {
        val n = bits.size
        for (i in luma.indices) {
            val b = if (bits[i % n]) 1 else 0
            luma[i] = ((luma[i].toInt() and 0xFE) or b).toByte()
        }
    }

    /**
     * LSB 帧内提取 + 多数投票（盲检测）：对每个水印比特位置，收集全帧
     * 所有对应像素的 LSB 做多数判决，返回 ±1 投票数组（正 = 倾向 1）。
     * 多帧场景下由调用方逐帧累加后取符号。
     */
    fun extractVotes(luma: ByteArray, width: Int, height: Int): IntArray {
        val n = WatermarkGenerator.BIT_COUNT
        val ones = IntArray(n)
        val total = luma.size / n   // 每比特在帧内重复次数
        for (i in luma.indices) {
            if (luma[i].toInt() and 0x01 == 1) ones[i % n]++
        }
        val votes = IntArray(n)
        for (j in 0 until n) votes[j] = if (ones[j] * 2 > total) 1 else -1
        return votes
    }
}
