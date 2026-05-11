package com.havokwaves.waves.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;

import com.havokwaves.waves.state.SurfaceColumn;

import static com.havokwaves.waves.service.MaterialPredicates.isWaterBodyMaterial;
import static com.havokwaves.waves.service.MaterialPredicates.isWaterVegetation;
import static com.havokwaves.waves.service.WorldProbes.isChunkLoaded;
import static com.havokwaves.waves.service.WorldProbes.isSurfaceOpenAbove;
import static com.havokwaves.waves.service.WorldProbes.resolveSurfaceWaterY;
import static com.havokwaves.waves.service.WorldProbes.waterDepthAt;

final class ChunkSurfaceScanner {
    private static final long CHUNK_CACHE_TTL_TICKS = 400L;

    private final BiomeFetchSampler biomeFetch;
    private final Map<ChunkCacheKey, ChunkSurfaceCache> cache = new ConcurrentHashMap<>();

    ChunkSurfaceScanner(final BiomeFetchSampler biomeFetch) {
        this.biomeFetch = biomeFetch;
    }

    List<SurfaceColumn> scanArea(
            final World world,
            final int centerX,
            final int centerZ,
            final int scanRadius,
            final long simulationTick
    ) {
        final List<SurfaceColumn> columns = new ArrayList<>();
        final List<int[]> chunks = collectChunks(centerX, centerZ, scanRadius);
        for (final int[] chunk : chunks) {
            columns.addAll(scanChunk(world, chunk[0], chunk[1], centerX, centerZ, scanRadius, simulationTick));
        }
        return columns;
    }

    List<int[]> collectChunks(final int centerX, final int centerZ, final int radius) {
        final int minChunkX = (centerX - radius) >> 4;
        final int maxChunkX = (centerX + radius) >> 4;
        final int minChunkZ = (centerZ - radius) >> 4;
        final int maxChunkZ = (centerZ + radius) >> 4;
        final List<int[]> chunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new int[] {chunkX, chunkZ});
            }
        }
        return chunks;
    }

    List<SurfaceColumn> scanChunk(
            final World world,
            final int chunkX,
            final int chunkZ,
            final int centerX,
            final int centerZ,
            final int radius,
            final long simulationTick
    ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return List.of();
        }
        final List<SurfaceColumn> baseColumns = getOrCreateChunkColumns(world, chunkX, chunkZ, simulationTick);
        if (baseColumns.isEmpty()) {
            return List.of();
        }
        final int radiusSquared = radius * radius;
        final List<SurfaceColumn> filtered = new ArrayList<>(baseColumns.size());
        for (final SurfaceColumn column : baseColumns) {
            final int dx = column.x() - centerX;
            final int dz = column.z() - centerZ;
            if ((dx * dx) + (dz * dz) > radiusSquared) {
                continue;
            }
            filtered.add(column);
        }
        return filtered;
    }

    private List<SurfaceColumn> getOrCreateChunkColumns(
            final World world,
            final int chunkX,
            final int chunkZ,
            final long simulationTick
    ) {
        final ChunkCacheKey key = new ChunkCacheKey(world.getUID(), chunkX, chunkZ);
        final ChunkSurfaceCache existing = cache.get(key);
        if (existing != null && (simulationTick - existing.tick()) <= CHUNK_CACHE_TTL_TICKS) {
            return existing.columns();
        }

        final int minX = chunkX << 4;
        final int minZ = chunkZ << 4;
        final List<SurfaceColumn> columns = new ArrayList<>(256);
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                final SurfaceColumn column = findSurfaceColumn(world, x, z);
                if (column != null) {
                    columns.add(column);
                }
            }
        }
        final List<SurfaceColumn> immutable = List.copyOf(columns);
        cache.put(key, new ChunkSurfaceCache(simulationTick, immutable));
        return immutable;
    }

    private SurfaceColumn findSurfaceColumn(final World world, final int x, final int z) {
        if (!isChunkLoaded(world, x, z)) {
            return null;
        }
        final int worldMin = world.getMinHeight();
        final int worldMax = world.getMaxHeight() - 2;
        int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        top = Math.max(worldMin + 1, Math.min(worldMax, top));
        final int floor = Math.max(worldMin + 1, top - 8);

        for (int y = top; y >= floor; y--) {
            final Material current = world.getBlockAt(x, y, z).getType();
            if (!isWaterBodyMaterial(current) && !isWaterVegetation(current)) {
                continue;
            }
            if (!isSurfaceOpenAbove(world, x, y, z)) {
                continue;
            }
            final int surfaceWaterY = resolveSurfaceWaterY(world, x, y, z, floor);
            if (surfaceWaterY != Integer.MIN_VALUE) {
                final int depth = waterDepthAt(world, x, surfaceWaterY, z);
                if (!biomeFetch.isOceanWaveArea(world, x, surfaceWaterY, z, depth)) {
                    continue;
                }
                return new SurfaceColumn(x, surfaceWaterY, z, depth);
            }
        }
        return null;
    }

    void invalidateNear(final World world, final int chunkX, final int chunkZ) {
        final UUID worldId = world.getUID();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                cache.remove(new ChunkCacheKey(worldId, chunkX + dx, chunkZ + dz));
            }
        }
    }

    void cleanupStale(final long simulationTick) {
        if (cache.isEmpty()) {
            return;
        }
        final long maxAge = CHUNK_CACHE_TTL_TICKS * 2L;
        cache.entrySet().removeIf(entry -> (simulationTick - entry.getValue().tick()) > maxAge);
    }

    void clear() {
        cache.clear();
    }

    private record ChunkSurfaceCache(long tick, List<SurfaceColumn> columns) {
    }
}
