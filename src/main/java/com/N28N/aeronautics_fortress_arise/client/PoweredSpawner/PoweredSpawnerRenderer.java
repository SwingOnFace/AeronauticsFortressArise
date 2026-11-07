package com.N28N.aeronautics_fortress_arise.client.PoweredSpawner;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.blocks.PoweredSpawner.PoweredSpawnerBlockEntity;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class PoweredSpawnerRenderer extends GeoBlockRenderer<PoweredSpawnerBlockEntity> {

    public PoweredSpawnerRenderer(BlockEntityRendererProvider.Context context) {
        super(new DefaultedBlockGeoModel<>(AeronauticsFortressArise.asResource("powered_spawner")));
    }
}
