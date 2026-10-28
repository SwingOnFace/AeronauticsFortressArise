package com.N28N.aeronautics_fortress_arise.util;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.N28N.aeronautics_fortress_arise.AllBlocks.VaultCategory;
import com.N28N.aeronautics_fortress_arise.blocks.OreVein.OreVeinBlock;
import com.N28N.aeronautics_fortress_arise.config.AIStructureControllerConfig;
import com.mojang.logging.LogUtils;
import com.tterrag.registrate.util.entry.BlockEntry;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 结构控制器的奖励方块生成器。
 *
 * <p>AI 结构控制器被移除时，先删除其所属 Sable 子空间，再在主世界生成奖励方块。
 * 奖励方块从奖励池中随机选取：战利品保险库（3 类别 × 5 等级）与矿脉（按配置）。
 */
public final class AIStructureRewardHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, List<PendingRemoval>> PENDING_REMOVALS = new HashMap<>();

    private AIStructureRewardHandler() {
    }

    //#region Reward generation
    public static void scheduleControllerRemoval(
            ServerLevel level, Player player, BlockPos controllerPos, int quality, int minCount, int maxCount, Vector3d home) {
        PendingRemoval pending = new PendingRemoval(
                level, player, controllerPos.immutable(), quality, minCount, maxCount,
                home == null ? null : new Vec3(home.x, home.y, home.z));
        PENDING_REMOVALS.computeIfAbsent(level.getServer(), server -> new ArrayList<>()).add(pending);
        debug("已排队控制器拆除处理：controller={} quality={} rewardCount={}..{} home={}",
                controllerPos, quality, minCount, maxCount, pending.homePos());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        List<PendingRemoval> pendingRemovals = PENDING_REMOVALS.remove(event.getServer());
        if (pendingRemovals == null) {
            return;
        }
        for (PendingRemoval pending : pendingRemovals) {
            handleControllerRemoved(pending);
        }
    }

    private static void handleControllerRemoved(PendingRemoval pending) {
        ServerLevel level = pending.level();
        Player player = pending.player();
        BlockPos controllerPos = pending.controllerPos();
        if (level.getBlockState(controllerPos).is(AllBlocks.AI_STRUCTURE_CONTROLLER.get())) {
            debug("控制器 {} 在延后处理时仍存在，可能拆除已取消，跳过删除和奖励", controllerPos);
            return;
        }
        SubLevel subLevel = SableSubLevelUtil.findSubLevel(level, controllerPos);
        if (subLevel == null) {
            debug("玩家拆除的控制器 {} 已不属于 Sable 子空间，跳过删除和奖励", controllerPos);
            return;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            LOGGER.error("[AIStructReward] 无法删除控制器 {} 所属子空间：Sable SubLevelContainer 不存在",
                    controllerPos);
            return;
        }

        UUID subLevelId = subLevel.getUniqueId();
        RemovedControllerSnapshot snapshot = new RemovedControllerSnapshot(
                controllerPos, pending.quality(), pending.minCount(), pending.maxCount(), pending.homePos());
        Vec3 basePos = getRewardBasePos(subLevel, snapshot);
        teleportPlayerToRewardArea(level, subLevel, player, basePos);
        debug("控制器被移除，先删除所属子空间：subLevel={} uuid={} controller={} quality={} home={}",
                subLevel.getName(), subLevelId, controllerPos, snapshot.quality(), snapshot.homePos());
        container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        debug("所属子空间已删除，开始生成奖励：subLevel={} uuid={}",
                subLevel.getName(), subLevelId);
        generateRewards(level, subLevel, snapshot, basePos);
    }

    private static Vec3 getRewardBasePos(SubLevel subLevel, RemovedControllerSnapshot snapshot) {
        Vec3 home = snapshot.homePos();
        if (home == null) {
            LOGGER.warn("[AIStructReward] 控制器 {} 的 home 坐标为空，将使用子空间位姿原点作为奖励基准；"
                            + "请检查控制器是否刚加载或初始化失败",
                    snapshot.controllerPos());
            // home 未初始化：退回子空间原点变换到主世界的位置。
            Pose3dc pose = subLevel.logicalPose();
            return pose.transformPosition(Vec3.ZERO);
        }
        return home;
    }

    private static void teleportPlayerToRewardArea(
            ServerLevel level, SubLevel subLevel, Player player, Vec3 basePos) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !serverPlayer.isAlive()
                || serverPlayer.serverLevel() != level) {
            return;
        }

        Vec3 playerLocalPos = subLevel.logicalPose().transformPositionInverse(serverPlayer.position());
        SubLevel playerSubLevel = SableSubLevelUtil.findSubLevel(level, BlockPos.containing(playerLocalPos));
        if (playerSubLevel == null || !playerSubLevel.getUniqueId().equals(subLevel.getUniqueId())) {
            return;
        }

        int x = (int) Math.floor(basePos.x);
        int z = (int) Math.floor(basePos.z);
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
        serverPlayer.teleportTo(x + 0.5, y, z + 0.5);
        debug("将位于子空间 {} 的玩家 {} 传送到奖励区域 {}",
                subLevel.getName(), serverPlayer.getGameProfile().getName(), new BlockPos(x, y, z));
    }

    private static void generateRewards(
            ServerLevel level, SubLevel subLevel, RemovedControllerSnapshot snapshot, Vec3 basePos) {
        RandomSource random = level.getRandom();
        int quality = snapshot.quality();
        BlockPos controllerPos = snapshot.controllerPos();
        Vec3 home = snapshot.homePos();

        // 3. 从配置范围内抽取奖励总数，再为每个奖励随机选择方块。
        int minCount = snapshot.minCount();
        int maxCount = snapshot.maxCount();
        if (minCount > maxCount) {
            LOGGER.warn("[AIStructReward] 奖励数量配置无效：最小值 {} 大于最大值 {}", minCount, maxCount);
            return;
        }
        List<Block> pool = buildRewardPool(quality);
        int rewardCount = random.nextInt(minCount, maxCount + 1);
        debug("开始生成奖励：subLevel={} controller={} quality={} home={} basePos={} countRange={}..{} "
                        + "chosenCount={} poolSize={} pool={}",
                subLevel.getName(), controllerPos, quality, home, basePos,
                minCount, maxCount, rewardCount, pool.size(),
                pool.stream().map(BuiltInRegistries.BLOCK::getKey).toList());
        if (rewardCount == 0) {
            debug("跳过奖励：随机数量为 0");
            return;
        }
        if (pool.isEmpty()) {
            LOGGER.warn("[AIStructReward] 奖励方块池为空：quality={} includeVaults={} includeOreVeins={}，不生成奖励",
                    quality, AIStructureControllerConfig.REWARD_INCLUDE_VAULTS.get(),
                    AIStructureControllerConfig.REWARD_INCLUDE_ORE_VEINS.get());
            return;
        }

        // 4. 在基准 xz 附近为每个奖励随机选址。
        int radius = Math.max(0, AIStructureControllerConfig.REWARD_SPAWN_OFFSET_RADIUS.get());
        for (int rewardIndex = 0; rewardIndex < rewardCount; rewardIndex++) {
            Block rewardBlock = pool.get(random.nextInt(pool.size()));
            int x = (int) Math.floor(basePos.x) + random.nextInt(-radius, radius + 1);
            int z = (int) Math.floor(basePos.z) + random.nextInt(-radius, radius + 1);

            int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos spawnPos = new BlockPos(x, topY + 1, z);
            debug("奖励尝试 {}/{}：block={} candidate={} surfaceY={} initialState={} radius={}",
                    rewardIndex + 1, rewardCount, BuiltInRegistries.BLOCK.getKey(rewardBlock), spawnPos,
                    topY, BuiltInRegistries.BLOCK.getKey(level.getBlockState(spawnPos).getBlock()), radius);
            while (spawnPos.getY() < level.getMaxBuildHeight() - 1
                    && !level.getBlockState(spawnPos).canBeReplaced()) {
                spawnPos = spawnPos.above();
            }
            if (spawnPos.getY() >= level.getMaxBuildHeight() - 1
                    && !level.getBlockState(spawnPos).canBeReplaced()) {
                LOGGER.warn("[AIStructReward] 无法在 {} 放置奖励方块，已跳过", spawnPos);
                continue;
            }

            boolean placed = level.setBlock(spawnPos, rewardBlock.defaultBlockState(), Block.UPDATE_ALL);
            boolean matches = level.getBlockState(spawnPos).is(rewardBlock);
            if (!placed || !matches) {
                LOGGER.warn("[AIStructReward] 放置奖励失败：pos={} block={} setBlockReturned={} actualBlock={}",
                        spawnPos, BuiltInRegistries.BLOCK.getKey(rewardBlock), placed,
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(spawnPos).getBlock()));
                continue;
            }
            int oreLootQuality = rewardBlock instanceof OreVeinBlock veinBlock
                    ? veinBlock.getLootQuality()
                    : quality;
            debug("奖励放置成功：subLevel={} pos={} block={} lootQuality={}",
                    subLevel.getName(), spawnPos, BuiltInRegistries.BLOCK.getKey(rewardBlock), oreLootQuality);
        }
    }
    //#endregion

    //#region Reward pool
    /**
     * 构建奖励方块池：战利品保险库（类别 × 等级范围）+ 矿脉（按配置矿石列表）。
     * <p>
     * 奖励质量越高，进入池中的保险库等级越高（等级 ≤ 质量），奖励越好。
     */
    private static List<Block> buildRewardPool(int quality) {
        List<Block> pool = new ArrayList<>();

        if (AIStructureControllerConfig.REWARD_INCLUDE_VAULTS.get()) {
            int minLevel = Math.max(1, AIStructureControllerConfig.REWARD_VAULT_MIN_LEVEL.get());
            int maxLevel = Math.min(AllBlocks.VAULT_LEVELS, AIStructureControllerConfig.REWARD_VAULT_MAX_LEVEL.get());
            maxLevel = Math.min(maxLevel, quality);
            if (minLevel <= maxLevel) {
                for (VaultCategory category : VaultCategory.values()) {
                    for (int level = minLevel; level <= maxLevel; level++) {
                        pool.add(AllBlocks.getVault(category, level).get());
                    }
                }
            }
        }

        if (AIStructureControllerConfig.REWARD_INCLUDE_ORE_VEINS.get()) {
            for (BlockEntry<OreVeinBlock> entry : AllBlocks.ORE_VEINS.values()) {
                OreVeinBlock vein = entry.get();
                if (vein.getLootQuality() <= quality) {
                    pool.add(vein);
                }
            }
        }

        return pool;
    }
    //#endregion

    private static void debug(String message, Object... args) {
        if (AIStructureControllerConfig.REWARD_DEBUG_LOG.get()) {
            LOGGER.info("[AIStructReward][DEBUG] " + message, args);
        }
    }

    private record RemovedControllerSnapshot(
            BlockPos controllerPos, int quality, int minCount, int maxCount, Vec3 homePos) {
    }

    private record PendingRemoval(
            ServerLevel level, Player player, BlockPos controllerPos, int quality, int minCount, int maxCount, Vec3 homePos) {
    }
}
