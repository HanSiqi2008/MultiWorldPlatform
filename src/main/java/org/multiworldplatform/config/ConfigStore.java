package org.multiworldplatform.config;

import org.multiworldplatform.api.MultiWorldPlatformApi.WorldKind;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.loader.ConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns all files below {@code config/multiworldplatform}.
 */
public final class ConfigStore {

    private final Path directory;
    private final Path worldsDirectory;
    private final Path importDirectory;
    private final Path exportDirectory;

    public ConfigStore(final Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.worldsDirectory = this.directory.resolve("worlds");
        this.importDirectory = this.directory.resolve("import");
        this.exportDirectory = this.directory.resolve("export");
    }

    public Path directory() {
        return this.directory;
    }

    public Path importDirectory() {
        return this.importDirectory;
    }

    public Path exportDirectory() {
        return this.exportDirectory;
    }

    public void initialize() throws IOException {
        Files.createDirectories(this.worldsDirectory);
        Files.createDirectories(this.importDirectory);
        Files.createDirectories(this.exportDirectory);

        final Path configFile = this.directory.resolve("config.conf");
        if (Files.notExists(configFile)) {
            final CommentedConfigurationNode root = this.loader(configFile).createNode();
            root.node("worlds", "managed-namespace").set(String.class, "multiworldplatform");
            root.node("worlds", "load-on-startup").set(boolean.class, false);
            root.node("worlds", "default-unload-destination").set(String.class,
                    "minecraft:overworld");
            root.node("archives", "world-root-import").set(boolean.class, true);
            root.node("archives", "config-import-directory").set(String.class, "import");
            root.node("archives", "export-directory").set(String.class, "export");
            root.node("worlds").comment(
                    "Game rules are stored per world in worlds/<world_name>.conf");
            this.loader(configFile).save(root);
        }
    }

    public Path definitionFile(final String name) {
        return this.worldsDirectory.resolve(name + ".conf");
    }

    public WorldDefinition load(final String name) throws IOException {
        return this.load(this.definitionFile(name), name);
    }

    public WorldDefinition load(final Path file, final String expectedName) throws IOException {
        final ConfigurationNode world = this.loader(file).load().node("world");
        final String name = world.node("name").getString(expectedName);
        final String keyString = world.node("key").getString("multiworldplatform:" + name);
        final ResourceKey key;
        try {
            key = ResourceKey.resolve(keyString);
        } catch (final IllegalArgumentException error) {
            throw new IOException("Invalid world key in " + file + ": " + keyString, error);
        }
        final ResourceKey environment = ResourceKey.resolve(
                world.node("environment").getString("minecraft:overworld"));
        final WorldKind kind;
        try {
            kind = WorldKind.valueOf(world.node("type").getString("normal").toUpperCase());
        } catch (final IllegalArgumentException error) {
            throw new IOException("Invalid world type in " + file, error);
        }

        final Map<String, String> rules = new LinkedHashMap<>();
        for (final Map.Entry<Object, ? extends ConfigurationNode> entry :
                world.parent().node("game-rules").childrenMap().entrySet()) {
            final String value = entry.getValue().getString();
            if (value != null) {
                rules.put(String.valueOf(entry.getKey()), value);
            }
        }
        return new WorldDefinition(name, key, environment, kind,
                world.node("seed").getLong(0L),
                world.node("generate-structures").getBoolean(true),
                world.node("adjust-spawn").getBoolean(true), rules);
    }

    public void save(final WorldDefinition definition) throws IOException {
        this.save(definition, this.definitionFile(definition.name()));
    }

    public void save(final WorldDefinition definition, final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        final CommentedConfigurationNode root = this.loader(file).createNode();
        final CommentedConfigurationNode world = root.node("world");
        world.node("name").set(String.class, definition.name());
        world.node("key").set(String.class, definition.key().formatted());
        world.node("environment").set(String.class, definition.environment().formatted());
        world.node("type").set(String.class, definition.kind().name().toLowerCase());
        world.node("seed").set(long.class, definition.seed());
        world.node("generate-structures").set(boolean.class, definition.generateStructures());
        world.node("adjust-spawn").set(boolean.class, definition.adjustSpawn());

        if (!definition.gameRules().isEmpty()) {
            final CommentedConfigurationNode rules = root.node("game-rules");
            rules.comment(
                    "Rule names may be registry IDs or vanilla names such as doDaylightCycle");
            try {
                for (final Map.Entry<String, String> entry : definition.gameRules().entrySet()) {
                    rules.node(entry.getKey()).set(String.class, entry.getValue());
                }
            } catch (final org.spongepowered.configurate.serialize.SerializationException error) {
                throw new IOException("Could not serialize game rules", error);
            }
        }
        this.loader(file).save(root);
    }

    public void delete(final String name) throws IOException {
        Files.deleteIfExists(this.definitionFile(name));
    }

    private ConfigurationLoader<CommentedConfigurationNode> loader(final Path file) {
        return HoconConfigurationLoader.builder().path(file).build();
    }
}
