package com.N28N.aeronautics_fortress_arise.compat.cbc;

import java.lang.reflect.Method;
import java.util.List;

import javax.annotation.Nullable;

import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBrain;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

/**
 * 弹药适配层：用 CBC 自身的 API 统一处理“任何可装进大炮的弹丸”。
 *
 * <p>CBC 把弹丸分成两类 Block：
 * <ul>
 *   <li>{@link ProjectileBlock}：标准 CBC 炮弹，以及第三方模组（如 CBC-Military-Supplement）的
 *       无壳炮弹 —— 它们同样继承 {@code ProjectileBlock}。</li>
 *   <li>仅实现 {@link BigCannonMunitionBlock} 的方块：例如 CBC-Military-Supplement 的双联炮炮弹
 *       （{@code cbcmoreshells:normal_ap_shot} 等）。它们不能用 {@code ProjectileBlock} 判定，
 *       否则会被整体漏掉。</li>
 * </ul>
 *
 * <p>弹道与初速也不依赖 CBC 的 munition 配置表（那对第三方弹药只会返回默认值），
 * 而是通过反射读取弹丸实体自身的实现，因此无需引入任何第三方编译依赖。
 */
public final class CBCMunitionAdapter {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 双联炮炮弹的公共基类，仅用于类名判断，避免编译依赖。 */
    private static final String DUAL_CANNON_PROJECTILE =
            "com.cainiao1053.cbcmoreshells.munitions.dual_cannon.AbstractDualCannonProjectile";

    private CBCMunitionAdapter() {
    }

    /** 是否是可以装填进炮管、并且是“弹丸”而非发射药的方块。 */
    public static boolean isCannonProjectileBlock(Block block) {
        return block instanceof BigCannonMunitionBlock && !(block instanceof BigCannonPropellantBlock);
    }

    public static boolean isCannonProjectile(Item item) {
        return item instanceof BlockItem blockItem && isCannonProjectileBlock(blockItem.getBlock());
    }

    /** 是否为 CBC 标准大炮能发射的 {@link ProjectileBlock} 类炮弹。 */
    public static boolean isBigCannonProjectile(Item item) {
        return item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ProjectileBlock<?>;
    }

    /** 是否为第三方标准炮（双管炮）专用炮弹，例如 {@code cbcmoreshells:normal_ap_shot}。 */
    public static boolean isStandardCannonProjectile(Item item) {
        return DualCannonCompat.isDualCannonMunition(new ItemStack(item));
    }

    /**
     * 按配置生成弹丸实体（仅用于读取弹道参数，调用方负责 {@code discard()}）。
     *
     * @return 无法生成时返回 {@code null}
     */
    @Nullable
    public static AbstractCannonProjectile createProjectile(Level level, ItemStack stack) {
        if (level == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        Block block = blockItem.getBlock();
        try {
            if (block instanceof ProjectileBlock<?> projectileBlock) {
                AbstractCannonProjectile projectile = projectileBlock.getProjectile(level, stack);
                AIFireControllerBrain.debug("createProjectile: {} via ProjectileBlock -> {}",
                        stack.getItem(), projectile == null ? "null" : projectile.getClass().getSimpleName());
                return projectile;
            }
            // 双联炮炮弹等：getProjectile(Level, ItemStack) 是公开方法，按签名反射调用。
            Method method = findMethod(block.getClass(), "getProjectile", Level.class, ItemStack.class);
            if (method == null) {
                LOGGER.warn("No getProjectile(Level, ItemStack) on {}", block.getClass().getName());
                return null;
            }
            Object result = method.invoke(block, level, stack);
            AbstractCannonProjectile projectile =
                    result instanceof AbstractCannonProjectile cannonProjectile ? cannonProjectile : null;
            AIFireControllerBrain.debug("createProjectile: {} via {}.{} -> {}",
                    stack.getItem(), method.getDeclaringClass().getSimpleName(), method.getName(),
                    projectile == null ? "null" : projectile.getClass().getSimpleName());
            return projectile;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Failed to create projectile entity for {}", stack.getItem(), exception);
            return null;
        }
    }

    /**
     * 读取弹丸自身的弹道参数。优先反射 {@code getBallisticProperties()}（CBC 定义、各模组重写），
     * 失败时回退到 CBC 的配置表查询。
     */
    public static BallisticPropertiesComponent getBallistics(AbstractCannonProjectile projectile) {
        Method method = findMethod(projectile.getClass(), "getBallisticProperties");
        if (method != null) {
            try {
                Object result = method.invoke(projectile);
                if (result instanceof BallisticPropertiesComponent properties) {
                    AIFireControllerBrain.debug("getBallistics: {} via {}.getBallisticProperties -> {}",
                            projectile.getClass().getSimpleName(),
                            method.getDeclaringClass().getSimpleName(), properties);
                    return properties;
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.warn("Failed to read ballistics of {}", projectile.getType(), exception);
            }
        } else {
            AIFireControllerBrain.debug("getBallistics: no getBallisticProperties() on {}, using CBC fallback",
                    projectile.getClass().getName());
        }
        return fallbackBallistics(projectile);
    }

    /**
     * 发射初速（格/tick）。
     *
     * <p>双联炮炮弹不使用发射药，速度由弹药自身的 {@code initial_vel} 决定，
     * 因此优先读取公开方法 {@code getInitVel()}；CBC 大炮仍按
     * 「装药 + 弹药自带装药」× 2 计算。
     */
    public static double getLaunchSpeed(AbstractCannonProjectile projectile, int chargePower) {
        Method method = findMethod(projectile.getClass(), "getInitVel");
        if (method != null) {
            try {
                Object result = method.invoke(projectile);
                if (result instanceof Number number) {
                    AIFireControllerBrain.debug("getLaunchSpeed: {} uses getInitVel()={}",
                            projectile.getClass().getSimpleName(), number.doubleValue());
                    return number.doubleValue();
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.warn("Failed to read initial velocity of {}", projectile.getType(), exception);
            }
        }
        double addedCharge = projectile instanceof AbstractBigCannonProjectile bigCannon
                ? bigCannon.addedChargePower()
                : 0.0;
        double speed = (chargePower + addedCharge) * 2.0;
        AIFireControllerBrain.debug("getLaunchSpeed: {} uses (charge {} + added {}) * 2 = {}",
                projectile.getClass().getSimpleName(), chargePower, addedCharge, speed);
        return speed;
    }

    /** 弹丸是否自带装药（例如无壳炮弹：装填后不需要再放药筒）。 */
    public static boolean hasIntegratedPropellant(AbstractCannonProjectile projectile) {
        if (projectile instanceof AbstractBigCannonProjectile bigCannon) {
            return bigCannon.addedChargePower() > 0;
        }
        // 双管炮炮弹完全不需要发射药。
        return isSubclassOf(projectile.getClass(), DUAL_CANNON_PROJECTILE);
    }

    /**
     * 解析弹药的发射参数，供弹道解算使用。
     *
     * <p>双管炮（shaolib 体系）的炮弹不是 CBC 实体，因此直接读取其数据包参数；
     * CBC 炮弹则创建一次实体读取自身弹道。
     */
    public static ResolvedProfile resolveProfile(Level level, ItemStack stack, int chargePower) {
        if (stack.isEmpty()) {
            return ResolvedProfile.EMPTY;
        }
        if (DualCannonCompat.isDualCannonMunition(stack)) {
            ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;
            DualCannonCompat.LaunchProfile profile = DualCannonCompat.getLaunchProfile(serverLevel, stack);
            AIFireControllerBrain.debug("resolveProfile: {} is a dual cannon munition, profile={}",
                    stack.getItem(), profile);
            return new ResolvedProfile(profile.initialVelocity(), ballisticComponent(profile), false);
        }
        AbstractCannonProjectile projectile = createProjectile(level, stack);
        if (projectile == null) {
            return ResolvedProfile.EMPTY;
        }
        try {
            return new ResolvedProfile(getLaunchSpeed(projectile, chargePower), getBallistics(projectile),
                    hasIntegratedPropellant(projectile));
        } finally {
            projectile.discard();
        }
    }

    private static BallisticPropertiesComponent ballisticComponent(DualCannonCompat.LaunchProfile profile) {
        BallisticPropertiesComponent defaults = BallisticPropertiesComponent.DEFAULT;
        return new BallisticPropertiesComponent(profile.gravity(), profile.drag(), profile.quadraticDrag(),
                defaults.durabilityMass(), defaults.penetration(), defaults.toughness(), defaults.deflection());
    }

    /** 解析后的发射参数。 */
    public record ResolvedProfile(double launchSpeed, BallisticPropertiesComponent properties,
                                  boolean integratedPropellant) {
        public static final ResolvedProfile EMPTY =
                new ResolvedProfile(0.0, BallisticPropertiesComponent.DEFAULT, false);

        public boolean isEmpty() {
            return this.launchSpeed <= 0.0;
        }
    }

    private static boolean isSubclassOf(Class<?> type, String className) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static BallisticPropertiesComponent fallbackBallistics(AbstractCannonProjectile projectile) {
        if (!(projectile instanceof AbstractBigCannonProjectile bigCannon)) {
            return BallisticPropertiesComponent.DEFAULT;
        }
        List<BallisticPropertiesComponent> candidates = List.of(
                CBCMunitionPropertiesHandlers.INERT_BIG_CANNON_PROJECTILE.getPropertiesOf(bigCannon).ballistics(),
                CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE.getPropertiesOf(bigCannon).ballistics(),
                CBCMunitionPropertiesHandlers.SHRAPNEL_SHELL.getPropertiesOf(bigCannon).ballistics(),
                CBCMunitionPropertiesHandlers.FLUID_SHELL.getPropertiesOf(bigCannon).ballistics(),
                CBCMunitionPropertiesHandlers.SMOKE_SHELL.getPropertiesOf(bigCannon).ballistics(),
                CBCMunitionPropertiesHandlers.MORTAR_STONE.getPropertiesOf(bigCannon).ballistics(),
                CBCMunitionPropertiesHandlers.DROP_MORTAR_SHELL.getPropertiesOf(bigCannon).ballistics());
        return candidates.stream()
                .filter(properties -> properties.gravity() != 0.0 || properties.drag() != 0.0)
                .findFirst()
                .orElse(BallisticPropertiesComponent.DEFAULT);
    }

    /** 沿继承链查找指定签名的方法（CBC 与第三方模组的方法多为 protected）。 */
    @Nullable
    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != parameterTypes.length
                        || !matches(method.getParameterTypes(), parameterTypes)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                } catch (RuntimeException exception) {
                    LOGGER.debug("Cannot access {}.{}", current.getName(), name, exception);
                }
                return method;
            }
        }
        return null;
    }

    private static boolean matches(Class<?>[] actual, Class<?>[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (!actual[i].equals(expected[i])) {
                return false;
            }
        }
        return true;
    }
}
