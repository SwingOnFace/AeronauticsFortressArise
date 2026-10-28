package com.N28N.aeronautics_fortress_arise.blocks.AIStructureController;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.N28N.aeronautics_fortress_arise.networking.AIStructureControllerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * AI 结构控制器方块。
 *
 * <p>放置于 Sable 子空间（航空学飞行器）上时，负责：
 * <ul>
 *     <li>初次加载时记录 home 坐标（x/z 为放置位置，y 为结构最高方块 + 悬浮高度）</li>
 *     <li>运行时以可配置强度对结构进行亚空间回正（防倾斜、防位移、平滑过渡）</li>
 *     <li>被摧毁时在子空间对应的主世界位置随机生成奖励方块（保险库 / 矿脉）</li>
 * </ul>
 * 右键打开配置面板，可单独配置悬浮高度、奖励质量与奖励数量范围。
 */
public class AIStructureControllerBlock extends Block implements EntityBlock {

    public AIStructureControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AIStructureControllerBlockEntity(AllBlocks.AI_STRUCTURE_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof AIStructureControllerBlockEntity controller) {
            controller.removeFromIndex();
            controller.scheduleControllerRemoval(player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (currentLevel, pos, currentState, blockEntity) -> {
            if (blockEntity instanceof AIStructureControllerBlockEntity controller) {
                controller.tick();
            }
        };
    }

    /**
     * 右键打开配置面板。
     * 客户端发送打开请求，服务端确认后回发 S2C 携带当前配置值。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide) {
            // 客户端发送打开面板请求
            AIStructureControllerNetwork.C2SOpenScreenPacket packet =
                    new AIStructureControllerNetwork.C2SOpenScreenPacket(pos.getX(), pos.getY(), pos.getZ());
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
            return ItemInteractionResult.sidedSuccess(true);
        }
        return ItemInteractionResult.sidedSuccess(false);
    }
}
