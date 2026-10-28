# Create Mod Addon Template

A ready-to-use template for building [Create](https://modrinth.com/mod/create) mod addons with **Java** and **NeoForge 1.21.1**.

## What's Included

- **NeoForge 1.21.1** with Create 6.0.10 dependency
- **Create Registrate** — Create's registration system, pre-configured
- **Ponder & Flywheel** — Create's rendering and documentation libraries
- **AI fire control** — computes trajectories from Create Big Cannons projectile physics and learns from observed impacts
- **JEI** — recipe viewer integration (optional, compile-only)
- **Mixin support** — pre-configured mixins
- **GitHub Actions** — automatic builds on push/PR
- **Gradle 8.10** with configuration cache enabled

## Getting Started

### 1. Use this template

Click **"Use this template"** on GitHub, or clone and rename.

### 2. Configure your mod

Edit `gradle.properties`:

```properties
mod_id=yourmod
mod_name=Your Mod Name
mod_version=0.1.0
mod_group_id=com.yourname.yourmod
mod_authors=YourName
mod_description=Your mod description.
mod_license=MIT
```

### 3. Rename packages

1. Rename `src/main/java/com/example/examplemod/` to match your `mod_group_id`
2. Update `ExampleMod.java` — change `ID` to your `mod_id`
3. Rename `src/main/resources/examplemod.mixins.json` to `{mod_id}.mixins.json`
4. Update the package path inside the mixins JSON

### 4. Build and run

```bash
./gradlew build          # Build the mod
./gradlew runClient      # Launch Minecraft with your mod
./gradlew runServer      # Launch a dedicated server
./gradlew runData        # Run data generators
```

## KubeJS projectile options

KubeJS is optional. When installed, configure the AI fire controller's projectile choices in a startup script:

```js
AIFireControllerEvents.projectiles(event => {
  // Add a full item string, including components.
  event.add('createbigcannons:he_shell[createbigcannons:fuze=[{item:{count:1,id:"createbigcannons:inertia_fuze"},slot:0}]]')

  // Hide a registered projectile item from the selection list.
  event.remove('createbigcannons:ap_shell')

  // Optional: remove an entry from the built-in/custom additions or undo a hidden item.
  // event.removeAdded('createbigcannons:he_shell[createbigcannons:fuze=[{item:{count:1,id:"createbigcannons:inertia_fuze"},slot:0}]]')
  // event.allow('createbigcannons:ap_shell')
})
```

Place the script in `kubejs/startup_scripts/`. The built-in projectile additions and removals remain active unless changed by the script.

## KubeJS loot vault options

KubeJS is optional. Modify loot vaults from a startup script using their category (`raw`, `misc`, or `machine`) and level (1-5):

```js
LootVaultEvents.modify(event => {
  // Add one bonus item stack per generated vault; the optional count defaults to 1.
  event.add('raw', 1, 'minecraft:diamond', 2)

  // Remove this item from the selected vault's existing loot table.
  event.remove('raw', 1, 'minecraft:coal')

  // Clear the selected vault's existing loot table; additions can still be made after this call.
  // event.clear('machine', 5)
  // event.add('machine', 5, 'minecraft:netherite_ingot', 1)
})
```

Place the script in `kubejs/startup_scripts/`. These changes apply when a loot vault generates its inventory.

## License

This template is provided under the [MIT License](LICENSE). Your mod built from this template can use any license you choose.
