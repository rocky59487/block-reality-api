package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;

/**
 * 唯讀的世界快照容器。
 * 使用 1D 陣列扁平化 3D 空間，保證 cache-friendly 存取。
 *
 * 索引公式: lx + sizeX * (ly + sizeY * lz)
 * → Y 軸連續排列，物理引擎沿柱狀 (column) 掃描時 cache 命中率最高。
 *
 * 不持有任何 net.minecraft.world.level.Level 參照。
 */
public class RWorldSnapshot {

    /** 安全煞車：最大快照方塊數 (≈ 40×40×40)，超過直接拒絕，防範 OOM */
    public static final int MAX_SNAPSHOT_BLOCKS = 65536;

    private final int startX, startY, startZ;
    private final int sizeX, sizeY, sizeZ;
    private final RBlockState[] blocks;
    private final long captureTimeNs;

    public RWorldSnapshot(BlockPos start, BlockPos end) {
        this.startX = Math.min(start.getX(), end.getX());
        this.startY = Math.min(start.getY(), end.getY());
        this.startZ = Math.min(start.getZ(), end.getZ());

        this.sizeX = Math.abs(end.getX() - start.getX()) + 1;
        this.sizeY = Math.abs(end.getY() - start.getY()) + 1;
        this.sizeZ = Math.abs(end.getZ() - start.getZ()) + 1;

        int totalBlocks = sizeX * sizeY * sizeZ;
        if (totalBlocks > MAX_SNAPSHOT_BLOCKS) {
            throw new IllegalArgumentException(
                String.format("Snapshot exceeds MAX_SNAPSHOT_BLOCKS (%d). Attempted: %dx%dx%d = %d",
                    MAX_SNAPSHOT_BLOCKS, sizeX, sizeY, sizeZ, totalBlocks)
            );
        }

        this.blocks = new RBlockState[totalBlocks];
        this.captureTimeNs = 0;
    }

    /** 內部建構子，由 SnapshotBuilder 使用（帶計時） */
    RWorldSnapshot(int startX, int startY, int startZ,
                   int sizeX, int sizeY, int sizeZ,
                   RBlockState[] blocks, long captureTimeNs) {
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = blocks;
        this.captureTimeNs = captureTimeNs;
    }

    /**
     * 1D 索引：lx + sizeX * (ly + sizeY * lz)
     * Y 連續 → 柱狀掃描 cache 友好
     */
    private int getIndex(int x, int y, int z) {
        return (x - startX) + sizeX * ((y - startY) + sizeY * (z - startZ));
    }

    public void setBlock(int x, int y, int z, RBlockState state) {
        if (isWithinBounds(x, y, z)) {
            blocks[getIndex(x, y, z)] = state;
        }
    }

    public RBlockState getBlock(int x, int y, int z) {
        if (!isWithinBounds(x, y, z)) return RBlockState.AIR;
        RBlockState state = blocks[getIndex(x, y, z)];
        return state != null ? state : RBlockState.AIR;
    }

    public boolean isWithinBounds(int x, int y, int z) {
        return x >= startX && x < startX + sizeX &&
               y >= startY && y < startY + sizeY &&
               z >= startZ && z < startZ + sizeZ;
    }

    // ─── Getters ───

    public int getStartX() { return startX; }
    public int getStartY() { return startY; }
    public int getStartZ() { return startZ; }
    public int getSizeX()  { return sizeX; }
    public int getSizeY()  { return sizeY; }
    public int getSizeZ()  { return sizeZ; }
    public int getTotalBlocks() { return sizeX * sizeY * sizeZ; }

    /** 快照擷取耗時 (奈秒) */
    public long getCaptureTimeNs() { return captureTimeNs; }

    /** 快照擷取耗時 (毫秒) */
    public double getCaptureTimeMs() { return captureTimeNs / 1_000_000.0; }
}
