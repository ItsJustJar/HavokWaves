package com.havokwaves.waves.service;

import org.bukkit.Material;
import org.bukkit.World;

import static com.havokwaves.waves.service.MaterialPredicates.isAirLike;
import static com.havokwaves.waves.service.MaterialPredicates.isWater;
import static com.havokwaves.waves.service.MaterialPredicates.isWaterBodyMaterial;
import static com.havokwaves.waves.service.MaterialPredicates.isWaterVegetation;

final class WorldProbes {
    private WorldProbes() {}

    static boolean isChunkLoaded(final World world, final int blockX, final int blockZ) {
        return world.isChunkLoaded(blockX >> 4, blockZ >> 4);
    }

    static int waterDepthAt(final World world, final int x, final int surfaceY, final int z) {
        final int minY = world.getMinHeight();
        int depth = 0;
        for (int y = surfaceY; y >= minY && depth < 16; y--) {
            if (!isWater(world.getBlockAt(x, y, z).getType())) {
                break;
            }
            depth++;
        }
        return depth;
    }

    static boolean isSurfaceOpenAbove(final World world, final int x, final int y, final int z) {
        final int maxY = world.getMaxHeight() - 1;
        for (int yy = y + 1; yy <= maxY && yy <= y + 6; yy++) {
            final Material above = world.getBlockAt(x, yy, z).getType();
            if (isWaterVegetation(above)) {
                continue;
            }
            if (isWaterBodyMaterial(above)) {
                return false;
            }
            return isAirLike(above);
        }
        return false;
    }

    static int resolveSurfaceWaterY(final World world, final int x, final int y, final int z, final int floor) {
        final Material here = world.getBlockAt(x, y, z).getType();
        if (isWaterBodyMaterial(here) || isWaterVegetation(here)) {
            return y;
        }
        for (int yy = y - 1; yy >= floor; yy--) {
            final Material below = world.getBlockAt(x, yy, z).getType();
            if (isWaterBodyMaterial(below) || isWaterVegetation(below)) {
                return yy;
            }
            if (!isWaterVegetation(below)) {
                break;
            }
        }
        return Integer.MIN_VALUE;
    }
}
