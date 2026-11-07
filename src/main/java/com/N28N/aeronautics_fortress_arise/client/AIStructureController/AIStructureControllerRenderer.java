package com.N28N.aeronautics_fortress_arise.client.AIStructureController;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureControllerBlockEntity;
import com.N28N.aeronautics_fortress_arise.client.ThroughWallCubeRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class AIStructureControllerRenderer extends GeoBlockRenderer<AIStructureControllerBlockEntity> {

    public AIStructureControllerRenderer(BlockEntityRendererProvider.Context context) {
        super(new DefaultedBlockGeoModel<>(AeronauticsFortressArise.asResource("ai_structure_controller")));
    }

    @Override
    public int getViewDistance() {
        return 512;
    }

    @Override
    public boolean shouldRenderOffScreen(AIStructureControllerBlockEntity blockEntity) {
        return true;
    }

    @Override
    public boolean shouldRender(AIStructureControllerBlockEntity blockEntity, Vec3 cameraPos) {
        return true;
    }

    @Override
    public void render(
            AIStructureControllerBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay) {
        super.render(blockEntity, partialTick, poseStack, buffer, packedLight, packedOverlay);
        ThroughWallCubeRenderer.renderWarningCube(poseStack, buffer);
    }
}
