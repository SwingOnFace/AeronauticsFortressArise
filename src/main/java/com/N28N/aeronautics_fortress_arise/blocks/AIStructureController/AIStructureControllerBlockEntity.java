package com.N28N.aeronautics_fortress_arise.blocks.AIStructureController;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.N28N.aeronautics_fortress_arise.config.AIStructureControllerConfig;
import com.N28N.aeronautics_fortress_arise.util.SableSubLevelUtil;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private static final Map<UUID, Boolean> FORCED_CHUNK_STATES = new HashMap<>();

    //#region Constants and state
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_LOAD_RANGE = 8;

    /** 是否已初始化 home 坐标。 */
    private boolean homeInitialized = false;

    /** home 世界坐标；请通过 {@link #getHomePos()} 访问，首次读取时自动初始化。 */
    @Nullable
    private Vector3d homePos;

    /** 奖励质量（1~5），实例级配置，未设置时使用全局默认。 */
    private int rewardQuality = -1;

    /** 奖励方块数量范围，实例级配置，未设置时使用全局默认。 */
    private int rewardMinCount = -1;
    private int rewardMaxCount = -1;

    /** 加载范围（区块），由结构模板配置写入。 */
    private int loadRange = DEFAULT_LOAD_RANGE;

    /** 控制器持久身份，创建时由存档顺序分配；尚未分配时为 null。 */
    @Nullable
    private UUID controllerId;

    /** 当前已注册进静态表的 UUID。 */
    @Nullable
    private UUID registeredId;

    /** 本次拆除是否已触发奖励生成（playerWillDestroy 与 onRemove 可能同时到达）。 */
    private boolean destroyHandled = false;

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    /** 回正决策逻辑。 */
    private final AIStructureControllerBrain brain = new AIStructureControllerBrain(this);
    //#endregion

    //#region Lifecycle
    public AIStructureControllerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.registerInstance();
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }
    
    public void onPlacedFromBlueprint(ServerLevel level, BlockPos storagePos) {
        CreateControllerId(level);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animationCache;
    }

    public void tick() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        // 1. 首次读取会尝试初始化 home；不在子空间上时返回 null，稍后重试。
        if (this.getHomePos() == null) {
            return;
        }

        // 2. 每 tick 回正。
        this.brain.tick();

        // 3. 给结构上的玩家施加区域效果。
        this.HandleAreaEffect();
    }

    /**
     * 初始化 home 坐标。
     * <p>
     * x/z 取方块当前放置位置（子空间局部坐标）；
     * y 取子空间 plot 局部包围盒的最高方块 Y + 悬浮高度（不做射线检测，
     * 避免误检测到同一子空间上的其他方块，因为控制器不一定在最下面）。
     *
     * @return 是否成功初始化
     */
    private boolean initHome(ServerLevel serverLevel) {
        ServerSubLevel subLevel = SableSubLevelUtil.findSubLevel(serverLevel, this.worldPosition);
        if (subLevel == null) {
            // 不在子空间上：不工作，也不初始化。
            LOGGER.debug("[AIStructCtrl] {} 不在子空间上，跳过初始化", this.worldPosition);
            return false;
        }

        // 记录 home 逻辑坐标经初始位姿变换后的世界坐标（结构应停的位置）。
        Pose3dc pose = subLevel.logicalPose();
        Vector3d homeLocalVec = new Vector3d(
                this.worldPosition.getX(), this.worldPosition.getY(), this.worldPosition.getZ());
        Vector3d home = pose.transformPosition(homeLocalVec, new Vector3d());
        this.setHomePos(home);
        LOGGER.info("[AIStructCtrl] {} 初始化完成：home={}", this.worldPosition, home);
        return true;
    }
    //#endregion

    //#region AreaEffect
    private static final int EFFECT_INTERVAL_TICKS = 20;
    /** 挖掘疲劳持续时间：2 秒。 */
    private static final int MINING_FATIGUE_DURATION_TICKS = 40;
    /** 挖掘疲劳等级 3 对应 amplifier 2（amplifier = 等级 − 1）。 */
    private static final int MINING_FATIGUE_AMPLIFIER = 2;
    private int effectTickCounter = 0;
    private void HandleAreaEffect()
    {
        if (++this.effectTickCounter < EFFECT_INTERVAL_TICKS)
        {
            return;
        }
        this.effectTickCounter = 0;

        ServerSubLevel sublevel = SableSubLevelUtil.findSubLevel(this.level, this.worldPosition);
        LOGGER.debug("[AIStructCtrl] {} 施加区域效果：sublevel={}", this.worldPosition, sublevel);
        if (sublevel == null)
        {
            return;
        }
        List<? extends Player> players = this.level.players();
        List<ServerPlayer> serverPlayers = players.stream()
                .filter(player -> player instanceof ServerPlayer)
                .map(player -> (ServerPlayer) player)
                .toList();
        LOGGER.debug("[AIStructCtrl] {} 找到的玩家：{}", this.worldPosition, serverPlayers.toString());
        for (ServerPlayer player : serverPlayers)
        {
            if (player.level() == this.level &&
                SableSubLevelUtil.isPlayerInSubLevel(player, sublevel))
            {
                player.addEffect(new MobEffectInstance(
                        MobEffects.DIG_SLOWDOWN, MINING_FATIGUE_DURATION_TICKS, MINING_FATIGUE_AMPLIFIER));
                LOGGER.debug("[AIStructCtrl] {} 已对玩家 {} 施加挖掘疲劳", this.worldPosition,
                        player.getGameProfile().getName());
            }
        }
    }
    ////#endregion

    //#region Instance configuration

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
        this.rewardMinCount = Math.max(0, Math.min(AIStructureControllerConfig.REWARD_COUNT_MAX, rewardMinCount));
    }

    public int getRewardMaxCount() {
        return this.rewardMaxCount < 0
                ? AIStructureControllerConfig.REWARD_MAX_COUNT.get()
                : this.rewardMaxCount;
    }

    public void setRewardMaxCount(int rewardMaxCount) {
        this.rewardMaxCount = Math.max(0, Math.min(AIStructureControllerConfig.REWARD_COUNT_MAX, rewardMaxCount));
    }

    public int getLoadRange() {
        return this.loadRange;
    }

    public void setLoadRange(int loadRange) {
        this.loadRange = Math.max(0, Math.min(AIStructureControllerConfig.LOAD_RANGE_MAX, loadRange));
        this.setChanged();
        this.syncPositionToIndex();
    }

    public void registerInstance() {
        if (!(this.level instanceof ServerLevel serverLevel) || this.isRemoved()) {
            return;
        }
        UUID id = this.getOrCreateControllerId(serverLevel);
        if (id.equals(this.registeredId)) {
            return;
        }
        AIStructureControllerIndexData index = AIStructureControllerIndexData.get(serverLevel);
        if (isIdTakenByOther(id, serverLevel, index)) {
            // 蓝图/结构方块复制过来的控制器可能带着相同的 UUID，重新分配。
            this.controllerId = index.allocateId();
            id = this.controllerId;
            this.setChanged();
        }
        this.unregisterInstance();
        this.registeredId = id;
        this.syncPositionToIndex();
    }

    private boolean isIdTakenByOther(
            UUID id, ServerLevel serverLevel, AIStructureControllerIndexData index) {
        AIStructureControllerIndexData.ControllerEntry existing = index.get(id);
        if (existing == null) {
            return false;
        }
        Vector3d home = this.getHomePos();
        Vector3d expected = home != null
                ? home
                : new Vector3d(this.worldPosition.getX(), this.worldPosition.getY(), this.worldPosition.getZ());
        return !existing.dimension().equals(serverLevel.dimension())
                || !existing.position().equals(expected);
    }

    /**
     * 返回控制器 UUID；尚未分配时向存档申请一个新的（分配进度同步落盘）。
     */
    private UUID getOrCreateControllerId(ServerLevel serverLevel) {
        if (this.controllerId == null) {
            CreateControllerId(serverLevel);
        }
        return this.controllerId;
    }
    private UUID CreateControllerId(ServerLevel serverLevel) {
        this.controllerId = AIStructureControllerIndexData.get(serverLevel).allocateId();
        this.setChanged();
        return this.controllerId;
    }

    public void unregisterInstance() {
        UUID id = this.registeredId;
        if (id == null) {
            return;
        }
        FORCED_CHUNK_STATES.remove(id);
        this.registeredId = null;
    }

    /**
     * 控制器被真正移除（方块被破坏）时，从持久化索引中删除。
     */
    public void removeFromIndex() {
        if (this.level instanceof ServerLevel serverLevel) {
            AIStructureControllerIndexData index = AIStructureControllerIndexData.get(serverLevel);
            UUID id = this.controllerId != null ? this.controllerId : this.registeredId;
            if (id != null) {
                index.remove(id);
                FORCED_CHUNK_STATES.remove(id);
            } else {
                // 旧存档里的控制器可能还没有 UUID，按位置兜底清理。
                index.removeAt(serverLevel.dimension(), this.ticketOwnerPos());
            }
            AIStructureChunkLoadManager.setControllerChunkLoaded(serverLevel, this.ticketOwnerPos(), false);
        }
    }

    /** 区块票据的 owner 位置：home 坐标取整；home 未知时退回方块位置。 */
    private BlockPos ticketOwnerPos() {
        Vector3d home = this.getHomePos();
        return home == null
                ? this.worldPosition
                : AIStructureControllerIndexData.ownerPosOf(home);
    }

    public static void updateChunkLoading(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % 20 != 0) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            List<AIStructureControllerIndexData.ControllerEntry> indexed =
                    AIStructureControllerIndexData.get(level).entries(level.dimension());
            if (indexed.isEmpty()) {
                continue;
            }
            List<ServerPlayer> players = level.players();
            indexed.forEach(entry -> {
                BlockPos controllerPos = entry.ownerPos();
                ChunkPos controllerChunk = new ChunkPos(controllerPos);
                long rangeSquared = (long) entry.loadRange() * entry.loadRange();
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

                Boolean wasForced = FORCED_CHUNK_STATES.get(entry.id());
                if (wasForced == null || wasForced != shouldForce) {
                    AIStructureChunkLoadManager.setControllerChunkLoaded(level, controllerPos, shouldForce);
                    FORCED_CHUNK_STATES.put(entry.id(), shouldForce);
                }
            });
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        this.registerInstance();
    }

    public boolean isHomeInitialized() {
        return this.homeInitialized;
    }

    /** 控制器 UUID；尚未分配时返回 null。 */
    @Nullable
    public UUID getControllerId() {
        return this.controllerId;
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
            // 直接读字段写入：序列化阶段不触发 home 自动初始化。
            tag.putBoolean("HomeInitialized", this.homeInitialized);
            if (this.homePos != null) {
                tag.putDouble("HomeX", this.homePos.x);
                tag.putDouble("HomeY", this.homePos.y);
                tag.putDouble("HomeZ", this.homePos.z);
            }
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
        if (this.controllerId != null) {
            tag.putUUID("ControllerId", this.controllerId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 旧存档没有该字段：保持 null，等服务端首次注册时再分配。
        this.controllerId = tag.hasUUID("ControllerId") ? tag.getUUID("ControllerId") : null;
        this.homeInitialized = tag.getBoolean("HomeInitialized");
        this.homePos = null;
        if (tag.contains("HomeX") && tag.contains("HomeY") && tag.contains("HomeZ")) {
            this.homePos = new Vector3d(
                    tag.getDouble("HomeX"), tag.getDouble("HomeY"), tag.getDouble("HomeZ"));
        }
        if (tag.contains("RewardQuality")) {
            this.rewardQuality = tag.getInt("RewardQuality");
        }
        if (tag.contains("RewardMinCount")) {
            this.rewardMinCount = Math.max(0, Math.min(AIStructureControllerConfig.REWARD_COUNT_MAX, tag.getInt("RewardMinCount")));
        }
        if (tag.contains("RewardMaxCount")) {
            this.rewardMaxCount = Math.max(0, Math.min(AIStructureControllerConfig.REWARD_COUNT_MAX, tag.getInt("RewardMaxCount")));
        }
        if (tag.contains("LoadRange")) {
            this.loadRange = Math.max(0, Math.min(AIStructureControllerConfig.LOAD_RANGE_MAX, tag.getInt("LoadRange")));
        } else {
            this.loadRange = DEFAULT_LOAD_RANGE;
        }
        if (this.homeInitialized && this.homePos == null) {
            LOGGER.warn("[AIStructCtrl] {} 已标记 home 初始化但没有已保存坐标，将在结构稳定后重新初始化",
                    this.worldPosition);
            this.homeInitialized = false;
        }
    }
    //#endregion

    /**
     * home 世界坐标；尚未初始化时自动尝试初始化。
     *
     * @return home 坐标；不在 Sable 子空间上或世界尚未就绪时返回 null，下次读取会重试
     */
    @Nullable
    public Vector3d getHomePos() {
        if (!this.homeInitialized && this.level instanceof ServerLevel serverLevel) {
            this.initHome(serverLevel);
        }
        return this.homePos;
    }

    /**
     * 设置 home 世界坐标（传 null 表示需要重新初始化），并同步到持久化索引。
     */
    public void setHomePos(@Nullable Vector3d homePos) {
        this.homePos = homePos == null ? null : new Vector3d(homePos);
        this.homeInitialized = homePos != null;
        this.setChanged();
        this.syncPositionToIndex();
    }

    /** 把当前 home 坐标写入持久化索引；尚未注册或 home 未知时跳过。 */
    private void syncPositionToIndex() {
        if (this.registeredId == null || this.homePos == null
                || !(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        AIStructureControllerIndexData.get(serverLevel).put(
                this.registeredId, serverLevel.dimension(), this.homePos, this.loadRange);
    }

    public void scheduleControllerRemoval(Player player) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            LOGGER.debug("[AIStructCtrl] {} 拆除处理跳过：level 不是 ServerLevel（level={}）",
                    this.worldPosition, this.level);
            return;
        }
        LOGGER.info("[AIStructCtrl] {} 排队拆除处理：player={} quality={} count={}..{} home={}",
                this.worldPosition, player == null ? "null" : player.getGameProfile().getName(),
                this.getRewardQuality(), this.getRewardMinCount(), this.getRewardMaxCount(), this.homePos);
        AIStructureRewardHandler.scheduleControllerRemoval(
                serverLevel, player, this.worldPosition, this.getRewardQuality(),
                this.getRewardMinCount(), this.getRewardMaxCount(), this.getHomePos());
    }

    /**
     * 标记本次拆除已处理，避免 playerWillDestroy 与 onRemove 重复触发奖励生成。
     * <p>
     * 状态保存在方块实体上（而不是 Block 实例字段）：方块对象是全存档共享的单例，
     * 只有方块实体级别的状态才是“每个控制器”独立的。
     *
     * @return 本次调用是否首次标记
     */
    public boolean markDestroyHandled() {
        if (this.destroyHandled) {
            LOGGER.debug("[AIStructCtrl] {} 拆除已处理过，忽略重复调用", this.worldPosition);
            return false;
        }
        this.destroyHandled = true;
        return true;
    }

    /**
     * 兜底拆除处理。
     *
     * <p>{@code onRemove} 依赖 {@code level.getBlockEntity(pos)} 才能拿到控制器，
     * 但方块被爆炸/弹射物等非玩家途径清除时，这一刻方块实体可能已经不在区块里
     * （不同 level 包装、区块被提前卸载等），导致拆除流程整体失效、子空间留在原地。
     * 方块实体自身被移除时一定会走到这里，因此在 {@link #setRemoved()} 里再判断一次：
     * 只有“方块已经不再是控制器”才算被拆除；区块卸载时方块状态仍是控制器，会被跳过。
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (!(this.level instanceof ServerLevel serverLevel) || this.destroyHandled) {
            return;
        }
        // 直接读所在区块的方块状态：区块已卸载时 getBlockState 会返回 VOID_AIR，
        // 会被误判成“方块消失”。拿不到区块说明只是卸载/未加载，跳过。
        LevelChunk chunk = serverLevel.getChunkSource()
                .getChunkNow(this.worldPosition.getX() >> 4, this.worldPosition.getZ() >> 4);
        if (chunk == null) {
            LOGGER.debug("[AIStructCtrl] {} 所在区块已卸载，跳过拆除兜底判断", this.worldPosition);
            return;
        }
        BlockState currentState = chunk.getBlockState(this.worldPosition);
        if (currentState.is(AllBlocks.AI_STRUCTURE_CONTROLLER.get())) {
            // 方块还在：只是区块卸载 / 方块实体重建，不是拆除。
            return;
        }
        LOGGER.info("[AIStructCtrl] {} 由 setRemoved 兜底触发拆除处理：currentState={}",
                this.worldPosition, currentState);
        if (this.markDestroyHandled()) {
            this.removeFromIndex();
            this.scheduleControllerRemoval(null);
        }
    }
}
