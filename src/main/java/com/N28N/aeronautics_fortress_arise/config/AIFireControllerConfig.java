package com.N28N.aeronautics_fortress_arise.config;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * AI 火控方块的配置项。
 * 所有效率相关参数均可通过配置文件调整。
 */
public class AIFireControllerConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 效率相关配置。
    public static final ModConfigSpec.DoubleValue MAX_EFFICIENCY_SPEED = BUILDER
            .comment("达到满效率（效率=1.0）所需的接入转速。",
                    "转速越高，旋转速度和装填速度越快。")
            .defineInRange("maxEfficiencySpeed", 256.0, 1.0, 1024.0);

    public static final ModConfigSpec.IntValue BASE_SUPPLY_INTERVAL = BUILDER
            .comment("基础装填间隔（tick），即效率为 0 时的装填间隔。",
                    "数值越小装填越快。")
            .defineInRange("baseSupplyInterval", 100, 1, 10000);

    public static final ModConfigSpec.IntValue MIN_SUPPLY_INTERVAL = BUILDER
            .comment("满效率时的最短装填间隔（tick）。",
                    "数值越小装填越快。")
            .defineInRange("minSupplyInterval", 5, 1, 10000);

    public static final ModConfigSpec.DoubleValue BASE_ROTATION_SPEED = BUILDER
            .comment("基础旋转速度（度/tick），即效率为 0 时的旋转速度。",
                    "数值越大旋转越快。")
            .defineInRange("baseRotationSpeed", 2.0, 0.1, 100.0);

    public static final ModConfigSpec.DoubleValue MAX_ROTATION_SPEED = BUILDER
            .comment("满效率时的最大旋转速度（度/tick）。",
                    "数值越大旋转越快。")
            .defineInRange("maxRotationSpeed", 8.0, 0.1, 100.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AIFireControllerConfig() {
    }

    public static void register() {
        AeronauticsFortressArise.LOGGER.info("Registering AI Fire Controller config");
    }
}
