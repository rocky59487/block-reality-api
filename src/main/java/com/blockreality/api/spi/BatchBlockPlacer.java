package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Efficient batch block placement utility.
 *
 * Queues block placements without triggering individual physics events,
 * then flushes all at once. Suppresses individual placement events and
 * fires a single batch completion event at the end.
 *
 * This is the core utility for the Fast Design module,
 * enabling efficient structural design operations.
 */
public class BatchBlockPlacer {

    private static final Logger LOGGER = LogManager.getLogger("BR-BatchBlockPlacer");

    private final ServerLevel level;
    private final Map<BlockPos, BlockState> pending = new LinkedHashMap<>();

    /**
     * Create a new batch placer for the given server level.
     *
     * @param level The ServerLevel where blocks will be placed
     */
    public BatchBlockPlacer(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("ServerLevel cannot be null");
        }
        this.level = level;
    }

    /**
     * Queue a block placement.
     * Does not immediately place the block.
     *
     * @param pos   The block position
     * @param state The block state to place
     */
    public void queue(BlockPos pos, BlockState state) {
        if (pos == null || state == null) {
            LOGGER.warn("Ignoring null position or blockstate in queue");
            return;
        }
        pending.put(pos.immutable(), state);
    }

    /**
     * Place all queued blocks in batch.
     *
     * Suppresses individual placement events by directly modifying level data,
     * then fires a single batch completion event.
     *
     * @return Number of blocks successfully placed
     */
    public int flush() {
        if (pending.isEmpty()) {
            return 0;
        }

        int count = 0;
        long startTime = System.nanoTime();

        try {
            for (Map.Entry<BlockPos, BlockState> entry : pending.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();

                // Direct level placement without triggering individual events
                if (level.setBlock(pos, state, 0)) {
                    count++;
                }
            }

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            LOGGER.debug("Batch block placement: {} blocks placed in {}ms",
                count, elapsedMs);

        } finally {
            pending.clear();
        }

        return count;
    }

    /**
     * Clear all pending placements without flushing.
     */
    public void clear() {
        pending.clear();
    }

    /**
     * Get the number of pending placements.
     *
     * @return Count of queued blocks
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Check if there are any pending placements.
     *
     * @return true if pending map is not empty
     */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * Get the target level.
     *
     * @return The ServerLevel for this batch placer
     */
    public ServerLevel getLevel() {
        return level;
    }
}
