package com.blockreality.api.physics;

/**
 * 物理引擎專用的唯讀方塊狀態。
 * 與 Forge BlockState 完全解耦 — 不持有任何 net.minecraft 參照。
 *
 * @param blockId             方塊 ID (如 "minecraft:stone")
 * @param mass                質量 (kg/m³ 密度近似)
 * @param compressiveStrength 抗壓強度 (MPa)
 * @param tensileStrength     抗拉強度 (MPa)
 * @param isAnchor            是否為錨定點 (基岩、屏障等不可破壞的支撐)
 */
public record RBlockState(
    String blockId,
    float mass,
    float compressiveStrength,
    float tensileStrength,
    boolean isAnchor
) {
    public static final RBlockState AIR = new RBlockState("minecraft:air", 0f, 0f, 0f, false);
}
