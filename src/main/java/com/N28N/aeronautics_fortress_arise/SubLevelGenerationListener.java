package com.N28N.aeronautics_fortress_arise;

import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureControllerBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureHeatmapData;
import com.N28N.aeronautics_fortress_arise.config.AIStructureControllerConfig;
import com.N28N.aeronautics_fortress_arise.config.SubLevelConfig;
import com.N28N.aeronautics_fortress_arise.config.SubLevelConfigLoader;
import com.N28N.aeronautics_fortress_arise.config.ModConfigPaths;
import com.N28N.aeronautics_fortress_arise.util.ChunkHashNoise;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprint;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprintPlacer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SubLevelGenerationListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubLevelGenerationListener.class);
    public static float placingHeight = 256;
    private static final Map<UUID, ScanCursor> SCAN_CURSORS = new HashMap<>();
    private static int nextPlayerIndex;

    //#region Configuration
    private static final Path CONFIG_DIR = ModConfigPaths.DIRECTORY;
    //#endregion

    //#region Chunk generation
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getChunk() instanceof LevelChunk chunk) {
            for (var blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof AIStructureControllerBlockEntity controller) {
                    controller.unregisterInstance();
                }
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        List<ServerPlayer> players = new ArrayList<>(event.getServer().getPlayerList().getPlayers());
        if (players.isEmpty()) {
            SCAN_CURSORS.clear();
            nextPlayerIndex = 0;
            return;
        }

        Set<UUID> onlinePlayers = new HashSet<>();
        for (ServerPlayer player : players) {
            onlinePlayers.add(player.getUUID());
        }
        SCAN_CURSORS.keySet().retainAll(onlinePlayers);

        int radius = AIStructureControllerConfig.CHUNK_SCAN_RADIUS.get();
        for (ServerPlayer player : players) {
            ScanCursor cursor = SCAN_CURSORS.computeIfAbsent(player.getUUID(), ignored -> new ScanCursor());
            cursor.prepare(player, radius);
        }

        int budget = AIStructureControllerConfig.MAX_CHUNK_SCANS_PER_TICK.get();
        int playerIndex = Math.floorMod(nextPlayerIndex, players.size());
        while (budget-- > 0) {
            ServerPlayer player = players.get(playerIndex);
            ScanCursor cursor = SCAN_CURSORS.get(player.getUUID());
            cursor.restartIfFinished(player, radius);
            scanNextChunk((ServerLevel) player.level(), cursor);
            playerIndex = (playerIndex + 1) % players.size();
        }
        nextPlayerIndex = playerIndex;
    }

    private static void scanNextChunk(ServerLevel level, ScanCursor cursor) {
        int diameter = cursor.radius * 2 + 1;
        int totalChunks = diameter * diameter;
        int centerIndex = cursor.radius * diameter + cursor.radius;
        int scanIndex = (centerIndex + cursor.nextIndex) % totalChunks;
        int offsetX = scanIndex / diameter - cursor.radius;
        int offsetZ = scanIndex % diameter - cursor.radius;
        cursor.nextIndex++;

        if ((long) offsetX * offsetX + (long) offsetZ * offsetZ > (long) cursor.radius * cursor.radius) {
            return;
        }

        int chunkX = cursor.centerX + offsetX;
        int chunkZ = cursor.centerZ + offsetZ;
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        AIStructureHeatmapData heatmap = AIStructureHeatmapData.get(level);
        long seed = level.getSeed();
        float noise = heatmap.getOrCreateNoise(
                chunkKey, ChunkHashNoise.random01((int) (seed ^ (seed >>> 32)), chunkX, chunkZ));
        if (heatmap.isProcessed(chunkKey)) {
            return;
        }

        List<SubLevelConfig> configs = SubLevelConfigLoader.getConfigs();
        if (configs.isEmpty()) {
            return;
        }

        double blockX = chunkX * 16.0 + 8.0;
        double blockZ = chunkZ * 16.0 + 8.0;
        BlockPos spawnPos = level.getLevelData().getSpawnPos();
        double distanceFromSpawn = Math.hypot(blockX - spawnPos.getX(), blockZ - spawnPos.getZ());
        for (SubLevelConfig config : configs) {
            if (distanceFromSpawn >= config.minDistanceFromSpawn && noise < config.spawnChance) {
                placeSubLevel(level, chunkX, chunkZ, config);
            }
        }
        heatmap.markProcessed(chunkKey);
    }

    private static void placeSubLevel(ServerLevel level, int chunkX, int chunkZ, SubLevelConfig config) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            LOGGER.warn("No Sable sub-level container available for level");
            return;
        }

        // 加载蓝图文件
        SableBlueprint blueprint = loadBlueprintFromNBT(config.path);
        if (blueprint == null) {
            LOGGER.warn("Failed to load blueprint from: {}", CONFIG_DIR.resolve(config.path));
            return;
        }

        // 在区块中心放置 SubLevel
        double centerX = chunkX * 16.0 + 8.5;
        double centerY = placingHeight;
        double centerZ = chunkZ * 16.0 + 8.5;

        // 使用 SableBlueprintPlacer 放置蓝图
        try {
            Vec3 origin = new Vec3(centerX, centerY, centerZ);
            SableBlueprintPlacer.Result result = SableBlueprintPlacer.place(level, blueprint, origin);
            activateBlueprintLevers(level, result);

            LOGGER.info("Successfully placed SubLevel '{}' at ({}, {}, {})",
                    config.name, centerX, centerY, centerZ);
        } catch (Exception e) {
            LOGGER.error("Error placing SubLevel '{}': ", config.name, e);
        }
    }

    private static final class ScanCursor {
        private net.minecraft.resources.ResourceKey<Level> dimension;
        private int centerX;
        private int centerZ;
        private int radius;
        private int nextIndex;

        private void prepare(ServerPlayer player, int configuredRadius) {
            if (this.dimension == null
                    || !this.dimension.equals(player.level().dimension())
                    || this.radius != configuredRadius) {
                this.reset(player, configuredRadius);
            }
        }

        private void restartIfFinished(ServerPlayer player, int configuredRadius) {
            long diameter = configuredRadius * 2L + 1L;
            if ((long) this.nextIndex >= diameter * diameter) {
                this.reset(player, configuredRadius);
            }
        }

        private void reset(ServerPlayer player, int configuredRadius) {
            ChunkPos playerChunk = player.chunkPosition();
            this.dimension = player.level().dimension();
            this.centerX = playerChunk.x;
            this.centerZ = playerChunk.z;
            this.radius = configuredRadius;
            this.nextIndex = 0;
        }
    }
    //#endregion

    //#region Sable structure initialization
    private static void activateBlueprintLevers(
            ServerLevel level, SableBlueprintPlacer.Result result) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            LOGGER.warn("Cannot initialize levers: no Sable sub-level container available");
            return;
        }

        int changedLevers = 0;
        for (UUID subLevelId : result.subLevelUuidMap().values()) {
            SubLevel subLevel = container.getSubLevel(subLevelId);
            if (subLevel instanceof ServerSubLevel serverSubLevel) {
                changedLevers += setAllLeversOn(level, serverSubLevel);
            }
        }
        LOGGER.info("Powered on {} levers in generated Sable structure", changedLevers);
    }

    private static int setAllLeversOn(ServerLevel level, ServerSubLevel subLevel) {
        var bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null || bounds.maxX() < bounds.minX()
                || bounds.maxY() < bounds.minY() || bounds.maxZ() < bounds.minZ()) {
            return 0;
        }

        int leverCount = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    var pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof LeverBlock && state.getValue(LeverBlock.POWERED)) {
                        level.setBlock(pos, state.setValue(LeverBlock.POWERED, false), Block.UPDATE_ALL);
                        leverCount++;
                    } else if (state.getBlock() instanceof LeverBlock) {
                        leverCount++;
                    }
                }
            }
        }

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    var pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof LeverBlock && !state.getValue(LeverBlock.POWERED)) {
                        level.setBlock(pos, state.setValue(LeverBlock.POWERED, true), Block.UPDATE_ALL);
                    }
                }
            }
        }
        return leverCount;
    }
    //#endregion

    //#region Blueprint loading
    /**
     * 从 NBT 文件加载 Sable 蓝图
     * 文件路径: config/aeronautics_fortress_arise/${path}
     */
    private static SableBlueprint loadBlueprintFromNBT(String relativePath) {
        try {
            java.nio.file.Path blueprintPath = CONFIG_DIR.resolve(relativePath);

            if (!java.nio.file.Files.exists(blueprintPath)) {
                LOGGER.warn("Blueprint file not found: {}", blueprintPath.toAbsolutePath());
                return null;
            }

            CompoundTag nbtData;

            try (java.io.InputStream in = java.nio.file.Files.newInputStream(blueprintPath)) {
                nbtData = net.minecraft.nbt.NbtIo.readCompressed(
                        in,
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap()
                );
            }

            if (nbtData == null) {
                LOGGER.warn("Failed to read NBT data from: {}", blueprintPath.toAbsolutePath());
                return null;
            }

            SableBlueprint blueprint = SableBlueprint.load(nbtData);
            if (blueprint == null) {
                LOGGER.warn("Failed to convert NBT to SableBlueprint from: {}", blueprintPath.toAbsolutePath());
                return null;
            }

            LOGGER.info("Successfully loaded blueprint from: {}", blueprintPath.toAbsolutePath());
            return blueprint;

        } catch (IOException e) {
            LOGGER.error("Error loading blueprint from: {}", relativePath, e);
            return null;
        }
    }
    //#endregion
}