package com.N28N.aeronautics_fortress_arise.blocks.AIStructureController;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.N28N.aeronautics_fortress_arise.networking.AIStructureControllerNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;

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
    private static final Logger LOGGER = LogUtils.getLogger();

    public AIStructureControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AIStructureControllerBlockEntity(AllBlocks.AI_STRUCTURE_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        boolean creative = player != null && player.isCreative();
        boolean shift = player != null && player.isShiftKeyDown();
        LOGGER.info("[AIStructCtrl] playerWillDestroy: pos={} side={} state={} player={} creative={} shift={}",
                pos, level.isClientSide ? "CLIENT" : "SERVER", state,
                player == null ? "null" : player.getGameProfile().getName(), creative, shift);

        if (!level.isClientSide) {
            AIStructureControllerBlockEntity controller =
                    level.getBlockEntity(pos) instanceof AIStructureControllerBlockEntity be ? be : null;
            if (controller == null) {
                LOGGER.warn("[AIStructCtrl] {} playerWillDestroy 时未找到控制器方块实体（be={}），无法处理拆除",
                        pos, level.getBlockEntity(pos));
            } else if (creative && shift) {
                // 必须在这里就标记 handled：否则 setRemoved 兜底会把它当成普通拆除，
                // 照样删除子空间并生成奖励。
                if (controller.markDestroyHandled()) {
                    controller.removeFromIndex();
                    LOGGER.info("[AIStructCtrl] {} 创造模式潜行拆除：已清理索引与强加载，"
                            + "不删除子空间、不生成奖励", pos);
                }
            } else if (controller.markDestroyHandled()) {
                controller.removeFromIndex();
                controller.scheduleControllerRemoval(player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        LOGGER.info("[AIStructCtrl] onRemove: pos={} side={} old={} new={} isMoving={}",
                pos, level.isClientSide ? "CLIENT" : "SERVER", state, newState, isMoving);
        super.onRemove(state, level, pos, newState, isMoving);

        // 同一方块的方块状态变化也走 onRemove，这里只处理真正被替换/破坏的情况。
        if (state.is(newState.getBlock())) {
            LOGGER.debug("[AIStructCtrl] {} onRemove 仅为方块状态变化，忽略", pos);
            return;
        }
        if (!level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof AIStructureControllerBlockEntity controller) {
                if (controller.markDestroyHandled()) {
                    LOGGER.info("[AIStructCtrl] {} 由 onRemove 触发拆除处理（非玩家直接破坏）", pos);
                    controller.removeFromIndex();
                    controller.scheduleControllerRemoval(null);
                }
            } else {
                LOGGER.warn("[AIStructCtrl] {} onRemove 时未找到控制器方块实体（be={}），交由 setRemoved 兜底",
                        pos, level.getBlockEntity(pos));
            }
        }
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
        // 手持方块物品时交回默认交互，否则控制器会吞掉右键、导致无法在它上面放置方块。
        if (stack.getItem() instanceof BlockItem) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
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
