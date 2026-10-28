package com.N28N.aeronautics_fortress_arise.client;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.items.OreVeinExtractorItem;
import software.bernie.geckolib.model.DefaultedItemGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class OreVeinExtractorRenderer extends GeoItemRenderer<OreVeinExtractorItem> {

    public OreVeinExtractorRenderer() {
        super(new DefaultedItemGeoModel<>(AeronauticsFortressArise.asResource("ore_vein_extractor")));
    }
}
