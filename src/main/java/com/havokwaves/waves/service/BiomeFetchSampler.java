package com.havokwaves.waves.service;

import java.util.function.Supplier;

import org.bukkit.World;

import com.havokwaves.waves.config.PluginConfig;

import static com.havokwaves.waves.service.MaterialPredicates.isWater;

final class BiomeFetchSampler {
    private final Supplier<PluginConfig> configSupplier;

    BiomeFetchSampler(final Supplier<PluginConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    boolean isOceanWaveArea(
            final World world,
            final int x,
            final int y,
            final int z,
            final int depth
    ) {
        if (depth < 1) {
            return false;
        }
        final PluginConfig config = configSupplier.get();
        if (config.isBiomeAllowed(world.getBiome(x, y, z))) {
            return true;
        }
        if (!hasNearbyAllowedBiome(world, x, y, z, 10, config)) {
            return false;
        }
        return hasOpenWaterFetch(world, x, y, z, 8);
    }

    private boolean hasNearbyAllowedBiome(
            final World world,
            final int x,
            final int y,
            final int z,
            final int radius,
            final PluginConfig config
    ) {
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) {
                    continue;
                }
                if ((dx * dx) + (dz * dz) > (radius * radius)) {
                    continue;
                }
                if (config.isBiomeAllowed(world.getBiome(x + dx, y, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasOpenWaterFetch(
            final World world,
            final int x,
            final int y,
            final int z,
            final int radius
    ) {
        int waterColumns = 0;
        int sampled = 0;
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                if ((dx * dx) + (dz * dz) > (radius * radius)) {
                    continue;
                }
                final int cx = x + dx;
                final int cz = z + dz;
                if (!world.isChunkLoaded(cx >> 4, cz >> 4)) {
                    continue;
                }
                sampled++;
                if (isWater(world.getBlockAt(cx, y, cz).getType())) {
                    waterColumns++;
                }
            }
        }
        if (sampled < 10) {
            return false;
        }
        final double ratio = waterColumns / (double) sampled;
        return ratio >= 0.55D;
    }
}
