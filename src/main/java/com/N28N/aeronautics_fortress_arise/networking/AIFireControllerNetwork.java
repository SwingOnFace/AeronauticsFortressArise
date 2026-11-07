package com.N28N.aeronautics_fortress_arise.networking;

import java.util.List;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBlockEntity;
import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerBrain;
import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerProjectileConfig;
import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.CannonMode;
import com.N28N.aeronautics_fortress_arise.client.AIFireController.AIFireControllerScreen;
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

public final class AIFireControllerNetwork {

    private AIFireControllerNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("ai_fire_controller").versioned("4");
        registrar.playToServer(C2SOpenScreenPacket.TYPE, C2SOpenScreenPacket.STREAM_CODEC,
                C2SOpenScreenPacket::handle);
        registrar.playToServer(C2SSaveConfigPacket.TYPE, C2SSaveConfigPacket.STREAM_CODEC,
                C2SSaveConfigPacket::handle);
        registrar.playToClient(S2COpenScreenPacket.TYPE, S2COpenScreenPacket.STREAM_CODEC,
                S2COpenScreenPacket::handle);
    }

    private static AIFireControllerBlockEntity getNearbyController(ServerPlayer player, BlockPos pos) {
        if (player.distanceToSqr(pos.getCenter()) > 64 || !player.serverLevel().hasChunkAt(pos)) {
            return null;
        }
        return player.serverLevel().getBlockEntity(pos) instanceof AIFireControllerBlockEntity controller
                ? controller
                : null;
    }

    public record C2SOpenScreenPacket(int x, int y, int z) implements CustomPacketPayload {
        public static final Type<C2SOpenScreenPacket> TYPE =
                new Type<>(AeronauticsFortressArise.asResource("c2s_fire_controller_open"));
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

        private static void handle(C2SOpenScreenPacket payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    AIFireControllerBlockEntity controller =
                            getNearbyController(player,
                                    new BlockPos(payload.x(), payload.y(), payload.z()));
                    if (controller != null) {
                        AIFireControllerBrain.debug("open screen: mode={} projectile={} power={} accuracy={}",
                                controller.getCannonMode(), controller.getProjectileId(),
                                controller.getPropellantPower(), controller.getAccuracy());
                        PacketDistributor.sendToPlayer(player, new S2COpenScreenPacket(
                                new BlockPos(payload.x(), payload.y(), payload.z()),
                                controller.getPropellantPower(), controller.getProjectileId(),
                                controller.getAccuracy(), controller.getCannonMode().ordinal(),
                                new S2COpenScreenPacket.ProjectileLists(
                                        AIFireControllerProjectileConfig.getSelectableProjectiles(
                                                CannonMode.BIG_CANNON),
                                        AIFireControllerProjectileConfig.getSelectableProjectiles(
                                                CannonMode.STANDARD_CANNON))));
                    }
                }
            });
        }
    }

    public record C2SSaveConfigPacket(BlockPos pos, int propellantPower, String projectileId, int accuracy,
            int cannonMode) implements CustomPacketPayload {
        public static final Type<C2SSaveConfigPacket> TYPE =
                new Type<>(AeronauticsFortressArise.asResource("c2s_fire_controller_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, C2SSaveConfigPacket> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, C2SSaveConfigPacket::pos,
                        ByteBufCodecs.INT, C2SSaveConfigPacket::propellantPower,
                        ByteBufCodecs.STRING_UTF8, C2SSaveConfigPacket::projectileId,
                        ByteBufCodecs.INT, C2SSaveConfigPacket::accuracy,
                        ByteBufCodecs.INT, C2SSaveConfigPacket::cannonMode,
                        C2SSaveConfigPacket::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void handle(C2SSaveConfigPacket payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) {
                    return;
                }
                CannonMode mode = CannonMode.byOrdinal(payload.cannonMode());
                AIFireControllerBlockEntity controller = getNearbyController(player, payload.pos());
                if (controller == null || payload.projectileId().length() > 2048
                        || AIFireControllerBlockEntity.createProjectileStack(
                                payload.projectileId(), player.serverLevel().registryAccess()).isEmpty()
                        || !AIFireControllerBlockEntity.isProjectileForMode(payload.projectileId(), mode)) {
                    AIFireControllerBrain.debug("save config REJECTED: mode={} projectile={}",
                            mode, payload.projectileId());
                    return;
                }
                controller.setCannonMode(mode);
                controller.setPropellantPower(payload.propellantPower());
                controller.setProjectileId(payload.projectileId());
                controller.setAccuracy(payload.accuracy());
                AIFireControllerBrain.debug("save config: mode={} projectile={} power={} accuracy={}",
                        controller.getCannonMode(), controller.getProjectileId(),
                        controller.getPropellantPower(), controller.getAccuracy());
            });
        }
    }

    public record S2COpenScreenPacket(BlockPos pos, int propellantPower, String projectileId, int accuracy,
            int cannonMode, ProjectileLists projectiles) implements CustomPacketPayload {
        public static final Type<S2COpenScreenPacket> TYPE =
                new Type<>(AeronauticsFortressArise.asResource("s2c_fire_controller_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenScreenPacket> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, S2COpenScreenPacket::pos,
                        ByteBufCodecs.INT, S2COpenScreenPacket::propellantPower,
                        ByteBufCodecs.STRING_UTF8, S2COpenScreenPacket::projectileId,
                        ByteBufCodecs.INT, S2COpenScreenPacket::accuracy,
                        ByteBufCodecs.INT, S2COpenScreenPacket::cannonMode,
                        ProjectileLists.STREAM_CODEC, S2COpenScreenPacket::projectiles,
                        S2COpenScreenPacket::new);

        /** 按火炮模式分别下发的可选弹药列表。 */
        public record ProjectileLists(List<String> bigCannon, List<String> standardCannon) {
            private static final StreamCodec<RegistryFriendlyByteBuf, List<String>> LIST_CODEC =
                    ByteBufCodecs.STRING_UTF8
                            .<RegistryFriendlyByteBuf>mapStream(buffer -> buffer)
                            .apply(ByteBufCodecs.list());
            public static final StreamCodec<RegistryFriendlyByteBuf, ProjectileLists> STREAM_CODEC =
                    StreamCodec.composite(
                            LIST_CODEC, ProjectileLists::bigCannon,
                            LIST_CODEC, ProjectileLists::standardCannon,
                            ProjectileLists::new);

            public List<String> forMode(CannonMode mode) {
                return mode == CannonMode.STANDARD_CANNON ? this.standardCannon : this.bigCannon;
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void handle(S2COpenScreenPacket payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() != null) {
                    AIFireControllerScreen.open(payload);
                }
            });
        }
    }
}
