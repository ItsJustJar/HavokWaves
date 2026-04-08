package com.havokwaves.waves.listener;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import com.havokwaves.waves.service.PlayerToggleService;
import com.havokwaves.waves.service.WaveRenderService;

public final class WaveListener implements Listener {
    private final WaveRenderService renderService;
    private final PlayerToggleService toggleService;

    public WaveListener(final WaveRenderService renderService, final PlayerToggleService toggleService) {
        this.renderService = renderService;
        this.toggleService = toggleService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        toggleService.setEnabled(event.getPlayer().getUniqueId(), true);
        renderService.markDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        renderService.clearPlayer(event.getPlayer());
        toggleService.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            renderService.markDirty(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        renderService.markDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        renderService.markDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent event) {
        renderService.markDirtyNearChunk(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(final BlockFromToEvent event) {
        if (event.getBlock().getType() == Material.WATER || event.getToBlock().getType() == Material.WATER) {
            renderService.markDirtyNearChunk(
                    event.getBlock().getWorld(),
                    event.getBlock().getChunk().getX(),
                    event.getBlock().getChunk().getZ()
            );
        }
    }

}
