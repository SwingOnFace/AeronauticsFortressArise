package com.N28N.aeronautics_fortress_arise.blocks.AIFireController;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 支持自定义步进（step）与独立 {@link BehaviourType} 的滚动值行为。
 *
 * <p>Create 默认的 {@link ScrollValueBehaviour} 在同一个方块实体中共享同一个
 * {@code ScrollValueBehaviour.TYPE}，而 {@link SmartBlockEntity} 内部使用
 * {@code Map<BehaviourType, BlockEntityBehaviour>} 存储行为，导致第二个滚动值会
 * 覆盖第一个。本类通过为每个实例分配独立的 BehaviourType 解决该问题。
 *
 * <p>同时，值设置界面的滚轮步进默认为 1（按住 Shift 为里程碑间隔），无法直接配置。
 * 本类将界面数值映射到真实数值（真实值 = min + 界面值 × step），从而让滚轮以
 * step 为单位进行步进。
 */
public class SteppedScrollValueBehaviour extends ScrollValueBehaviour {

    private final BehaviourType<ScrollValueBehaviour> type;
    private final int step;
    private int minValue = 0;
    private int maxValue = 1;

    public SteppedScrollValueBehaviour(Component label, SmartBlockEntity be, ValueBoxTransform slot,
                                       BehaviourType<ScrollValueBehaviour> type, int step) {
        super(label, be, slot);
        this.type = type;
        this.step = step;
    }

    @Override
    public SteppedScrollValueBehaviour between(int min, int max) {
        this.minValue = min;
        this.maxValue = max;
        super.between(min, max);
        return this;
    }

    @Override
    public BehaviourType<?> getType() {
        return this.type;
    }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        int boardMax = Math.max(1, (this.maxValue - this.minValue) / this.step);
        int milestone = Math.max(1, boardMax / 8);
        return new ValueSettingsBoard(this.label, boardMax, milestone,
            ImmutableList.of(Component.literal("Value")),
            new ValueSettingsFormatter(settings -> Component.literal(
                Integer.toString(this.minValue + settings.value() * this.step))));
    }

    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, (this.value - this.minValue) / this.step);
    }

    @Override
    public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlDown) {
        if (valueSetting.equals(getValueSettings())) {
            return;
        }
        setValue(this.minValue + valueSetting.value() * this.step);
        playFeedbackSound(this);
    }
}
