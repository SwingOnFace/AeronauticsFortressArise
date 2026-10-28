package com.N28N.aeronautics_fortress_arise.blocks.LootItemVault;

import com.N28N.aeronautics_fortress_arise.AllBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.List;

/**
 * 战利品保险箱方块。
 * <p>
 * 贴图与机械动力的保险箱（Item Vault）一致，带有水平轴向属性，
 * 用于控制保险箱前脸的朝向。内部通过 {@link LootItemVaultBlockEntity}
 * 生成海量战利品，且只允许取出、禁止存入；物品清空后保险箱消失。
 * <p>
 * 每种保险库对应一个独立的战利品表（通过 {@link #lootTableKey} 指定），
 * 15 种保险库共享同一个方块实体类型。
 */
public class LootItemVaultBlock extends Block implements EntityBlock {

    /** 水平轴向（X / Z），决定保险箱前脸的朝向。 */
    public static final EnumProperty<Direction.Axis> HORIZONTAL_AXIS =
            BlockStateProperties.HORIZONTAL_AXIS;

    /** 该保险库对应的战利品表 key。 */
    private final ResourceKey<LootTable> lootTableKey;

    public LootItemVaultBlock(Properties properties, ResourceKey<LootTable> lootTableKey) {
        super(properties);
        this.lootTableKey = lootTableKey;
        this.registerDefaultState(this.defaultBlockState().setValue(HORIZONTAL_AXIS, Direction.Axis.Z));
    }

    /** 获取该保险库对应的战利品表 key。 */
    public ResourceKey<LootTable> getLootTableKey() {
        return this.lootTableKey;
    }

    /** 保险库方块本身不产生掉落物，只掉落内部内容物（见 {@link #onRemove}）。 */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_AXIS);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // 所有保险库共享同一个方块实体类型，直接使用 AllBlocks 中保存的引用。
        BlockEntityType<?> type = AllBlocks.LOOT_VAULT_BLOCK_ENTITY.get();
        return new LootItemVaultBlockEntity(type, pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (currentLevel, pos, currentState, blockEntity) -> {
            if (blockEntity instanceof LootItemVaultBlockEntity vault) {
                vault.tick();
            }
        };
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // 方块被替换/破坏时掉落内部剩余物品。
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof LootItemVaultBlockEntity vault) {
                vault.dropContents();
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}
