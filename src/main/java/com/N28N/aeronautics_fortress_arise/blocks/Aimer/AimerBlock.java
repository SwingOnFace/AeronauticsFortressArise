package com.N28N.aeronautics_fortress_arise.blocks.Aimer;

import com.N28N.aeronautics_fortress_arise.items.AimingDeviceItem;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

/**
 * 瞄准器（Aimer）方块。
 *
 * <p>一种 1:1 动力直通的控制方块，用于驱动其相邻的炮座（CannonMount）：
 * <ul>
 *     <li><b>可倾斜放置</b>：拥有 {@link #FACING} 状态（6 个方向），
 *     放置时朝向玩家视线方向，可正放 / 倒放 / 侧放。</li>
 *     <li><b>动力直通</b>：沿朝向轴的两个面（底部输入 / 顶部输出）是轴
 *     （{@link #hasShaftTowards}），动力原样（1:1、应力不增加）传递给所驱动的炮座接口。</li>
 *     <li><b>四侧集成齿轮</b>：实现 {@link ICogWheel}（小齿轮），四个侧面可被
 *     外部齿轮 / 物理轴承咬合传动，兼容 contraption 上的旋转控制。</li>
 *     <li><b>瞄准控制</b>：用头部瞄准仪（{@link AimingDeviceItem}）右键绑定后，
 *     每 tick 读取佩戴者视线，投影到炮座当前旋转轴的垂直平面并写入炮座角度。</li>
 * </ul>
 */
public class AimerBlock extends KineticBlock implements EntityBlock, ICogWheel {

    /** 瞄准器朝向（决定旋转轴与渲染方向），支持任意方向。 */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public AimerBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    /** 放置时朝向玩家视线方向（可倾斜放置）。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection());
    }

    /** 旋转轴沿朝向轴。 */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /**
     * 接口配置（参考 Create 齿轮箱）：
     * <ul>
     *     <li>沿朝向轴的两个面（底部/顶部）是<b>轴</b>：动力从底部输入，
     *     1:1 直通到顶部输出给炮座接口（{@code CannonMountBlockEntity.getInterfacingBlockEntity}）。</li>
     *     <li>四个侧面为<b>集成齿轮</b>（实现 {@link ICogWheel} 小齿轮）：可与外部齿轮 /
     *     物理轴承咬合，兼容 contraption 上的旋转控制。
     *     Create 的 {@code RotationPropagator} 通过
     *     {@code ICogWheel.isSmallCog(stateFrom) && ICogWheel.isSmallCog(stateTo)}
     *     判定齿轮咬合；沿轴方向由于 {@code direction.getAxis() == getRotationAxis(state)}
     *     会自动跳过齿轮咬合，轴连接不受影响。</li>
     * </ul>
     */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(FACING).getAxis();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("aeronautics_fortress_arise", "aimer"));
        return new AimerBlockEntity(type, pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (currentLevel, pos, currentState, blockEntity) -> {
            if (blockEntity instanceof AimerBlockEntity aimer) {
                aimer.tick();
            }
        };
    }

    /**
     * 右键交互：手持头部瞄准仪（{@link AimingDeviceItem}）右键方块进行绑定 / 解绑。
     * <p>绑定关系为“瞄准仪 UUID → 瞄准器”。再次用同一瞄准仪右键则解绑。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(stack.getItem() instanceof AimingDeviceItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.sidedSuccess(true);
        }
        if (!(level.getBlockEntity(pos) instanceof AimerBlockEntity aimer)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        UUID uuid = AimingDeviceItem.getUuid(stack);
        if (uuid.equals(aimer.getBoundUuid())) {
            // 已绑定同一瞄准仪：解绑。
            aimer.setBoundUuid(null);
            player.displayClientMessage(
                    Component.translatable("message.aeronautics_fortress_arise.aimer.unbound"), true);
        } else {
            aimer.setBoundUuid(uuid);
            player.displayClientMessage(
                    Component.translatable("message.aeronautics_fortress_arise.aimer.bound"), true);
        }
        return ItemInteractionResult.sidedSuccess(true);
    }
}
