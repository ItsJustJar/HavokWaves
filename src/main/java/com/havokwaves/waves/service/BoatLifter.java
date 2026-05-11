package com.havokwaves.waves.service;

import java.util.Map;

import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.havokwaves.waves.scheduler.ServerScheduler;
import com.havokwaves.waves.util.BlockPosUtil;

final class BoatLifter {
    private static final double LIFT_OFFSET = 0.3D;
    private static final double LIFT_DEADBAND = 0.10D;
    private static final double LIFT_STEP = 0.35D;

    private final ServerScheduler scheduler;

    BoatLifter(final ServerScheduler scheduler) {
        this.scheduler = scheduler;
    }

    void liftNearby(final Player player, final Map<Long, Integer> waveTopByXZ, final int searchRadius) {
        // getNearbyEntities from the player's region is safe on Folia; world.getEntities() is not.
        // The per-boat work is dispatched to each boat's own entity scheduler so velocity/teleport
        // mutations happen on the boat's region thread, not the player's.
        for (final Entity entity : player.getWorld().getNearbyEntities(
                player.getLocation(),
                searchRadius + 2, 20, searchRadius + 2,
                e -> e instanceof Boat)) {
            final Boat boat = (Boat) entity;
            scheduler.runEntity(boat, () -> lift(boat, waveTopByXZ));
        }
    }

    private void lift(final Boat boat, final Map<Long, Integer> waveTopByXZ) {
        if (!boat.isValid()) {
            return;
        }
        final int bx = boat.getLocation().getBlockX();
        final int bz = boat.getLocation().getBlockZ();
        Integer targetY = waveTopByXZ.get(BlockPosUtil.columnKey(bx, bz));
        if (targetY == null) targetY = waveTopByXZ.get(BlockPosUtil.columnKey(bx + 1, bz));
        if (targetY == null) targetY = waveTopByXZ.get(BlockPosUtil.columnKey(bx - 1, bz));
        if (targetY == null) targetY = waveTopByXZ.get(BlockPosUtil.columnKey(bx, bz + 1));
        if (targetY == null) targetY = waveTopByXZ.get(BlockPosUtil.columnKey(bx, bz - 1));
        if (targetY == null) {
            return;
        }
        final double currentY = boat.getLocation().getY();
        final double desiredY = targetY + LIFT_OFFSET;

        // Gravity races teleport every tick, so zero out downward velocity directly.
        final Vector vel = boat.getVelocity();
        if (vel.getY() < 0.0D) {
            vel.setY(0.0D);
            boat.setVelocity(vel);
        }

        // Snap upward to the crest; let gravity handle descent naturally.
        if (currentY < desiredY - LIFT_DEADBAND) {
            final Location loc = boat.getLocation().clone();
            loc.setY(Math.min(desiredY, currentY + LIFT_STEP));
            boat.teleportAsync(loc);
        }
    }
}
