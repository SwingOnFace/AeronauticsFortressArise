package com.N28N.aeronautics_fortress_arise.config;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * AI 结构控制器的配置项。
 * 所有回正参数均可通过配置文件调整。
 */
public class AIStructureControllerConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 悬浮高度（格）：home 的 y = 结构最高方块 + 悬浮高度。 */
    public static final ModConfigSpec.IntValue HOVER_HEIGHT = BUILDER
            .comment("悬浮高度（格），home 的 y 坐标 = 结构最高方块 + 悬浮高度。")
            .defineInRange("hoverHeight", 20, 1, 256);

    /** 允许的最大倾斜角（度），超过则硬性回正。 */
    public static final ModConfigSpec.DoubleValue MAX_TILT = BUILDER
            .comment("允许的最大倾斜角（度）。",
                    "倾斜超过该值后进入硬性回正（大幅提高回正速度快速扶正）。")
            .defineInRange("maxTilt", 30.0, 0.5, 90.0);

    /** 允许的最大位移偏移（格），超过则硬性回正。 */
    public static final ModConfigSpec.DoubleValue MAX_OFFSET = BUILDER
            .comment("允许的最大位移偏移（格）。",
                    "偏移超过该值后进入硬性回正（大幅提高回正速度快速拉回）。")
            .defineInRange("maxOffset", 32.0, 0.5, 64.0);

    /** 回正死区：偏移/倾斜低于此值不施加冲量，防止抖动。 */
    public static final ModConfigSpec.DoubleValue DEADZONE = BUILDER
            .comment("回正死区：偏移（格）或倾斜（度）低于此值时停止回正，防止抖动。")
            .defineInRange("deadzone", 0.1, 0.0, 1.0);

    // ------------------------------------------------------------------
    // PID 控制参数（位置/线性通道）
    // ------------------------------------------------------------------

    /** PID 比例增益 Kp：正比于当前偏差，偏差越大回正越猛。 */
    public static final ModConfigSpec.DoubleValue PID_KP = BUILDER
            .comment("PID 比例增益 Kp（线性通道）。",
                    "输出正比于当前偏差（距离），偏差越大回正越猛。")
            .defineInRange("pidKp", 0.5, 0.001, 10.0);

    /** PID 积分增益 Ki：累积历史偏差，消除稳态误差（飘着回不去时靠它）。 */
    public static final ModConfigSpec.DoubleValue PID_KI = BUILDER
            .comment("PID 积分增益 Ki（线性通道）。",
                    "累积历史偏差，消除稳态误差——结构停在偏离 home 的位置不动时靠它继续推。")
            .defineInRange("pidKi", 0.001, 0.0, 1.0);

    /** PID 微分增益 Kd：正比于偏差变化率，接近 home 时提前减速，抑制振荡。 */
    public static final ModConfigSpec.DoubleValue PID_KD = BUILDER
            .comment("PID 微分增益 Kd（线性通道）。",
                    "正比于偏差变化率，结构接近 home 时提前减速，防止冲过头和振荡。")
            .defineInRange("pidKd", 1, 0.0, 10.0);

    /** PID 积分上限（防 windup）：累积误差不会无限增长。 */
    public static final ModConfigSpec.DoubleValue PID_INTEGRAL_MAX = BUILDER
            .comment("PID 积分累积上限（线性通道）。",
                    "防止积分项无限增长导致回正失控（anti-windup）。")
            .defineInRange("pidIntegralMax", 10.0, 0.1, 100.0);

    // ------------------------------------------------------------------
    // PID 控制参数（姿态/角向通道）
    // ------------------------------------------------------------------

    /** 姿态 PID 比例增益 Kp：正比于当前倾斜角，倾斜越大扶正越猛。 */
    public static final ModConfigSpec.DoubleValue PID_ROT_KP = BUILDER
            .comment("PID 比例增益 Kp（姿态通道）。",
                    "输出正比于当前倾斜角，倾斜越大扶正越猛。")
            .defineInRange("pidRotKp", 0.5, 0.001, 10.0);

    /** 姿态 PID 积分增益 Ki：消除稳态倾斜误差。 */
    public static final ModConfigSpec.DoubleValue PID_ROT_KI = BUILDER
            .comment("PID 积分增益 Ki（姿态通道）。",
                    "累积历史倾斜偏差，消除稳态倾斜。")
            .defineInRange("pidRotKi", 0.002, 0.0, 1.0);

    /** 姿态 PID 微分增益 Kd：抑制扶正过程中的振荡。 */
    public static final ModConfigSpec.DoubleValue PID_ROT_KD = BUILDER
            .comment("PID 微分增益 Kd（姿态通道）。",
                    "正比于倾斜角变化率，接近水平时提前减速，防止扶正过猛产生振荡。")
            .defineInRange("pidRotKd", 0.5, 0.0, 10.0);

    /** 姿态 PID 积分上限。 */
    public static final ModConfigSpec.DoubleValue PID_ROT_INTEGRAL_MAX = BUILDER
            .comment("PID 积分累积上限（姿态通道）。",
                    "防止积分项无限增长导致扶正失控。")
            .defineInRange("pidRotIntegralMax", 30.0, 0.1, 300.0);

    /** 最大线性回正速度（格/tick），防止回正过猛把结构推过头。 */
    public static final ModConfigSpec.DoubleValue MAX_RESTORE_SPEED = BUILDER
            .comment("最大线性回正速度（格/tick）。",
                    "回正施加的线性速度不超过该值，防止结构回正过猛冲过 home。")
            .defineInRange("maxRestoreSpeed", 10, 0.01, 100.0);

    /** 最大角向回正速度（度/tick），防止扶正过猛导致振荡。 */
    public static final ModConfigSpec.DoubleValue MAX_RESTORE_ANGULAR_SPEED = BUILDER
            .comment("最大角向回正速度（度/tick）。",
                    "回正施加的角速度不超过该值，防止扶正过猛产生振荡。")
            .defineInRange("maxRestoreAngularSpeed", 10.0, 0.1, 90.0);

    /** 硬性回正的线性速度倍率（相对平滑回正），偏差超限时快速拉回。 */
    public static final ModConfigSpec.DoubleValue HARD_RESTORE_SPEED_MULTIPLIER = BUILDER
            .comment("硬性回正线性速度倍率（相对平滑回正的最大速度）。",
                    "偏差超过 maxOffset/maxTilt 时，回正速度按该倍率放大，快速拉回结构。")
            .defineInRange("hardRestoreSpeedMultiplier", 5.0, 1.0, 100.0);

    /** 硬性回正的角向速度倍率（相对平滑回正），偏差超限时快速扶正。 */
    public static final ModConfigSpec.DoubleValue HARD_RESTORE_ANGULAR_MULTIPLIER = BUILDER
            .comment("硬性回正角向速度倍率（相对平滑回正的最大角速度）。",
                    "偏差超过 maxOffset/maxTilt 时，角回正速度按该倍率放大，快速扶正结构。")
            .defineInRange("hardRestoreAngularMultiplier", 5.0, 1.0, 100.0);

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
            .defineInRange("rewardMinCount", 1, 0, 64);

    public static final ModConfigSpec.IntValue REWARD_MAX_COUNT = BUILDER
            .comment("结构被摧毁时生成的奖励方块最大数量（包含此值）。")
            .defineInRange("rewardMaxCount", 1, 0, 64);

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
