package com.N28N.aeronautics_fortress_arise.blocks.AimerBlock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 瞄准器的方块物品。
 *
 * <p>手持本物品右键一个可用作方向基准的方块（炮座 / 激光指示器）时，只记录选择、不放置方块；
 * 随后放置瞄准器时，选择才被写进新方块的方块实体。
 * 与 Create 机械臂的交互点选择流程一致：选择是临时状态，放置那一刻才落盘。
 *
 * <p>潜行右键跳过选择，按普通方块直接放置。
 */
public class AimerBlockItem extends BlockItem {

    /** 一次选择：记录维度，避免跨维度误绑。 */
    private record Selection(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /** 玩家当前选中的方向基准方块；仅服务端使用。 */
    private static final Map<UUID, Selection> SELECTIONS = new ConcurrentHashMap<>();

    public AimerBlockItem(final AimerBlock block, final Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        Player player = context.getPlayer();

        /*
         * 选中模式下不放置方块，两端都返回 SUCCESS：
         * 客户端返回 SUCCESS 才会把这次交互发包给服务端，两边据此镜像同一套判定。
         */
        if (player != null
                && !player.isShiftKeyDown()
                && AimerBlockEntity.isSupportedDirectionStandard(level.getBlockEntity(clicked))) {
            if (!level.isClientSide) {
                this.toggleSelection(level, player, clicked);
            }
            return InteractionResult.SUCCESS;
        }

        return super.useOn(context);
    }

    /** 再次右键同一个方块即取消选择。 */
    private void toggleSelection(final Level level, final Player player, final BlockPos pos) {
        Selection wanted = new Selection(level.dimension(), pos);

        if (wanted.equals(SELECTIONS.get(player.getUUID()))) {
            SELECTIONS.remove(player.getUUID());
            player.displayClientMessage(Component.translatable(
                    "message.aeronautics_fortress_arise.aimer.direction_standard.cleared"), true);
            return;
        }

        SELECTIONS.put(player.getUUID(), wanted);
        player.displayClientMessage(Component.translatable(
                "message.aeronautics_fortress_arise.aimer.direction_standard.selected"), true);
    }

    /**
     * 方块放置完成后把选择写入方块实体。
     *
     * <p>这是方块实体的创建时机，{@code updateCustomBlockEntityTag} 由 {@code BlockItem#place}
     * 在方块实体已就位后调用，正好可以用来落盘临时选择。
     */
    @Override
    protected boolean updateCustomBlockEntityTag(final BlockPos pos, final Level level,
                                                 @Nullable final Player player, final ItemStack stack,
                                                 final BlockState state) {
        boolean handled = super.updateCustomBlockEntityTag(pos, level, player, stack, state);

        if (level.isClientSide || player == null) {
            return handled;
        }

        Selection selection = SELECTIONS.remove(player.getUUID());
        if (selection == null || !selection.dimension().equals(level.dimension())) {
            return handled;
        }

        if (!(level.getBlockEntity(pos) instanceof AimerBlockEntity aimer)) {
            return handled;
        }

        boolean bound = aimer.bindDirectionStandard(selection.pos());
        player.displayClientMessage(Component.translatable(bound
                ? "message.aeronautics_fortress_arise.aimer.direction_standard.applied"
                : "message.aeronautics_fortress_arise.aimer.direction_standard.failed"), true);

        return handled;
    }
}
