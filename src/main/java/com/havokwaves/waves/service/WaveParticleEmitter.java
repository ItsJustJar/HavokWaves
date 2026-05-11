package com.havokwaves.waves.service;

import org.bukkit.Particle;
import org.bukkit.entity.Player;

import com.havokwaves.waves.state.SurfaceColumn;
import com.havokwaves.waves.wave.WaveModel.WaveSample;

final class WaveParticleEmitter {
    static final int SEAFOAM_BUDGET_PER_TICK = 28;
    static final int CREST_GLINT_BUDGET_PER_TICK = 8;
    static final int RAIN_RIPPLE_BUDGET_PER_TICK = 10;
    static final int STORM_SPRAY_BUDGET_PER_TICK = 6;

    void spawnShoreImpact(final Player player, final int x, final int y, final int z, final double intensity) {
        final boolean storming = player.getWorld().isThundering();
        final int cloudCount = intensity >= 2.0D ? (storming ? 6 : 4) : (storming ? 3 : 2);
        player.spawnParticle(Particle.CLOUD, x + 0.5D, y + 0.10D, z + 0.5D, cloudCount, 0.28D, 0.08D, 0.28D, 0.005D);
    }

    void spawnRainRipple(final Player player, final int x, final int y, final int z, final boolean heavy) {
        final int rainCount = heavy ? 2 : 1;
        player.spawnParticle(Particle.RAIN, x + 0.5D, y + 0.05D, z + 0.5D, rainCount, 0.20D, 0.01D, 0.20D, 0.0D);
    }

    void spawnStormCrestSpray(
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

    void spawnSeaFoam(
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

    void spawnCrestGlint(
            final Player player,
            final int x,
            final int y,
            final int z,
            final double dirX,
            final double dirZ
    ) {
        // END_ROD reads as the small bright sparkle on the wave's crest tip.
        player.spawnParticle(
                Particle.END_ROD,
                x + 0.5D + (dirX * 0.15D),
                y + 0.18D,
                z + 0.5D + (dirZ * 0.15D),
                1,
                0.14D,
                0.04D,
                0.14D,
                0.004D
        );
    }

    boolean shouldSpawnCrestGlint(
            final WaveSample sample,
            final double edgeBlend,
            final int distSq,
            final int radiusSq,
            final double crestFront
    ) {
        if (sample.visualBand() <= 0
                || sample.intensity() < 0.80D
                || edgeBlend < 0.20D
                || crestFront < 0.55D) {
            return false;
        }
        final double normalizedDistance = Math.sqrt(distSq / Math.max(1.0D, (double) radiusSq));
        return normalizedDistance < 0.75D;
    }

    boolean shouldSpawnSeaFoam(
            final SurfaceColumn column,
            final WaveSample sample,
            final long simulationTick,
            final double edgeBlend,
            final int distSq,
            final int radiusSq,
            final double crestFront
    ) {
        if (sample.visualBand() <= 0
                || sample.intensity() < 0.50D
                || edgeBlend < 0.14D
                || sample.sporadicGate() < 0.20D
                || crestFront < 0.18D) {
            return false;
        }
        final double normalizedDistance = Math.sqrt(distSq / Math.max(1.0D, (double) radiusSq));
        final double centerWeight = 1.0D - clamp01(normalizedDistance);
        final double threshold = 0.40D - (centerWeight * 0.14D);
        return noise(column.x(), column.z(), simulationTick) >= threshold;
    }

    boolean shouldSpawnRainRipple(
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
        final double centerWeight = 1.0D - clamp01(normalizedDistance);
        final double baseThreshold = heavy ? 0.58D : 0.66D;
        final double threshold = baseThreshold - (centerWeight * 0.12D);
        return noise(column.x() + 19, column.z() - 31, simulationTick / (heavy ? 2L : 3L)) >= threshold;
    }

    boolean shouldSpawnStormSpray(
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
        final double centerWeight = 1.0D - clamp01(normalizedDistance);
        final double threshold = 0.64D - (centerWeight * 0.10D);
        return noise(column.x() - 7, column.z() + 13, simulationTick / 2L) >= threshold;
    }

    private double noise(final int x, final int z, final long simulationTick) {
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

    private static double clamp01(final double value) {
        if (value < 0.0D) return 0.0D;
        if (value > 1.0D) return 1.0D;
        return value;
    }
}
