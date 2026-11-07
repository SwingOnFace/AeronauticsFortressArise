package com.N28N.aeronautics_fortress_arise.compat.cbc;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBrain;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.CannonBehavior;
import rbasamoyai.createbigcannons.cannons.ICannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.breeches.quickfiring_breech.CannonMountPoint;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;

/**
 * 炮管装填/清膛适配层。
 *
 * <p>同时支持 CBC 标准大炮（{@link MountedBigCannonContraption}）与第三方模组的双联炮
 * （例如 CBC-Military-Supplement 的 {@code MountedDualCannonContraption}）。
 * 后者不引入编译依赖，仅通过类名识别，并用 CBC 通用的
 * {@link ICannonBlockEntity#cannonBehavior()} 完成装填与清膛。
 */
public final class CannonAmmoCompat {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 已提示过的不兼容组合，避免每次开炮都刷日志。 */
    private static final Set<String> WARNED_COMBINATIONS = ConcurrentHashMap.newKeySet();

    private CannonAmmoCompat() {
    }

    private static void warnOnce(String key, String message) {
        if (WARNED_COMBINATIONS.add(key)) {
            LOGGER.warn(message);
        }
    }

    /** 炮座上方是否装配了“双管炮”这类不使用发射药的火炮。 */
    public static boolean isDualCannon(AbstractMountedCannonContraption cannon) {
        return DualCannonCompat.isDualCannonContraption(cannon);
    }

    /**
     * 装填炮弹。CBC 大炮走官方 {@link CannonMountPoint#loadProjectile}，
     * 其余（双联炮）直接操作通用 {@link CannonBehavior}。
     */
    public static boolean loadProjectile(ItemStack stack, BigCannonMunitionBlock munition,
                                         PitchOrientedContraptionEntity contraptionEntity,
                                         AbstractMountedCannonContraption cannon) {
        if (cannon instanceof MountedBigCannonContraption bigCannon) {
            // CBC 大炮的发射逻辑只识别 ProjectileBlock，其余弹药（双联炮弹药）装进去也打不出去。
            if (!(munition instanceof ProjectileBlock<?>)) {
                warnOnce("cbc|" + munition.getClass().getName(),
                        "Standard CBC cannons can only fire ProjectileBlock munitions: "
                                + munition.getClass().getSimpleName() + " will not fire");
                return false;
            }
            CannonMountPoint.loadProjectile(stack, munition, contraptionEntity, bigCannon);
            AIFireControllerBrain.debug("loadProjectile: CBC big cannon <- {}", munition.getClass().getSimpleName());
            return true;
        }
        boolean dual = isDualCannon(cannon);
        if (dual) {
            // 双管炮只接受它自己注册过的弹药；装错会直接炸膛，因此先做严格校验。
            if (!DualCannonCompat.isDualCannonMunition(stack)) {
                warnOnce("dual|" + munition.getClass().getName(),
                        "Dual cannons can only fire their own munitions: "
                                + munition.getClass().getSimpleName() + " will not fire");
                return false;
            }
            boolean loaded = DualCannonCompat.loadProjectile(stack, munition, contraptionEntity, cannon);
            AIFireControllerBrain.debug("loadProjectile: dual cannon <- {} loaded={}",
                    munition.getClass().getSimpleName(), loaded);
            return loaded;
        }
        boolean loaded = loadDirectly(stack, munition, contraptionEntity, cannon, true);
        AIFireControllerBrain.debug("loadProjectile: cannon={} munition={} loaded={}",
                cannon == null ? "null" : cannon.getClass().getName(),
                munition.getClass().getSimpleName(), loaded);
        return loaded;
    }

    /** 装填药筒，仅 CBC 标准大炮使用。 */
    public static boolean loadCartridge(ItemStack stack, BigCannonMunitionBlock munition,
                                        PitchOrientedContraptionEntity contraptionEntity,
                                        AbstractMountedCannonContraption cannon) {
        if (cannon instanceof MountedBigCannonContraption bigCannon) {
            CannonMountPoint.loadCartridge(stack, munition, contraptionEntity, bigCannon);
            return true;
        }
        return loadDirectly(stack, munition, contraptionEntity, cannon, false);
    }

    /**
     * 直接通过方块行为装填：先移除炮尾处原有方块，再放入新弹药并同步到客户端。
     */
    private static boolean loadDirectly(ItemStack stack, BigCannonMunitionBlock munition,
                                        PitchOrientedContraptionEntity contraptionEntity,
                                        AbstractMountedCannonContraption cannon, boolean projectile) {
        if (cannon == null || contraptionEntity == null) {
            return false;
        }
        BlockPos startPos = cannon.getStartPos();
        Direction dir = cannon.initialOrientation();
        BlockEntity blockEntity = cannon.presentBlockEntities.get(startPos);
        if (!(blockEntity instanceof ICannonBlockEntity<?> cannonBlockEntity)) {
            LOGGER.warn("Cannot load ammo: {} at {} is not a cannon block entity",
                    cannon.getClass().getSimpleName(), startPos);
            return false;
        }
        if (!(cannonBlockEntity.cannonBehavior() instanceof BigCannonBehavior behavior)) {
            LOGGER.warn("Cannot load ammo: {} at {} has no big cannon behavior",
                    cannon.getClass().getSimpleName(), startPos);
            return false;
        }
        if (!projectile) {
            LOGGER.warn("Cannon {} does not accept cartridges", cannon.getClass().getSimpleName());
            return false;
        }
        behavior.removeBlock();
        StructureBlockInfo loadInfo = munition.getHandloadingInfo(
                stack, startPos, dir, contraptionEntity.level().registryAccess());
        boolean accepted = behavior.tryLoadingBlock(loadInfo);
        AIFireControllerBrain.debug("loadDirectly: startPos={} dir={} be={} accepted={}",
                startPos, dir, blockEntity.getClass().getSimpleName(), accepted);
        if (!accepted) {
            return false;
        }
        StructureBlockInfo oldInfo = cannon.getBlocks().get(startPos);
        if (oldInfo != null) {
            BigCannonBlock.writeAndSyncSingleBlockData(blockEntity, oldInfo, contraptionEntity, cannon);
        }
        return true;
    }

    /**
     * 清空炮管内所有已装填的弹药（炮弹和药筒）。
     *
     * <p>弹药以方块形式存放在炮管内，残留会导致炸膛，因此重新装填前必须先清空。
     *
     * @return 被清空的方块数量
     */
    public static int clear(AbstractMountedCannonContraption cannon,
                            PitchOrientedContraptionEntity contraptionEntity) {
        if (cannon == null || contraptionEntity == null) {
            return 0;
        }
        Direction dir = cannon.initialOrientation();
        BlockPos currentPos = cannon.getStartPos();
        Set<BlockPos> changes = new HashSet<>();
        while (cannon.presentBlockEntities.get(currentPos) instanceof ICannonBlockEntity<?> cannonBlockEntity
                && cannonBlockEntity.cannonBehavior() instanceof BigCannonBehavior behavior) {
            StructureBlockInfo info = behavior.block();
            if (info != null && !info.state().isAir()) {
                behavior.removeBlock();
                changes.add(currentPos);
            }
            currentPos = currentPos.relative(dir);
        }
        if (!changes.isEmpty()) {
            BigCannonBlock.writeAndSyncMultipleBlockData(changes, contraptionEntity, cannon);
        }
        return changes.size();
    }
}
