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

    /**
     * 模板统计量（与搜索位置无关）：均值、逐像素偏差表、偏差平方和。
     *
     * 优化点：原实现在每个匹配位置/每个网格点都重算一遍模板均值与方差
     * （式 11-1 的分母项只与模板有关）。 similarityMap 会对数百个网格点
     * 反复调用，重算模板统计量是纯浪费。这里只算一次并缓存偏差表，
     * 逐像素的 `tpl[i]-tMean` 变成数组读取，浮点运算次序不变，结果逐位相同。
     */
    private class TemplateStats(
        val mean: Double,
        val dev: DoubleArray,
        val varSum: Double
    )

    private fun templateStats(tpl: ByteArray): TemplateStats {
        var tSum = 0.0
        for (i in tpl.indices) tSum += tpl[i].toInt() and 0xFF
        val tMean = tSum / tpl.size
        val dev = DoubleArray(tpl.size) { (tpl[it].toInt() and 0xFF) - tMean }
        var tVar = 0.0
        for (d in dev) tVar += d * d
        return TemplateStats(tMean, dev, tVar)
    }

    /** 单点 NCC 值：在 (x,y) 处对齐模板计算。 */
    fun nccAt(
        gray: ByteArray, width: Int,
        tpl: ByteArray, tw: Int, th: Int,
        x: Int, y: Int
    ): Double = nccAt(gray, width, templateStats(tpl), tw, th, x, y)

    /** 单点 NCC（模板统计量已缓存版，供热力图/批量位置复用）。 */
    private fun nccAt(
        gray: ByteArray, width: Int,
        ts: TemplateStats, tw: Int, th: Int,
        x: Int, y: Int
    ): Double {
        if (ts.varSum <= 0.0) return 0.0
        val tpl = ts.dev

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
                cross += fd * tpl[j * tw + i]
                fVar += fd * fd
            }
        }
        if (fVar <= 0.0) return 0.0
        return cross / kotlin.math.sqrt(fVar * ts.varSum)
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
        val ts = templateStats(tpl)
        if (ts.varSum <= 0.0) return doubleArrayOf(x0.toDouble(), y0.toDouble(), 0.0)
        val tDev = ts.dev

        var bestX = x0
        var bestY = y0
        var bestScore = -2.0
        val xs = x0.coerceAtLeast(0)..x1.coerceAtMost(width - tw)
        val ys = y0.coerceAtLeast(0)..y1.coerceAtMost(height - th)
        // 搜索区间为空时不能把初值 -2.0 当成 NCC 返回：-2 不在 NCC 的值域
        // [-1,1] 内，调用方（如逐帧识别的「丢失帧」统计）会把它当真实分数计入。
        // 与上面 varSum<=0 的分支保持同一约定，返回 0.0。
        if (xs.isEmpty() || ys.isEmpty()) return doubleArrayOf(x0.toDouble(), y0.toDouble(), 0.0)

        // 窗口和 fSum 改由「高度带列和 + 行前缀」滑动维护。
        // 等价性：fSum 是像素值（0..255）的整数和，用 Long 累加精确无溢出，
        // 整数加法与求和次序无关 ⇒ 每个位置的 fSum 与原来「逐像素从 0.0 累加」
        // 的 double 结果（该量级下 double 累加同样精确）逐位相同，
        // 因此 fMean = fSum / tpl.size 的浮点值不变。
        // 代价从「每个位置 O(tw·th)」降为「每行 O(width)」；互相关/方差那一趟
        // 逐像素的浮点乘加次序完全没动。
        val band = LongArray(width)
        val prefix = LongArray(width + 1)
        val yFirst = ys.first
        for (j in 0 until th) {
            val row = (yFirst + j) * width
            for (x in 0 until width) band[x] += (gray[row + x].toInt() and 0xFF).toLong()
        }
        for (y in ys) {
            if (y != yFirst) {
                // 高度带向下滚动一行：加进新行、减去离开的那一行
                val addRow = (y + th - 1) * width
                val subRow = (y - 1) * width
                for (x in 0 until width) {
                    band[x] += (gray[addRow + x].toInt() and 0xFF) -
                        (gray[subRow + x].toInt() and 0xFF)
                }
            }
            var acc = 0L
            prefix[0] = 0L
            for (x in 0 until width) {
                acc += band[x]
                prefix[x + 1] = acc
            }
            for (x in xs) {
                val fMean = (prefix[x + tw] - prefix[x]).toDouble() / tpl.size
                var cross = 0.0
                var fVar = 0.0
                for (j in 0 until th) {
                    val row = (y + j) * width + x
                    for (i in 0 until tw) {
                        val fd = (gray[row + i].toInt() and 0xFF) - fMean
                        cross += fd * tDev[j * tw + i]
                        fVar += fd * fd
                    }
                }
                val score = if (fVar <= 0.0) 0.0
                else cross / kotlin.math.sqrt(fVar * ts.varSum)
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
        // 热力图有数百个网格点：模板统计量提到循环外，只算一次
        val ts = templateStats(tpl)
        var k = 0
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                out[k++] = nccAt(gray, width, ts, tw, th, x * step, y * step).toFloat()
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
