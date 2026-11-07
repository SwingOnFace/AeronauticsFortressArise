package com.N28N.aeronautics_fortress_arise;

import com.N28N.aeronautics_fortress_arise.items.AimingDeviceItem;
import com.N28N.aeronautics_fortress_arise.items.OreVeinExtractorItem;
import com.tterrag.registrate.util.entry.ItemEntry;

/**
 * Item registration using Create's Registrate.
 */
public class AllItems {
/*
    public static final ItemEntry<Item> EXAMPLE_ITEM = AeronauticsFortressArise.REGISTRATE
            .item("example_item", Item::new)
            .register();*/

    /** 头部瞄准仪：可戴在头上，持有独特 UUID 用于与机械动力方块绑定。 */
    public static final ItemEntry<AimingDeviceItem> AIMING_DEVICE = AeronauticsFortressArise.REGISTRATE
            .item("aiming_device", AimingDeviceItem::new)
            .properties(p -> p.stacksTo(1))
            .model((context, provider) -> provider.withExistingParent(context.getName(), "minecraft:builtin/entity"))
            .register();

    /** 提取并转移矿脉等级的手持工具。 */
    public static final ItemEntry<OreVeinExtractorItem> ORE_VEIN_EXTRACTOR = AeronauticsFortressArise.REGISTRATE
            .item("ore_vein_extractor", OreVeinExtractorItem::new)
            .properties(p -> p.stacksTo(1))
            .model((context, provider) -> provider.withExistingParent(context.getName(), "minecraft:builtin/entity"))
            .register();

    public static void register() {
        // Force class loading to trigger Registrate calls
    }
}
