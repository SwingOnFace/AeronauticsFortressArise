package com.N28N.aeronautics_fortress_arise.config;

public class SubLevelConfig {
    public String name;
    public String path;
    public double spawnChance;
    public int minDistanceFromSpawn;

    public SubLevelConfig() {
    }

    public SubLevelConfig(String name, String path, double spawnChance, int minDistanceFromSpawn) {
        this.name = name;
        this.path = path;
        this.spawnChance = spawnChance;
        this.minDistanceFromSpawn = minDistanceFromSpawn;
    }

    @Override
    public String toString() {
        return "SubLevelConfig{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", spawnChance=" + spawnChance +
                ", minDistanceFromSpawn=" + minDistanceFromSpawn +
                '}';
    }
}