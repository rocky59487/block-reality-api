package com.blockreality.api.physics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /**
     * ★ Round 5 fix: synchronized 防止 start() 的 check-then-act race。
     * 兩個執行緒同時呼叫 start() 時，volatile 只保證讀取可見性，
     * 但 `if (executor != null) ... executor = ...` 不是原子操作。
     */
    public static synchronized void start() {
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
     *
     * @param snapshot   含 margin 的完整掃描區快照
     * @param scanMargin 掃描邊距（崩塌區 = 快照 - 2*margin）
     */
    public static CompletableFuture<UnionFindEngine.PhysicsResult> submit(RWorldSnapshot snapshot, int scanMargin) {
        // ★ Round 5 fix: null snapshot 提前拒絕，避免 NPE 在 worker thread
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        ExecutorService exec = executor; // volatile read once
        if (exec == null || exec.isShutdown()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("PhysicsExecutor not running")
            );
        }
        return CompletableFuture.supplyAsync(
            () -> UnionFindEngine.findUnsupportedBlocks(snapshot, scanMargin),
            exec
        );
    }

    /** 無 margin 版本（向後相容） */
    public static CompletableFuture<UnionFindEngine.PhysicsResult> submit(RWorldSnapshot snapshot) {
        return submit(snapshot, 0);
    }

    public static synchronized void shutdown() {
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
