package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import java.util.List;
import java.util.Locale;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
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
    private final double maxRange;

    public BallisticsCalculator(double launchSpeed, BallisticPropertiesComponent properties,
                                double gravityMultiplier, double dragMultiplier, double barrelLength) {
        this.launchSpeed = Math.max(0.0, launchSpeed);
        this.gravity = properties.gravity() * gravityMultiplier;
        this.drag = properties.drag();
        this.quadraticDrag = properties.isQuadraticDrag();
        this.dragMultiplier = dragMultiplier;
        this.barrelLength = Math.max(0.0, barrelLength);
        this.maxRange = this.calculateMaxRange();
        /* 
        if (isDebugEnabled()) {
            debug("created launchSpeed=" + f(this.launchSpeed)
                    + " gravity=" + f(this.gravity)
                    + " drag=" + f(this.drag)
                    + " quadratic=" + this.quadraticDrag
                    + " dragMultiplier=" + f(this.dragMultiplier)
                    + " barrelLength=" + f(this.barrelLength)
                    + " maxRange=" + f(this.maxRange));
        }*/
    }

    public static BallisticsCalculator forProjectile(int chargePower, AbstractBigCannonProjectile projectile,
                                                       Level level) {
        return forProjectile(chargePower, projectile, level, 0.0);
    }

    public static BallisticsCalculator forProjectile(int chargePower, AbstractBigCannonProjectile projectile,
                                                       Level level, double barrelLength) {
        BallisticPropertiesComponent properties = getBallisticProperties(projectile);
        var dimensionProperties = DimensionMunitionPropertiesHandler.getProperties(level);
        double gravityMultiplier = dimensionProperties.gravityMultiplier();
        double dragMultiplier = dimensionProperties.dragMultiplier();
        double launchSpeed = (chargePower + projectile.addedChargePower()) * 2;
        return new BallisticsCalculator(launchSpeed, properties, gravityMultiplier, dragMultiplier, barrelLength);
    }

    private static BallisticPropertiesComponent getBallisticProperties(AbstractBigCannonProjectile projectile) {
        List<BallisticPropertiesComponent> candidates = List.of(
                CBCMunitionPropertiesHandlers.INERT_BIG_CANNON_PROJECTILE.getPropertiesOf(projectile).ballistics(),
                CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE.getPropertiesOf(projectile).ballistics(),
                CBCMunitionPropertiesHandlers.SHRAPNEL_SHELL.getPropertiesOf(projectile).ballistics(),
                CBCMunitionPropertiesHandlers.FLUID_SHELL.getPropertiesOf(projectile).ballistics(),
                CBCMunitionPropertiesHandlers.SMOKE_SHELL.getPropertiesOf(projectile).ballistics(),
                CBCMunitionPropertiesHandlers.MORTAR_STONE.getPropertiesOf(projectile).ballistics(),
                CBCMunitionPropertiesHandlers.DROP_MORTAR_SHELL.getPropertiesOf(projectile).ballistics());
        return candidates.stream()
                .filter(properties -> properties.gravity() != 0.0 || properties.drag() != 0.0)
                .findFirst()
                .orElseGet(() -> CBCMunitionPropertiesHandlers.INERT_BIG_CANNON_PROJECTILE
                        .getPropertiesOf(projectile).ballistics());
    }

    public double getMaxRange() {
        return this.maxRange;
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
                + " maxRange=" + f(this.maxRange);
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
        } else {
            double ticks = this.flightTime(pitch, horizontalDistance);
            if (Double.isFinite(ticks)) {
                flightTicks = ticks;
            } else {
                reachable = false;
            }
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
     */
    private PitchSolution solvePitch(double horizontalDistance, double targetHeight, double initialPitch) {
        boolean trace = AIFireControllerBrain.DEBUG_ENABLED;
        if (horizontalDistance < 12 || this.launchSpeed < MIN_SPEED) {
            double fallback = Math.max(-MAX_PITCH, Math.min(MAX_PITCH,
                    Math.toDegrees(Math.atan2(targetHeight, Math.max(horizontalDistance, 0.001)))));
            return new PitchSolution(fallback, 0.0);
        }

        double peakPitch = this.findPeakPitch(horizontalDistance);
        double peakHeight = this.heightAtDistance(peakPitch, horizontalDistance);
        if (trace) {
            debug("solvePitch dist=" + f(horizontalDistance) + " targetHeight=" + f(targetHeight)
                    + " seedPitch=" + f(initialPitch)
                    + " peakPitch=" + f(peakPitch) + " peakHeight=" + f(peakHeight));
        }
        if (!Double.isFinite(peakHeight)) {
            return new PitchSolution(initialPitch, Double.POSITIVE_INFINITY);
        }
        if (targetHeight >= peakHeight) {
            // 目标比该距离上弹道能达到的最高点还高：物理上打不到。
            return new PitchSolution(peakPitch, targetHeight - peakHeight);
        }

        double lowPitch = -MAX_PITCH;
        double lowHeight = this.heightAtDistance(lowPitch, horizontalDistance);
        if (!Double.isFinite(lowHeight)) {
            lowHeight = Double.NEGATIVE_INFINITY;
        }
        if (targetHeight <= lowHeight) {
            return new PitchSolution(lowPitch, targetHeight - lowHeight);
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
        /*if (trace) {
            debug("solvePitch bisection -> pitch=" + f(pitch) + " height=" + f(height)
                    + " error=" + f(error)
                    + " (low=" + f(low) + ", high=" + f(high) + ")");
        }*/
        return new PitchSolution(pitch, error);
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
        for (int i = 0; i < 32 && high - low > PEAK_PITCH_TOLERANCE; i++) {
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

    private double heightAtDistance(double pitchDegrees, double horizontalDistance) {
        double pitchRadians = Math.toRadians(pitchDegrees);//弧度制pitch
        double horizontalSpeed = this.launchSpeed * Math.cos(pitchRadians);//vx
        double verticalSpeed = this.launchSpeed * Math.sin(pitchRadians);//vy
        // 弹丸从炮口出膛：起点先补偿炮口相对枢轴的位移。
        double travelled = this.muzzleTravelled(pitchDegrees);
        double height = this.muzzleHeight(pitchDegrees);

        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) { //MAX_FLIGHT_TICKS=1200tick=60s
            double speed = Math.hypot(horizontalSpeed, verticalSpeed);//v合
            if (speed < MIN_SPEED) {
                /*if (isDebugEnabled()) {
                    debug("heightAtDistance stall pitch=" + f(pitchDegrees)
                            + " dist=" + f(horizontalDistance) + " at tick=" + tick
                            + " travelled=" + f(travelled));
                }*/
                return Double.NaN;//应该是飞力竭了
            }
            double dragDeceleration = this.dragForce(speed) / speed;
            double horizontalAcceleration = -horizontalSpeed * dragDeceleration;
            double verticalAcceleration = this.gravity - verticalSpeed * dragDeceleration;
            double nextTravelled = travelled + horizontalSpeed + horizontalAcceleration * 0.5;
            double nextHeight = height + verticalSpeed + verticalAcceleration * 0.5;

            if (nextTravelled >= horizontalDistance) {
                // 目标比炮口还近时 fraction 会被夹到 0（直接返回炮口高度）。
                double fraction = clamp01((horizontalDistance - travelled)
                        / Math.max(nextTravelled - travelled, 1.0e-9));
                return height + (nextHeight - height) * fraction;
            }

            travelled = nextTravelled;
            height = nextHeight;
            horizontalSpeed += horizontalAcceleration;
            verticalSpeed += verticalAcceleration;
        }
        /*if (isDebugEnabled()) {
            debug("heightAtDistance timeout pitch=" + f(pitchDegrees)
                    + " dist=" + f(horizontalDistance)
                    + " travelled=" + f(travelled)
                    + " ticks=" + MAX_FLIGHT_TICKS);
        }*/
        return Double.NaN;
    }

    private double flightTime(double pitchDegrees, double horizontalDistance) {
        if (horizontalDistance < 0.001) {
            return 0.0;
        }
        Vec3 velocity = new Vec3(0.0, 0.0, this.launchSpeed)
                .xRot((float) Math.toRadians(pitchDegrees));
        // 起点同样补偿炮口长度：水平方向已前进的格数。
        double travelled = this.muzzleTravelled(pitchDegrees);

        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            double speed = velocity.length();
            if (speed < MIN_SPEED) {
                /*if (isDebugEnabled()) {
                    debug("flightTime stall pitch=" + f(pitchDegrees)
                            + " dist=" + f(horizontalDistance) + " at tick=" + tick
                            + " travelled=" + f(travelled));
                }*/
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
        /*if (isDebugEnabled()) {
            debug("flightTime timeout pitch=" + f(pitchDegrees)
                    + " dist=" + f(horizontalDistance)
                    + " travelled=" + f(travelled)
                    + " ticks=" + MAX_FLIGHT_TICKS);
        }*/
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

    private double calculateMaxRange() {
        if (this.launchSpeed < MIN_SPEED) {
            return 0.0;
        }
        double max = 0.0;
        for (double pitch = 5.0; pitch <= 85.0; pitch += 2.0) {
            Vec3 position = new Vec3(0.0, this.muzzleHeight(pitch), this.muzzleTravelled(pitch));
            Vec3 velocity = new Vec3(0.0, 0.0, this.launchSpeed)
                    .xRot((float) Math.toRadians(pitch));
            for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
                if (velocity.length() < MIN_SPEED) {
                    break;
                }
                Vec3 acceleration = this.acceleration(velocity);
                position = position.add(velocity).add(acceleration.scale(0.5));
                velocity = velocity.add(acceleration);
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

    private record PitchSolution(double pitch, double error) {
        boolean reachable() {
            return Math.abs(this.error) <= REACH_TOLERANCE;
        }
    }
}
