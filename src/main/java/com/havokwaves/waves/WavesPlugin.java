package com.havokwaves.waves;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.havokwaves.waves.command.WavesCommand;
import com.havokwaves.waves.config.PluginConfig;
import com.havokwaves.waves.listener.WaveListener;
import com.havokwaves.waves.packet.PacketEventsBridge;
import com.havokwaves.waves.scheduler.SchedulerFactory;
import com.havokwaves.waves.scheduler.ServerScheduler;
import com.havokwaves.waves.service.PlayerToggleService;
import com.havokwaves.waves.service.WaveRenderService;
import com.havokwaves.waves.wave.WaveModel;

public final class WavesPlugin extends JavaPlugin {
    private volatile PluginConfig pluginConfig;
    private ServerScheduler scheduler;
    private PacketEventsBridge packetEventsBridge;
    private PlayerToggleService toggleService;
    private WaveRenderService renderService;
    private long simulationTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pluginConfig = PluginConfig.from(getConfig());

        scheduler = SchedulerFactory.create(this);
        packetEventsBridge = new PacketEventsBridge(this);
        if (!packetEventsBridge.initialize()) {
            getLogger().severe("Block-change transport initialization failed. Disabling HavokWaves.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        toggleService = new PlayerToggleService();
        toggleService.enableAll();
        final WaveModel waveModel = new WaveModel();
        renderService = new WaveRenderService(
                scheduler,
                packetEventsBridge,
                waveModel,
                this::getPluginConfig,
                toggleService
        );

        getServer().getPluginManager().registerEvents(new WaveListener(renderService, toggleService), this);
        registerCommand();
        startSimulationLoop();

        getLogger().log(
            Level.INFO,
            "HavokWaves enabled. Runtime={0}",
            scheduler.isFolia() ? "Folia" : "Paper"
        );
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        if (renderService != null) {
            renderService.clearAllImmediate();
        }
        if (packetEventsBridge != null) {
            packetEventsBridge.shutdown();
        }
    }

    public void reloadPluginConfiguration() {
        if (renderService != null) {
            renderService.clearAll();
        }
        if (toggleService != null) {
            toggleService.enableAll();
        }
        reloadConfig();
        pluginConfig = PluginConfig.from(getConfig());
        simulationTick = 0L;
        if (scheduler != null) {
            scheduler.cancelAll();
            startSimulationLoop();
        }
        if (renderService != null) {
            renderService.invalidateAll();
        }
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public boolean isFoliaRuntime() {
        return scheduler != null && scheduler.isFolia();
    }

    private void registerCommand() {
        final PluginCommand waves = getCommand("waves");
        if (waves == null) {
            getLogger().severe("Command /waves was not found in plugin.yml");
            return;
        }
        final WavesCommand commandHandler = new WavesCommand(this, toggleService, renderService);
        waves.setExecutor(commandHandler);
        waves.setTabCompleter(commandHandler);
    }

    private void startSimulationLoop() {
        final long period = Math.max(1L, pluginConfig.updateIntervalTicks());
        scheduler.runRepeating(() -> {
            if (!isEnabled()) {
                return;
            }
            simulationTick += period;
            final long tickSnapshot = simulationTick;
            renderService.tickGlobal(tickSnapshot);

            for (final Player player : Bukkit.getOnlinePlayers()) {
                scheduler.runPlayer(player, () -> renderService.tickPlayer(player, tickSnapshot));
            }
        }, period, period);
    }
}
