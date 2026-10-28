# Aeronautics: Fortress Arise

[![Build](https://github.com/SwingOnFace/AeronauticsFortressArise/actions/workflows/build.yml/badge.svg)](https://github.com/SwingOnFace/AeronauticsFortressArise/actions/workflows/build.yml)

A **Create** addon for **NeoForge 1.21.1** that spawns flying fortress structures into the world and adds the machinery to defend them: AI-controlled cannons, turret aiming gear, intruder repellents, compact engines, ore veins, powered spawners and loot vaults.

Built on top of [Create](https://modrinth.com/mod/create), [Create Big Cannons](https://modrinth.com/mod/create-big-cannons) and [Aeronautics](https://modrinth.com/mod/create-aeronautics).

---

## Features

### Fortress generation

- Fortress blueprints are loaded as **Sable sub-levels** and placed high above the world.
- Each entry in the config has its own spawn chance, minimum distance from world spawn and chunk load range.
- Fortresses stay dormant until a player comes close, then keep their chunks loaded within range so their machinery keeps running.

### AI Structure Controller

- Records a `home` position on placement (structure top + hover height).
- Runs a **PID-based self-righting brain** every tick that levels the fortress back to its home position after impacts.
- Hover height is editable per block through its config screen and stored on the block entity.
- Works together with chunk loading so the structure remains simulated while players are nearby.

### AI Fire Controller

- Finds and tracks targets, then aims a **Create Big Cannons** cannon mount automatically.
- Ballistics are solved from the actual CBC munition data (`gravity`, `drag`, dimension multipliers) with iterative lead compensation for moving targets, then converted to yaw/pitch commands.
- Configurable sight range, propellant power (1-8) and accuracy (0-100%), plus a projectile whitelist.
- Requires kinetic power: input rotation speed controls reload interval and turret rotation speed.
- Optional **debug ballistics** logging to inspect each solver iteration.

### Aimer & Head Aiming Device

- **Head Aiming Device** is worn in the vanilla helmet slot or the **Curios** `head` slot (optional integration).
- Bind an **Aimer** to the device, then aim Create Big Cannons mounts by looking around.
- The Aimer passes Create kinetics through 1:1 and selects the targeted axis (yaw on the bottom face, pitch on the sides).

### Defensive and utility blocks

| Block | Purpose |
| --- | --- |
| **Anti-Intrusion Block** | Pushes entities away within a configurable radius. |
| **Compact Engine** | Compact kinetic power source (single, 3x3x3 and 5x5x5 variants, plus casing). |
| **Powered Spawner** | Spawns a configured mob above it while a player is nearby with line of sight; works correctly on Sable sub-levels. |
| **Ore Vein** | Placed ore vein with a level (1-100) and a loot quality, generated from the ore whitelist. |
| **Ore Vein Extractor** | Extracts a vein's level and transfers it to another vein of the same ore. |
| **Loot Vault** | 15 variants (raw / misc / machine × level 1-5). Generates a large read-only inventory on first tick and disappears when emptied. |

---

## Requirements

| Dependency | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228+ |
| Create | 6.0.9 - 6.0.x |
| Create Big Cannons | 5.11.7+ |
| Ritchie's Projectile Library | 2.1.2+ |
| Sable | 2.0.0+ |
| Simulated | 1.3.0+ |
| Aeronautics | 1.3.0+ |
| GeckoLib | 4.7.6+ |

Optional: **JEI** (19.x), **Curios** (9.x), **KubeJS** (2101.7.x).

---

## Installation

1. Install NeoForge 21.1.x for Minecraft 1.21.1.
2. Drop the required dependencies above into your `mods/` folder.
3. Drop the built jar into `mods/` as well.

---

## Building from source

Requires **JDK 21**.

```bash
./gradlew build        # Build the mod jar into build/libs
./gradlew runClient    # Launch a client with the mod
./gradlew runServer    # Launch a dedicated server
./gradlew runData      # Run data generators
```

On Windows you can also use the provided helper, which builds and launches the client:

```powershell
.\buildAndRun.ps1
```

CI builds every push and pull request to `main` via [`.github/workflows/build.yml`](.github/workflows/build.yml).

---

## Configuration

All files live in `config/aeronautics_fortress_arise/`. Legacy files from older versions are migrated automatically on startup.

### `sublevel_generation.json`

```json
[
  {
    "name": "ExampleName",
    "path": "folder1/sublevel_1.nbt",
    "spawnChance": 0.005,
    "minDistanceFromSpawn": 500,
    "loadRange": 8
  }
]
```

| Field | Description |
| --- | --- |
| `name` | Structure identifier used in logs and bookkeeping. |
| `path` | Blueprint path relative to the mod's blueprint directory. |
| `spawnChance` | `0.0` - `1.0`. |
| `minDistanceFromSpawn` | Minimum block distance from world spawn. |
| `loadRange` | Chunk radius kept loaded around the structure while players are near. |

### `ore_vein.json`

Maps ore item IDs to loot quality (`1`-`5`). A legacy string array is still accepted and defaults to quality `1`.

```json
{
  "minecraft:iron_ore": 1,
  "minecraft:gold_ore": 2,
  "minecraft:diamond_ore": 4,
  "yourmod:aluminum_ore": 3
}
```

### `ai-fire-controller.toml`

Kinetic behaviour and solver settings: `maxEfficiencySpeed`, `baseSupplyInterval`, `minSupplyInterval`, `baseRotationSpeed`, `maxRotationSpeed`, `debugBallistics`.

### `ai-structure-controller.toml`

Self-righting settings: `hoverHeight`, `maxTilt`, `maxOffset`, `deadzone` and the PID gains `pidKp`, `pidKi`, `pidKd`.

---

## KubeJS integration

KubeJS is optional. Scripts go in `kubejs/startup_scripts/`.

### Projectile options

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

The built-in projectile additions and removals remain active unless changed by the script.

### Loot vault options

Modify vaults by category (`raw`, `misc`, `machine`) and level (1-5):

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

Changes apply when a loot vault generates its inventory.

---

## 中文简介

**Aeronautics: Fortress Arise** 是一个基于 NeoForge 1.21.1 的 **Create** 附属模组，会在世界中生成高空浮空要塞，并提供配套的防御与生产设施：

- **要塞生成**：通过配置以 Sable 子空间形式高空放置蓝图，可设置生成概率、离出生点最小距离与区块加载范围。
- **AI 结构控制器**：记录 home 坐标，使用 PID 自动回正，受到撞击后把要塞拉回原位；悬浮高度可单独调整。
- **AI 火控**：自动索敌，读取机械动力火炮（Create Big Cannons）弹药的真实弹道数据解算提前量并操控炮座开火，需要接入旋转动力。
- **瞄准仪 / 头部瞄准仪**：戴上瞄准仪并绑定瞄准器后，用视线操控火炮，兼容 Curios 头部饰品槽。
- **其他方块**：防入侵方块、紧凑引擎（含 3x3x3 / 5x5x5）、动力刷怪笼、矿脉与矿脉提取器、15 种战利品保险箱。
- **可选联动**：JEI、Curios、KubeJS（可在启动脚本中增删火控弹药与保险箱战利品）。

构建需要 **JDK 21**，执行 `./gradlew build`，产物位于 `build/libs`。配置文件在 `config/aeronautics_fortress_arise/`。

---

## License

This project is licensed under the [MIT License](LICENSE).