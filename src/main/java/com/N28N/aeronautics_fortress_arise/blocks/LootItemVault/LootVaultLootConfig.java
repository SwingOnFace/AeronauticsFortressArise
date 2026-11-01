package com.N28N.aeronautics_fortress_arise.blocks.LootItemVault;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LootVaultLootConfig {
    private static final String TABLE_PREFIX = "chests/loot_vault/loot_vault_";
    private static final Map<ResourceLocation, MutableChanges> CHANGES = new LinkedHashMap<>();

    private LootVaultLootConfig() {
    }

    public static synchronized void reset() {
        CHANGES.clear();
    }

    public static synchronized void add(String category, int level, String itemId, int count) {
        ResourceLocation tableId = getTableId(category, level);
        ResourceLocation item = parseItemId(itemId);
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("Loot vault bonus count must be between 1 and 64: " + count);
        }

        MutableChanges changes = CHANGES.computeIfAbsent(tableId, ignored -> new MutableChanges());
        changes.added.add(new BonusItem(item, count));
    }

    public static synchronized void remove(String category, int level, String itemId) {
        ResourceLocation tableId = getTableId(category, level);
        ResourceLocation item = parseItemId(itemId);
        MutableChanges changes = CHANGES.computeIfAbsent(tableId, ignored -> new MutableChanges());
        changes.removed.add(item);
        changes.added.removeIf(entry -> entry.item().equals(item));
    }

    public static synchronized void clear(String category, int level) {
        MutableChanges changes = CHANGES.computeIfAbsent(getTableId(category, level), ignored -> new MutableChanges());
        changes.cleared = true;
        changes.removed.clear();
        changes.added.clear();
    }

    public static synchronized Changes getChanges(ResourceLocation tableId) {
        MutableChanges changes = CHANGES.get(tableId);
        if (changes == null) {
            return Changes.EMPTY;
        }
        return new Changes(changes.cleared, Set.copyOf(changes.removed), List.copyOf(changes.added));
    }

    private static ResourceLocation getTableId(String category, int level) {
        String normalizedCategory = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (!normalizedCategory.equals("raw")
                && !normalizedCategory.equals("misc")
                && !normalizedCategory.equals("machine")) {
            throw new IllegalArgumentException("Unknown loot vault category: " + category
                    + " (expected raw, misc, or machine)");
        }
        if (level < 1 || level > 5) {
            throw new IllegalArgumentException("Loot vault level must be between 1 and 5: " + level);
        }
        return AeronauticsFortressArise.asResource(TABLE_PREFIX + normalizedCategory + "_" + level);
    }

    private static ResourceLocation parseItemId(String itemId) {
        ResourceLocation id = itemId == null || itemId.isBlank() ? null : ResourceLocation.tryParse(itemId);
        if (id == null) {
            throw new IllegalArgumentException("Invalid loot vault item ID: " + itemId);
        }
        return id;
    }

    public record BonusItem(ResourceLocation item, int count) {
    }

    public record Changes(boolean cleared, Set<ResourceLocation> removed, List<BonusItem> added) {
        private static final Changes EMPTY = new Changes(false, Set.of(), List.of());
    }

    private static final class MutableChanges {
        private boolean cleared;
        private final Set<ResourceLocation> removed = new LinkedHashSet<>();
        private final List<BonusItem> added = new ArrayList<>();
    }
}
