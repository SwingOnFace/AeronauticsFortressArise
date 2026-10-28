package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.N28N.aeronautics_fortress_arise.networking.AIFireControllerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

public class AIFireControllerBlock extends KineticBlock implements EntityBlock {

    public AIFireControllerBlock(Properties properties) {
        super(properties);
    }

    /** AI 火控方块不产生任何掉落物。 */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of();
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face)
    {
        return face == Direction.DOWN;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("aeronautics_fortress_arise", "ai_fire_controller"));
        return new AIFireControllerBlockEntity(type, pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (currentLevel, pos, currentState, blockEntity) -> {
            if (blockEntity instanceof AIFireControllerBlockEntity controller) {
                controller.tick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        this.requestConfiguration(level, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private void requestConfiguration(Level level, BlockPos pos) {
        if (level.isClientSide) {
            AIFireControllerNetwork.C2SOpenScreenPacket packet =
                    new AIFireControllerNetwork.C2SOpenScreenPacket(pos.getX(), pos.getY(), pos.getZ());
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
        }
    }
}
