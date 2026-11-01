package com.N28N.aeronautics_fortress_arise.blocks.OreVein;

import java.util.List;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矿脉方块的方块实体。
 * <p>
 * 职责：
 * <ul>
 *     <li>通过 {@link ScrollValueBehaviour} 在创造模式下设置矿脉等级（1~100）；</li>
 *     <li>矿脉等级控制矿石方块的生成速度；</li>
 *     <li>每 20 级增加一个生成面，最高开放五个方向（不含上方）；</li>
 *     <li>每个矿脉方块只生成其自身绑定的那一种矿石。</li>
 * </ul>
 * <p>
 * 绑定矿种的战利品品质与矿脉内部等级互相独立。
 */
public class OreVeinBlockEntity extends SmartBlockEntity {

    //#region Production level and state
    private static final int MIN_LEVEL = 1;

    public static final int MAX_LEVEL = 100;

    private static final int LEVELS_PER_FACE = 20;

    private static final int MAX_SPEED_LEVEL = 20;

    private static final int DEFAULT_LEVEL = 1;

    /** 每秒 tick 数（Minecraft 固定 20 tick/秒）。 */
    private static final int TICKS_PER_SECOND = 20;

    /** 固定的输出面解锁顺序。 */
    private static final Direction[] OUTPUT_DIRECTIONS = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST
    };

    /** 矿脉等级（创造模式下通过滚轮设置，或通过提取器转移）。 */
    private int productionLevel = DEFAULT_LEVEL;

    private boolean levelInitialized;

    /** 生成计时累加器（以 tick 为单位，支持非整数间隔）。 */
    private float generationTimer;

    private ScrollValueBehaviour productionLevelValue;
    //#endregion

    //#region Lifecycle and production
    public OreVeinBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        this.productionLevelValue = new ScrollValueBehaviour(
                Component.translatable("block.aeronautics_fortress_arise.ore_vein.level"),
                this, new CenteredSideValueBoxTransform().fromSide(Direction.NORTH)) {
            @Override
            public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlDown) {
                if (!player.isCreative()) {
                    return;
                }
                super.setValueSettings(player, valueSetting, ctrlDown);
            }
        };
        this.productionLevelValue.between(MIN_LEVEL, MAX_LEVEL);
        this.productionLevelValue.setValue(this.productionLevel);
        this.productionLevelValue.withCallback(this::setProductionLevel);
        behaviours.add(this.productionLevelValue);
    }

    /** 服务端每 tick 调用一次。 */
    public void tick() {
        super.tick();
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        this.initializeLevelIfNeeded();
        float interval = (float) TICKS_PER_SECOND / Math.min(this.productionLevel, MAX_SPEED_LEVEL);
        this.generationTimer += 1.0F;
        if (this.generationTimer < interval) {
            return;
        }
        this.generationTimer -= interval;

        int outputFaceCount = getOutputFaceCount(this.productionLevel);
        for (int i = 0; i < outputFaceCount; i++) {
            this.tryGenerateOre(OUTPUT_DIRECTIONS[i]);
        }
    }

    /** 尝试在指定输出面生成一种矿石方块。 */
    private void tryGenerateOre(Direction direction) {
        if (this.level == null) {
            return;
        }

        BlockPos outputPos = this.worldPosition.relative(direction);
        BlockState outputState = this.level.getBlockState(outputPos);

        // 仅当输出面为空气或可替换方块时才生成，否则本次尝试失败。
        if (!outputState.isAir() && !outputState.canBeReplaced()) {
            return;
        }

        Block oreBlock = this.getOreBlock();
        if (oreBlock == null) {
            return;
        }

        this.level.setBlock(outputPos, oreBlock.defaultBlockState(), Block.UPDATE_ALL);
    }
    //#endregion

    //#region Ore selection and production level
    /** 获取当前矿脉绑定的矿石方块。 */
    private Block getOreBlock() {
        if (!(this.getBlockState().getBlock() instanceof OreVeinBlock veinBlock)) {
            return null;
        }

        return BuiltInRegistries.ITEM.getOptional(veinBlock.getOreItemId())
                .map(item -> item instanceof net.minecraft.world.item.BlockItem blockItem
                        ? blockItem.getBlock() : null)
                .orElse(null);
    }

    /** 获取当前矿脉等级。 */
    public int getProductionLevel() {
        this.initializeLevelIfNeeded();
        return this.productionLevel;
    }

    /** 获取当前等级解锁的输出面数量，1~100 级对应 1~5 面。 */
    public static int getOutputFaceCount(int level) {
        int clamped = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
        return Math.min(OUTPUT_DIRECTIONS.length, (clamped - 1) / LEVELS_PER_FACE + 1);
    }

    /** 获取当前矿脉对应的矿石方块状态。 */
    public BlockState getOreBlockState() {
        Block oreBlock = this.getOreBlock();
        return oreBlock == null ? null : oreBlock.defaultBlockState();
    }

    /** 设置矿脉等级（由滚轮行为或提取器调用）。 */
    public void setProductionLevel(int value) {
        int clamped = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, value));
        if (this.productionLevel == clamped) {
            if (!this.levelInitialized) {
                this.levelInitialized = true;
                this.setChanged();
                this.sendData();
            }
            return;
        }
        this.productionLevel = clamped;
        this.levelInitialized = true;
        if (this.productionLevelValue != null) {
            this.productionLevelValue.setValue(clamped);
        }
        this.setChanged();
        this.sendData();
    }

    private void initializeLevelIfNeeded() {
        if (this.levelInitialized && this.level != null && !this.level.isClientSide) {
            return;
        }
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        this.productionLevel = this.level.getRandom().nextInt(1, 4);
        this.levelInitialized = true;
        if (this.productionLevelValue != null) {
            this.productionLevelValue.setValue(this.productionLevel);
        }
        this.setChanged();
        this.sendData();
    }
    //#endregion

    //#region Persistence
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("ProductionLevel", this.productionLevel);
        tag.putBoolean("LevelInitialized", this.levelInitialized);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("ProductionLevel") || tag.contains("Quality")) {
            int savedLevel = tag.contains("ProductionLevel")
                    ? tag.getInt("ProductionLevel")
                    : tag.getInt("Quality");
            this.productionLevel = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, savedLevel));
            this.levelInitialized = tag.contains("LevelInitialized")
                    ? tag.getBoolean("LevelInitialized")
                    : true;
        } else {
            this.levelInitialized = false;
        }
        if (this.productionLevelValue != null) {
            this.productionLevelValue.setValue(this.productionLevel);
        }
    }
    //#endregion
}
