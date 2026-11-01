package com.N28N.aeronautics_fortress_arise.config;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * AI 结构控制器的配置项。
 * 悬浮高度与固定死区可通过配置文件调整。
 */
public class AIStructureControllerConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 回正死区：偏移/倾斜低于此值不写入位姿，防止抖动。 */
    public static final ModConfigSpec.DoubleValue DEADZONE = BUILDER
            .comment("回正死区：偏移（格）或倾斜（度）低于此值时不再改写子空间位姿，防止抖动。")
            .defineInRange("deadzone", 0.1, 0.0, 1.0);

    /** 玩家附近热力图扫描半径（区块）。 */
    public static final ModConfigSpec.IntValue CHUNK_SCAN_RADIUS = BUILDER
            .comment("以玩家为中心扫描并生成 AI 结构的圆形区块半径。")
            .defineInRange("chunkScanRadius", 100, 1, 1024);

    /** 每 tick 最多检查的热力图区块数，限制渐进扫描带来的服务器负载。 */
    public static final ModConfigSpec.IntValue MAX_CHUNK_SCANS_PER_TICK = BUILDER
            .comment("每个服务器 tick 最多检查的热力图区块数；检查会在后续 tick 中继续。")
            .defineInRange("maxChunkScansPerTick", 128, 1, 4096);

    //#region Reward settings

    /** 奖励质量最高等级（对应保险库最高 5 级）。 */
    public static final int REWARD_QUALITY_MAX = 5;

    /** 奖励方块数量上限（实例设置、NBT 读取与配置项共用）。 */
    public static final int REWARD_COUNT_MAX = 64;

    /** 区块加载范围上限（区块）。 */
    public static final int LOAD_RANGE_MAX = 1024;

    /** 默认奖励质量（1~5），控制器未单独设置时使用，子空间上无控制器时也用它。 */
    public static final ModConfigSpec.IntValue DEFAULT_REWARD_QUALITY = BUILDER
            .comment("默认奖励质量（1~5），质量越高奖励越好（奖励池中出现的保险库等级越高）。",
                    "控制器可通过右键配置面板单独覆盖。")
            .defineInRange("defaultRewardQuality", 1, 1, 5);

    /** 奖励方块池：是否包含战利品保险库。 */
    public static final ModConfigSpec.BooleanValue REWARD_INCLUDE_VAULTS = BUILDER
            .comment("奖励方块池是否包含战利品保险库（3 类别 × 5 等级共 15 种）。",
                    "结构被摧毁时按奖励数量范围生成奖励，从奖励池随机选择方块。")
            .define("rewardIncludeVaults", true);

    /** 奖励方块池：保险库最低等级（1~5）。 */
    public static final ModConfigSpec.IntValue REWARD_VAULT_MIN_LEVEL = BUILDER
            .comment("奖励方块池中保险库的最低等级（1~5），低于该等级的保险库不参与随机。")
            .defineInRange("rewardVaultMinLevel", 1, 1, 5);

    /** 奖励方块池：保险库最高等级（1~5）。 */
    public static final ModConfigSpec.IntValue REWARD_VAULT_MAX_LEVEL = BUILDER
            .comment("奖励方块池中保险库的最高等级（1~5），高于该等级的保险库不参与随机。")
            .defineInRange("rewardVaultMaxLevel", 3, 1, 5);

    /** 奖励方块池：是否包含矿脉。 */
    public static final ModConfigSpec.BooleanValue REWARD_INCLUDE_ORE_VEINS = BUILDER
            .comment("奖励方块池是否包含矿脉（按 ore_vein.json 配置的矿石列表注册）。")
            .define("rewardIncludeOreVeins", true);

    /** 奖励方块生成位置的水平随机偏移半径（格）。 */
    public static final ModConfigSpec.IntValue REWARD_SPAWN_OFFSET_RADIUS = BUILDER
            .comment("奖励方块生成时，相对子空间对应主世界 xz 位置的水平随机偏移半径（格）。",
                    "在该 xz 位置取最高的方块，于其上方生成奖励方块。")
            .defineInRange("rewardSpawnOffsetRadius", 2, 0, 16);

    /** 摧毁结构时生成的奖励方块数量范围（包含上下限）。 */
    public static final ModConfigSpec.IntValue REWARD_MIN_COUNT = BUILDER
            .comment("结构被摧毁时生成的奖励方块最小数量（包含此值）。")
            .defineInRange("rewardMinCount", 1, 0, REWARD_COUNT_MAX);

    public static final ModConfigSpec.IntValue REWARD_MAX_COUNT = BUILDER
            .comment("结构被摧毁时生成的奖励方块最大数量（包含此值）。")
            .defineInRange("rewardMaxCount", 1, 0, REWARD_COUNT_MAX);

    /** 详细记录奖励生成链路，便于定位监听、控制器查找与放置失败。 */
    public static final ModConfigSpec.BooleanValue REWARD_DEBUG_LOG = BUILDER
            .comment("启用 AI 结构奖励诊断日志；重启游戏后生效。")
            .define("rewardDebugLog", false);

    //#endregion

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AIStructureControllerConfig() {
    }

    public static void register() {
        AeronauticsFortressArise.LOGGER.info("Registering AI Structure Controller config");
    }
}
