package com.N28N.aeronautics_fortress_arise.items;

import java.util.List;
import java.util.function.Consumer;

import com.N28N.aeronautics_fortress_arise.blocks.OreVein.OreVeinBlock;
import com.N28N.aeronautics_fortress_arise.blocks.OreVein.OreVeinBlockEntity;
import com.N28N.aeronautics_fortress_arise.client.OreVein.OreVeinExtractorRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

public class OreVeinExtractorItem extends Item implements GeoItem {

    private static final String TAG_ORE_ID = "OreItemId";
    private static final String TAG_LEVEL = "VeinLevel";
    private static final String TAG_USE_X = "UseTargetX";
    private static final String TAG_USE_Y = "UseTargetY";
    private static final String TAG_USE_Z = "UseTargetZ";
    private static final int USE_DURATION_TICKS = 40;
    private static final RawAnimation IDLE_ANIMATION = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation DRILLING_ANIMATION = RawAnimation.begin().thenPlay("drilling");

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    public OreVeinExtractorItem(Properties properties) {
        super(properties);
        GeoItem.registerSyncedAnimatable(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "extractor", state -> state.setAndContinue(IDLE_ANIMATION))
                .triggerableAnim("drilling", DRILLING_ANIMATION));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animationCache;
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private OreVeinExtractorRenderer renderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.renderer == null) {
                    this.renderer = new OreVeinExtractorRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockState(pos).getBlock() instanceof OreVeinBlock veinBlock)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && !(level.getBlockEntity(pos) instanceof OreVeinBlockEntity)) {
            return InteractionResult.PASS;
        }

        setUseTarget(stack, pos);
        player.startUsingItem(context.getHand());
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_DURATION_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPYGLASS;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof Player player) {
            BlockPos target = getUseTarget(stack);
            clearUseTarget(stack);
            if (target != null) {
                this.extractOrTransfer(level, target, player, stack);
            }
        }
        return stack;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeCharged) {
        clearUseTarget(stack);
    }

    private void extractOrTransfer(Level level, BlockPos pos, Player player, ItemStack stack) {
        if (!(level.getBlockState(pos).getBlock() instanceof OreVeinBlock veinBlock)) {
            return;
        }

        CompoundTag tag = getData(stack);
        String storedOreId = tag.getString(TAG_ORE_ID);
        int storedLevel = tag.getInt(TAG_LEVEL);
        boolean loaded = !storedOreId.isBlank() && storedLevel > 0;

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof OreVeinBlockEntity vein)) {
            return;
        }

        if (!loaded) {
            int extractedLevel = vein.getProductionLevel();
            setPayload(stack, veinBlock.getOreItemId().toString(), extractedLevel);
            if (!level.removeBlock(pos, false)) {
                clearPayload(stack);
                sendMessage(player,
                        Component.translatable("message.aeronautics_fortress_arise.ore_vein_extractor.failed"),
                        true);
                return;
            }
            this.triggerDrillingAnimation(player, level, stack);
            sendMessage(player,
                    Component.translatable("message.aeronautics_fortress_arise.ore_vein_extractor.extracted",
                            extractedLevel), true);
            return;
        }

        if (!storedOreId.equals(veinBlock.getOreItemId().toString())) {
            sendMessage(player,
                    Component.translatable("message.aeronautics_fortress_arise.ore_vein_extractor.wrong_ore"),
                    true);
            return;
        }

        int targetLevel = vein.getProductionLevel();
        if (targetLevel + storedLevel > OreVeinBlockEntity.MAX_LEVEL) {
            sendMessage(player,
                    Component.translatable("message.aeronautics_fortress_arise.ore_vein_extractor.level_cap",
                            OreVeinBlockEntity.MAX_LEVEL), true);
            return;
        }

        vein.setProductionLevel(targetLevel + storedLevel);
        clearPayload(stack);
        this.triggerDrillingAnimation(player, level, stack);
        sendMessage(player,
                Component.translatable("message.aeronautics_fortress_arise.ore_vein_extractor.transferred",
                        storedLevel, targetLevel + storedLevel), true);
    }

    private void triggerDrillingAnimation(Player player, Level level, ItemStack stack) {
        if (level instanceof ServerLevel serverLevel) {
            this.triggerAnim(player, GeoItem.getOrAssignId(stack, serverLevel), "extractor", "drilling");
        }
    }

    private static void setUseTarget(ItemStack stack, BlockPos pos) {
        CompoundTag tag = getData(stack);
        tag.putInt(TAG_USE_X, pos.getX());
        tag.putInt(TAG_USE_Y, pos.getY());
        tag.putInt(TAG_USE_Z, pos.getZ());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Nullable
    private static BlockPos getUseTarget(ItemStack stack) {
        CompoundTag tag = getData(stack);
        if (!tag.contains(TAG_USE_X) || !tag.contains(TAG_USE_Y) || !tag.contains(TAG_USE_Z)) {
            return null;
        }
        return new BlockPos(tag.getInt(TAG_USE_X), tag.getInt(TAG_USE_Y), tag.getInt(TAG_USE_Z));
    }

    private static void clearUseTarget(ItemStack stack) {
        CompoundTag tag = getData(stack);
        tag.remove(TAG_USE_X);
        tag.remove(TAG_USE_Y);
        tag.remove(TAG_USE_Z);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        tooltipComponents.add(Component.translatable(
                "item.aeronautics_fortress_arise.ore_vein_extractor.use_hint"));
        CompoundTag tag = getData(stack);
        String oreId = tag.getString(TAG_ORE_ID);
        int veinLevel = tag.getInt(TAG_LEVEL);
        if (oreId.isBlank() || veinLevel <= 0) {
            tooltipComponents.add(Component.translatable(
                    "item.aeronautics_fortress_arise.ore_vein_extractor.empty"));
            return;
        }

        Component oreName = getOreName(oreId);
        tooltipComponents.add(Component.translatable(
                "item.aeronautics_fortress_arise.ore_vein_extractor.contents", oreName, veinLevel));
    }

    private static Component getOreName(String oreId) {
        ResourceLocation id = ResourceLocation.tryParse(oreId);
        if (id == null) {
            return Component.literal(oreId);
        }
        return BuiltInRegistries.ITEM.getOptional(id)
                .map(item -> Component.translatable(item.getDescriptionId()))
                .orElse(Component.literal(oreId));
    }

    private static CompoundTag getData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void setPayload(ItemStack stack, String oreId, int level) {
        CompoundTag tag = getData(stack);
        tag.putString(TAG_ORE_ID, oreId);
        tag.putInt(TAG_LEVEL, level);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clearPayload(ItemStack stack) {
        CompoundTag tag = getData(stack);
        tag.remove(TAG_ORE_ID);
        tag.remove(TAG_LEVEL);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void sendMessage(@Nullable Player player, Component message, boolean overlay) {
        if (player != null) {
            player.displayClientMessage(message, overlay);
        }
    }
}
