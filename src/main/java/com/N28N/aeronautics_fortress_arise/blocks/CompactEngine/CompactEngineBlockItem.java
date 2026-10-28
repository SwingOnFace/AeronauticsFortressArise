package com.N28N.aeronautics_fortress_arise.blocks.CompactEngine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/**
 * 大型紧凑引擎的物品。
 * <p>
 * 放置时把主方块放到结构中心：3x3x3 相对放置位置沿点击面法线再偏移 1 格，
 * 5x5x5 偏移 2 格。这样整个结构从被点击的支撑方块向外展开，
 * 支撑方块位于结构之外，外壳得以完整包裹。
 */
public class CompactEngineBlockItem extends BlockItem {

    public CompactEngineBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (getBlock() instanceof CompactEngineBlock engine && engine.size > 1) {
            int radius = (engine.size - 1) / 2;
            // 结构中心 = 放置第一格 + 点击面法线 × radius（3x3x3 → 1，5x5x5 → 2）
            BlockPos center = context.getClickedPos().relative(context.getClickedFace(), radius);
            return super.place(BlockPlaceContext.at(context, center, context.getClickedFace()));
        }
        return super.place(context);
    }
}
