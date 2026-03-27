package com.blockreality.fastdesign.client;

import com.blockreality.api.client.GhostBlockRenderer;
import com.blockreality.api.placement.BuildMode;
import com.blockreality.api.placement.MultiBlockCalculator;
import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.build.BuildModeState;
import com.blockreality.fastdesign.network.FdActionPacket;
import com.blockreality.fastdesign.network.FdNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Fast Design 快捷鍵系統 — Level 3 + v2.0 Pie Menu
 *
 * G 鍵: 開啟 Control Panel (保留向下相容)
 * V 鍵: 切換建造模式 (Normal → Line → Wall → Cube → Mirror X → Mirror Z)
 * Alt 鍵: 開啟 Pie Menu 快捷輪盤 (v2.0 新增)
 * X 鍵: 取消選取 (v2.0 新增)
 * ↑/↓ 鍵: 上下平移選取區域 (v2.0 新增)
 * H 鍵(按住)+準心: 拖動選取邊界調整大小 (v2.0 新增)
 * Ctrl+V: 設定鏡像錨點 (Mirror 模式下)
 */
public class FdKeyBindings {

    public static final KeyMapping OPEN_PANEL = new KeyMapping(
        "key.fastdesign.open_panel",
        InputConstants.KEY_G,
        "key.categories.fastdesign"
    );

    public static final KeyMapping CYCLE_BUILD_MODE = new KeyMapping(
        "key.fastdesign.cycle_build_mode",
        InputConstants.KEY_V,
        "key.categories.fastdesign"
    );

    public static final KeyMapping OPEN_PIE_MENU = new KeyMapping(
        "key.fastdesign.pie_menu",
        InputConstants.KEY_LALT,
        "key.categories.fastdesign"
    );

    public static final KeyMapping DESELECT = new KeyMapping(
        "key.fastdesign.deselect",
        InputConstants.KEY_X,
        "key.categories.fastdesign"
    );

    public static final KeyMapping SHIFT_UP = new KeyMapping(
        "key.fastdesign.shift_up",
        InputConstants.KEY_UP,
        "key.categories.fastdesign"
    );

    public static final KeyMapping SHIFT_DOWN = new KeyMapping(
        "key.fastdesign.shift_down",
        InputConstants.KEY_DOWN,
        "key.categories.fastdesign"
    );

    public static final KeyMapping MOVE_SELECTION = new KeyMapping(
        "key.fastdesign.move_selection",
        InputConstants.KEY_H,
        "key.categories.fastdesign"
    );

    /**
     * MOD 事件匯流排 — 註冊快捷鍵
     */
    @Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModEvents {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_PANEL);
            event.register(CYCLE_BUILD_MODE);
            event.register(OPEN_PIE_MENU);
            event.register(DESELECT);
            event.register(SHIFT_UP);
            event.register(SHIFT_DOWN);
            event.register(MOVE_SELECTION);
        }
    }

    /**
     * FORGE 事件匯流排 — 監聽按鍵觸發 + 幽靈方塊預覽更新 + Hologram 渲染
     *
     * 注意：HologramRenderer 原本在 api/ClientSetup 中被硬引用，
     * api/mod 分離後改由 fastdesign 自行在此掛接。
     */
    @Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeEvents {

        /** H鍵拖動邊界：記錄抓取的面和上次發送的值，避免重複封包 */
        private static String grabbedFace = null;
        private static int lastSentValue = Integer.MIN_VALUE;

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            HologramRenderer.onRenderLevelStage(event);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            Minecraft mc = Minecraft.getInstance();

            // ─── G 鍵: 開啟控制面板 ───
            while (OPEN_PANEL.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new ControlPanelScreen());
                }
            }

            // ─── V 鍵: 切換建造模式 ───
            while (CYCLE_BUILD_MODE.consumeClick()) {
                if (mc.screen == null && mc.player != null) {
                    BuildMode newMode = BuildModeState.cycleMode();
                    mc.player.displayClientMessage(
                        Component.literal("§6[FD] §f建造模式: §a" +
                            newMode.getDisplayName() + " §7(" + newMode.getDescription() + ")"),
                        true
                    );
                    GhostBlockRenderer.clearPreview();
                }
            }

            // ─── Alt 鍵: 開啟 Pie Menu 快捷輪盤 (v2.0) ───
            while (OPEN_PIE_MENU.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new PieMenuScreen());
                }
            }

            // ─── X 鍵: 取消選取 (v2.0) ───
            while (DESELECT.consumeClick()) {
                if (mc.screen == null && mc.player != null) {
                    FdNetwork.CHANNEL.sendToServer(
                        new FdActionPacket(FdActionPacket.Action.DESELECT));
                }
            }

            // ─── ↑ 鍵: 選取區域上移一格 ───
            while (SHIFT_UP.consumeClick()) {
                if (mc.screen == null && mc.player != null && ClientSelectionHolder.hasSelection()) {
                    FdNetwork.CHANNEL.sendToServer(
                        new FdActionPacket(FdActionPacket.Action.SHIFT_SELECTION, "up"));
                }
            }

            // ─── ↓ 鍵: 選取區域下移一格 ───
            while (SHIFT_DOWN.consumeClick()) {
                if (mc.screen == null && mc.player != null && ClientSelectionHolder.hasSelection()) {
                    FdNetwork.CHANNEL.sendToServer(
                        new FdActionPacket(FdActionPacket.Action.SHIFT_SELECTION, "down"));
                }
            }

            // ─── H 鍵(按住): 對準選取邊界拖動調整大小 ───
            if (mc.screen == null && mc.player != null
                    && MOVE_SELECTION.isDown() && ClientSelectionHolder.hasSelection()) {
                HitResult hit = mc.hitResult;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos target = ((BlockHitResult) hit).getBlockPos();
                    var sel = ClientSelectionHolder.get();
                    if (sel != null) {
                        // 首次按下：偵測最近的邊界面
                        if (grabbedFace == null) {
                            grabbedFace = detectNearestFace(target, sel);
                        }
                        if (grabbedFace != null) {
                            // 根據抓取的面取得對應軸的座標值
                            int value = getFaceValue(target, grabbedFace);
                            if (value != lastSentValue) {
                                lastSentValue = value;
                                FdNetwork.CHANNEL.sendToServer(
                                    new FdActionPacket(FdActionPacket.Action.RESIZE_SELECTION,
                                        grabbedFace + "," + value));
                            }
                        }
                    }
                }
            } else {
                grabbedFace = null;
                lastSentValue = Integer.MIN_VALUE;
            }

            // ─── 每 tick 更新幽靈方塊預覽 ───
            updateGhostPreview(mc);
        }

        /**
         * 偵測準心位置最接近選取框的哪一面。
         * 比較目標方塊到六個面的距離，回傳最近的面 ID。
         */
        private static String detectNearestFace(BlockPos target, ClientSelectionHolder.SelectionData sel) {
            int tx = target.getX(), ty = target.getY(), tz = target.getZ();
            int minX = sel.min().getX(), minY = sel.min().getY(), minZ = sel.min().getZ();
            int maxX = sel.max().getX(), maxY = sel.max().getY(), maxZ = sel.max().getZ();

            String closest = null;
            int minDist = Integer.MAX_VALUE;

            int[][] faces = {
                {Math.abs(tx - minX), 0},  // x-
                {Math.abs(tx - maxX), 1},  // x+
                {Math.abs(ty - minY), 2},  // y-
                {Math.abs(ty - maxY), 3},  // y+
                {Math.abs(tz - minZ), 4},  // z-
                {Math.abs(tz - maxZ), 5},  // z+
            };
            String[] names = {"x-", "x+", "y-", "y+", "z-", "z+"};

            for (int[] f : faces) {
                if (f[0] < minDist) {
                    minDist = f[0];
                    closest = names[f[1]];
                }
            }
            return closest;
        }

        /**
         * 根據抓取的面，從目標方塊取得對應軸的座標值。
         */
        private static int getFaceValue(BlockPos target, String face) {
            return switch (face) {
                case "x-", "x+" -> target.getX();
                case "y-", "y+" -> target.getY();
                case "z-", "z+" -> target.getZ();
                default -> 0;
            };
        }

        /**
         * 每個客戶端 tick 更新幽靈方塊預覽。
         * 只在玩家手持 FdWand + 處於多方塊模式 + 已設定第一錨點時顯示。
         */
        private static void updateGhostPreview(Minecraft mc) {
            if (mc.player == null || mc.level == null) {
                GhostBlockRenderer.clearPreview();
                return;
            }

            // 只在手持 FdWand 時顯示預覽
            if (!(mc.player.getMainHandItem().getItem() instanceof
                    com.blockreality.fastdesign.item.FdWandItem)) {
                if (GhostBlockRenderer.hasPreview()) {
                    GhostBlockRenderer.clearPreview();
                }
                return;
            }

            // 只在多方塊模式下顯示
            if (!BuildModeState.isMultiBlockMode()) {
                if (GhostBlockRenderer.hasPreview()) {
                    GhostBlockRenderer.clearPreview();
                }
                return;
            }

            // 需要第一錨點才能計算預覽
            BlockPos anchor = BuildModeState.getAnchor();
            if (anchor == null) {
                if (GhostBlockRenderer.hasPreview()) {
                    GhostBlockRenderer.clearPreview();
                }
                return;
            }

            // 取得準心瞄準的方塊位置
            HitResult hit = mc.hitResult;
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
                GhostBlockRenderer.clearPreview();
                return;
            }

            BlockPos target = ((BlockHitResult) hit).getBlockPos();
            BuildModeState.setPreviewTarget(target);

            // 計算多方塊位置並更新預覽
            List<BlockPos> positions = MultiBlockCalculator.calculate(
                BuildModeState.getMode(),
                anchor,
                target,
                BuildModeState.getMirrorAnchor()
            );

            GhostBlockRenderer.setPreview(positions);
        }
    }
}
