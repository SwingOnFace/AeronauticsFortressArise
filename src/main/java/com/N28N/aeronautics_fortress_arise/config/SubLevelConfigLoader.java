package com.N28N.aeronautics_fortress_arise.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SubLevelConfigLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubLevelConfigLoader.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<SubLevelConfig> configs = new ArrayList<>();

    // 配置文件路径
    private static final Path CONFIG_DIR = ModConfigPaths.DIRECTORY;
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("sublevel_generation.json");

    public static void loadConfig() {
        try {
            // 如果配置目录不存在，创建它
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
                LOGGER.info("Created config directory: {}", CONFIG_DIR);
            }

            // 如果配置文件不存在，创建默认配置
            if (!Files.exists(CONFIG_FILE)) {
                createDefaultConfig();
                LOGGER.info("Created default config file: {}", CONFIG_FILE);
                return;
            }

            // 读取并解析 JSON 文件
            String jsonContent = Files.readString(CONFIG_FILE);
            SubLevelConfig[] configArray = GSON.fromJson(jsonContent, SubLevelConfig[].class);

            configs.clear();
            if (configArray != null) {
                for (SubLevelConfig config : configArray) {
                    // 验证配置
                    if (validateConfig(config)) {
                        configs.add(config);
                        LOGGER.info("Loaded SubLevel config: {}", config);
                    } else {
                        LOGGER.warn("Invalid config, skipping: {}", config);
                    }
                }
            }

            LOGGER.info("Successfully loaded {} SubLevel configs", configs.size());

        } catch (IOException e) {
            LOGGER.error("Failed to load SubLevel config", e);
        }
    }

    private static boolean validateConfig(SubLevelConfig config) {
        if (config.name == null || config.name.isBlank()) {
            LOGGER.warn("Config name is empty");
            return false;
        }
        if (config.path == null || config.path.isBlank()) {
            LOGGER.warn("Config path is empty");
            return false;
        }
        if (config.spawnChance < 0 || config.spawnChance > 1) {
            LOGGER.warn("spawnChance must be between 0 and 1, got: {}", config.spawnChance);
            return false;
        }
        if (config.minDistanceFromSpawn < 0) {
            LOGGER.warn("minDistanceFromSpawn must be >= 0, got: {}", config.minDistanceFromSpawn);
            return false;
        }
        return true;
    }

    private static void createDefaultConfig() {
        try {
            List<SubLevelConfig> defaultConfigs = new ArrayList<>();
            defaultConfigs.add(new SubLevelConfig("ExampleName", "folder1/sublevel_1.nbt", 0.005, 500));
            defaultConfigs.add(new SubLevelConfig("AnotherStructure", "folder2/sublevel_2.nbt", 0.01, 1000));

            String jsonContent = GSON.toJson(defaultConfigs);
            Files.writeString(CONFIG_FILE, jsonContent);
            configs = defaultConfigs;
        } catch (IOException e) {
            LOGGER.error("Failed to create default config", e);
        }
    }

    public static List<SubLevelConfig> getConfigs() {
        return new ArrayList<>(configs);
    }

    public static void reloadConfig() {
        LOGGER.info("Reloading SubLevel configs...");
        loadConfig();
    }
}