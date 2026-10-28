package com.N28N.aeronautics_fortress_arise.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AIStructureHeatmapData extends SavedData {

    private static final String DATA_NAME = "aeronautics_fortress_arise_heatmap";
    private static final String HEATMAP_TAG = "Heatmap";
    private static final String PROCESSED_TAG = "ProcessedChunks";
    private static final Factory<AIStructureHeatmapData> FACTORY =
            new Factory<>(AIStructureHeatmapData::new, AIStructureHeatmapData::load);

    private final Map<Long, Float> heatValues = new HashMap<>();
    private final Set<Long> processedChunks = new HashSet<>();

    public static AIStructureHeatmapData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public float getOrCreateNoise(long chunkKey, float generatedNoise) {
        Float storedNoise = this.heatValues.get(chunkKey);
        if (storedNoise != null) {
            return storedNoise;
        }
        this.heatValues.put(chunkKey, generatedNoise);
        this.setDirty();
        return generatedNoise;
    }

    public boolean isProcessed(long chunkKey) {
        return this.processedChunks.contains(chunkKey);
    }

    public void markProcessed(long chunkKey) {
        if (this.processedChunks.add(chunkKey)) {
            this.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag heatmap = new ListTag();
        this.heatValues.forEach((chunkKey, value) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Chunk", chunkKey);
            entry.putFloat("Noise", value);
            heatmap.add(entry);
        });
        tag.put(HEATMAP_TAG, heatmap);
        tag.put(PROCESSED_TAG, new LongArrayTag(this.processedChunks.stream()
                .mapToLong(Long::longValue)
                .toArray()));
        return tag;
    }

    private static AIStructureHeatmapData load(CompoundTag tag, HolderLookup.Provider registries) {
        AIStructureHeatmapData data = new AIStructureHeatmapData();
        ListTag heatmap = tag.getList(HEATMAP_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < heatmap.size(); i++) {
            CompoundTag entry = heatmap.getCompound(i);
            float noise = entry.getFloat("Noise");
            if (Float.isFinite(noise) && noise >= 0.0F && noise < 1.0F) {
                data.heatValues.put(entry.getLong("Chunk"), noise);
            }
        }
        for (long chunkKey : tag.getLongArray(PROCESSED_TAG)) {
            data.processedChunks.add(chunkKey);
        }
        return data;
    }
}
