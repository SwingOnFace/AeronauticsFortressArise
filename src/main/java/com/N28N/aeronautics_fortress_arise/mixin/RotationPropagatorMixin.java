package com.N28N.aeronautics_fortress_arise.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 提高 Create 反抖动机制的方块销毁阈值，并在销毁前输出诊断信息。
 *
 * <p>{@code RotationPropagator.propagateNewSource} 会在
 * {@code KineticBlockEntity.getFlickerScore() > 128} 时直接
 * {@code Level.destroyBlock(...)}，用于防止速度在两个节点之间来回跳变。
 *
 * <p>这个阈值对「频繁重建动力网络」的方块（例如瞄准器）太紧：
 * {@code removeSource()} 让转速归零、传播又把它设回非 0，
 * {@code onSpeedChanged} 每次跨零就 {@code flickerTally += 5}，
 * 而 {@code tick()} 每 tick 只衰减 1，128 的上限很容易被击穿，
 * 表现为「方块一放下就消失」。
 *
 * <p>这里只放宽上限，反抖动机制本身仍然生效。
 */
@Mixin(RotationPropagator.class)
public abstract class RotationPropagatorMixin {

    @Unique
    private static final Logger AFA_LOGGER = LogUtils.getLogger();

    /** 新的抖动阈值，原版为 128。 */
    @Unique
    private static final int AFA_FLICKER_LIMIT = 1024;

    /**
     * 替换 {@code getFlickerScore() > 128} 里的 128。
     *
     * <p>{@code propagateNewSource} 是 Create 的私有静态方法，
     * 且该 int 常量在方法内唯一，所以直接按值匹配。
     */
    @ModifyConstant(
            method = "propagateNewSource",
            constant = @Constant(intValue = 128),
            remap = false)
    private static int afa$raiseFlickerLimit(final int original) {
        return AFA_FLICKER_LIMIT;
    }

    /**
     * 在 {@code Level.destroyBlock} 被调用时先打印诊断信息与调用栈。
     *
     * <p>{@code propagateNewSource} 里有三处会摧毁方块的判定
     * （超速、速度方向冲突、抖动积分过高），它们共用同一个
     * {@code destroyBlock} 调用，所以这里能一次覆盖全部场景。
     * 销毁本身很罕见，用 INFO 级别保证默认日志配置下也能看到，
     * 并带上调用栈以便区分到底命中哪一条判定。
     */
    @WrapOperation(
            method = "propagateNewSource",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"),
            remap = false)
    private static boolean afa$logDestroy(
            final Level level,
            final BlockPos pos,
            final boolean dropBlock,
            final Operation<Boolean> original,
            @Local(argsOnly = true) final KineticBlockEntity be) {

        AFA_LOGGER.info(
                "Create 反抖动摧毁方块：位置={} 方块={} flicker={} speed={} 理论转速={} source={} 邻居={}{}",
                pos,
                be.getBlockState().getBlock(),
                be.getFlickerScore(),
                be.getSpeed(),
                be.getTheoreticalSpeed(),
                be.source,
                describe(level.getBlockEntity(pos)),
                afa$stackTrace());

        return original.call(level, pos, dropBlock);
    }

    /** 当前调用栈，已过滤本 mixin 自身的帧，便于看清是哪条销毁判定触发的。 */
    @Unique
    private static String afa$stackTrace() {
        final StackTraceElement[] frames = new Throwable().getStackTrace();
        final StringBuilder sb = new StringBuilder("\n--- destroyBlock 调用栈 ---");

        for (final StackTraceElement frame : frames) {
            if (frame.getClassName().startsWith("com.N28N.aeronautics_fortress_arise.mixin.")) {
                continue;
            }
            if (frame.getClassName().equals("java.lang.Thread")) {
                continue;
            }
            sb.append("\n    at ").append(frame);
        }

        return sb.toString();
    }

    @Unique
    private static String describe(@Nullable final BlockEntity blockEntity) {
        if (blockEntity == null) {
            return "无";
        }
        return blockEntity.getClass().getSimpleName()
                + (blockEntity instanceof final KineticBlockEntity kinetic
                        ? "[flicker=" + kinetic.getFlickerScore()
                        + ", speed=" + kinetic.getSpeed() + "]"
                        : "");
    }
}

