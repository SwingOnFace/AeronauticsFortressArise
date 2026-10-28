package com.N28N.aeronautics_fortress_arise.blocks.CompactEngine;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 大型紧凑引擎的外壳方块。
 * <p>
 * 属于 3x3x3 / 5x5x5 引擎结构的一部分，本身无动能逻辑，
 * 但挂有外壳方块实体，支持用滚轮调节所属引擎的转速；
 * 被破坏时会在半径 2 格内寻找所属的主方块并连带拆除整个结构。
 */
public class CompactEngineCasingBlock extends Block implements EntityBlock {

    public CompactEngineCasingBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompactEngineCasingBlockEntity(AllBlocks.COMPACT_ENGINE_CASING_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            // 搜索半径 2 格内的主方块（5x5x5 结构的中心距离边缘最多 2 格）
            for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
                BlockState candidate = level.getBlockState(p);
                if (candidate.getBlock() instanceof CompactEngineBlock engine && engine.size > 1) {
                    // 仅当该位置确实是此引擎结构的成员时才拆除，避免误伤相邻引擎
                    int radius = (engine.size - 1) / 2;
                    BlockPos delta = pos.subtract(p);
                    if (Math.abs(delta.getX()) <= radius && Math.abs(delta.getY()) <= radius
                            && Math.abs(delta.getZ()) <= radius) {
                        // 拆除主方块会触发其 onRemove，进而清理整个结构的外壳
                        level.destroyBlock(p, true);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
