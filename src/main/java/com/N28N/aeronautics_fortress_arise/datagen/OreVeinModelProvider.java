package com.N28N.aeronautics_fortress_arise.datagen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.AllBlocks;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * 为每一种矿脉方块生成方块状态与物品模型。
 *
 * <p>矿脉方块不维护自己的模型资源，而是直接指向对应矿石方块的模型：
 * <ul>
 *     <li>方块状态：{@code {"variants": {"": {"model": "&lt;矿石方块模型&gt;"}}}}</li>
 *     <li>物品模型：{@code {"parent": "&lt;矿石方块模型&gt;"}}</li>
 * </ul>
 * 矿石方块由配置项（矿石物品 ID）解析而来，因此新增配置项无需手写任何资源文件。
 */
public class OreVeinModelProvider implements DataProvider {

    private final PackOutput output;

    public OreVeinModelProvider(PackOutput output) {
        this.output = output;
    }

    /** 由 {@code runData} 触发；同时生成客户端资源，因此不区分客户端/服务端数据。 */
    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(true,
                (DataProvider.Factory<OreVeinModelProvider>) OreVeinModelProvider::new);
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        Path assets = this.output.getOutputFolder(PackOutput.Target.RESOURCE_PACK)
                .resolve(AeronauticsFortressArise.ID);

        List<CompletableFuture<?>> tasks = new ArrayList<>();
        AllBlocks.ORE_VEINS.forEach((oreId, entry) -> {
            String path = entry.getId().getPath();
            ResourceLocation oreModel = oreModelOf(oreId);

            tasks.add(DataProvider.saveStable(cache, blockStateJson(oreModel),
                    assets.resolve("blockstates/" + path + ".json")));
            tasks.add(DataProvider.saveStable(cache, itemModelJson(oreModel),
                    assets.resolve("models/item/" + path + ".json")));
        });
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
    }

    private static JsonObject blockStateJson(ResourceLocation oreModel) {
        JsonObject model = new JsonObject();
        model.addProperty("model", oreModel.toString());

        JsonObject variants = new JsonObject();
        variants.add("", model);

        JsonObject root = new JsonObject();
        root.add("variants", variants);
        return root;
    }

    private static JsonObject itemModelJson(ResourceLocation oreModel) {
        JsonObject root = new JsonObject();
        root.addProperty("parent", oreModel.toString());
        return root;
    }

    /** 配置项是矿石物品 ID，取其方块模型的 ID；解析失败时按同名方块模型回退。 */
    private static ResourceLocation oreModelOf(ResourceLocation oreId) {
        Block oreBlock = BuiltInRegistries.ITEM.getOptional(oreId)
                .map(item -> item instanceof BlockItem blockItem ? blockItem.getBlock() : null)
                .orElse(null);
        ResourceLocation blockId = oreBlock == null ? oreId : BuiltInRegistries.BLOCK.getKey(oreBlock);
        return blockId.withPrefix("block/");
    }

    @Override
    public String getName() {
        return "Ore vein blockstates and item models";
    }
}
