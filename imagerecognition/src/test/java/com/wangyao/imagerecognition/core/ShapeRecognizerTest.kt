package com.wangyao.imagerecognition.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [HuMoments] 与 [ShapeRecognizer] 单元测试（第 11 章 11.3~11.4 节：
 * Hu 不变矩形状特征 + 最近邻分类）。
 *
 * 验证维度：
 * 1. Hu 矩三大不变性：平移 / 旋转 / 尺度（对数 Hu 距离应近似为 0）；
 * 2. 类间可分性：不同形状的 Hu 距离远大于同形状变换副本的距离；
 * 3. 最近邻分类器：5 类形状 × 各种变换都能正确分类；
 * 4. 边界行为：空图 / 全零掩码安全返回。
 */
class ShapeRecognizerTest {

    // -------------------------------------------------------------------------
    // 测试工具
    // -------------------------------------------------------------------------

    /** 渲染形状并计算对数 Hu 特征。 */
    private fun huOf(shape: ShapeRecognizer.Shape, rot: Double = 0.0, scale: Double = 1.0) =
        ShapeRecognizer().huOf(shape, rot, scale)

    /**
     * 与产品分类器一致的加权对数 Hu 距离：只用 H1~H3。
     * H4~H7 原值趋零、-lg 放大后对像素离散化噪声极敏感
     * （同形状异变换间可发生符号翻转），判别上不可靠。
     */
    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 3) {
            val d = a[i] - b[i]
            s += d * d
        }
        return kotlin.math.sqrt(s)
    }

    /** 对掩码做整数平移（content 平移 SIZE/8，零填充）。 */
    private fun shiftMask(mask: ByteArray, size: Int, dx: Int, dy: Int): ByteArray {
        val out = ByteArray(mask.size)
        for (y in 0 until size) {
            val sy = y - dy
            if (sy in 0 until size) {
                for (x in 0 until size) {
                    val sx = x - dx
                    if (sx in 0 until size) out[y * size + x] = mask[sy * size + sx]
                }
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Hu 矩不变性（教材核心性质）
    // -------------------------------------------------------------------------

    @Test
    fun `Hu moments are translation invariant`() {
        val shape = ShapeRecognizer.Shape.TRIANGLE
        val base = ShapeRecognizer.renderShape(shape, ShapeRecognizer.SIZE)
        val shifted = shiftMask(base, ShapeRecognizer.SIZE, 12, -8)
        val h1 = HuMoments.compute(base, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
        val h2 = HuMoments.compute(shifted, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
        // 逐分量近似相等（离散化带来微小误差）
        for (k in 0 until 7) {
            assertTrue(
                "H[$k] differs after translation: ${h1[k]} vs ${h2[k]}",
                abs(h1[k] - h2[k]) < 1e-6
            )
        }
    }

    @Test
    fun `log-Hu distance is near zero under rotation`() {
        // 旋转不变性：0° vs 90°（精确旋转，离散化误差最小）
        val shape = ShapeRecognizer.Shape.RECT
        val h0 = huOf(shape, rot = 0.0)
        val h90 = huOf(shape, rot = 90.0)
        assertTrue("rotation distance ${dist(h0, h90)} too large", dist(h0, h90) < 0.5)
    }

    @Test
    fun `log-Hu distance is near zero under scaling`() {
        // 尺度不变性（稳定形状）：圆 1.0x vs 1.3x，低阶特征几乎不变
        val shape = ShapeRecognizer.Shape.CIRCLE
        val h1 = huOf(shape, scale = 1.0)
        val h2 = huOf(shape, scale = 1.3)
        assertTrue("scale distance ${dist(h1, h2)} too large", dist(h1, h2) < 0.5)
    }

    @Test
    fun `scaled shape stays closer to itself than to other shapes`() {
        // 尺度不变性（敏感形状）：星形高阶矩对缩放离散化极敏感，
        // 绝对距离不恒小，但「同形状异尺度」必须显著近于「异形状」
        // ——这正是分类器可用的判别性质
        val star1 = huOf(ShapeRecognizer.Shape.STAR, scale = 1.0)
        val star13 = huOf(ShapeRecognizer.Shape.STAR, scale = 1.3)
        val triangle = huOf(ShapeRecognizer.Shape.TRIANGLE)
        val intra = dist(star1, star13)
        val inter = dist(star1, triangle)
        assertTrue(
            "intra=$intra should be smaller than inter=$inter",
            intra < inter
        )
    }

    @Test
    fun `log-Hu distance is near zero under rotation plus scale`() {
        val shape = ShapeRecognizer.Shape.CIRCLE
        val h1 = huOf(shape)
        val h2 = huOf(shape, rot = 53.0, scale = 1.4)
        assertTrue("combined distance ${dist(h1, h2)} too large", dist(h1, h2) < 0.5)
    }

    @Test
    fun `empty mask yields all-zero Hu moments`() {
        val h = HuMoments.compute(ByteArray(64 * 64), 64, 64)
        assert(h.all { it == 0.0 })
        // 对数变换后也应全零（0 的特殊处理）
        val lt = HuMoments.logTransform(h)
        assert(lt.all { it == 0.0 })
    }

    @Test
    fun `logTransform compresses dynamic range`() {
        // 实心圆 H1 = 2η20 ≈ r²/(2m00) ≈ 0.16，取对数后 -lg(0.16) ≈ 0.8：
        // H 值压到 (0,1) 区间后其对数变换为正的 O(1) 数值
        val mask = ShapeRecognizer.renderShape(
            ShapeRecognizer.Shape.CIRCLE, ShapeRecognizer.SIZE
        )
        val h = HuMoments.compute(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
        val lt = HuMoments.logTransform(h)
        assertTrue("H1=${h[0]} should be in (0,1)", h[0] in 0.0..1.0)
        assertTrue("log-H1=${lt[0]} should be O(1)", lt[0] in 0.1..3.0)
        assertTrue(lt[0] > 0) // 小于 1 的正数取 -lg 后为正
    }

    // -------------------------------------------------------------------------
    // 类间可分性与分类器
    // -------------------------------------------------------------------------

    @Test
    fun `different shapes are well separated in Hu space`() {
        // 类内距离：圆 0° vs 90°（圆具有连续旋转对称，特征几乎不变）
        val intra = dist(
            huOf(ShapeRecognizer.Shape.CIRCLE, rot = 0.0),
            huOf(ShapeRecognizer.Shape.CIRCLE, rot = 90.0)
        )
        // 类间距离：圆 vs 三角形
        val inter = dist(
            huOf(ShapeRecognizer.Shape.CIRCLE),
            huOf(ShapeRecognizer.Shape.TRIANGLE)
        )
        assertTrue("intra=$intra should be much smaller than inter=$inter", inter > intra * 3)
        assertTrue("inter=$inter should be clearly nonzero", inter > 1.0)
    }

    @Test
    fun `classify recognizes all five shapes without transform`() {
        val recognizer = ShapeRecognizer()
        for (shape in ShapeRecognizer.Shape.entries) {
            val mask = ShapeRecognizer.renderShape(shape, ShapeRecognizer.SIZE)
            val result = recognizer.classify(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
            assertEquals("shape $shape misclassified", shape, result.label)
            // 未变换的查询与库内原始样本特征一致 → 距离≈0
            assertEquals(shape, result.top3[0].first)
            assertTrue("top-1 distance ${result.top3[0].second} should be ~0",
                result.top3[0].second < 1e-6)
        }
    }

    @Test
    fun `classify is robust to rotation`() {
        val recognizer = ShapeRecognizer()
        val cases = listOf(
            ShapeRecognizer.Shape.CIRCLE to 37.0,
            ShapeRecognizer.Shape.RECT to 30.0,
            ShapeRecognizer.Shape.TRIANGLE to 45.0,
            ShapeRecognizer.Shape.STAR to 22.0,
            ShapeRecognizer.Shape.ELLIPSE to 40.0
        )
        for ((shape, rot) in cases) {
            val mask = ShapeRecognizer.renderShape(
                shape, ShapeRecognizer.SIZE, rot, 1.0
            )
            val result = recognizer.classify(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
            assertEquals("$shape rotated $rot° misclassified", shape, result.label)
        }
    }

    @Test
    fun `classify is robust to rotation plus scale`() {
        val recognizer = ShapeRecognizer()
        for (shape in ShapeRecognizer.Shape.entries) {
            val mask = ShapeRecognizer.renderShape(
                shape, ShapeRecognizer.SIZE, 53.0, 1.5
            )
            val result = recognizer.classify(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
            assertEquals("$shape rotated+scaled misclassified", shape, result.label)
        }
    }

    @Test
    fun `classify translates correctly`() {
        // 掩码平移后分类（平移不变性的端到端验证）
        val recognizer = ShapeRecognizer()
        val mask = shiftMask(
            ShapeRecognizer.renderShape(ShapeRecognizer.Shape.STAR, ShapeRecognizer.SIZE),
            ShapeRecognizer.SIZE, 10, -10
        )
        val result = recognizer.classify(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
        assertEquals(ShapeRecognizer.Shape.STAR, result.label)
    }

    @Test
    fun `confidence is in valid range and top3 has 3 entries`() {
        val recognizer = ShapeRecognizer()
        val mask = ShapeRecognizer.renderShape(
            ShapeRecognizer.Shape.ELLIPSE, ShapeRecognizer.SIZE, 33.0, 1.3
        )
        val result = recognizer.classify(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
        assertEquals(3, result.top3.size)
        assertTrue(result.confidence in 0.0..1.0)
        assertTrue(result.confidence > 0.5)
        // Top-3 按距离升序
        assertTrue(result.top3[0].second <= result.top3[1].second)
        assertTrue(result.top3[1].second <= result.top3[2].second)
    }

    // -------------------------------------------------------------------------
    // Hu 矩闭式数学验证（11.3 节公式正确性）
    // -------------------------------------------------------------------------

    @Test
    fun `H1 of solid circle matches closed form 1 over 2pi`() {
        // 实心圆解析解：η20=η02=1/(4π)，故 H1=η20+η02=1/(2π)≈0.15915。
        // 这是 Hu 矩实现正确性最强的独立验证——不含任何库内自参照。
        val mask = ShapeRecognizer.renderShape(
            ShapeRecognizer.Shape.CIRCLE, ShapeRecognizer.SIZE
        )
        val h = HuMoments.compute(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
        assertEquals(1.0 / (2 * kotlin.math.PI), h[0], 1e-3)
    }

    @Test
    fun `H1 of solid rectangle matches closed form`() {
        // 实心矩形 a×b 解析解：η20=a/(12b)、η02=b/(12a)，
        // H1=(a/b+b/a)/12。渲染 RECT 半宽 0.85r、半高 0.6r → a/b=17/12。
        val mask = ShapeRecognizer.renderShape(
            ShapeRecognizer.Shape.RECT, ShapeRecognizer.SIZE
        )
        val h = HuMoments.compute(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
        val ab = (2 * 0.85) / (2 * 0.6)
        val expected = (ab + 1 / ab) / 12
        assertEquals(expected, h[0], 5e-3)
    }

    @Test
    fun `H1 ordering between circle and rectangle matches theory`() {
        // 理论：圆 H1=0.159 < 矩形(17/12) H1=0.177（矩形更「方」）。
        // 相对差异仅 ~11%，实现必须保留此精细排序。
        val circle = HuMoments.compute(
            ShapeRecognizer.renderShape(ShapeRecognizer.Shape.CIRCLE, ShapeRecognizer.SIZE),
            ShapeRecognizer.SIZE, ShapeRecognizer.SIZE
        )[0]
        val rect = HuMoments.compute(
            ShapeRecognizer.renderShape(ShapeRecognizer.Shape.RECT, ShapeRecognizer.SIZE),
            ShapeRecognizer.SIZE, ShapeRecognizer.SIZE
        )[0]
        assertTrue(circle < rect)
    }

    // -------------------------------------------------------------------------
    // 形状渲染
    // -------------------------------------------------------------------------

    @Test
    fun `renderShape produces non-empty binary masks`() {
        for (shape in ShapeRecognizer.Shape.entries) {
            val mask = ShapeRecognizer.renderShape(shape, ShapeRecognizer.SIZE)
            val fg = mask.count { (it.toInt() and 0xFF) > 0 }
            // 前景占比应在合理区间（非空、非满）
            assertTrue("shape $shape fg=$fg too small", fg > mask.size / 20)
            assertTrue("shape $shape fg=$fg too large", fg < mask.size / 2)
            // 值只能是 0 或 255
            assert(mask.all { (it.toInt() and 0xFF) in listOf(0, 255) })
        }
    }

    @Test
    fun `renderShape masks differ between shapes`() {
        val circle = ShapeRecognizer.renderShape(ShapeRecognizer.Shape.CIRCLE, ShapeRecognizer.SIZE)
        val ellipse = ShapeRecognizer.renderShape(ShapeRecognizer.Shape.ELLIPSE, ShapeRecognizer.SIZE)
        // 圆与椭圆掩码应有显著差异（椭圆长短轴 0.55）
        var diff = 0
        for (i in circle.indices) {
            if ((circle[i].toInt() and 0xFF) != (ellipse[i].toInt() and 0xFF)) diff++
        }
        assertTrue(diff > circle.size / 20)
    }
}
