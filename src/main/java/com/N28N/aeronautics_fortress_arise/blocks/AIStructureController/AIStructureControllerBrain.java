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
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * AI 结构控制器的“大脑”，负责把亚空间固定在 home 位姿。
 *
 * <p>每个 tick 直接把子空间位姿写回 home：
 * <ul>
 *     <li>位置：让锚点（控制器方块坐标）精确落在 home 世界坐标</li>
 *     <li>朝向：保留当前 yaw，pitch/roll 归零（只扶正倾斜，不影响要塞转向）</li>
 *     <li>速度：清空残余线速度与非 yaw 角速度，避免重力/碰撞积累的速度在解除固定时爆发</li>
 * </ul>
 * 偏差在死区内时不重复写入位姿，只清速度，避免每 tick 无意义地改写位姿。
 *
 * <p>坐标系统一在世界坐标系计算：home 世界坐标在初始化时由初始位姿变换得到，
 * 目标位姿位置由「home 世界坐标 − 目标朝向下的锚点偏移」反推。
 */
public class AIStructureControllerBrain {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final AIStructureControllerBlockEntity controller;

    /** 调试日志节流间隔（tick）。 */
    private static final int DEBUG_INTERVAL = 40;

    /** 是否输出固定调试日志（每 DEBUG_INTERVAL tick 一次）。 */
    private static final boolean DEBUG_LOG = false;

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
        
        BlockPos controllerPos = this.controller.getBlockPos();
        Vector3d anchorLocal = new Vector3d(
                controllerPos.getX(),
                controllerPos.getY(),
                controllerPos.getZ());

        Pose3dc pose = subLevel.logicalPose();

        // 目标朝向：保留当前 yaw，pitch/roll 归零（只扶正倾斜，不影响要塞转向）。
        Quaterniond targetRot = levelOrientation(pose.orientation());

        // 目标位姿位置：锚点经缩放、目标朝向旋转后正好落在 home 世界坐标。
        Vector3d anchorOffset = anchorLocal
                .sub(pose.rotationPoint(), new Vector3d())
                .mul(pose.scale());
        targetRot.transform(anchorOffset);
        Vector3d targetPos = new Vector3d(homeWorldPos).sub(anchorOffset);

        Vector3d currentWorldPos = pose.transformPosition(anchorLocal, new Vector3d());
        double offset = currentWorldPos.distance(homeWorldPos);
        double tilt = angleDegrees(pose.orientation(), targetRot);
        double deadzone = AIStructureControllerConfig.DEADZONE.get();

        long gameTime = level.getGameTime();
        boolean doLog = gameTime % DEBUG_INTERVAL == 0 && DEBUG_LOG;
        if (doLog) {
            LOGGER.info("[AIStructCtrl] {} 固定：当前位置={} home={} 偏移={} 倾斜={}° 目标位姿={}",
                    controllerPos, currentWorldPos, homeWorldPos, offset, tilt, targetPos);
        }

        if (offset > deadzone || tilt > deadzone) {
            handle.teleport(targetPos, targetRot);
        }

        // teleport 只写入位姿，速度仍在累积：必须一并清零，否则重力会让刚体越掉越快，
        // 解除固定（控制器被破坏）的瞬间爆发。yaw 角速度保留，避免与要塞转向冲突。
        Vector3d linearVelocity = handle.getLinearVelocity(new Vector3d()).negate();
        Vector3d angularVelocity = handle.getAngularVelocity(new Vector3d());
        handle.addLinearAndAngularVelocity(
                linearVelocity,
                new Vector3d(-angularVelocity.x, -angularVelocity.y, -angularVelocity.z));
    }

    /**
     * 目标朝向：保留当前 yaw（绕世界 Y 轴的角度），pitch/roll 归零。
     *
     * <p>不能用 identity 作为水平目标，否则会把 yaw（水平朝向）也拉回 0，
     * 与要塞转向操作冲突。
     */
    private static Quaterniond levelOrientation(Quaterniondc current) {
        Quaterniond rot = new Quaterniond(current).normalize();
        double yaw = Math.atan2(
                2.0 * (rot.w * rot.y + rot.x * rot.z),
                1.0 - 2.0 * (rot.y * rot.y + rot.z * rot.z));
        return new Quaterniond().rotationY(yaw);
    }

    /** 两个朝向之间的夹角（度），取最短旋转路径。 */
    private static double angleDegrees(Quaterniondc a, Quaterniondc b) {
        double dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
        return Math.toDegrees(2.0 * Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }
}
