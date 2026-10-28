package com.N28N.aeronautics_fortress_arise.config;

public class SubLevelConfig {
    public String name;
    public String path;
    public double spawnChance;
    public int minDistanceFromSpawn;
    /** 玩家与 AI 结构之间的最大加载距离（区块）。 */
    public int loadRange = 8;

    public SubLevelConfig() {
    }

    public SubLevelConfig(String name, String path, double spawnChance, int minDistanceFromSpawn) {
        this(name, path, spawnChance, minDistanceFromSpawn, 8);
    }

    public SubLevelConfig(String name, String path, double spawnChance, int minDistanceFromSpawn, int loadRange) {
        this.name = name;
        this.path = path;
        this.spawnChance = spawnChance;
        this.minDistanceFromSpawn = minDistanceFromSpawn;
        this.loadRange = loadRange;
    }

    @Override
    public String toString() {
        return "SubLevelConfig{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", spawnChance=" + spawnChance +
                ", minDistanceFromSpawn=" + minDistanceFromSpawn +
                ", loadRange=" + loadRange +
                '}';
    }
}