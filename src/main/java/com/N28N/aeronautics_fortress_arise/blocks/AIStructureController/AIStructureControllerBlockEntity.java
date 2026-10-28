package com.N28N.aeronautics_fortress_arise.blocks.AIStructureController;

import com.N28N.aeronautics_fortress_arise.config.AIStructureControllerConfig;
import com.N28N.aeronautics_fortress_arise.util.AIStructureChunkLoadManager;
import com.N28N.aeronautics_fortress_arise.util.AIStructureControllerIndexData;
import com.N28N.aeronautics_fortress_arise.util.AIStructureRewardHandler;
import com.N28N.aeronautics_fortress_arise.util.SableSubLevelUtil;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * AI 结构控制器方块实体。
 *
 * <p>负责：
 * <ul>
 *     <li>首次初始化时记录 home 坐标（x/z 为放置位置，y 为结构最高方块 + 悬浮高度）</li>
 *     <li>每 tick 调用大脑执行亚空间回正</li>
 *     <li>保存实例级悬浮高度，可右键配置面板修改</li>
 * </ul>
 */
public class AIStructureControllerBlockEntity extends BlockEntity implements GeoBlockEntity {

    public static final Map<ControllerKey, AIStructureControllerBlockEntity> hashToInstances = new HashMap<>();
    private static final Map<ControllerKey, Boolean> FORCED_CHUNK_STATES = new HashMap<>();

    //#region Constants and state
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_LOAD_RANGE = 8;

    /** 首次初始化前的等待 tick（等子空间装配稳定）。 */
    private static final int INIT_COOLDOWN = 20;

    private int initCooldown = INIT_COOLDOWN;

    /** 是否已初始化 home 坐标。 */
    private boolean homeInitialized;

    private Vector3d homePos;

    /** 悬浮高度（格），实例级配置，未设置时使用全局默认。 */
    private int hoverHeight = -1;

    /** 奖励质量（1~5），实例级配置，未设置时使用全局默认。 */
    private int rewardQuality = -1;

    /** 奖励方块数量范围，实例级配置，未设置时使用全局默认。 */
    private int rewardMinCount = -1;
    private int rewardMaxCount = -1;

    /** 加载范围（区块），由结构模板配置写入。 */
    private int loadRange = DEFAULT_LOAD_RANGE;
    @Nullable
    private ControllerKey registeredKey;

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    /** 回正决策逻辑。 */
    private final AIStructureControllerBrain brain = new AIStructureControllerBrain(this);
    //#endregion

    //#region Lifecycle
    public AIStructureControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animationCache;
    }

    public void tick() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        this.registerInstance();

        // 1. 首次初始化：等子空间稳定后记录 home。
        if (!this.homeInitialized) {
            if (this.initCooldown > 0) {
                this.initCooldown--;
                return;
            }
            this.tryInitHome(serverLevel);
            return;
        }

        // 2. 每 tick 回正。
        this.brain.tick();
    }

    /**
     * 初始化 home 坐标。
     * <p>
     * x/z 取方块当前放置位置（子空间局部坐标）；
     * y 取子空间 plot 局部包围盒的最高方块 Y + 悬浮高度（不做射线检测，
     * 避免误检测到同一子空间上的其他方块，因为控制器不一定在最下面）。
     */
    private void tryInitHome(ServerLevel serverLevel) {
        ServerSubLevel subLevel = SableSubLevelUtil.findSubLevel(serverLevel, this.worldPosition);
        if (subLevel == null) {
            // 不在子空间上：不工作，也不初始化。
            LOGGER.debug("[AIStructCtrl] {} 不在子空间上，跳过初始化", this.worldPosition);
            return;
        }

        int homeX = this.worldPosition.getX();
        int homeZ = this.worldPosition.getZ();

        // 用 plot 的局部包围盒（getBoundingBox 为 block-aligned 局部包围盒）取最高 Y。
        var bounds = subLevel.getPlot().getBoundingBox();
        int topY;
        if (bounds == null || bounds.maxY() < bounds.minY()) {
            // 无有效包围盒时退回方块自身位置。
            topY = this.worldPosition.getY();
            LOGGER.warn("[AIStructCtrl] {} 子空间包围盒无效，退回方块位置", this.worldPosition);
        } else {
            topY = bounds.maxY();
        }

        int hover = this.getHoverHeight();

        // 记录 home 逻辑坐标经初始位姿变换后的世界坐标（结构应停的位置）。
        Pose3dc pose = subLevel.logicalPose();
        Vector3d homeLocalVec = new Vector3d(homeX + 0.5, topY + 0.5, homeZ + 0.5);
        this.homePos = pose.transformPosition(homeLocalVec, new Vector3d());
        this.homePos.add(0.0, hover, 0.0); // 悬浮高度

        this.homeInitialized = true;
        this.setChanged();
        LOGGER.info("[AIStructCtrl] {} 初始化完成：home={}（结构顶 {} + 悬浮 {}）worldHome={}",
                this.worldPosition, this.homePos, topY, hover, this.homePos);
    }
    //#endregion

    //#region Instance configuration
    public int getHoverHeight() {
        if (this.hoverHeight <= 0) {
            return AIStructureControllerConfig.HOVER_HEIGHT.get();
        }
        return this.hoverHeight;
    }

    public void setHoverHeight(int hoverHeight) {
        this.hoverHeight = Math.max(1, hoverHeight);
    }

    public int getRewardQuality() {
        if (this.rewardQuality <= 0) {
            return AIStructureControllerConfig.DEFAULT_REWARD_QUALITY.get();
        }
        return this.rewardQuality;
    }

    public void setRewardQuality(int rewardQuality) {
        this.rewardQuality = Math.max(1, Math.min(AIStructureControllerConfig.REWARD_QUALITY_MAX, rewardQuality));
    }

    public int getRewardMinCount() {
        return this.rewardMinCount < 0
                ? AIStructureControllerConfig.REWARD_MIN_COUNT.get()
                : this.rewardMinCount;
    }

    public void setRewardMinCount(int rewardMinCount) {
        this.rewardMinCount = Math.max(0, Math.min(64, rewardMinCount));
    }

    public int getRewardMaxCount() {
        return this.rewardMaxCount < 0
                ? AIStructureControllerConfig.REWARD_MAX_COUNT.get()
                : this.rewardMaxCount;
    }

    public void setRewardMaxCount(int rewardMaxCount) {
        this.rewardMaxCount = Math.max(0, Math.min(64, rewardMaxCount));
    }

    public int getLoadRange() {
        return this.loadRange;
    }

    public void setLoadRange(int loadRange) {
        this.loadRange = Math.max(0, Math.min(1024, loadRange));
        this.setChanged();
        if (this.registeredKey != null && this.level instanceof ServerLevel serverLevel) {
            AIStructureControllerIndexData.get(serverLevel)
                    .put(this.registeredKey.dimension(), this.registeredKey.position(), this.loadRange);
        }
    }

    public void registerInstance() {
        if (!(this.level instanceof ServerLevel serverLevel) || this.isRemoved()) {
            return;
        }
        ControllerKey key = new ControllerKey(serverLevel.dimension(), this.worldPosition.asLong());
        if (key.equals(this.registeredKey)) {
            return;
        }
        this.unregisterInstance();
        this.registeredKey = key;
        hashToInstances.put(key, this);
        AIStructureControllerIndexData.get(serverLevel).put(key.dimension(), key.position(), this.loadRange);
    }

    public void unregisterInstance() {
        ControllerKey key = this.registeredKey;
        if (key == null) {
            return;
        }
        hashToInstances.remove(key, this);
        FORCED_CHUNK_STATES.remove(key);
        this.registeredKey = null;
    }

    /**
     * 控制器被真正移除（方块被破坏）时，从持久化索引中删除。
     * 区块卸载只调用 {@link #unregisterInstance()}，索引保留以便远处重新加载。
     */
    public void removeFromIndex() {
        if (this.level instanceof ServerLevel serverLevel) {
            AIStructureControllerIndexData.get(serverLevel)
                    .remove(serverLevel.dimension(), this.worldPosition.asLong());
            AIStructureChunkLoadManager.setControllerChunkLoaded(serverLevel, this.worldPosition, false);
            FORCED_CHUNK_STATES.remove(
                    new ControllerKey(serverLevel.dimension(), this.worldPosition.asLong()));
        }
    }

    public static void updateChunkLoading(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % 20 != 0) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            Map<Long, Integer> indexed = AIStructureControllerIndexData.get(level).get(level.dimension());
            if (indexed.isEmpty()) {
                continue;
            }
            List<ServerPlayer> players = level.players();
            indexed.forEach((position, loadRange) -> {
                ControllerKey key = new ControllerKey(level.dimension(), position);
                BlockPos controllerPos = BlockPos.of(position);
                ChunkPos controllerChunk = new ChunkPos(controllerPos);
                long rangeSquared = (long) loadRange * loadRange;
                boolean shouldForce = false;
                for (ServerPlayer player : players) {
                    ChunkPos playerChunk = player.chunkPosition();
                    long dx = (long) controllerChunk.x - playerChunk.x;
                    long dz = (long) controllerChunk.z - playerChunk.z;
                    if (dx * dx + dz * dz <= rangeSquared) {
                        shouldForce = true;
                        break;
                    }
                }

                Boolean wasForced = FORCED_CHUNK_STATES.get(key);
                if (wasForced == null || wasForced != shouldForce) {
                    AIStructureChunkLoadManager.setControllerChunkLoaded(level, controllerPos, shouldForce);
                    FORCED_CHUNK_STATES.put(key, shouldForce);
                }
            });
        }
    }

    @Override
    public void setRemoved() {
        this.unregisterInstance();
        super.setRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        this.registerInstance();
    }

    public boolean isHomeInitialized() {
        return this.homeInitialized;
    }

    public AIStructureControllerBrain getBrain() {
        return this.brain;
    }
    //#endregion

    //#region Persistence
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.level == null || !this.level.hasNeighborSignal(this.worldPosition)) {
            tag.putBoolean("HomeInitialized", this.homeInitialized);
            if (this.homePos != null) {
                tag.putDouble("HomeX", this.homePos.x);
                tag.putDouble("HomeY", this.homePos.y);
                tag.putDouble("HomeZ", this.homePos.z);
            }
        }
        if (this.hoverHeight > 0) {
            tag.putInt("HoverHeight", this.hoverHeight);
        }
        if (this.rewardQuality > 0) {
            tag.putInt("RewardQuality", this.rewardQuality);
        }
        if (this.rewardMinCount >= 0) {
            tag.putInt("RewardMinCount", this.rewardMinCount);
        }
        if (this.rewardMaxCount >= 0) {
            tag.putInt("RewardMaxCount", this.rewardMaxCount);
        }
        tag.putInt("LoadRange", this.loadRange);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.homeInitialized = tag.getBoolean("HomeInitialized");
        this.homePos = null;
        if (!this.homeInitialized) {
            this.initCooldown = INIT_COOLDOWN;
        }
        if (tag.contains("HomeX") && tag.contains("HomeY") && tag.contains("HomeZ")) {
            this.homePos = new Vector3d(
                    tag.getDouble("HomeX"), tag.getDouble("HomeY"), tag.getDouble("HomeZ"));
        }
        if (tag.contains("HoverHeight")) {
            this.hoverHeight = tag.getInt("HoverHeight");
        }
        if (tag.contains("RewardQuality")) {
            this.rewardQuality = tag.getInt("RewardQuality");
        }
        if (tag.contains("RewardMinCount")) {
            this.rewardMinCount = Math.max(0, Math.min(64, tag.getInt("RewardMinCount")));
        }
        if (tag.contains("RewardMaxCount")) {
            this.rewardMaxCount = Math.max(0, Math.min(64, tag.getInt("RewardMaxCount")));
        }
        if (tag.contains("LoadRange")) {
            this.loadRange = Math.max(0, Math.min(1024, tag.getInt("LoadRange")));
        } else {
            this.loadRange = DEFAULT_LOAD_RANGE;
        }
        if (this.homeInitialized && this.homePos == null) {
            LOGGER.warn("[AIStructCtrl] {} 已标记 home 初始化但没有已保存坐标，将在结构稳定后重新初始化",
                    this.worldPosition);
            this.homeInitialized = false;
            this.initCooldown = INIT_COOLDOWN;
        }
    }
    //#endregion

    public Vector3d getHomePos() {
        return this.homePos;
    }

    public void setHomePos(Vector3d homePos) {
        this.homePos = new Vector3d(homePos);
        this.homeInitialized = true;
        this.setChanged();
    }

    public void scheduleControllerRemoval(Player player) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        AIStructureRewardHandler.scheduleControllerRemoval(
                serverLevel, player, this.worldPosition, this.getRewardQuality(),
                this.getRewardMinCount(), this.getRewardMaxCount(), this.homePos);
    }

    public record ControllerKey(ResourceKey<Level> dimension, long position) {
    }
}
