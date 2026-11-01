package com.N28N.aeronautics_fortress_arise.blocks.CompactEngine;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 大型紧凑引擎外壳的方块实体。
 * <p>
 * 本身不参与动能网络，仅承载「转速调节」交互：
 * 玩家用滚轮点击外壳时，把目标转速转发给结构中心的主方块。
 */
public class CompactEngineCasingBlockEntity extends KineticBlockEntity {

    public ScrollValueBehaviour generatedSpeed;

    public CompactEngineCasingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        generatedSpeed = new KineticScrollValueBehaviour(
                CreateLang.translateDirect("kinetics.creative_motor.rotation_speed"),
                this, new CasingValueBox());
        generatedSpeed.between(-CompactEngineBlockEntity.MAX_SPEED, CompactEngineBlockEntity.MAX_SPEED);
        generatedSpeed.value = CompactEngineBlockEntity.DEFAULT_SPEED;
        generatedSpeed.withCallback(this::applyToController);
        behaviours.add(generatedSpeed);
    }

    @Override
    public void initialize() {
        super.initialize();
        // 与主方块同步当前转速显示
        CompactEngineBlockEntity controller = findController();
        if (controller != null) {
            generatedSpeed.value = controller.generatedSpeed.getValue();
        }
    }

    /** 把外壳上的转速目标转发给结构中心的主方块。 */
    private void applyToController(int value) {
        CompactEngineBlockEntity controller = findController();
        if (controller != null) {
            controller.setGeneratedSpeed(value);
        }
    }

    /** 在半径 2 的立方体内查找所属结构的主方块（5x5x5 中心到角各轴距离为 2）。 */
    private CompactEngineBlockEntity findController() {
        if (level == null) {
            return null;
        }
        for (BlockPos p : BlockPos.betweenClosed(worldPosition.offset(-2, -2, -2),
                worldPosition.offset(2, 2, 2))) {
            BlockState state = level.getBlockState(p);
            if (state.getBlock() instanceof CompactEngineBlock engine && engine.size > 1) {
                if (level.getBlockEntity(p) instanceof CompactEngineBlockEntity controller) {
                    return controller;
                }
            }
        }
        return null;
    }

    /** 外壳上的滚轮交互盒：任意面都可用，显示在该面中心偏外。 */
    static class CasingValueBox extends ValueBoxTransform.Sided {
        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 8, 12.5);
        }
    }
}
