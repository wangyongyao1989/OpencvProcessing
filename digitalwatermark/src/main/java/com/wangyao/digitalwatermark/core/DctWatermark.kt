package com.wangyao.digitalwatermark.core

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * DCT 变换域水印（第 8 章 8.3 节）。
 *
 * 原理（结合第 5 章式 5-17/5-18 的 8×8 DCT，与 JPEG 压缩同域）：
 * 1. 亮度平面划分为 8×8 子块，做二维 DCT；
 * 2. 水印比特经密钥 PN 序列扩频（每 bit 8 个 chip）调制后，
 *    加性嵌入到子块的中频系数（Zig-Zag 序号 9~16）：
 *    人眼对低频噪声敏感（不可见性差）、高频易被量化/滤波去除（鲁棒性差），
 *    折中嵌入中频（教材图 8-5）；
 * 3. 嵌入公式：c'j = cj + k·pnj·m，m = ±1 为水印比特双极性表示；
 * 4. 盲提取（式 8-4）：对中频系数与 PN 序列做相关检测，
 *    corr = Σ cj'·pnj > 0 判为 1，否则为 0（式 8-5 相关性检验思想）。
 *
 * 全帧选取 4096 个均匀分布的子块承载 64×64 水印（帧内冗余），
 * 多帧提取时再逐比特投票（帧间冗余）。
 */
object DctWatermark {

    /** 嵌入强度 k：越大越鲁棒、但不可见性下降（鲁棒性与不可感知性折中）。 */
    const val STRENGTH = 30.0

    /** 每 bit 的扩频 chip 数（中频系数个数）。 */
    private const val CHIPS = 8

    /** PN 序列密钥（安全性：未经授权无法提取）。 */
    const val DEFAULT_KEY = 0x5A5A2026L

    /** 标准 JPEG 亮度量化表（第 5 章表 5-4，Zig-Zag 顺序）。 */
    val LUMA_QUANT_TABLE = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    )

    // ---- 8×8 DCT 正交基（Orthonormal DCT-II basis）----
    // B[u][x] = c(u)·cos((2x+1)·u·π/16)，c(0)=√(1/8)，c(u>0)=1/2
    private val B = Array(8) { u ->
        val c = if (u == 0) sqrt(1.0 / 8.0) else 0.5
        DoubleArray(8) { x -> c * cos((2 * x + 1) * u * Math.PI / 16.0) }
    }

    /** 中频系数位置（Zig-Zag 序号 9~16 → (u,v) 坐标）。 */
    private val MID_FREQ = arrayOf(
        intArrayOf(4, 0), intArrayOf(3, 1), intArrayOf(2, 2), intArrayOf(1, 3),
        intArrayOf(0, 4), intArrayOf(0, 5), intArrayOf(1, 4), intArrayOf(2, 3)
    )

    /** 由密钥生成的 PN 序列（±1 双极性，扩频水印）。 */
    private fun pnSequence(key: Long): DoubleArray {
        val rnd = java.util.Random(key)
        return DoubleArray(CHIPS) { if (rnd.nextBoolean()) 1.0 else -1.0 }
    }

    /** 8×8 二维 DCT（可分离实现）。 */
    private fun forwardDct(f: Array<DoubleArray>): Array<DoubleArray> {
        val tmp = Array(8) { DoubleArray(8) }
        val out = Array(8) { DoubleArray(8) }
        // 行方向：tmp[x][v] = Σy f[x][y]·B[v][y]
        for (x in 0 until 8) for (v in 0 until 8) {
            var s = 0.0
            for (y in 0 until 8) s += f[x][y] * B[v][y]
            tmp[x][v] = s
        }
        // 列方向：F[u][v] = Σx tmp[x][v]·B[u][x]
        for (u in 0 until 8) for (v in 0 until 8) {
            var s = 0.0
            for (x in 0 until 8) s += tmp[x][v] * B[u][x]
            out[u][v] = s
        }
        return out
    }

    /** 8×8 二维 IDCT（正交基对称性：f = Bᵀ·F·B）。 */
    private fun inverseDct(F: Array<DoubleArray>): Array<DoubleArray> {
        val tmp = Array(8) { DoubleArray(8) }
        val out = Array(8) { DoubleArray(8) }
        // tmp[u][y] = Σv F[u][v]·B[v][y]
        for (u in 0 until 8) for (y in 0 until 8) {
            var s = 0.0
            for (v in 0 until 8) s += F[u][v] * B[v][y]
            tmp[u][y] = s
        }
        // f[x][y] = Σu tmp[u][y]·B[u][x]
        for (x in 0 until 8) for (y in 0 until 8) {
            var s = 0.0
            for (u in 0 until 8) s += tmp[u][y] * B[u][x]
            out[x][y] = s
        }
        return out
    }

    /**
     * DCT 域嵌入（式 8-1：IW = E(I, W, K)）：
     * 均匀抽取 4096 个 8×8 子块，每块承载 1 个水印比特。
     */
    fun embed(luma: ByteArray, width: Int, height: Int, bits: BooleanArray,
              key: Long = DEFAULT_KEY) {
        val pn = pnSequence(key)
        val blocksX = width / 8
        val blocksY = height / 8
        val totalBlocks = blocksX * blocksY
        val step = maxOf(1, totalBlocks / bits.size)

        val f = Array(8) { DoubleArray(8) }
        for (i in bits.indices) {
            val bi = i * step
            if (bi >= totalBlocks) break
            val bx = bi % blocksX
            val by = bi / blocksX
            val base = by * 8 * width + bx * 8

            // 取块 → DCT
            for (y in 0 until 8) for (x in 0 until 8)
                f[y][x] = (luma[base + y * width + x].toInt() and 0xFF).toDouble()
            val F = forwardDct(f)

            // 中频系数加性嵌入：cj' = cj + k·pnj·m
            val m = if (bits[i]) 1.0 else -1.0
            for (j in 0 until CHIPS) {
                val (u, v) = MID_FREQ[j]
                F[u][v] += STRENGTH * pn[j] * m
            }

            // IDCT → 写回
            val g = inverseDct(F)
            for (y in 0 until 8) for (x in 0 until 8) {
                val v = g[y][x].toInt().coerceIn(0, 255)
                luma[base + y * width + x] = v.toByte()
            }
        }
    }

    /**
     * DCT 域盲提取（式 8-4：Ŵ = D(IW, K)）：
     * 相关检测 corr = Σ cj'·pnj，corr > 0 → bit 1（式 8-5）。
     * 返回投票计数数组（正 = 倾向 1，负 = 倾向 0），供多帧累加投票。
     */
    fun extractVotes(luma: ByteArray, width: Int, height: Int,
                     key: Long = DEFAULT_KEY): IntArray {
        val pn = pnSequence(key)
        val n = WatermarkGenerator.BIT_COUNT
        val votes = IntArray(n)
        val blocksX = width / 8
        val blocksY = height / 8
        val totalBlocks = blocksX * blocksY
        val step = maxOf(1, totalBlocks / n)

        val f = Array(8) { DoubleArray(8) }
        for (i in 0 until n) {
            val bi = i * step
            if (bi >= totalBlocks) break
            val bx = bi % blocksX
            val by = bi / blocksX
            val base = by * 8 * width + bx * 8

            for (y in 0 until 8) for (x in 0 until 8)
                f[y][x] = (luma[base + y * width + x].toInt() and 0xFF).toDouble()
            val F = forwardDct(f)

            var corr = 0.0
            for (j in 0 until CHIPS) {
                val (u, v) = MID_FREQ[j]
                corr += F[u][v] * pn[j]
            }
            votes[i] = if (corr > 0) 1 else -1
        }
        return votes
    }
}
