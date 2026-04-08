package com.havokwaves.waves.packet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PacketEventsBridge {
    private final JavaPlugin plugin;
    private volatile boolean available;
    private final Map<Material, BlockData> blockDataCache = new ConcurrentHashMap<>();
    private final Map<String, BlockData> stringBlockDataCache = new ConcurrentHashMap<>();

    public PacketEventsBridge(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean initialize() {
        available = true;
        blockDataCache.put(Material.WATER, Material.WATER.createBlockData());
        blockDataCache.put(Material.AIR, Material.AIR.createBlockData());
        plugin.getLogger().info("Using native Bukkit block-change sender (no PacketEvents dependency).");
        return true;
    }

    public boolean isAvailable() {
        return available;
    }

    public void shutdown() {
        available = false;
        blockDataCache.clear();
        stringBlockDataCache.clear();
    }

    public void sendBlockChange(final Player player, final int x, final int y, final int z, final Material material) {
        if (!available || !player.isOnline()) {
            return;
        }
        final BlockData data = blockDataCache.computeIfAbsent(material, Material::createBlockData);
        player.sendBlockChange(new Location(player.getWorld(), x, y, z), data);
    }

    public void sendBlockChange(final Player player, final int x, final int y, final int z, final String blockDataString) {
        if (!available || !player.isOnline() || blockDataString == null || blockDataString.isBlank()) {
            return;
        }
        try {
            final BlockData blockData = stringBlockDataCache.computeIfAbsent(blockDataString, Bukkit::createBlockData);
            player.sendBlockChange(new Location(player.getWorld(), x, y, z), blockData);
        } catch (final IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid block data string for block change: " + blockDataString);
        }
    }
}
