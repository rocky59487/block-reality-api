package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.item.FdWandItem;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 體素級全息預覽渲染器 — Fast Design v2.0 Feature 1
 *
 * 當玩家手持載有藍圖的 FdWandItem 並處於 Paste 模式時，
 * 不再只顯示空洞的線框邊界，而是渲染剪貼簿內每個方塊的半透明「幽靈方塊」。
 *
 * 設計參考：Litematica 的 SchematicWorldRenderingNotifier + Placement Alpha Rendering。
 *
 * 效能策略：
 * - 使用預編譯的方塊頂點資料快取 (Map<BlockPos, BlockState>)
 * - 使用 RenderSystem 批次渲染，避免每幀重建 BufferBuilder
 * - 超過 MAX_PREVIEW_BLOCKS 時自動降級為線框模式
 */
@Mod.EventBusSubscriber(
    modid = FastDesignMod.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public class GhostPreviewRenderer {

    private static final int MAX_PREVIEW_BLOCKS = 100_000;
    private static final float GHOST_ALPHA = 0.4f;

    // 全息預覽資料快取（由指令系統或 Wand 邏輯填入）
    private static volatile Map<BlockPos, BlockState> previewData = null;
    private static volatile BlockPos previewOrigin = BlockPos.ZERO;
    private static volatile boolean previewActive = false;

    // ─── 公開 API ───

    /** 設定預覽資料（由 Paste 指令或 Wand 邏輯呼叫） */
    public static void setPreview(Map<BlockPos, BlockState> blocks, BlockPos origin) {
        if (blocks == null || blocks.isEmpty()) {
            clearPreview();
            return;
        }
        previewData = new ConcurrentHashMap<>(blocks);
        previewOrigin = origin;
        previewActive = true;
    }

    /** 清除預覽 */
    public static void clearPreview() {
        previewActive = false;
        previewData = null;
    }

    public static boolean hasPreview() {
        return previewActive && previewData != null;
    }

    // ─── 渲染 ───

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!previewActive || previewData == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 只在手持 FdWand 時渲染
        ItemStack mainHand = mc.player.getMainHandItem();
        if (!(mainHand.getItem() instanceof FdWandItem)) return;

        Map<BlockPos, BlockState> data = previewData;
        if (data == null || data.isEmpty()) return;

        // 超出上限時降級（只畫外框，不畫每個方塊）
        if (data.size() > MAX_PREVIEW_BLOCKS) {
            renderBoundingBoxOnly(event, data);
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = mc.getEntityRenderDispatcher().camera.getPosition();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f mat = poseStack.last().pose();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (var entry : data.entrySet()) {
            BlockPos relPos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;

            // 世界座標 = 預覽原點 + 相對座標
            float wx = previewOrigin.getX() + relPos.getX();
            float wy = previewOrigin.getY() + relPos.getY();
            float wz = previewOrigin.getZ() + relPos.getZ();

            // 從方塊的 MapColor 取色
            int mapColor = state.getMapColor(mc.level, relPos).col;
            int r = (mapColor >> 16) & 0xFF;
            int g = (mapColor >> 8) & 0xFF;
            int b = mapColor & 0xFF;
            int a = (int)(GHOST_ALPHA * 255);

            // 簡化的 6 面方塊渲染
            renderGhostCube(buf, mat, wx, wy, wz, wx + 1, wy + 1, wz + 1, r, g, b, a);
        }

        tess.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void renderBoundingBoxOnly(RenderLevelStageEvent event, Map<BlockPos, BlockState> data) {
        // 計算 AABB 並只渲染外框
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : data.keySet()) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }

        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = mc.getEntityRenderDispatcher().camera.getPosition();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f mat = poseStack.last().pose();

        float x0 = previewOrigin.getX() + minX;
        float y0 = previewOrigin.getY() + minY;
        float z0 = previewOrigin.getZ() + minZ;
        float x1 = previewOrigin.getX() + maxX + 1;
        float y1 = previewOrigin.getY() + maxY + 1;
        float z1 = previewOrigin.getZ() + maxZ + 1;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(3.0f);

        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        int r = 0, g = 200, b = 255, a = 200;

        // 12 edges
        line(buf, mat, x0,y0,z0, x1,y0,z0, r,g,b,a); line(buf, mat, x1,y0,z0, x1,y0,z1, r,g,b,a);
        line(buf, mat, x1,y0,z1, x0,y0,z1, r,g,b,a); line(buf, mat, x0,y0,z1, x0,y0,z0, r,g,b,a);
        line(buf, mat, x0,y1,z0, x1,y1,z0, r,g,b,a); line(buf, mat, x1,y1,z0, x1,y1,z1, r,g,b,a);
        line(buf, mat, x1,y1,z1, x0,y1,z1, r,g,b,a); line(buf, mat, x0,y1,z1, x0,y1,z0, r,g,b,a);
        line(buf, mat, x0,y0,z0, x0,y1,z0, r,g,b,a); line(buf, mat, x1,y0,z0, x1,y1,z0, r,g,b,a);
        line(buf, mat, x1,y0,z1, x1,y1,z1, r,g,b,a); line(buf, mat, x0,y0,z1, x0,y1,z1, r,g,b,a);

        tess.end();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    // ─── 工具方法 ───

    private static void renderGhostCube(BufferBuilder buf, Matrix4f mat,
                                         float x0, float y0, float z0,
                                         float x1, float y1, float z1,
                                         int r, int g, int b, int a) {
        // Bottom (Y-)
        quad(buf, mat, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1, r,g,b,a);
        // Top (Y+)
        quad(buf, mat, x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0, r,g,b,a);
        // North (Z-)
        quad(buf, mat, x0,y0,z0, x0,y1,z0, x1,y1,z0, x1,y0,z0, r,g,b,a);
        // South (Z+)
        quad(buf, mat, x1,y0,z1, x1,y1,z1, x0,y1,z1, x0,y0,z1, r,g,b,a);
        // West (X-)
        quad(buf, mat, x0,y0,z1, x0,y1,z1, x0,y1,z0, x0,y0,z0, r,g,b,a);
        // East (X+)
        quad(buf, mat, x1,y0,z0, x1,y1,z0, x1,y1,z1, x1,y0,z1, r,g,b,a);
    }

    private static void quad(BufferBuilder buf, Matrix4f mat,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float x3, float y3, float z3,
                              int r, int g, int b, int a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x2, y2, z2).color(r, g, b, a).endVertex();
        buf.vertex(mat, x3, y3, z3).color(r, g, b, a).endVertex();
    }

    private static void line(BufferBuilder buf, Matrix4f mat,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              int r, int g, int b, int a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
    }
}
