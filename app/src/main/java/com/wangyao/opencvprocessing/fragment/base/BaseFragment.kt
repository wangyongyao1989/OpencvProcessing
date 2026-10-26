package com.wangyao.opencvprocessing.fragment.base

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fragment 基类（参考 WyFFmpeg BaseFragment 四阶段初始化流程）。
 *
 * 子类按顺序覆写：
 * 1. [getLayoutBinding] -> inflate ViewBinding
 * 2. [initView] -> findViewById / binding 赋值
 * 3. [initData] -> 初始数据（如加载 assets 图片）
 * 4. [initObserver] -> ViewModel LiveData 订阅
 * 5. [initListener] -> 点击 / 勾选监听
 *
 * 另提供统一的「后台算法 → 回主线程更新」通道（[beginWork] / [submitWork] /
 * [runOnUiThreadIfLatest]），解决三件事：
 * - 每次点击新建线程且从不复用（进入 12 个页面 × 反复点击 = 线程数无上限）；
 * - 快速连点时后点的先算完、先点的后算完，最终显示的是「上一个选项」的结果；
 * - 页面已切走时后台线程仍回调操作已失效的视图。
 */
abstract class BaseFragment : Fragment() {

    /** 本实例的任务序号：每次 [beginWork] 自增，用于「只认最新一次点击」。 */
    private val workSeq = AtomicInteger(0)

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = getLayoutBinding(inflater, container, savedInstanceState)
        initView()
        initData()
        initObserver()
        initListener()
        return root
    }

    /** 返回此 Fragment 的根 View（来自 ViewBinding）。 */
    abstract fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View

    open fun initView() = Unit

    open fun initData() = Unit

    open fun initObserver() = Unit

    open fun initListener() = Unit

    // -------------------------------------------------------------------------
    // 后台算法队列
    // -------------------------------------------------------------------------

    /** 开启一次后台任务，返回任务序号（须回传给 [runOnUiThreadIfLatest]）。 */
    protected fun beginWork(): Int = workSeq.incrementAndGet()

    /** 把 [work] 排入全 app 共用的串行算法队列。 */
    protected fun submitWork(seq: Int, work: () -> Unit) {
        SHARED_WORK_QUEUE.execute(Runnable {
            // 排队期间若用户又点了别的选项，这个任务已经没意义了：直接丢弃，
            // 避免「过期任务把全图算法算完再覆盖正确结果」。
            if (workSeq.get() != seq) {
                Log.d(TAG, "drop stale work seq=$seq (latest=${workSeq.get()})")
                return@Runnable
            }
            try {
                work()
            } catch (t: Throwable) {
                Log.e(TAG, "work seq=$seq failed", t)
            }
        })
    }

    /** 仅当 [seq] 仍是最新任务、且视图仍然存活时，在主线程执行 [block]。 */
    protected fun runOnUiThreadIfLatest(seq: Int, block: () -> Unit) {
        if (workSeq.get() != seq) return
        val act = activity ?: return
        // 坑：存活检查必须放到主线程里做，不能在这里判 `view == null` 就 return。
        // Fragment 的 mView 要等 onCreateView **返回之后**才被赋值，而子类的 initData()
        // 是在 onCreateView 内部调用的；页面初始化时提交的「默认第一项」运算如果做得很快
        // （例如 ① 原图基准 只是 `{ src -> src }`，不经过 JNI），回调就会在 mView 仍为
        // null 时发生，被这个守卫**静默丢弃** —— 真机 A/B 实测：形态学·灰度基本/灰度处理、
        // 分割·阈值分割/边缘分割 这 4 页的「① 原图基准」结果区整屏 30s 内始终空白，
        // 而基线轮正常显示原图（差异 6.30%、包围盒正好是结果面板 330x440 全区域）。
        // 慢运算（>几十毫秒）不受影响，因为回调时视图早已 attach —— 所以这是「快运算专属」
        // 的隐蔽回归。改成先切主线程、再在主线程判存活，语义与原 `activity?.runOnUiThread`
        // 一致，同时保留「只认最新一次点击」和「视图已销毁不再操作」。
        act.runOnUiThread {
            if (workSeq.get() == seq && isAdded && view != null) block()
        }
    }

    companion object {
        private const val TAG = "BaseFragment"

        /**
         * 全 app 共用的单线程队列：同一时刻只有一个页面在做 OpenCV 全图运算，
         * 既不会因连点产生多个并发大图运算（内存峰值叠加），也不会在每次
         * 进入页面时新建线程。守护线程，随进程结束自然回收。
         */
        private val SHARED_WORK_QUEUE: Executor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "CvAlgoWorker").apply { isDaemon = true }
            }
    }
}
