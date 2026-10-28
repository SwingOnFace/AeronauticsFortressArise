package com.N28N.aeronautics_fortress_arise.blocks.AntiIntrusionBlock;

import java.util.List;

import com.N28N.aeronautics_fortress_arise.util.SableSubLevelUtil;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AntiIntrusionBlockEntity extends KineticBlockEntity {

    private static final double MAX_PUSH = 3.5D;

    private ScrollValueBehaviour pushRadiusValue;

    public AntiIntrusionBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);

        this.pushRadiusValue = new ScrollValueBehaviour(
                Component.translatable("block.aeronautics_fortress_arise.anti_intrusion.push_radius"),
            this, new CenteredSideValueBoxTransform().fromSide(Direction.NORTH));
        this.pushRadiusValue.between(1, 128);
        this.pushRadiusValue.setValue(4);
        this.pushRadiusValue.withCallback(value -> this.setPushRadius(value));
        behaviours.add(this.pushRadiusValue);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        int radius = this.getPushRadius();

        // 若方块位于 Sable 子空间（飞行器）上，则通过子空间的位姿
        // 把方块中心坐标变换到主世界，并在主世界中搜索玩家。
        Pose3dc pose = SableSubLevelUtil.findPose(this.level, this.worldPosition);
        Vec3 centerLocal = Vec3.atCenterOf(this.worldPosition);
        Vec3 centerGlobal = pose == null ? centerLocal : pose.transformPosition(centerLocal);

        AABB searchBox = new AABB(centerGlobal, centerGlobal).inflate(radius);
        for (Player player : this.level.getEntitiesOfClass(Player.class, searchBox)) {
            // 创造模式与观察者模式玩家不受推开影响
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }

            // 把玩家世界坐标变换回子空间局部坐标，在方块所在坐标系中计算推离。
            Vec3 playerLocal = pose == null
                    ? player.position()
                    : pose.transformPositionInverse(player.position());

            double pushX = playerLocal.x - centerLocal.x;
            double pushZ = playerLocal.z - centerLocal.z;
            double distance = Math.sqrt(pushX * pushX + pushZ * pushZ);
            if (distance >= radius) {
                continue;
            }
            if (distance < 0.001D) {
                pushX = 0.01D;
                pushZ = 0.01D;
                distance = Math.sqrt(pushX * pushX + pushZ * pushZ);
            }

            double strength = MAX_PUSH * (1.0D - distance / radius);
            // 推离方向在子空间局部坐标系中计算，需变换回主世界全局坐标施加给玩家。
            Vec3 pushDirLocal = new Vec3(pushX / distance * strength, 0.0D, pushZ / distance * strength);
            Vec3 pushDirGlobal = pose == null ? pushDirLocal : pose.transformNormal(pushDirLocal);
            player.push(pushDirGlobal.x, 0.0D, pushDirGlobal.z);
            player.hurtMarked = true;
        }
    }

    public int getPushRadius() {
        // 始终从 behaviour 读取权威值，服务端与客户端保持一致
        return this.pushRadiusValue != null ? this.pushRadiusValue.getValue() : 8;
    }

    private void setPushRadius(int value) {
        int clamped = Math.max(1, Math.min(32, value));
        // 更新 behaviour 值并同步到客户端，触发重新渲染
        this.pushRadiusValue.setValue(clamped);
        this.setChanged();
        this.sendData();
        this.notifyUpdate();
    }

    @Override
    public AABB getRenderBoundingBox() {
        float radius = getPushRadius();

       return new AABB(worldPosition).inflate(radius + 1.0D);
    }

}