package com.N28N.aeronautics_fortress_arise;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBlock;
import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.AntiIntrusionBlock.AntiIntrusionBlock;
import com.N28N.aeronautics_fortress_arise.blocks.AntiIntrusionBlock.AntiIntrusionBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.AntiIntrusionBlock.AntiIntrusionBlockItem;
import com.N28N.aeronautics_fortress_arise.blocks.CompactEngine.CompactEngineBlock;
import com.N28N.aeronautics_fortress_arise.blocks.CompactEngine.CompactEngineBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.CompactEngine.CompactEngineBlockItem;
import com.N28N.aeronautics_fortress_arise.blocks.CompactEngine.CompactEngineCasingBlock;
import com.N28N.aeronautics_fortress_arise.blocks.CompactEngine.CompactEngineCasingBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.LootItemVault.LootItemVaultBlock;
import com.N28N.aeronautics_fortress_arise.blocks.LootItemVault.LootItemVaultBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.OreVein.OreVeinBlock;
import com.N28N.aeronautics_fortress_arise.blocks.OreVein.OreVeinBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.PoweredSpawner.PoweredSpawnerBlock;
import com.N28N.aeronautics_fortress_arise.blocks.PoweredSpawner.PoweredSpawnerBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureControllerBlock;
import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureControllerBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.AimerBlock.AimerBlock;
import com.N28N.aeronautics_fortress_arise.blocks.AimerBlock.AimerBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.AimerBlock.AimerBlockItem;
import com.N28N.aeronautics_fortress_arise.client.AIFireController.AIFireControllerRenderer;
import com.N28N.aeronautics_fortress_arise.client.AIStructureController.AIStructureControllerRenderer;
import com.N28N.aeronautics_fortress_arise.client.AntiIntrusionBlock.AntiIntrusionRenderer;
import com.N28N.aeronautics_fortress_arise.client.OreVein.OreVeinRenderer;
import com.N28N.aeronautics_fortress_arise.client.PoweredSpawner.PoweredSpawnerRenderer;
import com.N28N.aeronautics_fortress_arise.config.OreVeinConfig;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.tterrag.registrate.util.nullness.NonNullSupplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Block registration using Create's Registrate.
 * <p>
 * Example:
 * <pre>
 * public static final BlockEntry&lt;Block&gt; EXAMPLE_BLOCK = ExampleMod.REGISTRATE
 *         .block("example_block", Block::new)
 *         .simpleItem()
 *         .register();
 * </pre>
 */
public class AllBlocks {

    /** 战利品保险库的类别。 */
    public enum VaultCategory {
        /** 原材料（矿石、锭、宝石等）。 */
        RAW("raw"),
        /** 杂物（食物、药水、杂项物品等）。 */
        MISC("misc"),
        /** 机械方块（机械动力等机械相关方块）。 */
        MACHINE("machine");

        /** 类别在资源路径中的后缀。 */
        public final String suffix;

        VaultCategory(String suffix) {
            this.suffix = suffix;
        }
    }

    /** 战利品保险库的等级数量。 */
    public static final int VAULT_LEVELS = 5;

    /** 所有战利品保险库方块，按 (类别, 等级) 索引。 */
    public static final Map<VaultCategory, BlockEntry<LootItemVaultBlock>[]> LOOT_VAULTS =
            new EnumMap<>(VaultCategory.class);

    public static final BlockEntry<AIFireControllerBlock> AI_FIRE_CONTROLLER =
            AeronauticsFortressArise.REGISTRATE
                    .block("ai_fire_controller", AIFireControllerBlock::new)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                    .properties(BlockBehaviour.Properties::noOcclusion)
                    .blockEntity(AIFireControllerBlockEntity::new)
                    .renderer(() -> AIFireControllerRenderer::new)
                    .build()
                    .simpleItem()
                    .register();

    /** 瞄准器：1:1 动力直通控制方块，用头部瞄准仪右键绑定后驱动相邻炮座对准玩家视线。 */
    public static final BlockEntry<AimerBlock> AIMER =
            AeronauticsFortressArise.REGISTRATE
                    .block("aimer", AimerBlock::new)
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                    .properties(BlockBehaviour.Properties::noOcclusion)
                    .item(AimerBlockItem::new)
                    .build()
                    .register();

    /** 瞄准器方块实体：渲染器与 Flywheel visual 都以这个类型为键。 */
    public static final BlockEntityEntry<AimerBlockEntity> AIMER_BLOCK_ENTITY =
            AeronauticsFortressArise.REGISTRATE
                    .blockEntity("aimer", AimerBlockEntity::new)
                    .validBlocks(AIMER)
                    .register();

    /** 动力刷怪笼：需要动力驱动的刷怪笼。 */
    public static final BlockEntry<PoweredSpawnerBlock> POWERED_SPAWNER =
        AeronauticsFortressArise.REGISTRATE
            .block("powered_spawner", PoweredSpawnerBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
            .properties(BlockBehaviour.Properties::noOcclusion)
            .simpleItem()
            .register();

    public static final BlockEntityEntry<PoweredSpawnerBlockEntity> POWERED_SPAWNER_BLOCK_ENTITY =
        AeronauticsFortressArise.REGISTRATE
            .blockEntity("powered_spawner", PoweredSpawnerBlockEntity::new)
            .validBlocks(POWERED_SPAWNER)
            .renderer(() -> PoweredSpawnerRenderer::new)
            .register();

    /** 紧凑型动力引擎：0 应力、可调转速，外观直接套用创造马达。 */
    public static final BlockEntry<CompactEngineBlock> COMPACT_ENGINE =
                    AeronauticsFortressArise.REGISTRATE
                        .block("compact_engine", CompactEngineBlock::new)
                        .initialProperties(SharedProperties::softMetal)
                        .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                        .properties(BlockBehaviour.Properties::noOcclusion)
                        .defaultBlockstate()
                        .blockEntity(CompactEngineBlockEntity::new)
                        .build()
                        .simpleItem()
                        .register();

    /** 3x3x3 大型紧凑引擎：放置时自动生成 26 个外壳方块组成结构。 */
    public static final BlockEntry<CompactEngineBlock> COMPACT_ENGINE_3 =
                    AeronauticsFortressArise.REGISTRATE
                        .block("compact_engine_3", p -> new CompactEngineBlock(p, 3))
                        .initialProperties(SharedProperties::softMetal)
                        .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                        .properties(BlockBehaviour.Properties::noOcclusion)
                        .defaultBlockstate()
                        .item(CompactEngineBlockItem::new)
                        .transform(ModelGen.customItemModel())
                        .register();

    public static final BlockEntityEntry<CompactEngineBlockEntity> COMPACT_ENGINE_3_BLOCK_ENTITY =
                    AeronauticsFortressArise.REGISTRATE
                        .blockEntity("compact_engine_3", CompactEngineBlockEntity::new)
                        .validBlocks(COMPACT_ENGINE_3)
                        .register();

    /** 5x5x5 巨型紧凑引擎：放置时自动生成 124 个外壳方块组成结构。 */
    public static final BlockEntry<CompactEngineBlock> COMPACT_ENGINE_5 =
                    AeronauticsFortressArise.REGISTRATE
                        .block("compact_engine_5", p -> new CompactEngineBlock(p, 5))
                        .initialProperties(SharedProperties::softMetal)
                        .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                        .properties(BlockBehaviour.Properties::noOcclusion)
                        .defaultBlockstate()
                        .item(CompactEngineBlockItem::new)
                        .transform(ModelGen.customItemModel())
                        .register();

    public static final BlockEntityEntry<CompactEngineBlockEntity> COMPACT_ENGINE_5_BLOCK_ENTITY =
                    AeronauticsFortressArise.REGISTRATE
                        .blockEntity("compact_engine_5", CompactEngineBlockEntity::new)
                        .validBlocks(COMPACT_ENGINE_5)
                        .register();

    /** 大型紧凑引擎的外壳方块（纯装饰，破坏时联动拆除所属结构）。 */
    public static final BlockEntry<CompactEngineCasingBlock> COMPACT_ENGINE_CASING =
                    AeronauticsFortressArise.REGISTRATE
                        .block("compact_engine_casing", CompactEngineCasingBlock::new)
                        .initialProperties(SharedProperties::softMetal)
                        .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                        .properties(BlockBehaviour.Properties::noOcclusion)
                        .simpleItem()
                        .register();

    /** 外壳方块实体：承载滚轮调转速交互，转发给结构中心的主方块。 */
    public static final BlockEntityEntry<CompactEngineCasingBlockEntity> COMPACT_ENGINE_CASING_BLOCK_ENTITY =
                    AeronauticsFortressArise.REGISTRATE
                        .blockEntity("compact_engine_casing", CompactEngineCasingBlockEntity::new)
                        .validBlocks(COMPACT_ENGINE_CASING)
                        .register();

    public static final BlockEntityEntry<CompactEngineBlockEntity> COMPACT_ENGINE_BLOCK_ENTITY =
                    AeronauticsFortressArise.REGISTRATE
                        .blockEntity("compact_engine", CompactEngineBlockEntity::new)
                        .validBlocks(COMPACT_ENGINE)
                        .register();

    public static final BlockEntry<AntiIntrusionBlock> ANTI_INTRUSION =
                    AeronauticsFortressArise.REGISTRATE
                        .block("anti_intrusion", AntiIntrusionBlock::new)
                        .initialProperties(SharedProperties::softMetal)
                        .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                        .properties(BlockBehaviour.Properties::noOcclusion)
                        .blockEntity(AntiIntrusionBlockEntity::new)
                        .renderer(() -> AntiIntrusionRenderer::new)
                        .build()
                        .item(AntiIntrusionBlockItem::new)
                        .model((context, provider) -> provider.withExistingParent(context.getName(), "minecraft:builtin/entity"))
                        .build()
                        .register();

    /** AI 结构控制器：在 Sable 子空间上记录 home 并执行亚空间回正，被摧毁时生成奖励结构。 */
    public static final BlockEntry<AIStructureControllerBlock> AI_STRUCTURE_CONTROLLER =
                    AeronauticsFortressArise.REGISTRATE
                        .block("ai_structure_controller", AIStructureControllerBlock::new)
                        .initialProperties(SharedProperties::softMetal)
                        .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                        .properties(BlockBehaviour.Properties::noOcclusion)
                        .simpleItem()
                        .register();

    public static final BlockEntityEntry<AIStructureControllerBlockEntity> AI_STRUCTURE_CONTROLLER_BLOCK_ENTITY =
                    AeronauticsFortressArise.REGISTRATE
                        .blockEntity("ai_structure_controller", AIStructureControllerBlockEntity::new)
                        .validBlocks(AI_STRUCTURE_CONTROLLER)
                        .renderer(() -> AIStructureControllerRenderer::new)
                        .register();

    /** 按矿石 ID 注册的独立矿脉方块。 */
    public static final Map<ResourceLocation, BlockEntry<OreVeinBlock>> ORE_VEINS =
            new LinkedHashMap<>();

    /** 所有矿脉共享的方块实体类型。 */
    public static final BlockEntityEntry<OreVeinBlockEntity> ORE_VEIN_BLOCK_ENTITY;

    static {
        for (Map.Entry<String, Integer> oreEntry : OreVeinConfig.getOreLootQualities().entrySet()) {
            String itemId = oreEntry.getKey();
            ResourceLocation oreId;
            try {
                oreId = ResourceLocation.parse(itemId);
            } catch (IllegalArgumentException exception) {
                AeronauticsFortressArise.LOGGER.warn("Invalid ore item id: {}", itemId);
                continue;
            }

            String blockName = oreId.getNamespace().equals("minecraft")
                    ? oreId.getPath() + "_vein"
                    : oreId.getNamespace() + "_" + oreId.getPath() + "_vein";
            BlockEntry<OreVeinBlock> vein = AeronauticsFortressArise.REGISTRATE
                    .block(blockName, properties -> new OreVeinBlock(properties, oreId, oreEntry.getValue()))
                    .initialProperties(SharedProperties::softMetal)
                    .properties(properties -> properties.sound(SoundType.STONE))
                    .properties(BlockBehaviour.Properties::noOcclusion)
                    .simpleItem()
                    .register();
            ORE_VEINS.put(oreId, vein);
        }

        @SuppressWarnings("unchecked")
        NonNullSupplier<? extends Block>[] validBlocks = ORE_VEINS.values().stream()
                .toArray(NonNullSupplier[]::new);
        ORE_VEIN_BLOCK_ENTITY = AeronauticsFortressArise.REGISTRATE
                .blockEntity("ore_vein", OreVeinBlockEntity::new)
                .validBlocks(validBlocks)
                .renderer(() -> OreVeinRenderer::new)
                .register();
    }

    /** 共享的方块实体类型（所有保险库共用）。 */
    public static final BlockEntityEntry<LootItemVaultBlockEntity> LOOT_VAULT_BLOCK_ENTITY;

    static {
        // 第一阶段：只注册所有方块，不注册 BlockEntityType
        for (VaultCategory category : VaultCategory.values()) {
            @SuppressWarnings("unchecked")
            BlockEntry<LootItemVaultBlock>[] entries =
                new BlockEntry[VAULT_LEVELS];

            for (int level = 1; level <= VAULT_LEVELS; level++) {
            String name = "loot_vault_" + category.suffix + "_" + level;

            ResourceKey<LootTable> lootTableKey = ResourceKey.create(
                    Registries.LOOT_TABLE,
                    AeronauticsFortressArise.asResource(
                            "chests/loot_vault/loot_vault_" + category.suffix + "_" + level
                    )
            );

            entries[level - 1] = AeronauticsFortressArise.REGISTRATE
                    .block(name, p -> new LootItemVaultBlock(p, lootTableKey))
                    .initialProperties(SharedProperties::softMetal)
                    .properties(p -> p.sound(SoundType.NETHERITE_BLOCK))
                    .properties(p -> p.destroyTime(10000))
                    .properties(p -> p.explosionResistance(3600000.0F))
                        .item()
                        .model((context, provider) -> provider.withExistingParent(
                            context.getName(),
                            AeronauticsFortressArise.asResource(
                                "item/loot_vault/" + context.getName()).toString()))
                        .build()
                    .register();
        }

        LOOT_VAULTS.put(category, entries);
        }

        // 第二阶段：收集全部 15 个方块
        // BlockEntry 本身实现了 NonNullSupplier，因此直接收集为数组。
        @SuppressWarnings("unchecked")
        NonNullSupplier<? extends Block>[] validBlocks = LOOT_VAULTS.values().stream()
                .flatMap(Arrays::stream)
                .toArray(NonNullSupplier[]::new);

        // 第三阶段：注册一个共享的 BlockEntityType，并绑定全部方块
        LOOT_VAULT_BLOCK_ENTITY = AeronauticsFortressArise.REGISTRATE
            .blockEntity("loot_item_vault", LootItemVaultBlockEntity::new)
            .validBlocks(validBlocks)
            .register();
    }


    /** 获取指定类别、等级的保险库方块。 */
    public static BlockEntry<LootItemVaultBlock> getVault(VaultCategory category, int level) {
        return LOOT_VAULTS.get(category)[level - 1];
    }

    public static void register() {
        // 空调用即可，触发类加载让静态字段初始化
    }
}
