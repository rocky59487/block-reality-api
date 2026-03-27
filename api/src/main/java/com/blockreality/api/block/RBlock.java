package com.blockreality.api.block;

import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.chisel.VoxelGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

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
     *