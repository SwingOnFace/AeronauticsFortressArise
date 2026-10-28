package com.N28N.aeronautics_fortress_arise.compat.kubejs;

import com.N28N.aeronautics_fortress_arise.blocks.AIFireController.AIFireControllerProjectileConfig;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.plugin.ClassFilter;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;

public final class AIFireControllerKubeJSPlugin implements KubeJSPlugin {
    private static final EventGroup GROUP = EventGroup.of("AIFireControllerEvents");
    private static final EventHandler PROJECTILES =
            GROUP.startup("projectiles", () -> ProjectileListEvent.class);

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(GROUP);
    }

    @Override
    public void registerClasses(ClassFilter filter) {
        filter.allow(ProjectileListEvent.class);
    }

    @Override
    public void afterScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType == ScriptType.STARTUP) {
            AIFireControllerProjectileConfig.reset();
            PROJECTILES.post(new ProjectileListEvent());
        }
    }

    public static final class ProjectileListEvent implements KubeEvent {
        public void add(String projectileStack) {
            AIFireControllerProjectileConfig.add(projectileStack);
        }

        public void remove(String projectileId) {
            AIFireControllerProjectileConfig.remove(projectileId);
        }

        public void removeAdded(String projectileStack) {
            AIFireControllerProjectileConfig.removeAdded(projectileStack);
        }

        public void allow(String projectileId) {
            AIFireControllerProjectileConfig.allow(projectileId);
        }
    }
}
