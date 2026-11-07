package com.N28N.aeronautics_fortress_arise.blocks.AimerBlock;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.N28N.aeronautics_fortress_arise.items.AimingDeviceItem;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;

import dev.simulated_team.simulated.content.blocks.analog_transmission.AnalogTransmissionBlockEntity;
import dev.simulated_team.simulated.util.extra_kinetics.ExtraKinetics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

/**
 * 瞄准器（Aimer）方块。
 *
 * <p>一种 1:1 动力直通的控制方块，用于驱动其相邻的炮座（CannonMount）：
 * <ul>
 *     <li><b>可倾斜放置</b>：沿用 {@link RotatedPillarKineticBlock} 的 {@code AXIS}
 *     状态（X / Y / Z 三条轴），放置时按点击面决定旋转轴。</li>
 *     <li><b>动力直通</b>：沿朝向轴的两个面（底部输入 / 顶部输出）是轴
 *     （{@link #hasShaftTowards}），动力原样（1:1、应力不增加）传递给所驱动的炮座接口。</li>
 *     <li><b>四侧集成齿轮</b>：实现 {@link ICogWheel}（小齿轮），四个侧面可被
 *     外部齿轮 / 物理轴承咬合传动，兼容 contraption 上的旋转控制。</li>
 *     <li><b>瞄准控制</b>：用头部瞄准仪（{@link AimingDeviceItem}）右键绑定后，
 *     每 tick 读取佩戴者视线，投影到炮座当前旋转轴的垂直平面并写入炮座角度。</li>
 * </ul>
 */
public class AimerBlock extends RotatedPillarKineticBlock implements IBE<AimerBlockEntity>, ExtraKinetics.ExtraKineticsBlock {

    /**
     * 由旋转轴得到一个稳定的朝向。
     *
     * <p>方块继承自 {@link RotatedPillarKineticBlock}，状态里只有 {@code AXIS}
     * 而没有 {@code FACING}；模型与渲染统一用这个方法把轴还原成方向。
     */
    public static Direction facingOf(final BlockState state) {
        return Direction.get(Direction.AxisDirection.POSITIVE, state.getValue(AXIS));
    }

    public AimerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        /*
         * 手持头部瞄准仪时，绑定或解绑。
         */
        if (stack.getItem() instanceof AimingDeviceItem) {
            if (level.isClientSide) {
                return ItemInteractionResult.sidedSuccess(true);
            }

            if (!(level.getBlockEntity(pos) instanceof AimerBlockEntity aimer)) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }

            UUID uuid = AimingDeviceItem.getUuid(stack);

            if (uuid.equals(aimer.getBoundUuid())) {
                aimer.setBoundUuid(null);

                player.displayClientMessage(
                        Component.translatable(
                                "message.aeronautics_fortress_arise.aimer.unbound"
                        ),
                        true
                );
            } else {
                aimer.setBoundUuid(uuid);

                player.displayClientMessage(
                        Component.translatable(
                                "message.aeronautics_fortress_arise.aimer.bound"
                        ),
                        true
                );
            }

            return ItemInteractionResult.sidedSuccess(true);
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public IRotate getExtraKineticsRotationConfiguration() {
        return AnalogTransmissionBlockEntity.AnalogTransmissionCogwheel.EXTRA_COGWHEEL_CONFIG;
    }

    @Override
    public Direction.Axis getRotationAxis(final BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public boolean hasShaftTowards(final LevelReader world, final BlockPos pos, final BlockState state, final Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Class<AimerBlockEntity> getBlockEntityClass() {
        return AimerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AimerBlockEntity> getBlockEntityType() {
        return AllBlocks.AIMER_BLOCK_ENTITY.get();
    }
}
