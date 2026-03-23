package com.blockreality.api.physics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.*;

/**
 * 物理運算專用執行緒池。
 * 單例管理，生命週期跟隨 Minecraft Server。
 *
 * 使用單執行緒 executor — 物理計算是 CPU-bound，
 * 多執行緒反而增加排程開銷且快照是循序擷取的。
 */
public class PhysicsExecutor {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/PhysicsExecutor");

    private static volatile ExecutorService executor;

    public static void start() {
        if (executor != null && !executor.isShutdown()) {
            LOGGER.warn("PhysicsExecutor already running");
            return;
        }
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BR-Physics");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // 略低於主執行緒
            return t;
        });
        LOGGER.info("PhysicsExecutor started (single thread, daemon)");
    }

    /**
     * 提交快照進行非同步物理運算。
     * 快照擷取必須在主執行緒完成，此方法只負責 BFS 運算。
     */
    public static CompletableFuture<UnionFindEngine.PhysicsResult> submit(RWorldSnapshot snapshot) {
        if (executor == null || executor.isShutdown()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("PhysicsExecutor not running")
            );
        }
        return CompletableFuture.supplyAsync(
            () -> UnionFindEngine.findUnsupportedBlocks(snapshot),
            executor
        );
    }

    public static void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOGGER.info("PhysicsExecutor stopped");
        }
    }
}
