package com.N28N.aeronautics_fortress_arise.util;

import com.N28N.aeronautics_fortress_arise.config.AIStructureControllerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joml.Vector3d;

/**
 * 持久化的 AI 结构控制器索引。
 *
 * <p>内存里的 {@code hashToInstances} 只包含已加载区块中的控制器，
 * 重进存档后远处结构不会注册。这里把每个控制器的 UUID、维度、方块坐标与加载范围
 * 存进存档，启动时恢复，再按玩家距离决定是否加载对应区块。
 *
 * <p>控制器以 UUID 为身份标识：UUID 按创建顺序分配，分配进度（{@code LastAllocatedId}）
 * 与索引一起存档，重启后继续接着分配。位置仍然保留在条目内，
 * 因为区块票据（{@code TicketController#forceChunk}）必须以方块位置作为 owner。
 */
public final class AIStructureControllerIndexData extends SavedData {

    private static final String DATA_NAME = "aeronautics_fortress_arise_controllers";
    private static final String ENTRIES_TAG = "Controllers";
    private static final String LAST_ALLOCATED_ID_TAG = "LastAllocatedId";
    /** 顺序分配 UUID 时使用的命名空间高位（ASCII "AISTRUCT"）。 */
    private static final long ID_NAMESPACE = 0x4149535452554354L;
    private static final Factory<AIStructureControllerIndexData> FACTORY =
            new Factory<>(AIStructureControllerIndexData::new, AIStructureControllerIndexData::load);

    private final Map<UUID, ControllerEntry> controllers = new HashMap<>();
    /** 维度 + 方块位置 -> 控制器 UUID，用于区块票据校验和按位置反查。 */
    private final Map<ResourceKey<Level>, Map<Vector3d, UUID>> positionIndex = new HashMap<>();
    private long lastAllocatedId;

    /**
     * 一个控制器的持久记录。
     *
     * @param id        控制器 UUID，作为身份标识
     * @param dimension 所在维度
     * @param position  控制器的 home 世界坐标（区块票据需要）
     * @param loadRange 加载范围（区块）
     */
    public record ControllerEntry(UUID id, ResourceKey<Level> dimension, Vector3d position, int loadRange) {

        /** 该控制器的区块票据 owner 位置。 */
        public BlockPos ownerPos() {
            return AIStructureControllerIndexData.ownerPosOf(position);
        }
    }

    /**
     * 位置向量 → 区块票据 owner 方块坐标。
     * home 是带小数的世界坐标，而 {@code TicketController#forceChunk} 以方块位置为 owner，
     * 索引、票据施加与票据校验必须共用这一个取整规则。
     */
    public static BlockPos ownerPosOf(Vector3d position) {
        return BlockPos.containing(position.x, position.y, position.z);
    }

    public static AIStructureControllerIndexData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * 顺序分配一个新的控制器 UUID，并把分配进度写进存档。
     * <p>重启后会从上次分配到的位置继续，不会重复发放。
     */
    public UUID allocateId() {
        this.lastAllocatedId++;
        this.setDirty();
        return new UUID(ID_NAMESPACE, this.lastAllocatedId);
    }

    /**
     * 写入或更新一个控制器。同一维度同一位置只保留一个条目。
     */
    public void put(UUID id, ResourceKey<Level> dimension, Vector3d position, int loadRange) {
        ControllerEntry entry = new ControllerEntry(id, dimension, position, loadRange);
        ControllerEntry previous = this.controllers.get(id);
        if (entry.equals(previous)) {
            return;
        }
        if (previous != null) {
            this.removePositionIndex(previous);
        }
        Map<Vector3d, UUID> positions = this.positionIndex.computeIfAbsent(dimension, ignored -> new HashMap<>());
        UUID occupant = positions.get(position);
        if (occupant != null && !occupant.equals(id)) {
            // 旧存档迁移或 UUID 重新分配时，清掉同位置的旧条目。
            ControllerEntry evicted = this.controllers.remove(occupant);
            if (evicted != null) {
                this.removePositionIndex(evicted);
                positions = this.positionIndex.computeIfAbsent(dimension, ignored -> new HashMap<>());
            }
        }
        this.controllers.put(id, entry);
        positions.put(position, id);
        this.setDirty();
    }

    public void remove(UUID id) {
        ControllerEntry removed = this.controllers.remove(id);
        if (removed == null) {
            return;
        }
        this.removePositionIndex(removed);
        this.setDirty();
    }

    /** 没有 UUID 时的兜底删除：按维度 + 方块位置移除。 */
    public boolean removeAt(ResourceKey<Level> dimension, BlockPos position) {
        Map<Vector3d, UUID> positions = this.positionIndex.get(dimension);
        if (positions == null) {
            return false;
        }
        Vector3d key = findKey(positions, position);
        if (key == null) {
            return false;
        }
        this.remove(positions.get(key));
        return true;
    }

    public boolean contains(ResourceKey<Level> dimension, BlockPos position) {
        Map<Vector3d, UUID> positions = this.positionIndex.get(dimension);
        return positions != null && findKey(positions, position) != null;
    }

    /** 方块坐标 → 位置向量；与 {@link #put} 写入的键使用同一表示。 */
    private static Vector3d toVector(BlockPos position) {
        return new Vector3d(position.getX(), position.getY(), position.getZ());
    }

    /**
     * 查找与方块坐标对应的位置键。
     * 控制器存的是 home 世界坐标（可能带小数），所以先精确匹配，再按方块取整兜底。
     */
    @Nullable
    private static Vector3d findKey(Map<Vector3d, UUID> positions, BlockPos position) {
        Vector3d exact = toVector(position);
        if (positions.containsKey(exact)) {
            return exact;
        }
        for (Vector3d candidate : positions.keySet()) {
            if (AIStructureControllerIndexData.ownerPosOf(candidate).equals(position)) {
                return candidate;
            }
        }
        return null;
    }

    /** 按 UUID 查询条目；不存在时返回 null。 */
    @Nullable
    public ControllerEntry get(UUID id) {
        return this.controllers.get(id);
    }

    public List<ControllerEntry> entries(ResourceKey<Level> dimension) {
        List<ControllerEntry> result = new ArrayList<>();
        for (ControllerEntry entry : this.controllers.values()) {
            if (entry.dimension().equals(dimension)) {
                result.add(entry);
            }
        }
        return result;
    }

    private void removePositionIndex(ControllerEntry entry) {
        Map<Vector3d, UUID> positions = this.positionIndex.get(entry.dimension());
        if (positions == null) {
            return;
        }
        positions.remove(entry.position(), entry.id());
        if (positions.isEmpty()) {
            this.positionIndex.remove(entry.dimension());
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        this.controllers.forEach((id, entry) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("Id", id);
            entryTag.putString("Dimension", entry.dimension().location().toString());
            putPosition(entryTag, entry.position());
            entryTag.putInt("LoadRange", entry.loadRange());
            entries.add(entryTag);
        });
        tag.put(ENTRIES_TAG, entries);
        tag.putLong(LAST_ALLOCATED_ID_TAG, this.lastAllocatedId);
        return tag;
    }

    private static AIStructureControllerIndexData load(CompoundTag tag, HolderLookup.Provider registries) {
        AIStructureControllerIndexData data = new AIStructureControllerIndexData();
        boolean migrated = false;
        ListTag entries = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entryTag = entries.getCompound(i);
            ResourceLocation dimensionId = ResourceLocation.tryParse(entryTag.getString("Dimension"));
            if (dimensionId == null) {
                continue;
            }
            ResourceKey<Level> dimension = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimensionId);
            UUID id;
            if (entryTag.hasUUID("Id")) {
                id = entryTag.getUUID("Id");
            } else {
                // 旧格式条目没有 UUID：补发一个，下次保存时升级。
                id = new UUID(ID_NAMESPACE, ++data.lastAllocatedId);
                migrated = true;
            }
            data.putInternal(new ControllerEntry(id, dimension, readPosition(entryTag),
                    Math.max(0, Math.min(AIStructureControllerConfig.LOAD_RANGE_MAX,
                            entryTag.getInt("LoadRange")))));
        }
        data.lastAllocatedId = Math.max(data.lastAllocatedId,
                Math.max(Math.max(0L, tag.getLong(LAST_ALLOCATED_ID_TAG)), data.highestAllocatedId()));
        if (migrated) {
            data.setDirty();
        }
        return data;
    }

    /** 用三个 double 保存控制器位置向量。 */
    private static void putPosition(CompoundTag tag, Vector3d position) {
        tag.putDouble("PosX", position.x);
        tag.putDouble("PosY", position.y);
        tag.putDouble("PosZ", position.z);
    }

    /** 读取位置向量；旧存档仍是 {@code BlockPos.asLong()} 编码，读取时升级。 */
    private static Vector3d readPosition(CompoundTag tag) {
        if (tag.contains("PosX") && tag.contains("PosY") && tag.contains("PosZ")) {
            return new Vector3d(tag.getDouble("PosX"), tag.getDouble("PosY"), tag.getDouble("PosZ"));
        }
        BlockPos legacy = BlockPos.of(tag.getLong("Position"));
        return new Vector3d(legacy.getX(), legacy.getY(), legacy.getZ());
    }

    private void putInternal(ControllerEntry entry) {
        this.controllers.put(entry.id(), entry);
        this.positionIndex
                .computeIfAbsent(entry.dimension(), ignored -> new HashMap<>())
                .put(entry.position(), entry.id());
    }

    /** 已写入索引的 UUID 中最大的顺序号，用于修正丢失或过旧的分配进度。 */
    private long highestAllocatedId() {
        long highest = 0L;
        for (UUID id : this.controllers.keySet()) {
            if (id.getMostSignificantBits() == ID_NAMESPACE) {
                highest = Math.max(highest, id.getLeastSignificantBits());
            }
        }
        return highest;
    }
}
