package com.N28N.aeronautics_fortress_arise.client.AntiIntrusionBlock;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.blocks.AntiIntrusionBlock.AntiIntrusionBlockItem;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/** 物品形态复用方块模型与贴图。 */
public class AntiIntrusionItemRenderer extends GeoItemRenderer<AntiIntrusionBlockItem> {

    public AntiIntrusionItemRenderer() {
        super(new DefaultedBlockGeoModel<>(AeronauticsFortressArise.asResource("anti_intrusion")));
    }
}
