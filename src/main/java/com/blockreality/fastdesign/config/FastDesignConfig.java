package com.blockreality.fastdesign.config;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/**
 * FastDesign configuration for Forge 1.20.1
 * Exposes commonly-used hardcoded values as configurable options.
 */
@Mod.EventBusSubscriber(modid = "fastdesign", bus = Mod.EventBusSubscriber.Bus.MOD)
public class FastDesignConfig {

    // ============================================================================
    // COMMON CONFIG (Server + Client synced)
    // ============================================================================

    public static final ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec COMMON_SPEC;

    // Selection & Undo
    public static ForgeConfigSpec.IntValue MAX_SELECTION_VOLUME;
    public static ForgeConfigSpec.IntValue UNDO_STACK_SIZE;

    // Export
    public static ForgeConfigSpec.IntValue EXPORT_MAX_BLOCKS;
    public static ForgeConfigSpec.IntValue EXPORT_TIMEOUT_SECONDS;

    // Rebar
    public static ForgeConfigSpec.IntValue REBAR_SPACING_MAX;

    // Hologram
    public static ForgeConfigSpec.IntValue HOLOGRAM_MAX_BLOCKS;
    public static ForgeConfigSpec.DoubleValue HOLOGRAM_GHOST_ALPHA;
    public static ForgeConfigSpec.DoubleValue HOLOGRAM_CULL_DISTANCE;

    // Features
    public static ForgeConfigSpec.BooleanValue WAND_ENABLED;
    public static ForgeConfigSpec.BooleanValue CAD_AUTO_OPEN;
    public static ForgeConfigSpec.BooleanValue ALWAYS_SHOW_SELECTION;

    static {
        // ==== Selection & Undo ====
        COMMON_BUILDER.comment("Selection and Undo Settings")
                .push("selection");

        MAX_SELECTION_VOLUME = COMMON_BUILDER
                .comment("Maximum number of blocks allowed in a single selection")
                .defineInRange("maxSelectionVolume", 125_000, 1, 1_000_000);

        UNDO_STACK_SIZE = COMMON_BUILDER
                .comment("Maximum undo depth per player")
                .defineInRange("undoStackSize", 10, 1, 50);

        COMMON_BUILDER.pop();

        // ==== Export ====
        COMMON_BUILDER.comment("Export Settings")
                .push("export");

        EXPORT_MAX_BLOCKS = COMMON_BUILDER
                .comment("Maximum number of blocks allowed in NURBS export")
                .defineInRange("exportMaxBlocks", 5000, 100, 50_000);

        EXPORT_TIMEOUT_SECONDS = COMMON_BUILDER
                .comment("Timeout in seconds for sidecar export operations")
                .defineInRange("exportTimeoutSeconds", 30, 5, 300);

        COMMON_BUILDER.pop();

        // ==== Rebar ====
        COMMON_BUILDER.comment("Rebar Settings")
                .push("rebar");

        REBAR_SPACING_MAX = COMMON_BUILDER
                .comment("Maximum rebar grid spacing")
                .defineInRange("rebarSpacingMax", 8, 1, 16);

        COMMON_BUILDER.pop();

        // ==== Hologram ====
        COMMON_BUILDER.comment("Hologram Settings")
                .push("hologram");

        HOLOGRAM_MAX_BLOCKS = COMMON_BUILDER
                .comment("Maximum number of blocks rendered in hologram displays")
                .defineInRange("hologramMaxBlocks", 10_000, 100, 100_000);

        HOLOGRAM_GHOST_ALPHA = COMMON_BUILDER
                .comment("Transparency level for hologram ghost rendering (0.1 = very transparent, 1.0 = opaque)")
                .defineInRange("hologramGhostAlpha", 0.4, 0.1, 1.0);

        HOLOGRAM_CULL_DISTANCE = COMMON_BUILDER
                .comment("Distance in blocks at which holograms stop rendering (culling distance)")
                .defineInRange("hologramCullDistance", 128.0, 16.0, 512.0);

        COMMON_BUILDER.pop();

        // ==== Features ====
        COMMON_BUILDER.comment("Feature Toggles")
                .push("features");

        WAND_ENABLED = COMMON_BUILDER
                .comment("Enable the selection wand item")
                .define("wandEnabled", true);

        CAD_AUTO_OPEN = COMMON_BUILDER
                .comment("Automatically open CAD UI after loading a blueprint")
                .define("cadAutoOpen", false);

        ALWAYS_SHOW_SELECTION = COMMON_BUILDER
                .comment("Always show selection bounding box, even when not holding wand")
                .define("alwaysShowSelection", false);

        COMMON_BUILDER.pop();

        COMMON_SPEC = COMMON_BUILDER.build();
    }

    // ============================================================================
    // STATIC GETTERS
    // ============================================================================

    public static int getMaxSelectionVolume() {
        return MAX_SELECTION_VOLUME.get();
    }

    public static int getUndoStackSize() {
        return UNDO_STACK_SIZE.get();
    }

    public static int getExportMaxBlocks() {
        return EXPORT_MAX_BLOCKS.get();
    }

    public static int getExportTimeoutSeconds() {
        return EXPORT_TIMEOUT_SECONDS.get();
    }

    public static int getRebarSpacingMax() {
        return REBAR_SPACING_MAX.get();
    }

    public static int getHologramMaxBlocks() {
        return HOLOGRAM_MAX_BLOCKS.get();
    }

    public static double getHologramGhostAlpha() {
        return HOLOGRAM_GHOST_ALPHA.get();
    }

    public static double getHologramCullDistance() {
        return HOLOGRAM_CULL_DISTANCE.get();
    }

    public static boolean isWandEnabled() {
        return WAND_ENABLED.get();
    }

    public static boolean isCadAutoOpen() {
        return CAD_AUTO_OPEN.get();
    }

    public static boolean isAlwaysShowSelection() {
        return ALWAYS_SHOW_SELECTION.get();
    }
}
