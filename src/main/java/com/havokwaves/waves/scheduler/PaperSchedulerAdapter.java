package com.havokwaves.waves.scheduler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class PaperSchedulerAdapter implements ServerScheduler {
    private final JavaPlugin plugin;
    private final List<BukkitTask> tasks = new CopyOnWriteArrayList<>();

    public PaperSchedulerAdapter(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isFolia() {
        return false;
    }

    @Override
    public void runRepeating(final Runnable runnable, final long delayTicks, final long periodTicks) {
        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                runnable,
                Math.max(1L, delayTicks),
                Math.max(1L, periodTicks)
        );
        tasks.add(task);
    }

    @Override
    public void runPlayer(final Player player, final Runnable runnable) {
        if (!player.isOnline()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public void runRegion(final World world, final int chunkX, final int chunkZ, final Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public void cancelAll() {
        for (final BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }
}
