package com.blockreality.api.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Block Reality 配置系統 (ForgeConfigSpec)。
 *
 * 配置檔會自動生成在 config/blockreality-common.toml
 * 支援遊戲內 /config 指令查看。
 *
 * 參數分類：
 *   1. RC Fusion — 鋼筋混凝土融合相關
 *   2. Physics Engine — BFS/SPH 引擎限制
 *   3. Snapshot — 快照範圍限制
 */
public class BRConfig {

    public static final ForgeConfigSpec SPEC;
    public static final BRConfig INSTANCE;

    // ─── RC Fusion 參數 ───

    /** RC 融合：抗拉強度增幅係數 (φ_tens) */
    public final ForgeConfigSpec.DoubleValue rcFusionPhiTens;

    /** RC 融合：抗剪強度增幅係數 (φ_shear) */
    public final ForgeConfigSpec.DoubleValue rcFusionPhiShear;

    /** RC 融合：抗壓強度增幅比例 */
    public final ForgeConfigSpec.DoubleValue rcFusionCompBoost;

    /** RC 融合：鋼筋最大間距 (格數) */
    public final ForgeConfigSpec.IntValue rcFusionRebarSpacingMax;

    /** RC 融合：蜂窩空洞機率 (品質控制) */
    public final ForgeConfigSpec.DoubleValue rcFusionHoneycombProb;

    /** RC 融合：養護時間 (ticks, 2400 = 2分鐘) */
    public final ForgeConfigSpec.IntValue rcFusionCuringTicks;

    // ─── Physics Engine 參數 ───

    /** SPH 異步觸發半徑 (格數) */
    public final ForgeConfigSpec.IntValue sphAsyncTriggerRadius;

    /** SPH 最大粒子數 */
    public final ForgeConfigSpec.IntValue sphMaxParticles;

    /** Anchor BFS 最大搜索深度 */
    public final ForgeConfigSpec.IntValue anchorBfsMaxDepth;

    // ─── Structure Engine 參數 ───

    /** 結構 BFS 最大方塊數 */
    public final ForgeConfigSpec.IntValue structureBfsMaxBlocks;

    /** 結構 BFS 最大執行時間 (ms) */
    public final ForgeConfigSpec.IntValue structureBfsMaxMs;

    /** 快照最大半徑 (格數) */
    public final ForgeConfigSpec.IntValue snapshotMaxRadius;

    /** 掃描邊距 (Scan Margin) 預設格數 */
    public final ForgeConfigSpec.IntValue scanMarginDefault;

    /** ★ T-3: 環路偵測最大追溯深度（LoadPathEngine.wouldCreateCycle） */
    public final ForgeConfigSpec.IntValue cycleDetectMaxDepth;

    /** ★ v3fix: 啟用 ForceEquilibriumSolver 作為備選分析方法（預設關閉） */
    public final ForgeConfigSpec.BooleanValue useForceEquilibrium;

    // ─── Phase 2: 並行物理引擎參數 ───

    /** ★ Phase 2: 物理執行緒數（0 = 自動，使用 availableProcessors - 2） */
    public final ForgeConfigSpec.IntValue physicsThreadCount;

    /** ★ Phase 1: 快照最大方塊數上限（突破 40³ 限制） */
    public final ForgeConfigSpec.IntValue maxSnapshotBlocks;

    // ─── Phase 4: LOD 物理參數 ───

    /** ★ Phase 4: 完整精度物理的最大距離（格） */
    public final ForgeConfigSpec.IntValue lodFullPrecisionDistance;

    /** ★ Phase 4: 標準精度物理的最大距離（格） */
    public final ForgeConfigSpec.IntValue lodStandardDistance;

    /** ★ Phase 4: 粗略精度物理的最大距離（格） */
    public final ForgeConfigSpec.IntValue lodCoarseDistance;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        INSTANCE = new BRConfig(builder);
        SPEC = builder.build();
    }

    private BRConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Block Reality API Configuration")
               .push("rc_fusion");

        rcFusionPhiTens = builder
            .comment("RC fusion tensile strength coefficient (φ_tens)")
            .defineInRange("phi_tens", 0.8, 0.0, 2.0);

        rcFusionPhiShear = builder
            .comment("RC fusion shear strength coefficient (φ_shear)")
            .defineInRange("phi_shear", 0.6, 0.0, 2.0);

        rcFusionCompBoost = builder
            .comment("RC fusion compressive strength boost ratio")
            .defineInRange("comp_boost", 1.1, 1.0, 3.0);

        rcFusionRebarSpacingMax = builder
            .comment("Maximum rebar spacing for RC fusion (blocks)")
            .defineInRange("rebar_spacing_max", 3, 1, 8);

        rcFusionHoneycombProb = builder
            .comment("Probability of honeycomb void in RC fusion (quality control)")
            .defineInRange("honeycomb_prob", 0.15, 0.0, 1.0);

        rcFusionCuringTicks = builder
            .comment("RC curing time in ticks (2400 = 2 minutes)")
            .defineInRange("curing_ticks", 2400, 0, 72000);

        builder.pop().push("physics_engine");

        sphAsyncTriggerRadius = builder
            .comment("SPH async trigger radius (blocks)")
            .defineInRange("sph_trigger_radius", 5, 1, 32);

        sphMaxParticles = builder
            .comment("SPH maximum particle count")
            .defineInRange("sph_max_particles", 200, 10, 2000);

        anchorBfsMaxDepth = builder
            .comment("Anchor BFS maximum search depth")
            .defineInRange("anchor_bfs_max_depth", 64, 8, 512);

        builder.pop().push("structure_engine");

        structureBfsMaxBlocks = builder
            .comment("Structure BFS maximum block count. W-8 fix: raised from 512 to 2048 to handle larger RBlock structures without false NO_SUPPORT.")
            .defineInRange("bfs_max_blocks", 2048, 64, 262144);

        structureBfsMaxMs = builder
            .comment("Structure BFS maximum execution time in ms. W-8 fix: raised from 15 to 50ms for SupportPathAnalyzer weighted BFS.")
            .defineInRange("bfs_max_ms", 50, 5, 500);

        snapshotMaxRadius = builder
            .comment("Snapshot maximum radius (blocks)")
            .defineInRange("snapshot_max_radius", 20, 4, 40);

        scanMarginDefault = builder
            .comment("Default scan margin for physics analysis (blocks)")
            .defineInRange("scan_margin_default", 4, 0, 16);

        cycleDetectMaxDepth = builder
            .comment("T-3: Max parent chain depth for cycle detection in support tree (default 8)")
            .defineInRange("cycle_detect_max_depth", 8, 2, 64);

        useForceEquilibrium = builder
            .comment("v3fix: Enable ForceEquilibriumSolver as alternative physics analysis (experimental, default false)")
            .define("use_force_equilibrium", false);

        builder.pop().push("performance");

        physicsThreadCount = builder
            .comment("Phase 2: Physics thread count. 0 = auto (availableProcessors - 2). Range: 0-8.")
            .defineInRange("physics_thread_count", 0, 0, 8);

        maxSnapshotBlocks = builder
            .comment("Phase 1: Maximum snapshot blocks. Raised from 65536 to support multi-chunk structures.")
            .defineInRange("max_snapshot_blocks", 262144, 65536, 1048576);

        lodFullPrecisionDistance = builder
            .comment("Phase 4: Maximum distance (blocks) for full precision physics (BeamStress + ForceEquilibrium)")
            .defineInRange("lod_full_precision_distance", 32, 8, 128);

        lodStandardDistance = builder
            .comment("Phase 4: Maximum distance (blocks) for standard precision physics (SupportPathAnalyzer)")
            .defineInRange("lod_standard_distance", 96, 32, 256);

        lodCoarseDistance = builder
            .comment("Phase 4: Maximum distance (blocks) for coarse physics (LoadPathEngine only)")
            .defineInRange("lod_coarse_distance", 256, 96, 512);

        builder.pop();
    }
}
