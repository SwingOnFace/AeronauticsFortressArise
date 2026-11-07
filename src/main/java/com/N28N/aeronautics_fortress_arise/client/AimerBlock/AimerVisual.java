package com.N28N.aeronautics_fortress_arise.client.AimerBlock;

import java.util.function.Consumer;

import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.N28N.aeronautics_fortress_arise.blocks.AimerBlock.AimerBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;

import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;

/**
 * 瞄准器的 Flywheel visual。
 *
 * <p>静态外壳仍由方块模型渲染，这里只接管两个独立的旋转部件。
 * 两个部件共用同一条 Create 动力网络，但各自使用自己的速度倍率。
 */
public class AimerVisual extends KineticBlockEntityVisual<AimerBlockEntity>
        implements SimpleTickableVisual {

    private final RotatingInstance verticalRotor;
    private final RotatingInstance sideGear;

    public AimerVisual(VisualizationContext context, AimerBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);
        Axis axis = rotationAxis();
        verticalRotor = createInstance(
                AimerPartialModels.VERTICAL_ROTOR,
                axis,
                blockEntity.getSpeed()
        );
        sideGear = createInstance(
                AimerPartialModels.SIDE_GEAR,
                axis,
                blockEntity.getSpeed() * blockEntity.getRotationModifier()
        );
    }

    /**
     * 注册本 visual。
     *
     * <p>{@code neverSkipVanillaRender} 让 BER 继续运行，因为绑定瞄准仪后的
     * 方块表面高亮由 BER 绘制；BER 内部会在可视化接管时跳过旋转部件。
     * 必须在模型烘焙完成后、进入世界前调用。
     */
    public static void register() {
        SimpleBlockEntityVisualizer.builder(AllBlocks.AIMER_BLOCK_ENTITY.get())
                .factory(AimerVisual::new)
                .neverSkipVanillaRender()
                .apply();
    }

    private RotatingInstance createInstance(PartialModel model, Axis axis, float speed) {
        /*
         * 部件沿 +Y 建模，因此要把「模型的 UP」转到方块的旋转轴。
         * rotateToFace(from, to) 的语义是「把 from 转到 to」，所以第一个参数必须是 UP；
         * 若传 axis 的正方向，就变成「把 +axis 转到 +axis」，是单位旋转，部件会永远朝上。
         */
        RotatingInstance instance = instancerProvider()
                .instancer(AllInstanceTypes.ROTATING, Models.partial(model))
                .createInstance();
        instance.rotateToFace(Direction.UP, axis)
                .setup(blockEntity, axis, speed)
                .setPosition(getVisualPosition());
        instance.setChanged();
        return instance;
    }

    @Override
    public void update(float partialTick) {
        Axis axis = rotationAxis();
        verticalRotor.setup(blockEntity, axis, blockEntity.getSpeed())
                .setChanged();
        sideGear.setup(blockEntity, axis, blockEntity.getSpeed() * blockEntity.getRotationModifier())
                .setChanged();
    }

    @Override
    public void tick(TickableVisual.Context context) {
        applyOverstressEffect(blockEntity, verticalRotor, sideGear);
    }

    @Override
    public void updateLight(float partialTick) {
        relight(verticalRotor, sideGear);
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(verticalRotor);
        consumer.accept(sideGear);
    }

    @Override
    protected void _delete() {
        verticalRotor.delete();
        sideGear.delete();
    }
}
