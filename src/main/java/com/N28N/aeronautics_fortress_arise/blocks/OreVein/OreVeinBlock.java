package com.N28N.aeronautics_fortress_arise.blocks.OreVein;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;

import java.util.List;

/**
 * 矿脉方块。
 * <p>
 * 贴图与矿石材质相同（见资源文件 {@code models/block/ore_vein.json}），
 * 外观上看起来就像一块矿石，并通过 {@link OreVeinRenderer} 叠加提亮层，
 * 使颜色比正常矿石更亮。内部通过 {@link OreVeinBlockEntity}
 * 按矿脉等级在其解锁的各个面生成一种矿石方块；每 20 级增加一个输出面，
 * 等级可在创造模式下通过滚轮设置或使用矿脉提取器转移。
 */
public class OreVeinBlock extends Block implements EntityBlock {

    private final ResourceLocation oreItemId;
    private final int lootQuality;

    public OreVeinBlock(Properties properties, ResourceLocation oreItemId, int lootQuality) {
        super(properties);
        this.oreItemId = oreItemId;
        this.lootQuality = lootQuality;
    }

    public ResourceLocation getOreItemId() {
        return this.oreItemId;
    }

    public int getLootQuality() {
        return this.lootQuality;
    }

    /** 矿脉方块不产生任何掉落物。 */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
            ResourceLocation.fromNamespaceAndPath("aeronautics_fortress_arise", "ore_vein"));
        return new OreVeinBlockEntity(type, pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (currentLevel, pos, currentState, blockEntity) -> {
            if (blockEntity instanceof OreVeinBlockEntity vein) {
                vein.tick();
            }
        };
    }
}
