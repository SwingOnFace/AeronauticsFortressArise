package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import java.util.Comparator;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.util.SableSubLevelUtil;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;

/**
 * AI 火控方块的“大脑”，负责所有决策逻辑（索敌、瞄准、开火时机等）。
 *
 * <p>方块实体 {@link AIFireControllerBlockEntity} 只提供“执行能力”
 * （装填弹药、开火、读取炮座状态、设置瞄准角度等），而“每个 tick 该做什么”
 * 由本类决定。编写真正的 AI 逻辑时，只需修改本类，无需改动方块实体。
 */
public class AIFireControllerBrain {
    public static final boolean DEBUG_ENABLED = true;

    /** 解算失败（目标不可达 / 装填失败）后的重试间隔，避免每 tick 重复解算。 */
    private static final int AIM_RETRY_TICKS = 10;

    /**
     * 射角下发后到真正击发之间的延迟（tick）。
     *
     * <p>炮座的俯仰角并非立即生效，需要给炮塔留出转动时间，
     * 因此设定射角后等待该延迟再开火，并在提前量中补偿同样的时间。
     */
    private static final int FIRE_DELAY_TICKS = 10;

    private final AIFireControllerBlockEntity controller;

    private BallisticsCalculator ballisticsCalculator;
    private String ballisticsProfile;
    private float accuracy = 0.8f;

    /** 距离上一次开炮剩余的 tick 数，归零后才解算弹道并开炮。 */
    private int fireCooldownTicks;

    /** 已下发瞄准角、等待炮塔转到位剩余的 tick 数；大于 0 时不开火。 */
    private int aimSettleTicks;

    public AIFireControllerBrain(AIFireControllerBlockEntity controller) {
        this.controller = controller;
    }

    public void init() {
        this.ballisticsProfile = null;
    }

    /**
     * AI 主逻辑入口，每个 tick 由方块实体调用一次。
     *
     * <p>每 tick 搜索射程内玩家；只有开炮冷却结束且锁定目标时，
     * 才按 CBC 炮弹物理参数解算弹道并装填、下发射角，
     * 再等待炮塔转动到位（{@link #FIRE_DELAY_TICKS}）后击发。
     */
    public void tick() {
        this.tickAi();
    }

    /** 索敌视野距离根据当前炮弹的物理参数估算。 */
    private float sightRange = 200.0f;

    public float getSightRange() {
        return this.sightRange;
    }

    public void setAccuracy(float accuracy) {
        this.accuracy = Math.max(0.0f, Math.min(1.0f, accuracy));
    }

    /** 丢弃冷却与待击发状态（例如动力中断后，避免用旧射角开炮）。 */
    public void resetCombatState() {
        this.fireCooldownTicks = 0;
        this.aimSettleTicks = 0;
    }

    private void tickAi() {
        if (this.controller.getLevel() == null || this.controller.getLevel().isClientSide) {
            return;
        }

        Level level = this.controller.getLevel();
        this.refreshBallistics(level);
        if (this.ballisticsCalculator == null) {
            return;
        }
        BlockPos controllerPos = this.controller.getBlockPos();

        // 炮口在子空间中的局部坐标（位于控制器上方一格）。
        Vec3 cannonLocal = Vec3.atCenterOf(controllerPos.above());

        // 若控制器位于 Sable 子空间（飞行器）上，则通过子空间的位姿
        // 把炮口坐标变换到主世界，并在主世界中搜索玩家。
        Pose3dc pose = SableSubLevelUtil.findPose(level, controllerPos);
        Vec3 cannonGlobal = pose == null ? cannonLocal : pose.transformPosition(cannonLocal);

        AABB searchBox = new AABB(cannonGlobal, cannonGlobal)
                .inflate(this.sightRange);
        Player target = level.getEntitiesOfClass(Player.class, searchBox).stream()
                .filter(player -> player.isAlive() && !player.isSpectator())
                .min(Comparator.comparingDouble(player -> player.distanceToSqr(cannonGlobal)))
                .orElse(null);

        this.controller.setTargetDetected(target != null);

        // 瞄准角已下发，等待炮塔转动到位后再击发（俯仰角不会立即生效）。
        if (this.aimSettleTicks > 0) {
            this.aimSettleTicks--;
            if (this.aimSettleTicks > 0) {
                return;
            }
            if (target == null || !this.controller.fireCannon()) {
                this.fireCooldownTicks = AIM_RETRY_TICKS;
            } else {
                this.fireCooldownTicks = this.controller.getSupplyInterval();
            }
            return;
        }

        // 冷却未结束时只做索敌，不解算弹道、不调整炮口、不装填。
        if (this.fireCooldownTicks > 0) {
            this.fireCooldownTicks--;
            return;
        }
        if (target == null) {
            return;
        }

        // 冷却结束且已锁定目标，此时才解算弹道并执行瞄准 / 装填 / 发射。
        // 把玩家的世界坐标与速度变换回子空间局部坐标，
        // 使弹道计算在炮口所在的坐标系中进行（大炮瞄准角相对子空间本体）。
        Vec3 targetLocal = pose == null
                ? target.position()
                : pose.transformPositionInverse(target.position());
        Vec3 targetVelocityLocal = pose == null
                ? target.getDeltaMovement()
                : pose.transformNormalInverse(target.getDeltaMovement());

        Vec3 offset = targetLocal.subtract(cannonLocal);
        BallisticsCalculator.AimSolution aim = this.ballisticsCalculator.solve(
                offset.x, offset.z, offset.y,
                targetVelocityLocal.x, targetVelocityLocal.y, targetVelocityLocal.z,
                FIRE_DELAY_TICKS);

        boolean canFire = aim.reachable() && Double.isFinite(aim.pitch()) && Double.isFinite(aim.yaw())
                && Double.isFinite(aim.flightTicks());
        /*if (BallisticsCalculator.isDebugEnabled()) {
            this.logShotCalculation(target, controllerPos, cannonLocal, cannonGlobal, pose,
                    targetLocal, targetVelocityLocal, offset, aim, canFire);
        }*/

        if (canFire) {
            double maxError = 10.0 * (1.0 - this.accuracy);
            double yawError = (level.random.nextDouble() * 2.0 - 1.0) * maxError;
            double pitchError = (level.random.nextDouble() * 2.0 - 1.0) * maxError;
            if (BallisticsCalculator.isDebugEnabled()) {
                AeronauticsFortressArise.LOGGER.info(
                        "[Ballistics] fire target={} aimApplied=({}, {}) err=({}, {})",
                        target.getName().getString(),
                        fmt(aim.yaw() + yawError), fmt(aim.pitch() + pitchError),
                        fmt(yawError), fmt(pitchError));
            }
            if (!this.controller.supplyCannon()) {
                // 装填失败（未检测到炮座、弹药配置错误等），稍后重新解算并重试。
                this.fireCooldownTicks = AIM_RETRY_TICKS;
                return;
            }
            if (!this.controller.setCannonAim(
                    (float) (aim.yaw() + yawError), (float) (aim.pitch() + pitchError))) {
                // 炮座尚未就绪（未运行 / 无 contraption），稍后重试。
                this.fireCooldownTicks = AIM_RETRY_TICKS;
                return;
            }
            // 俯仰角需要时间生效，延迟 FIRE_DELAY_TICKS 后再击发。
            this.aimSettleTicks = FIRE_DELAY_TICKS;
        } else {
            // 目标不可达：稍后再解算，避免每 tick 重复弹道计算。
            this.fireCooldownTicks = AIM_RETRY_TICKS;
        }
    }

    /**
     * 打印一次完整解算的诊断信息：坐标链路、直瞄角、解算角、隐含初速。
     *
     * <p>{@code impliedSpeed} 由「水平距离 / (flightTicks · cos pitch)」反推，
     * 用于核对解算器使用的 {@code launchSpeed} 是否与实际发射参数一致。
     * {@code losPitch/losYaw} 是「不看重力、直瞄」的角度，若 aim 与 los 几乎相同，
     * 说明飞行时间极短（初速偏大）或目标几乎与炮口等高。
     */
    private void logShotCalculation(Player target, BlockPos controllerPos, Vec3 cannonLocal, Vec3 cannonGlobal,
                                    Pose3dc pose, Vec3 targetLocal, Vec3 targetVelocityLocal,
                                    Vec3 offset, BallisticsCalculator.AimSolution aim, boolean canFire) {
        Vec3 targetWorld = target.position();
        Vec3 worldOffset = targetWorld.subtract(cannonGlobal);
        Vec3 worldOffsetInLocal = pose == null ? worldOffset : pose.transformNormalInverse(worldOffset);
        Vec3 targetRoundTrip = pose == null ? targetLocal : pose.transformPosition(targetLocal);
        Vec3 cannonRoundTrip = pose == null ? cannonLocal : pose.transformPosition(cannonLocal);
        Vec3 targetEyeWorld = target.getEyePosition();
        Vec3 targetEyeLocal = pose == null ? targetEyeWorld : pose.transformPositionInverse(targetEyeWorld);
        double horizontal = Math.hypot(offset.x, offset.z);
        double losPitch = Math.toDegrees(Math.atan2(offset.y, Math.max(horizontal, 0.001)));
        double losYaw = Math.toDegrees(Math.atan2(-offset.x, offset.z));
        AeronauticsFortressArise.LOGGER.info(
                "[Ballistics] {} target={} controllerPos={} chained={} pose={} scale={} "
                        + "cannonLocal={} cannonGlobal={} cannonRoundTrip={} targetWorld={} targetRoundTrip={} "
                        + "targetLocal={} targetEyeWorld={} targetEyeLocal={} worldOffset={} worldOffsetInLocal={} "
                        + "offset={} horizontal={} los=(yaw {}, pitch {}) "
                        + "aim=(yaw {}, pitch {}) flightTicks={} solvedDist={} solvedHeight={} "
                        + "heightError={} impliedSpeed={} reachable={} {}",
                canFire ? "solve-fire" : "solve-hold",
                target.getName().getString(),
                controllerPos,
                pose == null ? "world(no-sublevel)" : "sublevel",
                describePose(pose),
                pose == null ? "(1.000, 1.000, 1.000)" : "(" + fmt(pose.scale().x()) + ", "
                        + fmt(pose.scale().y()) + ", " + fmt(pose.scale().z()) + ")",
                vec(cannonLocal), vec(cannonGlobal), vec(cannonRoundTrip),
                vec(targetWorld), vec(targetRoundTrip), vec(targetLocal),
                vec(targetEyeWorld), vec(targetEyeLocal),
                vec(worldOffset), vec(worldOffsetInLocal),
                vec(offset), fmt(horizontal),
                fmt(losYaw), fmt(losPitch),
                fmt(aim.yaw()), fmt(aim.pitch()), fmt(aim.flightTicks()),
                fmt(aim.solvedDistance()), fmt(aim.solvedHeight()),
                fmt(aim.heightError()), fmt(aim.impliedSpeed()), aim.reachable(),
                this.ballisticsCalculator.describeParameters()
                        + " targetVelWorld=" + vec(target.getDeltaMovement())
                        + " targetVelLocal=" + vec(targetVelocityLocal));
    }

    private static String vec(Vec3 value) {
        return "(" + fmt(value.x) + ", " + fmt(value.y) + ", " + fmt(value.z) + ")";
    }

    private static String describePose(Pose3dc pose) {
        if (pose == null) {
            return "none";
        }
        return String.format(java.util.Locale.ROOT,
                "{pos=(%.2f,%.2f,%.2f) rotPoint=(%.2f,%.2f,%.2f) scale=(%.3f,%.3f,%.3f)}",
                pose.position().x(), pose.position().y(), pose.position().z(),
                pose.rotationPoint().x(), pose.rotationPoint().y(), pose.rotationPoint().z(),
                pose.scale().x(), pose.scale().y(), pose.scale().z());
    }

    /** 上次求解是否可行（保留用于后续节流/状态判断逻辑）。 */
    private boolean wasReachable = true;

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private void refreshBallistics(Level level) {
        String profile = this.controller.getProjectileId() + ':' + this.controller.getPropellantPower()
                + ':' + level.dimension().location();
        if (profile.equals(this.ballisticsProfile)) {
            return;
        }
        this.ballisticsProfile = profile;

        var stack = this.controller.getProjectile();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof ProjectileBlock<?> projectileBlock)) {
            AeronauticsFortressArise.LOGGER.warn("Cannot calculate ballistics for configured projectile {}",
                    this.controller.getProjectileId());
            this.ballisticsCalculator = null;
            return;
        }
        AbstractBigCannonProjectile projectile = projectileBlock.getProjectile(level, stack);
        if (projectile == null) {
            AeronauticsFortressArise.LOGGER.warn("CBC did not create a projectile entity for {}",
                    this.controller.getProjectileId());
            this.ballisticsCalculator = null;
            return;
        }
        this.ballisticsCalculator = BallisticsCalculator.forProjectile(
                this.controller.getPropellantPower(), projectile, level);
        projectile.discard();
        this.sightRange = (float) this.ballisticsCalculator.getMaxRange();
    }

}
