package com.N28N.aeronautics_fortress_arise.config;

import com.N28N.aeronautics_fortress_arise.AeronauticsFortressArise;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class ModConfigPaths {
    public static final Path DIRECTORY =
            FMLPaths.CONFIGDIR.get().resolve(AeronauticsFortressArise.ID);

    private ModConfigPaths() {
    }

    public static void migrateLegacyFiles() {
        try {
            Files.createDirectories(DIRECTORY);
            migrateLegacyBlueprints();
            migrateLegacyConfig(AeronauticsFortressArise.ID + "-ai-fire-controller.toml",
                    "ai-fire-controller.toml");
            migrateLegacyConfig(AeronauticsFortressArise.ID + "-ai-structure-controller.toml",
                    "ai-structure-controller.toml");
        } catch (IOException e) {
            AeronauticsFortressArise.LOGGER.error("Failed to prepare mod config directory {}", DIRECTORY, e);
        }
    }

    private static void migrateLegacyBlueprints() throws IOException {
        Path legacyDirectory = FMLPaths.GAMEDIR.get().resolve("AeronauticsFortressArise");
        if (!Files.isDirectory(legacyDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(legacyDirectory)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                Path destination = DIRECTORY.resolve(legacyDirectory.relativize(source));
                if (Files.exists(destination)) {
                    AeronauticsFortressArise.LOGGER.warn(
                            "Keeping legacy blueprint because destination already exists: {}", destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.move(source, destination);
                AeronauticsFortressArise.LOGGER.info("Moved legacy blueprint to {}", destination);
            }
        }
    }

    private static void migrateLegacyConfig(String oldFileName, String newFileName) throws IOException {
        Path source = FMLPaths.CONFIGDIR.get().resolve(oldFileName);
        Path destination = DIRECTORY.resolve(newFileName);
        if (!Files.exists(source)) {
            return;
        }
        if (Files.exists(destination)) {
            AeronauticsFortressArise.LOGGER.warn(
                    "Keeping legacy config because destination already exists: {}", destination);
            return;
        }
        Files.move(source, destination);
        AeronauticsFortressArise.LOGGER.info("Moved legacy config to {}", destination);
    }
}
