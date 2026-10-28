package com.N28N.aeronautics_fortress_arise.blocks.Aimer;

import com.N28N.aeronautics_fortress_arise.items.AimingDeviceItem;
import com.N28N.aeronautics_fortress_arise.util.SableSubLevelUtil;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import java.util.UUID;

/**
 * 瞄准器方块实体。
 *
 * <p><b>动力传递</b>：继承 {@link KineticBlockEntity} 参与 Create 传动网络，
 * {@link #calculateStressApplied()} 恒返回 0（不凭空产生应力），
 * 转速通过轴连接 1:1 直通，把下方网络的应力与转速原样传递给所驱动的炮座接口。
 *
 * <p><b>瞄准控制</b>：每 tick（有动力 + 已绑定瞄准仪时）：
 * <ol>
 *     <li>检测相邻的“激活火炮”（运行中的 {@link CannonMountBlockEntity}）；</li>
 *     <li>通过 {@code CannonMountBlockEntity.getInterfacingBlockEntity} 判断瞄准器接的是
 *     火炮的哪个轴——底面 → Yaw 接口（左右旋转），侧面 → Pitch 接口（上下旋转）；</li>
 *     <li>从代码直接取得火炮当前轴的朝向（旋转轴单位向量），
 *     把佩戴瞄准仪的玩家视线投影到以该轴为法向量的平面上；</li>
 *     <li>将炮座角度（setYaw / setPitch）转到投影角，并补偿炮座自身的转速漂移，
 *     使炮管稳定对准投影方向。</li>
 * </ol>
 */
public class AimerBlockEntity extends KineticBlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 已绑定的瞄准仪 UUID（null = 未绑定）。 */
    private UUID boundUuid;

    public AimerBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    // ------------------------------------------------------------------
    // 绑定
    // ------------------------------------------------------------------

    public UUID getBoundUuid() {
        return this.boundUuid;
    }

    public void setBoundUuid(UUID uuid) {
        this.boundUuid = uuid;
        this.setChanged();
        this.sendData();
    }

    // ------------------------------------------------------------------
    // 每 tick 逻辑
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        // 无动力不工作：动力既是驱动炮座的条件，也是瞄准控制的使能。
        if (this.getSpeed() == 0) {
            return;
        }
        if (this.boundUuid == null) {
            return;
        }
        this.tickAim();
    }

    /**
     * 瞄准控制主逻辑。
     */
    private void tickAim() {
        CannonMountBlockEntity mount = this.findCannonMount();
        if (mount == null) {
            return;
        }

        // 瞄准器相对炮座的偏移 → 判断接入的是哪个接口（轴）。
        BlockPos from = this.worldPosition.subtract(mount.getBlockPos());
        KineticBlockEntity interfacing = mount.getInterfacingBlockEntity(from);
        if (interfacing == null) {
            return;
        }
        boolean controlYaw = interfacing == mount.getYawInterface();
        boolean controlPitch = interfacing == mount.getPitchInterface();
        if (!controlYaw && !controlPitch) {
            return;
        }

        // 佩戴瞄准仪的玩家（在线）。
        Player player = AimingDeviceItem.getBoundPlayer(this.level, this.boundUuid);
        if (player == null || !player.isAlive()) {
            return;
        }

        // 玩家视线（世界坐标）。若瞄准器位于 Sable 子空间（飞行器）上，
        // 变换到子空间局部坐标，使计算在炮座所在坐标系内进行。
        Vec3 look = player.getLookAngle();
        Pose3dc pose = SableSubLevelUtil.findPose(this.level, this.worldPosition);
        if (pose != null) {
            look = pose.transformNormalInverse(look);
        }

        // 从代码直接取得火炮当前轴的朝向（旋转轴单位向量，局部坐标）。
        Vec3 axis;
        if (controlYaw) {
            // 底面（Yaw 接口）：左右旋转，轴为竖直方向。
            axis = new Vec3(0, 1, 0);
        } else {
            // 侧面（Pitch 接口）：上下旋转，轴为炮座 blockstate 的水平旋转轴。
            Direction.Axis rotAxis = ((CannonMountBlock) mount.getBlockState().getBlock())
                    .getRotationAxis(mount.getBlockState());
            axis = rotAxis == Direction.Axis.X ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1);
        }

        // 把玩家视线投影到以该轴为法向量的平面上。
        Vec3 proj = look.subtract(axis.scale(look.dot(axis)));
        double projLen = proj.length();
        if (projLen < 1.0e-4) {
            // 视线与轴近似平行（看向正上方/正下方等），无投影角可算。
            return;
        }

        if (controlYaw) {
            // 投影向量在水平面内，yaw = atan2(x, z)（与 Minecraft yRot 约定一致）。
            float desiredYaw = (float) Math.toDegrees(Math.atan2(proj.x, proj.z));
            // 炮座 tick 中 cannonYaw = cannonYaw - yawSpeed，补偿自转漂移使角度收敛到投影角。
            float yawSpeed = mount.getAngularSpeed(mount.getYawSpeed(), 0);
            mount.setYaw(desiredYaw + yawSpeed);
        } else {
            // 投影向量相对水平面的仰角。
            float desiredPitch = (float) Math.toDegrees(
                    Math.asin(Mth.clamp(proj.y / projLen, -1.0, 1.0)));
            // 炮座 tick 中 cannonPitch = cannonPitch + pitchSpeed * sgn，其中 sgn 由大炮初始朝向决定。
            float pitchSpeed = mount.getAngularSpeed(mount.getPitchSpeed(), 0);
            Direction dir = mount.getContraptionDirection();
            boolean positive = (dir.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                    == (dir.getAxis() == Direction.Axis.X);
            float sgn = positive ? 1 : -1;
            mount.setPitch(desiredPitch - pitchSpeed * sgn);
        }
    }

    /**
     * 检测相邻的“激活火炮”（运行中且装配了 contraption 的炮座）。
     * <p>优先检测上方（炮座在瞄准器上方时瞄准器位于炮座底面，控制左右旋转），
     * 也支持侧面（控制上下旋转）。只有与瞄准器存在接口连接（轴接入）的炮座才会被返回。
     */
    private CannonMountBlockEntity findCannonMount() {
        if (this.level == null) {
            return null;
        }
        // 优先检查上方，其次水平方向，最后下方。
        Direction[] priority = {
                Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN
        };
        for (Direction dir : priority) {
            BlockEntity blockEntity = this.level.getBlockEntity(this.worldPosition.relative(dir));
            if (!(blockEntity instanceof CannonMountBlockEntity mount)) {
                continue;
            }
            if (!mount.isRunning() || mount.getContraption() == null) {
                continue;
            }
            BlockPos from = this.worldPosition.subtract(mount.getBlockPos());
            if (mount.getInterfacingBlockEntity(from) != null) {
                return mount;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 动力传递：不凭空产生应力，直接传递下方网络的应力，转速 1:1 不变
    // ------------------------------------------------------------------

    @Override
    public float calculateStressApplied() {
        return 0f;
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (this.boundUuid != null) {
            tag.putUUID("BoundUuid", this.boundUuid);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.boundUuid = tag.contains("BoundUuid") ? tag.getUUID("BoundUuid") : null;
    }
}
