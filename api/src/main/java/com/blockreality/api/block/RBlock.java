package com.blockreality.api.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Block Reality 結構方塊。
 *
 * 繼承 BaseEntityBlock 以搭載 RBlockEntity，
 * 提供材料參數存儲、NBT 持久化、Client 同步等完整物理數據管線。
 *
 * 渲染使用 MODEL 模式（標準方塊外觀），
 * 未來可依 stressLevel 改變外觀（裂紋紋理等）。
 */
public class RBlock extends BaseEntityBlock {

    public RBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f, 6.0f)      // hardness=2, resistance=6 (同石頭)
            .sound(SoundType.STONE)
            .requiresCorrectToolForDrops()
        );
    }

    /**
     * 自訂 Properties 的建構子 — 給不同材質的子類使用。
     */
    public RBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new RBlockEntity(pos, state);
    }

    /**
     * 目前不需要 tick — 未來養護計時 (RC curing) 可在此掛載 ticker。
     */
    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            @NotNull Level level,
            @NotNull BlockState state,
            @NotNull BlockEntityType<T> type) {
        return null;
    }

    /**
     * 使用標準方塊模型渲染。
     * BaseEntityBlock 預設是 INVISIBLE，必須覆寫。
     */
    @Override
    @NotNull
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }
}
