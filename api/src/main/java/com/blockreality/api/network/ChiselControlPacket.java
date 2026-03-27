package com.blockreality.api.network;

import com.blockreality.api.item.ChiselItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C→S 雕刻刀控制封包 — 處理按鍵調整選區大小與橡皮擦模式。
 *
 * 操作：
 *   - SEL_WIDTH_INC / SEL_WIDTH_DEC：左右鍵調整選區寬度 (1~10)
 *   - SEL_HEIGHT_INC / SEL_HEIGHT_DEC：上下鍵調整選區高度 (1~10)
 *   - ERASE_ON / ERASE_OFF：X 鍵按住/放開 → 橡皮擦模式
 */
public class ChiselControlPacket {

    public enum Action {
        SEL_WIDTH_INC,
        SEL_WIDTH_DEC,
        SEL_HEIGHT_INC,
        SEL_HEIGHT_DEC,
        ERASE_ON,
        ERASE_OFF
    }

    private final Action action;

    public ChiselControlPacket(Action action) {
        this.action = action;
    }

    public static void encode(ChiselControlPacket pkt, FriendlyByteBuf buf) {
        buf.writeInt(pkt.action.ordinal());
    }

    public static ChiselControlPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readInt();
        Action[] actions = Action.values();
        if (ordinal < 0 || ordinal >= actions.length) {
            return new ChiselControlPacket(Action.SEL_WIDTH_INC); // safe fallback
        }
        return new ChiselControlPacket(actions[ordinal]);
    }

    public static void handle(ChiselControlPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack heldItem = player.getMainHandItem();
            if (!(heldItem.getItem() instanceof ChiselItem)) {
                // 嘗試副手
                heldItem = player.getOffhandItem();
                if (!(heldItem.getItem() instanceof ChiselItem)) return;
            }

            switch (pkt.action) {
                case SEL_WIDTH_INC ->
                    ChiselItem.adjustSelectionWidth(heldItem, 1);
                case SEL_WIDTH_DEC ->
                    ChiselItem.adjustSelectionWidth(heldItem, -1);
                case SEL_HEIGHT_INC ->
                    ChiselItem.adjustSelectionHeight(heldItem, 1);
                case SEL_HEIGHT_DEC ->
                    ChiselItem.adjustSelectionHeight(heldItem, -1);
                case ERASE_ON ->
                    ChiselItem.setEraseMode(heldItem, true);
                case ERASE_OFF ->
                    ChiselItem.setEraseMode(heldItem, false);
            }

            // ActionBar 反饋
            int w = ChiselItem.getSelectionWidth(heldItem);
            int h = ChiselItem.getSelectionHeight(heldItem);
            boolean erase = ChiselItem.isEraseMode(heldItem);
            String mode = erase ? "§c橡皮擦" : "§a填充";
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    "§b[雕刻刀] §f選區: " + w + "×" + h + " " + mode),
                true
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
