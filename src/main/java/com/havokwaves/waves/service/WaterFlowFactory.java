package com.havokwaves.waves.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

final class WaterFlowFactory {
    private static final int[] CREST_FLOW_LEVELS = {7, 6, 5, 4, 3, 2, 1};

    private final Map<Integer, BlockData> dataCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> visualStringCache = new ConcurrentHashMap<>();

    BlockData flowingWaterData(final int level) {
        final int waterLevel = clamp(level, 1, 7);
        return dataCache.computeIfAbsent(waterLevel, ignored -> {
            final Levelled data = (Levelled) Bukkit.createBlockData(Material.WATER);
            data.setLevel(waterLevel);
            return data;
        });
    }

    String flowingWaterVisualData(final int level) {
        final int waterLevel = clamp(level, 1, 7);
        return visualStringCache.computeIfAbsent(
                waterLevel,
                ignored -> flowingWaterData(waterLevel).getAsString()
        );
    }

    String crestTopVisualWaterData(
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

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }
}
