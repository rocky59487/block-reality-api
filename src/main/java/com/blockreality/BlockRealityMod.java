package com.blockreality;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Block Reality API — 主模組進入點
 *
 * 模組架構分支：
 *   api               → 核心 API 介面與資料結構
 *   fast-design       → 快速設計系統 (BR-003+)
 *   construction-intern → 施工助手系統 (BR-008+)
 */
@Mod(BlockRealityMod.MOD_ID)
public class BlockRealityMod {

    public static final String MOD_ID = "blockreality";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BlockRealityMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 註冊 setup 事件
        modEventBus.addListener(this::commonSetup);

        // 將本類別註冊到 Forge 事件匯流排
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[BlockReality] Mod 初始化完成 — version 0.1.0-alpha");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[BlockReality] Common setup 完成");
    }

    @SubscribeEvent
    public void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[BlockReality] Client setup 完成");
    }
}
