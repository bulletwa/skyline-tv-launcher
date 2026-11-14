package com.tvlauncher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Worker pools whose threads exit after 20 s idle, so a launcher that sits in the background holds no idle threads. */
final class Pools {
    private Pools() {}

    static ExecutorService fixed(int threads, final String name) {
        ThreadPoolExecutor e = new ThreadPoolExecutor(threads, threads, 20, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), new java.util.concurrent.ThreadFactory() {
            private int n;
            @Override public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + (++n));
                t.setPriority(Thread.NORM_PRIORITY - 1); // background work never competes with drawing
                return t;
            }
        });
        e.allowCoreThreadTimeOut(true);
        return e;
    }
}
