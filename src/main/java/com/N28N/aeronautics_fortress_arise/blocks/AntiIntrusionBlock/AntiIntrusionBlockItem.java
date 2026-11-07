package com.N28N.aeronautics_fortress_arise.blocks.AntiIntrusionBlock;

import java.util.function.Consumer;

import com.N28N.aeronautics_fortress_arise.client.AntiIntrusionBlock.AntiIntrusionItemRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 反入侵方块的物品形态。
 * <p>方块模型在游戏内由 GeckoLib 渲染（方块模型本身为空模型），
 * 因此物品栏与手持也需要使用同一 GeckoLib 模型渲染。
 */
public class AntiIntrusionBlockItem extends BlockItem implements GeoItem {

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    public AntiIntrusionBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animationCache;
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private AntiIntrusionItemRenderer renderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.renderer == null) {
                    this.renderer = new AntiIntrusionItemRenderer();
                }
                return this.renderer;
            }
        });
    }
}
