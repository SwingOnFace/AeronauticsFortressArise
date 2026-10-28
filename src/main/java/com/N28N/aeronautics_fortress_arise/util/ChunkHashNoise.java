package com.N28N.aeronautics_fortress_arise.util;

public final class ChunkHashNoise {

    private ChunkHashNoise() {
    }

    public static long hash(int seed, int x, int z) {
        long h = seed;
        h = h * 0x9E3779B97F4A7C15L + x;
        h = h * 0x9E3779B97F4A7C15L + z;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h;
    }

    public static float random01(int seed, int x, int z) {
        return (hash(seed, x, z) >>> 40) * 0x1.0p-24f;
    }
}
