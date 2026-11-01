package org.multiworldplatform;

import com.google.inject.Inject;
import org.multiworldplatform.api.MultiWorldPlatformApi;
import org.multiworldplatform.command.WorldCommand;
import org.multiworldplatform.config.ConfigStore;
import org.multiworldplatform.world.WorldArchiveService;
import org.multiworldplatform.world.WorldService;
import org.spongepowered.api.Server;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.io.IOException;
import java.nio.file.Path;

/**
 * MultiWorldPlatform entry point.
 */
@Plugin("multiworldplatform")
public final class MultiWorldPlatformPlugin {

    private final PluginContainer plugin;
    private final ConfigStore config;
    private final WorldService worlds;

    @Inject
    public MultiWorldPlatformPlugin(final PluginContainer plugin,
            @ConfigDir(sharedRoot = false) final Path configDirectory) {
        this.plugin = plugin;
        this.config = new ConfigStore(configDirectory);
        try {
            this.config.initialize();
        } catch (final IOException error) {
            throw new IllegalStateException("Could not initialize MultiWorldPlatform config", error);
        }
        final WorldArchiveService archives = new WorldArchiveService(this.config);
        this.worlds = new WorldService(plugin, this.config, archives);
        MultiWorldPlatform.install(this.worlds);
        plugin.logger().info("MultiWorldPlatform initialized; config={}",
                this.config.directory());
    }

    @Listener
    public void registerCommands(final RegisterCommandEvent<Command.Raw> event) {
        final WorldCommand command = new WorldCommand(this.worlds);
        event.register(this.plugin, command, "mwp", "cwp", "multiworldplatform");
    }

    @Listener
    public void serverStarted(final StartedEngineEvent<Server> event) {
        final MultiWorldPlatformApi installed = MultiWorldPlatform.apiIfReady().orElse(null);
        if (installed != this.worlds) {
            MultiWorldPlatform.install(this.worlds);
        }
        this.worlds.applyConfiguredRulesToLoadedWorlds();
        this.plugin.logger().info("Commands: /mwp and /cwp; import directories: {} and {}",
                "world/<name>.zip", this.config.importDirectory());
    }
}
