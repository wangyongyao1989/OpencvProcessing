#ifndef OPENCVPROCESSING_PERFTIMER_H
#define OPENCVPROCESSING_PERFTIMER_H

#include <android/log.h>
#include <chrono>

/**
 * 昂贵算子的耗时打点（RAII，析构时打印）。
 *
 * 为什么需要它：真机 A/B 用 UI 轮询取耗时，地板约 1.2 s，短于它的耗时
 * 根本测不出来，也无法区分「慢在算法」还是「慢在 JNI 往返 / 主线程排队」。
 * 日志里的 `cost=` 才是可以逐次比较的量，判「等价性能」时用它。
 *
 * 纯观测：不读写任何 Mat，构造/析构都不影响像素结果。
 * 用法：函数开头 `ScopedLogTimer timer(TAG, "segWatershed");`
 */
class ScopedLogTimer {
public:
    /**
     * @param tag   logcat tag（各算子文件自己的 TAG 宏）
     * @param what  算子名（日志里 %s）
     * @param ksize 可选的结构元素/核尺寸参数，无则 -1
     */
    ScopedLogTimer(const char *tag, const char *what, int ksize = -1)
            : tag_(tag), what_(what), ksize_(ksize),
              t0_(std::chrono::steady_clock::now()) {}

    ~ScopedLogTimer() {
        const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t0_).count();
        __android_log_print(ANDROID_LOG_INFO, tag_, "%s: ksize=%d cost=%lldms",
                            what_, ksize_, ms);
    }

    ScopedLogTimer(const ScopedLogTimer &) = delete;
    ScopedLogTimer &operator=(const ScopedLogTimer &) = delete;

private:
    const char *tag_;
    const char *what_;
    int ksize_;
    std::chrono::steady_clock::time_point t0_;
};

#endif //OPENCVPROCESSING_PERFTIMER_H
