package com.wangyao.opencvprocessing

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.fragment.ImageGrayTransformFragment
import com.wangyao.opencvprocessing.fragment.MainFragment

/**
 * 应用唯一的 Activity，负责：
 * 1. 初始化 FFViewModel 并订阅 [FFViewModel.switchFragment]；
 * 2. 按枚举值在 `fragment_container` 内切换 Fragment（hide/show + tag 复用）；
 * 3. 统一拦截系统返回键：非主界面则回到 MAIN，主界面则 finish。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ffViewModel: FFViewModel
    private var currentFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ffViewModel = ViewModelProvider(this)[FFViewModel::class.java]
        ffViewModel.switchFragment.observe(this) { status ->
            if (status != null) selectFragment(status)
        }

        if (savedInstanceState == null) {
            selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
        } else {
            // 恢复被系统重建后 currentFragment 的引用
            currentFragment = supportFragmentManager
                .findFragmentById(R.id.fragment_container)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentFragment) {
                    is MainFragment -> finish()
                    else -> selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                }
            }
        })
    }

    private fun selectFragment(status: FFViewModel.FRAGMENT_STATUS) {
        val fm: FragmentManager = supportFragmentManager
        val tx: FragmentTransaction = fm.beginTransaction()
        val tag = status.name

        var target: Fragment? = fm.findFragmentByTag(tag)
        if (target == null) {
            target = when (status) {
                FFViewModel.FRAGMENT_STATUS.MAIN -> MainFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_GRAY_TRANSFORM -> ImageGrayTransformFragment()
            }
        }

        val cur = currentFragment
        if (cur != null && cur !== target) {
            tx.hide(cur)
        }
        if (!target.isAdded) {
            tx.add(R.id.fragment_container, target, tag)
        } else {
            tx.show(target)
        }
        currentFragment = target
        tx.commit()
    }
}
