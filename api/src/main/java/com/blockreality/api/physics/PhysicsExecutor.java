package com.blockreality.api.physics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 物理運算專用執行緒池。
 * 單例管理，生命週期跟隨 Minecraft Server。
 *
 * ★ Phase 2 升級：改用 ForkJoinPool 支援多島嶼並行計算。
 * - 不同 island 的物理任務可以並行執行
 * - 保留 2 個 CPU 核心給 Minecraft server thread 和 network I/O
 * - 可透過 BRConfig.physicsThreadCount 配置（預設 auto）
 *
 * 向後相容：原有的 submit(snapshot, scanMargin) API 保持不變。
 */
public class PhysicsExecutor {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/PhysicsExecutor");

    private static volatile ExecutorService executor;

    /** 目前配置的執行緒數（用於診斷） */
    private static volatile int configuredThreadCount;

    /**
     * 使用預設執行緒數啟動（auto: availableProcessors - 2，最少 1）。
     */
    public static synchronized void start() {
        start(0); // 0 = auto
    }

    /**
     * ★ Phase 2: 使用指定執行緒數啟動。
     *
     * @param threadCount 執行緒數（0 = auto，使用 availableProcessors - 2）
     */
    public static synchronized void start(int threadCount) {
        if (executor != null && !executor.isShutdown()) {
            LOGGER.warn("PhysicsExecutor already running");
            return;
        }

        int cores = Runtime.getRuntime().availableProcessors();
        int threads;
        if (threadCount <= 0) {
            // Auto: 保留 2 核給 MC server thread + network
            threads = Math.max(1, cores - 2);
        } else {
            threads = Math.max(1, Math.min(threadCount, cores));
        }
        configuredThreadCount = threads;

        if (threads == 1) {
            // 單核心：使用單執行緒 executor（最低開銷）
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BR-Physics");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
            LOGGER.info("PhysicsExecutor started (single thread, daemon)");
        } else {
            // 多核心：使用 ForkJoinPool 支援 work-stealing
            AtomicInteger threadNum = new AtomicInteger(0);
            executor = new ForkJoinPool(threads,
                pool -> {
                    ForkJoinPool.ForkJoinWorkerThreadFactory factory =
                        ForkJoinPool.defaultForkJoinWorkerThreadFactory;
                    var worker = factory.newThread(pool);
                    worker.setName("BR-Physics-" + threadNum.getAndIncrement());
                    worker.setDaemon(true);
                    worker.setPriority(Thread.NORM_PRIORITY - 1);
                    r