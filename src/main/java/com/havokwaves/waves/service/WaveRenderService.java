package com.havokwaves.waves.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
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

import static com.havokwaves.waves.service.MaterialPredicates.isAirLike;
import static com.havokwaves.waves.service.MaterialPredicates.isRunupReplaceable;
import static com.havokwaves.waves.service.MaterialPredicates.isVisualReplaceable;
import static com.havokwaves.waves.service.MaterialPredicates.isWater;
import static com.havokwaves.waves.service.MaterialPredicates.isWaterBodyMaterial;
import static com.havokwaves.waves.service.MaterialPredicates.isWaterVegetation;
import static com.havokwaves.waves.service.WorldProbes.isChunkLoaded;
import static com.havokwaves.waves.service.WorldProbes.isSurfaceOpenAbove;
import static com.havokwaves.waves.service.WorldProbes.resolveSurfaceWaterY;
import static com.havokwaves.waves.service.WorldProbes.waterDepthAt;

public final class WaveRenderService {
    private static final long VISUAL_STICKY_TICKS = 12L;
    private static final long SHORE_DIRECTION_CACHE_TTL_TICKS = 600L;
    private static final long COLUMN_VISUAL_MEMORY_TICKS = 220L;
    private static final int RENDER_EDGE_BLEND_BLOCKS = 20;
    private static final int MAX_SHORE_DIRECTIONS_PER_COLUMN = 1;
    private static final int MAX_RUNUPS_PER_PLAYER = 192;
    private static final long RUNUP_TRIGGER_COOLDOWN_TICKS = 18L;
    private static final double RUNUP_ADVANCE_TICKS_PER_BLOCK = 4.0D;
    private static final double RUNUP_RETREAT_TICKS_PER_BLOCK = 5.0D;
    private static final int[][] RUNUP_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final ServerScheduler scheduler;
    private final PacketEventsBridge packetBridge;
    private final WaveModel waveModel;
    private final Supplier<PluginConfig> configSupplier;
    private final PlayerToggleService toggleService;
    private final BoatLifter boatLifter;
    private final WaveParticleEmitter particles;
    private final WaterFlowFactory waterFlow;
    private final BiomeFetchSampler biomeFetch;
    private final ChunkSurfaceScanner surfaceScanner;
    private final Map<UUID, PlayerWaveState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, ShoreRunupState>> playerRunups = new ConcurrentHashMap<>();
    private final Map<ShoreColumnKey, CachedShoreDirection> shoreDirectionCache = new ConcurrentHashMap<>();
    private volatile long lastChunkCacheCleanupTick = Long.MIN_VALUE;

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
        this.boatLifter = new BoatLifter(scheduler);
        this.particles = new WaveParticleEmitter();
        this.waterFlow = new WaterFlowFactory();
        this.biomeFetch = new BiomeFetchSampler(configSupplier);
        this.surfaceScanner = new ChunkSurfaceScanner(biomeFetch);
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
    }

    public void markDirty(final Player player) {
        final PlayerWaveState state = playerStates.get(player.getUniqueId());
        if (state != null) {
            state.markDirty();
        }
    }

    public void markDirtyNearChunk(final World world, final int chunkX, final int chunkZ) {
        surfaceScanner.invalidateNear(world, chunkX, chunkZ);
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
        surfaceScanner.clear();
        shoreDirectionCache.clear();
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
        surfaceScanner.clear();
        shoreDirectionCache.clear();
    }

    public void clearAllImmediate() {
        clearAll();
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
            final List<SurfaceColumn> columns = surfaceScanner.scanArea(world, centerX, centerZ, scanRadius, simulationTick);
            state.completeScan(centerX, centerZ, simulationTick, columns);
            return;
        }

        final List<int[]> chunks = surfaceScanner.collectChunks(centerX, centerZ, scanRadius);
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
                collected.addAll(surfaceScanner.scanChunk(world, chunkX, chunkZ, centerX, centerZ, scanRadius, simulationTick));
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
        final Map<Long, Integer> waveTopByXZ = new HashMap<>();
        int seafoamBudget = WaveParticleEmitter.SEAFOAM_BUDGET_PER_TICK;
        int crestGlintBudget = WaveParticleEmitter.CREST_GLINT_BUDGET_PER_TICK;
        int rainRippleBudget = raining ? WaveParticleEmitter.RAIN_RIPPLE_BUDGET_PER_TICK : 0;
        int stormSprayBudget = storming ? WaveParticleEmitter.STORM_SPRAY_BUDGET_PER_TICK : 0;

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
                    waveModel.shorelineFactor(column.waterDepth()),
                    storming,
                    driftAngle
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
                    shoreBias,
                    storming
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
            // Storm: cap at 1 step — prevents stacked block columns that look blocky.
            // Single-step waves with Levelled water data give smooth undulation in rain.
            if (storming) {
                steps = Math.min(1, steps);
            }
            // At the very shore edge, suppress waves completely (distance=1) or cap to 1 (distance 2-3).
            final int shorelineDistance = shoreBias.shorelineDistance();
            if (shorelineDistance <= 1) {
                steps = 0;
            } else if (shorelineDistance <= 3) {
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
                    final String fakeData;
                    if (level == steps) {
                        if (shorelineDistance == 2 || shorelineDistance == 3) {
                            // Near shore: use a shallow Levelled water level so the wave
                            // tapers to a thin wash rather than a full-height block.
                            final int shoreLevel = shorelineDistance == 2 ? 7 : 5;
                            fakeData = waterFlow.flowingWaterVisualData(shoreLevel);
                        } else {
                            fakeData = waterFlow.crestTopVisualWaterData(smoothedHeight, steps, visualCap);
                        }
                    } else {
                        fakeData = null;
                    }
                    desired.put(
                            BlockPosUtil.pack(column.x(), y, column.z()),
                            new FakeBlockState(Material.WATER, restore, fakeData)
                    );
                }

                if (seafoamBudget > 0 || stormSprayBudget > 0 || crestGlintBudget > 0) {
                    final double crestFront = crestFrontFactor(
                            profile, column, sample, simulationTick, waveDirection[0], waveDirection[1], storming);
                    if (seafoamBudget > 0
                            && particles.shouldSpawnSeaFoam(column, sample, simulationTick, edgeBlend, distSq, radiusSq, crestFront)) {
                        particles.spawnSeaFoam(
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
                    if (crestGlintBudget > 0
                            && particles.shouldSpawnCrestGlint(sample, edgeBlend, distSq, radiusSq, crestFront)) {
                        particles.spawnCrestGlint(
                                player,
                                column.x(),
                                column.y() + steps,
                                column.z(),
                                waveDirection[0],
                                waveDirection[1]
                        );
                        crestGlintBudget--;
                    }
                    if (stormSprayBudget > 0
                            && particles.shouldSpawnStormSpray(column, sample, simulationTick, edgeBlend, distSq, radiusSq, crestFront)) {
                        particles.spawnStormCrestSpray(
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
                waveTopByXZ.put(columnKey, column.y() + steps);
            }

            if (rainRippleBudget > 0
                    && particles.shouldSpawnRainRipple(column, sample, simulationTick, edgeBlend, distSq, radiusSq, storming)) {
                particles.spawnRainRipple(player, column.x(), column.y() + 1, column.z(), storming);
                rainRippleBudget--;
            }
        }

        // Remove isolated wave columns — a column with zero elevated neighbors looks like a stray spike.
        // Require at least 1 cardinal neighbor also present in waveTopByXZ before rendering.
        if (waveTopByXZ.size() > 1) {
            final Set<Long> isolatedXZ = new HashSet<>();
            for (final long xzKey : waveTopByXZ.keySet()) {
                final int cx = (int) (xzKey >> 32);
                final int cz = (int) (xzKey);
                if (!waveTopByXZ.containsKey(columnKey(cx + 1, cz))
                        && !waveTopByXZ.containsKey(columnKey(cx - 1, cz))
                        && !waveTopByXZ.containsKey(columnKey(cx, cz + 1))
                        && !waveTopByXZ.containsKey(columnKey(cx, cz - 1))) {
                    isolatedXZ.add(xzKey);
                }
            }
            if (!isolatedXZ.isEmpty()) {
                desired.keySet().removeIf(packed -> {
                    final int px = BlockPosUtil.unpackX(packed);
                    final int pz = BlockPosUtil.unpackZ(packed);
                    return isolatedXZ.contains(columnKey(px, pz));
                });
                waveTopByXZ.keySet().removeAll(isolatedXZ);
            }
        }

        if (!waveTopByXZ.isEmpty()) {
            boatLifter.liftNearby(player, waveTopByXZ, effectiveRenderRadius);
        }
        state.pruneColumnVisualState(simulationTick - COLUMN_VISUAL_MEMORY_TICKS);
        renderActiveShoreRunups(player, simulationTick, desired, seafoamBudget);

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
        if (sample.visualBand() <= 0 || sample.rawHeight() <= 0.0D || sample.intensity() < 1.30D) {
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
            int particleBudget
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
                particles.spawnShoreImpact(player, impact.x(), impact.baseY(), impact.z(), runup.intensity);
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
                final int baseLevel = runupWaterLevel(i, runup.path.size(), globalCollapse);
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
                    // Upper layers thin out to a wash rather than stacking as full cubes.
                    final int level = clamp(baseLevel + (layer * 2), 1, 7);
                    desired.put(
                            BlockPosUtil.pack(node.x(), y, node.z()),
                            new FakeBlockState(Material.WATER, current, waterFlow.flowingWaterVisualData(level))
                    );
                    placedAny = true;
                }

                if (placedAny && i == 0) {
                    final int seaTop = runup.waterSurfaceY + 1;
                    final int physicalY = node.baseY();
                    final int low = Math.min(seaTop, physicalY);
                    final int high = Math.max(seaTop, physicalY);
                    final int connectorLevel = Math.max(1, baseLevel - 1);
                    for (int yy = low; yy <= high; yy++) {
                        if (Math.abs(yy - physicalY) > 2) {
                            continue;
                        }
                        final Material connector = world.getBlockAt(node.x(), yy, node.z()).getType();
                        if (isRunupReplaceable(connector)) {
                            desired.put(
                                    BlockPosUtil.pack(node.x(), yy, node.z()),
                                    new FakeBlockState(Material.WATER, connector, waterFlow.flowingWaterVisualData(connectorLevel))
                            );
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

    private int runupWaterLevel(final int inlandDistance, final int pathSize, final int globalCollapse) {
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
        // Faster fall so waves don't linger as frozen blocks after the crest passes.
        final double fallAlpha = 0.28D + (speedFactor * 0.10D);
        final double alpha = delta >= 0.0D ? riseAlpha : fallAlpha;
        final double maxRiseStep = 0.16D + (speedFactor * 0.08D);
        final double maxFallStep = 0.22D + (speedFactor * 0.08D);
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
            final double dirZ,
            final boolean storming
    ) {
        final double shore = waveModel.shorelineFactor(column.waterDepth());
        final double driftAngle = center.windAngle();
        final double spacing = 1.05D;
        final WaveSample behind = waveModel.sample(
            profile,
            column.x() + 0.5D - (dirX * spacing),
            column.z() + 0.5D - (dirZ * spacing),
            simulationTick,
            shore,
            storming,
            driftAngle
        );
        final WaveSample previous = waveModel.sample(
            profile,
            column.x() + 0.5D,
            column.z() + 0.5D,
            simulationTick - 2L,
            shore,
            storming,
            driftAngle
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
            final ShoreDirectionBias shoreBias,
            final boolean storming
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
                shore,
                storming,
                driftAngle
        );
        final WaveSample trailSample = waveModel.sample(
                profile,
                column.x() + 0.5D - (dirX * spacing),
                column.z() + 0.5D - (dirZ * spacing),
                simulationTick,
                shore,
                storming,
                driftAngle
        );

        final double lead = Math.max(0.0D, waveModel.effectiveVisualHeight(profile, leadSample.rawHeight(), visualCap));
        final double trail = Math.max(0.0D, waveModel.effectiveVisualHeight(profile, trailSample.rawHeight(), visualCap));
        final double neighborAverage = (lead + trail) * 0.5D;

        // In storm mode, lean more on neighbour average to spread out isolated spikes —
        // this prevents single-column high steps that look blocky during rain.
        double coherent = storming
                ? (center * 0.40D) + (neighborAverage * 0.60D)
                : (center * 0.58D) + (neighborAverage * 0.42D);
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

        // At the very edge (distance=1) suppress entirely — waves are already blocked by step clamp above,
        // but returning 0 here prevents any height leaking through other paths.
        if (distance <= 1) {
            return 0.0D;
        }

        final double shoal = 1.0D - smoothStep(4.0D, 24.0D, distance);
        final double collapse = smoothStep(2.0D, 5.0D, (double) distance);

        final double speedFactor = clamp(profile.speed() / 2.6D, 0.18D, 1.0D);
        final double frequencyFactor = clamp(profile.frequency() / 2.0D, 0.20D, 1.0D);
        // Slow the shore pulse rate and reduce amplitude swing — prevents the "bouncy" look.
        // Phase advances slower (0.42 instead of 0.72) and the train modulation is narrower.
        final double phase = (distance * 0.35D) - ((simulationTick / 20.0D) * (0.42D + (speedFactor * 0.55D)));
        final double train = smoothStep(0.30D, 0.85D, 0.5D + (0.5D * Math.sin(phase)));

        final double base = 0.44D + (shoal * 0.46D);
        // Narrower swing (0.78 + 0.22 * train) instead of (0.52 + 0.48 * train) — the shore
        // envelope stays consistently present and fades naturally rather than pulsing on/off.
        final double shaped = base * collapse * (0.78D + (0.22D * train));
        // Reduced floor — less residual wave energy pinning near the shoreline.
        final double floor = 0.06D + (frequencyFactor * 0.03D);
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
        // If the height is negligible, allow immediate drop to zero regardless of previous step.
        if (previousStep > 0 && smoothedHeight < 0.022D) {
            return 0;
        }
        if (previousStep == 0 && targetStep > 0 && smoothedHeight < 0.08D) {
            return 0;
        }
        if (targetStep > previousStep + 1) {
            return previousStep + 1;
        }
        if (targetStep < previousStep - 1) {
            return previousStep - 1;
        }
        // Lower threshold (0.04 vs 0.07) so the fallback to step=1 clears sooner.
        if (previousStep > 0 && targetStep == 0 && smoothedHeight > 0.04D) {
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

    private void cleanupStaleChunkCache(final long simulationTick) {
        if (lastChunkCacheCleanupTick != Long.MIN_VALUE && (simulationTick - lastChunkCacheCleanupTick) < 200L) {
            return;
        }
        lastChunkCacheCleanupTick = simulationTick;
        surfaceScanner.cleanupStale(simulationTick);
        if (!shoreDirectionCache.isEmpty()) {
            final long maxAge = SHORE_DIRECTION_CACHE_TTL_TICKS + 400L;
            shoreDirectionCache.entrySet().removeIf(entry -> (simulationTick - entry.getValue().cachedTick()) > maxAge);
        }
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

    private record RunupNode(int x, int z, int baseY) {
    }

    private record ShoreDirectionHit(int[] direction, int shorelineStart) {
    }

    private record ShoreDirectionBias(double x, double z, double bias, int shorelineDistance) {
        private static final ShoreDirectionBias NONE = new ShoreDirectionBias(0.0D, 0.0D, 0.0D, Integer.MAX_VALUE);
    }

    private record ShoreColumnKey(UUID worldId, int x, int z) {
    }

    private record CachedShoreDirection(ShoreDirectionBias bias, long cachedTick) {
    }
}
