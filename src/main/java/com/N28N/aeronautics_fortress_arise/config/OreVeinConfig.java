package com.N28N.aeronautics_fortress_arise.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 矿脉方块的矿石物品列表配置。
 * <p>
 * 配置文件位于 {@code config/aeronautics_fortress_arise/ore_vein.json}，
 * 以 JSON 对象形式存储矿石物品 ID 与战利品品质（1~5）。
 * 例如 {@code "minecraft:iron_ore": 1}；第三方铝矿可按实际注册 ID 添加为
 * {@code "modid:aluminum_ore": 3}。旧版 JSON 字符串数组仍兼容，默认战利品品质为 1。
 * 矿脉方块生成矿石时，会从该列表中随机挑选一种矿石，在其上方生成对应的矿石方块。
 */
public class OreVeinConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(OreVeinConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 配置文件路径。 */
    private static final Path CONFIG_DIR = ModConfigPaths.DIRECTORY;
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("ore_vein.json");

    //#region Ore quality data
    private static final int DEFAULT_LOOT_QUALITY = 1;
    private static final int MIN_LOOT_QUALITY = 1;
    private static final int MAX_LOOT_QUALITY = 5;
    private static Map<String, Integer> oreLootQualities = new LinkedHashMap<>();
    //#endregion

    private OreVeinConfig() {
    }

    /** 加载（或创建默认）矿石列表配置。 */
    public static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
                LOGGER.info("Created config directory: {}", CONFIG_DIR);
            }

            if (!Files.exists(CONFIG_FILE)) {
                createDefaultConfig();
                LOGGER.info("Created default ore vein config file: {}", CONFIG_FILE);
                return;
            }

            String jsonContent = Files.readString(CONFIG_FILE);
            JsonElement root = JsonParser.parseString(jsonContent);
            Map<String, Integer> loaded = new LinkedHashMap<>();
            if (root.isJsonArray()) {
                for (JsonElement entry : root.getAsJsonArray()) {
                    if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                        String id = entry.getAsString().trim();
                        if (!id.isEmpty()) {
                            loaded.put(id, getDefaultLootQuality(id));
                        }
                    }
                }
                LOGGER.info("Loaded legacy ore list; assigning default loot quality to {} ores", loaded.size());
            } else if (root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    String id = entry.getKey().trim();
                    JsonElement value = entry.getValue();
                    if (id.isEmpty() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                        LOGGER.warn("Skipping invalid ore loot quality entry: {}={}", id, value);
                        continue;
                    }
                    int quality;
                    try {
                        quality = value.getAsBigDecimal().intValueExact();
                    } catch (ArithmeticException e) {
                        LOGGER.warn("Skipping non-integer ore loot quality: {}={}", id, value);
                        continue;
                    }
                    if (quality < MIN_LOOT_QUALITY || quality > MAX_LOOT_QUALITY) {
                        LOGGER.warn("Skipping ore loot quality outside 1-5: {}={}", id, quality);
                        continue;
                    }
                    loaded.put(id, quality);
                }
            } else {
                throw new IllegalArgumentException("Ore vein config must be a JSON object or legacy array");
            }
            oreLootQualities = loaded;
            LOGGER.info("Loaded {} ore loot quality entries", oreLootQualities.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load ore vein config", e);
        } catch (RuntimeException e) {
            LOGGER.error("Invalid ore vein config at {}", CONFIG_FILE, e);
        }
    }

    //#region Defaults and accessors
    /** 创建默认配置（包含常见原版矿石及其战利品品质）。 */
    private static void createDefaultConfig() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put("minecraft:coal_ore", 1);
        defaults.put("minecraft:iron_ore", 1);
        defaults.put("minecraft:copper_ore", 1);
        defaults.put("minecraft:gold_ore", 2);
        defaults.put("minecraft:redstone_ore", 2);
        defaults.put("minecraft:lapis_ore", 2);
        defaults.put("minecraft:diamond_ore", 4);
        defaults.put("minecraft:emerald_ore", 4);

        try {
            String jsonContent = GSON.toJson(defaults);
            Files.writeString(CONFIG_FILE, jsonContent);
            oreLootQualities = defaults;
        } catch (IOException e) {
            LOGGER.error("Failed to create default ore vein config", e);
        }
    }

    private static int getDefaultLootQuality(String itemId) {
        return switch (itemId) {
            case "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
                    "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
                    "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore" -> 2;
            case "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
                    "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore" -> 4;
            default -> DEFAULT_LOOT_QUALITY;
        };
    }

    public static Map<String, Integer> getOreLootQualities() {
        return new LinkedHashMap<>(oreLootQualities);
    }

    public static int getLootQuality(String itemId) {
        return oreLootQualities.getOrDefault(itemId, getDefaultLootQuality(itemId));
    }
    //#endregion
}
