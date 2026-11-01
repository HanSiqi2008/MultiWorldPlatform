package org.multiworldplatform.api;

import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for MultiWorldPlatform.
 *
 * <p>Obtain the installed instance with {@code MultiWorldPlatform.api()}.
 * Every world operation is serialized by the plugin. World-manager and entity
 * work is moved onto the server thread, while archive and filesystem work is
 * performed asynchronously.</p>
 */
public interface MultiWorldPlatformApi {

    /**
     * Gets all built-in and managed worlds.
     */
    CompletableFuture<List<WorldInfo>> listWorlds();

    /**
     * Creates and loads a managed world.
     *
     * @param worldName the unqualified world name
     * @param environment the dimension environment
     * @param options creation options
     */
    CompletableFuture<WorldInfo> createWorld(String worldName, ResourceKey environment,
            CreateOptions options);

    /**
     * Imports a plugin-exported archive. The archive is searched for at
     * {@code world/<name>.zip} and then
     * {@code config/multiworldplatform/import/<name>.zip}.
     */
    CompletableFuture<WorldInfo> importWorld(String worldName);

    /**
     * Exports a world into
     * {@code config/multiworldplatform/export/<name>.zip}.
     */
    CompletableFuture<Path> exportWorld(String worldName);

    /**
     * Loads an existing managed world.
     */
    CompletableFuture<WorldInfo> loadWorld(String worldName);

    /**
     * Transfers all players out and unloads a managed world.
     *
     * @param worldName world to unload
     * @param destinationWorld destination world, or null/blank for the
     *     default overworld
     */
    CompletableFuture<Boolean> unloadWorld(String worldName, String destinationWorld);

    /**
     * Deletes an unloaded managed world. If {@code autoUnload} is true, players
     * are transferred to the overworld and the world is unloaded first.
     */
    CompletableFuture<Boolean> deleteWorld(String worldName, boolean autoUnload);

    /**
     * Teleports a player to a loaded managed or built-in world.
     */
    CompletableFuture<Boolean> teleport(ServerPlayer player, String worldName);

    /**
     * Persists and, if the world is loaded, applies a game rule.
     */
    CompletableFuture<Void> setGameRule(String worldName, String ruleName, String value);

    /**
     * Gets a configured game rule value. The current world value is returned
     * when the world is loaded.
     */
    CompletableFuture<Optional<String>> getGameRule(String worldName, String ruleName);

    /**
     * Resolves a command/API world name to its namespaced world key.
     */
    ResourceKey keyForName(String worldName);

    enum WorldKind {
        NORMAL,
        FLAT,
        VOID
    }

    record WorldInfo(String name, ResourceKey key, ResourceKey environment, String generator,
            boolean loaded) {

        public WorldInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(generator, "generator");
        }
    }

    final class CreateOptions {

        private final Long seed;
        private final WorldKind kind;
        private final boolean generateStructures;
        private final boolean adjustSpawn;

        private CreateOptions(final Long seed, final WorldKind kind,
                final boolean generateStructures, final boolean adjustSpawn) {
            this.seed = seed;
            this.kind = Objects.requireNonNull(kind, "kind");
            this.generateStructures = generateStructures;
            this.adjustSpawn = adjustSpawn;
        }

        public static CreateOptions defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public Optional<Long> seed() {
            return Optional.ofNullable(this.seed);
        }

        public WorldKind kind() {
            return this.kind;
        }

        public boolean generateStructures() {
            return this.generateStructures;
        }

        public boolean adjustSpawn() {
            return this.adjustSpawn;
        }

        public static final class Builder {

            private Long seed;
            private WorldKind kind = WorldKind.NORMAL;
            private boolean generateStructures = true;
            private boolean adjustSpawn = true;

            public Builder seed(final Long seed) {
                this.seed = seed;
                return this;
            }

            public Builder kind(final WorldKind kind) {
                this.kind = Objects.requireNonNull(kind, "kind");
                return this;
            }

            public Builder generateStructures(final boolean generateStructures) {
                this.generateStructures = generateStructures;
                return this;
            }

            public Builder adjustSpawn(final boolean adjustSpawn) {
                this.adjustSpawn = adjustSpawn;
                return this;
            }

            public CreateOptions build() {
                return new CreateOptions(this.seed, this.kind, this.generateStructures,
                        this.adjustSpawn);
            }
        }
    }
}
