package com.N28N.aeronautics_fortress_arise.client.AntiIntrusionBlock;

import com.N28N.aeronautics_fortress_arise.client.ThroughWallCubeRenderer;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.blocks.AntiIntrusionBlock.AntiIntrusionBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class AntiIntrusionRenderer extends GeoBlockRenderer<AntiIntrusionBlockEntity> {

    public AntiIntrusionRenderer(BlockEntityRendererProvider.Context context) {
        super(new DefaultedBlockGeoModel<>(AeronauticsFortressArise.asResource("anti_intrusion")));
    }

    @Override
    public int getViewDistance() {
        // 默认通常较小，这里改成 512 格。
        // 可以改成 1024，但距离越大，客户端性能开销越高。
        return 512;
    }

    @Override
    public boolean shouldRenderOffScreen(AntiIntrusionBlockEntity blockEntity) {
        // 即使包围盒暂时不在屏幕视锥体内，也继续允许渲染。
        return true;
    }

    @Override
    public boolean shouldRender(AntiIntrusionBlockEntity blockEntity, Vec3 cameraPos) {
        // 无视视锥剔除与距离检查，始终渲染。
        // 默认实现会检查渲染包围盒是否在视锥内，导致方块移出视野后停止渲染；
        // 该方块需要"隔墙警示"效果，因此必须始终进入渲染流程。
        return true;
    }


    @Override
    public void render(
            AntiIntrusionBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay) {

        super.render(blockEntity, partialTick, poseStack, buffer, packedLight, packedOverlay);
        ThroughWallCubeRenderer.renderWarningCube(poseStack, buffer);
        float half = blockEntity.getPushRadius();
        float rangeMin = 0.5F - half;
        float rangeMax = 0.5F + half;
        ThroughWallCubeRenderer.renderCube(
                poseStack, buffer, rangeMin, rangeMin, rangeMin, rangeMax, rangeMax, rangeMax, 20);
    }
}
