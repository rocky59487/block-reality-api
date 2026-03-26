package com.blockreality.fastdesign.sidecar;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.sidecar.SidecarBridge;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * NURBS 匯出器 — v3fix §2.4
 */
public class NurbsExporter {

    private static final Logger LOGGER = LogManager.getLogger("FD-NURBS");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static JsonObject export(ServerLevel level, PlayerSelectionManager.SelectionBox box)
            throws IOException, InterruptedException, TimeoutException {

        JsonArray blockArray = collectBlockData(level, box);

        if (blockArray.isEmpty()) {
            throw new IOException("Selection contains no R-unit blocks. Nothing to export.");
        }

        int maxExport = FastDesignConfig.getExportMaxBlocks();
        if (blockArray.size() > maxExport) {
            throw new IOException("Selection too large for export: " + blockArray.size() +
                " blocks (max " + maxExport + "). Reduce selection size.");
        }

        JsonObject payload = new JsonObject();
        payload.add("blocks", blockArray);
        payload.addProperty("originX", box.min().getX());
        payload.addProperty("originY", box.min().getY());
        payload.addProperty("originZ", box.min().getZ());

        int timeoutSec = FastDesignConfig.getExportTimeoutSeconds();
        try {
            JsonObject result = SidecarBridge.getInstance().call("export", payload, timeoutSec * 1000);
            LOGGER.info("[NURBS] Export via SidecarBridge succeeded");
            return result;
        } catch (Exception e) {
            LOGGER.warn("[NURBS] SidecarBridge export failed, falling back to ProcessBuilder: {}",
                e.getMessage());
        }

        return exportViaProcess(payload);
    }

    private static JsonArray collectBlockData(ServerLevel level,
                                               PlayerSelectionManager.SelectionBox box) {
        JsonArray arr = new JsonArray();
        BlockPos origin = box.min();

        for (BlockPos pos : box.allPositions()) {
            BlockPos immutable = pos.immutable();
            BlockState state = level.getBlockState(immutable);
            if (state.isAir()) continue;

            BlockEntity be = level.getBlockEntity(immutable);
            if (!(be instanceof RBlockEntity rbe)) continue;

            RMaterial mat = rbe.getMaterial();
            JsonObject obj = new JsonObject();
            obj.addProperty("x", immutable.getX() - origin.getX());
            obj.addProperty("y", immutable.getY() - origin.getY());
            obj.addProperty("z", immutable.getZ() - origin.getZ());
            obj.addProperty("material", mat.getMaterialId());
            obj.addProperty("rcomp", mat.getRcomp());
            obj.addProperty("rtens", mat.getRtens());
            obj.addProperty("stressLevel", rbe.getStressLevel());
            obj.addProperty("isAnchored", rbe.isAnchored());
            arr.add(obj);
        }

        return arr;
    }

    private static JsonObject exportViaProcess(JsonObject payload)
            throws IOException, InterruptedException, TimeoutException {

        Path sidecarScript = resolveSidecarScript();
        String jsonInput = GSON.toJson(payload);

        Path outputDir = FMLPaths.CONFIGDIR.get().resolve("blockreality/exports");
        Files.createDirectories(outputDir);

        ProcessBuilder pb = new ProcessBuilder("node", sidecarScript.toAbsolutePath().toString());
        pb.redirectErrorStream(false);
        pb.environment().put("NURBS_OUTPUT_DIR", outputDir.toString());

        Process process = pb.start();

        try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(jsonInput);
            writer.flush();
        }

        ExecutorService ioPool = Executors.newFixedThreadPool(2);
        try {
            Future<String> stdoutFuture = ioPool.submit(() ->
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            Future<String> stderrFuture = ioPool.submit(() ->
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));

            int fallbackTimeout = FastDesignConfig.getExportTimeoutSeconds();
            boolean finished = process.waitFor(fallbackTimeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TimeoutException("NURBS sidecar timed out after " + fallbackTimeout + "s");
            }

            try {
                String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
                if (!stderr.isBlank()) {
                    LOGGER.warn("[NURBS stderr] {}", stderr);
                }
            } catch (ExecutionException | TimeoutException e) {
                LOGGER.warn("[NURBS] Could not read stderr: {}", e.getMessage());
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("NURBS sidecar exited with code " + exitCode);
            }

            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            return GSON.fromJson(stdout.trim(), JsonObject.class);
        } catch (ExecutionException e) {
            throw new IOException("Failed to read sidecar output: " + e.getMessage());
        } finally {
            ioPool.shutdownNow();
            // 確保 process streams 關閉，防止 file descriptor 洩漏
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
            if (process.isAlive()) { process.destroyForcibly(); }
        }
    }

    private static Path resolveSidecarScript() throws FileNotFoundException {
        Path path = FMLPaths.MODSDIR.get().resolve("sidecar/nurbs_pipeline.js");
        if (!Files.exists(path)) {
            throw new FileNotFoundException(
                "NURBS sidecar script not found: " + path +
                "\nPlace nurbs_pipeline.js in mods/sidecar/ directory");
        }
        return path;
    }
}
