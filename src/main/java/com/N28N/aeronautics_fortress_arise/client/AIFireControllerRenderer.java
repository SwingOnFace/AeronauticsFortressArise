package com.N28N.aeronautics_fortress_arise.client;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class AIFireControllerRenderer extends GeoBlockRenderer<AIFireControllerBlockEntity> {

    public AIFireControllerRenderer(BlockEntityRendererProvider.Context context) {
        super(new DefaultedBlockGeoModel<>(
                AeronauticsFortressArise.asResource("ai_fire_controller")));
    }
}