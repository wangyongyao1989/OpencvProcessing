package com.wangyao.imagerecognition.core

/**
 * 归一化互相关（NCC）模板匹配（《数字图像与视频处理》第 11 章
 * 11.2 节：图像识别的基本方法之一——模板匹配）。
 *
 * NCC（式 11-1）：
 *   NCC(x,y) = Σ[(f(x+i,y+j)-μf)·(t(i,j)-μt)]
 *              / sqrt(Σ(f-μf)² · Σ(t-μt)²)
 * ∈[-1,1]，1 表示完全匹配。相比 SSD/SAD，NCC 对线性光照变化
 * （增益/偏置）不变，是光照鲁棒的经典相似性度量。
 *
 * 为控制计算量，[searchFull] 采用「粗到精」金字塔策略：
 * 先在 1/4 分辨率全图搜索候选，再回全分辨率局部精化。
 */
object NccMatcher {

    /** 单点 NCC 值：在 (x,y) 处对齐模板计算。 */
    fun nccAt(
        gray: ByteArray, width: Int,
        tpl: ByteArray, tw: Int, th: Int,
        x: Int, y: Int
    ): Double {
        // 模板均值与方差
        var tSum = 0.0
        for (i in tpl.indices) tSum += tpl[i].toInt() and 0xFF
        val tMean = tSum / tpl.size
        var tVar = 0.0
        for (i in tpl.indices) {
            val d = (tpl[i].toInt() and 0xFF) - tMean
            tVar += d * d
        }
        if (tVar <= 0.0) return 0.0

        // 窗口均值与互相关
        var fSum = 0.0
        for (j in 0 until th) {
            val row = (y + j) * width + x
            for (i in 0 until tw) fSum += gray[row + i].toInt() and 0xFF
        }
        val fMean = fSum / tpl.size
        var cross = 0.0
        var fVar = 0.0
        for (j in 0 until th) {
            val row = (y + j) * width + x
            for (i in 0 until tw) {
                val fd = (gray[row + i].toInt() and 0xFF) - fMean
                val td = (tpl[j * tw + i].toInt() and 0xFF) - tMean
                cross += fd * td
                fVar += fd * fd
            }
        }
        if (fVar <= 0.0) return 0.0
        return cross / kotlin.math.sqrt(fVar * tVar)
    }

    /**
     * 粗到精全图搜索：返回 [bestX, bestY, bestScore]（左上角坐标）。
     * 先 1/4 分辨率全搜，再全分辨率 ±[refine] 精化。
     */
    fun searchFull(
        gray: ByteArray, width: Int, height: Int,
        tpl: ByteArray, tw: Int, th: Int,
        refine: Int = 8
    ): DoubleArray {
        // ---- 粗搜（1/4 分辨率，面积 2x2 均值）----
        val sw = width / 4
        val sh = height / 4
        val stw = tw / 4
        val sth = th / 4
        if (sw <= stw || sh <= sth) {
            // 图太小无需金字塔，直接全搜
            return searchWindow(gray, width, height, tpl, tw, th, 0, 0, width - tw, height - th)
        }
        val small = downsample(gray, width, height, 4)
        val smallTpl = downsample(tpl, tw, th, 4)
        val coarse = searchWindow(
            small, sw, sh, smallTpl, stw, sth, 0, 0, sw - stw, sh - sth
        )

        // ---- 精化（全分辨率，粗结果 ×4 附近 ±refine）----
        val cx = (coarse[0] * 4).toInt().coerceIn(0, width - tw)
        val cy = (coarse[1] * 4).toInt().coerceIn(0, height - th)
        val fine = searchWindow(
            gray, width, height, tpl, tw, th,
            (cx - refine).coerceAtLeast(0), (cy - refine).coerceAtLeast(0),
            (cx + refine).coerceAtMost(width - tw), (cy + refine).coerceAtMost(height - th)
        )
        return fine
    }

    /**
     * 窗口内搜索：在 [x0,y0]-[x1,y1]（含）范围内找 NCC 最大位置。
     * 返回 [bestX, bestY, bestScore]。
     */
    fun searchWindow(
        gray: ByteArray, width: Int, height: Int,
        tpl: ByteArray, tw: Int, th: Int,
        x0: Int, y0: Int, x1: Int, y1: Int
    ): DoubleArray {
        // 模板统计量（只算一次）
        var tSum = 0.0
        for (i in tpl.indices) tSum += tpl[i].toInt() and 0xFF
        val tMean = tSum / tpl.size
        var tVar = 0.0
        for (i in tpl.indices) {
            val d = (tpl[i].toInt() and 0xFF) - tMean
            tVar += d * d
        }
        if (tVar <= 0.0) return doubleArrayOf(x0.toDouble(), y0.toDouble(), 0.0)

        var bestX = x0
        var bestY = y0
        var bestScore = -2.0
        val xs = x0.coerceAtLeast(0)..x1.coerceAtMost(width - tw)
        val ys = y0.coerceAtLeast(0)..y1.coerceAtMost(height - th)
        for (y in ys) {
            for (x in xs) {
                // 积分图像思路：窗口和用行增量维护太复杂，直接算
                var fSum = 0.0
                for (j in 0 until th) {
                    val row = (y + j) * width + x
                    for (i in 0 until tw) fSum += gray[row + i].toInt() and 0xFF
                }
                val fMean = fSum / tpl.size
                var cross = 0.0
                var fVar = 0.0
                for (j in 0 until th) {
                    val row = (y + j) * width + x
                    for (i in 0 until tw) {
                        val fd = (gray[row + i].toInt() and 0xFF) - fMean
                        val td = (tpl[j * tw + i].toInt() and 0xFF) - tMean
                        cross += fd * td
                        fVar += fd * fd
                    }
                }
                val score = if (fVar <= 0.0) 0.0
                else cross / kotlin.math.sqrt(fVar * tVar)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }
        return doubleArrayOf(bestX.toDouble(), bestY.toDouble(), bestScore)
    }

    /**
     * 计算粗网格 NCC 热力图（可视化用，步长 [step] 采样）。
     * 返回 (⌈(w-tw+1)/step⌉)×(⌈(h-th+1)/step⌉) 行优先的得分数组。
     */
    fun similarityMap(
        gray: ByteArray, width: Int, height: Int,
        tpl: ByteArray, tw: Int, th: Int,
        step: Int
    ): FloatArray {
        val gw = (width - tw) / step + 1
        val gh = (height - th) / step + 1
        val out = FloatArray(gw * gh)
        var k = 0
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                out[k++] = nccAt(gray, width, tpl, tw, th, x * step, y * step).toFloat()
            }
        }
        return out
    }

    /** 2x2…nxn 均值下采样（[factor] 倍缩小）。 */
    fun downsample(src: ByteArray, w: Int, h: Int, factor: Int): ByteArray {
        val ow = w / factor
        val oh = h / factor
        val out = ByteArray(ow * oh)
        val area = factor * factor
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                var s = 0
                for (j in 0 until factor) {
                    val row = (y * factor + j) * w + x * factor
                    for (i in 0 until factor) s += src[row + i].toInt() and 0xFF
                }
                out[y * ow + x] = (s / area).toByte()
            }
        }
        return out
    }
}
