package com.N28N.aeronautics_fortress_arise.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 持久化的 AI 结构控制器索引。
 *
 * <p>内存里的 {@code hashToInstances} 只包含已加载区块中的控制器，
 * 重进存档后远处结构不会注册。这里把每个控制器的维度、方块坐标与加载范围
 * 存进存档，启动时恢复，再按玩家距离决定是否加载对应区块。
 */
public final class AIStructureControllerIndexData extends SavedData {

    private static final String DATA_NAME = "aeronautics_fortress_arise_controllers";
    private static final String ENTRIES_TAG = "Controllers";
    private static final Factory<AIStructureControllerIndexData> FACTORY =
            new Factory<>(AIStructureControllerIndexData::new, AIStructureControllerIndexData::load);

    private final Map<ResourceKey<Level>, Map<Long, Integer>> controllers = new HashMap<>();

    public static AIStructureControllerIndexData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public void put(ResourceKey<Level> dimension, long position, int loadRange) {
        Map<Long, Integer> dimensionControllers =
                this.controllers.computeIfAbsent(dimension, ignored -> new HashMap<>());
        if (!Integer.valueOf(loadRange).equals(dimensionControllers.put(position, loadRange))) {
            this.setDirty();
        }
    }

    public void remove(ResourceKey<Level> dimension, long position) {
        Map<Long, Integer> dimensionControllers = this.controllers.get(dimension);
        if (dimensionControllers != null && dimensionControllers.remove(position) != null) {
            if (dimensionControllers.isEmpty()) {
                this.controllers.remove(dimension);
            }
            this.setDirty();
        }
    }

    public boolean contains(ResourceKey<Level> dimension, long position) {
        Map<Long, Integer> dimensionControllers = this.controllers.get(dimension);
        return dimensionControllers != null && dimensionControllers.containsKey(position);
    }

    public Map<Long, Integer> get(ResourceKey<Level> dimension) {
        Map<Long, Integer> dimensionControllers = this.controllers.get(dimension);
        return dimensionControllers == null ? Map.of() : Map.copyOf(dimensionControllers);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        this.controllers.forEach((dimension, dimensionControllers) ->
                dimensionControllers.forEach((position, loadRange) -> {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("Dimension", dimension.location().toString());
                    entry.putLong("Position", position);
                    entry.putInt("LoadRange", loadRange);
                    entries.add(entry);
                }));
        tag.put(ENTRIES_TAG, entries);
        return tag;
    }

    private static AIStructureControllerIndexData load(CompoundTag tag, HolderLookup.Provider registries) {
        AIStructureControllerIndexData data = new AIStructureControllerIndexData();
        ListTag entries = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString("Dimension"));
            if (dimensionId == null) {
                continue;
            }
            ResourceKey<Level> dimension = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimensionId);
            data.controllers
                    .computeIfAbsent(dimension, ignored -> new HashMap<>())
                    .put(entry.getLong("Position"), Math.max(0, Math.min(1024, entry.getInt("LoadRange"))));
        }
        return data;
    }
}
