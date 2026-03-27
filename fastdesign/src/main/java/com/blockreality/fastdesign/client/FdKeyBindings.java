package com.blockreality.fastdesign.client;

import com.blockreality.api.client.GhostBlockRenderer;
import com.blockreality.api.item.ChiselItem;
import com.blockreality.api.network.BRNetwork;
import com.blockreality.api.network.ChiselControlPacket;
import com.blockreality.api.placement.BuildMode;
import com.blockreality.api.placement.MultiBlockCalculator;
import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.build.BuildModeState;
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
 * Fast Design 快捷鍵系統 — Level 3
 *
 * G 鍵: 開啟 Control Panel
 * V 鍵: 切換建造模式 (Normal → Line → Wall → Cube → Mirror X → Mirror Z)
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

    // ★ 雕刻刀選區控制鍵
    public static final KeyMapping CHISEL_HEIGHT_UP = new KeyMapping(
        "key.fastdesign.chisel_height_up",
        InputConstants.KEY_UP,
        "key.categories.fastdesign"
    );

    public static final KeyMapping CHISEL_HEIGHT_DOWN = new KeyMapping(
        "key.fastdesign.chisel_height_down",
        InputConstants.KEY_DOWN,
        "key.categories.fastdesign"
    );

    public static final KeyMapping CHISEL_WIDTH_RIGHT = new KeyMapping(
        "key.fastdesign.chisel_width_right",
        InputConstants.KEY_RIGHT,
        "key.categories.fastdesign"
    );

    public static final KeyMapping CHISEL_WIDTH_LEFT = new KeyMapping(
        "key.fastdesign.chisel_width_left",
        InputConstants.KEY_LEFT,
        "key.categories.fastdesign"
    );

    /** X 鍵：按住啟用橡皮擦模式（取消選取單個方塊） */
    public static final KeyMapping CHISEL_ERASE = new KeyMapping(
        "key.fastdesign.chisel_erase",
        InputConstants.KEY_X,
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
            // ★ 雕刻刀控制鍵
            event.register(CHISEL_HEIGHT_UP);
            event.register(CHISEL_HEIGHT_DOWN);
            event.register(CHISEL_WIDTH_RIGHT);
            event.register(CHISEL_WIDTH_LEFT);
            event.register(CHISEL_ERASE);
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
                    // ActionBar 顯示當前模式
                    mc.player.displayClientMessage(
                        Component.literal("§6[FD] §f建造模式: §a" +
                            newMode.getDisplayName() + " §7(" + newMode.getDescription() + ")"),
                        true
                    );
                    // 切換模式時清除預覽
                    GhostBlockRenderer.clearPreview();
                }
            }

            // ─── 雕刻刀控制鍵 ───
            handleChiselKeys(mc);

            // ─── 每 tick 更新幽靈方塊預覽 ───
            updateGhostPreview(mc);
        }

        /** X 鍵上一 tick 狀態 — 用於偵測按下/放開邊緣 */
        private static boolean wasEraseKeyDown = false;

        /**
         * 處理雕刻刀專用快捷鍵。
         * 只在玩家手持雕刻刀時生效。
         */
        private static void handleChiselKeys(Minecraft mc) {
            if (mc.player == null || mc.screen != null) return;

            // 只在手持雕刻刀時響應
            boolean holdingChisel =
                mc.player.getMainHandItem().getItem() instanceof ChiselItem ||
                mc.player.getOffhandItem().getItem() instanceof ChiselItem;
            if (!holdingChisel) {
                // 若切換離開雕刻刀，確保橡皮擦模式關閉
                if (wasEraseKeyDown) {
                    wasEraseKeyDown = false;
                    BRNetwork.CHANNEL.sendToServer(
                        new ChiselControlPacket(ChiselControlPacket.Action.ERASE_OFF));
                }
                return;
            }

            // 上下鍵：調高度
            while (CHISEL_HEIGHT_UP.consumeClick()) {
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.SEL_HEIGHT_INC));
            }
            while (CHISEL_HEIGHT_DOWN.consumeClick()) {
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.SEL_HEIGHT_DEC));
            }

            // 左右鍵：調寬度
            while (CHISEL_WIDTH_RIGHT.consumeClick()) {
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.SEL_WIDTH_INC));
            }
            while (CHISEL_WIDTH_LEFT.consumeClick()) {
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.SEL_WIDTH_DEC));
            }

            // X 鍵：按住 = 橡皮擦模式（偵測邊緣觸發，避免每 tick 發封包）
            boolean isEraseDown = CHISEL_ERASE.isDown();
            if (isEraseDown && !wasEraseKeyDown) {
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.ERASE_ON));
            } else if (!isEraseDown && wasEraseKeyDown) {
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.ERASE_OFF));
            }
            wasEraseKeyDown = isEraseDown;
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
