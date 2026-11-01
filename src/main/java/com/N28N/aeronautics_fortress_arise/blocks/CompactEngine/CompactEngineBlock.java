package com.N28N.aeronautics_fortress_arise.blocks.CompactEngine;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.data.AssetLookup;
import com.simibubi.create.foundation.data.SpecialBlockStateGen;
import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CompactEngineBlock extends DirectionalKineticBlock implements IBE<CompactEngineBlockEntity> {

    /** 结构边长：1 = 单方块，3 = 3x3x3，5 = 5x5x5。 */
    public final int size;

    public CompactEngineBlock(Properties properties) {
        this(properties, 1);
    }

    public CompactEngineBlock(Properties properties, int size) {
        super(properties);
        this.size = size;
    }

    @Override
    public @NotNull VoxelShape getShape(BlockState state, BlockGetter worldIn, BlockPos pos, CollisionContext context) {
        return AllShapes.MOTOR_BLOCK.get(state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 大型引擎：context.getClickedPos() 已是结构中心（见 CompactEngineBlockItem）
        if (size > 1) {
            if (!canPlaceStructure(context.getClickedPos(), context.getLevel())) {
                return null;
            }
            // 面向点击面（结构向外生长方向），正面露出轴接口便于接传动
            return defaultBlockState().setValue(FACING, context.getClickedFace());
        }
        Direction preferred = getPreferredFacing(context);
        if ((context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) || preferred == null) {
            return super.getStateForPlacement(context);
        }
        return defaultBlockState().setValue(FACING, preferred);
    }

    /** 检查以 center 为中心、边长为 size 的立方体空间（除中心外）是否全部可替换。 */
    private boolean canPlaceStructure(BlockPos center, Level level) {
        int radius = (size - 1) / 2;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (pos.equals(center)) {
                continue;
            }
            if (!level.getBlockState(pos).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /** 玩家放置后：把结构内其余位置填充为外壳方块。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || size <= 1) {
            return;
        }
        Block casing = AllBlocks.COMPACT_ENGINE_CASING.get();
        int radius = (size - 1) / 2;
        // 轴道：FACING 方向从中心到结构边缘整条线（1..radius 格）都不填外壳，
        // 让传动轴能从中心方块一路通到结构外（3x3x3 为 1 格，5x5x5 为 2 格）。
        Direction facing = state.getValue(FACING);
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-radius, -radius, -radius),
                pos.offset(radius, radius, radius))) {
            if (p.equals(pos) || isOnShaftAxis(p, pos, facing)) {
                continue;
            }
            if (level.getBlockState(p).canBeReplaced()) {
                level.setBlock(p, casing.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** 判断 p 是否位于从 center 沿 facing 方向的轴道上（1..radius 格）。 */
    private boolean isOnShaftAxis(BlockPos p, BlockPos center, Direction facing) {
        BlockPos delta = p.subtract(center);
        int fx = facing.getStepX();
        int fy = facing.getStepY();
        int fz = facing.getStepZ();
        int radius = (size - 1) / 2;
        for (int k = 1; k <= radius; k++) {
            if (delta.getX() == fx * k && delta.getY() == fy * k && delta.getZ() == fz * k) {
                return true;
            }
        }
        return false;
    }

    /** 主方块被移除时：把结构内的外壳方块全部清空。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && size > 1 && !level.isClientSide) {
            Block casing = AllBlocks.COMPACT_ENGINE_CASING.get();
            int radius = (size - 1) / 2;
            for (BlockPos p : BlockPos.betweenClosed(pos.offset(-radius, -radius, -radius),
                    pos.offset(radius, radius, radius))) {
                if (p.equals(pos)) {
                    continue;
                }
                if (level.getBlockState(p).is(casing)) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }
    
	@Override
	public boolean hideStressImpact() {
		return true;
	}

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
        return false;
    }

    @Override
    public Class<CompactEngineBlockEntity> getBlockEntityClass() {
        return CompactEngineBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CompactEngineBlockEntity> getBlockEntityType() {
        return switch (size) {
            case 3 -> AllBlocks.COMPACT_ENGINE_3_BLOCK_ENTITY.get();
            case 5 -> AllBlocks.COMPACT_ENGINE_5_BLOCK_ENTITY.get();
            default -> AllBlocks.COMPACT_ENGINE_BLOCK_ENTITY.get();
        };
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompactEngineBlockEntity(getBlockEntityType(), pos, state);
    }

    public static class CompactEngineGenerator extends SpecialBlockStateGen {

        @Override
        protected int getXRotation(BlockState state) {
            return state.getValue(FACING) == Direction.DOWN ? 180 : 0;
        }

        @Override
        protected int getYRotation(BlockState state) {
            return state.getValue(FACING).getAxis().isVertical()
                    ? 0
                    : horizontalAngle(state.getValue(FACING));
        }

        @Override
        public <T extends Block> ModelFile getModel(DataGenContext<Block, T> ctx, RegistrateBlockstateProvider prov,
                BlockState state) {
            return state.getValue(FACING).getAxis().isVertical()
                    ? AssetLookup.partialBaseModel(ctx, prov, "vertical")
                    : AssetLookup.partialBaseModel(ctx, prov);
        }
    }
}
