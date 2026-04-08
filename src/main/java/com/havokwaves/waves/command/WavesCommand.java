package com.havokwaves.waves.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.havokwaves.waves.WavesPlugin;
import com.havokwaves.waves.config.PluginConfig;
import com.havokwaves.waves.service.PlayerToggleService;
import com.havokwaves.waves.service.WaveRenderService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class WavesCommand implements CommandExecutor, TabCompleter {
    private final WavesPlugin plugin;
    private final PlayerToggleService toggleService;
    private final WaveRenderService renderService;

    public WavesCommand(
            final WavesPlugin plugin,
            final PlayerToggleService toggleService,
            final WaveRenderService renderService
    ) {
        this.plugin = plugin;
        this.toggleService = toggleService;
        this.renderService = renderService;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (sender == null) {
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /waves <on|off|reload|debug>", NamedTextColor.YELLOW));
            return true;
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "on" -> handleToggle(sender, true);
            case "off" -> handleToggle(sender, false);
            case "reload" -> handleReload(sender);
            case "debug" -> handleDebug(sender);
            default -> {
                sender.sendMessage(
                        Component.text("Unknown subcommand. Use /waves <on|off|reload|debug>.", NamedTextColor.RED)
                );
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        final List<String> options = new ArrayList<>(4);
        final String input = args[0].toLowerCase(Locale.ROOT);
        for (final String candidate : List.of("on", "off", "reload", "debug")) {
            if (candidate.startsWith(input)) {
                options.add(candidate);
            }
        }
        return options;
    }

    private boolean handleToggle(final CommandSender sender, final boolean enabled) {
        if (sender == null) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this subcommand.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("waves.use")) {
            sender.sendMessage(Component.text("You do not have permission: waves.use", NamedTextColor.RED));
            return true;
        }

        toggleService.setEnabled(player.getUniqueId(), enabled);
        if (enabled) {
            renderService.markDirty(player);
            sender.sendMessage(Component.text("Waves enabled.", NamedTextColor.GREEN));
        } else {
            renderService.clearPlayer(player);
            sender.sendMessage(Component.text("Waves disabled.", NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean handleReload(final CommandSender sender) {
        if (sender == null) {
            return true;
        }
        if (!sender.hasPermission("waves.admin")) {
            sender.sendMessage(Component.text("You do not have permission: waves.admin", NamedTextColor.RED));
            return true;
        }
        plugin.reloadPluginConfiguration();
        sender.sendMessage(Component.text("HavokWaves config reloaded.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleDebug(final CommandSender sender) {
        if (sender == null) {
            return true;
        }
        if (!sender.hasPermission("waves.admin")) {
            sender.sendMessage(Component.text("You do not have permission: waves.admin", NamedTextColor.RED));
            return true;
        }

        final PluginConfig config = plugin.getPluginConfig();
        sender.sendMessage(Component.text("HavokWaves Debug", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("- Runtime: " + (plugin.isFoliaRuntime() ? "Folia" : "Paper"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("- update-interval-ticks: " + config.updateIntervalTicks(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("- simulation-radius: " + config.simulationRadius(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("- render-radius: " + config.renderRadius(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("- clear profile: amplitude=" + config.clear().amplitude()
                + ", speed=" + config.clear().speed()
                + ", wavelength=" + config.clear().wavelength()
                + ", frequency=" + config.clear().frequency()
                + ", height-variation=" + config.clear().heightVariation()
            + ", occurrence=" + config.clear().occurrence(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("- storm profile: amplitude=" + config.storm().amplitude()
                + ", speed=" + config.storm().speed()
                + ", wavelength=" + config.storm().wavelength()
                + ", frequency=" + config.storm().frequency()
                + ", height-variation=" + config.storm().heightVariation()
            + ", occurrence=" + config.storm().occurrence(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("- max-block-updates-per-tick-per-player: "
            + config.maxBlockUpdatesPerTickPerPlayer(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("- biomes-whitelist size: " + config.biomesWhitelist().size(), NamedTextColor.GRAY));
        return true;
    }
}
