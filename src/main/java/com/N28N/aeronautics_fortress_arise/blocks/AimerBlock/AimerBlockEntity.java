package com.N28N.aeronautics_fortress_arise.blocks.AimerBlock;

import com.N28N.aeronautics_fortress_arise.items.AimingDeviceItem;
import com.google.common.annotations.VisibleForTesting;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.lasers.laser_pointer.LaserPointerBlockEntity;
import dev.simulated_team.simulated.mixin_interface.extra_kinetics.KineticBlockEntityExtension;
import dev.simulated_team.simulated.util.extra_kinetics.ExtraBlockPos;
import dev.simulated_team.simulated.util.extra_kinetics.ExtraKinetics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * 瞄准器方块实体。
 *
 * <p>借用 Simulated 的 ExtraKinetics 机制，把一个方块拆成两个动力节点：
 * 主实体与附加齿轮，两者之间按 PID 转速计算传动比。
 */
public class AimerBlockEntity extends KineticBlockEntity implements ExtraKinetics {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 调试日志总开关。 */
    private static final boolean DEBUG_ENABLED = false;

    @Override
    public void tick() {
        this.tickTransmission();
        super.tick();
        this.tickAiming();
    }

    //region 动力传动

    /** 附加齿轮，传动比的另一端。 */
    private final AimerBlockTransmissionCogwheel extraWheel;

    /** PID 转速，单位与 Create 转速一致。 */
    private float pidSpeed;

    /** 置位后下一 tick 重建一次动力网络。 */
    private boolean needsKineticRebuild;

    public AimerBlockEntity(final BlockEntityType<?> typeIn, final BlockPos pos, final BlockState state) {
        super(typeIn, pos, state);
        this.extraWheel = new AimerBlockTransmissionCogwheel(typeIn, new ExtraBlockPos(pos), state, this);
    }

    public void tickTransmission() {
        this.rebuildKineticsIfNeeded();
        this.extraWheel.tick();
    }

    /** 请求重建动力网络。 */
    private void requestKineticRebuild() {
        this.needsKineticRebuild = true;
    }

    /**
     * 重建动力网络。
     *
     * <p>Create 会缓存 {@link #propagateRotationTo} 的结果，改字段不生效，
     * 必须 detach + attach 才会重新调用。重建要遍历整个网络，所以只在需要时做一次。
     */
    private void rebuildKineticsIfNeeded() {
        if (!this.needsKineticRebuild || this.level == null || this.level.isClientSide) {
            return;
        }

        this.needsKineticRebuild = false;

        this.detachKinetics();
        this.extraWheel.detachKinetics();
        this.removeSource();
        this.extraWheel.removeSource();

        // 连接顺序跟随动力原本传入的一侧，与 shouldConnectExtraKinetics 的判定一致。
        if (((KineticBlockEntityExtension) this).simulated$getConnectedToExtraKinetics()) {
            this.attachKinetics();
            this.extraWheel.attachKinetics();
        } else {
            this.extraWheel.attachKinetics();
            this.attachKinetics();
        }
    }

    /**
     * 附加齿轮相对主实体的倍率。
     *
     * <p>可能为 0 或负数，调用方需处理（见 {@link #sanitizeModifier}）。
     */
    @VisibleForTesting
    public float getRotationModifier() {
        return Math.clamp(this.getPidSpeed(), -1.0F, 1.0F);
    }

    @Override
    public float propagateRotationTo(final KineticBlockEntity target, final BlockState stateFrom, final BlockState stateTo, final BlockPos diff, final boolean connectedViaAxes, final boolean connectedViaCogs) {
        // 只处理主实体与附加齿轮这一条边，对外部方块断连。
        if (diff.getX() != 0 || diff.getY() != 0 || diff.getZ() != 0) {
            return 0.0F;
        }

        final float modifier = this.getRotationModifier();

        // 驱动侧用 modifier，回程用 1/modifier，往返相乘为 1 才能收敛。
        // this 永远是主实体（附加齿轮转发调用），所以 target == this 即「附加 → 主」。
        final boolean fromDriver = this.isDrivenFromExtraWheel()
                ? target == this
                : target == this.extraWheel;

        return fromDriver
                ? sanitizeModifier(modifier)
                : sanitizeModifier(1.0F / modifier);
    }

    /** 0 / NaN / 无穷都按断开处理，避免 Infinity × 0 产生 NaN 让 Create 摧毁方块。 */
    private static float sanitizeModifier(final float value) {
        return isModifierValid(value) ? value : 0.0F;
    }

    private static boolean isModifierValid(final float value) {
        return value != 0.0F && Float.isFinite(value);
    }

    /** 动力是否从附加齿轮一侧传入。 */
    private boolean isDrivenFromExtraWheel() {
        if (!this.extraWheel.hasSource()) {
            return false;
        }

        final BlockPos source = this.extraWheel.source;
        return source != null && !source.equals(this.worldPosition);
    }

    /** 只有 PID 输出损坏时才报过载；倍率为 0 是正常状态。 */
    @Override
    public boolean isOverStressed() {
        return this.overStressed || !Float.isFinite(this.getPidSpeed());
    }

    //endregion

    //region ExtraKinetics 接口

    @Override
    public @NotNull KineticBlockEntity getExtraKinetics() {
        return this.extraWheel;
    }

    @Override
    public boolean shouldConnectExtraKinetics() {
        return true;
    }

    @Override
    public String getExtraKineticsSaveName() {
        return "ExtraCogwheel";
    }

    //endregion

    //region 附加齿轮

    /**
     * 附加齿轮。
     *
     * <p>与主实体共用方块与坐标，仅以 {@link ExtraBlockPos} 区分节点；
     * 不参与世界 tick，传动比全部转发给主实体。
     */
    public static class AimerBlockTransmissionCogwheel extends KineticBlockEntity implements ExtraKineticsBlockEntity {

        /** 无轴面，只沿方块轴向自转。 */
        public static final ICogWheel EXTRA_COGWHEEL_CONFIG = new ICogWheel() {
            @Override
            public boolean hasShaftTowards(final LevelReader world, final BlockPos pos, final BlockState state, final Direction face) {
                return false;
            }

            @Override
            public Direction.Axis getRotationAxis(final BlockState state) {
                return state.getValue(AimerBlock.AXIS);
            }
        };

        private final KineticBlockEntity parentBlockEntity;

        public AimerBlockTransmissionCogwheel(final BlockEntityType<?> typeIn, final ExtraBlockPos pos, final BlockState state, final KineticBlockEntity parentBlockEntity) {
            super(typeIn, pos, state);
            this.parentBlockEntity = parentBlockEntity;
        }

        @Override
        public float propagateRotationTo(final KineticBlockEntity target, final BlockState stateFrom, final BlockState stateTo, final BlockPos diff, final boolean connectedViaAxes, final boolean connectedViaCogs) {
            return this.parentBlockEntity.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
        }

        @Override
        protected boolean canPropagateDiagonally(final IRotate block, final BlockState state) {
            return true;
        }

        @Override
        public KineticBlockEntity getParentBlockEntity() {
            return this.parentBlockEntity;
        }
    }

    //endregion

    //region 绑定

    /** 旋转方向滚轮：0 = 左右，1 = 上下。 */
    private ScrollValueBehaviour rotateDirectionValue;

    /** 绑定的瞄准仪 UUID。 */
    private UUID boundUuid;

    /** 方向基准方块相对本方块的偏移，null 表示未绑定。 */
    @Nullable
    private BlockPos directionStandardOffset;

    /** NBT 键名。 */
    private static final String TAG_BOUND_UUID = "BoundUuid";
    private static final String TAG_DIRECTION_STANDARD = "DirectionStandard";
    private static final String TAG_PID_SPEED = "PidSpeed";

    /** 注册「旋转方向」滚轮，默认上下。 */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);

        this.rotateDirectionValue = new ScrollValueBehaviour(
                Component.translatable("block.aeronautics_fortress_arise.aimer.rotate_direction"),
                this,
                new CenteredSideValueBoxTransform().fromSide(Direction.NORTH))
                .between(0, 1)
                .withFormatter(value -> Component.translatable(value == 1
                        ? "block.aeronautics_fortress_arise.aimer.rotate_direction.vertical"
                        : "block.aeronautics_fortress_arise.aimer.rotate_direction.horizontal"
                ).getString());

        this.rotateDirectionValue.setValue(1);
        behaviours.add(this.rotateDirectionValue);
    }

    /** true 为上下旋转，false 为左右。 */
    public boolean getRotateDirection() {
        return this.rotateDirectionValue == null || this.rotateDirectionValue.getValue() == 1;
    }

    public void setRotateDirection(final boolean vertical) {
        if (this.rotateDirectionValue != null) {
            this.rotateDirectionValue.setValue(vertical ? 1 : 0);
        }
    }

    public UUID getBoundUuid() {
        return this.boundUuid;
    }

    /** 绑定或解绑瞄准仪，需存档并同步（客户端靠它画高亮）。 */
    public void setBoundUuid(@Nullable final UUID uuid) {
        if (Objects.equals(this.boundUuid, uuid)) {
            return;
        }

        this.boundUuid = uuid;
        this.setChanged();
        this.sendData();
    }

    /** 目标方块能否作为方向基准。 */
    public static boolean isSupportedDirectionStandard(@Nullable final BlockEntity blockEntity) {
        return blockEntity instanceof CannonMountBlockEntity
                || blockEntity instanceof LaserPointerBlockEntity;
    }

    /** 当前绑定的基准方块坐标。 */
    @Nullable
    public BlockPos getDirectionStandardPos() {
        return this.directionStandardOffset == null
                ? null
                : this.worldPosition.offset(this.directionStandardOffset);
    }

    /**
     * 基准方块实体。
     *
     * <p>不缓存实例，每次按相对坐标重查：方块实体会在区块卸载、
     * 结构移动、方块被替换后失效。
     */
    @Nullable
    public BlockEntity getDirectionStandard() {
        if (this.level == null || this.directionStandardOffset == null) {
            return null;
        }

        final BlockPos targetPos = this.worldPosition.offset(this.directionStandardOffset);
        if (!this.level.isLoaded(targetPos)) {
            return null;
        }

        final BlockEntity blockEntity = this.level.getBlockEntity(targetPos);
        return isSupportedDirectionStandard(blockEntity) ? blockEntity : null;
    }

    /**
     * 绑定基准方块，null 表示解绑。
     *
     * @return 绑定关系是否改变
     */
    public boolean bindDirectionStandard(@Nullable final BlockPos targetPos) {
        BlockPos newOffset = null;

        if (targetPos != null) {
            newOffset = targetPos.subtract(this.worldPosition);

            if (this.level == null || !isSupportedDirectionStandard(this.level.getBlockEntity(targetPos))) {
                return false;
            }
        }

        if (Objects.equals(this.directionStandardOffset, newOffset)) {
            return false;
        }

        this.directionStandardOffset = newOffset;
        this.setChanged();
        this.sendData();
        return true;
    }

    //endregion

    //region 存档与同步

    /** pidSpeed 只进同步包（clientPacket），不进存档。 */
    @Override
    protected void write(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        if (clientPacket) {
            compound.putFloat(TAG_PID_SPEED, this.getPidSpeed());
        }

        if (this.boundUuid != null) {
            compound.putUUID(TAG_BOUND_UUID, this.boundUuid);
        } else {
            compound.remove(TAG_BOUND_UUID);
        }

        if (this.directionStandardOffset != null) {
            compound.put(TAG_DIRECTION_STANDARD, NbtUtils.writeBlockPos(this.directionStandardOffset));
        } else {
            compound.remove(TAG_DIRECTION_STANDARD);
        }
    }

    @Override
    protected void read(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        if (clientPacket) {
            this.loadPidSpeed(compound.getFloat(TAG_PID_SPEED));
        }

        this.boundUuid = compound.hasUUID(TAG_BOUND_UUID)
                ? compound.getUUID(TAG_BOUND_UUID)
                : null;

        this.directionStandardOffset = NbtUtils
                .readBlockPos(compound, TAG_DIRECTION_STANDARD)
                .orElse(null);
    }

    //endregion

    //region PID 转速

    /** 转速变化超过该值才发包并重建。 */
    private static final float PID_SYNC_THRESHOLD = 0.01F;

    /** 设置 PID 转速，变化足够大时同步并重建网络。 */
    public void setPidSpeed(final float speed) {
        if (!Float.isFinite(speed)) {
            log("瞄准器 {} 收到非法转速 {}，已忽略", this.worldPosition, speed);
            return;
        }

        final float previous = this.pidSpeed;
        this.pidSpeed = speed;

        boolean flag = (Math.abs(previous - speed) / Math.max(2 ,getPidSpeedModifier())) >= PID_SYNC_THRESHOLD;
        flag = flag || (previous == 0.0F && speed != 0.0F);
        flag = flag || (previous != 0.0F && speed == 0.0F);
        if (flag) {
            this.sendData();
            this.requestKineticRebuild();
        }
    }

    public float getPidSpeed() {
        return this.pidSpeed;
    }

    /** 读包专用：不触发同步与重建。 */
    private void loadPidSpeed(final float speed) {
        this.pidSpeed = Float.isFinite(speed) ? speed : 0.0F;
    }

    //endregion

    //region 方向获取

    /** 基准方块的世界朝向。 */
    @Nullable
    public Vec3 getCurrentDirection() {
        final BlockEntity standard = this.getDirectionStandard();
        if (standard == null) {
            log("瞄准器 {} 取当前方向失败：基准方块不存在", this.worldPosition);
            return null;
        }

        final Vec3 local = this.getBlockDirection(standard);
        if (local == null) {
            log("瞄准器 {} 取当前方向失败：{} 类型不支持",
                    this.worldPosition, standard.getClass().getName());
            return null;
        }

        // 基准方块在 Sable 子结构上时，方向属于局部坐标系，需乘上子结构姿态。
        final SubLevel subLevel = Sable.HELPER.getContaining(standard.getLevel(), standard.getBlockPos());
        return subLevel == null
                ? local
                : subLevel.logicalPose().transformNormal(local);
    }

    @Nullable
    public Vec3 getCurrentBlockDirection() {
        final BlockEntity standard = this.getDirectionStandard();
        return standard == null ? null : this.getBlockDirection(standard);
    }

    /** 基准方块 → 局部坐标系方向。 */
    @Nullable
    private Vec3 getBlockDirection(final BlockEntity standard) {
        if (standard instanceof final CannonMountBlockEntity cannonMount) {
            final float elevation = cannonMount.getPitchOffset(1.0F);
            final float yaw = cannonMount.getYawOffset(1.0F);

            // CBC 的 pitch 是仰角（正值向上），MC 的 xRot 正值向下，必须取负。
            final Vec3 direction = calculateViewVector(-elevation, yaw);

            log("瞄准器 {} 炮座 {} 初始朝向={} 仰角={}° yaw={}° -> 方向 {}",
                    this.worldPosition, standard.getBlockPos(),
                    cannonMount.getContraptionDirection(),
                    round(elevation), round(yaw), direction);

            return direction;
        }

        if (standard instanceof final LaserPointerBlockEntity laserPointer) {
            final Direction direction = laserPointer.getDirection();

            log("瞄准器 {} 激光指示器 {} 朝向={}",
                    this.worldPosition, standard.getBlockPos(), direction);

            return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        }

        return null;
    }

    /** 佩戴者当前的视线方向。 */
    @Nullable
    public Vec3 getTargetDirection() {
        if (this.boundUuid == null || this.level == null) {
            log("瞄准器 {} 取目标方向失败：未绑定", this.worldPosition);
            return null;
        }

        final Player player = AimingDeviceItem.getBoundPlayer(this.level, this.boundUuid);
        if (player == null) {
            log("瞄准器 {} 取目标方向失败：瞄准仪 {} 无人佩戴", this.worldPosition, this.boundUuid);
            return null;
        }

        log("瞄准器 {} 目标方向 {}（{}）", this.worldPosition, player.getViewVector(1.0F), player.getName().getString());
        return player.getViewVector(1.0F);
    }

    //endregion

    //region 角度换算

    /** (pitch, yaw) → 单位视线向量，与 {@link Entity#getViewVector} 一致。 */
    public static Vec3 calculateViewVector(final float xRot, final float yRot) {
        final float f = xRot * (float) (Math.PI / 180.0);
        final float f1 = -yRot * (float) (Math.PI / 180.0);
        final float f2 = Mth.cos(f1);
        final float f3 = Mth.sin(f1);
        final float f4 = Mth.cos(f);
        final float f5 = Mth.sin(f);
        return new Vec3(f3 * f4, -f5, f2 * f4);
    }

    /**
     * 单位视线向量 → (pitch, yaw)，{@link #calculateViewVector} 的逆运算。
     *
     * @return {@code [0] = pitch}, {@code [1] = yaw}，单位度
     */
    public static float[] calculateViewAngles(final Vec3 vec) {
        final Vec3 v = vec.normalize();

        final float xRot = (float) Math.toDegrees(Math.asin(-Mth.clamp(v.y, -1.0, 1.0)));

        // 正上 / 正下时水平分量为 0，yaw 无意义。
        float yRot = 0.0F;
        if (Math.sqrt(v.x * v.x + v.z * v.z) > 1.0E-6) {
            yRot = (float) -Math.toDegrees(Math.atan2(v.x, v.z));
        }

        return new float[]{xRot, yRot};
    }

    //endregion

    //region 瞄准控制

    /*
     * PID 参数，基准输入转速为 AIM_REFERENCE_SPEED。
     * 其余转速下按 REFERENCE / |输入转速| 线性缩放：
     * 输入越快，同样的输出带来的实际转动越大，增益需相应减小。
     */
    private static final float AIM_REFERENCE_SPEED = 16.0F;
    private static final float AIM_P = 0.05F;
    private static final float AIM_I = 0.0015F;
    private static final float AIM_D = 0.20F;

    /** 积分上限，防饱和。 */
    private static final float AIM_INTEGRAL_MAX = 60.0F;

    /** 死区，度。 */
    private static final float AIM_DEADBAND_DEG = 0.5F;

    /** 单 tick 转角上限，度。 */
    private static final float AIM_MAX_DEG_PER_TICK = 4.0F;

    private float aimIntegral;
    private float aimLastError;
    private boolean aimHasLastError;

    /** 每 tick 把视线同步到基准方块角度，仅服务端执行。 */
    public void tickAiming() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        final BlockEntity standard = this.getDirectionStandard();
        if (this.boundUuid == null || standard == null) {
            return;
        }

        final Vec3 targetDirection = this.getTargetDirection();
        final Vec3 currentDirection = this.getCurrentDirection();
        if (targetDirection == null || currentDirection == null) {
            return;
        }

        // 角差必须在角度空间相减，向量直接相减再反解不成立。
        final float[] targetAngles = calculateViewAngles(targetDirection);
        final float[] currentAngles = calculateViewAngles(currentDirection);

        final float deltaPitch = Mth.wrapDegrees(targetAngles[0] - currentAngles[0]);
        final float deltaYaw = Mth.wrapDegrees(targetAngles[1] - currentAngles[1]);

        log("瞄准器 {} 目标={}° 当前={}° 角差 pitch={}° yaw={}°",
                this.worldPosition,
                round(targetAngles[0]), round(currentAngles[0]),
                round(deltaPitch), round(deltaYaw));

        this.applyAimDelta(deltaPitch, deltaYaw);
    }

    /** PID：角差 → 转速。 */
    private void applyAimDelta(final float deltaPitch, final float deltaYaw) {
        final float delta = this.getRotateDirection() ? deltaPitch : deltaYaw;

        if (Math.abs(delta) < AIM_DEADBAND_DEG) {
            this.resetAimPid();
            this.setPidSpeed(0.0F);
            return;
        }

        final float derivative = this.aimHasLastError ? delta - this.aimLastError : 0.0F;
        this.aimLastError = delta;
        this.aimHasLastError = true;

        // 条件积分：大误差阶段交给 PD，避免积分堆积导致回调超调。
        if (Math.abs(delta) < AIM_INTEGRAL_MAX) {
            this.aimIntegral = Mth.clamp(this.aimIntegral + delta, -AIM_INTEGRAL_MAX, AIM_INTEGRAL_MAX);
        }

        final float output = Mth.clamp(
                AIM_P * delta + AIM_I * this.aimIntegral + AIM_D * derivative,
                -AIM_MAX_DEG_PER_TICK, AIM_MAX_DEG_PER_TICK);

        // 输出是「本 tick 应转过的角度」，换成转速后按输入转速缩放：
        // 输入越快，同样的倍率转得越快，所以倍率要按 基准转速 / 实际转速 减小。
        final float speed = output / getPidSpeedModifier();

        log("瞄准器 {} PID：误差={}° P={} I={} D={} 输出={}° pidSpeed={}",
                this.worldPosition, round(delta),
                round(AIM_P * delta), round(AIM_I * this.aimIntegral), round(AIM_D * derivative),
                round(output), round(speed));

        this.setPidSpeed(speed);
    }
    public float getPidSpeedModifier()
    {
        final float inputSpeed = Math.max(1.0F, Math.abs(this.getSpeed()));
        return 1 / convertToAngular(10.0F) * (AIM_REFERENCE_SPEED / inputSpeed);
    }

    private void resetAimPid() {
        this.aimIntegral = 0.0F;
        this.aimLastError = 0.0F;
        this.aimHasLastError = false;
    }

    //endregion

    //region 调试

    /** 统一日志出口，{@link #DEBUG_ENABLED} 关闭时零开销。 */
    private static void log(final String message, final Object... args) {
        if (DEBUG_ENABLED) {
            LOGGER.info(message, args);
        }
    }

    private static float round(final float value) {
        return Math.round(value * 100.0F) / 100.0F;
    }

    //endregion
}
