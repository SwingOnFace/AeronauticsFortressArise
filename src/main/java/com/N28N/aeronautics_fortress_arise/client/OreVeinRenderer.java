package com.N28N.aeronautics_fortress_arise.client;

import com.N28N.aeronautics_fortress_arise.blocks.OreVein.OreVeinBlockEntity;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矿脉方块的渲染器。
 * <p>
 * 矿脉方块直接复用对应矿石方块的模型（见 {@code OreVeinModelProvider}），
 * 外观与矿石一致。此渲染器手动渲染矿石的六个面（带矿石贴图），
 * 并在表面叠加一层半透明白色提亮层，使矿脉方块整体颜色比正常矿石更亮，
 * 以突出其“矿脉”身份。
 */
public class OreVeinRenderer implements BlockEntityRenderer<OreVeinBlockEntity> {

    /**
     * 提亮层渲染类型。
     * <p>
     * 使用半透明白色叠加在矿石模型表面，通过混合让颜色变亮。
     * NO_CULL 确保从内外两侧都能看到立方体面。
     */
    private static final RenderType BRIGHTEN_RENDER_TYPE = RenderType.create(
            "ore_vein_brighten",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .createCompositeState(false));

    public OreVeinRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            OreVeinBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay) {

        BlockState oreState = blockEntity.getOreBlockState();
        if (oreState == null) {
            return;
        }

        // 获取矿石贴图（粒子图标即方块主贴图）
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer()
                .getBlockModel(oreState)
                .getParticleIcon();

        // 创建带矿石贴图的渲染类型
        RenderType oreRenderType = RenderType.create(
                "ore_vein_textured",
                DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_SOLID_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(sprite.atlasLocation(), false, false))
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .createCompositeState(false));

        VertexConsumer consumer = buffer.getBuffer(oreRenderType);
        PoseStack.Pose pose = poseStack.last();

        // 矿石模型略微外扩，避免与周围方块表面产生 z-fighting 或缝隙。
        float expand = 0.002F;
        float min = -expand;
        float max = 1.0F + expand;

        // 手动渲染矿石的六个面（带贴图）
        addTexturedCube(consumer, pose, min, min, min, max, max, max, sprite, packedLight, packedOverlay);

        // 半透明白色提亮层：白色叠加在矿石模型上，让颜色更亮。
        // 提亮层略微外扩，避免与矿石模型表面产生 z-fighting。
        VertexConsumer brightenConsumer = buffer.getBuffer(BRIGHTEN_RENDER_TYPE);

        // 白色，透明度适中（约 40%），让矿石颜色明显变亮但不过曝。
        int red = 255;
        int green = 255;
        int blue = 255;
        int alpha = 100;

        addCube(brightenConsumer, pose, min, min, min, max, max, max, red, green, blue, alpha);
    }

    /**
     * 绘制一个带贴图的六面立方体。
     * <p>
     * 每个面使用矿石贴图的完整 UV 范围，法线方向与面朝向一致，
     * 使光照效果与普通方块一致。
     */
    private static void addTexturedCube(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            TextureAtlasSprite sprite,
            int packedLight,
            int packedOverlay) {

        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float u1 = sprite.getU1();
        float v1 = sprite.getV1();

        // 南面：Z = minZ，法线 (0, 0, -1)
        addTexturedFace(consumer, pose,
                minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ,
                u0, v0, u1, v0, u1, v1, u0, v1,
                0, 0, -1, packedLight, packedOverlay);
        // 北面：Z = maxZ，法线 (0, 0, 1)
        addTexturedFace(consumer, pose,
                maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ,
                u0, v0, u1, v0, u1, v1, u0, v1,
                0, 0, 1, packedLight, packedOverlay);
        // 西面：X = minX，法线 (-1, 0, 0)
        addTexturedFace(consumer, pose,
                minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ,
                u0, v0, u1, v0, u1, v1, u0, v1,
                -1, 0, 0, packedLight, packedOverlay);
        // 东面：X = maxX，法线 (1, 0, 0)
        addTexturedFace(consumer, pose,
                maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ,
                u0, v0, u1, v0, u1, v1, u0, v1,
                1, 0, 0, packedLight, packedOverlay);
        // 顶面：Y = maxY，法线 (0, 1, 0)
        addTexturedFace(consumer, pose,
                minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ,
                u0, v0, u1, v0, u1, v1, u0, v1,
                0, 1, 0, packedLight, packedOverlay);
        // 底面：Y = minY，法线 (0, -1, 0)
        addTexturedFace(consumer, pose,
                minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ,
                u0, v0, u1, v0, u1, v1, u0, v1,
                0, -1, 0, packedLight, packedOverlay);
    }

    /**
     * 以四边形方式写入一个带贴图的面。
     */
    private static void addTexturedFace(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4,
            float u1, float v1,
            float u2, float v2,
            float u3, float v3,
            float u4, float v4,
            float nx, float ny, float nz,
            int packedLight,
            int packedOverlay) {

        addTexturedVertex(consumer, pose, x1, y1, z1, u1, v1, nx, ny, nz, packedLight, packedOverlay);
        addTexturedVertex(consumer, pose, x2, y2, z2, u2, v2, nx, ny, nz, packedLight, packedOverlay);
        addTexturedVertex(consumer, pose, x3, y3, z3, u3, v3, nx, ny, nz, packedLight, packedOverlay);
        addTexturedVertex(consumer, pose, x4, y4, z4, u4, v4, nx, ny, nz, packedLight, packedOverlay);
    }

    /**
     * 写入一个带贴图、光照和法线的顶点。
     */
    private static void addTexturedVertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz,
            int packedLight,
            int packedOverlay) {

        consumer.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setUv2(packedLight & 0xFFFF, packedLight >> 16 & 0xFFFF)
                .setNormal(pose, nx, ny, nz);
    }

    /** 绘制一个六面立方体（纯色，用于提亮层）。 */
    private static void addCube(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            int red,
            int green,
            int blue,
            int alpha) {

        // 南面：Z = minZ
        addFace(consumer, pose, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ,
                red, green, blue, alpha);
        // 北面：Z = maxZ
        addFace(consumer, pose, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ,
                red, green, blue, alpha);
        // 西面：X = minX
        addFace(consumer, pose, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ,
                red, green, blue, alpha);
        // 东面：X = maxX
        addFace(consumer, pose, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ,
                red, green, blue, alpha);
        // 顶面：Y = maxY
        addFace(consumer, pose, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ,
                red, green, blue, alpha);
        // 底面：Y = minY
        addFace(consumer, pose, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ,
                red, green, blue, alpha);
    }

    /** 以四边形方式写入一个面。 */
    private static void addFace(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4,
            int red, int green, int blue, int alpha) {

        addVertex(consumer, pose, x1, y1, z1, red, green, blue, alpha);
        addVertex(consumer, pose, x2, y2, z2, red, green, blue, alpha);
        addVertex(consumer, pose, x3, y3, z3, red, green, blue, alpha);
        addVertex(consumer, pose, x4, y4, z4, red, green, blue, alpha);
    }

    private static void addVertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x, float y, float z,
            int red, int green, int blue, int alpha) {

        consumer.addVertex(pose, x, y, z)
                .setColor(red, green, blue, alpha);
    }
}
