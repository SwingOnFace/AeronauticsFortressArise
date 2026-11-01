package com.N28N.aeronautics_fortress_arise.blocks.CompactEngine;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.compat.computercraft.AbstractComputerBehaviour;
import com.simibubi.create.compat.computercraft.ComputerCraftProxy;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;

import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.List;

public class CompactEngineBlockEntity extends KineticBlockEntity {
    public static final int DEFAULT_SPEED = 16;
	public static final int MAX_SPEED = 256;

	public ScrollValueBehaviour generatedSpeed;
	public AbstractComputerBehaviour computerBehaviour;

	public CompactEngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		int max = MAX_SPEED;
		generatedSpeed = new KineticScrollValueBehaviour(CreateLang.translateDirect("kinetics.creative_motor.rotation_speed"),
			this, new MotorValueBox());
		generatedSpeed.between(-max, max);
		generatedSpeed.value = DEFAULT_SPEED;
		generatedSpeed.withCallback(i -> this.updateGeneratedRotation());
		behaviours.add(generatedSpeed);
		behaviours.add(computerBehaviour = ComputerCraftProxy.behaviour(this));
	}

	@Override
	public void initialize() {
		super.initialize();
		if (!hasSource() || getGeneratedSpeed() > getTheoreticalSpeed())
			updateGeneratedRotation();
	}

    private void updateGeneratedRotation() {
		float speed = getGeneratedSpeed();
		float prevSpeed = this.speed;

		if (level == null || level.isClientSide)
			return;

		if (prevSpeed != speed) {
			if (!hasSource()) {
				SpeedLevel levelBefore = SpeedLevel.of(this.speed);
				SpeedLevel levelafter = SpeedLevel.of(speed);
				if (levelBefore != levelafter)
					effects.queueRotationIndicators();
			}

			applyNewSpeed(prevSpeed, speed);
		}

		if (hasNetwork() && speed != 0) {
			KineticNetwork network = getOrCreateNetwork();
			notifyStressCapacityChange(calculateAddedStressCapacity());
			getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
			network.updateStress();
		}

		onSpeedChanged(prevSpeed);
		sendData();
	}

    private void applyNewSpeed(float prevSpeed, float speed) {

		// Speed changed to 0
		if (speed == 0) {
			if (hasSource()) {
				notifyStressCapacityChange(0);
				getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
				return;
			}
			detachKinetics();
			setSpeed(0);
			setNetwork(null);
			return;
		}

		// Now turning - create a new Network
		if (prevSpeed == 0) {
			setSpeed(speed);
			setNetwork(createNetworkId());
			attachKinetics();
			return;
		}

		// Change speed when overpowered by other generator
		if (hasSource()) {

			// Staying below Overpowered speed
			if (Math.abs(prevSpeed) >= Math.abs(speed)) {
				if (Math.signum(prevSpeed) != Math.signum(speed))
					level.destroyBlock(worldPosition, true);
				return;
			}

			// Faster than attached network -> become the new source
			detachKinetics();
			setSpeed(speed);
			source = null;
			setNetwork(createNetworkId());
			attachKinetics();
			return;
		}

		// Reapply source
		detachKinetics();
		setSpeed(speed);
		attachKinetics();
	}

    public Long createNetworkId() {
		return worldPosition.asLong();
	}
    
	/** 由外壳方块实体转发调用：设置目标转速并应用。 */
	public void setGeneratedSpeed(int value) {
		generatedSpeed.value = value;
		updateGeneratedRotation();
	}

	protected void notifyStressCapacityChange(float capacity) {
		getOrCreateNetwork().updateCapacityFor(this, capacity);
	}

    @Override
    public float calculateAddedStressCapacity() {
        return 0.1F;
    }
    @Override
    public float calculateStressApplied() {
        return 0F;
    }
	/** 引擎结构边长：1 = 单方块，3 = 3x3x3，5 = 5x5x5。 */
	public int getSize() {
		if (getBlockState().getBlock() instanceof CompactEngineBlock engine) {
			return engine.size;
		}
		return 1;
	}
	@Override
	public float getGeneratedSpeed() {
		return convertToDirection(generatedSpeed.getValue(), getBlockState().getValue(CreativeMotorBlock.FACING));
	}

	static class MotorValueBox extends ValueBoxTransform.Sided {
		@Override
		protected Vec3 getSouthLocation() {
			return VecHelper.voxelSpace(8, 8, 12.5);
		}

		@Override
		public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
			Direction facing = state.getValue(CreativeMotorBlock.FACING);
			return super.getLocalOffset(level, pos, state).add(Vec3.atLowerCornerOf(facing.getNormal())
				.scale(-1 / 16f));
		}

		@Override
		public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
			super.rotate(level, pos, state, ms);
			Direction facing = state.getValue(CreativeMotorBlock.FACING);
			if (facing.getAxis() == Axis.Y)
				return;
			if (getSide() != Direction.UP)
				return;
			TransformStack.of(ms)
				.rotateZDegrees(-AngleHelper.horizontalAngle(facing) + 180);
		}

		@Override
		protected boolean isSideActive(BlockState state, Direction direction) {
			Direction facing = state.getValue(CreativeMotorBlock.FACING);
			if (facing.getAxis() != Axis.Y && direction == Direction.DOWN)
				return false;
			return direction.getAxis() != facing.getAxis();
		}

	}

	@Override
	public void invalidate() {
		super.invalidate();
		computerBehaviour.removePeripheral();
	}
}
