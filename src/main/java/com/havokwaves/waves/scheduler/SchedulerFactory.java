package com.havokwaves.waves.scheduler;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerFactory {
    private SchedulerFactory() {
    }

    public static ServerScheduler create(final JavaPlugin plugin) {
        if (isFoliaRuntime()) {
            return new FoliaSchedulerAdapter(plugin);
        }
        return new PaperSchedulerAdapter(plugin);
    }

    public static boolean isFoliaRuntime() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (final ClassNotFoundException ignored) {
            return Bukkit.getServer().getName().toLowerCase(Locale.ROOT).contains("folia");
        }
    }
}
