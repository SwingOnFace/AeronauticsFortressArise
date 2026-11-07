package com.N28N.aeronautics_fortress_arise.client.AimerBlock;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import net.minecraft.resources.ResourceLocation;


/**
 * Aimer 使用的独立旋转模型。
 */
public class AimerPartialModels {

    public static final PartialModel VERTICAL_ROTOR =
            PartialModel.of(
                    ResourceLocation.fromNamespaceAndPath(
                            AeronauticsFortressArise.ID,
                            "block/aimer/vertical_rotor"
                    )
            );

    public static final PartialModel SIDE_GEAR =
            PartialModel.of(
                    ResourceLocation.fromNamespaceAndPath(
                            AeronauticsFortressArise.ID,
                            "block/aimer/side_gear"
                    )
            );

    private AimerPartialModels() {
    }

    /**
     * 强制本类在模型烘焙前完成初始化。
     *
     * <p>Flywheel 会在 {@code ModelEvent.RegisterAdditional} 阶段把
     * {@link PartialModel#of} 创建过的全部模型登记为附加模型。若本类直到
     * 烘焙完成后才被加载，字段里的模型就不会被登记，{@code get()} 会返回 null。
     *
     * <p>因此需要在客户端初始化阶段（烘焙之前）调用一次本方法，触发静态初始化。
     */
    public static void init() {
        // 显式引用字段，确保静态初始化器执行。
        PartialModel first = VERTICAL_ROTOR;
        PartialModel second = SIDE_GEAR;
        if (first == null || second == null) {
            throw new IllegalStateException("Aimer partial models failed to initialize");
        }
    }
}
