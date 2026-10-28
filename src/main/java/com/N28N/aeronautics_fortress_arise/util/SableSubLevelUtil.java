package com.N28N.aeronautics_fortress_arise.util;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
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
}
