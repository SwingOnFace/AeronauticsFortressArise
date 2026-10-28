package com.N28N.aeronautics_fortress_arise.blocks.AIStructureController;

import com.N28N.aeronautics_fortress_arise.config.AIStructureControllerConfig;
import com.N28N.aeronautics_fortress_arise.util.SableSubLevelUtil;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * AI 结构控制器的“大脑”，负责亚空间回正决策。
 *
 * <p>每个 tick 读取子空间当前位姿与 home 比较，计算位移偏差与倾斜偏差：
 * <ul>
 *     <li>偏差小于死区 → 不动（自然停住，防抖）</li>
 *     <li>偏差在允许范围内 → 施加平滑速度（带最大速度限制），把结构拉回 home 并扶正</li>
 *     <li>偏差超过硬性阈值 → 大幅提高回正速度快速拉回（仍通过速度间接操作，不直接改坐标）</li>
 * </ul>
 *
 * <p>坐标系统一在世界坐标系计算：home 世界坐标在初始化时由初始位姿变换得到，
 * 当前结构位置 = 当前位姿对 home 逻辑坐标的变换。绝不能用逻辑坐标与世界坐标直接相减。
 */
public class AIStructureControllerBrain {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final AIStructureControllerBlockEntity controller;

    /** 调试日志节流间隔（tick）。 */
    private static final int DEBUG_INTERVAL = 40;

    /** 是否输出回正调试日志（每 DEBUG_INTERVAL tick 一次）。 */
    private static final boolean DEBUG_LOG = false;

    // ------------------------------------------------------------------
    // PID 状态（线性通道：每个轴独立的误差积分与上次误差）
    // ------------------------------------------------------------------

    private double integralX;
    private double integralY;
    private double integralZ;
    private double prevErrorX;
    private double prevErrorY;
    private double prevErrorZ;

    // ------------------------------------------------------------------
    // PID 状态（姿态通道）
    // ------------------------------------------------------------------

    private double rotIntegral;
    private double prevRotError;

    public AIStructureControllerBrain(AIStructureControllerBlockEntity controller) {
        this.controller = controller;
    }

    public void tick() {
        ServerLevel level = (ServerLevel) this.controller.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        Vector3d homeWorldPos = this.controller.getHomePos();
        if (homeWorldPos == null) {
            return;
        }

        ServerSubLevel subLevel = SableSubLevelUtil.findSubLevel(level, this.controller.getBlockPos());
        if (subLevel == null) {
            // 不在子空间上：不工作。
            return;
        }

        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(level);
        if (physics == null) {
            return;
        }
        RigidBodyHandle handle = physics.getPhysicsHandle(subLevel);
        if (handle == null || !handle.isValid()) {
            return;
        }

        // 当前位姿。
        Pose3dc pose = subLevel.logicalPose();

        // 与 home 初始化时使用同一个 plot 局部基准点，再转换到世界坐标。
        var bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null || bounds.maxY() < bounds.minY()) {
            return;
        }
        BlockPos controllerPos = this.controller.getBlockPos();
        Vector3d homeLocalPos = new Vector3d(
                controllerPos.getX() + 0.5,
                bounds.maxY() + 0.5,
                controllerPos.getZ() + 0.5);
        Vector3d currentWorldPos = pose.transformPosition(homeLocalPos, new Vector3d())
                .add(0.0, this.controller.getHoverHeight(), 0.0);

        // 位移偏差（世界坐标，三维：水平 + 垂直）。
        double dx = homeWorldPos.x - currentWorldPos.x;
        double dy = homeWorldPos.y - currentWorldPos.y;
        double dz = homeWorldPos.z - currentWorldPos.z;
        double horizontalOffset = Math.sqrt(dx * dx + dz * dz);
        double verticalOffset = Math.abs(dy);

        if (horizontalOffset > 100) {
            this.controller.setHomePos(currentWorldPos);
            this.resetPidState();
            return;
        }

        // 倾斜偏差：相对"当前 yaw 的水平朝向"的 pitch/roll 夹角（度）。
        // 注意：绝不能直接用 identity 作为水平目标，否则会把 yaw（水平朝向）
        // 也拉回 0，与飞行器转向操作冲突 → 抖动。必须保留 yaw，只回正倾斜。
        Quaterniond currentRot = new Quaterniond(pose.orientation());
        Quaterniond tiltDiff = new Quaterniond();
        double tilt = computeTilt(currentRot, tiltDiff);

        double deadzone = AIStructureControllerConfig.DEADZONE.get();
        double maxOffset = AIStructureControllerConfig.MAX_OFFSET.get();
        double maxTilt = AIStructureControllerConfig.MAX_TILT.get();

        // 调试日志（节流）。
        boolean debug = DEBUG_LOG;
        long gameTime = level.getGameTime();
        boolean doLog = debug && gameTime % DEBUG_INTERVAL == 0;
        if (doLog) {
            LOGGER.info("[AIStructCtrl] {} 位姿origin={} 结构当前位置={} homeWorld={}",
                    this.controller.getBlockPos(), pose.position(), currentWorldPos, homeWorldPos);
            LOGGER.info("[AIStructCtrl] {} 偏移: 水平={} 垂直={} 倾斜={}°（死区 {} / 硬性 {}/{}°）",
                    this.controller.getBlockPos(), horizontalOffset, verticalOffset, tilt, deadzone, maxOffset, maxTilt);
        }

        // 死区：偏移与倾斜都很小 → 自然停住，不做任何干预，并重置积分防累积。
        if (horizontalOffset < deadzone && verticalOffset < deadzone && tilt < deadzone) {
            this.resetPidState();
            if (doLog) {
                LOGGER.info("[AIStructCtrl] {} 死区内，不动", this.controller.getBlockPos());
            }
            return;
        }

        // 硬性兜底：偏差超出允许范围时，放大速度上限快速拉回（仍通过速度，不传送）。
        double maxSpeed = AIStructureControllerConfig.MAX_RESTORE_SPEED.get();
        double maxAngularSpeed = AIStructureControllerConfig.MAX_RESTORE_ANGULAR_SPEED.get();
        boolean outOfBounds = horizontalOffset > maxOffset || verticalOffset > maxOffset || tilt > maxTilt;
        if (outOfBounds) {
            maxSpeed *= AIStructureControllerConfig.HARD_RESTORE_SPEED_MULTIPLIER.get();
            maxAngularSpeed *= AIStructureControllerConfig.HARD_RESTORE_ANGULAR_MULTIPLIER.get();
            if (doLog) {
                LOGGER.info("[AIStructCtrl] {} 硬性回正（加速）：水平={} 垂直={} tilt={}° -> 速度上限 x{}",
                        this.controller.getBlockPos(), horizontalOffset, verticalOffset, tilt,
                        AIStructureControllerConfig.HARD_RESTORE_SPEED_MULTIPLIER.get());
            }

        }

        // ------------------------------------------------------------------
        // 线性通道 PID：输出期望速度（每个轴独立）。
        //   P 项 = Kp × 当前偏差（距离）
        //   I 项 = Ki × 历史偏差积分（消除稳态误差）
        //   D 项 = Kd × 偏差变化率（接近目标时减速，抑制振荡）
        // ------------------------------------------------------------------
        double kp = AIStructureControllerConfig.PID_KP.get();
        double ki = AIStructureControllerConfig.PID_KI.get();
        double kd = AIStructureControllerConfig.PID_KD.get();
        double integralMax = AIStructureControllerConfig.PID_INTEGRAL_MAX.get();

        // 更新积分（带 anti-windup）与微分项。
        this.integralX = clamp(this.integralX + dx, integralMax);
        this.integralY = clamp(this.integralY + dy, integralMax);
        this.integralZ = clamp(this.integralZ + dz, integralMax);

        double dTermX = dx - this.prevErrorX;
        double dTermY = dy - this.prevErrorY;
        double dTermZ = dz - this.prevErrorZ;

        double outX = kp * dx + ki * this.integralX + kd * dTermX;
        double outY = kp * dy + ki * this.integralY + kd * dTermY;
        double outZ = kp * dz + ki * this.integralZ + kd * dTermZ;

        this.prevErrorX = dx;
        this.prevErrorY = dy;
        this.prevErrorZ = dz;

        // 限幅到最大速度。
        double speed = Math.sqrt(outX * outX + outY * outY + outZ * outZ);
        if (speed > maxSpeed) {
            double scale = maxSpeed / speed;
            outX *= scale;
            outY *= scale;
            outZ *= scale;
        }
        // 期望速度（PID 输出）。
        Vector3d desiredLinear = new Vector3d(outX, outY, outZ);

        // ------------------------------------------------------------------
        // 姿态通道 PID：输出期望角速度。
        //   误差 = 当前朝向相对水平(identity)的旋转角（度），旋转轴 = 修正方向。
        // ------------------------------------------------------------------
        double rotKp = AIStructureControllerConfig.PID_ROT_KP.get();
        double rotKi = AIStructureControllerConfig.PID_ROT_KI.get();
        double rotKd = AIStructureControllerConfig.PID_ROT_KD.get();
        double rotIntegralMax = AIStructureControllerConfig.PID_ROT_INTEGRAL_MAX.get();

        Vector3d desiredAngular = new Vector3d(0, 0, 0);
        if (tilt > deadzone) {
            // tiltDiff 的旋转轴位于水平面内（纯 pitch/roll 修正方向，不含 yaw）。
            double s = Math.sqrt(tiltDiff.x * tiltDiff.x
                    + tiltDiff.y * tiltDiff.y + tiltDiff.z * tiltDiff.z);
            if (s > 1.0e-6) {
                // 误差 = 倾斜角（度），沿修正旋转轴方向输出角速度。
                double rotError = tilt;
                this.rotIntegral = clamp(this.rotIntegral + rotError, rotIntegralMax);
                double rotDTerm = rotError - this.prevRotError;
                this.prevRotError = rotError;

                double angOut = rotKp * rotError + rotKi * this.rotIntegral + rotKd * rotDTerm;
                angOut = Math.max(-maxAngularSpeed, Math.min(maxAngularSpeed, angOut));

                desiredAngular.set(
                        tiltDiff.x / s * angOut,
                        tiltDiff.y / s * angOut,
                        tiltDiff.z / s * angOut);
            }
        } else {
            this.rotIntegral = 0;
            this.prevRotError = 0;
        }

        // ------------------------------------------------------------------
        // 关键：addLinearAndAngularVelocity 是"累加"速度，不是"设置"速度。
        // 若直接把 PID 期望速度整体加上，速度会每 tick 无限累加 → 冲过 home →
        // 误差变号 → 反向累加 → 来回猛冲（抽搐）。
        // 正确做法：只施加"速度差"（期望速度 − 当前速度），
        // 使刚体速度指数收敛到期望值，形成稳定的闭环。
        // ------------------------------------------------------------------
        Vector3d currentLinear = handle.getLinearVelocity(new Vector3d());
        Vector3d linearDelta = new Vector3d(desiredLinear).sub(currentLinear);

        Vector3d currentAngular = handle.getAngularVelocity(new Vector3d());
        Vector3d angularDelta = new Vector3d(desiredAngular).sub(currentAngular);

        // 速度差限幅：单 tick 变化不超过上限，防止收敛过猛。
        double maxDelta = AIStructureControllerConfig.MAX_RESTORE_SPEED.get() * 0.5D;
        double linearDeltaLen = linearDelta.length();
        if (linearDeltaLen > maxDelta) {
            linearDelta.mul(maxDelta / linearDeltaLen);
        }
        double angularDeltaLen = angularDelta.length();
        if (angularDeltaLen > AIStructureControllerConfig.MAX_RESTORE_ANGULAR_SPEED.get() * 0.5D) {
            angularDelta.mul(maxAngularSpeed * 0.5D / angularDeltaLen);
        }

        if (doLog) {
            LOGGER.info("[AIStructCtrl] {} PID回正：期望linear=({},{},{}) 当前linear=({},{},{}) 施加=({},{},{})",
                    this.controller.getBlockPos(),
                    desiredLinear.x, desiredLinear.y, desiredLinear.z,
                    currentLinear.x, currentLinear.y, currentLinear.z,
                    linearDelta.x, linearDelta.y, linearDelta.z);
        }

        handle.addLinearAndAngularVelocity(linearDelta, angularDelta);
    }

    private void resetPidState() {
        this.integralX = this.integralY = this.integralZ = 0;
        this.prevErrorX = this.prevErrorY = this.prevErrorZ = 0;
        this.rotIntegral = 0;
        this.prevRotError = 0;
    }

    /** 钳制到 [-max, max]。 */
    private static double clamp(double value, double max) {
        return Math.max(-max, Math.min(max, value));
    }

    /**
     * 计算四元数相对"当前 yaw 的水平朝向"的倾斜角（度）。
     * <p>
     * 目标朝向 = 保持当前 yaw（绕世界 Y 轴角度）不变，pitch/roll 归零。
     * 这样只回正倾斜，不影响飞行器的水平朝向（转向）。
     *
     * @param currentRot 当前朝向（可能非单位，内部会归一化）
     * @param diffOut    输出修正四元数（从当前姿态转到水平朝向的旋转，轴在水平面内）
     * @return 倾斜角（度），0 表示水平
     */
    private static double computeTilt(Quaterniond currentRot, Quaterniond diffOut) {
        Quaterniond rot = new Quaterniond(currentRot).normalize();

        // 提取当前 yaw（绕世界 Y 轴的角度）。
        double yaw = Math.atan2(
                2.0 * (rot.w * rot.y + rot.x * rot.z),
                1.0 - 2.0 * (rot.y * rot.y + rot.z * rot.z));

        // 期望朝向：水平且保持当前 yaw。
        Quaterniond desired = new Quaterniond().rotationY(yaw);

        // 修正旋转：从当前姿态转到期望姿态（仅含 pitch/roll 分量）。
        Quaterniond diff = new Quaterniond(rot).conjugate().mul(desired).normalize();
        // 取最短旋转路径（保证角度 ≤ 180°），并避免旋转轴在 w 符号切换时翻转。
        if (diff.w < 0) {
            diff.set(-diff.x, -diff.y, -diff.z, -diff.w);
        }
        if (diffOut != null) {
            diffOut.set(diff);
        }

        double angle = 2.0 * Math.acos(Math.max(-1.0, Math.min(1.0, diff.w)));
        return Math.toDegrees(angle);
    }
}
