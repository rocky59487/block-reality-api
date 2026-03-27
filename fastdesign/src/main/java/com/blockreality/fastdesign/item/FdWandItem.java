package com.blockreality.fastdesign.item;

import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.blockreality.fastdesign.network.FdNetwork;
import com.blockreality.fastdesign.network.FdSelectionSyncPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Fast Design 選取游標 — 實體道具
 *
 * 左鍵點方塊 → 設定 pos1 (由 SelectionWandHandler 攔截 LeftClickBlock 事件)
 * 右鍵點方塊 → 設定 pos2 (NORMAL) / 設定錨點或確認放置 (多方塊模式)
 * Shift + 右鍵空氣 → 開啟 Control Panel (Level 3)
 * Ctrl + 右鍵方塊 → 設定鏡像錨點 (Mirror 模式)
 */
public class FdWandItem extends Item {

    public FdWandItem() {
        super(new Item.Properties().stacksTo(1));
    }

    /**
     * 右鍵點擊方塊 — 設定 pos2 或多方塊放置
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!FastDesignConfig.isWandEnabled()) {
            return InteractionResult.PASS;
        }

        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();

        // ─── 客戶端: 多方塊模式處理 ───
        // WandClientHandler 是 @OnlyIn(CLIENT) 類別，透過 DistExecutor 確保伺服器不載入
        if (level.isClientSide) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.blockreality.fastdesign.client.WandClientHandler.handleMultiBlock(player, pos)
            );
            return InteractionResult.SUCCESS;
        }

        // ─── 伺服器端: NORMAL 模式 — 傳統 pos2 設定 ───
        // 多方塊模式的伺服器端放置由 PLACE_MULTI packet handler 處理
        PlayerSelectionManager.setPos2(player.getUUID(), pos);

        if (player instanceof ServerPlayer sp) {
            BlockPos pos1 = PlayerSelectionManager.getPos1(player.getUUID());
            if (pos1 != null) {
                FdNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new FdSelectionSyncPacket(pos1, pos)
                );

                var box = PlayerSelectionManager.getSelection(player.getUUID());
                sp.displayClientMessage(Component.literal(String.format(
                    "§6[FD] §aA: (%d,%d,%d) §7→ §cB: (%d,%d,%d) §7| §f%d×%d×%d = %d blocks",
                    pos1.getX(), pos1.getY(), pos1.getZ(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    box.sizeX(), box.sizeY(), box.sizeZ(), box.volume()
                )), true);
            } else {
                sp.displayClientMessage(
                    Component.literal("§6[FD] §fPos2 設定: §a" + pos.toShortString()),
                    true
                );
            }
        }

        return InteractionResult.CONSUME;
    }

    /**
     * 右鍵空氣 — Shift 時開啟 Control Panel
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!FastDesignConfig.isWandEnabled()) {
            return InteractionResultHolder.pass(stack);
        }

        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> net.minecraft.client.Minecraft.getInstance().setScreen(
                        new com.blockreality.fastdesign.client.ControlPanelScreen()));
            }
            return InteractionResultHolder.consume(stack);
        }

        return InteractionResultHolder.pass(stack);
    }

    /**
     * 發光效果
     */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /**
     * 工具提示
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                 List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("左鍵: 設定選取點 A").withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.literal("右鍵: 設定選取點 B / 多方塊放置").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("Alt: 開啟控制面板").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("V 鍵: 切換建造模式").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.literal("Ctrl+右鍵: 設定鏡像錨點").withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("↑↓ 調高度  ←→ 調寬度  H 邊長").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("X+右鍵: 橡皮擦").withStyle(ChatFormatting.RED));
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("Fast Design 建築輔助工具").withStyle(ChatFormatting.GRAY));
    }
}
