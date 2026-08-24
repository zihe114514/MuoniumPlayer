package com.muoniumplayer.core.utils.other.multithreading;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台重活的专用线程池：解码、转码、抽帧、图像卷积、缓存清理。
 *
 * <p>为什么不继续用 {@link MultiThreadingUtil#runAsync}：那是一个<b>无界</b>的缓存线程池，线程优先级是
 * 默认的 {@link Thread#NORM_PRIORITY}——和 Minecraft 的渲染线程<b>完全相同</b>。于是一次 automix 预备
 * （整首歌解码 + 若干次 ffmpeg 分析）或者一张动态封面（抽帧 + 31 半径高斯模糊）会和渲染线程平等地争
 * CPU，表现就是切歌前后掉帧。而那个池同时承载着用户点下去正在等结果的活（搜索、登录、歌单），不能
 * 整体降优先级。</p>
 *
 * <p>所以后台活单独一个池，三条约束：</p>
 * <ul>
 *   <li><b>最低优先级</b>：渲染线程永远优先拿到 CPU。这不是硬实时保证，但在任何主流调度器上都足够
 *       把「后台在忙」和「画面卡顿」分开。</li>
 *   <li><b>并发有上限</b>：预备解码、动态封面、缓存清理三件事可能同时到来。不限并发时它们会一起占满
 *       所有核心，此时优先级也救不了——每个核上都只有低优先级任务可跑。</li>
 *   <li><b>队列无界</b>：后台任务本来就稀疏（一首歌一次），丢任务的代价（下一首没预备上、封面不动）
 *       远大于多排一会儿。</li>
 * </ul>
 *
 * <p>任务抛出的异常只落到返回的 future 上，不会杀掉工作线程——池的线程是长期复用的。</p>
 */
public final class BackgroundWork {

    private BackgroundWork() {
    }

    /**
     * 并发上限。留出核心给渲染与音频引擎：4 核及以下只给 1 条，再多也不超过 2 条。
     *
     * <p>上限刻意压得很低。这些任务单条就能吃满一个核（ffmpeg 默认还会自己开满线程，所以调用方额外
     * 传了 {@code -threads 1}），真正的瓶颈从来不是排队深度而是抢核。</p>
     */
    private static final int MAX_CONCURRENCY =
            Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 2));

    private static final ExecutorService EXECUTOR = create();

    private static ExecutorService create() {
        final AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "muonium-bg-" + counter.incrementAndGet());
                thread.setDaemon(true);
                try {
                    thread.setPriority(Thread.MIN_PRIORITY);
                } catch (RuntimeException ignored) {
                    // 某些安全管理器下不允许改优先级；那就按默认优先级跑，功能不受影响。
                }
                return thread;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(MAX_CONCURRENCY, MAX_CONCURRENCY,
                30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), factory);
        // 空闲时把线程还给系统：整局游戏里大多数时间这个池是空的。
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** 当前允许的并发数，供日志与自检使用。 */
    public static int maxConcurrency() {
        return MAX_CONCURRENCY;
    }

    /**
     * 把一件后台重活排进低优先级池。
     *
     * @return 完成信号；任务抛出的异常经由 {@code completeExceptionally} 传出，工作线程不受影响
     */
    public static CompletableFuture<Void> submit(final Runnable runnable) {
        final CompletableFuture<Void> future = new CompletableFuture<Void>();
        if (runnable == null) {
            future.completeExceptionally(new IllegalArgumentException("Runnable is null"));
            return future;
        }
        try {
            EXECUTOR.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        runnable.run();
                        future.complete(null);
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            // 池被关闭（游戏退出中）时不该把异常抛给调用方的业务逻辑。
            future.completeExceptionally(rejected);
        }
        return future;
    }
}