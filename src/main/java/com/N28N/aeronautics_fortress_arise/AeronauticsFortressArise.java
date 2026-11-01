package com.N28N.aeronautics_fortress_arise;

import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureControllerBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.LootItemVault.LootItemVaultBlockEntity;
import com.N28N.aeronautics_fortress_arise.compat.CuriosCompat;
import com.N28N.aeronautics_fortress_arise.compat.SableSchematicApiCompat;
import com.N28N.aeronautics_fortress_arise.config.AIFireControllerConfig;
import com.N28N.aeronautics_fortress_arise.config.AIStructureControllerConfig;
import com.N28N.aeronautics_fortress_arise.config.ModConfigPaths;
import com.N28N.aeronautics_fortress_arise.config.OreVeinConfig;
import com.N28N.aeronautics_fortress_arise.config.SubLevelConfigLoader;
import com.N28N.aeronautics_fortress_arise.networking.AIStructureControllerNetwork;
import com.N28N.aeronautics_fortress_arise.networking.AIFireControllerNetwork;
import com.N28N.aeronautics_fortress_arise.util.AIStructureRewardHandler;
import com.N28N.aeronautics_fortress_arise.util.AIStructureChunkLoadManager;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;

import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(AeronauticsFortressArise.ID)
public class AeronauticsFortressArise {
    public static final String ID = "aeronautics_fortress_arise";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(ID)
            .setTooltipModifierFactory(item ->
                    new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE)
                            .andThen(TooltipModifier.mapNull(KineticStats.create(item)))
            );

    public AeronauticsFortressArise(IEventBus modBus, ModContainer modContainer) {
        // 1. 加载配置
        ModConfigPaths.migrateLegacyFiles();
        SubLevelConfigLoader.loadConfig();
        OreVeinConfig.loadConfig();
        // 注册 AI 火控配置（服务端配置，写入 config 目录）。
        modContainer.registerConfig(ModConfig.Type.SERVER, AIFireControllerConfig.SPEC,
                AeronauticsFortressArise.ID + "/ai-fire-controller.toml");
        // 注册 AI 结构控制器配置（服务端配置，写入 config 目录）。
        modContainer.registerConfig(ModConfig.Type.SERVER, AIStructureControllerConfig.SPEC,
                AeronauticsFortressArise.ID + "/ai-structure-controller.toml");

        // 注册网络通道
        modBus.addListener(AIStructureControllerNetwork::register);
        modBus.addListener(AIFireControllerNetwork::register);

        // 5. 原有的 Registrate 和项目注册
        REGISTRATE.registerEventListeners(modBus);
        AllCreativeModeTabs.register();
        REGISTRATE.setCreativeTab(AllCreativeModeTabs.MAIN_TAB);
        AllItems.register();
        AllBlocks.register();

        // 6. 生命周期监听
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);
        // Curios 兼容（可选）：注册事件完成后再绑定饰品槽（此时物品 DeferredHolder 已绑定）。
        modBus.addListener(RegisterCapabilitiesEvent.class, this::onRegisterCapabilities);
        // 注册方块实体 capability（战利品保险箱的只读物品处理器）。
        modBus.addListener(LootItemVaultBlockEntity::registerCapabilities);

        // 7. 运行时事件
        AIStructureChunkLoadManager.register(modBus);
        NeoForge.EVENT_BUS.register(SubLevelGenerationListener.class);
        NeoForge.EVENT_BUS.addListener(SubLevelGenerationListener::onServerTick);
        NeoForge.EVENT_BUS.addListener(AIStructureControllerBlockEntity::updateChunkLoading);
        NeoForge.EVENT_BUS.addListener(AIStructureRewardHandler::onServerTick);

    }

    /**
     * Curios 兼容（可选）：把瞄准仪注册为 Curios head 槽饰品（同时保留原版头盔槽）。
     * <p>RegisterCapabilitiesEvent 在物品注册完成后触发，此时物品的 DeferredHolder
     * 已绑定，调用 {@code AllItems.AIMING_DEVICE.get()} 不再抛 unbound 异常。
     * ModList.isLoaded 把关：未安装 Curios 时不会加载 CuriosApi 相关类。
     */
    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        if (ModList.get().isLoaded("curios")) {
            CuriosCompat.register();
        }
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
    public static ResourceLocation id(String path) {
        return asResource(path);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Common setup...");

        //1、注册 AI 结构控制器的 Sable 结构图兼容性
        SableSchematicApiCompat.register(AllBlocks.AI_STRUCTURE_CONTROLLER.get());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Client setup...");
    }
}