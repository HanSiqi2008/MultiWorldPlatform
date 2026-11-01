package org.multiworldplatform.config;

import org.multiworldplatform.api.MultiWorldPlatformApi.WorldKind;
import org.spongepowered.api.ResourceKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record WorldDefinition(String name, ResourceKey key, ResourceKey environment,
        WorldKind kind, long seed, boolean generateStructures, boolean adjustSpawn,
        Map<String, String> gameRules) {

    public WorldDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(kind, "kind");
        gameRules = Map.copyOf(new LinkedHashMap<>(gameRules));
    }

    public WorldDefinition withGameRule(final String name, final String value) {
        final Map<String, String> rules = new LinkedHashMap<>(this.gameRules);
        rules.put(name, value);
        return new WorldDefinition(this.name, this.key, this.environment, this.kind,
                this.seed, this.generateStructures, this.adjustSpawn, rules);
    }
}
