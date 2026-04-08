package com.havokwaves.waves.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;

import com.havokwaves.waves.config.PluginConfig;
import com.havokwaves.waves.config.PluginConfig.WaveProfile;
import com.havokwaves.waves.packet.PacketEventsBridge;
import com.havokwaves.waves.scheduler.ServerScheduler;
import com.havokwaves.waves.state.FakeBlockState;
import com.havokwaves.waves.state.PlayerWaveState;
import com.havokwaves.waves.state.SurfaceColumn;
import com.havokwaves.waves.util.BlockPosUtil;
import com.havokwaves.waves.wave.WaveModel;
import com.havokwaves.waves.wave.WaveModel.WaveSample;

public final class WaveRenderService {
    private static final long CHUNK_CACHE_TTL_TICKS = 400L;
    private static final long VISUAL_STICKY_TICKS = 40L;
    private static final long SHORE_DIRECTION_CACHE_TTL_TICKS = 600L;
    private static final long COLUMN_VISUAL_MEMORY_TICKS = 220L;
    private static final long PHYSICAL_CELL_BASE_TTL_TICKS = 40L;
    private static final long PHYSICAL_CLEANUP_INTERVAL_TICKS = 8L;
    private static final long PHYSICAL_CELL_MAX_LIFETIME_TICKS = 80L;
    private static final long PHYSICAL_CELL_REFRACTORY_TICKS = 18L;
    private static final int RENDER_EDGE_BLEND_BLOCKS = 20;
    private static final int MAX_SHORE_DIRECTIONS_PER_COLUMN = 1;
    private static final int MAX_RUNUPS_PER_PLAYER = 192;
    private static final long RUNUP_TRIGGER_COOLDOWN_TICKS = 18L;
    private static final double RUNUP_ADVANCE_TICKS_PER_BLOCK = 4.0D;
    private static final double RUNUP_RETREAT_TICKS_PER_BLOCK = 5.0D;
    private static final int SEAFOAM_PARTICLE_BUDGET_PER_TICK = 20;
    private static final int RAIN_RIPPLE_PARTICLE_BUDGET_PER_TICK = 10;
    private static final int STORM_SPRAY_PARTICLE_BUDGET_PER_TICK = 6;
    private static final int[] CREST_FLOW_LEVELS = {7, 6, 5, 4, 3, 2, 1};
    private static final int[][] RUNUP_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final ServerScheduler scheduler;
    private final PacketEventsBridge packetBridge;
    private final WaveModel waveModel;
    private final Supplier<PluginConfig> configSupplier;
    private final PlayerToggleService toggleService;
    private final Map<UUID, PlayerWaveState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, ShoreRunupState>> playerRunups = new ConcurrentHashMap<>();
    private final Map<ChunkCacheKey, ChunkSurfaceCache> chunkSurfaceCache = new ConcurrentHashMap<>();
    private final Map<PhysicalCellKey, PhysicalShoreCell> physicalShoreCells = new ConcurrentHashMap<>();
    private final Map<PhysicalCellKey, Long> physicalCellRefractoryUntil = new ConcurrentHashMap<>();
    private final Map<Integer, BlockData> flowingWaterDataCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> flowingWaterVisualDataStringCache = new ConcurrentHashMap<>();
    private final Map<ShoreColumnKey, CachedShoreDirection> shoreDirectionCache = new ConcurrentHashMap<>();
    private volatile long lastChunkCacheCleanupTick = Long.MIN_VALUE;
    private volatile long lastPhysicalCleanupTick = Long.MIN_VALUE;

    public WaveRenderService(
            final ServerScheduler scheduler,
            final PacketEventsBridge packetBridge,
            final WaveModel waveModel,
            final Supplier<PluginConfig> configSupplier,
            final PlayerToggleService toggleService
    ) {
        this.scheduler = scheduler;
        this.packetBridge = packetBridge;
        this.waveModel = waveModel;
        this.configSupplier = configSupplier;
        this.toggleService = toggleService;
    }

    public void tickPlayer(final Player player, final long simulationTick) {
        final PluginConfig config = configSupplier.get();
        if (!packetBridge.isAvailable()
                || !player.isOnline()
                || !player.hasPermission("waves.use")
                || !toggleService.isEnabled(player)
                || !config.isWorldAllowed(player.getWorld().getName())) {
            clearPlayer(player);
            return;
        }

        final PlayerWaveState state = playerStates.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerWaveState());
        final int scanRadius = Math.max(
                config.resolveSimulationRadius(player.getWorld()),
                config.resolveRenderRadius(player.getWorld())
        );
        final int renderRadius = config.resolveRenderRadius(player.getWorld());
        if (state.shouldRequestScan(player.getLocation(), scanRadius, renderRadius, simulationTick)) {
            requestSurfaceScan(player, state, scanRadius, simulationTick);
        }

        applyVisuals(player, state, simulationTick, config, renderRadius);
    }

    public void tickGlobal(final long simulationTick) {
        cleanupExpiredPhysicalShoreCells(simulationTick);
    }

    public void markDirty(final Player player) {
        final PlayerWaveState state = playerStates.get(player.getUniqueId());
        if (state != null) {
            state.markDirty();
        }
    }

    public void markDirtyNearChunk(final World world, final int chunkX, final int chunkZ) {
        invalidateChunkCache(world, chunkX, chunkZ);
        final PluginConfig config = configSupplier.get();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(world)) {
                continue;
            }
            final int radiusChunks = Math.max(1, (config.resolveRenderRadius(world) / 16) + 1);
            final int playerChunkX = player.getLocation().getBlockX() >> 4;
            final int playerChunkZ = player.getLocation().getBlockZ() >> 4;
            if (Math.abs(playerChunkX - chunkX) <= radiusChunks && Math.abs(playerChunkZ - chunkZ) <= radiusChunks) {
                markDirty(player);
            }
        }
    }

    public void invalidateAll() {
        for (final PlayerWaveState state : playerStates.values()) {
            state.markDirty();
        }
        chunkSurfaceCache.clear();
        shoreDirectionCache.clear();
        restoreAllPhysicalShoreCells();
        physicalCellRefractoryUntil.clear();
    }

    public void clearPlayer(final Player player) {
        final PlayerWaveState state = playerStates.remove(player.getUniqueId());
        playerRunups.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        synchronized (state) {
            final Map<Long, FakeBlockState> active = state.getActiveFakeBlocks();
            final Map<Long, Long> sticky = state.getStickyVisualUntilTick();
            restoreAll(player, active);
            active.clear();
            sticky.clear();
            state.clearVisualTracking();
            state.markDirty();
        }
    }

    public void clearAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            clearPlayer(player);
        }
        playerStates.clear();
        playerRunups.clear();
        chunkSurfaceCache.clear();
        shoreDirectionCache.clear();
        restoreAllPhysicalShoreCells();
        physicalCellRefractoryUntil.clear();
    }

    public void clearAllImmediate() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            clearPlayer(player);
        }
        playerStates.clear();
        playerRunups.clear();
        chunkSurfaceCache.clear();
        shoreDirectionCache.clear();
        restoreAllPhysicalShoreCellsImmediate();
        physicalCellRefractoryUntil.clear();
    }

    private void requestSurfaceScan(
            final Player player,
            final PlayerWaveState state,
            final int scanRadius,
            final long simulationTick
    ) {
        cleanupStaleChunkCache(simulationTick);
        if (!state.beginScan()) {
            return;
        }

        final World world = player.getWorld();
        final int centerX = chunkAnchor(player.getLocation().getBlockX());
        final int centerZ = chunkAnchor(player.getLocation().getBlockZ());

        if (!scheduler.isFolia()) {
            final List<SurfaceColumn> columns = scanArea(world, centerX, centerZ, scanRadius, simulationTick);
            state.completeScan(centerX, centerZ, simulationTick, columns);
            return;
        }

        final List<int[]> chunks = collectChunks(centerX, centerZ, scanRadius);
        if (chunks.isEmpty()) {
            state.completeScan(centerX, centerZ, simulationTick, List.of());
            return;
        }

        final ConcurrentLinkedQueue<SurfaceColumn> collected = new ConcurrentLinkedQueue<>();
        final AtomicInteger remaining = new AtomicInteger(chunks.size());

        for (final int[] chunk : chunks) {
            final int chunkX = chunk[0];
            final int chunkZ = chunk[1];
            scheduler.runRegion(world, chunkX, chunkZ, () -> {
                collected.addAll(scanChunk(world, chunkX, chunkZ, centerX, centerZ, scanRadius, simulationTick));
                if (remaining.decrementAndGet() == 0) {
                    scheduler.runPlayer(player, () -> {
                        if (!player.isOnline() || !player.getWorld().equals(world)) {
                            state.failScan();
                            return;
                        }
                        state.completeScan(centerX, centerZ, simulationTick, new ArrayList<>(collected));
                    });
                }
            });
        }
    }

    private List<SurfaceColumn> scanArea(
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

    private List<SurfaceColumn> scanChunk(
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
        final ChunkSurfaceCache existing = chunkSurfaceCache.get(key);
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
        chunkSurfaceCache.put(key, new ChunkSurfaceCache(simulationTick, immutable));
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
                if (!isOceanWaveArea(world, x, surfaceWaterY, z, depth)) {
                    continue;
                }
                return new SurfaceColumn(x, surfaceWaterY, z, depth);
            }
        }
        return null;
    }

    private void applyVisuals(
            final Player player,
            final PlayerWaveState state,
            final long simulationTick,
            final PluginConfig config,
            final int renderRadius
    ) {
        final List<SurfaceColumn> columns = state.getSurfaceColumnsSnapshot();
        final WaveProfile profile = config.resolveWaveProfile(player.getWorld());
        final World world = player.getWorld();
        final int playerX = player.getLocation().getBlockX();
        final int playerZ = player.getLocation().getBlockZ();
        final int eyeYFloor = (int) Math.floor(player.getEyeLocation().getY());
        final boolean cameraInWater = isWater(player.getEyeLocation().getBlock().getType());
        final Boat ridingBoat = player.getVehicle() instanceof Boat boat ? boat : null;
        final int boatX = ridingBoat != null ? ridingBoat.getLocation().getBlockX() : Integer.MIN_VALUE;
        final int boatY = ridingBoat != null ? ridingBoat.getLocation().getBlockY() : Integer.MIN_VALUE;
        final int boatZ = ridingBoat != null ? ridingBoat.getLocation().getBlockZ() : Integer.MIN_VALUE;
        final int renderCenterX = playerX;
        final int renderCenterZ = playerZ;
        final int effectiveRenderRadius = renderRadius + 4;
        final double visualCap = computeVisualCap(profile);
        final double driftAngle = waveDriftAngle(world, profile, simulationTick);
        final boolean raining = world.hasStorm();
        final boolean storming = world.isThundering();
        final int radiusSq = effectiveRenderRadius * effectiveRenderRadius;
        columns.sort(Comparator.comparingInt(column -> distanceSquared(column.x(), column.z(), renderCenterX, renderCenterZ)));
        final Map<Long, FakeBlockState> desired = new LinkedHashMap<>(Math.max(16, columns.size() / 2));
        final List<PhysicalShorePlacement> physicalPlacements = new ArrayList<>();
        int seafoamBudget = SEAFOAM_PARTICLE_BUDGET_PER_TICK;
        int rainRippleBudget = raining ? RAIN_RIPPLE_PARTICLE_BUDGET_PER_TICK : 0;
        int stormSprayBudget = storming ? STORM_SPRAY_PARTICLE_BUDGET_PER_TICK : 0;

        for (final SurfaceColumn column : columns) {
            final int distSq = distanceSquared(column.x(), column.z(), renderCenterX, renderCenterZ);
            if (distSq > radiusSq) {
                continue;
            }
            final double edgeBlend = renderEdgeBlend(distSq, effectiveRenderRadius);
            if (edgeBlend <= 0.0D) {
                continue;
            }
            final WaveSample sample = waveModel.sample(
                    profile,
                    column.x() + 0.5D,
                    column.z() + 0.5D,
                    simulationTick,
                    waveModel.shorelineFactor(column.waterDepth())
            );
                final ShoreDirectionBias shoreBias = resolveShoreDirectionBias(player.getWorld(), column, simulationTick);
                final double[] waveDirection = blendDriftWithShoreDirection(driftAngle, shoreBias);
            final long columnKey = columnKey(column.x(), column.z());
            double targetVisualHeight = computeCoherentVisualHeight(
                    profile,
                    sample,
                    column,
                    simulationTick,
                    edgeBlend,
                    visualCap,
                    driftAngle,
                    shoreBias
            );
            final double crestCoverage = crestCoverageGate(profile, sample);
            if (crestCoverage <= 0.005D) {
                state.putColumnVisualState(columnKey, 0.0D, 0, simulationTick);
                continue;
            }
            targetVisualHeight *= crestCoverage;
            final double lifecycle = shorelineLifecycleEnvelope(shoreBias, profile, simulationTick, column.waterDepth());
            targetVisualHeight *= lifecycle;
            final double setSpacing = crestSetSpacingFactor(
                    profile,
                    column,
                    simulationTick,
                    waveDirection[0],
                    waveDirection[1],
                    shoreBias
            );
            targetVisualHeight *= setSpacing;
            if (sample.visualBand() > 0) {
                final double visibilityFloor = 0.09D
                        * edgeBlend
                        * smoothStep(0.36D, 0.84D, sample.sporadicGate())
                        * smoothStep(0.52D, 1.10D, sample.intensity());
                targetVisualHeight = Math.max(targetVisualHeight, visibilityFloor);
                if (!raining) {
                    final double sunnyFloor = (0.13D + (sample.intensity() * 0.04D))
                            * edgeBlend
                            * smoothStep(0.22D, 0.78D, sample.sporadicGate());
                    targetVisualHeight = Math.max(targetVisualHeight, sunnyFloor);
                }
            }
            if (shoreBias.bias() > 0.0D) {
                targetVisualHeight *= 1.0D + (shoreBias.bias() * 0.18D);
            }
            if (storming) {
                final double stormDepthAttenuation = clamp(waveModel.shorelineFactor(column.waterDepth()) + 0.20D, 0.42D, 1.0D);
                targetVisualHeight *= stormDepthAttenuation;
            }
            final double smoothedHeight = smoothVisualHeight(
                    profile,
                    targetVisualHeight,
                    state.getSmoothedColumnHeight(columnKey)
            );
            final int previousStep = state.getColumnVisualStep(columnKey);
            int steps = classifyVisualSteps(profile, smoothedHeight, previousStep);
            steps = dampStepTransition(previousStep, steps, smoothedHeight);
            final double distNorm = Math.sqrt(distSq / Math.max(1.0D, (double) radiusSq));
            if (distNorm > 0.86D) {
                steps = Math.min(1, steps);
            } else if (distNorm > 0.70D) {
                steps = Math.min(2, steps);
            }
            if (storming) {
                steps = Math.min(3, steps);
            }
            if (shoreBias.bias() >= 0.70D && sample.intensity() > 1.12D && smoothedHeight > 0.17D) {
                steps = Math.max(steps, 1);
            }
            if (shoreBias.shorelineDistance() <= 2) {
                steps = Math.min(1, steps);
            }
            state.putColumnVisualState(columnKey, smoothedHeight, steps, simulationTick);
            if (steps > 0) {
                for (int level = 1; level <= steps; level++) {
                    final int y = column.y() + level;
                    final Material restore = player.getWorld().getBlockAt(column.x(), y, column.z()).getType();
                    if (!isVisualReplaceable(restore)) {
                        continue;
                    }
                    if (shouldSkipCrestForCamera(cameraInWater, playerX, playerZ, eyeYFloor, column.x(), y, column.z())) {
                        continue;
                    }
                    if (shouldSkipForBoatEnvelope(boatX, boatY, boatZ, column.x(), y, column.z())) {
                        continue;
                    }
                        final String fakeData = level == steps
                            ? crestTopVisualWaterData(smoothedHeight, steps, visualCap)
                            : null;
                    desired.put(
                            BlockPosUtil.pack(column.x(), y, column.z()),
                            new FakeBlockState(Material.WATER, restore, fakeData)
                    );
                }

                if (seafoamBudget > 0 || stormSprayBudget > 0) {
                    final double crestFront = crestFrontFactor(
                            profile, column, sample, simulationTick, waveDirection[0], waveDirection[1]);
                    if (seafoamBudget > 0
                            && shouldSpawnSeaFoam(column, sample, simulationTick, edgeBlend, distSq, radiusSq, crestFront)) {
                        spawnSeaFoamParticles(
                                player,
                                column.x(),
                                column.y() + steps,
                                column.z(),
                                sample.intensity(),
                                waveDirection[0],
                                waveDirection[1]
                        );
                        seafoamBudget--;
                    }
                    if (stormSprayBudget > 0
                            && shouldSpawnStormSpray(column, sample, simulationTick, edgeBlend, distSq, radiusSq, crestFront)) {
                        spawnStormCrestSprayParticles(
                                player,
                                column.x(),
                                column.y() + steps,
                                column.z(),
                                sample.intensity(),
                                waveDirection[0],
                                waveDirection[1]
                        );
                        stormSprayBudget--;
                    }
                }

                registerShoreRunups(player, column, sample, steps, simulationTick);
            }

            if (rainRippleBudget > 0
                    && shouldSpawnRainRipple(column, sample, simulationTick, edgeBlend, distSq, radiusSq, storming)) {
                spawnRainRippleParticles(player, column.x(), column.y() + 1, column.z(), storming);
                rainRippleBudget--;
            }
        }
        state.pruneColumnVisualState(simulationTick - COLUMN_VISUAL_MEMORY_TICKS);
        renderActiveShoreRunups(
                player,
                simulationTick,
                desired,
                seafoamBudget,
                physicalPlacements
        );
        applyPhysicalShoreWater(player.getWorld(), simulationTick, physicalPlacements);

        final int cap = config.maxBlockUpdatesPerTickPerPlayer();
        synchronized (state) {
            int sent = 0;
            final Map<Long, FakeBlockState> active = state.getActiveFakeBlocks();
            final Map<Long, Long> sticky = state.getStickyVisualUntilTick();
            final List<Long> restoreQueue = new ArrayList<>();
            final long stickyUntil = simulationTick + VISUAL_STICKY_TICKS;
            for (final Long packed : desired.keySet()) {
                sticky.put(packed, stickyUntil);
            }
            final Iterator<Map.Entry<Long, FakeBlockState>> activeIterator = active.entrySet().iterator();

            while (activeIterator.hasNext()) {
                final Map.Entry<Long, FakeBlockState> current = activeIterator.next();
                final FakeBlockState wanted = desired.remove(current.getKey());
                if (wanted == null) {
                    final Long keepUntilTick = sticky.get(current.getKey());
                    if (keepUntilTick != null && simulationTick <= keepUntilTick) {
                        continue;
                    }
                    restoreQueue.add(current.getKey());
                    continue;
                }

                if (!sameVisualState(current.getValue(), wanted) && sent < cap) {
                    sendPacked(player, current.getKey(), wanted);
                    current.setValue(wanted);
                    sent++;
                }
            }

            for (final Map.Entry<Long, FakeBlockState> wanted : desired.entrySet()) {
                if (sent >= cap) {
                    break;
                }
                if (active.containsKey(wanted.getKey())) {
                    continue;
                }
                sendPacked(player, wanted.getKey(), wanted.getValue());
                active.put(wanted.getKey(), wanted.getValue());
                sent++;
            }

            for (final Long packed : restoreQueue) {
                if (sent >= cap) {
                    break;
                }
                final FakeBlockState current = active.remove(packed);
                if (current == null) {
                    sticky.remove(packed);
                    continue;
                }
                sendPacked(player, packed, current.restoreMaterial());
                sticky.remove(packed);
                sent++;
            }

            final long pruneBefore = simulationTick - (VISUAL_STICKY_TICKS * 3L);
            sticky.entrySet().removeIf(entry -> entry.getValue() < pruneBefore && !active.containsKey(entry.getKey()));
        }
    }

    private List<int[]> collectChunks(final int centerX, final int centerZ, final int radius) {
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

    private void sendPacked(final Player player, final long packed, final Material material) {
        packetBridge.sendBlockChange(
                player,
                BlockPosUtil.unpackX(packed),
                BlockPosUtil.unpackY(packed),
                BlockPosUtil.unpackZ(packed),
                material
        );
    }

    private void sendPacked(final Player player, final long packed, final FakeBlockState fakeState) {
        final int x = BlockPosUtil.unpackX(packed);
        final int y = BlockPosUtil.unpackY(packed);
        final int z = BlockPosUtil.unpackZ(packed);
        if (fakeState.fakeBlockData() != null) {
            packetBridge.sendBlockChange(player, x, y, z, fakeState.fakeBlockData());
            return;
        }
        packetBridge.sendBlockChange(player, x, y, z, fakeState.fakeMaterial());
    }

    private boolean sameVisualState(final FakeBlockState left, final FakeBlockState right) {
        if (left.fakeMaterial() != right.fakeMaterial()) {
            return false;
        }
        if (left.fakeBlockData() == null) {
            return right.fakeBlockData() == null;
        }
        return left.fakeBlockData().equals(right.fakeBlockData());
    }

    private void restoreAll(final Player player, final Map<Long, FakeBlockState> active) {
        if (!player.isOnline()) {
            return;
        }
        for (final Map.Entry<Long, FakeBlockState> entry : active.entrySet()) {
            sendPacked(player, entry.getKey(), entry.getValue().restoreMaterial());
        }
    }

    private boolean isAirLike(final Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private boolean isWater(final Material material) {
        return isWaterBodyMaterial(material) || isWaterVegetation(material);
    }

    private int waterDepthAt(final World world, final int x, final int surfaceY, final int z) {
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

    private boolean shouldSkipCrestForCamera(
            final boolean cameraInWater,
            final int playerX,
            final int playerZ,
            final int eyeYFloor,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        return cameraInWater
            && blockX == playerX
                && blockZ == playerZ
            && blockY == eyeYFloor;
    }

    private boolean shouldSkipForBoatEnvelope(
            final int boatX,
            final int boatY,
            final int boatZ,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        if (boatX == Integer.MIN_VALUE) {
            return false;
        }
        final boolean nearHull = Math.abs(blockX - boatX) <= 1 && Math.abs(blockZ - boatZ) <= 1;
        final boolean inHullVertical = blockY >= boatY && blockY <= (boatY + 2);
        return nearHull && inHullVertical;
    }

    private void registerShoreRunups(
            final Player player,
            final SurfaceColumn column,
            final WaveSample sample,
            final int crestSteps,
            final long simulationTick
    ) {
        if (sample.visualBand() <= 0 || sample.rawHeight() <= 0.0D || sample.intensity() < 0.92D) {
            return;
        }
        final int runupDistance = computeRunupDistance(sample, crestSteps);
        if (runupDistance <= 0) {
            return;
        }

        final World world = player.getWorld();
        final Map<Long, ShoreRunupState> runups = playerRunups.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new ConcurrentHashMap<>()
        );

        final List<ShoreDirectionHit> hits = collectShorelineHits(world, column, runupDistance + 2);
        if (hits.isEmpty()) {
            return;
        }

        final int directionsToUse = Math.min(MAX_SHORE_DIRECTIONS_PER_COLUMN, hits.size());
        for (int i = 0; i < directionsToUse; i++) {
            final ShoreDirectionHit hit = hits.get(i);
            final int[] dir = hit.direction();
            final List<RunupNode> path = buildRunupPath(world, column, dir, hit.shorelineStart(), runupDistance);
            if (path.size() < 2) {
                continue;
            }

            final RunupNode impact = path.get(0);
            final long key = shorelineKey(impact.x(), impact.baseY(), impact.z(), dir[0], dir[1]);
            final ShoreRunupState existing = runups.get(key);
            if (existing != null && (simulationTick - existing.lastTriggerTick) < RUNUP_TRIGGER_COOLDOWN_TICKS) {
                continue;
            }

            final int peakLayers = crestSteps >= 2 ? 2 : 1;
            runups.put(
                    key,
                    new ShoreRunupState(
                            world.getUID(),
                            path,
                            column.y(),
                            peakLayers,
                            sample.intensity(),
                            simulationTick,
                            false
                    )
            );
        }

        trimRunups(runups);
    }

    private int renderActiveShoreRunups(
            final Player player,
            final long simulationTick,
            final Map<Long, FakeBlockState> desired,
            int particleBudget,
            final List<PhysicalShorePlacement> physicalPlacements
    ) {
        final Map<Long, ShoreRunupState> runups = playerRunups.get(player.getUniqueId());
        if (runups == null || runups.isEmpty()) {
            return particleBudget;
        }

        final World world = player.getWorld();
        final UUID worldId = world.getUID();
        final List<Long> expired = new ArrayList<>();

        for (final Map.Entry<Long, ShoreRunupState> entry : runups.entrySet()) {
            final ShoreRunupState runup = entry.getValue();
            if (!runup.worldId.equals(worldId) || runup.path.isEmpty()) {
                expired.add(entry.getKey());
                continue;
            }

            final long age = Math.max(0L, simulationTick - runup.startTick);
            final double advanceTicks = Math.max(4.0D, runup.path.size() * RUNUP_ADVANCE_TICKS_PER_BLOCK);
            final double retreatTicks = Math.max(6.0D, runup.path.size() * RUNUP_RETREAT_TICKS_PER_BLOCK);
            final double totalTicks = advanceTicks + retreatTicks;
            if (age > totalTicks) {
                expired.add(entry.getKey());
                continue;
            }

            final double front = 1.0D + Math.min(runup.path.size(), age / RUNUP_ADVANCE_TICKS_PER_BLOCK);
            final double retreat = age <= advanceTicks
                    ? 0.0D
                    : Math.min(runup.path.size(), (age - advanceTicks) / RUNUP_RETREAT_TICKS_PER_BLOCK);
            final int globalCollapse = age <= advanceTicks ? 0 : (int) ((age - advanceTicks) / 7L);

            if (!runup.impactParticleSent && particleBudget > 0) {
                final RunupNode impact = runup.path.get(0);
                spawnShoreImpactParticles(player, impact.x(), impact.baseY(), impact.z(), runup.intensity);
                runup.impactParticleSent = true;
                particleBudget--;
            }

            for (int i = 0; i < runup.path.size(); i++) {
                final double nodeIndex = i + 1.0D;
                if (nodeIndex > front) {
                    break;
                }
                if (i < retreat) {
                    continue;
                }

                final int layers = runupLayersForDistance(runup, i, globalCollapse, front);
                if (layers <= 0) {
                    continue;
                }

                final RunupNode node = runup.path.get(i);
                boolean placedAny = false;
                for (int layer = 0; layer < layers; layer++) {
                    final int y = node.baseY() + layer;
                    if (y > runup.waterSurfaceY + 3) {
                        break;
                    }
                    final Material current = world.getBlockAt(node.x(), y, node.z()).getType();
                    if (!isRunupReplaceable(current)) {
                        if (!placedAny) {
                            break;
                        }
                        continue;
                    }
                    desired.put(
                            BlockPosUtil.pack(node.x(), y, node.z()),
                            new FakeBlockState(Material.WATER, current)
                    );
                    placedAny = true;
                }
                if (placedAny) {
                    final int physicalY = node.baseY();
                    final int waterLevel = physicalWaterLevel(i, runup.path.size(), globalCollapse);
                    final int ttl = physicalWaterTtl(runup.path.size(), i);
                    physicalPlacements.add(
                            new PhysicalShorePlacement(node.x(), physicalY, node.z(), waterLevel, ttl, false)
                    );

                    if (i == 0) {
                        final int seaTop = runup.waterSurfaceY + 1;
                        final int low = Math.min(seaTop, physicalY);
                        final int high = Math.max(seaTop, physicalY);
                        for (int yy = low; yy <= high; yy++) {
                            if (Math.abs(yy - physicalY) > 2) {
                                continue;
                            }
                            final Material connector = world.getBlockAt(node.x(), yy, node.z()).getType();
                            if (isRunupReplaceable(connector)) {
                                desired.put(
                                        BlockPosUtil.pack(node.x(), yy, node.z()),
                                        new FakeBlockState(Material.WATER, connector)
                                );
                                physicalPlacements.add(
                                        new PhysicalShorePlacement(
                                                node.x(),
                                                yy,
                                                node.z(),
                                                Math.max(1, waterLevel - 1),
                                                ttl + 4,
                                                false
                                        )
                                );
                            }
                        }
                    }
                }
            }
        }

        for (final Long key : expired) {
            runups.remove(key);
        }
        return particleBudget;
    }

    private List<RunupNode> buildRunupPath(
            final World world,
            final SurfaceColumn column,
            final int[] dir,
            final int shorelineStart,
            final int runupDistance
    ) {
        final List<RunupNode> path = new ArrayList<>(runupDistance);
        int previousBaseY = Integer.MIN_VALUE;
        for (int inland = 0; inland < runupDistance; inland++) {
            final int d = shorelineStart + inland;
            final int x = column.x() + (dir[0] * d);
            final int z = column.z() + (dir[1] * d);
            if (!isChunkLoaded(world, x, z)) {
                break;
            }
            final int groundY = findShoreGroundY(world, x, column.y(), z);
            if (groundY == Integer.MIN_VALUE) {
                break;
            }

            final int baseY = groundY + 1;
            if (baseY > column.y() + 1) {
                break;
            }
            if (previousBaseY != Integer.MIN_VALUE && Math.abs(baseY - previousBaseY) > 1) {
                break;
            }

            final Material current = world.getBlockAt(x, baseY, z).getType();
            if (!isRunupReplaceable(current)) {
                break;
            }

            path.add(new RunupNode(x, z, baseY));
            previousBaseY = baseY;
        }
        return path;
    }

    private int computeRunupDistance(final WaveSample sample, final int crestSteps) {
        if (sample.visualBand() <= 0) {
            return 0;
        }
        final int stepBoost = crestSteps >= 3 ? 2 : (crestSteps >= 2 ? 1 : 0);
        final int intensityBoost = (int) Math.floor(sample.intensity() * 0.6D);
        return clamp(2 + stepBoost + intensityBoost, 2, 6);
    }

    private int findShorelineStart(
            final World world,
            final SurfaceColumn column,
            final int[] dir,
            final int maxProbeDistance
    ) {
        final int surfaceY = column.y();
        for (int d = 1; d <= maxProbeDistance; d++) {
            final int x = column.x() + (dir[0] * d);
            final int z = column.z() + (dir[1] * d);
            if (!isChunkLoaded(world, x, z)) {
                return Integer.MIN_VALUE;
            }
            final Material atSurface = world.getBlockAt(x, surfaceY, z).getType();
            if (isWater(atSurface)) {
                continue;
            }
            final int groundY = findShoreGroundY(world, x, surfaceY, z);
            if (groundY == Integer.MIN_VALUE || (groundY + 1) > (surfaceY + 1)) {
                return Integer.MIN_VALUE;
            }
            final Material replaceTarget = world.getBlockAt(x, groundY + 1, z).getType();
            return isRunupReplaceable(replaceTarget) ? d : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    private List<ShoreDirectionHit> collectShorelineHits(
            final World world,
            final SurfaceColumn column,
            final int maxProbeDistance
    ) {
        final List<ShoreDirectionHit> hits = new ArrayList<>(RUNUP_DIRECTIONS.length);
        for (final int[] dir : RUNUP_DIRECTIONS) {
            final int shorelineStart = findShorelineStart(world, column, dir, maxProbeDistance);
            if (shorelineStart == Integer.MIN_VALUE) {
                continue;
            }
            hits.add(new ShoreDirectionHit(new int[] {dir[0], dir[1]}, shorelineStart));
        }
        hits.sort(Comparator.comparingInt(ShoreDirectionHit::shorelineStart));
        return hits;
    }

    private int runupLayersForDistance(
            final ShoreRunupState runup,
            final int inlandDistance,
            final int globalCollapse,
            final double front
    ) {
        int layers = runup.peakLayers;
        if (inlandDistance >= 2) {
            layers -= 1;
        }
        if (((inlandDistance + 1.0D) + 1.1D) >= front) {
            layers = Math.min(1, layers);
        }
        layers -= globalCollapse;
        return Math.max(0, layers);
    }

    private int physicalWaterLevel(final int inlandDistance, final int pathSize, final int globalCollapse) {
        final int waveTail = Math.max(0, pathSize - inlandDistance - 1);
        int level;
        if (inlandDistance <= 1 && globalCollapse == 0) {
            level = 1;
        } else {
            level = 1 + Math.min(5, Math.max(0, inlandDistance - 1) + (globalCollapse / 2));
        }
        if (waveTail <= 1 && inlandDistance > 0) {
            level = Math.max(level, 4);
        }
        return clamp(level, 1, 7);
    }

    private int physicalWaterTtl(final int pathSize, final int inlandDistance) {
        final int tailFactor = Math.max(0, pathSize - inlandDistance);
        final int ttl = (int) PHYSICAL_CELL_BASE_TTL_TICKS + Math.min(12, tailFactor * 2);
        return Math.max(16, ttl);
    }

    private long shorelineKey(final int x, final int y, final int z, final int dirX, final int dirZ) {
        final long packed = BlockPosUtil.pack(x, y, z);
        final long dirCode = (((long) (dirX + 2)) << 3) ^ (dirZ + 2L);
        return packed ^ (dirCode * 0x9E3779B97F4A7C15L);
    }

    private void trimRunups(final Map<Long, ShoreRunupState> runups) {
        while (runups.size() > MAX_RUNUPS_PER_PLAYER) {
            long oldestTick = Long.MAX_VALUE;
            Long oldestKey = null;
            for (final Map.Entry<Long, ShoreRunupState> entry : runups.entrySet()) {
                if (entry.getValue().startTick < oldestTick) {
                    oldestTick = entry.getValue().startTick;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            runups.remove(oldestKey);
        }
    }

    private void applyPhysicalShoreWater(
            final World world,
            final long simulationTick,
            final List<PhysicalShorePlacement> placements
    ) {
        if (placements.isEmpty()) {
            return;
        }
        final Map<Long, PhysicalShorePlacement> mergedPlacements = new HashMap<>();
        for (final PhysicalShorePlacement placement : placements) {
            final long packed = BlockPosUtil.pack(placement.x(), placement.y(), placement.z());
            final PhysicalShorePlacement existing = mergedPlacements.get(packed);
            if (existing == null) {
                mergedPlacements.put(packed, placement);
                continue;
            }
            mergedPlacements.put(
                    packed,
                    new PhysicalShorePlacement(
                            placement.x(),
                            placement.y(),
                            placement.z(),
                            Math.min(existing.level(), placement.level()),
                            Math.max(existing.ttlTicks(), placement.ttlTicks()),
                            false
                    )
            );
        }

        final UUID worldId = world.getUID();
        final Map<Long, List<PhysicalShorePlacement>> chunkBatches = new HashMap<>();
        for (final PhysicalShorePlacement placement : mergedPlacements.values()) {
            if (!isChunkLoaded(world, placement.x(), placement.z())) {
                continue;
            }
            final int chunkX = placement.x() >> 4;
            final int chunkZ = placement.z() >> 4;
            final long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
            chunkBatches.computeIfAbsent(chunkKey, ignored -> new ArrayList<>()).add(placement);
        }

        for (final Map.Entry<Long, List<PhysicalShorePlacement>> entry : chunkBatches.entrySet()) {
            final int chunkX = (int) (entry.getKey() >> 32);
            final int chunkZ = (int) (long) entry.getKey();
            final List<PhysicalShorePlacement> batch = entry.getValue();
            scheduler.runRegion(world, chunkX, chunkZ, () -> {
                if (!isChunkLoaded(world, chunkX << 4, chunkZ << 4)) {
                    return;
                }
                for (final PhysicalShorePlacement placement : batch) {
                    if (!isChunkLoaded(world, placement.x(), placement.z())) {
                        continue;
                    }
                    final Block block = world.getBlockAt(placement.x(), placement.y(), placement.z());
                    final Material current = block.getType();
                    final boolean waterCell = isWaterBodyMaterial(current);
                    if (waterCell) {
                        continue;
                    }
                    if (!isAirLike(current) && !isWaterVegetation(current)) {
                        continue;
                    }

                    final PhysicalCellKey key = new PhysicalCellKey(worldId, placement.x(), placement.y(), placement.z());
                    final Long refractoryUntil = physicalCellRefractoryUntil.get(key);
                    if (refractoryUntil != null && simulationTick < refractoryUntil) {
                        continue;
                    }
                    final PhysicalShoreCell existing = physicalShoreCells.get(key);
                    final String originalData = existing == null
                            ? block.getBlockData().getAsString()
                            : existing.originalBlockData();
                    final long createdTick = existing == null ? simulationTick : existing.createdTick();
                    final long expireTick = simulationTick + placement.ttlTicks();
                    final long cappedExpire = Math.min(
                            Math.max(expireTick, existing == null ? 0L : existing.expireTick()),
                            createdTick + PHYSICAL_CELL_MAX_LIFETIME_TICKS
                    );
                    physicalShoreCells.put(
                            key,
                            new PhysicalShoreCell(originalData, createdTick, cappedExpire)
                    );

                    final int level = clamp(placement.level(), 1, 7);
                    final BlockData targetData = flowingWaterData(level).clone();
                    if (!block.getBlockData().matches(targetData)) {
                        block.setBlockData(targetData, false);
                    }
                }
            });
        }
    }

    private void cleanupExpiredPhysicalShoreCells(final long simulationTick) {
        if (physicalShoreCells.isEmpty()) {
            if (!physicalCellRefractoryUntil.isEmpty()) {
                physicalCellRefractoryUntil.entrySet().removeIf(entry -> entry.getValue() <= simulationTick);
            }
            return;
        }
        if (lastPhysicalCleanupTick != Long.MIN_VALUE
                && (simulationTick - lastPhysicalCleanupTick) < PHYSICAL_CLEANUP_INTERVAL_TICKS) {
            return;
        }
        lastPhysicalCleanupTick = simulationTick;

        final Map<ChunkCacheKey, List<PhysicalCellKey>> expiredByChunk = new HashMap<>();
        for (final Map.Entry<PhysicalCellKey, PhysicalShoreCell> entry : physicalShoreCells.entrySet()) {
            if (entry.getValue().expireTick() > simulationTick) {
                continue;
            }
            final PhysicalCellKey key = entry.getKey();
            final World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                physicalShoreCells.remove(key);
                continue;
            }
            expiredByChunk.computeIfAbsent(
                    new ChunkCacheKey(key.worldId(), key.x() >> 4, key.z() >> 4),
                    ignored -> new ArrayList<>()
            ).add(key);
        }

        for (final Map.Entry<ChunkCacheKey, List<PhysicalCellKey>> entry : expiredByChunk.entrySet()) {
            final ChunkCacheKey chunk = entry.getKey();
            final World world = Bukkit.getWorld(chunk.worldId());
            if (world == null) {
                for (final PhysicalCellKey key : entry.getValue()) {
                    physicalShoreCells.remove(key);
                }
                continue;
            }
            final List<PhysicalCellKey> keys = entry.getValue();
            scheduler.runRegion(world, chunk.chunkX(), chunk.chunkZ(), () -> {
                for (final PhysicalCellKey key : keys) {
                    final PhysicalShoreCell cell = physicalShoreCells.get(key);
                    if (cell == null || cell.expireTick() > simulationTick) {
                        continue;
                    }
                    if (!isChunkLoaded(world, key.x(), key.z())) {
                        continue;
                    }

                    final Block block = world.getBlockAt(key.x(), key.y(), key.z());
                    if (isWaterBodyMaterial(block.getType())
                            || isWaterVegetation(block.getType())
                            || block.getType() == Material.BARRIER) {
                        try {
                            block.setBlockData(Bukkit.createBlockData(cell.originalBlockData()), false);
                        } catch (final IllegalArgumentException ignored) {
                            block.setType(Material.AIR, false);
                        }
                    }
                    physicalShoreCells.remove(key);
                    physicalCellRefractoryUntil.put(key, simulationTick + PHYSICAL_CELL_REFRACTORY_TICKS);
                }
            });
        }

        if (!physicalCellRefractoryUntil.isEmpty()) {
            physicalCellRefractoryUntil.entrySet().removeIf(entry -> entry.getValue() <= simulationTick);
        }
    }

    private void restoreAllPhysicalShoreCells() {
        if (physicalShoreCells.isEmpty()) {
            return;
        }
        final List<Map.Entry<PhysicalCellKey, PhysicalShoreCell>> snapshot = new ArrayList<>(physicalShoreCells.entrySet());
        for (final Map.Entry<PhysicalCellKey, PhysicalShoreCell> entry : snapshot) {
            final PhysicalCellKey key = entry.getKey();
            final PhysicalShoreCell cell = entry.getValue();
            final World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                continue;
            }
            scheduler.runRegion(world, key.x() >> 4, key.z() >> 4, () -> {
                if (!isChunkLoaded(world, key.x(), key.z())) {
                    return;
                }
                final Block block = world.getBlockAt(key.x(), key.y(), key.z());
                if (isWaterBodyMaterial(block.getType())
                        || isWaterVegetation(block.getType())
                        || block.getType() == Material.BARRIER) {
                    try {
                        block.setBlockData(Bukkit.createBlockData(cell.originalBlockData()), false);
                    } catch (final IllegalArgumentException ignored) {
                        block.setType(Material.AIR, false);
                    }
                }
            });
        }
        physicalShoreCells.clear();
        physicalCellRefractoryUntil.clear();
    }

    private void restoreAllPhysicalShoreCellsImmediate() {
        if (physicalShoreCells.isEmpty()) {
            return;
        }
        final List<Map.Entry<PhysicalCellKey, PhysicalShoreCell>> snapshot = new ArrayList<>(physicalShoreCells.entrySet());
        for (final Map.Entry<PhysicalCellKey, PhysicalShoreCell> entry : snapshot) {
            final PhysicalCellKey key = entry.getKey();
            final PhysicalShoreCell cell = entry.getValue();
            final World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                continue;
            }
            if (!isChunkLoaded(world, key.x(), key.z())) {
                continue;
            }
            final Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (isWaterBodyMaterial(block.getType())
                    || isWaterVegetation(block.getType())
                    || block.getType() == Material.BARRIER) {
                try {
                    block.setBlockData(Bukkit.createBlockData(cell.originalBlockData()), false);
                } catch (final IllegalArgumentException ignored) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        physicalShoreCells.clear();
        physicalCellRefractoryUntil.clear();
    }

    private void spawnShoreImpactParticles(
            final Player player,
            final int x,
            final int y,
            final int z,
            final double intensity
    ) {
        final boolean storming = player.getWorld().isThundering();
        final int cloudCount = intensity >= 2.0D ? (storming ? 6 : 4) : (storming ? 3 : 2);
        player.spawnParticle(Particle.CLOUD, x + 0.5D, y + 0.10D, z + 0.5D, cloudCount, 0.28D, 0.08D, 0.28D, 0.005D);
    }

    private void spawnRainRippleParticles(
            final Player player,
            final int x,
            final int y,
            final int z,
            final boolean heavy
    ) {
        final int rainCount = heavy ? 2 : 1;
        player.spawnParticle(Particle.RAIN, x + 0.5D, y + 0.05D, z + 0.5D, rainCount, 0.20D, 0.01D, 0.20D, 0.0D);
    }

    private void spawnStormCrestSprayParticles(
            final Player player,
            final int x,
            final int y,
            final int z,
            final double intensity,
            final double dirX,
            final double dirZ
        ) {
        final double nx = -dirZ;
        final double nz = dirX;
        final int cloudCount = intensity >= 1.8D ? 4 : 3;
        player.spawnParticle(
            Particle.CLOUD,
            x + 0.5D + (nx * 0.20D),
            y + 0.14D,
            z + 0.5D + (nz * 0.20D),
            cloudCount,
            0.24D,
            0.07D,
            0.24D,
            0.006D
        );
    }

    private void spawnSeaFoamParticles(
            final Player player,
            final int x,
            final int y,
            final int z,
            final double intensity,
            final double dirX,
            final double dirZ
    ) {
        final int cloudCount = intensity >= 1.4D ? 6 : 3;
        final double nx = -dirZ;
        final double nz = dirX;
        player.spawnParticle(
            Particle.CLOUD,
            x + 0.5D + (nx * 0.28D),
            y + 0.01D,
            z + 0.5D + (nz * 0.28D),
            cloudCount,
            0.20D,
            0.010D,
            0.20D,
            0.004D
        );
    }

    private boolean shouldSpawnSeaFoam(
            final SurfaceColumn column,
            final WaveSample sample,
            final long simulationTick,
            final double edgeBlend,
            final int distSq,
            final int radiusSq,
            final double crestFront
    ) {
        if (sample.visualBand() <= 0
            || sample.intensity() < 0.75D
                || edgeBlend < 0.14D
                || sample.sporadicGate() < 0.35D
            || crestFront < 0.30D) {
            return false;
        }
        final double normalizedDistance = Math.sqrt(distSq / Math.max(1.0D, (double) radiusSq));
        final double centerWeight = 1.0D - clamp(normalizedDistance, 0.0D, 1.0D);
        final double threshold = 0.40D - (centerWeight * 0.14D);
        return seaFoamNoise(column.x(), column.z(), simulationTick) >= threshold;
    }

    private boolean shouldSpawnRainRipple(
            final SurfaceColumn column,
            final WaveSample sample,
            final long simulationTick,
            final double edgeBlend,
            final int distSq,
            final int radiusSq,
            final boolean heavy
    ) {
        if (edgeBlend < 0.25D || sample.shorelineFactor() < 0.20D) {
            return false;
        }
        final double normalizedDistance = Math.sqrt(distSq / Math.max(1.0D, (double) radiusSq));
        final double centerWeight = 1.0D - clamp(normalizedDistance, 0.0D, 1.0D);
        final double baseThreshold = heavy ? 0.58D : 0.66D;
        final double threshold = baseThreshold - (centerWeight * 0.12D);
        return seaFoamNoise(column.x() + 19, column.z() - 31, simulationTick / (heavy ? 2L : 3L)) >= threshold;
    }

    private boolean shouldSpawnStormSpray(
            final SurfaceColumn column,
            final WaveSample sample,
            final long simulationTick,
            final double edgeBlend,
            final int distSq,
            final int radiusSq,
            final double crestFront
    ) {
        if (sample.visualBand() <= 0
                || sample.intensity() < 1.22D
                || edgeBlend < 0.30D
                || crestFront < 0.60D) {
            return false;
        }
        final double normalizedDistance = Math.sqrt(distSq / Math.max(1.0D, (double) radiusSq));
        final double centerWeight = 1.0D - clamp(normalizedDistance, 0.0D, 1.0D);
        final double threshold = 0.64D - (centerWeight * 0.10D);
        return seaFoamNoise(column.x() - 7, column.z() + 13, simulationTick / 2L) >= threshold;
    }

    private double seaFoamNoise(final int x, final int z, final long simulationTick) {
        long n = 1469598103934665603L;
        n ^= (long) x * 0x9E3779B97F4A7C15L;
        n *= 1099511628211L;
        n ^= (long) z * 0xC2B2AE3D27D4EB4FL;
        n *= 1099511628211L;
        n ^= (simulationTick / 3L) * 0x165667B19E3779F9L;
        n *= 1099511628211L;
        final long bits = (n >>> 11) & ((1L << 53) - 1);
        return bits / (double) (1L << 53);
    }

    private int findShoreGroundY(final World world, final int x, final int nearSurfaceY, final int z) {
        if (!isChunkLoaded(world, x, z)) {
            return Integer.MIN_VALUE;
        }
        final int minY = Math.max(world.getMinHeight(), nearSurfaceY - 3);
        final int maxY = Math.min(world.getMaxHeight() - 2, nearSurfaceY + 3);
        for (int y = maxY; y >= minY; y--) {
            final Material material = world.getBlockAt(x, y, z).getType();
            if (material.isSolid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private int chunkAnchor(final int blockCoord) {
        return ((blockCoord >> 4) << 4) + 8;
    }

    private int distanceSquared(final int x1, final int z1, final int x2, final int z2) {
        final int dx = x1 - x2;
        final int dz = z1 - z2;
        return (dx * dx) + (dz * dz);
    }

    private double renderEdgeBlend(final int distSq, final int radius) {
        final int fade = Math.max(6, RENDER_EDGE_BLEND_BLOCKS);
        final int inner = Math.max(0, radius - fade);
        final int innerSq = inner * inner;
        if (distSq <= innerSq) {
            return 1.0D;
        }
        final double dist = Math.sqrt(distSq);
        if (dist >= radius) {
            return 0.0D;
        }
        final double t = clamp((dist - inner) / Math.max(1.0D, (double) (radius - inner)), 0.0D, 1.0D);
        return 1.0D - (t * t * (3.0D - (2.0D * t)));
    }

    private long columnKey(final int x, final int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    private double smoothVisualHeight(
            final WaveProfile profile,
            final double targetHeight,
            final double previousHeight
    ) {
        final double speedFactor = clamp(profile.speed() / 1.8D, 0.0D, 1.0D);
        final double delta = targetHeight - previousHeight;
        final double riseAlpha = 0.22D + (speedFactor * 0.10D);
        final double fallAlpha = 0.14D + (speedFactor * 0.06D);
        final double alpha = delta >= 0.0D ? riseAlpha : fallAlpha;
        final double maxRiseStep = 0.16D + (speedFactor * 0.08D);
        final double maxFallStep = 0.12D + (speedFactor * 0.06D);
        double clampedDelta = clamp(delta, -maxFallStep, maxRiseStep);
        if (delta > 0.0D && previousHeight < 0.05D && targetHeight < 0.20D) {
            clampedDelta = Math.min(clampedDelta, 0.06D + (speedFactor * 0.03D));
        }
        final double smoothed = previousHeight + (clampedDelta * alpha);
        if (smoothed < 0.012D && targetHeight < 0.015D) {
            return 0.0D;
        }
        return smoothed;
    }

        private double crestFrontFactor(
            final WaveProfile profile,
            final SurfaceColumn column,
            final WaveSample center,
            final long simulationTick,
            final double dirX,
            final double dirZ
        ) {
        final double shore = waveModel.shorelineFactor(column.waterDepth());
        final double spacing = 1.05D;
        final WaveSample behind = waveModel.sample(
            profile,
            column.x() + 0.5D - (dirX * spacing),
            column.z() + 0.5D - (dirZ * spacing),
            simulationTick,
            shore
        );
        final WaveSample previous = waveModel.sample(
            profile,
            column.x() + 0.5D,
            column.z() + 0.5D,
            simulationTick - 2L,
            shore
        );

        final double along = center.rawHeight() - behind.rawHeight();
        final double temporal = center.rawHeight() - previous.rawHeight();
        final double gate = center.sporadicGate();
        final double combined = (along * 1.45D) + (temporal * 0.85D) + ((gate - 0.5D) * 0.24D);
        return clamp((combined + 0.28D) / 0.78D, 0.0D, 1.0D);
        }

    private double computeCoherentVisualHeight(
            final WaveProfile profile,
            final WaveSample centerSample,
            final SurfaceColumn column,
            final long simulationTick,
            final double edgeBlend,
            final double visualCap,
            final double driftAngle,
            final ShoreDirectionBias shoreBias
    ) {
        final double center = Math.max(
                0.0D,
                waveModel.effectiveVisualHeight(profile, centerSample.rawHeight(), visualCap) * edgeBlend
        );
        if (center <= 0.0D) {
            return 0.0D;
        }

        final double[] direction = blendDriftWithShoreDirection(driftAngle, shoreBias);
        final double dirX = direction[0];
        final double dirZ = direction[1];
        final double spacing = 1.15D + clamp(profile.wavelength() / 38.0D, 0.0D, 1.75D);
        final double shore = waveModel.shorelineFactor(column.waterDepth());

        final WaveSample leadSample = waveModel.sample(
                profile,
                column.x() + 0.5D + (dirX * spacing),
                column.z() + 0.5D + (dirZ * spacing),
                simulationTick,
                shore
        );
        final WaveSample trailSample = waveModel.sample(
                profile,
                column.x() + 0.5D - (dirX * spacing),
                column.z() + 0.5D - (dirZ * spacing),
                simulationTick,
                shore
        );

        final double lead = Math.max(0.0D, waveModel.effectiveVisualHeight(profile, leadSample.rawHeight(), visualCap));
        final double trail = Math.max(0.0D, waveModel.effectiveVisualHeight(profile, trailSample.rawHeight(), visualCap));
        final double neighborAverage = (lead + trail) * 0.5D;

        double coherent = (center * 0.58D) + (neighborAverage * 0.42D);
        if (center > (neighborAverage * 1.45D) && centerSample.intensity() > 1.1D) {
            coherent *= 0.86D;
        } else if (neighborAverage > (center * 1.15D) && centerSample.intensity() > 0.9D) {
            coherent *= 1.05D;
        }

        final double ridgeCohesion = clamp(
                (lead + trail) / Math.max(0.02D, lead + trail + center), 0.65D, 1.20D);
        coherent *= ridgeCohesion;

        final double gateCoherence = clamp(
                (centerSample.sporadicGate()
                        + leadSample.sporadicGate()
                        + trailSample.sporadicGate()) / 3.0D,
                0.66D,
                1.0D
        );
        return clamp(coherent * gateCoherence * edgeBlend, 0.0D, visualCap);
    }

    private ShoreDirectionBias resolveShoreDirectionBias(
            final World world, final SurfaceColumn column, final long simulationTick) {
        if (column.waterDepth() > 8) {
            return ShoreDirectionBias.NONE;
        }
        final ShoreColumnKey key = new ShoreColumnKey(world.getUID(), column.x(), column.z());
        final CachedShoreDirection cached = shoreDirectionCache.get(key);
        if (cached != null && (simulationTick - cached.cachedTick()) <= SHORE_DIRECTION_CACHE_TTL_TICKS) {
            return cached.bias();
        }
        final List<ShoreDirectionHit> hits = collectShorelineHits(world, column, 14);
        if (hits.isEmpty()) {
            shoreDirectionCache.put(key, new CachedShoreDirection(ShoreDirectionBias.NONE, simulationTick));
            return ShoreDirectionBias.NONE;
        }
        final ShoreDirectionHit nearest = hits.get(0);
        final int[] direction = nearest.direction();
        final double len = Math.sqrt((direction[0] * direction[0]) + (direction[1] * direction[1]));
        if (len < 0.5D) {
            shoreDirectionCache.put(key, new CachedShoreDirection(ShoreDirectionBias.NONE, simulationTick));
            return ShoreDirectionBias.NONE;
        }
        final double nx = direction[0] / len;
        final double nz = direction[1] / len;
        final double shorelineNearness = 1.0D - clamp((nearest.shorelineStart() - 1.0D) / 26.0D, 0.0D, 1.0D);
        final double depthBias = 1.0D - clamp((column.waterDepth() - 2.0D) / 10.0D, 0.0D, 1.0D);
        final double bias = clamp((shorelineNearness * 0.70D) + (depthBias * 0.30D), 0.0D, 1.0D);
        final ShoreDirectionBias result = new ShoreDirectionBias(nx, nz, bias, nearest.shorelineStart());
        shoreDirectionCache.put(key, new CachedShoreDirection(result, simulationTick));
        return result;
    }

    private double shorelineLifecycleEnvelope(
            final ShoreDirectionBias shoreBias,
            final WaveProfile profile,
            final long simulationTick,
            final int waterDepth
    ) {
        if (shoreBias.shorelineDistance() == Integer.MAX_VALUE) {
            final double deep = clamp((waterDepth - 2.0D) / 10.0D, 0.0D, 1.0D);
            return 0.55D + (deep * 0.20D);
        }
        final int distance = clamp(shoreBias.shorelineDistance(), 1, 32);

        final double shoal = 1.0D - smoothStep(4.0D, 24.0D, distance);
        final double collapse = smoothStep(1.0D, 4.0D, (double) distance);

        final double speedFactor = clamp(profile.speed() / 2.6D, 0.18D, 1.0D);
        final double frequencyFactor = clamp(profile.frequency() / 2.0D, 0.20D, 1.0D);
        final double phase = (distance * 0.35D) - ((simulationTick / 20.0D) * (0.72D + (speedFactor * 1.10D)));
        final double train = smoothStep(0.30D, 0.85D, 0.5D + (0.5D * Math.sin(phase)));

        final double base = 0.44D + (shoal * 0.46D);
        final double shaped = base * collapse * (0.52D + (0.48D * train));
        final double floor = 0.26D + (shoreBias.bias() * 0.10D) + (frequencyFactor * 0.04D);
        return clamp(Math.max(floor, shaped), 0.0D, 1.0D);
    }

    private double crestSetSpacingFactor(
            final WaveProfile profile,
            final SurfaceColumn column,
            final long simulationTick,
            final double dirX,
            final double dirZ,
            final ShoreDirectionBias shoreBias
    ) {
        final double along = ((column.x() + 0.5D) * dirX) + ((column.z() + 0.5D) * dirZ);
        final double shoreCompression = 1.0D - (shoreBias.bias() * 0.18D);
        final double spacingBlocks = Math.max(18.0D, profile.wavelength() * (2.10D * shoreCompression));
        final double period = 52.0D + (profile.wavelength() * 1.9D);
        final double t = simulationTick / period;
        final double phase = ((along / spacingBlocks) - t) * (Math.PI * 2.0D);
        final double line = 0.5D + (0.5D * Math.sin(phase));
        final double mainBand = smoothStep(0.74D, 0.98D, line);
        final double shoulder = smoothStep(0.58D, 0.78D, line) * 0.16D;
        return clamp((mainBand * 0.84D) + shoulder, 0.48D, 1.0D);
    }

    private int dampStepTransition(final int previousStep, final int targetStep, final double smoothedHeight) {
        if (previousStep == 0 && targetStep > 0 && smoothedHeight < 0.08D) {
            return 0;
        }
        if (targetStep > previousStep + 1) {
            return previousStep + 1;
        }
        if (targetStep < previousStep - 1) {
            return previousStep - 1;
        }
        if (previousStep > 0 && targetStep == 0 && smoothedHeight > 0.07D) {
            return 1;
        }
        return targetStep;
    }

    private double[] blendDriftWithShoreDirection(final double driftAngle, final ShoreDirectionBias shoreBias) {
        final double baseX = Math.cos(driftAngle);
        final double baseZ = Math.sin(driftAngle);
        if (shoreBias.bias() <= 0.01D) {
            return new double[] {baseX, baseZ};
        }

        final double dot = (baseX * shoreBias.x()) + (baseZ * shoreBias.z());
        final double alongX = shoreBias.x() * Math.max(0.0D, dot);
        final double alongZ = shoreBias.z() * Math.max(0.0D, dot);
        final double perpX = baseX - (shoreBias.x() * dot);
        final double perpZ = baseZ - (shoreBias.z() * dot);

        final double shoreWeight = 0.58D + (shoreBias.bias() * 0.38D);
        final double retainedPerp = 0.18D + ((1.0D - shoreBias.bias()) * 0.22D);
        final double driftX = alongX + (perpX * retainedPerp);
        final double driftZ = alongZ + (perpZ * retainedPerp);
        final double x = (driftX * (1.0D - shoreWeight)) + (shoreBias.x() * shoreWeight);
        final double z = (driftZ * (1.0D - shoreWeight)) + (shoreBias.z() * shoreWeight);
        final double len = Math.sqrt((x * x) + (z * z));
        if (len < 1.0E-6D) {
            return new double[] {baseX, baseZ};
        }
        return new double[] {x / len, z / len};
    }

    private double waveDriftAngle(final World world, final WaveProfile profile, final long simulationTick) {
        final double t = simulationTick / 20.0D;
        final long seed = world.getUID().getMostSignificantBits() ^ world.getUID().getLeastSignificantBits();
        final double baseAngle = ((seed & 0xFFFFL) / 65535.0D) * (Math.PI * 2.0D);
        final double phaseA = (((seed >>> 16) & 0xFFFFL) / 65535.0D) * (Math.PI * 2.0D);
        final double phaseB = (((seed >>> 32) & 0xFFFFL) / 65535.0D) * (Math.PI * 2.0D);

        final double meander = (Math.sin((t * 0.0017D) + phaseA) * 0.22D)
                + (Math.cos((t * 0.0011D) + phaseB) * 0.12D);
        final double speedBias = clamp((profile.speed() - 0.75D) / 2.6D, 0.0D, 1.0D) * 0.06D;
        return baseAngle + meander + speedBias;
    }

    private double computeVisualCap(final WaveProfile profile) {
        final double amplitudeScale = clamp((profile.amplitude() - 0.20D) / 1.45D, 0.0D, 1.30D);
        final double speedScale = clamp((profile.speed() - 0.75D) / 2.5D, 0.0D, 1.0D);
        final double frequencyScale = clamp((profile.frequency() - 0.90D) / 1.70D, 0.0D, 1.15D);
        final double cap = 1.06D + (amplitudeScale * 1.55D) + (speedScale * 0.20D) - (frequencyScale * 0.10D);
        return clamp(cap, 1.08D, 3.40D);
    }

    private int classifyVisualSteps(final WaveProfile profile, final double visualHeight, final int previousStep) {
        final double stormScale = clamp(
                (((profile.amplitude() - 0.20D) / 1.45D) * 0.65D) + (((profile.speed() - 0.75D) / 2.5D) * 0.35D),
                0.0D,
                1.20D
        );
        final double chopScale = clamp((profile.frequency() - 0.90D) / 1.60D, 0.0D, 1.10D);
        final double step1Enter = 0.14D + (stormScale * 0.01D);
        final double step1Exit = 0.04D;
        final double step2Enter = 0.38D + (stormScale * 0.04D) + (chopScale * 0.02D);
        final double step2Exit = 0.18D + (stormScale * 0.03D) + (chopScale * 0.01D);
        final double step3Enter = 0.66D + (stormScale * 0.08D) + (chopScale * 0.03D);
        final double step3Exit = 0.42D + (stormScale * 0.06D) + (chopScale * 0.02D);

        if (previousStep >= 3) {
            if (visualHeight >= step3Exit) {
                return 3;
            }
            if (visualHeight >= step2Exit) {
                return 2;
            }
            return visualHeight >= step1Exit ? 1 : 0;
        }
        if (previousStep == 2) {
            if (visualHeight >= step3Enter) {
                return 3;
            }
            if (visualHeight >= step2Exit) {
                return 2;
            }
            return visualHeight >= step1Exit ? 1 : 0;
        }
        if (previousStep == 1) {
            if (visualHeight >= step3Enter) {
                return 3;
            }
            if (visualHeight >= step2Enter) {
                return 2;
            }
            return visualHeight >= step1Exit ? 1 : 0;
        }
        if (visualHeight >= step3Enter) {
            return 3;
        }
        if (visualHeight >= step2Enter) {
            return 2;
        }
        return visualHeight >= step1Enter ? 1 : 0;
    }

    private double crestCoverageGate(final WaveProfile profile, final WaveSample sample) {
        final double stormScale = clamp(
                (((profile.amplitude() - 0.20D) / 1.45D) * 0.65D) + (((profile.speed() - 0.75D) / 2.5D) * 0.35D),
                0.0D,
                1.20D
        );
        final double occurrence = clamp(profile.occurrence(), 0.0D, 1.0D);
        final double entry = 0.36D + (stormScale * 0.04D) + (occurrence * 0.03D);
        final double exit = entry - 0.22D;
        final double gate = smoothStep(exit, entry, sample.sporadicGate());
        final double intensityScale = smoothStep(
            0.40D + (stormScale * 0.06D),
            1.02D + (stormScale * 0.10D),
                sample.intensity()
        );
        final double floor = 0.20D + (occurrence * 0.08D);
        return clamp(Math.max(floor, gate) * (0.78D + (0.22D * intensityScale)), 0.0D, 1.0D);
    }

    private double smoothStep(final double edge0, final double edge1, final double value) {
        if (edge1 <= edge0) {
            return value >= edge0 ? 1.0D : 0.0D;
        }
        final double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - (2.0D * t));
    }

    private BlockData flowingWaterData(final int level) {
        final int waterLevel = clamp(level, 1, 7);
        return flowingWaterDataCache.computeIfAbsent(waterLevel, ignored -> {
            final Levelled data = (Levelled) Bukkit.createBlockData(Material.WATER);
            data.setLevel(waterLevel);
            return data;
        });
    }

    private String flowingWaterVisualData(final int level) {
        final int waterLevel = clamp(level, 1, 7);
        return flowingWaterVisualDataStringCache.computeIfAbsent(
                waterLevel,
                ignored -> flowingWaterData(waterLevel).getAsString()
        );
    }

    private String crestTopVisualWaterData(
            final double smoothedHeight,
            final int steps,
            final double visualCap
    ) {
        if (steps <= 1) {
            final double oneStepFill = clamp(smoothedHeight / Math.max(0.35D, visualCap * 0.92D), 0.0D, 1.0D);
            if (oneStepFill >= 0.985D) {
                return null;
            }
            final double curved = Math.pow(oneStepFill, 0.72D);
            final int idx = clamp((int) Math.round(curved * (CREST_FLOW_LEVELS.length - 1)), 0, CREST_FLOW_LEVELS.length - 1);
            final int oneStepLevel = CREST_FLOW_LEVELS[idx];
            return flowingWaterVisualData(oneStepLevel);
        }
        final double topFill = clamp(smoothedHeight - (steps - 1), 0.0D, 1.0D);
        final double globalFill = clamp(smoothedHeight / Math.max(0.01D, visualCap), 0.0D, 1.0D);
        final double combinedFill = (topFill * 0.72D) + (globalFill * 0.28D);
        if (combinedFill >= 0.94D) {
            return null;
        }
        final int flowingLevel = clamp(7 - (int) Math.round(combinedFill * 6.0D), 1, 7);
        return flowingWaterVisualData(flowingLevel);
    }

    private boolean isVisualReplaceable(final Material material) {
        return isAirLike(material) || isWaterVegetation(material);
    }

    private boolean isRunupReplaceable(final Material material) {
        return isAirLike(material) || isWaterVegetation(material) || isWaterBodyMaterial(material);
    }

    private boolean isChunkLoaded(final World world, final int blockX, final int blockZ) {
        return world.isChunkLoaded(blockX >> 4, blockZ >> 4);
    }

    private void cleanupStaleChunkCache(final long simulationTick) {
        if (lastChunkCacheCleanupTick != Long.MIN_VALUE && (simulationTick - lastChunkCacheCleanupTick) < 200L) {
            return;
        }
        lastChunkCacheCleanupTick = simulationTick;
        if (!chunkSurfaceCache.isEmpty()) {
            final long maxAge = CHUNK_CACHE_TTL_TICKS * 2L;
            chunkSurfaceCache.entrySet().removeIf(entry -> (simulationTick - entry.getValue().tick()) > maxAge);
        }
        if (!shoreDirectionCache.isEmpty()) {
            final long maxAge = SHORE_DIRECTION_CACHE_TTL_TICKS + 400L;
            shoreDirectionCache.entrySet().removeIf(entry -> (simulationTick - entry.getValue().cachedTick()) > maxAge);
        }
    }

    private void invalidateChunkCache(final World world, final int chunkX, final int chunkZ) {
        final UUID worldId = world.getUID();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunkSurfaceCache.remove(new ChunkCacheKey(worldId, chunkX + dx, chunkZ + dz));
            }
        }
    }

    private boolean isWaterBodyMaterial(final Material material) {
        return material == Material.WATER || material == Material.BUBBLE_COLUMN;
    }

    private boolean isWaterVegetation(final Material material) {
        return material == Material.KELP
                || material == Material.KELP_PLANT
                || material == Material.SEAGRASS
                || material == Material.TALL_SEAGRASS;
    }

    private boolean isSurfaceOpenAbove(final World world, final int x, final int y, final int z) {
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

    private boolean isOceanWaveArea(
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
                if (!isChunkLoaded(world, x + dx, z + dz)) {
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
                if (!isChunkLoaded(world, cx, cz)) {
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

    private int resolveSurfaceWaterY(final World world, final int x, final int y, final int z, final int floor) {
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

    private double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ShoreRunupState {
        private final UUID worldId;
        private final List<RunupNode> path;
        private final int waterSurfaceY;
        private final int peakLayers;
        private final double intensity;
        private final long startTick;
        private final long lastTriggerTick;
        private boolean impactParticleSent;

        private ShoreRunupState(
                final UUID worldId,
                final List<RunupNode> path,
                final int waterSurfaceY,
                final int peakLayers,
                final double intensity,
                final long startTick,
                final boolean impactParticleSent
        ) {
            this.worldId = worldId;
            this.path = List.copyOf(path);
            this.waterSurfaceY = waterSurfaceY;
            this.peakLayers = peakLayers;
            this.intensity = intensity;
            this.startTick = startTick;
            this.lastTriggerTick = startTick;
            this.impactParticleSent = impactParticleSent;
        }
    }

    private record PhysicalShorePlacement(int x, int y, int z, int level, int ttlTicks, boolean allowWaterOverride) {
    }

    private record PhysicalCellKey(UUID worldId, int x, int y, int z) {
    }

    private record PhysicalShoreCell(String originalBlockData, long createdTick, long expireTick) {
    }

    private record RunupNode(int x, int z, int baseY) {
    }

    private record ShoreDirectionHit(int[] direction, int shorelineStart) {
    }

    private record ShoreDirectionBias(double x, double z, double bias, int shorelineDistance) {
        private static final ShoreDirectionBias NONE = new ShoreDirectionBias(0.0D, 0.0D, 0.0D, Integer.MAX_VALUE);
    }

    private record ChunkCacheKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record ChunkSurfaceCache(long tick, List<SurfaceColumn> columns) {
    }

    private record ShoreColumnKey(UUID worldId, int x, int z) {
    }

    private record CachedShoreDirection(ShoreDirectionBias bias, long cachedTick) {
    }
}
