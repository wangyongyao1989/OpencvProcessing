package com.wangyao.opencvprocessing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wangyao.opencvdeal.jni.OpencvDealJni
import com.wangyao.opencvprocessing.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 显示 OpenCV 版本号
        binding.tvOpencvVersion.text = "OpenCV: ${OpencvDealJni.getOpencvVersion()}"

        // 从 assets 加载原始图片
        val originalBitmap = loadBitmapFromAssets("IMG_20260821_190758.jpg")
        if (originalBitmap == null) {
            binding.tvTitle.text = "无法加载 assets/IMG_20260821_190758.jpg"
            return
        }

        // ① 显示原始图像
        binding.ivOriginal.setImageBitmap(originalBitmap)

        // ② 灰度线性变换 (式 2-1)：将 [50, 200] 映射到 [0, 255]
        OpencvDealJni.grayLinearTransform(originalBitmap, 50.0, 200.0, 0.0, 255.0)
            ?.let { binding.ivLinear.setImageBitmap(it) }

        // ③ 反转变换 (图 2-3)
        OpencvDealJni.grayInvertTransform(originalBitmap)
            ?.let { binding.ivInvert.setImageBitmap(it) }

        // ④ 对比度扩展/分段线性 (式 2-3)：a=80, b=180, c=30, d=220
        OpencvDealJni.grayPiecewiseLinear(originalBitmap, 80.0, 180.0, 30.0, 220.0)
            ?.let { binding.ivPiecewise.setImageBitmap(it) }

        // ⑤ 削波 (图 2-6)：抑制 [0,100] 和 [180,255]
        OpencvDealJni.grayClipTransform(originalBitmap, 100.0, 180.0)
            ?.let { binding.ivClip.setImageBitmap(it) }

        // ⑥ 阈值化 (图 2-7)：T=128
        OpencvDealJni.grayThresholdTransform(originalBitmap, 128.0)
            ?.let { binding.ivThreshold.setImageBitmap(it) }

        // ⑦ 对数变换 (式 2-4)：c=0 自动计算
        OpencvDealJni.grayLogTransform(originalBitmap, 0.0)
            ?.let { binding.ivLog.setImageBitmap(it) }

        // ⑧ 伽马变换 (式 2-5)：γ=0.5, c=1.0
        OpencvDealJni.grayGammaTransform(originalBitmap, 1.0, 0.5)
            ?.let { binding.ivGamma.setImageBitmap(it) }

        // ⑨ 直方图均衡化 (式 2-14)
        OpencvDealJni.grayHistogramEqualize(originalBitmap)
            ?.let { binding.ivHisteq.setImageBitmap(it) }
    }

    /**
     * 从 assets 目录加载 Bitmap
     */
    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            assets.open(fileName).use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                // 确保格式为 ARGB_8888，JNI 层按此格式处理
                if (bmp != null && bmp.config != Bitmap.Config.ARGB_8888) {
                    bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
                } else {
                    bmp
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * A native method that is implemented by the 'opencvprocessing' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'opencvprocessing' library on application startup.
        init {
            System.loadLibrary("opencvprocessing")
        }
    }
}
