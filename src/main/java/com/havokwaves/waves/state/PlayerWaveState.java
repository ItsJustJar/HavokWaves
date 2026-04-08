package com.havokwaves.waves.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;

public final class PlayerWaveState {
    private final Map<Long, FakeBlockState> activeFakeBlocks = new LinkedHashMap<>();
    private final Map<Long, Long> stickyVisualUntilTick = new HashMap<>();
    private final Map<Long, Double> smoothedColumnHeights = new HashMap<>();
    private final Map<Long, Integer> columnVisualSteps = new HashMap<>();
    private final Map<Long, Long> columnLastSeenTick = new HashMap<>();
    private List<SurfaceColumn> surfaceColumns = List.of();
    private boolean dirty = true;
    private boolean scanInProgress = false;
    private int lastCenterX = Integer.MIN_VALUE;
    private int lastCenterZ = Integer.MIN_VALUE;
    private long lastScanTick = 0L;

    public synchronized boolean shouldRequestScan(
            final Location location,
            final int simulationRadius,
            final int renderRadius,
            final long tick
    ) {
        if (dirty || surfaceColumns.isEmpty()) {
            return true;
        }
        if ((tick - lastScanTick) >= 120L) {
            return true;
        }
        final int centerX = location.getBlockX();
        final int centerZ = location.getBlockZ();
        final int dx = centerX - lastCenterX;
        final int dz = centerZ - lastCenterZ;
        final int margin = Math.max(4, simulationRadius - renderRadius);
        final int trigger = Math.max(6, Math.min(18, (margin / 2) + 2));
        return (dx * dx) + (dz * dz) >= (trigger * trigger);
    }

    public synchronized boolean beginScan() {
        if (scanInProgress) {
            return false;
        }
        scanInProgress = true;
        return true;
    }

    public synchronized void completeScan(
            final int centerX,
            final int centerZ,
            final long tick,
            final List<SurfaceColumn> columns
    ) {
        this.surfaceColumns = List.copyOf(columns);
        this.lastCenterX = centerX;
        this.lastCenterZ = centerZ;
        this.lastScanTick = tick;
        this.scanInProgress = false;
        this.dirty = false;
    }

    public synchronized void failScan() {
        this.scanInProgress = false;
        this.dirty = true;
    }

    public synchronized void markDirty() {
        this.dirty = true;
    }

    public synchronized boolean isScanInProgress() {
        return scanInProgress;
    }

    public synchronized List<SurfaceColumn> getSurfaceColumnsSnapshot() {
        return new ArrayList<>(surfaceColumns);
    }

    public synchronized Map<Long, FakeBlockState> getActiveFakeBlocks() {
        return activeFakeBlocks;
    }

    public synchronized Map<Long, Long> getStickyVisualUntilTick() {
        return stickyVisualUntilTick;
    }

    public synchronized double getSmoothedColumnHeight(final long columnKey) {
        return smoothedColumnHeights.getOrDefault(columnKey, 0.0D);
    }

    public synchronized int getColumnVisualStep(final long columnKey) {
        return columnVisualSteps.getOrDefault(columnKey, 0);
    }

    public synchronized void putColumnVisualState(
            final long columnKey,
            final double smoothedHeight,
            final int step,
            final long tick
    ) {
        smoothedColumnHeights.put(columnKey, smoothedHeight);
        columnVisualSteps.put(columnKey, step);
        columnLastSeenTick.put(columnKey, tick);
    }

    public synchronized void pruneColumnVisualState(final long minSeenTick) {
        final List<Long> stale = new ArrayList<>();
        for (final Map.Entry<Long, Long> entry : columnLastSeenTick.entrySet()) {
            if (entry.getValue() < minSeenTick) {
                stale.add(entry.getKey());
            }
        }
        for (final Long key : stale) {
            columnLastSeenTick.remove(key);
            smoothedColumnHeights.remove(key);
            columnVisualSteps.remove(key);
        }
    }

    public synchronized void clearVisualTracking() {
        smoothedColumnHeights.clear();
        columnVisualSteps.clear();
        columnLastSeenTick.clear();
    }
}
