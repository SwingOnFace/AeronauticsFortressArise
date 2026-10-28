package com.N28N.aeronautics_fortress_arise.client;

import com.N28N.aeronautics_fortress_arise.blocks.Aimer.AimerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * 瞄准器渲染器。
 * <p>已用头部瞄准仪绑定后，在方块表面叠加一层半透明高亮（绿色识别纹理），
 * 未绑定则不渲染。纹理当前复用 AI 火炮控制器的 recognized 纹理作为占位。
 */
public class AimerRenderer implements BlockEntityRenderer<AimerBlockEntity> {

    private static final ResourceLocation BOUND_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "aeronautics_fortress_arise", "textures/block/ai_fire_controller_recognized.png");

    public AimerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AimerBlockEntity blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (blockEntity.getBoundUuid() == null) {
            return;
        }
        renderLayer(poseStack, buffer, BOUND_TEXTURE, 0.001f, 1.0f, packedLight, packedOverlay);
    }

    private static void renderLayer(PoseStack poseStack, MultiBufferSource buffer, ResourceLocation texture,
            float offset, float alpha, int packedLight, int packedOverlay) {
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(texture));
        int colorAlpha = (int) (255 * Math.min(1.0f, alpha));
        // north
        addQuad(consumer, pose, colorAlpha, packedLight, packedOverlay, offset,
                new float[] {0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0},
                new float[] {0, 0, 0, 0.0625f, 0.0625f, 0.0625f, 0.0625f, 0}, 0, 0, -1);
        // east
        addQuad(consumer, pose, colorAlpha, packedLight, packedOverlay, offset,
                new float[] {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1},
                new float[] {0, 0.0625f, 0, 0.125f, 0.0625f, 0.125f, 0.0625f, 0.0625f}, 1, 0, 0);
        // south
        addQuad(consumer, pose, colorAlpha, packedLight, packedOverlay, offset,
                new float[] {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},
                new float[] {0.0625f, 0, 0.125f, 0, 0.125f, 0.0625f, 0.0625f, 0.0625f}, 0, 0, 1);
        // west
        addQuad(consumer, pose, colorAlpha, packedLight, packedOverlay, offset,
                new float[] {0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0},
                new float[] {0.0625f, 0.0625f, 0.0625f, 0.125f, 0.125f, 0.125f, 0.125f, 0.0625f}, -1, 0, 0);
        // up
        addQuad(consumer, pose, colorAlpha, packedLight, packedOverlay, offset,
                new float[] {0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1},
                new float[] {0.0625f, 0.1875f, 0, 0.1875f, 0, 0.125f, 0.0625f, 0.125f}, 0, 1, 0);
        // down
        addQuad(consumer, pose, colorAlpha, packedLight, packedOverlay, offset,
                new float[] {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
                new float[] {0.1875f, 0, 0.125f, 0, 0.125f, 0.0625f, 0.1875f, 0.0625f}, 0, -1, 0);
    }

    private static void addQuad(VertexConsumer consumer, PoseStack.Pose pose, int alpha, int packedLight,
            int packedOverlay, float offset, float[] vertices, float[] uv, float normalX, float normalY,
            float normalZ) {
        float dx = normalX * offset;
        float dy = normalY * offset;
        float dz = normalZ * offset;
        vertex(consumer, pose, vertices[0] + dx, vertices[1] + dy, vertices[2] + dz, uv[0], uv[1], alpha,
                packedLight, packedOverlay, normalX, normalY, normalZ);
        vertex(consumer, pose, vertices[3] + dx, vertices[4] + dy, vertices[5] + dz, uv[2], uv[3], alpha,
                packedLight, packedOverlay, normalX, normalY, normalZ);
        vertex(consumer, pose, vertices[6] + dx, vertices[7] + dy, vertices[8] + dz, uv[4], uv[5], alpha,
                packedLight, packedOverlay, normalX, normalY, normalZ);
        vertex(consumer, pose, vertices[9] + dx, vertices[10] + dy, vertices[11] + dz, uv[6], uv[7], alpha,
                packedLight, packedOverlay, normalX, normalY, normalZ);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z,
            float u, float v, int alpha, int packedLight, int packedOverlay, float normalX, float normalY,
            float normalZ) {
        consumer.addVertex(pose, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v)
                .setOverlay(packedOverlay).setLight(packedLight).setNormal(pose, normalX, normalY, normalZ);
    }
}
