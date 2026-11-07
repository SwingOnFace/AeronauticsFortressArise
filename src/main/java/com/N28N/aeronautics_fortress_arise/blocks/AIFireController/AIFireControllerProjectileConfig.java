package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.N28N.aeronautics_fortress_arise.compat.cbc.CBCMunitionAdapter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

public final class AIFireControllerProjectileConfig {    private static final List<String> DEFAULT_ADDED_PROJECTILES = List.of(
            "createbigcannons:he_shell[createbigcannons:fuze=[{item:{count:1,id:\"createbigcannons:inertia_fuze\"},slot:0}]]",
            "createbigcannons:ap_shell[createbigcannons:fuze=[{item:{components:{\"createbigcannons:fuze_timer\":4},count:1,id:\"createbigcannons:delayed_inertia_fuze\"},slot:0}]]",
            "createbigcannons:shrapnel_shell[createbigcannons:fuze=[{item:{components:{\"createbigcannons:detonation_distance\":2},count:1,id:\"createbigcannons:proximity_fuze\"},slot:0}]]",
            "createbigcannons:fluid_shell[createbigcannons:fuze=[{item:{count:1,id:\"createbigcannons:inertia_fuze\"},slot:0}],createbigcannons:fluid_content={Fluid:{amount:2000,id:\"minecraft:lava\"}}]",
            "createbigcannons:smoke_shell[createbigcannons:fuze=[{item:{components:{\"createbigcannons:fuze_timer\":10},count:1,id:\"createbigcannons:timed_fuze\"},slot:0}]]");
    private static final Set<ResourceLocation> DEFAULT_REMOVED_PROJECTILES = Set.of(
            ResourceLocation.parse("createbigcannons:he_shell"),
            ResourceLocation.parse("createbigcannons:ap_shell"),
            ResourceLocation.parse("createbigcannons:shrapnel_shell"),
            ResourceLocation.parse("createbigcannons:fluid_shell"),
            ResourceLocation.parse("createbigcannons:smoke_shell"));

    private static final Set<String> addedProjectiles = new LinkedHashSet<>();
    private static final Set<ResourceLocation> removedProjectiles = new LinkedHashSet<>();

    static {
        reset();
    }

    private AIFireControllerProjectileConfig() {
    }

    public static synchronized void reset() {
        addedProjectiles.clear();
        addedProjectiles.addAll(DEFAULT_ADDED_PROJECTILES);
        removedProjectiles.clear();
        removedProjectiles.addAll(DEFAULT_REMOVED_PROJECTILES);
    }

    public static synchronized void add(String projectileStack) {
        if (projectileStack == null || projectileStack.isBlank()
                || !AIFireControllerBlockEntity.isProjectile(projectileStack)) {
            throw new IllegalArgumentException("Not a valid CBC projectile item string: " + projectileStack);
        }
        addedProjectiles.add(projectileStack);
    }

    public static synchronized void remove(String projectileId) {
        ResourceLocation id = parseId(projectileId);
        if (id == null) {
            throw new IllegalArgumentException("Invalid projectile item ID: " + projectileId);
        }
        removedProjectiles.add(id);
    }

    public static synchronized void removeAdded(String projectileStack) {
        addedProjectiles.remove(projectileStack);
    }

    public static synchronized void allow(String projectileId) {
        ResourceLocation id = parseId(projectileId);
        if (id == null) {
            throw new IllegalArgumentException("Invalid projectile item ID: " + projectileId);
        }
        removedProjectiles.remove(id);
    }

    private static ResourceLocation parseId(String projectileId) {
        return projectileId == null || projectileId.isBlank() ? null : ResourceLocation.tryParse(projectileId);
    }

    public static synchronized List<String> getAddedProjectiles() {
        return List.copyOf(addedProjectiles);
    }

    public static synchronized Set<ResourceLocation> getRemovedProjectiles() {
        return Set.copyOf(removedProjectiles);
    }

    /**
     * 按火炮模式筛选可选弹药。
     *
     * <p>大炮模式只列 {@code ProjectileBlock} 类炮弹，标准炮模式只列第三方标准炮注册过的弹药，
     * 避免玩家在界面上选到当前火炮打不出去的弹药（装错会直接炸膛）。
     */
    public static synchronized List<String> getSelectableProjectiles(CannonMode mode) {
        return Stream.concat(
                BuiltInRegistries.ITEM.stream()
                        .filter(item -> mode == CannonMode.STANDARD_CANNON
                                ? CBCMunitionAdapter.isStandardCannonProjectile(item)
                                : CBCMunitionAdapter.isBigCannonProjectile(item))
                        .map(BuiltInRegistries.ITEM::getKey)
                        .filter(id -> !removedProjectiles.contains(id))
                        .map(ResourceLocation::toString),
                addedProjectiles.stream())
                .distinct()
                .sorted()
                .toList();
    }
}
