package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 在主執行緒中以 ChunkSection 批次讀取方塊，建立唯讀快照。
 *
 * 關鍵效能設計：
 *   1. 以 chunk section 為單位遍歷，chunk 查找次數 = 跨越的 section 數（非方塊數）
 *   2. section.getBlockState(lx, ly, lz) 是 PalettedContainer 的陣列索引，O(1)
 *   3. hasOnlyAir() 提前跳過空 section，大幅減少無效遍歷
 *   4. 不強制加載 chunk (getChunkNow)，避免卡死主執行緒
 */
public class SnapshotBuilder {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Snapshot");

    /**
     * 擷取指定範圍的世界快照。
     * 必須在主執行緒 (Server Thread) 呼叫。
     *
     * @param level Minecraft ServerLevel
     * @param start 起點 (含)
     * @param end   終點 (含)
     * @return 不可變的 RWorldSnapshot
     */
    public static RWorldSnapshot capture(ServerLevel level, BlockPos start, BlockPos end) {
        long t0 = System.nanoTime();

        int minX = Math.min(start.getX(), end.getX());
        int minY = Math.min(start.getY(), end.getY());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxX = Math.max(start.getX(), end.getX());
        int maxY = Math.max(start.getY(), end.getY());
        int maxZ = Math.max(start.getZ(), end.getZ());

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        int totalBlocks = sizeX * sizeY * sizeZ;

        if (totalBlocks > RWorldSnapshot.MAX_SNAPSHOT_BLOCKS) {
            throw new IllegalArgumentException(
                String.format("Snapshot exceeds MAX_SNAPSHOT_BLOCKS (%d). Attempted: %dx%dx%d = %d",
                    RWorldSnapshot.MAX_SNAPSHOT_BLOCKS, sizeX, sizeY, sizeZ, totalBlocks)
            );
        }

        RBlockState[] blocks = new RBlockState[totalBlocks];
        int nonAirCount = 0;

        // ─── Section 批次遍歷 ───
        int minCX = minX >> 4, maxCX = maxX >> 4;
        int minCZ = minZ >> 4, maxCZ = maxZ >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                // 不強制加載 chunk，僅讀取已存在的
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;

                int minSectionY = Math.max(chunk.getMinBuildHeight() >> 4, minY >> 4);
                int maxSectionY = Math.min((chunk.getMaxBuildHeight() - 1) >> 4, maxY >> 4);

                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    int sectionIdx = chunk.getSectionIndex(sy << 4);
                    if (sectionIdx < 0 || sectionIdx >= chunk.getSectionsCount()) continue;

                    LevelChunkSection section = chunk.getSection(sectionIdx);
                    if (section == null || section.hasOnlyAir()) continue;

                    // 計算 section 與 bounding box 的交集
                    int secMinX = Math.max(minX, cx << 4);
                    int secMaxX = Math.min(maxX, (cx << 4) + 15);
                    int secMinY = Math.max(minY, sy << 4);
                    int secMaxY = Math.min(maxY, (sy << 4) + 15);
                    int secMinZ = Math.max(minZ, cz << 4);
                    int secMaxZ = Math.min(maxZ, (cz << 4) + 15);

                    for (int y = secMinY; y <= secMaxY; y++) {
                        for (int z = secMinZ; z <= secMaxZ; z++) {
                            for (int x = secMinX; x <= secMaxX; x++) {
                                // O(1) PalettedContainer 讀取
                                BlockState mcState = section.getBlockState(x & 15, y & 15, z & 15);

                                if (!mcState.isAir()) {
                                    RBlockState rState = translate(mcState);
                                    // 索引: lx + sizeX * (ly + sizeY * lz)
                                    int idx = (x - minX) + sizeX * ((y - minY) + sizeY * (z - minZ));
                                    blocks[idx] = rState;
                                    nonAirCount++;
                                }
                                // air → blocks[idx] 保持 null，getBlock() 會回傳 RBlockState.AIR
                            }
                        }
                    }
                }
            }
        }

        long elapsed = System.nanoTime() - t0;

        LOGGER.debug("Snapshot captured: {}x{}x{} ({} blocks, {} non-air) in {:.2f}ms",
            sizeX, sizeY, sizeZ, totalBlocks, nonAirCount, elapsed / 1_000_000.0);

        return new RWorldSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, blocks, elapsed);
    }

    // ─── BlockState → RBlockState 轉譯 ───

    /**
     * 將 Minecraft BlockState 轉換為物理引擎用的 RBlockState。
     * TODO: 後續接上 Material Registry，從 JSON/config 載入材料參數
     */
    private static RBlockState translate(BlockState mcState) {
        String blockId = BuiltInRegistries.BLOCK.getKey(mcState.getBlock()).toString();

        // 錨定判定：基岩、屏障等不可破壞方塊
        boolean isAnchor = mcState.is(Blocks.BEDROCK) || mcState.is(Blocks.BARRIER);

        // 預設物理參數（石材級別）— 後續由 Material Registry 覆蓋
        float mass = 2400f;               // kg/m³ (石頭密度)
        float compressive = 30f;          // MPa
        float tensile = 3f;               // MPa

        // 依材質分類給予不同參數
        if (mcState.is(Blocks.BEDROCK) || mcState.is(Blocks.BARRIER)) {
            mass = Float.MAX_VALUE;
            compressive = Float.MAX_VALUE;
            tensile = Float.MAX_VALUE;
        } else if (mcState.is(Blocks.IRON_BLOCK)) {
            mass = 7870f; compressive = 250f; tensile = 400f;
        } else if (mcState.is(Blocks.OAK_PLANKS) || mcState.is(Blocks.OAK_LOG)
                || mcState.is(Blocks.SPRUCE_PLANKS) || mcState.is(Blocks.BIRCH_PLANKS)) {
            mass = 600f; compressive = 5f; tensile = 10f;
        } else if (mcState.is(Blocks.GLASS) || mcState.is(Blocks.GLASS_PANE)) {
            mass = 2500f; compressive = 100f; tensile = 0.5f;
        } else if (mcState.is(Blocks.SAND) || mcState.is(Blocks.GRAVEL)) {
            mass = 1600f; compressive = 0.1f; tensile = 0f;
        } else if (mcState.is(Blocks.OBSIDIAN)) {
            mass = 2600f; compressive = 200f; tensile = 5f;
        }

        return new RBlockState(blockId, mass, compressive, tensile, isAnchor);
    }
}
