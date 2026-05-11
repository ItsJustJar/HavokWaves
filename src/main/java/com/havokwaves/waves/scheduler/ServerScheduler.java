package com.havokwaves.waves.scheduler;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public interface ServerScheduler {
    boolean isFolia();

    void runRepeating(Runnable runnable, long delayTicks, long periodTicks);

    void runPlayer(Player player, Runnable runnable);

    void runEntity(Entity entity, Runnable runnable);

    void runRegion(World world, int chunkX, int chunkZ, Runnable runnable);

    void cancelAll();
}
