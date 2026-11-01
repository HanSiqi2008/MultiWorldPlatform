package org.multiworldplatform.world;

import org.multiworldplatform.api.MultiWorldPlatformApi;
import org.multiworldplatform.api.MultiWorldPlatformApi.CreateOptions;
import org.multiworldplatform.api.MultiWorldPlatformApi.WorldInfo;
import org.multiworldplatform.api.MultiWorldPlatformApi.WorldKind;
import org.multiworldplatform.config.ConfigStore;
import org.multiworldplatform.config.WorldDefinition;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.data.persistence.DataFormats;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.registry.RegistryEntry;
import org.spongepowered.api.registry.RegistryTypes;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.world.DefaultWorldKeys;
import org.spongepowered.api.world.SerializationBehavior;
import org.spongepowered.api.world.WorldTypes;
import org.spongepowered.api.world.gamerule.GameRule;
import org.spongepowered.api.world.generation.ChunkGenerator;
import org.spongepowered.api.world.generation.config.flat.FlatGeneratorConfig;
import org.spongepowered.api.world.generation.config.flat.FlatGeneratorConfigs;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.api.world.server.WorldManager;
import org.spongepowered.api.world.server.WorldTemplate;
import org.spongepowered.plugin.PluginContainer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Serialized implementation of every world operation.
 */
public final class WorldService implements MultiWorldPlatformApi {

    public static final String MANAGED_NAMESPACE = "multiworldplatform";
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

    private final PluginContainer plugin;
    private final ConfigStore config;
    private final WorldArchiveService archives;
    private final Object operationLock = new Object();
    private CompletableFuture<Void> operationTail = CompletableFuture.completedFuture(null);

    public WorldService(final PluginContainer plugin, final ConfigStore config,
            final WorldArchiveService archives) {
        this.plugin = plugin;
        this.config = config;
        this.archives = archives;
    }

    @Override
    public ResourceKey keyForName(final String worldName) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("World name is required");
        }
        final String input = worldName.trim();
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "world", "overworld", "minecraft:overworld" -> DefaultWorldKeys.DEFAULT;
            case "dim-1", "the_nether", "minecraft:the_nether" -> DefaultWorldKeys.THE_NETHER;
            case "dim1", "the_end", "minecraft:the_end" -> DefaultWorldKeys.THE_END;
            default -> keyForManagedName(input);
        };
    }

    @Override
    public CompletableFuture<List<WorldInfo>> listWorlds() {
        return this.serialized(() -> this.onServer(() -> {
            final WorldManager manager = Sponge.server().worldManager();
            final Map<ResourceKey, WorldInfo> result = new LinkedHashMap<>();
            result.put(DefaultWorldKeys.DEFAULT, this.info(DefaultWorldKeys.DEFAULT));
            if (manager.worldExists(DefaultWorldKeys.THE_NETHER)) {
                result.put(DefaultWorldKeys.THE_NETHER, this.info(DefaultWorldKeys.THE_NETHER));
            }
            if (manager.worldExists(DefaultWorldKeys.THE_END)) {
                result.put(DefaultWorldKeys.THE_END, this.info(DefaultWorldKeys.THE_END));
            }
            for (final ResourceKey key : manager.worldKeys()) {
                if (key.namespace().equals(MANAGED_NAMESPACE)) {
                    result.put(key, this.info(key));
                }
            }
            final List<WorldInfo> worlds = new ArrayList<>(result.values());
            worlds.sort(Comparator.comparing(world -> world.key().formatted()));
            return CompletableFuture.completedFuture(List.copyOf(worlds));
        }));
    }

    @Override
    public CompletableFuture<WorldInfo> createWorld(final String worldName,
            final ResourceKey environment, final CreateOptions options) {
        return this.serialized(() -> {
            final String name = this.managedName(worldName);
            final ResourceKey key = managedKey(name);
            if (!this.keyForName(environment.formatted()).equals(environment)) {
                throw new IllegalArgumentException("Unsupported environment: " + environment);
            }
            final WorldKind kind = options.kind();
            if (kind == WorldKind.NORMAL && !options.generateStructures()) {
                throw new IllegalArgumentException(
                        "Sponge 12 / Minecraft 1.21 exposes structure generation as a "
                                + "server-wide WorldOptions flag, not a per-dimension public "
                                + "API setting; --no-structures is supported safely for "
                                + "flat and void worlds only");
            }
            final long seed = options.seed()
                    .orElseGet(() -> ThreadLocalRandom.current().nextLong());
            final WorldDefinition definition = new WorldDefinition(name, key, environment, kind,
                    seed, options.generateStructures(), options.adjustSpawn(), Map.of());
            return this.onServer(() -> {
                final WorldManager manager = Sponge.server().worldManager();
                if (manager.worldExists(key)) {
                    throw new IllegalStateException("World already exists: " + name);
                }
                final CompletableFuture<Void> configWritten =
                        CompletableFuture.runAsync(() -> {
                            try {
                                this.config.save(definition);
                            } catch (final IOException error) {
                                throw new CompletionException(error);
                            }
                        });
                return configWritten.thenCompose($ -> this.onServer(() -> {
                    final WorldManager serverManager = Sponge.server().worldManager();
                    final WorldTemplate template = this.createTemplate(definition);
                    return serverManager.loadWorld(template)
                            .thenCompose(world -> this.configureWorld(world, definition))
                            .thenApply(world -> this.info(key));
                }));
            }).whenComplete((ignored, error) -> {
                if (error != null) {
                    try {
                        this.config.delete(name);
                    } catch (final IOException ignoredDelete) {
                        // Preserve the original creation error.
                    }
                }
            });
        });
    }

    @Override
    public CompletableFuture<WorldInfo> importWorld(final String worldName) {
        return this.serialized(() -> {
            final String name = this.managedName(worldName);
            final ResourceKey key = managedKey(name);
            return this.onServer(() -> {
                if (Sponge.server().worldManager().worldExists(key)) {
                    throw new IllegalArgumentException("World already exists: " + name);
                }
                final Path defaultWorld = Sponge.server().worldManager()
                        .worldDirectory(DefaultWorldKeys.DEFAULT)
                        .orElseThrow(() -> new IllegalStateException(
                                "The default world directory is unavailable"));
                final Path archive = this.archives.findImportArchive(defaultWorld, name);
                if (archive == null) {
                    throw new IllegalArgumentException("No import archive found at "
                            + defaultWorld.resolve(name + ".zip").toAbsolutePath()
                            + " or " + this.config.importDirectory().resolve(name + ".zip"));
                }
                return CompletableFuture.completedFuture(
                        new ImportRequest(archive, defaultWorld, name, key));
            }).thenCompose(request -> CompletableFuture.supplyAsync(() -> {
                try {
                    return this.archives.extract(request.archive(), request.name());
                } catch (final IOException error) {
                    throw new CompletionException(error);
                }
            })).thenCompose(extracted -> this.installImport(extracted, name, key));
        });
    }

    @Override
    public CompletableFuture<Path> exportWorld(final String worldName) {
        return this.serialized(() -> {
            final String name = this.managedName(worldName);
            final ResourceKey key = managedKey(name);
            return this.onServer(() -> {
                if (!Sponge.server().worldManager().worldExists(key)) {
                    throw new IllegalArgumentException("World does not exist: " + name);
                }
                final WorldDefinition definition;
                try {
                    definition = this.config.load(name);
                } catch (final IOException error) {
                    throw new CompletionException(error);
                }
                final WorldManager manager = Sponge.server().worldManager();
                final boolean wasLoaded = manager.world(key).isPresent();
                final CompletableFuture<ServerWorld> worldFuture = wasLoaded
                        ? CompletableFuture.completedFuture(manager.world(key).orElseThrow())
                        : manager.loadWorld(key);
                return worldFuture.thenCompose(world -> {
                    if (!world.players().isEmpty()) {
                        throw new CompletionException(new IllegalStateException(
                                "Move all players out of " + name
                                        + " before exporting it"));
                    }
                    final String template;
                    try {
                        if (!world.saveAndFlush()) {
                            throw new IllegalStateException("World refused to save: " + name);
                        }
                        template = DataFormats.JSON.get().write(
                                WorldTemplate.builder().from(world).build().toContainer());
                    } catch (final IOException error) {
                        return failed(new CompletionException(error));
                    }

                    final Path directory = world.directory().toAbsolutePath().normalize();
                    return manager.unloadWorld(world).thenCompose(unloaded -> {
                        if (!unloaded) {
                            throw new CompletionException(new IllegalStateException(
                                    "World could not be unloaded for a consistent snapshot"));
                        }
                        return this.writeExportAsync(directory, name, definition, template)
                                .thenCompose(path -> {
                                    if (!wasLoaded) {
                                        return CompletableFuture.completedFuture(path);
                                    }
                                    return this.onServer(() -> manager.loadWorld(key))
                                            .thenCompose(loaded -> this.configureWorld(
                                                    loaded, definition))
                                            .thenApply(ignored -> path)
                                            .exceptionally(error -> {
                                                this.plugin.logger().warn(
                                                        "Export succeeded, but world "
                                                                + name
                                                                + " could not be loaded again",
                                                        rootCause(error));
                                                return path;
                                            });
                                });
                    });
                });
            });
        });
    }

    @Override
    public CompletableFuture<WorldInfo> loadWorld(final String worldName) {
        return this.serialized(() -> {
            final String name = this.managedName(worldName);
            final ResourceKey key = managedKey(name);
            return this.onServer(() -> {
                final WorldManager manager = Sponge.server().worldManager();
                if (manager.world(key).isPresent()) {
                    throw new IllegalStateException("World is already loaded: " + name);
                }
                if (!manager.worldExists(key)) {
                    throw new IllegalArgumentException("World does not exist: " + name);
                }
                final WorldDefinition definition;
                try {
                    definition = this.config.load(name);
                } catch (final IOException error) {
                    throw new CompletionException(error);
                }
                return manager.loadWorld(key)
                        .thenCompose(world -> this.configureWorld(world, definition))
                        .thenApply(world -> this.info(key));
            });
        });
    }

    @Override
    public CompletableFuture<Boolean> unloadWorld(final String worldName,
            final String destinationWorld) {
        return this.serialized(() -> {
            final String name = this.managedName(worldName);
            final ResourceKey key = managedKey(name);
            final ResourceKey destination = destinationWorld == null || destinationWorld.isBlank()
                    ? DefaultWorldKeys.DEFAULT : this.keyForName(destinationWorld);
            return this.onServer(() -> {
                final WorldManager manager = Sponge.server().worldManager();
                final ServerWorld world = manager.world(key).orElse(null);
                if (world == null) {
                    throw new IllegalStateException("World is not loaded: " + name);
                }
                if (key.equals(destination)) {
                    throw new IllegalArgumentException(
                            "Unload destination cannot be the world being unloaded");
                }
                final CompletableFuture<ServerWorld> destinationFuture =
                        manager.world(destination).isPresent()
                                ? CompletableFuture.completedFuture(
                                        manager.world(destination).orElseThrow())
                                : manager.loadWorld(destination);
                return destinationFuture.thenCompose(target -> {
                    for (final ServerPlayer player : List.copyOf(world.players())) {
                        if (!player.transferToWorld(target,
                                target.properties().spawnPosition().toDouble())) {
                            throw new CompletionException(new IllegalStateException(
                                    "Could not transfer player " + player.name() + " to "
                                            + destination.formatted()));
                        }
                    }
                    try {
                        if (!world.saveAndFlush()) {
                            throw new IllegalStateException("World refused to save: " + name);
                        }
                    } catch (final IOException error) {
                        throw new CompletionException(error);
                    }
                    return manager.unloadWorld(world);
                });
            });
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteWorld(final String worldName,
            final boolean autoUnload) {
        return this.serialized(() -> {
            final String name = this.managedName(worldName);
            final ResourceKey key = managedKey(name);
            return this.onServer(() -> {
                final WorldManager manager = Sponge.server().worldManager();
                if (!manager.worldExists(key)) {
                    throw new IllegalArgumentException("World does not exist: " + name);
                }
                if (manager.world(key).isPresent()) {
                    if (!autoUnload) {
                        throw new IllegalStateException(
                                "Refusing to delete a loaded world; unload it first or use --auto");
                    }
                    return this.unloadNow(key, DefaultWorldKeys.DEFAULT)
                            .thenCompose($ -> manager.deleteWorld(key));
                }
                return manager.deleteWorld(key);
            }).thenCompose(deleted -> {
                if (!deleted) {
                    return CompletableFuture.completedFuture(false);
                }
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        this.config.delete(name);
                        return true;
                    } catch (final IOException error) {
                        throw new CompletionException(error);
                    }
                });
            });
        });
    }

    @Override
    public CompletableFuture<Boolean> teleport(final ServerPlayer player,
            final String worldName) {
        return this.serialized(() -> {
            final ResourceKey key = this.keyForName(worldName);
            return this.onServer(() -> {
                final WorldManager manager = Sponge.server().worldManager();
                final CompletableFuture<ServerWorld> targetFuture = manager.world(key).isPresent()
                        ? CompletableFuture.completedFuture(manager.world(key).orElseThrow())
                        : manager.loadWorld(key);
                return targetFuture.thenApply(target -> {
                    if (!player.transferToWorld(target,
                            target.properties().spawnPosition().toDouble())) {
                        throw new CompletionException(new IllegalStateException(
                                "Could not teleport " + player.name()));
                    }
                    return true;
                });
            });
        });
    }

    @Override
    public CompletableFuture<Void> setGameRule(final String worldName, final String ruleName,
            final String value) {
        return this.serialized(() -> {
            final String name = this.managedName(worldName);
            final ResourceKey key = managedKey(name);
            final WorldDefinition existing;
            try {
                existing = this.config.load(name);
            } catch (final IOException error) {
                return failed(new CompletionException(error));
            }
            return this.onServer(() -> {
                final ResolvedGameRule rule = this.resolveGameRule(ruleName);
                this.validateGameRuleValue(rule.rule(), value);
                final String canonicalName = this.gameRuleName(rule.key());
                final WorldDefinition updated = existing.withGameRule(canonicalName, value);
                final CompletableFuture<Void> saved = CompletableFuture.runAsync(() -> {
                    try {
                        this.config.save(updated);
                    } catch (final IOException error) {
                        throw new CompletionException(error);
                    }
                });
                return saved.thenCompose($ -> this.onServer(() -> {
                    final Optional<ServerWorld> world = Sponge.server().worldManager().world(key);
                    if (world.isPresent()) {
                        this.applyGameRule(world.get(), rule, value);
                        try {
                            if (!world.get().saveAndFlush()) {
                                throw new IllegalStateException(
                                        "World refused to persist game rule");
                            }
                        } catch (final IOException error) {
                            return failed(new CompletionException(error));
                        }
                    }
                    return CompletableFuture.completedFuture(null);
                }));
            });
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getGameRule(final String worldName,
            final String ruleName) {
        return this.serialized(() -> {
            final String name = this.managedName(worldName);
            final ResourceKey key = managedKey(name);
            return this.onServer(() -> {
                final ResolvedGameRule rule = this.resolveGameRule(ruleName);
                final Optional<ServerWorld> world = Sponge.server().worldManager().world(key);
                if (world.isPresent()) {
                    return CompletableFuture.completedFuture(
                            Optional.of(String.valueOf(this.readGameRule(world.get(), rule))));
                }
                final WorldDefinition definition;
                try {
                    definition = this.config.load(name);
                } catch (final IOException error) {
                    return failed(new CompletionException(error));
                }
                return CompletableFuture.completedFuture(Optional.ofNullable(
                        definition.gameRules().get(this.gameRuleName(rule.key()))));
            });
        });
    }

    public void applyConfiguredRulesToLoadedWorlds() {
        if (this.config == null) {
            return;
        }
        for (final ResourceKey key : Sponge.server().worldManager().worldKeys()) {
            if (!key.namespace().equals(MANAGED_NAMESPACE)) {
                continue;
            }
            try {
                final WorldDefinition definition = this.config.load(key.value());
                Sponge.server().worldManager().world(key)
                        .ifPresent(world -> this.applyGameRules(world, definition));
            } catch (final Exception error) {
                this.plugin.logger().warn("Could not restore rules for " + key, error);
            }
        }
    }

    public static ResourceKey managedKey(final String name) {
        return ResourceKey.of(MANAGED_NAMESPACE, name);
    }

    private WorldTemplate createTemplate(final WorldDefinition definition) {
        final WorldTemplate base = switch (definition.environment().formatted()) {
            case "minecraft:the_nether" -> WorldTemplate.theNether();
            case "minecraft:the_end" -> WorldTemplate.theEnd();
            default -> WorldTemplate.overworld();
        };
        final ChunkGenerator generator = switch (definition.kind()) {
            case NORMAL -> switch (definition.environment().formatted()) {
                case "minecraft:the_nether" -> ChunkGenerator.theNether();
                case "minecraft:the_end" -> ChunkGenerator.theEnd();
                default -> ChunkGenerator.overworld();
            };
            case FLAT -> flatGenerator(false);
            case VOID -> flatGenerator(true);
        };
        final WorldTemplate.Builder builder = WorldTemplate.builder()
                .from(base)
                .key(definition.key())
                .add(Keys.CHUNK_GENERATOR, generator)
                .add(Keys.SEED, definition.seed())
                .add(Keys.IS_LOAD_ON_STARTUP, false)
                .add(Keys.PERFORM_SPAWN_LOGIC, definition.adjustSpawn())
                .add(Keys.SERIALIZATION_BEHAVIOR, SerializationBehavior.AUTOMATIC);
        return builder.build();
    }

    private static ChunkGenerator flatGenerator(final boolean theVoid) {
        final FlatGeneratorConfig.Builder builder = FlatGeneratorConfig.builder()
                .from(theVoid ? FlatGeneratorConfigs.THE_VOID.get()
                        : FlatGeneratorConfigs.CLASSIC_FLAT.get())
                .structureSets(List.of());
        return ChunkGenerator.flat(builder.build());
    }

    private CompletableFuture<ServerWorld> configureWorld(final ServerWorld world,
            final WorldDefinition definition) {
        world.properties().offer(Keys.IS_LOAD_ON_STARTUP, false);
        world.properties().offer(Keys.PERFORM_SPAWN_LOGIC, definition.adjustSpawn());
        if (definition.kind() == WorldKind.NORMAL && !definition.generateStructures()) {
            this.plugin.logger().warn(
                    "Ignoring generate-structures=false for normal world {} because Sponge "
                            + "12 does not expose per-dimension structure generation controls",
                    definition.key());
        }
        this.applyGameRules(world, definition);
        try {
            if (!world.saveAndFlush()) {
                return failed(new IllegalStateException("World refused to save configuration"));
            }
        } catch (final IOException error) {
            return failed(new CompletionException(error));
        }
        return CompletableFuture.completedFuture(world);
    }

    private void applyGameRules(final ServerWorld world, final WorldDefinition definition) {
        definition.gameRules().forEach((ruleName, value) -> {
            try {
                this.applyGameRule(world, this.resolveGameRule(ruleName), value);
            } catch (final RuntimeException error) {
                this.plugin.logger().warn("Invalid game rule '" + ruleName + "' for "
                        + definition.key(), error);
            }
        });
    }

    private CompletableFuture<Boolean> unloadNow(final ResourceKey key,
            final ResourceKey destinationKey) {
        final WorldManager manager = Sponge.server().worldManager();
        final ServerWorld world = manager.world(key).orElse(null);
        if (world == null) {
            return CompletableFuture.completedFuture(true);
        }
        final CompletableFuture<ServerWorld> destinationFuture =
                manager.world(destinationKey).isPresent()
                        ? CompletableFuture.completedFuture(
                                manager.world(destinationKey).orElseThrow())
                        : manager.loadWorld(destinationKey);
        return destinationFuture.thenCompose(destination -> {
            for (final ServerPlayer player : List.copyOf(world.players())) {
                if (!player.transferToWorld(destination,
                        destination.properties().spawnPosition().toDouble())) {
                    return failed(new IllegalStateException(
                            "Could not transfer player " + player.name()));
                }
            }
            return manager.unloadWorld(world);
        });
    }

    private CompletableFuture<WorldInfo> installImport(
            final WorldArchiveService.ExtractedWorld extracted, final String name,
            final ResourceKey key) {
        if (!extracted.definition().key().equals(key)) {
            extracted.closeQuietly();
            return failed(new IllegalArgumentException(
                    "Archive world key does not match " + key.formatted()));
        }
        return this.onServer(() -> {
            try {
                final Path defaultWorld = Sponge.server().worldManager()
                        .worldDirectory(DefaultWorldKeys.DEFAULT)
                        .orElseThrow(() -> new IllegalStateException(
                                "The default world directory is unavailable"))
                        .toAbsolutePath().normalize();
                final Path target = defaultWorld.resolve("dimensions")
                        .resolve(MANAGED_NAMESPACE).resolve(name).normalize();
                if (!target.startsWith(defaultWorld.resolve("dimensions")
                        .resolve(MANAGED_NAMESPACE).normalize())) {
                    throw new IllegalArgumentException("Unsafe import target");
                }
                if (Files.exists(target)) {
                    throw new IllegalStateException("World directory already exists: " + target);
                }
                Files.createDirectories(target.getParent());
                moveDirectory(extracted.worldDirectory(), target);
                this.config.save(extracted.definition());

                final WorldTemplate template = WorldTemplate.builder()
                        .fromDataPack(DataFormats.JSON.get().read(extracted.template()))
                        .key(key)
                        .add(Keys.IS_LOAD_ON_STARTUP, false)
                        .add(Keys.PERFORM_SPAWN_LOGIC,
                                extracted.definition().adjustSpawn())
                        .build();
                final WorldManager manager = Sponge.server().worldManager();
                final CompletableFuture<WorldInfo> imported = manager.loadWorld(template)
                        .thenCompose(world -> this.configureWorld(world,
                                extracted.definition()))
                        .thenCompose(world -> manager.unloadWorld(world))
                        .thenApply(unloaded -> this.info(key))
                        .whenComplete((ignored, error) -> extracted.closeQuietly());
                imported.whenComplete((ignored, error) -> {
                    if (error == null) {
                        return;
                    }
                    manager.deleteWorld(key).whenComplete((deleted, cleanupError) -> {
                        try {
                            this.config.delete(name);
                        } catch (final IOException ignoredDelete) {
                            this.plugin.logger().warn(
                                    "Could not clean up failed import config for " + name,
                                    ignoredDelete);
                        }
                        if (cleanupError != null) {
                            this.plugin.logger().warn(
                                    "Could not clean up failed import world " + key,
                                    cleanupError);
                        }
                    });
                });
                return imported;
            } catch (final IOException | RuntimeException error) {
                extracted.closeQuietly();
                return failed(error);
            }
        });
    }

    private CompletableFuture<Path> writeExportAsync(final Path source, final String name,
            final WorldDefinition definition, final String template) {
        final Path target = this.archives.exportPath(name);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.archives.writeExport(source, name, definition, template, target);
            } catch (final IOException error) {
                throw new CompletionException(error);
            }
        });
    }

    private WorldInfo info(final ResourceKey key) {
        final Optional<ServerWorld> loaded = Sponge.server().worldManager().world(key);
        final String name = displayName(key);
        final WorldDefinition definition;
        try {
            definition = key.namespace().equals(MANAGED_NAMESPACE)
                    ? this.config.load(key.value()) : null;
        } catch (final IOException error) {
            throw new CompletionException(error);
        }
        final ResourceKey environment;
        if (loaded.isPresent()) {
            environment = Sponge.server().registry(RegistryTypes.WORLD_TYPE)
                    .findValueKey(loaded.get().worldType())
                    .orElseGet(() -> definition == null
                            ? environmentForKey(key) : definition.environment());
        } else {
            environment = definition == null ? environmentForKey(key)
                    : definition.environment();
        }
        final String generator = definition == null ? "normal"
                : definition.kind().name().toLowerCase(Locale.ROOT);
        return new WorldInfo(name, key, environment, generator, loaded.isPresent());
    }

    private ResolvedGameRule resolveGameRule(final String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Game rule name is required");
        }
        final String wanted = input.trim();
        for (final RegistryEntry<GameRule<?>> entry :
                Sponge.game().registry(RegistryTypes.GAME_RULE).streamEntries().toList()) {
            if ((wanted.contains(":") && entry.key().formatted().equalsIgnoreCase(wanted))
                    || (!wanted.contains(":") && entry.key().value().equalsIgnoreCase(wanted))
                    || entry.key().value().equalsIgnoreCase(wanted)
                    || compact(entry.key().value()).equals(compact(wanted))
                    || entry.value().name().equalsIgnoreCase(wanted)) {
                return new ResolvedGameRule(entry.key(), entry.value());
            }
        }
        throw new IllegalArgumentException("Unknown game rule: " + input);
    }

    private void validateGameRuleValue(final GameRule<?> rule, final String value) {
        final Type type = rule.valueType();
        if (type == Boolean.class) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Game rule requires true or false");
            }
        } else if (type == Integer.class) {
            try {
                Integer.parseInt(value);
            } catch (final NumberFormatException error) {
                throw new IllegalArgumentException("Game rule requires an integer", error);
            }
        } else {
            throw new IllegalArgumentException("Unsupported game rule type: " + type);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyGameRule(final ServerWorld world, final ResolvedGameRule rule,
            final String value) {
        this.validateGameRuleValue(rule.rule(), value);
        if (rule.rule().valueType() == Boolean.class) {
            this.setTypedGameRule(world,
                    (GameRule<Boolean>) rule.rule(), Boolean.parseBoolean(value));
        } else {
            this.setTypedGameRule(world,
                    (GameRule<Integer>) rule.rule(), Integer.parseInt(value));
        }
    }

    @SuppressWarnings("unchecked")
    private Object readGameRule(final ServerWorld world, final ResolvedGameRule rule) {
        if (rule.rule().valueType() == Boolean.class) {
            return this.readTypedGameRule(world, (GameRule<Boolean>) rule.rule());
        }
        return this.readTypedGameRule(world, (GameRule<Integer>) rule.rule());
    }

    private <V> void setTypedGameRule(final ServerWorld world, final GameRule<V> rule,
            final V value) {
        world.properties().setGameRule(rule, value);
    }

    private <V> V readTypedGameRule(final ServerWorld world, final GameRule<V> rule) {
        return world.properties().gameRule(rule);
    }

    private String gameRuleName(final ResourceKey key) {
        return key.value();
    }

    private String managedName(final String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("World name is required");
        }
        final String value = input.trim();
        final String name = value.startsWith(MANAGED_NAMESPACE + ":")
                ? value.substring(MANAGED_NAMESPACE.length() + 1) : value;
        if (name.contains(":")) {
            throw new IllegalArgumentException(
                    "Only worlds in the " + MANAGED_NAMESPACE + " namespace can be managed");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "World names must be 1-64 lowercase characters: a-z, 0-9, _, . or -");
        }
        if (isBuiltInName(name)) {
            throw new IllegalArgumentException("Built-in worlds cannot be modified by name: "
                    + name);
        }
        return name;
    }

    private ResourceKey keyForManagedName(final String input) {
        if (input.contains(":")) {
            final ResourceKey key;
            try {
                key = ResourceKey.resolve(input);
            } catch (final IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid world key: " + input, error);
            }
            if (!key.namespace().equals(MANAGED_NAMESPACE)) {
                throw new IllegalArgumentException("Unsupported world namespace: "
                        + key.namespace());
            }
            if (!VALID_NAME.matcher(key.value()).matches()) {
                throw new IllegalArgumentException("Invalid managed world name: "
                        + key.value());
            }
            return key;
        }
        return managedKey(this.managedName(input));
    }

    private static boolean isBuiltInName(final String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "world", "overworld", "dim-1", "the_nether", "dim1", "the_end" -> true;
            default -> false;
        };
    }

    private static String displayName(final ResourceKey key) {
        if (key.equals(DefaultWorldKeys.DEFAULT)) {
            return "world";
        }
        if (key.equals(DefaultWorldKeys.THE_NETHER)) {
            return "DIM-1";
        }
        if (key.equals(DefaultWorldKeys.THE_END)) {
            return "DIM1";
        }
        return key.value();
    }

    private static ResourceKey environmentForKey(final ResourceKey key) {
        if (key.equals(DefaultWorldKeys.THE_NETHER)) {
            return ResourceKey.minecraft("the_nether");
        }
        if (key.equals(DefaultWorldKeys.THE_END)) {
            return ResourceKey.minecraft("the_end");
        }
        return ResourceKey.minecraft("overworld");
    }

    private static String compact(final String value) {
        return value.toLowerCase(Locale.ROOT).replace("_", "");
    }

    private <T> CompletableFuture<T> serialized(
            final Supplier<CompletableFuture<T>> action) {
        synchronized (this.operationLock) {
            final CompletableFuture<T> result = this.operationTail.handle((ignored, error) -> null)
                    .thenCompose($ -> {
                        try {
                            return action.get();
                        } catch (final Throwable error) {
                            return failed(error);
                        }
                    });
            this.operationTail = result.handle((ignored, error) -> null);
            return result;
        }
    }

    private <T> CompletableFuture<T> onServer(
            final Callable<CompletableFuture<T>> action) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Sponge.server().scheduler().submit(Task.builder()
                    .plugin(this.plugin)
                    .execute(() -> {
                        try {
                            action.call().whenComplete((value, error) -> {
                                if (error != null) {
                                    result.completeExceptionally(rootCause(error));
                                } else {
                                    result.complete(value);
                                }
                            });
                        } catch (final Throwable error) {
                            result.completeExceptionally(rootCause(error));
                        }
                    })
                    .build());
        } catch (final Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    private static <T> CompletableFuture<T> failed(final Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    private static Throwable rootCause(final Throwable throwable) {
        Throwable result = throwable;
        while ((result instanceof CompletionException
                || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static void moveDirectory(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (final IOException atomicMoveFailure) {
            if (Files.exists(target)) {
                throw atomicMoveFailure;
            }
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (final Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                final Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        WorldArchiveService.deleteDirectory(source);
    }

    private record ImportRequest(Path archive, Path defaultWorld, String name, ResourceKey key) {
    }

    private record ResolvedGameRule(ResourceKey key, GameRule<?> rule) {
    }
}
