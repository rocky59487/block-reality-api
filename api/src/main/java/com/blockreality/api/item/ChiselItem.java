package com.blockreality.api.item;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.chisel.VoxelGrid;
import com.blockreality.api.network.BRNetwork;
import com.blockreality.api.network.ChiselSyncPacket;
import com.blockreality.api.physics.PhysicsScheduler;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.UnionFindEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * 雕刻刀 — 將 RBlock 雕刻為子方塊形狀。
 *
 * 操作方式：
 *   - 右鍵方塊：套用當前模板形狀 / 在自訂模式下切換子體素
 *   - Shift+右鍵空中：循環切換模板形狀
 *
 * 模式儲存在 ItemStack NBT 的 "chisel_mode" 標籤中。
 */
public class ChiselItem extends Item {

    private static final String TAG_MODE = "chisel_mode";
    private static final String TAG_SHAPE_INDEX = "chisel_shape_idx";

    /** 可循環的模板形狀（不含 CUSTOM，CUSTOM 需要額外操作進入） */
    private static final SubBlockShape[] TEMPLATE_CYCLE = {
        SubBlockShape.FULL,
        SubBlockShape.SLAB_BOTTOM,
        SubBlockShape.SLAB_TOP,
        SubBlockShape.STAIR_NORTH,
        SubBlockShape.STAIR_SOUTH,
        SubBlockShape.STAIR_EAST,
        SubBlockShape.STAIR_WEST,
        SubBlockShape.PILLAR,
        SubBlockShape.QUARTER_NE,
        SubBlockShape.QUARTER_NW,
        SubBlockShape.QUARTER_SE,
        SubBlockShape.QUARTER_SW,
        SubBlockShape.ARCH_BOTTOM,
        SubBlockShape.ARCH_TOP,
        SubBlockShape.BEAM_NS,
        SubBlockShape.BEAM_EW,
        SubBlockShape.CUSTOM,
    };

    public ChiselItem() {
        super(new Item.Properties().stacksTo(1));
    }

    // ─── 右鍵方塊：套用形狀 ───

    @Override
    @NotNull
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockPos pos = context.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RBlockEntity rbe)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        SubBlockShape currentShape = getCurrentShape(stack);

        if (currentShape == SubBlockShape.CUSTOM) {
            // 自訂模式：切換 hit 位置的子體素
            Vec3 hit = context.getClickLocation();
            int vx = Mth.clamp((int) ((hit.x - pos.getX()) * 10), 0, 9);
            int vy = Mth.clamp((int) ((hit.y - pos.getY()) * 10), 0, 9);
            int vz = Mth.clamp((int) ((hit.z - pos.getZ()) * 10), 0, 9);

            ChiselState oldState = rbe.getChiselState();
            VoxelGrid oldGrid = oldState.isCustom() ? oldState.voxelGrid() : VoxelGrid.full();
            boolean wasSet = oldGrid.get(vx, vy, vz);

            VoxelGrid newGrid = new VoxelGrid.Builder(oldGrid)
                .set(vx, vy, vz, !wasSet)
                .build();

            if (newGrid.isEmpty()) {
                return InteractionResult.FAIL; // 不允許完全移除
            }

            ChiselState newState = ChiselState.ofCustom(newGrid);
            rbe.setChiselState(newState);
        } else {
            // 模板模式：直接套用
            ChiselState newState = ChiselState.ofShape(currentShape);
            rbe.setChiselState(newState);
        }

        // 同步到附近玩家
        BRNetwork.CHANNEL.send(
            PacketDistributor.TRACKING_CHUNK.with(() ->
                level.getChunkAt(pos)),
            new ChiselSyncPacket(pos, rbe.getChiselState())
        );

        // 觸發物理重新計算
        long epoch = UnionFindEngine.getStructureEpoch();
        int islandId = StructureIslandRegistry.getIslandId(pos);
        if (islandId >= 0) {
            PhysicsScheduler.markDirty(islandId, epoch);
        }

        return InteractionResult.CONSUME;
    }

    // ─── Shift+右鍵空中：循環切換形狀 ───

    @Override
    @NotNull
    public InteractionResultHolder<ItemStack> use(
            @NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {

        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }

        ItemStack stack = player.getItemInHand(hand);
        int currentIdx = getShapeIndex(stack);
        int nextIdx = (currentIdx + 1) % TEMPLATE_CYCLE.length;
        setShapeIndex(stack, nextIdx);

        SubBlockShape nextShape = TEMPLATE_CYCLE[nextIdx];

        if (!level.isClientSide) {
            player.displayClientMessage(
                Component.literal("§b[雕刻刀] §f形狀: " + nextShape.getSerializedName()),
                true // actionbar
            );
        }

        return InteractionResultHolder.success(stack);
    }

    // ─── NBT 輔助 ───

    private SubBlockShape getCurrentShape(ItemStack stack) {
        int idx = getShapeIndex(stack);
        if (idx < 0 || idx >= TEMPLATE_CYCLE.length) return SubBlockShape.FULL;
        return TEMPLATE_CYCLE[idx];
    }

    private int getShapeIndex(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_SHAPE_INDEX)) return 0;
        return tag.getInt(TAG_SHAPE_INDEX);
    }

    private void setShapeIndex(ItemStack stack, int idx) {
        stack.getOrCreateTag().putInt(TAG_SHAPE_INDEX, idx);
    }
}
