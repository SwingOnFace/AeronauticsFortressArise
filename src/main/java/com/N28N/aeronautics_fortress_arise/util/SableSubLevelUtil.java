package com.N28N.aeronautics_fortress_arise.util;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import org.jetbrains.annotations.Nullable;

/**
 * Sable 子空间（航空学飞行器）坐标变换工具。
 *
 * <p>子空间方块直接存储在宿主 Level 中，其逻辑坐标就是 plotgrid 的全局坐标，
 * 因此可以直接用容器的 O(1) 索引定位所在 plot，再获取子空间位姿，
 * 用于子空间局部坐标与主世界全局坐标之间的变换。
 */
public final class SableSubLevelUtil {

    private SableSubLevelUtil() {
    }

    /**
     * 查找包含指定方块位置的 Sable 子空间，并返回其当前位姿。
     * 若方块不在任何子空间内（普通主世界），返回 null。
     */
    @Nullable
    public static Pose3dc findPose(Level level, BlockPos pos) {
        ServerSubLevel subLevel = findSubLevel(level, pos);
        return subLevel == null ? null : subLevel.logicalPose();
    }

    /**
     * 查找包含指定方块位置的 Sable 子空间。
     * 若方块不在任何子空间内（普通主世界），返回 null。
     */
    @Nullable
    public static ServerSubLevel findSubLevel(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
        if (container == null) {
            return null;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        if (!container.inBounds(chunkPos)) {
            return null;
        }
        LevelPlot plot = container.getPlot(chunkPos);
        if (plot == null) {
            return null;
        }
        SubLevel subLevel = plot.getSubLevel();
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            return null;
        }
        return serverSubLevel;
    }

    /**
     * 判断玩家当前是否站在指定子空间上。
     *
     * <p>注意不能用 {@link #findSubLevel} 来判断：它按 plot 单元格索引，而 plot 单元格
     * 边长是 {@code 1 << 7} 区块（2048 格），距离结构几十上百格的点仍会命中同一格，
     * 导致"离得很远也被判定在结构上"。
     *
     * <p>这里改用结构自身的内容包围盒：{@link LevelPlot#getBoundingBox()} 是子空间实际占用
     * 方块的逻辑包围盒（plot 逻辑坐标），经位姿逆变换后与玩家世界坐标比对即可精确判定。
     * {@link SubLevel#boundingBox()} 是它的世界 AABB，结构旋转时会偏大，只作为快速排除。
     */
    public static boolean isPlayerInSubLevel(ServerPlayer player, ServerSubLevel sublevel) {
        if (player.serverLevel() != sublevel.getLevel()) {
            return false;
        }

        Vec3 playerPos = player.position();

        BoundingBox3dc worldBounds = sublevel.boundingBox();
        if (worldBounds.volume() > 0 && !worldBounds.contains(playerPos.x, playerPos.y, playerPos.z)) {
            return false;
        }

        LevelPlot plot = sublevel.getPlot();
        BoundingBox3ic localBounds = plot == null ? null : plot.getBoundingBox();
        if (localBounds == null || localBounds.volume() <= 0) {
            return false;
        }
        Vec3 localPos = sublevel.logicalPose().transformPositionInverse(playerPos);
        return localBounds.contains(new Vector3d(localPos.x, localPos.y, localPos.z));
    }
}
