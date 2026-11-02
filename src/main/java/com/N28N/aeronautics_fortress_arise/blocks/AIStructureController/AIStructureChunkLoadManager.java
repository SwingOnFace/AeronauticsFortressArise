package com.N28N.aeronautics_fortress_arise.blocks.AIStructureController;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

public final class AIStructureChunkLoadManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean DEBUG_LOG = true;

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
            ServerLevel level, BlockPos ownerPos, boolean shouldLoad) 
    {
        ChunkPos chunkPos = new ChunkPos(ownerPos);
        debug("控制器 {} 强加载 {}：dimension={} chunk=({}, {})",
                ownerPos, shouldLoad ? "开启" : "关闭",
                level.dimension().location(), chunkPos.x, chunkPos.z);
        TICKET_CONTROLLER.forceChunk(
                level, ownerPos, chunkPos.x, chunkPos.z, shouldLoad, true);
    }

    private static void validateTickets(ServerLevel level, TicketHelper helper) {
        AIStructureControllerIndexData index = AIStructureControllerIndexData.get(level);
        // 先把索引里仍有效的票据 owner 收集成集合，避免每张票据都线性扫描索引。
        Set<BlockPos> knownOwners = new HashSet<>();
        for (AIStructureControllerIndexData.ControllerEntry entry : index.entries(level.dimension())) {
            knownOwners.add(entry.ownerPos());
        }
        helper.getBlockTickets().forEach((ownerPos, tickets) -> {
            if (!knownOwners.contains(ownerPos)) {
                debug("清理失效强加载票据：dimension={} controller={}",
                        level.dimension().location(), ownerPos);
                helper.removeAllTickets(ownerPos);
            }
        });
    }

    private static void debug(String message, Object... args) {
        if (DEBUG_LOG) {
            LOGGER.info("[AIStructChunkLoad][DEBUG] " + message, args);
        }
    }
}
