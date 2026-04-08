package com.havokwaves.waves.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class PluginConfig {
    public static final WaveProfile DEFAULT_CLEAR = new WaveProfile(0.38D, 0.82D, 28.0D, 0.72D, 0.55D, 0.62D);
    public static final WaveProfile DEFAULT_STORM = new WaveProfile(1.55D, 2.15D, 22.0D, 1.20D, 0.95D, 0.88D);
    public static final Set<String> DEFAULT_BIOMES_WHITELIST = Set.of(
            "ocean",
            "deep_ocean",
            "warm_ocean",
            "lukewarm_ocean",
            "deep_lukewarm_ocean",
            "cold_ocean",
            "deep_cold_ocean",
            "frozen_ocean",
            "deep_frozen_ocean",
            "river",
            "frozen_river",
            "swamp",
            "mangrove_swamp",
            "beach",
            "snowy_beach",
            "stony_shore"
    );

    private final int simulationRadius;
    private final int renderRadius;
    private final int updateIntervalTicks;
    private final WaveProfile clear;
    private final WaveProfile storm;
    private final int maxBlockUpdatesPerTickPerPlayer;
    private final Set<String> worldsWhitelist;
    private final Set<String> biomesWhitelist;
    private final boolean debug;
    private final Map<String, WorldOverride> worldOverrides;

    private PluginConfig(
            final int simulationRadius,
            final int renderRadius,
            final int updateIntervalTicks,
            final WaveProfile clear,
            final WaveProfile storm,
            final int maxBlockUpdatesPerTickPerPlayer,
            final Set<String> worldsWhitelist,
            final Set<String> biomesWhitelist,
            final boolean debug,
            final Map<String, WorldOverride> worldOverrides
    ) {
        this.simulationRadius = simulationRadius;
        this.renderRadius = renderRadius;
        this.updateIntervalTicks = updateIntervalTicks;
        this.clear = clear;
        this.storm = storm;
        this.maxBlockUpdatesPerTickPerPlayer = maxBlockUpdatesPerTickPerPlayer;
        this.worldsWhitelist = worldsWhitelist;
        this.biomesWhitelist = biomesWhitelist;
        this.debug = debug;
        this.worldOverrides = worldOverrides;
    }

    public static PluginConfig from(final FileConfiguration config) {
        final int simulationRadius = Math.max(4, config.getInt("simulation-radius", 56));
        final int renderRadius = Math.max(4, config.getInt("render-radius", 40));
        final int updateIntervalTicks = Math.max(1, config.getInt("update-interval-ticks", 2));
        final WaveProfile clear = readProfile(config, "clear", DEFAULT_CLEAR);
        final WaveProfile storm = readProfile(config, "storm", DEFAULT_STORM);

        final int maxBlockUpdates = Math.max(50, config.getInt("max-block-updates-per-tick-per-player", 900));
        final boolean debug = config.getBoolean("debug", false);

        final Set<String> whitelist = new HashSet<>();
        for (final String worldName : config.getStringList("worlds-whitelist")) {
            if (worldName != null && !worldName.isBlank()) {
                whitelist.add(normalize(worldName));
            }
        }

        final Set<String> biomesWhitelist = new HashSet<>();
        for (final String biomeId : config.getStringList("biomes-whitelist")) {
            if (biomeId != null && !biomeId.isBlank()) {
                biomesWhitelist.add(normalizeBiomeId(biomeId));
            }
        }
        if (biomesWhitelist.isEmpty()) {
            biomesWhitelist.addAll(DEFAULT_BIOMES_WHITELIST);
        }

        final Map<String, WorldOverride> overrides = new HashMap<>();
        final ConfigurationSection overrideSection = config.getConfigurationSection("world-overrides");
        if (overrideSection != null) {
            for (final String worldName : overrideSection.getKeys(false)) {
                final ConfigurationSection worldSection = overrideSection.getConfigurationSection(worldName);
                if (worldSection == null) {
                    continue;
                }

                final WaveProfile worldClear = readProfile(worldSection, "clear", clear);
                final WaveProfile worldStorm = readProfile(worldSection, "storm", storm);

                final Integer worldSimulationRadius = worldSection.contains("simulation-radius")
                        ? Math.max(4, worldSection.getInt("simulation-radius"))
                        : null;
                final Integer worldRenderRadius = worldSection.contains("render-radius")
                        ? Math.max(4, worldSection.getInt("render-radius"))
                        : null;

                overrides.put(
                        normalize(worldName),
                        new WorldOverride(worldClear, worldStorm, worldSimulationRadius, worldRenderRadius)
                );
            }
        }

        return new PluginConfig(
                simulationRadius,
                renderRadius,
                updateIntervalTicks,
                clear,
                storm,
                maxBlockUpdates,
                Collections.unmodifiableSet(whitelist),
                Collections.unmodifiableSet(biomesWhitelist),
                debug,
                Collections.unmodifiableMap(overrides)
        );
    }

    public boolean isWorldAllowed(final String worldName) {
        if (worldsWhitelist.isEmpty()) {
            return true;
        }
        return worldsWhitelist.contains(normalize(worldName));
    }

    public WaveProfile resolveWaveProfile(final World world) {
        final WorldOverride worldOverride = worldOverrides.get(normalize(world.getName()));
        final boolean storming = world.hasStorm() || world.isThundering();
        if (worldOverride != null) {
            return storming ? worldOverride.storm() : worldOverride.clear();
        }
        return storming ? storm : clear;
    }

    public int resolveSimulationRadius(final World world) {
        final WorldOverride worldOverride = worldOverrides.get(normalize(world.getName()));
        if (worldOverride != null && worldOverride.simulationRadius() != null) {
            return worldOverride.simulationRadius();
        }
        return simulationRadius;
    }

    public int resolveRenderRadius(final World world) {
        final WorldOverride worldOverride = worldOverrides.get(normalize(world.getName()));
        if (worldOverride != null && worldOverride.renderRadius() != null) {
            return worldOverride.renderRadius();
        }
        return renderRadius;
    }

    public int simulationRadius() {
        return simulationRadius;
    }

    public int renderRadius() {
        return renderRadius;
    }

    public int updateIntervalTicks() {
        return updateIntervalTicks;
    }

    public WaveProfile clear() {
        return clear;
    }

    public WaveProfile storm() {
        return storm;
    }

    public int maxBlockUpdatesPerTickPerPlayer() {
        return maxBlockUpdatesPerTickPerPlayer;
    }

    public Set<String> worldsWhitelist() {
        return worldsWhitelist;
    }

    public Set<String> biomesWhitelist() {
        return biomesWhitelist;
    }

    public boolean isBiomeAllowed(final Biome biome) {
        final String id = normalizeBiomeId(biome.getKey().getKey());
        return biomesWhitelist.contains(id);
    }

    public boolean debug() {
        return debug;
    }

    private static WaveProfile readProfile(
            final ConfigurationSection root,
            final String path,
            final WaveProfile fallback
    ) {
        final ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }
        final double amplitude = Math.max(0.01D, section.getDouble("amplitude", fallback.amplitude()));
        final double speed = Math.max(0.01D, section.getDouble("speed", fallback.speed()));
        final double wavelength = Math.max(1.0D, section.getDouble("wavelength", fallback.wavelength()));
        final double frequency = clamp(section.getDouble("frequency", fallback.frequency()), 0.05D, 4.0D);
        final double heightVariation = clamp(
                section.getDouble("height-variation", fallback.heightVariation()),
                0.0D,
                1.5D
        );
        final double occurrence = clamp(section.getDouble("occurrence", fallback.occurrence()), 0.0D, 1.0D);
        return new WaveProfile(amplitude, speed, wavelength, frequency, heightVariation, occurrence);
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(final String worldName) {
        return Objects.requireNonNull(worldName, "worldName").toLowerCase(Locale.ROOT);
    }

    private static String normalizeBiomeId(final String rawBiomeId) {
        String biomeId = Objects.requireNonNull(rawBiomeId, "rawBiomeId").toLowerCase(Locale.ROOT);
        final int namespace = biomeId.indexOf(':');
        if (namespace >= 0 && namespace < (biomeId.length() - 1)) {
            biomeId = biomeId.substring(namespace + 1);
        }
        return biomeId;
    }

    public record WaveProfile(
            double amplitude,
            double speed,
            double wavelength,
            double frequency,
            double heightVariation,
            double occurrence
    ) {
    }

    public record WorldOverride(
            WaveProfile clear,
            WaveProfile storm,
            Integer simulationRadius,
            Integer renderRadius
    ) {
    }
}
