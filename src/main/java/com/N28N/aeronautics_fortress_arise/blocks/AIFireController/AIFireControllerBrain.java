package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nullable;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.compat.cbc.CBCMunitionAdapter;
import com.N28N.aeronautics_fortress_arise.util.SableSubLevelUtil;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * AI 火控方块的“大脑”，负责所有决策逻辑（索敌、瞄准、开火时机等）。
 *
 * <p>方块实体 {@link AIFireControllerBlockEntity} 只提供“执行能力”
 * （装填弹药、开火、读取炮座状态、设置瞄准角度等），而“每个 tick 该做什么”
 * 由本类决定。编写真正的 AI 逻辑时，只需修改本类，无需改动方块实体。
 */
public class AIFireControllerBrain {
    public static final boolean DEBUG_ENABLED = false;

    /** 调试日志总开关：排查“完全没有行为”时打开。 */
    public static final boolean DEBUG_LOGGING = true;

    /** 调试状态日志的间隔（tick）。 */
    private static final int DEBUG_LOG_INTERVAL = 20;

    private int debugTick;

    /** 统一调试输出；兼容层（compat.cbc）也直接调用它。 */
    public static void debug(String format, Object... args) {
        if (DEBUG_LOGGING) {
            AeronauticsFortressArise.LOGGER.info("[AIFC] " + format, args);
        }
    }

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

    private UUID trackedTargetId;
    private Vec3 previousTargetLocalPosition;
    private long previousTargetSampleTick;
    /** 上一次是否检测到目标，用于只在状态变化时打印日志。 */
    private boolean lastTargetPresent;

    /** 弹道解算线程池：单个守护线程足够，避免抢占服务端 tick 时间。 */
    private static final ExecutorService SOLVER_EXECUTOR = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "AIFC-Ballistics");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            });

    /** 等待结果的最长时间（tick）：超时后丢弃并同步兜底，防止永久卡住。 */
    private static final int SOLVE_TIMEOUT_TICKS = 40;
    /** 目标位置变化超过该距离（格）时认为旧结果失效。 */
    private static final double SOLVE_STALE_DISTANCE = 2.0;
    /** 目标速度估算变化超过该值时认为旧结果失效（容差放宽，避免逐 tick 抖动导致反复重算）。 */
    private static final double SOLVE_STALE_VELOCITY = 1.0;

    /** 正在后台解算的任务；为 {@code null} 表示没有待处理请求。 */
    private AsyncSolve pendingSolve;

    public AIFireControllerBrain(AIFireControllerBlockEntity controller) {
        this.controller = controller;
    }

    public void init() {
        this.ballisticsProfile = null;
        this.clearTargetPositionHistory();
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
        this.cancelPendingSolve();
        this.clearTargetPositionHistory();
    }

    /** 取消后台解算，避免动力中断后仍用旧结果开炮。 */
    private void cancelPendingSolve() {
        AsyncSolve pending = this.pendingSolve;
        if (pending != null) {
            pending.future().cancel(false);
            this.pendingSolve = null;
        }
    }

    private void tickAi() {
        if (this.controller.getLevel() == null || this.controller.getLevel().isClientSide) {
            return;
        }

        Level level = this.controller.getLevel();
        this.refreshBallistics(level);
        if (++this.debugTick >= DEBUG_LOG_INTERVAL) {
            this.debugTick = 0;
            this.logState();
        }
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
        if ((target != null) != this.lastTargetPresent) {
            this.lastTargetPresent = target != null;
            debug("target {}", target == null
                    ? "LOST"
                    : "acquired: " + target.getName().getString()
                            + " dist=" + fmt(Math.sqrt(target.distanceToSqr(cannonGlobal))));
        }

        Vec3 targetLocal = target == null
                ? null
                : pose == null
                        ? target.position()
                        : pose.transformPositionInverse(target.position());
        Vec3 targetVelocityLocal = this.sampleTargetVelocity(target, targetLocal, level.getGameTime());

        // 瞄准角已下发，等待炮塔转动到位后再击发（俯仰角不会立即生效）。
        if (this.aimSettleTicks > 0) {
            this.aimSettleTicks--;
            if (this.aimSettleTicks > 0) {
                return;
            }
            boolean fired = target != null && this.controller.fireCannon();
            debug("aim settle done: target={} fired={}", target == null ? "none" : target.getName().getString(), fired);
            if (!fired) {
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
        // 用相邻 tick 的本地坐标位移估算目标速度，不使用实体速度或加速度。
        Vec3 offset = targetLocal.subtract(cannonLocal);
        BallisticsCalculator.AimSolution aim = this.pollOrSubmitSolve(
                level, target, offset, targetVelocityLocal);
        if (aim == null) {
            // 后台仍在解算：本 tick 不做其它动作，下一 tick 再取结果。
            return;
        }

        boolean canFire = aim.reachable();
        /*if (BallisticsCalculator.isDebugEnabled()) {
            this.logShotCalculation(target, controllerPos, cannonLocal, cannonGlobal, pose,
                    targetLocal, targetVelocityLocal, offset, aim, canFire);
        }*/

        if (canFire) {
            double maxError = 10.0 * (1.0 - this.accuracy);
            double yawError = (level.random.nextDouble() * 2.0 - 1.0) * maxError;
            double pitchError = (level.random.nextDouble() * 2.0 - 1.0) * maxError;
            debug("solve ok: target={} dist={} aim=(yaw {}, pitch {})",
                    target.getName().getString(), fmt(Math.hypot(offset.x, offset.z)),
                    fmt(aim.yaw() + yawError), fmt(aim.pitch() + pitchError));
            if (DEBUG_ENABLED) {
                AeronauticsFortressArise.LOGGER.info(
                        "[Ballistics] fire target={} aimApplied=({}, {}) err=({}, {})",
                        target.getName().getString(),
                        fmt(aim.yaw() + yawError), fmt(aim.pitch() + pitchError),
                        fmt(yawError), fmt(pitchError));
            }
            if (!this.controller.supplyCannon()) {
                // 装填失败（未检测到炮座、弹药配置错误等），稍后重新解算并重试。
                debug("supplyCannon FAILED, retry in {} ticks", AIM_RETRY_TICKS);
                this.fireCooldownTicks = AIM_RETRY_TICKS;
                return;
            }
            if (!this.controller.setCannonAim(
                    (float) (aim.yaw() + yawError), (float) (aim.pitch() + pitchError))) {
                // 炮座尚未就绪（未运行 / 无 contraption），稍后重试。
                debug("setCannonAim FAILED (mount not running / no contraption)");
                this.fireCooldownTicks = AIM_RETRY_TICKS;
                return;
            }
            // 俯仰角需要时间生效，延迟 FIRE_DELAY_TICKS 后再击发。
            this.aimSettleTicks = FIRE_DELAY_TICKS;
        } else {
            // 目标不可达：稍后再解算，避免每 tick 重复弹道计算。
            debug("solve not reachable: dist={} heightError={} maxRange={}",
                    fmt(Math.hypot(offset.x, offset.z)), fmt(aim.heightError()),
                    fmt(this.ballisticsCalculator.getMaxRange()));
            this.fireCooldownTicks = AIM_RETRY_TICKS;
        }
    }

    /**
     * 取得弹道解算结果；没有可用结果时提交后台解算并返回 {@code null}。
     *
     * <p>解算在后台线程进行，避免远距离弹道积分阻塞服务端 tick。目标位置变化过大、
     * 超时或抛出异常时丢弃旧任务并同步兜底一次，保证 AI 不会永久卡住。
     */
    @Nullable
    private BallisticsCalculator.AimSolution pollOrSubmitSolve(Level level, Player target,
                                                               Vec3 offset, Vec3 targetVelocity) {
        BallisticsCalculator calculator = this.ballisticsCalculator;
        if (calculator == null) {
            return null;
        }
        long gameTime = level.getGameTime();
        AsyncSolve pending = this.pendingSolve;
        if (pending != null) {
            boolean stale = pending.calculator() != calculator
                    || pending.isStale(offset, targetVelocity)
                    || gameTime - pending.submittedTick() > SOLVE_TIMEOUT_TICKS;
            if (stale) {
                pending.future().cancel(false);
                this.pendingSolve = null;
                pending = null;
                debug("solve discarded (stale or timed out)");
            }
        }
        if (pending == null) {
            submitSolve(calculator, offset, targetVelocity, gameTime);
            return null;
        }
        if (!pending.future().isDone()) {
            return null;
        }
        this.pendingSolve = null;
        try {
            return pending.future().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.fireCooldownTicks = AIM_RETRY_TICKS;
            return null;
        } catch (Exception exception) {
            debug("async solve failed ({}), falling back to synchronous solve", exception.toString());
            return this.solveSynchronously(calculator, offset, targetVelocity);
        }
    }

    private void submitSolve(BallisticsCalculator calculator, Vec3 offset, Vec3 velocity, long gameTime) {
        double dx = offset.x;
        double dz = offset.z;
        double dy = offset.y;
        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;
        try {
            CompletableFuture<BallisticsCalculator.AimSolution> future = CompletableFuture.supplyAsync(
                    () -> calculator.solve(dx, dz, dy, vx, vy, vz, FIRE_DELAY_TICKS),
                    SOLVER_EXECUTOR);
            this.pendingSolve = new AsyncSolve(calculator, dx, dz, dy, vx, vy, vz, gameTime, future);
            debug("solve submitted: dist={} height={}", fmt(Math.hypot(dx, dz)), fmt(dy));
        } catch (RejectedExecutionException exception) {
            // 线程池拒绝（关闭中）：同步兜底。
            this.pendingSolve = null;
            this.fireCooldownTicks = AIM_RETRY_TICKS;
        }
    }

    private BallisticsCalculator.AimSolution solveSynchronously(BallisticsCalculator calculator,
                                                                Vec3 offset, Vec3 velocity) {
        return calculator.solve(offset.x, offset.z, offset.y, velocity.x, velocity.y, velocity.z, FIRE_DELAY_TICKS);
    }

    /** 一次后台解算请求。 */
    private record AsyncSolve(BallisticsCalculator calculator,
                              double dx, double dz, double dy,
                              double vx, double vy, double vz,
                              long submittedTick,
                              CompletableFuture<BallisticsCalculator.AimSolution> future) {

        /** 目标移动过多时旧结果不再适用。 */
        boolean isStale(Vec3 offset, Vec3 velocity) {
            return Math.abs(this.dx - offset.x) > SOLVE_STALE_DISTANCE
                    || Math.abs(this.dz - offset.z) > SOLVE_STALE_DISTANCE
                    || Math.abs(this.dy - offset.y) > SOLVE_STALE_DISTANCE
                    || Math.abs(this.vx - velocity.x) > SOLVE_STALE_VELOCITY
                    || Math.abs(this.vy - velocity.y) > SOLVE_STALE_VELOCITY
                    || Math.abs(this.vz - velocity.z) > SOLVE_STALE_VELOCITY;
        }
    }

    /** 低频状态快照，便于判断“卡在哪一步”。 */
    private void logState() {
        if (!DEBUG_LOGGING) {
            return;
        }
        var cannon = this.controller.getMountedCannon();
        debug("state: mode={} compatible={} speed={} mountRunning={} cannon={} projectile={} propellant={} needsPropellant={} ballistics={} sight={} cooldown={} settle={}",
                this.controller.getCannonMode(),
                this.controller.isModeCompatible(cannon),
                this.controller.getControlSpeed(),
                this.controller.getCannonMountAbove() != null && this.controller.getCannonMountAbove().isRunning(),
                cannon == null ? "NONE" : cannon.getClass().getName(),
                this.controller.getProjectileId(),
                this.controller.getPropellantPower(),
                this.controller.needsPropellant(),
                this.ballisticsCalculator == null ? "NULL" : this.ballisticsCalculator.describeParameters(),
                fmt(this.sightRange), this.fireCooldownTicks, this.aimSettleTicks);
    }

    private Vec3 sampleTargetVelocity(Player target, Vec3 targetLocalPosition, long gameTime) {
        if (target == null || targetLocalPosition == null) {
            this.clearTargetPositionHistory();
            return Vec3.ZERO;
        }

        UUID targetId = target.getUUID();
        Vec3 velocity = Vec3.ZERO;
        if (targetId.equals(this.trackedTargetId) && this.previousTargetLocalPosition != null) {
            long elapsedTicks = gameTime - this.previousTargetSampleTick;
            if (elapsedTicks > 0) {
                velocity = targetLocalPosition.subtract(this.previousTargetLocalPosition)
                        .scale(1.0 / elapsedTicks);
            }
        }

        this.trackedTargetId = targetId;
        this.previousTargetLocalPosition = targetLocalPosition;
        this.previousTargetSampleTick = gameTime;
        return velocity;
    }

    private void clearTargetPositionHistory() {
        this.trackedTargetId = null;
        this.previousTargetLocalPosition = null;
        this.previousTargetSampleTick = 0L;
    }

    /**
     * 打印一次完整解算的诊断信息：坐标链路、直瞄角、解算角、隐含初速。
     *
     * <p>{@code impliedSpeed} 由「水平距离 / (flightTicks · cos pitch)」反推，
     * 用于核对解算器使用的 {@code launchSpeed} 是否与实际发射参数一致。
     * {@code losPitch/losYaw} 是「不看重力、直瞄」的角度，若 aim 与 los 几乎相同，
     * 说明飞行时间极短（初速偏大）或目标几乎与炮口等高。
     */
    /*private void logShotCalculation(Player target, BlockPos controllerPos, Vec3 cannonLocal, Vec3 cannonGlobal,
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
    }*/
    /*
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
    /*private boolean wasReachable = true;*/

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private void refreshBallistics(Level level) {
        // 炮口长度随大炮装配变化，一并纳入缓存键。
        double barrelLength = this.controller.getBarrelLength();
        String profile = this.controller.getProjectileId() + ':' + this.controller.getPropellantPower()
                + ':' + level.dimension().location() + ':' + fmt(barrelLength);
        if (profile.equals(this.ballisticsProfile)) {
            return;
        }
        this.ballisticsProfile = profile;
        debug("refreshBallistics: mode={} projectile={} power={} dim={} barrel={}",
                this.controller.getCannonMode(), this.controller.getProjectileId(),
                this.controller.getPropellantPower(), level.dimension().location(), fmt(barrelLength));

        var stack = this.controller.getProjectile();
        if (stack.isEmpty()) {
            debug("  -> projectile stack EMPTY (invalid config or incompatible with mode {})",
                    this.controller.getCannonMode());
            this.ballisticsCalculator = null;
            return;
        }
        CBCMunitionAdapter.ResolvedProfile resolved = CBCMunitionAdapter.resolveProfile(
                level, stack, this.controller.getPropellantPower());
        if (resolved.isEmpty()) {
            AeronauticsFortressArise.LOGGER.warn("Cannot calculate ballistics for configured projectile {}",
                    this.controller.getProjectileId());
            debug("  -> no usable launch profile (mode={}, item={})",
                    this.controller.getCannonMode(), stack.getItem());
            this.ballisticsCalculator = null;
            return;
        }
        this.ballisticsCalculator = BallisticsCalculator.forResolvedProfile(
                level, resolved.launchSpeed(), resolved.properties(), barrelLength);
        debug("  -> launchSpeed={} properties={} maxRange={}",
                fmt(resolved.launchSpeed()), resolved.properties(),
                fmt(this.ballisticsCalculator.getMaxRange()));
        this.sightRange = (float) this.ballisticsCalculator.getMaxRange();
    }

}
