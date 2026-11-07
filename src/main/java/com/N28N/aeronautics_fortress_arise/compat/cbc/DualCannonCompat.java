package com.N28N.aeronautics_fortress_arise.compat.cbc;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

/**
 * CBC-Military-Supplement（武器拓展）标准炮/双管炮的兼容层。
 *
 * <p>该模组 3.0.x 的双管炮弹是 {@code shaolib} 项目体系，炮弹实体<b>不是</b> CBC 的
 * {@code AbstractCannonProjectile}，因此不能用 CBC 的 {@code getProjectile} 创建实体来读弹道；
 * 但它提供了公开的注册表 {@code CBCMSDualCannonMunitionRegistry}，以及一份
 * 数据包内的弹药参数 JSON。
 *
 * <p>本类全程通过类名 + 反射访问该模组，不引入编译依赖；模组未安装时所有方法都安全返回。
 */
public final class DualCannonCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String REGISTRY_CLASS =
            "com.cainiao1053.cbcmoreshells.munitions.dual_cannon.shaolib.CBCMSDualCannonMunitionRegistry";
    private static final String MOUNT_POINT_CLASS =
            "com.cainiao1053.cbcmoreshells.cannons.dual_cannon.breeches.quick_firing_breech.DualCannonMountPoint";
    private static final String CONTRAPTION_NAME = "DualCannonContraption";
    /** 弹药参数所在的数据包目录。 */
    private static final String MUNITION_DIR = "shaolib_munitions/munitions/";

    private static boolean registryLookupFailed;
    @Nullable
    private static Method registryOfMethod;
    private static boolean registryMethodResolved;

    private DualCannonCompat() {
    }

    /** 是否为该模组的双管炮（含标准炮）。 */
    public static boolean isDualCannonContraption(@Nullable Object cannon) {
        return cannon != null && cannon.getClass().getName().contains(CONTRAPTION_NAME);
    }

    /**
     * 该物品是否为双管炮可用的弹药。
     *
     * <p>以模组自己的注册表为准，这样可以排除鱼雷、火箭弹等虽然也是
     * {@code BigCannonMunitionBlock}、但双管炮打不出去的方块。
     */
    public static boolean isDualCannonMunition(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Object entry = lookupEntry(stack);
        if (entry != null) {
            return true;
        }
        return registryLookupFailed && looksLikeDualCannonBlock(stack.getItem());
    }

    /**
     * 读取弹药的发射参数（弹道 + 初速）。
     *
     * <p>优先读取数据包中的 {@code shaolib_munitions/munitions/<id>.json}，
     * 读取失败时回退到一个保守的近似值，保证 AI 仍能开火。
     */
    public static LaunchProfile getLaunchProfile(@Nullable ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return LaunchProfile.DEFAULT;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (level != null) {
            LaunchProfile parsed = readFromDatapack(level, itemId);
            if (parsed != null) {
                return parsed;
            }
        }
        return LaunchProfile.DEFAULT;
    }

    @Nullable
    private static LaunchProfile readFromDatapack(ServerLevel level, ResourceLocation itemId) {
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath(itemId.getNamespace(),
                MUNITION_DIR + itemId.getPath() + ".json");
        Optional<Resource> resource = level.getServer().getResourceManager().getResource(path);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject ballistics = root.has("ballistics") ? root.getAsJsonObject("ballistics") : new JsonObject();
            JsonObject dualCannon = root.has("dual_cannon") ? root.getAsJsonObject("dual_cannon") : new JsonObject();
            double gravity = getDouble(ballistics, "gravity", -0.04);
            double drag = getDouble(ballistics, "drag", 0.01);
            boolean quadratic = ballistics.has("quadratic_drag") && ballistics.get("quadratic_drag").getAsBoolean();
            double initialVelocity = getDouble(dualCannon, "initial_velocity", 6.0);
            double addedCharge = getDouble(dualCannon, "added_charge_power", 0.0);
            return new LaunchProfile(gravity, drag, quadratic, initialVelocity, addedCharge);
        } catch (Exception exception) {
            LOGGER.warn("Failed to read dual cannon munition data {}", path, exception);
            return null;
        }
    }

    private static double getDouble(JsonObject object, String key, double fallback) {
        return object.has(key) ? object.get(key).getAsDouble() : fallback;
    }

    /**
     * 按模组的装填逻辑向双管炮装填炮弹。
     *
     * @return true 表示装填成功
     */
    public static boolean loadProjectile(ItemStack stack, Object munition, Object contraptionEntity, Object cannon) {
        if (cannon == null || contraptionEntity == null) {
            return false;
        }
        // 优先使用模组自己的装填入口：它会处理弹匣、装填冷却与合法性校验。
        Method insert = findStatic(MOUNT_POINT_CLASS, "dualCannonInsert", 4);
        if (insert != null) {
            try {
                Object result = insert.invoke(null, stack, false, cannon, contraptionEntity);
                if (result instanceof ItemStack remaining) {
                    return remaining.getCount() < stack.getCount();
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.warn("DualCannonMountPoint.dualCannonInsert failed, falling back", exception);
            }
        }
        Method load = findStatic(MOUNT_POINT_CLASS, "loadProjectile", 4);
        if (load != null) {
            try {
                load.invoke(null, stack, munition, contraptionEntity, cannon);
                return true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.warn("DualCannonMountPoint.loadProjectile failed", exception);
            }
        }
        return false;
    }

    @Nullable
    private static Object lookupEntry(ItemStack stack) {
        if (registryLookupFailed) {
            return null;
        }
        if (!registryMethodResolved) {
            registryMethodResolved = true;
            try {
                registryOfMethod = Class.forName(REGISTRY_CLASS).getMethod("of", ItemStack.class);
            } catch (ClassNotFoundException | NoSuchMethodException exception) {
                // 模组未安装或版本不同：之后不再重试反射，改用类名兜底。
                registryLookupFailed = true;
                return null;
            }
        }
        if (registryOfMethod == null) {
            return null;
        }
        try {
            return registryOfMethod.invoke(null, stack);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Dual cannon registry lookup failed", exception);
            return null;
        }
    }

    private static boolean looksLikeDualCannonBlock(Item item) {
        if (!(item instanceof BlockItem blockItem)) {
            return false;
        }
        Block block = blockItem.getBlock();
        for (Class<?> current = block.getClass(); current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (current.getSimpleName().contains("DualCannonProjectileBlock")) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Method findStatic(String className, String methodName, int parameterCount) {
        try {
            for (Method method : Class.forName(className).getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        } catch (ClassNotFoundException exception) {
            // 模组未安装，忽略。
        }
        return null;
    }

    /** 双管炮弹药的发射参数。 */
    public record LaunchProfile(double gravity, double drag, boolean quadraticDrag,
                                double initialVelocity, double addedChargePower) {
        /** 数据缺失时的保守近似：接近该模组常见弹药（gravity -0.04 / drag 0.01 / 初速 6）。 */
        public static final LaunchProfile DEFAULT = new LaunchProfile(-0.04, 0.01, false, 6.0, 0.0);
    }
}
