package com.N28N.aeronautics_fortress_arise.networking;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureControllerBlockEntity;
import com.N28N.aeronautics_fortress_arise.client.screen.AIStructureControllerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * AI 结构控制器的网络通道。
 * <p>
 * 用于客户端与服务端之间传输控制器配置以及打开配置面板。
 */
public final class AIStructureControllerNetwork {

    /** 唯一的通道 ID（字符串形式）。 */
    private static final String CHANNEL_ID = "ai_structure_controller";

    private AIStructureControllerNetwork() {
    }

    /** 注册所有数据包。 */
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(CHANNEL_ID).versioned("3");
        registrar.playToServer(
                C2SOpenScreenPacket.TYPE, C2SOpenScreenPacket.STREAM_CODEC, C2SOpenScreenPacket::handle);
        registrar.playToServer(
                C2SSaveConfigPacket.TYPE, C2SSaveConfigPacket.STREAM_CODEC, C2SSaveConfigPacket::handle);
        registrar.playToClient(
                S2COpenScreenPacket.TYPE, S2COpenScreenPacket.STREAM_CODEC, S2COpenScreenPacket::handle);
    }

    /**
     * 客户端 → 服务端：请求打开配置面板。
     */
    public record C2SOpenScreenPacket(int x, int y, int z) implements CustomPacketPayload {
        public static final Type<C2SOpenScreenPacket> TYPE =
                new Type<>(AeronauticsFortressArise.asResource("c2s_open_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, C2SOpenScreenPacket> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT, C2SOpenScreenPacket::x,
                        ByteBufCodecs.INT, C2SOpenScreenPacket::y,
                        ByteBufCodecs.INT, C2SOpenScreenPacket::z,
                        C2SOpenScreenPacket::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void handle(final C2SOpenScreenPacket payload, final IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    return;
                }
                var level = serverPlayer.serverLevel();
                if (level.getBlockEntity(new BlockPos(payload.x(), payload.y(), payload.z()))
                        instanceof AIStructureControllerBlockEntity be) {
                    // 服务端确认存在后，把当前配置发回客户端打开面板。
                    PacketDistributor.sendToPlayer(serverPlayer,
                            new S2COpenScreenPacket(new BlockPos(payload.x(), payload.y(), payload.z()),
                                    be.getRewardQuality(),
                                    be.getRewardMinCount(), be.getRewardMaxCount(), be.getLoadRange()));
                }
            });
        }
    }

    /**
     * 客户端 → 服务端：保存配置。
     */
    public record C2SSaveConfigPacket(
            BlockPos pos, int rewardQuality, int rewardMinCount, int rewardMaxCount,
            int loadRange)
            implements CustomPacketPayload {
        public static final Type<C2SSaveConfigPacket> TYPE =
                new Type<>(AeronauticsFortressArise.asResource("c2s_save_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, C2SSaveConfigPacket> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, C2SSaveConfigPacket::pos,
                        ByteBufCodecs.INT, C2SSaveConfigPacket::rewardQuality,
                        ByteBufCodecs.INT, C2SSaveConfigPacket::rewardMinCount,
                        ByteBufCodecs.INT, C2SSaveConfigPacket::rewardMaxCount,
                        ByteBufCodecs.INT, C2SSaveConfigPacket::loadRange,
                        C2SSaveConfigPacket::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void handle(final C2SSaveConfigPacket payload, final IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    return;
                }
                var level = serverPlayer.serverLevel();
                if (level.getBlockEntity(payload.pos())
                        instanceof AIStructureControllerBlockEntity be) {
                    be.setRewardQuality(payload.rewardQuality());
                    be.setRewardMinCount(payload.rewardMinCount());
                    be.setRewardMaxCount(payload.rewardMaxCount());
                    be.setLoadRange(payload.loadRange());
                    be.setChanged();
                }
            });
        }
    }

    /**
     * 服务端 → 客户端：打开配置面板并携带当前配置值。
     */
    public record S2COpenScreenPacket(
            BlockPos pos, int rewardQuality, int rewardMinCount, int rewardMaxCount,
            int loadRange)
            implements CustomPacketPayload {
        public static final Type<S2COpenScreenPacket> TYPE =
                new Type<>(AeronauticsFortressArise.asResource("s2c_open_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenScreenPacket> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, S2COpenScreenPacket::pos,
                        ByteBufCodecs.INT, S2COpenScreenPacket::rewardQuality,
                        ByteBufCodecs.INT, S2COpenScreenPacket::rewardMinCount,
                        ByteBufCodecs.INT, S2COpenScreenPacket::rewardMaxCount,
                        ByteBufCodecs.INT, S2COpenScreenPacket::loadRange,
                        S2COpenScreenPacket::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void handle(final S2COpenScreenPacket payload, final IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() == null) {
                    return;
                }
                // 客户端线程：打开配置面板
                AIStructureControllerScreen.open(payload);
            });
        }
    }
}
