package com.wangyao.opencvprocessing

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import com.wangyao.opencvprocessing.databinding.ActivityMainBinding
import com.wangyao.opencvdeal.jni.OpencvDealJni

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 显示 OpenCV 版本号（由 opencvdeal 模块提供）
        binding.sampleText.text = "OpenCV version: ${OpencvDealJni.getOpencvVersion()}"
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