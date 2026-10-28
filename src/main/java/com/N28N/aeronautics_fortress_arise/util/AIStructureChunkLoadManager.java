package com.N28N.aeronautics_fortress_arise.util;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

public final class AIStructureChunkLoadManager {

    private static final TicketController TICKET_CONTROLLER = new TicketController(
            AeronauticsFortressArise.asResource("ai_structure_controller"),
            AIStructureChunkLoadManager::validateTickets);

    private AIStructureChunkLoadManager() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(AIStructureChunkLoadManager::registerTicketController);
    }

    private static void registerTicketController(RegisterTicketControllersEvent event) {
        event.register(TICKET_CONTROLLER);
    }

    public static void setControllerChunkLoaded(
            ServerLevel level, BlockPos ownerPos, boolean shouldLoad) {
        ChunkPos chunkPos = new ChunkPos(ownerPos);
        TICKET_CONTROLLER.forceChunk(
                level, ownerPos, chunkPos.x, chunkPos.z, shouldLoad, true);
    }

    private static void validateTickets(ServerLevel level, TicketHelper helper) {
        AIStructureControllerIndexData index = AIStructureControllerIndexData.get(level);
        helper.getBlockTickets().forEach((ownerPos, tickets) -> {
            if (!index.contains(level.dimension(), ownerPos.asLong())) {
                helper.removeAllTickets(ownerPos);
            }
        });
    }
}
