package com.blockreality.api.command;

import com.blockreality.api.physics.PhysicsExecutor;
import com.blockreality.api.physics.RWorldSnapshot;
import com.blockreality.api.physics.SnapshotBuilder;
import com.blockreality.api.physics.UnionFindEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * /br_test_physics [size]
 *
 * 以玩家為中心擷取快照 → 非同步 BFS 物理運算 → 回報懸空方塊。
 * 完整展示 主執行緒快照 → 背景執行緒運算 → 結果回報 的流程。
 */
public class PhysicsTestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_test_physics")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("size", IntegerArgumentType.integer(1, 40))
                    .executes(ctx -> {
                        int size = IntegerArgumentType.getInteger(ctx, "size");
                        return execute(ctx.getSource(), size);
                    })
                )
                .executes(ctx -> execute(ctx.getSource(), 32))
        );
    }

    private static int execute(CommandSourceStack source, int size) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BR] 需要以玩家身份執行"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos center = player.blockPosition();
        int half = size / 2;
        BlockPos start = center.offset(-half, -half, -half);
        BlockPos end = center.offset(half - 1, half - 1, half - 1);

        try {
            // ─── Step 1: 主執行緒擷取快照 ───
            RWorldSnapshot snapshot = SnapshotBuilder.capture(level, start, end);

            final String snapMsg = String.format(
                "[BR] Snapshot %dx%dx%d captured in %.2fms",
                snapshot.getSizeX(), snapshot.getSizeY(), snapshot.getSizeZ(),
                snapshot.getCaptureTimeMs()
            );
            source.sendSuccess(() -> Component.literal(snapMsg), false);

            // ─── Step 2: 丟進背景執行緒跑 BFS ───
            PhysicsExecutor.submit(snapshot).thenAccept(result -> {
                // 結果回到 CompletableFuture 回調（仍在背景執行緒）
                String resultMsg = String.format(
                    "[BR Physics] BFS done in %.2fms | anchors=%d, visited=%d, unsupported=%d/%d",
                    result.computeTimeMs(),
                    result.anchorCount(),
                    result.bfsVisited(),
                    result.unsupportedCount(),
                    result.totalNonAir()
                );
                source.sendSuccess(() -> Component.literal(resultMsg), true);

                if (result.timedOut()) {
                    source.sendFailure(Component.literal("[BR Physics] WARNING: BFS timed out!"));
                }

                // 列出前 10 個懸空方塊座標
                if (result.unsupportedCount() > 0) {
                    int shown = 0;
                    StringBuilder sb = new StringBuilder("[BR Physics] Unsupported blocks: ");
                    for (BlockPos pos : result.unsupportedBlocks()) {
                        if (shown >= 10) {
                            sb.append(String.format("... and %d more", result.unsupportedCount() - 10));
                            break;
                        }
                        if (shown > 0) sb.append(", ");
                        sb.append(String.format("(%d,%d,%d)", pos.getX(), pos.getY(), pos.getZ()));
                        shown++;
                    }
                    final String blockList = sb.toString();
                    source.sendSuccess(() -> Component.literal(blockList), false);
                }

            }).exceptionally(ex -> {
                source.sendFailure(Component.literal("[BR Physics] FAILED: " + ex.getMessage()));
                return null;
            });

            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("[BR] " + e.getMessage()));
            return 0;
        }
    }
}
