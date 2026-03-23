// src/main/java/com/blockreality/api/sidecar/SidecarBridge.java
package com.blockreality.api.sidecar;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Java ↔ TypeScript Sidecar 橋接器（JSON-RPC 2.0 over stdio）
 * 使用方式：
 *   SidecarBridge bridge = SidecarBridge.getInstance();
 *   bridge.start();
 *   JsonObject result = bridge.call("dualContouring", params, 10_000);
 *   bridge.stop();
 */
public class SidecarBridge {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Sidecar");
    private static final Gson GSON = new Gson();

    // v3-fix: 添加請求超時時間常數，用於定期清理
    private static final long REQUEST_TIMEOUT_MS = 30000;

    // ─── 單例（v2.0-fix：改用 Bill Pugh Holder，避免 DCL 指令重排序風險）───
    private static class Holder {
        private static final SidecarBridge INSTANCE = new SidecarBridge();
    }

    // v3-fix: 添加 readResolve 支持序列化
    private Object readResolve() {
        return Holder.INSTANCE;
    }

    private Process nodeProcess;
    private PrintWriter writer;
    private BufferedReader reader;

    // ─── writer 同步鎖（v2.0-fix：避免多執行緒同時寫入 stdout 導致 JSON 交錯）───
    // v3-fix: 重命名為 writeSyncLock 避免命名誤導
    private final Object writeSyncLock = new Object();

    // v3-fix: 添加狀態鎖，保護 running 和 writer 的原子性檢查
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();

    // RPC id 計數器
    private final AtomicInteger rpcId = new AtomicInteger(0);

    // v3-fix: 使用帶時間戳的封裝類替代直接使用 CompletableFuture
    private static class PendingEntry {
        final CompletableFuture<JsonObject> future;
        final long createTime;

        PendingEntry(CompletableFuture<JsonObject> future) {
            this.future = future;
            this.createTime = System.currentTimeMillis();
        }

        boolean isExpired(long now, long timeout) {
            return now - createTime > timeout;
        }
    }

    // 待回應的 Future 表（rpcId → PendingEntry）
    // v3-fix: 改用 PendingEntry 封裝類
    private final ConcurrentHashMap<Integer, PendingEntry> pending =
            new ConcurrentHashMap<>();

    // v3-fix: 添加定期清理執行器
    private final ScheduledExecutorService cleanupExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BR-Sidecar-Cleanup");
                t.setDaemon(true);
                return t;
            });

    // 讀取執行緒
    private volatile Thread readerThread;
    private volatile boolean running = false;

    private SidecarBridge() {
        // v3-fix: 啟動定期清理任務
        startCleanupTask();
    }

    public static SidecarBridge getInstance() {
        return Holder.INSTANCE;
    }

    // v3-fix: 修改點4 - 健康檢查相關欄位
    private volatile long lastSuccessfulCleanupTime = System.currentTimeMillis();
    private volatile long cleanupSuccessCount = 0;
    private volatile long cleanupErrorCount = 0;
    private static final long CLEANUP_HEALTH_TIMEOUT_MS = 300000; // 5分鐘健康超時

    // v3-fix: 修改點4 - 添加定期清理過期請求的方法（帶健康檢查）
    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                AtomicInteger cleanedCount = new AtomicInteger(0);

                pending.entrySet().removeIf(entry -> {
                    PendingEntry pendingEntry = entry.getValue();
                    if (pendingEntry.isExpired(now, REQUEST_TIMEOUT_MS * 2)) {
                        pendingEntry.future.completeExceptionally(
                            new SidecarException("Request expired by cleanup task")
                        );
                        LOGGER.warn("清理過期請求: id={}", entry.getKey());
                        cleanedCount.incrementAndGet();
                        return true;
                    }
                    return false;
                });

                // v3-fix: 修改點4 - 記錄成功清理時間
                lastSuccessfulCleanupTime = now;
                cleanupSuccessCount++;

                if (cleanedCount.get() > 0) {
                    LOGGER.debug("Cleanup task completed: {} expired requests removed", cleanedCount.get());
                }

            } catch (Exception e) {
                // v3-fix: 修改點4 - 記錄錯誤計數
                cleanupErrorCount++;
                LOGGER.error("清理任務執行異常 (error count: {})", cleanupErrorCount, e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    // v3-fix: 修改點4 - 添加健康檢查方法
    /**
     * 檢查清理任務的健康狀態
     * @return true if cleanup task is healthy
     */
    public boolean isCleanupHealthy() {
        long now = System.currentTimeMillis();
        long timeSinceLastSuccess = now - lastSuccessfulCleanupTime;

        if (timeSinceLastSuccess > CLEANUP_HEALTH_TIMEOUT_MS) {
            LOGGER.warn("Cleanup task unhealthy: last successful cleanup was {} ms ago",
                timeSinceLastSuccess);
            return false;
        }

        if (cleanupErrorCount > cleanupSuccessCount * 2 && cleanupErrorCount > 10) {
            LOGGER.warn("Cleanup task unhealthy: {} errors vs {} successes",
                cleanupErrorCount, cleanupSuccessCount);
            return false;
        }

        return true;
    }

    // v3-fix: 修改點4 - 獲取清理任務統計資訊
    /**
     * 獲取清理任務的統計資訊
     */
    public CleanupStats getCleanupStats() {
        return new CleanupStats(
            lastSuccessfulCleanupTime,
            cleanupSuccessCount,
            cleanupErrorCount,
            pending.size()
        );
    }

    // v3-fix: 修改點4 - 清理任務統計資訊記錄類
    public record CleanupStats(
        long lastSuccessfulCleanupTime,
        long successCount,
        long errorCount,
        int pendingRequestCount
    ) {
        public long getTimeSinceLastSuccessMs() {
            return System.currentTimeMillis() - lastSuccessfulCleanupTime;
        }

        public boolean isHealthy() {
            return getTimeSinceLastSuccessMs() <= CLEANUP_HEALTH_TIMEOUT_MS &&
                   (errorCount <= successCount * 2 || errorCount <= 10);
        }

        @Override
        public String toString() {
            return String.format(
                "CleanupStats{lastSuccess=%dms ago, successes=%d, errors=%d, pending=%d, healthy=%b}",
                getTimeSinceLastSuccessMs(), successCount, errorCount, pendingRequestCount, isHealthy()
            );
        }
    }

    // v3-fix: 修改點4 - 重置清理任務健康狀態（用於恢復後）
    public void resetCleanupHealth() {
        lastSuccessfulCleanupTime = System.currentTimeMillis();
        cleanupSuccessCount = 0;
        cleanupErrorCount = 0;
        LOGGER.info("Cleanup health stats reset");
    }

    /**
     * 啟動 Node.js sidecar 子行程
     *
     * @param nodeExecutable Node.js 執行檔路徑（null 表示用系統 PATH 的 node）
     * @param sidecarScript  sidecar 入口腳本絕對路徑
     */
    public synchronized void start(String nodeExecutable, Path sidecarScript) throws IOException {
        // v3-fix: 使用寫鎖保護狀態檢查和修改
        stateLock.writeLock().lock();
        try {
            if (running) {
                LOGGER.warn("SidecarBridge 已在執行中，忽略重複啟動");
                return;
            }

            String nodePath = (nodeExecutable != null) ? nodeExecutable : resolveNodeFromPath();
            LOGGER.info("啟動 Sidecar: {} {}", nodePath, sidecarScript);

            ProcessBuilder pb = new ProcessBuilder(
                    nodePath,
                    sidecarScript.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(false);                   // stderr 分離，避免污染 stdout JSON
            pb.environment().put("BLOCKREALITY_MODE", "sidecar");
            pb.environment().put("NODE_ENV", "production");

            nodeProcess = pb.start();

            // stderr → 轉發到 Log4j
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(nodeProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        LOGGER.warn("[Sidecar STDERR] {}", line);
                    }
                } catch (IOException e) {
                    // 子行程結束，忽略
                }
            }, "BR-Sidecar-Stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            writer = new PrintWriter(
                    new OutputStreamWriter(nodeProcess.getOutputStream(), StandardCharsets.UTF_8),
                    true  // autoFlush
            );
            reader = new BufferedReader(
                    new InputStreamReader(nodeProcess.getInputStream(), StandardCharsets.UTF_8)
            );

            running = true;

            // 非同步讀取回應的執行緒
            readerThread = new Thread(this::readLoop, "BR-Sidecar-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

            LOGGER.info("Sidecar 啟動成功（PID: {}）", nodeProcess.pid());
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /**
     * 便利多載：使用系統 PATH 中的 node，sidecar.js 在 mod config 目錄旁
     */
    public void start() throws IOException {
        Path defaultScript = FMLPaths.GAMEDIR.get()
                .resolve("blockreality")
                .resolve("sidecar")
                .resolve("dist")
                .resolve("sidecar.js");
        start(null, defaultScript);
    }

    /**
     * 發送 JSON-RPC 2.0 請求並等待回應
     *
     * @param method     RPC 方法名稱
     * @param params     參數（JsonObject）
     * @param timeoutMs  逾時毫秒數
     * @return result 欄位的 JsonObject（若 error 則拋 SidecarException）
     */
    public JsonObject call(String method, JsonObject params, long timeoutMs)
            throws SidecarException, InterruptedException {

        // v3-fix: 使用讀鎖保護 running 和 writer 的原子性檢查
        stateLock.readLock().lock();
        int id = -1;  // v3-fix: 在 try 外部聲明 id，以便 catch 塊中使用
        try {
            if (!running || writer == null) {
                throw new SidecarException("Sidecar 未啟動或 writer 尚未初始化");
            }

            id = rpcId.incrementAndGet();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            // v3-fix: 使用 PendingEntry 封裝，包含時間戳
            pending.put(id, new PendingEntry(future));

            // 組裝 JSON-RPC 2.0 請求
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("method", method);
            request.add("params", params);
            request.addProperty("id", id);

            // v3-fix: 改進 writeLock 使用，添加異常處理和錯誤檢查
            synchronized (writeSyncLock) {
                try {
                    String json = GSON.toJson(request);
                    writer.println(json);
                    writer.flush(); // v3-fix: 確保立即發送

                    // v3-fix: 檢查 writer 錯誤狀態
                    if (writer.checkError()) {
                        pending.remove(id);
                        throw new SidecarException("Writer encountered error");
                    }
                } catch (Exception e) {
                    pending.remove(id);
                    throw new SidecarException("Failed to send request", e);
                }
            }

            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // v3-fix: 使用已記錄的 id 清理 pending
            if (id != -1) pending.remove(id);
            throw new SidecarException("RPC 逾時: method=" + method + ", id=" + id);
        } catch (ExecutionException e) {
            if (id != -1) pending.remove(id);
            throw new SidecarException("RPC 執行失敗: " + e.getCause().getMessage(), e.getCause());
        } catch (Exception e) {
            if (id != -1) pending.remove(id);
            throw new SidecarException("RPC 呼叫失敗: " + e.getMessage(), e);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /** stdout 讀取迴圈（在獨立執行緒運行） */
    private void readLoop() {
        try {
            String line;
            // v2.0-fix：加入 Thread.interrupted() 檢查，配合 stop() 的 interrupt 優雅退出
            while (running && !Thread.currentThread().isInterrupted()
                   && (line = reader.readLine()) != null) {
                final String payload = line.trim();
                if (payload.isEmpty()) continue;

                JsonObject response;
                try {
                    response = GSON.fromJson(payload, JsonObject.class);
                } catch (Exception e) {
                    LOGGER.error("Sidecar 回傳非 JSON: {}", payload);
                    continue;
                }

                // 解析 id
                if (!response.has("id") || response.get("id").isJsonNull()) {
                    // 通知事件（notification），暫不處理
                    LOGGER.debug("[Sidecar Notification] {}", payload);
                    continue;
                }

                int id = response.get("id").getAsInt();
                // v3-fix: 從 PendingEntry 中獲取 future
                PendingEntry entry = pending.remove(id);
                if (entry == null) {
                    LOGGER.warn("收到未知 RPC id {} 的回應", id);
                    continue;
                }

                CompletableFuture<JsonObject> future = entry.future;
                if (response.has("error")) {
                    future.completeExceptionally(
                        new SidecarException("RPC error: " + response.get("error").toString())
                    );
                } else {
                    future.complete(response.has("result")
                            ? response.get("result").getAsJsonObject()
                            : new JsonObject());
                }
            }
        } catch (IOException e) {
            if (running) LOGGER.error("Sidecar 讀取中斷", e);
        } finally {
            // 通知所有等待中的 future 失敗
            // v3-fix: 從 PendingEntry 中獲取 future
            pending.forEach((id, entry) ->
                entry.future.completeExceptionally(new SidecarException("Sidecar 連線中斷"))
            );
            pending.clear();
        }
    }

    /** 停止 sidecar 子行程（v3-fix：修正資源清理順序，確保安全關閉） */
    public void stop() {
        // v3-fix: 使用寫鎖保護狀態修改
        stateLock.writeLock().lock();
        try {
            running = false;

            // v3-fix: 1. 先關閉 writer，讓對端知道不再發送
            if (writer != null) {
                // 傳送 shutdown 通知
                try {
                    JsonObject shutdown = new JsonObject();
                    shutdown.addProperty("jsonrpc", "2.0");
                    shutdown.addProperty("method", "shutdown");
                    shutdown.add("id", null);
                    writer.println(GSON.toJson(shutdown));
                    writer.flush();
                } catch (Exception e) {
                    LOGGER.warn("發送 shutdown 通知失敗", e);
                }
                writer.close();
                writer = null;
            }

            // v3-fix: 2. 中斷並等待 reader 執行緒
            if (readerThread != null) {
                readerThread.interrupt();
                try {
                    readerThread.join(5000);
                    // v3-fix: 檢查執行緒是否真正結束
                    if (readerThread.isAlive()) {
                        LOGGER.warn("Reader thread did not terminate gracefully");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // v3-fix: 3. 關閉 reader
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    LOGGER.error("Error closing reader", e);
                }
                reader = null;
            }

            // v3-fix: 4. 銷毀 process
            if (nodeProcess != null) {
                if (nodeProcess.isAlive()) {
                    nodeProcess.destroy();
                    try {
                        if (!nodeProcess.waitFor(5, TimeUnit.SECONDS)) {
                            nodeProcess.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        nodeProcess.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                }
                nodeProcess = null;
            }

            // v3-fix: 5. 清理 pending（從 PendingEntry 中獲取 future）
            pending.forEach((id, entry) ->
                entry.future.completeExceptionally(new SidecarException("Bridge stopped"))
            );
            pending.clear();

            // v3-fix: 6. 關閉清理執行器
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            LOGGER.info("Sidecar 已停止");
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /** 從系統 PATH 解析 node 執行檔 */
    private String resolveNodeFromPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "node.exe" : "node";
    }

    /** Sidecar 相關例外 */
    public static class SidecarException extends Exception {
        public SidecarException(String message) { super(message); }
        public SidecarException(String message, Throwable cause) { super(message, cause); }
    }
}
