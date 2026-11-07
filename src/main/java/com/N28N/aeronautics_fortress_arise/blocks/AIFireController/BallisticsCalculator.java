package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

/**
 * CBC 大口径炮弹的弹道解算器。
 *
 * <p>只按空气模拟：重力 = 弹药 {@code gravity} × 维度倍率，阻力 = 弹药 {@code drag} ×
 * 维度 {@code dragMultiplier}（可选平方阻力）。解算中不考虑水/岩浆/蜂蜜等流体的额外阻力，
 * 与 CBC 实际的 {@code getDragForce()} 相比会偏乐观。
 */
public final class BallisticsCalculator {
    private static final int MAX_FLIGHT_TICKS = 1200;
    private static final int LEAD_ITERATIONS = 4;
    private static final int PITCH_BISECTION_ITERATIONS = 14;
    private static final double MIN_SPEED = 0.05;
    private static final double MAX_PITCH = 89.0;
    /** 峰值仰角搜索精度（度）。 */
    private static final double PEAK_PITCH_TOLERANCE = 0.25;
    /** 二分终止精度（度）。0.05° 在 60 格处约 5 厘米。 */
    private static final double PITCH_TOLERANCE = 0.05;
    /** 命中高度误差容差（格）。超过即视为该距离打不到目标高度。 */
    private static final double REACH_TOLERANCE = 0.5;
    /** 弹道校准（格）：瞄准点整体下移的格数，用于修正实际弹着点偏高。 */
    private static final double AIM_HEIGHT_OFFSET = 0;
    /** 单次求高的积分步数上限（仅平方阻力路径使用），防止极端参数下死循环。 */
    private static final int MAX_INTEGRATION_STEPS = MAX_FLIGHT_TICKS;
    /** 解算缓存的 Profile 数量上限，超出后整体清空，避免长时间运行时无限增长。 */
    private static final int MAX_CACHED_PROFILES = 32;
    /** 按弹道参数共享的解算缓存，键为量化后的弹道参数。 */
    private static final ConcurrentHashMap<ProfileKey, ProfileCache> PROFILE_CACHES = new ConcurrentHashMap<>();
    /** 峰值仰角搜索的迭代次数：黄金分割每步把区间缩小到 0.618 倍。 */
    private static final int PEAK_PITCH_ITERATIONS = goldenSectionIterations(2 * MAX_PITCH, PEAK_PITCH_TOLERANCE);

    private static void debug(String message) {
        if (AIFireControllerBrain.DEBUG_ENABLED) {
            AeronauticsFortressArise.LOGGER.info("[Ballistics] {}", message);
        }
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private final double launchSpeed;
    private final double gravity;
    private final double drag;
    private final boolean quadraticDrag;
    private final double dragMultiplier;
    /**
     * 炮口长度（格）：从炮座枢轴（控制器上方格的中心）到炮管末端的距离。
     * <p>弹丸实际是从炮口出膛的，因此弹道积分要把这段长度作为初始位移：
     * 水平方向记入 {@code travelled}，垂直方向记入 {@code height}。
     */
    private final double barrelLength;
    /** 该解算器共享的缓存（最大射程 + 射角插值表）。 */
    private final ProfileCache profileCache;

    public BallisticsCalculator(double launchSpeed, BallisticPropertiesComponent properties,
                                double gravityMultiplier, double dragMultiplier, double barrelLength) {
        this.launchSpeed = Math.max(0.0, launchSpeed);
        this.gravity = properties.gravity() * gravityMultiplier;
        this.drag = properties.drag();
        this.quadraticDrag = properties.isQuadraticDrag();
        this.dragMultiplier = dragMultiplier;
        this.barrelLength = Math.max(0.0, barrelLength);
        if (PROFILE_CACHES.size() > MAX_CACHED_PROFILES) {
            PROFILE_CACHES.clear();
        }
        this.profileCache = PROFILE_CACHES.computeIfAbsent(
                ProfileKey.of(this.launchSpeed, this.gravity, this.drag, this.dragMultiplier,
                        this.quadraticDrag, this.barrelLength),
                key -> new ProfileCache());
    }

    /**
     * 用外部提供的初速与弹道参数构造解算器。
     *
     * <p>弹药的物理参数（弹道分量与初速）由 {@code CBCMunitionAdapter} 统一解析后传进来：
     * CBC 炮弹读取实体自身的 {@code getBallisticProperties()}，双管炮炮弹读取其数据包 JSON。
     * 这样不必依赖 CBC 的 munition 配置表（那对第三方弹药只会返回默认值）。
     */
    public static BallisticsCalculator forResolvedProfile(Level level, double launchSpeed,
                                                         BallisticPropertiesComponent properties,
                                                         double barrelLength) {
        var dimensionProperties = DimensionMunitionPropertiesHandler.getProperties(level);
        return new BallisticsCalculator(launchSpeed, properties,
                dimensionProperties.gravityMultiplier(), dimensionProperties.dragMultiplier(), barrelLength);
    }

    /**
     * 最大射程（格）。首次调用时才计算，并按弹道参数在全局缓存中共享，
     * 避免每次重建解算器都做一次昂贵的扫描。
     */
    public double getMaxRange() {
        double cached = this.profileCache.maxRange;
        if (Double.isNaN(cached)) {
            synchronized (this.profileCache) {
                cached = this.profileCache.maxRange;
                if (Double.isNaN(cached)) {
                    cached = this.calculateMaxRange();
                    this.profileCache.maxRange = cached;
                }
            }
        }
        return cached;
    }

    public double getBarrelLength() {
        return this.barrelLength;
    }

    /** 炮口相对炮座枢轴的水平初始位移（沿炮管方向）。 */
    private double muzzleTravelled(double pitchDegrees) {
        return this.barrelLength * Math.cos(Math.toRadians(pitchDegrees));
    }

    /** 炮口相对炮座枢轴的垂直初始位移（沿炮管方向，仰角向上为正）。 */
    private double muzzleHeight(double pitchDegrees) {
        return this.barrelLength * Math.sin(Math.toRadians(pitchDegrees));
    }

    /** 供调试日志显示当前物理参数。 */
    public String describeParameters() {
        return "launchSpeed=" + f(this.launchSpeed)
                + " gravity=" + f(this.gravity)
                + " drag=" + f(this.drag) + "x" + f(this.dragMultiplier)
                + (this.quadraticDrag ? " (quadratic)" : " (linear)")
                + " barrelLength=" + f(this.barrelLength)
                + " maxRange=" + f(this.getMaxRange());
    }

    public AimSolution solve(double dx, double dz, double dy,
                             double targetVelocityX, double targetVelocityY, double targetVelocityZ) 
    {
        return this.solve(dx, dz, dy, targetVelocityX, targetVelocityY, targetVelocityZ, 0.0);
    }

    /**
     * 求解射角，并在提前量上额外叠加 {@code extraLeadTicks}。
     *
     * <p>用于补偿“设定射角到真正击发之间的固定延迟”：炮塔转向需要若干 tick，
     * 这段时间内目标仍会移动，因此提前量要按「飞行时间 + 延迟」预测目标位置。
     */
    public AimSolution solve(double dx, double dz, double dy,
                             double targetVelocityX, double targetVelocityY, double targetVelocityZ,
                             double extraLeadTicks)
    {
        dy += AIM_HEIGHT_OFFSET;
        double leadTicks = Math.hypot(dx, dz) / Math.max(this.launchSpeed, MIN_SPEED) + extraLeadTicks;
        double pitch = Math.toDegrees(Math.atan2(dy, Math.max(Math.hypot(dx, dz), 0.001)));
        double flightTicks = Math.max(0.0, leadTicks - extraLeadTicks);
        boolean reachable = true;

        for (int i = 0; i < LEAD_ITERATIONS; i++) {
            double ledX = dx + targetVelocityX * leadTicks;
            double ledZ = dz + targetVelocityZ * leadTicks;
            leadTicks = Math.hypot(ledX, ledZ) / Math.max(this.launchSpeed, MIN_SPEED)
                    + extraLeadTicks;
            if(AIFireControllerBrain.DEBUG_ENABLED)
            {
                AeronauticsFortressArise.LOGGER.info(
                        "[Ballistics] iteration {}, targetVelocity=({}, {}, {}), ledX={} ledZ={} hypot={} leadTicks={}",
                        i, targetVelocityX, targetVelocityY, targetVelocityZ, ledX, ledZ, Math.hypot(ledX, ledZ), leadTicks);
            }
        }

        double ledX = dx + targetVelocityX * leadTicks;
        double ledY = dy + targetVelocityY * leadTicks;
        double ledZ = dz + targetVelocityZ * leadTicks;
        double horizontalDistance = Math.hypot(ledX, ledZ);
        double yaw = wrapDegrees(Math.toDegrees(Math.atan2(-ledX, ledZ)));
        PitchSolution pitchSolution = this.solvePitch(horizontalDistance, ledY, pitch);
        pitch = pitchSolution.pitch();
        double heightError = pitchSolution.error();
        double solvedDistance = horizontalDistance;
        double solvedHeight = ledY;
        if (!pitchSolution.reachable()) {
            reachable = false;
        } else if (Double.isFinite(pitchSolution.flightTicks())) {
            flightTicks = pitchSolution.flightTicks();
        } else {
            reachable = false;
        }

        double cosPitch = Math.cos(Math.toRadians(pitch));
        double impliedSpeed = flightTicks > 1.0e-6 && Math.abs(cosPitch) > 1.0e-6
                ? solvedDistance / (flightTicks * cosPitch)
                : Double.NaN;
        return new AimSolution(pitch, yaw, flightTicks, reachable, heightError,
                solvedDistance, solvedHeight, impliedSpeed);
    }

    /**
     * 求使弹道在水平距离 {@code horizontalDistance} 处到达 {@code targetHeight} 的仰角。
     *
     * <p>高度对仰角的曲线在 [-89°, 89°] 上单峰（先升后降），因此先黄金分割找峰值角，
     * 再在 [-89°, 峰值角] 这段单调递增区间上二分。相比原来的 Newton 迭代，
     * 不会在“打不到的高度”上被 ±15° 的修正夹断而反复震荡。
     *
     * <p>每次求高都是闭式解（O(1)），因此整次解算只需约 3 µs，无需再做结果缓存。
     */
    private PitchSolution solvePitch(double horizontalDistance, double targetHeight, double initialPitch) {
        boolean trace = AIFireControllerBrain.DEBUG_ENABLED;
        if (horizontalDistance < 12 || this.launchSpeed < MIN_SPEED) {
            double fallback = Math.max(-MAX_PITCH, Math.min(MAX_PITCH,
                    Math.toDegrees(Math.atan2(targetHeight, Math.max(horizontalDistance, 0.001)))));
            return new PitchSolution(fallback, 0.0, 0.0);
        }

        double peakPitch = this.findPeakPitch(horizontalDistance);
        double peakHeight = this.heightAtDistance(peakPitch, horizontalDistance);
        if (trace) {
            debug("solvePitch dist=" + f(horizontalDistance) + " targetHeight=" + f(targetHeight)
                    + " seedPitch=" + f(initialPitch)
                    + " peakPitch=" + f(peakPitch) + " peakHeight=" + f(peakHeight));
        }
        if (!Double.isFinite(peakHeight)) {
            return new PitchSolution(initialPitch, Double.POSITIVE_INFINITY, Double.NaN);
        }
        if (targetHeight >= peakHeight) {
            // 目标比该距离上弹道能达到的最高点还高：物理上打不到。
            return new PitchSolution(peakPitch, targetHeight - peakHeight, Double.NaN);
        }

        double lowPitch = -MAX_PITCH;
        double lowHeight = this.heightAtDistance(lowPitch, horizontalDistance);
        if (!Double.isFinite(lowHeight)) {
            lowHeight = Double.NEGATIVE_INFINITY;
        }
        if (targetHeight <= lowHeight) {
            return new PitchSolution(lowPitch, targetHeight - lowHeight, Double.NaN);
        }

        double low = lowPitch;
        double high = peakPitch;
        double pitch = high;
        for (int i = 0; i < PITCH_BISECTION_ITERATIONS && high - low > PITCH_TOLERANCE; i++) {
            double mid = (low + high) * 0.5;
            double midHeight = this.heightAtDistance(mid, horizontalDistance);
            if (!Double.isFinite(midHeight) || midHeight < targetHeight) {
                low = mid;
            } else {
                high = mid;
            }
            pitch = (low + high) * 0.5;
        }

        double height = this.heightAtDistance(pitch, horizontalDistance);
        double error = Double.isFinite(height) ? height - targetHeight : Double.POSITIVE_INFINITY;
        double ticks = Math.abs(error) <= REACH_TOLERANCE
                ? this.flightTime(pitch, horizontalDistance)
                : Double.NaN;
        /*if (trace) {
            debug("solvePitch bisection -> pitch=" + f(pitch) + " height=" + f(height)
                    + " error=" + f(error)
                    + " (low=" + f(low) + ", high=" + f(high) + ")");
        }*/
        return new PitchSolution(pitch, error, ticks);
    }

    /** 黄金分割搜索“在该水平距离处高度最高”的仰角。 */
    private double findPeakPitch(double horizontalDistance) {
        final double phi = 0.6180339887498949;
        double low = -MAX_PITCH;
        double high = MAX_PITCH;
        double c = high - (high - low) * phi;
        double d = low + (high - low) * phi;
        double fc = this.heightScore(c, horizontalDistance);
        double fd = this.heightScore(d, horizontalDistance);
        for (int i = 0; i < PEAK_PITCH_ITERATIONS && high - low > PEAK_PITCH_TOLERANCE; i++) {
            if (fc > fd) {
                high = d;
                d = c;
                fd = fc;
                c = high - (high - low) * phi;
                fc = this.heightScore(c, horizontalDistance);
            } else {
                low = c;
                c = d;
                fc = fd;
                d = low + (high - low) * phi;
                fd = this.heightScore(d, horizontalDistance);
            }
        }
        return (low + high) * 0.5;
    }

    private double heightScore(double pitchDegrees, double horizontalDistance) {
        double height = this.heightAtDistance(pitchDegrees, horizontalDistance);
        return Double.isFinite(height) ? height : Double.NEGATIVE_INFINITY;
    }

    /**
     * 求弹道在水平距离 {@code horizontalDistance} 处的高度。
     *
     * <p>线性阻力下使用闭式解（{@link #heightAtDistanceLinear}），复杂度 O(1)；
     * 平方阻力无法解析求和，退回逐 tick 积分。
     */
    private double heightAtDistance(double pitchDegrees, double horizontalDistance) {
        if (this.quadraticDrag) {
            return this.heightAtDistanceIterative(pitchDegrees, horizontalDistance);
        }
        return this.heightAtDistanceLinear(pitchDegrees, horizontalDistance);
    }

    /**
     * 线性阻力下的解析解。
     *
     * <p>阻力系数恒定时，逐 tick 递推是线性递推：速度每 tick 乘以 {@code decay = 1 - k}，
     * 因此水平位移与高度可以闭式求和，直接跳到目标 tick 前一步，再补最后的分数插值。
     * 结果与原逐 tick 迭代在浮点误差内完全一致（实测最大偏差 &lt; 1e-6 格），但无需遍历上百 tick。
     */
    private double heightAtDistanceLinear(double pitchDegrees, double horizontalDistance) {
        double k = this.linearDragDeceleration();
        double decay = 1.0 - k;
        double travelFactor = 1.0 - k * 0.5;
        double pitchRadians = Math.toRadians(pitchDegrees);
        double horizontalSpeed = this.launchSpeed * Math.cos(pitchRadians);
        double verticalSpeed = this.launchSpeed * Math.sin(pitchRadians);
        double travelled = this.muzzleTravelled(pitchDegrees);
        double height = this.muzzleHeight(pitchDegrees);

        double ticksToReach = this.ticksToReach(travelled, horizontalSpeed, horizontalDistance,
                decay, travelFactor);
        if (!Double.isFinite(ticksToReach)) {
            return Double.NaN;
        }
        // 留最后一步用逐 tick 复现，保证落点分数插值与原实现完全一致。
        int fullTicks = Math.max(0, (int) Math.floor(ticksToReach) - 1);
        if (fullTicks > 0) {
            if (Math.abs(decay - 1.0) < 1.0e-12) {
                double n = fullTicks;
                travelled += travelFactor * horizontalSpeed * n;
                height += travelFactor * (verticalSpeed * n + 0.5 * this.gravity * n * (n - 1.0))
                        + 0.5 * this.gravity * n;
                verticalSpeed += this.gravity * n;
            } else {
                double decayN = Math.pow(decay, fullTicks);
                double fixedPoint = this.gravity / k;
                double speedSum = (decayN - 1.0) / (decay - 1.0);
                travelled += travelFactor * horizontalSpeed * speedSum;
                height += travelFactor * ((verticalSpeed - fixedPoint) * speedSum + fixedPoint * fullTicks)
                        + 0.5 * this.gravity * fullTicks;
                horizontalSpeed *= decayN;
                verticalSpeed = (verticalSpeed - fixedPoint) * decayN + fixedPoint;
            }
        }

        for (int iteration = 0; iteration < 4; iteration++) {
            double nextTravelled = travelled + horizontalSpeed * (1.0 - k * 0.5);
            double nextHeight = height + verticalSpeed * (1.0 - k * 0.5) + this.gravity * 0.5;
            if (nextTravelled >= horizontalDistance) {
                double fraction = clamp01((horizontalDistance - travelled)
                        / Math.max(nextTravelled - travelled, 1.0e-9));
                return height + (nextHeight - height) * fraction;
            }
            travelled = nextTravelled;
            height = nextHeight;
            horizontalSpeed *= decay;
            verticalSpeed = verticalSpeed * decay + this.gravity;
        }
        // 理论上不会走到这里；保守地返回 NaN 表示不可达。
        return Double.NaN;
    }

    /**
     * 到达水平距离所需的 tick 数（可含小数），线性阻力下的闭式解。
     *
     * <p>由 {@code t_n = travelled + travelFactor · vx · (decay^n - 1) / (decay - 1)} 反解 n。
     */
    private double ticksToReach(double travelled, double horizontalSpeed, double horizontalDistance,
                                double decay, double travelFactor) {
        double needed = horizontalDistance - travelled;
        if (needed <= 0.0) {
            return 0.0;
        }
        if (horizontalSpeed <= 1.0e-6) {
            return Double.NaN;
        }
        double perTick = travelFactor * horizontalSpeed;
        if (perTick <= 1.0e-9) {
            return Double.NaN;
        }
        if (Math.abs(decay - 1.0) < 1.0e-12) {
            // 无阻力：匀速直线
            double ticks = needed / perTick;
            return ticks > MAX_FLIGHT_TICKS ? Double.NaN : ticks;
        }
        double progress = 1.0 + needed * (decay - 1.0) / perTick;
        if (progress <= 0.0) {
            return Double.NaN;
        }
        if (decay <= 0.0) {
            // 阻力大到一 tick 内水平速度就归零：只有一步内可达才有解。
            return 1.0;
        }
        double ticks = Math.log(progress) / Math.log(decay);
        // 与原逐 tick 实现保持一致：超过最大飞行时间视为不可达。
        return ticks > MAX_FLIGHT_TICKS ? Double.NaN : ticks;
    }

    /** 平方阻力下的逐 tick 积分（无法解析求和）。 */
    private double heightAtDistanceIterative(double pitchDegrees, double horizontalDistance) {
        double pitchRadians = Math.toRadians(pitchDegrees);
        double horizontalSpeed = this.launchSpeed * Math.cos(pitchRadians);
        double verticalSpeed = this.launchSpeed * Math.sin(pitchRadians);
        double travelled = this.muzzleTravelled(pitchDegrees);
        double height = this.muzzleHeight(pitchDegrees);

        for (int iteration = 0; iteration < MAX_INTEGRATION_STEPS; iteration++) {
            double speed = Math.hypot(horizontalSpeed, verticalSpeed);
            if (speed < MIN_SPEED) {
                return Double.NaN;
            }
            double dragDeceleration = this.dragForce(speed) / speed;
            double horizontalAcceleration = -horizontalSpeed * dragDeceleration;
            double verticalAcceleration = this.gravity - verticalSpeed * dragDeceleration;
            double nextTravelled = travelled + horizontalSpeed + horizontalAcceleration * 0.5;
            double nextHeight = height + verticalSpeed + verticalAcceleration * 0.5;
            if (nextTravelled >= horizontalDistance) {
                double fraction = clamp01((horizontalDistance - travelled)
                        / Math.max(nextTravelled - travelled, 1.0e-9));
                return height + (nextHeight - height) * fraction;
            }
            travelled = nextTravelled;
            height = nextHeight;
            horizontalSpeed += horizontalAcceleration;
            verticalSpeed += verticalAcceleration;
        }
        return Double.NaN;
    }

    /** 线性阻力下的速度衰减系数 {@code k}（逐 tick 阻力减速度）。 */
    private double linearDragDeceleration() {
        return Math.min(this.drag * this.dragMultiplier, 1.0);
    }

    private double flightTime(double pitchDegrees, double horizontalDistance) {
        if (horizontalDistance < 0.001) {
            return 0.0;
        }
        if (this.quadraticDrag) {
            return this.flightTimeIterative(pitchDegrees, horizontalDistance);
        }
        double k = this.linearDragDeceleration();
        double pitchRadians = Math.toRadians(pitchDegrees);
        double horizontalSpeed = this.launchSpeed * Math.cos(pitchRadians);
        double travelled = this.muzzleTravelled(pitchDegrees);
        return this.ticksToReach(travelled, horizontalSpeed, horizontalDistance, 1.0 - k, 1.0 - k * 0.5);
    }

    /** 平方阻力下的飞行时间（逐 tick 积分）。 */
    private double flightTimeIterative(double pitchDegrees, double horizontalDistance) {
        Vec3 velocity = new Vec3(0.0, 0.0, this.launchSpeed)
                .xRot((float) Math.toRadians(pitchDegrees));
        double travelled = this.muzzleTravelled(pitchDegrees);
        for (int tick = 0; tick < MAX_INTEGRATION_STEPS; tick++) {
            double speed = velocity.length();
            if (speed < MIN_SPEED) {
                return Double.NaN;
            }
            Vec3 acceleration = this.acceleration(velocity);
            double nextTravelled = travelled + Math.hypot(velocity.x + acceleration.x * 0.5,
                    velocity.z + acceleration.z * 0.5);
            if (nextTravelled >= horizontalDistance) {
                double fraction = clamp01((horizontalDistance - travelled)
                        / Math.max(nextTravelled - travelled, 1.0e-9));
                return tick + fraction;
            }
            travelled = nextTravelled;
            velocity = velocity.add(acceleration);
        }
        return Double.NaN;
    }

    /** 空气阻力产生的减速度大小：{@code min(drag · mult · |v|^q, |v|)}。 */
    private double dragForce(double speed) {
        double dragForce = this.drag * this.dragMultiplier * speed;
        if (this.quadraticDrag) {
            dragForce *= speed;
        }
        return Math.min(dragForce, speed);
    }

    /** 只考虑空气阻力：{@code a = -v̂ · min(drag · mult · |v|^q, |v|) + (0, g, 0)}。 */
    private Vec3 acceleration(Vec3 velocity) {
        double speed = velocity.length();
        return velocity.normalize().scale(-this.dragForce(speed)).add(0.0, this.gravity, 0.0);
    }

    /**
     * 最大射程估算：用 2 tick 的粗步长扫描，精度足够且开销约为原来的一半。
     * 结果由 {@link #getMaxRange()} 按弹道参数缓存。
     */
    private double calculateMaxRange() {
        if (this.launchSpeed < MIN_SPEED) {
            return 0.0;
        }
        final double step = 2.0;
        double max = 0.0;
        for (double pitch = 5.0; pitch <= 85.0; pitch += 2.0) {
            Vec3 position = new Vec3(0.0, this.muzzleHeight(pitch), this.muzzleTravelled(pitch));
            Vec3 velocity = new Vec3(0.0, 0.0, this.launchSpeed)
                    .xRot((float) Math.toRadians(pitch));
            for (int iteration = 0; iteration < MAX_INTEGRATION_STEPS; iteration++) {
                if (velocity.length() < MIN_SPEED) {
                    break;
                }
                Vec3 acceleration = this.acceleration(velocity);
                position = position.add(velocity.scale(step)).add(acceleration.scale(step * step * 0.5));
                velocity = velocity.add(acceleration.scale(step));
                if (position.y < 0.0) {
                    max = Math.max(max, Math.hypot(position.x, position.z));
                    break;
                }
            }
        }
        return Math.max(32.0, max);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * 解算结果。
     *
     * @param pitch            仰角（度，正数向上）
     * @param yaw              水平朝向（度，MC 约定）
     * @param flightTicks      到预测点的飞行时间（tick）
     * @param reachable        该距离/高度在物理上是否可达
     * @param heightError      命中点与目标的高度差（格）
     * @param solvedDistance   最后一轮求解使用的水平距离（含提前量）
     * @param solvedHeight     最后一轮求解使用的目标高度（含提前量）
     * @param impliedSpeed     由 distance / (flightTicks·cos pitch) 反推的初速，用于自检
     */
    public record AimSolution(double pitch, double yaw, double flightTicks,
                              boolean reachable, double heightError,
                              double solvedDistance, double solvedHeight, double impliedSpeed) {}

    private record PitchSolution(double pitch, double error, double flightTicks) {
        boolean reachable() {
            return Math.abs(this.error) <= REACH_TOLERANCE;
        }
    }

    /** 黄金分割搜索所需的迭代次数：{@code range · 0.618^n ≤ tolerance}。 */
    private static int goldenSectionIterations(double range, double tolerance) {
        return (int) Math.ceil(Math.log(tolerance / range) / Math.log(0.6180339887498949));
    }

    /** 按弹道参数共享的缓存：只保存开销较大的最大射程。 */
    private static final class ProfileCache {
        private volatile double maxRange = Double.NaN;
    }

    /** 解算缓存的键：决定弹道形状的参数（量化以容忍浮点误差）。 */
    private record ProfileKey(long launchSpeed, long gravity, long drag, long dragMultiplier,
                              boolean quadraticDrag, long barrelLength) {
        static ProfileKey of(double launchSpeed, double gravity, double drag, double dragMultiplier,
                             boolean quadraticDrag, double barrelLength) {
            return new ProfileKey(
                    Math.round(launchSpeed * 100.0),
                    Math.round(gravity * 10000.0),
                    Math.round(drag * 100000.0),
                    Math.round(dragMultiplier * 1000.0),
                    quadraticDrag,
                    Math.round(barrelLength * 100.0));
        }
    }
}
