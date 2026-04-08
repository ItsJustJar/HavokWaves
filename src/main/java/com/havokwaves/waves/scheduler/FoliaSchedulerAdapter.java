package com.havokwaves.waves.scheduler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class FoliaSchedulerAdapter implements ServerScheduler {
    private final JavaPlugin plugin;
    private final List<Object> foliaTasks = new ArrayList<>();
    private final List<BukkitTask> fallbackTasks = new ArrayList<>();
    private volatile boolean warnFallback = false;

    public FoliaSchedulerAdapter(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isFolia() {
        return true;
    }

    @Override
    public synchronized void runRepeating(final Runnable runnable, final long delayTicks, final long periodTicks) {
        try {
            final Object globalRegionScheduler = invokeNoArgs(Bukkit.getServer(), "getGlobalRegionScheduler");
            final Method runAtFixedRate = findMethod(globalRegionScheduler.getClass(), "runAtFixedRate", 4);
            if (runAtFixedRate == null) {
                throw new NoSuchMethodException("runAtFixedRate");
            }
            final Object scheduled = runAtFixedRate.invoke(
                    globalRegionScheduler,
                    plugin,
                    (Consumer<Object>) ignored -> runnable.run(),
                    Math.max(1L, delayTicks),
                    Math.max(1L, periodTicks)
            );
            foliaTasks.add(scheduled);
            return;
        } catch (final ReflectiveOperationException ex) {
            logFallback(ex);
        }

        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                runnable,
                Math.max(1L, delayTicks),
                Math.max(1L, periodTicks)
        );
        fallbackTasks.add(task);
    }

    @Override
    public void runPlayer(final Player player, final Runnable runnable) {
        if (!player.isOnline()) {
            return;
        }
        try {
            final Object entityScheduler = invokeNoArgs(player, "getScheduler");

            Method runMethod = findMethod(entityScheduler.getClass(), "run", 3);
            if (runMethod != null) {
                runMethod.invoke(entityScheduler, plugin, (Consumer<Object>) ignored -> runnable.run(), null);
                return;
            }

            final Method delayedRunMethod = findMethod(entityScheduler.getClass(), "runDelayed", 4);
            if (delayedRunMethod != null) {
                delayedRunMethod.invoke(
                        entityScheduler,
                        plugin,
                        (Consumer<Object>) ignored -> runnable.run(),
                        null,
                        1L
                );
                return;
            }

            final Method executeMethod = findMethod(entityScheduler.getClass(), "execute", 4);
            if (executeMethod != null) {
                executeMethod.invoke(entityScheduler, plugin, runnable, null, 1L);
                return;
            }
        } catch (final ReflectiveOperationException ex) {
            logFallback(ex);
        }

        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public void runRegion(final World world, final int chunkX, final int chunkZ, final Runnable runnable) {
        try {
            final Object regionScheduler = invokeNoArgs(Bukkit.getServer(), "getRegionScheduler");
            final Method runMethod = findMethod(regionScheduler.getClass(), "run", 5);
            if (runMethod == null) {
                throw new NoSuchMethodException("run");
            }
            runMethod.invoke(
                    regionScheduler,
                    plugin,
                    world,
                    chunkX,
                    chunkZ,
                    (Consumer<Object>) ignored -> runnable.run()
            );
            return;
        } catch (final ReflectiveOperationException ex) {
            logFallback(ex);
        }

        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public synchronized void cancelAll() {
        for (final Object task : foliaTasks) {
            try {
                final Method cancel = findMethod(task.getClass(), "cancel", 0);
                if (cancel != null) {
                    cancel.invoke(task);
                }
            } catch (final ReflectiveOperationException ex) {
                plugin.getLogger().log(Level.FINE, "Failed to cancel Folia task reflectively", ex);
            }
        }
        foliaTasks.clear();

        for (final BukkitTask task : fallbackTasks) {
            task.cancel();
        }
        fallbackTasks.clear();
    }

    private Object invokeNoArgs(final Object target, final String methodName) throws ReflectiveOperationException {
        final Method method = findMethod(target.getClass(), methodName, 0);
        if (method == null) {
            throw new NoSuchMethodException("Missing method " + methodName + " on " + target.getClass().getName());
        }
        return method.invoke(target);
    }

    private Method findMethod(final Class<?> type, final String name, final int parameterCount) {
        for (final Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private void logFallback(final ReflectiveOperationException ex) {
        if (!warnFallback) {
            warnFallback = true;
            plugin.getLogger().log(
                    Level.WARNING,
                    "Folia scheduler reflection failed, falling back to Bukkit scheduler for some tasks.",
                    ex
            );
        } else {
            plugin.getLogger().log(Level.FINE, "Folia scheduler reflection error", ex);
        }
    }
}
