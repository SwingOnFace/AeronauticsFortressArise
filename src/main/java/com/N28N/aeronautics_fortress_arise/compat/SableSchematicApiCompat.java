package com.N28N.aeronautics_fortress_arise.compat;

import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureControllerBlock;
import com.N28N.aeronautics_fortress_arise.blocks.AIStructureController.AIStructureControllerBlockEntity;

import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

public class SableSchematicApiCompat {
    public static void register(AIStructureControllerBlock aiStructureControllerBlock) {
        SableBlueprintMapperRegistry.register(aiStructureControllerBlock,
            new SableBlueprintBlockMapper() {
                @Override
                public void afterLoadBlockEntity(BlueprintBlockPlaceContext context,
                                                 BlockEntity blockEntity,
                                                 CompoundTag loadedTag) {
                    if (blockEntity instanceof AIStructureControllerBlockEntity aiStructureController) {
                        aiStructureController.onPlacedFromBlueprint(context.level(), context.storagePos());
                    }
                }
            });
    }
}
