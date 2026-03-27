package com.blockreality.api.event;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.construction.ConstructionZoneManager;
import com.blockreality.api.physics.PhysicsScheduler;
import com.blockreality.api.physics.ResultApplicator;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.UnionFindEngine;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server tick 事件處理器 — v3fix §3.2 + §3.4
 *
 * 負責驅動每 tick 的佇列消費：
 *   - CollapseManager.processQueue()：處理分批坍方
 *   - ConstructionZoneManager.tickCuring()：養護進度檢查
 *
 * 世界卸載時清空佇列，避免跨世界洩漏。
 */
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerTickHandler {

    private static final Logger LOGGER = LogManager.getLogger("BR-Tick");

    /** 養護檢查頻率：每 20 ticks (1 秒) 檢查一次，減少開銷 */
    private static final int CURING_CHECK_INTERVAL = 20;

    /** AD-7 快取驅逐頻率：每 200 ticks (10 秒) 清理一次過期快取 */
    private static final int CACHE_EVICTION_INTERVAL = 200;

    /**
     * 每 server tick 結束時驅動坍方佇列及養護管理。
     * ★ F-2 fix: 移除無用的 curingTickCounter（養護已由 onLevelTick 驅動）。
     * ★ v3fix §3.4: 驅動 CuringManager.tickCuring() 以推進混凝土養護進度。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 驅動坍方佇列
        if (CollapseManager.hasPending()) {
            CollapseManager.processQueue();
        }

        // ★ v3fix §3.4: 推進所有活躍中的混凝土養護
        java.util.Set<BlockPos> curedBlocks = ModuleRegistry.getCuringManager().tickCuring();

        // ★ L-5 fix: 對已完成養護的方塊觸發 CuringProgressEvent
        if (!curedBlocks.isEmpty()) {
            MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
            if (srv != null) {
                ServerLevel overworld = srv.overworld();
                for (BlockPos pos : curedBlocks) {
                    MinecraftForge.EVENT_BUS.post(
                        new CuringProgressEvent(overworld, pos, 1.0f, true));
                }
            }
        }

        // ★ R3-9 fix: 跨 tick 恢復寫回失敗的方塊
        // ResultApplicator 的 getOrRetry() 記錄了找不到 RBlockEntity 的位置，
        // 需要每 tick 重試，直到 BE 載入或超過 MAX_RETRIES 次放棄。
        if (ResultApplicator.hasPendingFailures()) {
            MinecraftServer srv2 = ServerLifecycleHooks.getCurrentServer();
            if (srv2 != null) {
                for (ServerLevel sl : srv2.getAllLevels()) {
                    ResultApplicator.processFailedUpdates(sl);
                }
            }
        }

        // ★ audit-fix C-1: 消費 PhysicsScheduler 的 dirty queue，防止記憶體洩漏。
        // getScheduledWork 原本未被呼叫，導致 dirtyIslandIds 只增不減。
        // 目前僅 drain（標記已處理），未來可在此處觸發延遲物理重算。
        if (PhysicsScheduler.hasPendingWork()) {
            MinecraftServer srv3 = ServerLifecycleHooks.getCurrentServer();
            if (srv3 != null) {
                java.util.List<net.minecraft.server.level.ServerPlayer> players =
                    srv3.getPlayerList().getPlayers();
                long epoch = UnionFindEngine.getStructureEpoch();
                java.util.List<PhysicsScheduler.ScheduledWork> work =
                    PhysicsScheduler.getScheduledWork(players, epoch);
                for (PhysicsScheduler.ScheduledWork sw : work) {
                    PhysicsScheduler.markProcessed(sw.islandId());
                }
            }
        }

        // ★ AD-7: 定期驅逐過期 UnionFind 快取條目，防止記憶體洩漏
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.getTickCount() % CACHE_EVICTION_INTERVAL == 0) {
            UnionFindEngine.evictStaleEntries();
        }
    }

    /**
     * 每 level tick 結束時驅動養護檢查。
     * 養護系統在所有維度運作（現實混凝土不因維度而改變固化速度）。
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;

        if (level.getServer().getTickCount() % CURING_CHECK_INTERVAL != 0) return;

        ConstructionZoneManager manager = ConstructionZoneManager.get(level);
        if (manager.getZoneCount() > 0) {
            manager.tickCuring(level, level.getServer().getTickCount());
        }
    }

    /**
     * 世界卸載時清空坍方佇列。
     */
    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        CollapseManager.clearQueue();
        // ★ R3-10 fix: 世界卸載時清理 ResultApplicator 失敗追蹤，
        // 防止殘留的 BlockPos 在其他世界的 retryFailed() 中被錯誤查詢。
        ResultApplicator.clearFailedPositions();
        // ★ audit-fix M-5: Island Registry 和 Scheduler 是全域 static 結構，
        // 不應在每個維度卸載時清除（否則 Nether 卸載會清掉 Overworld 的數據）。
        // 僅在 Overworld 卸載（= 伺服器關閉）時清除。
        if (event.getLevel() instanceof ServerLevel sl && sl.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            StructureIslandRegistry.clear();
            PhysicsScheduler.clear();
            LOGGER.debug("[BR-Tick] Island registry & scheduler cleared on overworld unload (server shutdown)");
        }
        LOGGER.debug("[BR-Tick] Collapse queue & failed positions cleared on world unload");
    }
}
