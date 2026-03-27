package com.blockreality.api.physics;

/**
 * 共用物理常數 — Block Reality 全域統一。
 *
 * ★ review-fix #12: 將散落在 CableNode、ForceEquilibriumSolver 等處的
 * 硬編碼常數提取到單一位置，避免日後調整時遺漏。
 */
public final class PhysicsConstants {

    private PhysicsConstants() {} // 不可實例化

    /** 重力加速度 (m/s²) — Minecraft 的 1 格 = 1 米 */
    public static final double GRAVITY = 9.81;

    /** 方塊截面積 (m²) — Minecraft 方塊 1m × 1m */
    public static final double BLOCK_AREA = 1.0;

    /** 完整方塊截面慣性矩 (m⁴) — 1m × 1m 正方形: I = b⁴/12 */
    public static fi