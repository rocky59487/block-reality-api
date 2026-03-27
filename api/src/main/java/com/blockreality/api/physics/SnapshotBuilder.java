package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.material.VanillaMaterialMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
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

        int effectiveMax = RWorldSnapshot.getMaxSnapshotBlocks();
        if (totalBlocks > effectiveMax) {
            throw new IllegalArgumentException(
                String.format("Snapshot exceeds max_snapshot_blocks (%d). Attempted: %dx%dx%d = %d",
                    effectiveMax, sizeX, sizeY, sizeZ, totalBlocks)
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
                                    // ★ T-4 fix: 嘗試讀取 RBlockEntity 以取得精確材料參數
                                    // chunk.getBlockEntity() 是 HashMap 查找 O(1)，不會加載 chunk
                                    BlockEntity be = chunk.getBlockEntity(new BlockPos(x, y, z),
                                        LevelChunk.EntityCreationType.CHECK);
                                    RBlockState rState = (be != null)
                                        ? translateWithEntity(mcState, be)
                                        : translate(mcState);
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

        LOGGER.debug("Snapshot captured: {}x{}x{} ({} blocks, {} non-air) in {}ms",
            sizeX, sizeY, sizeZ, totalBlocks, nonAirCount,
            String.format("%.2f", elapsed / 1_000_000.0));

        return new RWorldSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, blocks, elapsed);
    }

    // ─── BlockState → RBlockState 轉譯 ───

    /**
     * 將 Minecraft BlockState 轉換為物理引擎用的 RBlockState。
     *
     * 優先讀取 RBlockEntity 的精確材料參數（R-unit system），
     * 若非 RBlock 則 fallback 到原版方塊的預設材料映射。
     */
    private static RBlockState translate(BlockState mcState) {
        String blockId = BuiltInRegistries.BLOCK.getKey(mcState.getBlock()).toString();

        // 錨定判定：基岩、屏障等不可破壞方塊
        boolean isAnchor = mcState.is(Blocks.BEDROCK) || mcState.is(Blocks.BARRIER);

        // 使用 DefaultMaterial 映射原版方塊
        RMaterial mat = mapVanillaBlock(mcState);

        return new RBlockState(
            blockId,
            (float) mat.getDensity(),
            (float) mat.getRcomp(),
            (float) mat.getRtens(),
            isAnchor
        );
    }

    /**
     * 帶 BlockEntity 感知的轉譯 — 用於精確快照。
     * 若方塊有 RBlockEntity，直接使用其材料參數（最精確）。
     */
    static RBlockState translateWithEntity(BlockState mcState, BlockEntity be) {
        if (be instanceof RBlockEntity rbe) {
            RMaterial mat = rbe.getMaterial();
            ChiselState cs = rbe.getChiselState();
            // ★ audit-fix M-6: 傳遞 X 和 Y 軸的截面屬性
            return new RBlockState(
                mat.getMaterialId(),
                (float) (mat.getDensity() * cs.fillRatio()),  // 質量按填充率縮放
                (float) mat.getRcomp(),
                (float) mat.getRtens(),
                rbe.isAnchored(),
                (float) cs.crossSectionArea(),
                (float) cs.momentOfInertiaX(),
                (float) cs.sectionModulusX(),
                (float) cs.momentOfInertiaY(),
                (float) cs.sectionModulusY()
            );
        }
        return translate(mcState);
    }

    /**
     * 原版方塊 → DefaultMaterial 映射。
     *
     * 使用 VanillaMaterialMap（JSON 數據驅動），覆蓋 100+ 原版方塊。
     * 未列入映射表的方塊 fallback 到 STONE。
     */
    private static RMaterial mapVanillaBlock(BlockState mcState) {
        String blockId = BuiltInRegistries.BLOCK.getKey(mcState.getBlock()).toString();
        return VanillaMaterialMap.getInstance().getMaterial(blockId);
    }

    /**
     * 以中心點周圍 radius=2 的鄰域擷取快照（26 個鄰域方塊）。
     * 用於局部物理分析的上下文感知。
     *
     * 標準立方體鄰域�