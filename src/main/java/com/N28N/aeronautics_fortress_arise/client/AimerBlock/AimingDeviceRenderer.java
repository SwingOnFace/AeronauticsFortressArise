package com.N28N.aeronautics_fortress_arise.client.AimerBlock;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.items.AimingDeviceItem;
import software.bernie.geckolib.model.DefaultedItemGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class AimingDeviceRenderer extends GeoItemRenderer<AimingDeviceItem> {

    public AimingDeviceRenderer() {
        super(new DefaultedItemGeoModel<>(AeronauticsFortressArise.asResource("aiming_device")));
    }
}
