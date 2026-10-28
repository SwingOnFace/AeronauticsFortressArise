package com.N28N.aeronautics_fortress_arise.compat.kubejs;

import com.N28N.aeronautics_fortress_arise.blocks.LootItemVault.LootVaultLootConfig;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.plugin.ClassFilter;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;

public final class LootVaultKubeJSPlugin implements KubeJSPlugin {
    private static final EventGroup GROUP = EventGroup.of("LootVaultEvents");
    private static final EventHandler MODIFY =
            GROUP.startup("modify", () -> LootVaultModifyEvent.class);

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(GROUP);
    }

    @Override
    public void registerClasses(ClassFilter filter) {
        filter.allow(LootVaultModifyEvent.class);
    }

    @Override
    public void afterScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType == ScriptType.STARTUP) {
            LootVaultLootConfig.reset();
            MODIFY.post(new LootVaultModifyEvent());
        }
    }

    public static final class LootVaultModifyEvent implements KubeEvent {
        public void add(String category, int level, String itemId) {
            LootVaultLootConfig.add(category, level, itemId, 1);
        }

        public void add(String category, int level, String itemId, int count) {
            LootVaultLootConfig.add(category, level, itemId, count);
        }

        public void remove(String category, int level, String itemId) {
            LootVaultLootConfig.remove(category, level, itemId);
        }

        public void clear(String category, int level) {
            LootVaultLootConfig.clear(category, level);
        }
    }
}
