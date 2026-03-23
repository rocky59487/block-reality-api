package com.blockreality.api;

import com.blockreality.api.sidecar.SidecarBridge;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BlockRealityMod.MOD_ID)
public class BlockRealityMod {
    public static final String MOD_ID = "blockreality";
    private static final Logger LOGGER = LogManager.getLogger("BlockReality");

    public BlockRealityMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // TODO: 之後在這裡註冊 BRBlocks 與 BRBlockEntities

        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[BlockReality] Mod 初始化完成 — v0.1.0-alpha");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        try {
            SidecarBridge.getInstance().start();
            LOGGER.info("[BlockReality] Sidecar 已啟動");
        } catch (Exception e) {
            LOGGER.error("[BlockReality] Sidecar 啟動失敗，CAD 功能將不可用", e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        SidecarBridge.getInstance().stop();
        LOGGER.info("[BlockReality] Sidecar 已停止");
    }
}
