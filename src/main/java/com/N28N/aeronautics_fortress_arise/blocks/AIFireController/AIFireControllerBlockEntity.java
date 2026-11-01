package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import java.util.HashSet;
import java.util.Set;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.N28N.aeronautics_fortress_arise.config.AIFireControllerConfig;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.slf4j.Logger;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.breeches.quickfiring_breech.CannonMountPoint;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlockItem;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class AIFireControllerBlockEntity extends KineticBlockEntity implements GeoBlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RawAnimation ERROR_ANIMATION = RawAnimation.begin().thenLoop("error");
    private static final RawAnimation AIMING_ANIMATION = RawAnimation.begin().thenLoop("aiming");
    private static final RawAnimation FIRING_ANIMATION = RawAnimation.begin().thenPlay("fire");

    private static final int FEEDBACK_TICKS = 8;
    private static final int FIRE_ANIMATION_TICKS = 19;

    private static final int DEFAULT_PROPELLANT_POWER = 4;
    private static final int DEFAULT_ACCURACY = 80;
    private static final String DEFAULT_PROJECTILE = "createbigcannons:ap_shot";
    /** 炮口长度估算值（格）：CBC 未暴露炮管长度，按典型炮管取 5。 */
    public static final double DEFAULT_BARREL_LENGTH = 5.0;

    private boolean cannonRecognized;
    private boolean targetDetected;
    private int fireAnimationCooldownTicks;
    private int loadingFeedbackTicks;
    private int firingFeedbackTicks;
    private int propellantPower = DEFAULT_PROPELLANT_POWER;
    private int accuracy = DEFAULT_ACCURACY;
    private String projectileId = DEFAULT_PROJECTILE;
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    
    private final AIFireControllerBrain brain = new AIFireControllerBrain(this);

    public AIFireControllerBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
        this.brain.init();
        this.brain.setAccuracy(this.accuracy / 100.0f);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "status", 0, state -> {
            return state.setAndContinue(this.targetDetected ? AIMING_ANIMATION : ERROR_ANIMATION);
        }).triggerableAnim("fire", FIRING_ANIMATION));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animationCache;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        if (this.fireAnimationCooldownTicks > 0) {
            this.fireAnimationCooldownTicks--;
        }
        if (this.getSpeed() == 0) {
            this.setTargetDetected(false);
            this.brain.resetCombatState();
            return;
        }
        // 装填由 AI 在真正开炮前执行，这里只按间隔刷新炮座状态。
        if (this.level.getGameTime() % this.getSupplyInterval() == 0) {
            this.updateCannonFeedback();
        }
        this.tickAi();
        this.tickFeedback();
    }

    /**
     * AI 决策入口。所有“瞄准 / 开火 / 索敌”等决策逻辑已封装到
     * {@link AIFireControllerBrain} 中，方块实体只负责调用。
     */
    private void tickAi() {
        this.brain.tick();
    }

    private void tickFeedback() {
        if (this.level == null || this.level.isClientSide || (this.loadingFeedbackTicks == 0
                && this.firingFeedbackTicks == 0)) {
            return;
        }
        if (this.loadingFeedbackTicks > 0) {
            this.loadingFeedbackTicks--;
        }
        if (this.firingFeedbackTicks > 0) {
            this.firingFeedbackTicks--;
        }
        this.sendData();
    }

    private void updateCannonFeedback() {
        CannonMountBlockEntity cannonMount = this.getCannonMountAbove();
        boolean recognized = cannonMount != null && cannonMount.isRunning() && cannonMount.getContraption() != null;
        if (this.cannonRecognized != recognized) {
            this.cannonRecognized = recognized;
            this.sendData();
        }
    }

    public boolean isCannonRecognized() {
        return this.cannonRecognized;
    }

    public void setTargetDetected(boolean targetDetected) {
        if (this.targetDetected == targetDetected) {
            return;
        }
        this.targetDetected = targetDetected;
        this.sendData();
    }

    public float getLoadingFeedback(float partialTick) {
        return Math.max(0, this.loadingFeedbackTicks - partialTick) / (float) FEEDBACK_TICKS;
    }

    public float getFiringFeedback(float partialTick) {
        return Math.max(0, this.firingFeedbackTicks - partialTick) / (float) FEEDBACK_TICKS;
    }

    public float getControlSpeed() {
        return this.getSpeed();
    }

    public boolean hasMechanicalPower() {
        return this.getSpeed() != 0;
    }
    public int getPropellantPower() {
        return this.propellantPower;
    }

    public int getAccuracy() {
        return this.accuracy;
    }

    public String getProjectileId() {
        return this.projectileId;
    }

    public void setPropellantPower(int value) {
        int clamped = Math.max(1, Math.min(8, value));
        if (this.propellantPower == clamped) {
            return;
        }
        this.propellantPower = clamped;
        this.brain.init();
        this.setChanged();
        this.sendData();
    }

    public void setAccuracy(int value) {
        int clamped = Math.max(0, Math.min(100, value));
        if (this.accuracy == clamped) {
            return;
        }
        this.accuracy = clamped;
        this.brain.setAccuracy(clamped / 100.0f);
        this.setChanged();
        this.sendData();
    }

    public boolean setProjectileId(String projectileStack) {
        HolderLookup.Provider registries = this.level == null ? null : this.level.registryAccess();
        if (createProjectileStack(projectileStack, registries).isEmpty()) {
            return false;
        }
        if (this.projectileId.equals(projectileStack)) {
            return true;
        }
        this.projectileId = projectileStack;
        this.brain.init();
        this.setChanged();
        this.sendData();
        return true;
    }

    public static ResourceLocation getProjectileItemId(String projectileStack) {
        int componentsStart = projectileStack.indexOf('[');
        String itemId = componentsStart < 0 ? projectileStack : projectileStack.substring(0, componentsStart);
        return ResourceLocation.tryParse(itemId);
    }

    public static boolean isProjectile(String projectileStack) {
        ResourceLocation itemId = getProjectileItemId(projectileStack);
        return itemId != null && BuiltInRegistries.ITEM.getOptional(itemId)
                .filter(item -> item instanceof BlockItem blockItem
                        && blockItem.getBlock() instanceof ProjectileBlock)
                .isPresent();
    }

    public static ItemStack createProjectileStack(String projectileStack, HolderLookup.Provider registries) {
        if (registries == null || !isProjectile(projectileStack)) {
            return ItemStack.EMPTY;
        }
        try {
            StringReader reader = new StringReader(projectileStack);
            ItemParser.ItemResult parsedInput = new ItemParser(registries).parse(reader);
            if (reader.canRead()) {
                LOGGER.warn("Ignoring trailing input in projectile stack config: {}", reader.getRemaining());
                return ItemStack.EMPTY;
            }
            ItemStack stack = new ItemStack(parsedInput.item().value());
            stack.applyComponents(parsedInput.components());
            ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (stack.isEmpty() || !isProjectile(resultId.toString())) {
                LOGGER.warn("Configured item stack is not a valid CBC projectile: {}", projectileStack);
                return ItemStack.EMPTY;
            }
            return stack;
        } catch (CommandSyntaxException exception) {
            LOGGER.warn("Invalid projectile stack config: {}", projectileStack, exception);
            return ItemStack.EMPTY;
        }
    }

    /**
     * 根据效率计算当前装填间隔（tick）。效率越高，装填越快。
     * 采用平方根曲线：低效率时收益递增，高效率时收益递减，
     * 使转速刚起步时就能获得明显的装填加速，越接近满效率提速越平缓。
     *
     * <p>装填只在开炮前进行，因此该间隔同时决定最大开火频率。
     */
    public int getSupplyInterval() {
        float efficiency = this.getEfficiency();
        int base = AIFireControllerConfig.BASE_SUPPLY_INTERVAL.get();
        int min = AIFireControllerConfig.MIN_SUPPLY_INTERVAL.get();
        double curve = Math.sqrt(efficiency);
        return (int) Math.round((min + (base - min) * (1 - curve)) * Math.sqrt((double) propellantPower));
    }

    private float getEfficiency() {
        return this.getSpeed() / 256f;
    }

    /**
     * 根据效率计算当前最大旋转速度（度/tick）。效率越高，旋转越快。
     */
    float getRotationSpeed() {
        float efficiency = this.getEfficiency();
        float base = AIFireControllerConfig.BASE_ROTATION_SPEED.get().floatValue();
        float max = AIFireControllerConfig.MAX_ROTATION_SPEED.get().floatValue();
        return base + (max - base) * efficiency;
    }

    public CannonMountBlockEntity getCannonMountAbove() {
        if (this.level == null) {
            return null;
        }
        BlockEntity blockEntity = this.level.getBlockEntity(this.worldPosition.relative(Direction.UP));
        return blockEntity instanceof CannonMountBlockEntity cannonMount ? cannonMount : null;
    }

    public boolean setCannonAim(float yaw, float pitch) {
        CannonMountBlockEntity cannonMount = this.getCannonMountAbove();
        if (cannonMount == null || !cannonMount.isRunning() || cannonMount.getContraption() == null) {
            return false;
        }
        cannonMount.setYaw(yaw);
        cannonMount.setPitch(pitch);
        return true;
    }

    /**
     * 获取炮座上方装配的大炮 contraption（MountedBigCannonContraption）。
     * 大炮的弹药不是通过 IItemHandler 容器存储，而是以方块形式直接存放在炮管内，
     * 因此这里返回的是大炮 contraption 本身，用于后续的装填操作。
     */
    public MountedBigCannonContraption getMountedCannon() {
        CannonMountBlockEntity cannonMount = this.getCannonMountAbove();
        if (cannonMount == null) {
            return null;
        }
        PitchOrientedContraptionEntity contraption = cannonMount.getContraption();
        if (contraption == null || !(contraption.getContraption() instanceof MountedBigCannonContraption bigCannon)) {
            return null;
        }
        return bigCannon;
    }

    /**
     * 获取炮座上方装配的 PitchOrientedContraptionEntity（用于装填时同步数据）。
     */
    public PitchOrientedContraptionEntity getCannonContraptionEntity() {
        CannonMountBlockEntity cannonMount = this.getCannonMountAbove();
        if (cannonMount == null) {
            return null;
        }
        return cannonMount.getContraption();
    }

    /**
     * 炮口长度（格）：从炮座枢轴（控制器上方格中心）到炮管末端的距离。
     *
     * <p>弹道解算以枢轴为原点，而弹丸实际从炮口出膛，因此需要把这段长度作为初始位移
     * 补偿进弹道。CBC 没有暴露炮管长度的公开接口，这里统一按 {@link #DEFAULT_BARREL_LENGTH} 估算。
     */
    public double getBarrelLength() {
        return DEFAULT_BARREL_LENGTH;
    }

    /**
     * 装填炮弹（穿甲弹）。大炮装填顺序：先装炮弹，再装药筒。
     */
    public ItemStack insertCannonProjectile(ItemStack stack) {
        return this.insertCannonAmmo(stack, true);
    }

    /**
     * 装填药筒（火药）。大炮装填顺序：先装炮弹，再装药筒。
     */
    public ItemStack insertCannonPropellant(ItemStack stack) {
        return this.insertCannonAmmo(stack, false);
    }

    /**
     * 清空炮管内所有已装填的弹药（炮弹和药筒）。
     * 大炮的弹药以方块形式存放在炮管内，发射失败或残留时会导致炸膛，
     * 因此在重新装填前必须先清空。
     */
    private void clearCannon() {
        MountedBigCannonContraption bigCannon = this.getMountedCannon();
        PitchOrientedContraptionEntity poce = this.getCannonContraptionEntity();
        if (bigCannon == null || poce == null) {
            return;
        }
        Direction dir = bigCannon.initialOrientation();
        BlockPos startPos = bigCannon.getStartPos();
        Set<BlockPos> changes = new HashSet<>();
        // 从炮管起点开始，沿炮管方向遍历所有方块实体，清空其中的弹药。
        BlockPos currentPos = startPos;
        while (bigCannon.presentBlockEntities.get(currentPos) instanceof IBigCannonBlockEntity cbe) {
            StructureBlockInfo info = cbe.cannonBehavior().block();
            if (info != null && !info.state().isAir()) {
                cbe.cannonBehavior().removeBlock();
                changes.add(currentPos);
            }
            currentPos = currentPos.relative(dir);
        }
        if (!changes.isEmpty()) {
            BigCannonBlock.writeAndSyncMultipleBlockData(changes, poce, bigCannon);
        }
    }

    /**
     * 直接以方块形式向炮管装填弹药，兼容所有炮尾类型。
     * @param stack 要装填的弹药物品
     * @param isProjectile true=炮弹，false=药筒
     * @return 装填后剩余的物品（装填成功则数量减 1）
     */
    private ItemStack insertCannonAmmo(ItemStack stack, boolean isProjectile) {
        MountedBigCannonContraption bigCannon = this.getMountedCannon();
        PitchOrientedContraptionEntity poce = this.getCannonContraptionEntity();
        if (bigCannon == null || poce == null || stack.isEmpty()) {
            return stack;
        }
        // 校验弹药是否为有效的大炮弹药方块（炮弹 / 药筒）。
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof BigCannonMunitionBlock munition)) {
            LOGGER.warn("Insert failed: {} is not a munition block",
                    BuiltInRegistries.ITEM.getKey(stack.getItem()));
            return stack;
        }
        // 直接操作炮管方块进行装填，绕开 bigCannonInsert 对“快速射击炮尾”的硬性限制，
        // 从而兼容螺纹 / 滑动 / 快速射击等所有炮尾类型。
        if (isProjectile && munition instanceof ProjectileBlock) {
            CannonMountPoint.loadProjectile(stack, munition, poce, bigCannon);
            ItemStack result = stack.copy();
            result.shrink(1);
            return result;
        }
        if (!isProjectile && munition instanceof BigCartridgeBlock) {
            CannonMountPoint.loadCartridge(stack, munition, poce, bigCannon);
            ItemStack result = stack.copy();
            result.shrink(1);
            return result;
        }
        LOGGER.warn("Insert failed: type mismatch (isProjectile={}, munition={})",
                isProjectile, munition.getClass().getSimpleName());
        return stack;
    }

    /**
     * 清空炮管并按配置重新装填炮弹与药筒。
     *
     * <p>只在 AI 真正要开炮时调用，返回值表示炮管是否已经可以击发。
     */
    public boolean supplyCannon() {
        MountedBigCannonContraption bigCannon = this.getMountedCannon();
        if (bigCannon == null) {
            return false;
        }
        // 先清空炮管内残留弹药，避免叠加导致炸膛。
        this.clearCannon();
        // 装填顺序：先装炮弹，再装药筒。
        ItemStack projectile = this.getProjectile();
        ItemStack remainingProjectile = this.insertCannonProjectile(projectile);
        ItemStack propellant = this.getPropellant(this.propellantPower);
        ItemStack remainingPropellant = this.insertCannonPropellant(propellant);
        return remainingProjectile.getCount() < projectile.getCount()
                && remainingPropellant.getCount() < propellant.getCount();
    }

    public boolean fireCannon() {
        CannonMountBlockEntity cannonMount = this.getCannonMountAbove();
        if (cannonMount == null || !cannonMount.isRunning()) {
            return false;
        }
        PitchOrientedContraptionEntity contraption = cannonMount.getContraption();
        if (contraption == null || this.level == null || this.level.isClientSide) {
            return false;
        }
        contraption.tryFiringShot();
        if (this.fireAnimationCooldownTicks == 0) {
            this.triggerAnim("status", "fire");
            this.fireAnimationCooldownTicks = FIRE_ANIMATION_TICKS;
        }
        this.firingFeedbackTicks = FEEDBACK_TICKS;
        this.sendData();
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putBoolean("CannonRecognized", this.cannonRecognized);
        tag.putBoolean("TargetDetected", this.targetDetected);
        tag.putInt("LoadingFeedbackTicks", this.loadingFeedbackTicks);
        tag.putInt("FiringFeedbackTicks", this.firingFeedbackTicks);
        tag.putInt("PropellantPower", this.propellantPower);
        tag.putInt("Accuracy", this.accuracy);
        tag.putString("ProjectileId", this.projectileId);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.cannonRecognized = tag.getBoolean("CannonRecognized");
        this.targetDetected = tag.getBoolean("TargetDetected");
        this.loadingFeedbackTicks = tag.getInt("LoadingFeedbackTicks");
        this.firingFeedbackTicks = tag.getInt("FiringFeedbackTicks");
        this.propellantPower = tag.contains("PropellantPower")
                ? Math.max(1, Math.min(8, tag.getInt("PropellantPower")))
                : DEFAULT_PROPELLANT_POWER;
        this.accuracy = tag.contains("Accuracy")
                ? Math.max(0, Math.min(100, tag.getInt("Accuracy")))
                : DEFAULT_ACCURACY;
        String savedProjectile = tag.getString("ProjectileId");
        this.projectileId = createProjectileStack(savedProjectile, registries).isEmpty()
                ? DEFAULT_PROJECTILE
                : savedProjectile;
        this.brain.init();
        this.brain.setAccuracy(this.accuracy / 100.0f);
    }

    @Override
    public float calculateStressApplied() {
        return 0f;
    }

    public ItemStack getProjectile() {
        return createProjectileStack(this.projectileId, this.level == null ? null : this.level.registryAccess());
    }

    public ItemStack getPropellant(int powder) {
        // 根据火药强度直接生成药筒，效果等同于 power = powder。
        return BigCartridgeBlockItem.getWithPower(powder);
    }

}
