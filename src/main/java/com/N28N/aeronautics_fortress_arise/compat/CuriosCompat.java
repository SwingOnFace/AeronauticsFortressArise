package com.N28N.aeronautics_fortress_arise.compat;

import com.N28N.aeronautics_fortress_arise.AllItems;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * Curios 兼容注册：把瞄准仪注册为 Curios head 槽饰品，
 * 使其同时支持原版头盔槽与饰品栏穿戴（参考机械动力护目镜）。
 *
 * <p>注意：本类引用了 CuriosApi，必须在确认 Curios 已加载后才调用
 * {@link #register()}（由主类用 {@code ModList.isLoaded("curios")} 把关），
 * 避免未安装 Curios 时触发 NoClassDefFoundError。
 */
public final class CuriosCompat {

    private CuriosCompat() {
    }

    /** 注册瞄准仪到 Curios head 槽。仅在 Curios 已加载时调用。 */
    public static void register() {
        CuriosApi.registerCurio(AllItems.AIMING_DEVICE.get(), new ICurioItem() {
            /** 允许右键物品直接穿戴到饰品栏。 */
            @Override
            public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
                return true;
            }
        });
    }
}
