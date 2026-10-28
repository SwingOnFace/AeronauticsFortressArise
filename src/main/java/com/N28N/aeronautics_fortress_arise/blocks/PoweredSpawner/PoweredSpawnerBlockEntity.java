package com.N28N.aeronautics_fortress_arise.blocks.PoweredSpawner;

import com.mojang.logging.LogUtils;
import com.N28N.aeronautics_fortress_arise.util.SableSubLevelUtil;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class PoweredSpawnerBlockEntity extends KineticBlockEntity implements GeoBlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RawAnimation RUNNING_ANIMATION = RawAnimation.begin().thenLoop("running");

    private static final float MAX_ENERGY = 4096F;
    private static final float ENERGY_PER_SPAWN = 512F;
    private static final float ENERGY_PER_TICK_AT_FULL_SPEED = 256.0F/20F; // 256/s * 1s/20t = 12.8/tick
    private static final int SPAWN_COOLDOWN = 10;

    /** 玩家必须进入该范围内，刷怪笼才会开始刷怪（格）。 */
    private static final int PLAYER_DETECTION_RANGE = 24;

    /** 该刷怪笼同时存在的存活生物数量上限。 */
    private static final int MAX_SPAWNED_MOBS = 8;

    private float energy;
    private int spawnCooldown;

    /** 要生成的生物类型，可通过刷怪蛋右键修改，默认生成僵尸。 */
    private EntityType<?> mobType = EntityType.ZOMBIE;

    /** 该刷怪笼生成且仍存活的生物列表，用于限制刷怪数量。 */
    private final List<Mob> spawnedMobs = new ArrayList<>();
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    public PoweredSpawnerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "running", state -> this.getSpeed() == 0
                ? PlayState.STOP
                : state.setAndContinue(RUNNING_ANIMATION)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animationCache;
    }

    @Override
    public void initialize() {
        super.initialize();
        if (!this.level.isClientSide) {
            this.energy = MAX_ENERGY;
            this.spawnCooldown = 0;
            this.setChanged();
        }
    }

    public void tick() {
        //#region 异常检查
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        if (!(this.level instanceof ServerLevel serverLevel)) {
            LOGGER.warn("[PoweredSpawner] {} tick 时 level 不是 ServerLevel，跳过", this.worldPosition);
            return;
        }
        //#endregion

        //#region 刷怪笼能量
        int speed = Math.abs((int) this.getSpeed());
        if (speed == 0) {
            return;
        }

        float before = this.energy;
        this.energy = Math.min(MAX_ENERGY,
                this.energy + Math.max(1, speed * ENERGY_PER_TICK_AT_FULL_SPEED / 256));
        if (this.energy != before) {
            LOGGER.debug("[PoweredSpawner] {} 充能 {} -> {} (speed={})",
                    this.worldPosition, before, this.energy, speed);
        }
        this.setChanged();

        if (this.spawnCooldown > 0) {
            this.spawnCooldown--;
            return;
        }
        //#endregion

        //#region 尝试刷怪
        //计算center位置，若在Sable子空间中则转换为主世界坐标
        Pose3dc pose = SableSubLevelUtil.findPose(serverLevel, this.worldPosition);
        Vec3 center = pose == null
                ? Vec3.atCenterOf(this.worldPosition)
                : pose.transformPosition(Vec3.atCenterOf(this.worldPosition));

        if (this.energy < ENERGY_PER_SPAWN) {
            LOGGER.debug("[PoweredSpawner] {} 能量不足，无法生成 (energy={}, need={})",
                    center, this.energy, ENERGY_PER_SPAWN);
            return;
        }

        // 1. 附近 24 格内没有玩家时，不生成生物。
        if (!this.isPlayerNearby(serverLevel, center, pose)) {
            LOGGER.debug("[PoweredSpawner] {} 附近 {} 格内没有玩家，暂停生成",
                    center, PLAYER_DETECTION_RANGE);
            return;
        }

        // 2. 清理已死亡或被移除的生物，检查刷怪上限。
        this.cleanupSpawnedMobs();
        if (this.spawnedMobs.size() >= MAX_SPAWNED_MOBS) {
            LOGGER.debug("[PoweredSpawner] {} 已达到刷怪上限 {}，暂停生成",
                    center, MAX_SPAWNED_MOBS);
            return;
        }

        if (this.spawnMob(serverLevel)) {
            this.energy -= ENERGY_PER_SPAWN;
            this.spawnCooldown = SPAWN_COOLDOWN;
            LOGGER.debug("[PoweredSpawner] {} 生成成功，剩余能量 {}，冷却 {} tick，当前存活 {} 只",
                    center, this.energy, this.spawnCooldown, this.spawnedMobs.size());
            this.setChanged();
        } else {
            LOGGER.debug("[PoweredSpawner] {} 生成失败（未生成任何生物）", this.worldPosition);
        }
        //#endregion
    }

    /**
     * 在方块上方生成配置的生物。仅在成功生成时返回 true。
     * <p>
     * 若方块位于 Sable 子空间（飞行器）上，生成位置会从子空间逻辑坐标
     * 变换到主世界坐标——生物必须生成在主世界才能被玩家看到和交互。
     */
    private boolean spawnMob(ServerLevel serverLevel) {
        BlockPos center = this.worldPosition.above();

        // 子空间兼容：把生成点变换到主世界坐标。
        Pose3dc pose = SableSubLevelUtil.findPose(serverLevel, this.worldPosition);
        Vec3 spawnPos = pose == null
                ? Vec3.atCenterOf(center).add(0, 1, 0)
                : pose.transformPosition(Vec3.atCenterOf(center).add(0, 1, 0));

        Entity entity = this.mobType.create(serverLevel);
        if (!(entity instanceof Mob mob)) {
            LOGGER.debug("[PoweredSpawner] {} 实体类型 {} 无法作为生物生成",
                    this.worldPosition, BuiltInRegistries.ENTITY_TYPE.getKey(this.mobType));
            return false;
        }
        mob.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, serverLevel.random.nextFloat() * 360.0F, 0.0F);
        if (serverLevel.addFreshEntity(mob)) {
            // 记录到列表，用于限制同时存在的生物数量。
            this.spawnedMobs.add(mob);
            return true;
        }
        return false;
    }

    /**
     * 判断方块周围 {@link #PLAYER_DETECTION_RANGE} 格内是否有存活的玩家。
     * <p>
     * 若方块位于 Sable 子空间（飞行器）上，方块中心从逻辑坐标变换到
     * 主世界坐标再与玩家测距，避免逻辑坐标（巨大偏移）导致距离判断错误。
     */
    private boolean isPlayerNearby(ServerLevel serverLevel, Vec3 center, Pose3dc pose) {
        double rangeSq = (double) PLAYER_DETECTION_RANGE * PLAYER_DETECTION_RANGE;

        for (Player player : serverLevel.players()) {
            if (!player.isAlive()
                    || player.distanceToSqr(center.x, center.y, center.z) > rangeSq) {
                continue;
            }
            // 只有刷怪笼能“看到”玩家（连线无遮挡，Sable 结构也算遮挡物）才刷怪。
            if (!this.hasLineOfSight(serverLevel, player, center, pose)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 判断刷怪笼与玩家之间是否存在无遮挡的视线。
     * <p>
     * 若刷怪笼位于 Sable 子空间（飞行器）上：子空间方块以逻辑坐标存储在宿主世界中，
     * 因此只在子空间局部坐标做一次射线，命中飞行器自身结构即视为遮挡
     * （Sable 结构同样算作遮挡物）；不做主世界射线，因为逻辑坐标与主世界坐标
     * 相差巨大偏移，主世界射线只会误命中子空间方块自身。
     * <p>
     * 若刷怪笼在普通主世界：直接对主世界方块做射线检测。
     */
    private boolean hasLineOfSight(ServerLevel serverLevel, Player player, Vec3 centerWorld, Pose3dc pose) {
        Vec3 eye = player.getEyePosition();

        // 不在子空间上：只做主世界射线。
        if (pose == null) {
            return !this.isRayBlocked(serverLevel, centerWorld, eye);
        }

        // 在子空间上：把玩家眼睛变换到局部坐标，检测飞行器自身结构。
        Vec3 fromLocal = Vec3.atCenterOf(this.worldPosition);
        Vec3 eyeLocal = pose.transformPositionInverse(eye);
        return !this.isRayBlocked(serverLevel, fromLocal, eyeLocal);
    }

    /**
     * 射线检测 from→to 是否被方块遮挡。
     * <p>起点从方块中心沿射线方向外移 1.5 格（确保完全离开自身方块所在格，
     * 避免 {@code level.clip} 的体素遍历在起点仍位于自身方块内时把自身方块
     * 作为第一个命中返回）。clip 只返回第一个命中，若起点未离开自身方块，
     * 后续真正的遮挡方块将永远无法被检测到。
     */
    private boolean isRayBlocked(ServerLevel serverLevel, Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from);
        double len = dir.length();
        if (len < 1.0e-4) {
            return false;
        }
        // 沿射线方向外移 4 格，四格内无论遮挡生成
        Vec3 start = from.add(dir.scale(4 / len));

        ClipContext context = new ClipContext(start, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                CollisionContext.empty());
        BlockHitResult hit = serverLevel.clip(context);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        Vec3 location = hit.getLocation();
        LOGGER.debug("[PoweredSpawner] {} 射线检测被方块 {} 遮挡，命中点 {}, 终点 {}",
                this.worldPosition, hit.getBlockPos(), location, to);
        return true;
    }

    /**
     * 从列表中移除已死亡、已被移除或已不在当前维度中的生物。
     */
    private void cleanupSpawnedMobs() {
        this.spawnedMobs.removeIf(mob -> !mob.isAlive() || mob.isRemoved() || mob.level() != this.level);
    }

    public EntityType<?> getMobType() {
        return this.mobType;
    }

    /**
     * 设置要生成的生物类型（由刷怪蛋右键触发），并同步到客户端。
     */
    public void setMobType(EntityType<?> mobType) {
        if (mobType == null) {
            return;
        }
        this.mobType = mobType;
        this.setChanged();
        this.notifyUpdate();
    }

    public float getEnergy() {
        return this.energy;
    }

    public float getMaxEnergy() {
        return MAX_ENERGY;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("Energy", this.energy);
        tag.putInt("SpawnCooldown", this.spawnCooldown);
        ResourceLocation mobId = BuiltInRegistries.ENTITY_TYPE.getKey(this.mobType);
        if (mobId != null) {
            tag.putString("MobType", mobId.toString());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.energy = Math.max(0, Math.min(MAX_ENERGY, tag.getFloat("Energy")));
        this.spawnCooldown = Math.max(0, tag.getInt("SpawnCooldown"));
        ResourceLocation mobId = ResourceLocation.tryParse(tag.getString("MobType"));
        if (mobId != null) {
            BuiltInRegistries.ENTITY_TYPE.getOptional(mobId).ifPresent(type -> this.mobType = type);
        }
    }

    @Override
    public float calculateStressApplied() {
        return 0f;
    }
}