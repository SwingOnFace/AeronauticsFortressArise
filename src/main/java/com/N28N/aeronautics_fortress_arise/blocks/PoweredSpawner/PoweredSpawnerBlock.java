package com.N28N.aeronautics_fortress_arise.blocks.PoweredSpawner;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class PoweredSpawnerBlock extends KineticBlock implements EntityBlock {

    public PoweredSpawnerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == Direction.DOWN;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PoweredSpawnerBlockEntity(AllBlocks.POWERED_SPAWNER_BLOCK_ENTITY.get(), pos, state);
    }

    /**
     * 使用刷怪蛋右键点击方块时，将刷怪笼生成的生物设置为该刷怪蛋对应的生物。
     * 手持其他物品时返回 PASS，交给 Minecraft 默认的方块交互处理。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.getItem() instanceof SpawnEggItem egg
                && level.getBlockEntity(pos) instanceof PoweredSpawnerBlockEntity spawner) {
            if (!level.isClientSide) {
                EntityType<?> type = egg.getType(stack);
                spawner.setMobType(type);
                level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP,
                        SoundSource.BLOCKS, 1.0F, 1.0F);
                player.displayClientMessage(
                        Component.translatable(
                                "message.aeronautics_fortress_arise.spawner_set",
                                type.getDescription()),
                        true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (currentLevel, pos, currentState, blockEntity) -> {
            if (blockEntity instanceof PoweredSpawnerBlockEntity spawner) {
                spawner.tick();
            }
        };
    }
}