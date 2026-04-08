package com.havokwaves.waves.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

public final class PlayerToggleService {
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();

    public boolean isEnabled(final Player player) {
        return !disabledPlayers.contains(player.getUniqueId());
    }

    public void setEnabled(final UUID playerId, final boolean enabled) {
        if (enabled) {
            disabledPlayers.remove(playerId);
        } else {
            disabledPlayers.add(playerId);
        }
    }

    public void remove(final UUID playerId) {
        disabledPlayers.remove(playerId);
    }

    public void enableAll() {
        disabledPlayers.clear();
    }
}
