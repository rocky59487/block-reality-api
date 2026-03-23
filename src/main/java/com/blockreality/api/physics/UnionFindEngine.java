package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * BFS 連通塊引擎 — 從 Anchor 擴散，找出所有失去支撐的懸空方塊。
 *
 * 效能設計：
 *   1. 零 GC：使用 BitSet (visited/supported) + int[] queue，不配置 BlockPos 物件
 *   2. 1D index 運算：直接在 snapshot 的扁平陣列索引上操作
 *   3. 雙煞車：bfs_max_blocks (65536) + bfs_max_ms (50ms)
 *
 * 不持有任何 net.minecraft.world.level.Level 參照。
 */
public class UnionFindEngine {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Physics");

    /** BFS 遍歷上限（等於快照最大方塊數） */
    private static final int BFS_MAX_BLOCKS = RWorldSnapshot.MAX_SNAPSHOT_BLOCKS;

    /** BFS 時間煞車 (ms) */
    private static final long BFS_MAX_MS = 50;

    /** 六方向偏移 (dx, dy, dz) — 零物件配置 */
    private static final int[] DX = { 1, -1, 0, 0, 0, 0 };
    private static final int[] DY = { 0, 0, 1, -1, 0, 0 };
    private static final int[] DZ = { 0, 0, 0, 0, 1, -1 };

    /**
     * 找出快照中所有失去支撐（未連接到 Anchor）的非空氣方塊。
     *
     * @param snapshot 唯讀世界快照（絕對不可傳入 Level）
     * @return 懸空方塊的絕對座標集合
     */
    public static PhysicsResult findUnsupportedBlocks(RWorldSnapshot snapshot) {
        long t0 = System.nanoTime();

        int sizeX = snapshot.getSizeX();
        int sizeY = snapshot.getSizeY();
        int sizeZ = snapshot.getSizeZ();
        int total = sizeX * sizeY * sizeZ;

        // ─── Phase 1: 掃描所有非空氣方塊 & Anchor ───
        BitSet nonAir = new BitSet(total);
        int[] anchorQueue = new int[total]; // 預分配最大容量，避免 resize
        int anchorCount = 0;
        int nonAirCount = 0;

        int sx = snapshot.getStartX();
        int sy = snapshot.getStartY();
        int sz = snapshot.getStartZ();

        for (int lz = 0; lz < sizeZ; lz++) {
            for (int ly = 0; ly < sizeY; ly++) {
                for (int lx = 0; lx < sizeX; lx++) {
                    int idx = lx + sizeX * (ly + sizeY * lz);
                    RBlockState state = snapshot.getBlock(sx + lx, sy + ly, sz + lz);

                    if (state != RBlockState.AIR && state.mass() > 0) {
                        nonAir.set(idx);
                        nonAirCount++;

                        if (state.isAnchor()) {
                            anchorQueue[anchorCount++] = idx;
                        }
                    }
                }
            }
        }

        // ─── Phase 2: BFS 從 Anchor 擴散 ───
        BitSet supported = new BitSet(total);

        // 用 int[] 作為環形佇列（circular buffer），零 GC
        int[] queue = new int[total];
        int head = 0, tail = 0;

        // 初始化：所有 anchor 放入佇列
        for (int i = 0; i < anchorCount; i++) {
            int idx = anchorQueue[i];
            supported.set(idx);
            queue[tail++] = idx;
        }

        long deadline = System.currentTimeMillis() + BFS_MAX_MS;
        int visitCount = 0;
        boolean timedOut = false;
        boolean exceededMax = false;

        while (head < tail) {
            // ─── 煞車檢查 ───
            if (visitCount >= BFS_MAX_BLOCKS) {
                exceededMax = true;
                LOGGER.warn("BFS hit max block limit ({}), aborting", BFS_MAX_BLOCKS);
                break;
            }
            if ((visitCount & 0xFF) == 0 && System.currentTimeMillis() > deadline) {
                // 每 256 次才檢查時間，減少 syscall 開銷
                timedOut = true;
                LOGGER.warn("BFS timed out after {}ms, visited {} blocks", BFS_MAX_MS, visitCount);
                break;
            }

            int idx = queue[head++];
            visitCount++;

            // 解碼 1D → 3D 本地座標
            int lx = idx % sizeX;
            int ly = (idx / sizeX) % sizeY;
            int lz = idx / (sizeX * sizeY);

            // 六方向鄰居
            for (int d = 0; d < 6; d++) {
                int nx = lx + DX[d];
                int ny = ly + DY[d];
                int nz = lz + DZ[d];

                // 邊界檢查
                if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) {
                    continue;
                }

                int nIdx = nx + sizeX * (ny + sizeY * nz);

                // 是非空氣 && 尚未標記
                if (nonAir.get(nIdx) && !supported.get(nIdx)) {
                    supported.set(nIdx);
                    queue[tail++] = nIdx;
                }
            }
        }

        // ─── Phase 3: 比對 — 非空氣 && 未支撐 = 懸空 ───
        Set<BlockPos> unsupported = new HashSet<>();
        // 用 BitSet 的 XOR 找差異
        BitSet floating = (BitSet) nonAir.clone();
        floating.andNot(supported);

        for (int idx = floating.nextSetBit(0); idx >= 0; idx = floating.nextSetBit(idx + 1)) {
            int lx = idx % sizeX;
            int ly = (idx / sizeX) % sizeY;
            int lz = idx / (sizeX * sizeY);
            unsupported.add(new BlockPos(sx + lx, sy + ly, sz + lz));
        }

        long elapsed = System.nanoTime() - t0;

        return new PhysicsResult(
            unsupported,
            nonAirCount,
            anchorCount,
            visitCount,
            elapsed,
            timedOut,
            exceededMax
        );
    }

    /**
     * 物理運算結果
     */
    public record PhysicsResult(
        Set<BlockPos> unsupportedBlocks,
        int totalNonAir,
        int anchorCount,
        int bfsVisited,
        long computeTimeNs,
        boolean timedOut,
        boolean exceededMaxBlocks
    ) {
        public double computeTimeMs() {
            return computeTimeNs / 1_000_000.0;
        }

        public int unsupportedCount() {
            return unsupportedBlocks.size();
        }

        @Override
        public String toString() {
            return String.format(
                "PhysicsResult{unsupported=%d, nonAir=%d, anchors=%d, bfsVisited=%d, time=%.2fms, timeout=%b}",
                unsupportedCount(), totalNonAir, anchorCount, bfsVisited, computeTimeMs(), timedOut
            );
        }
    }
}
