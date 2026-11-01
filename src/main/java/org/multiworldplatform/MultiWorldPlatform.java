package org.multiworldplatform;

import org.multiworldplatform.api.MultiWorldPlatformApi;

import java.util.Optional;

/**
 * Static entry point for dependants.
 *
 * <pre>{@code
 * MultiWorldPlatform.api().loadWorld("arena_01")
 *     .thenAccept(info -> ...);
 * }</pre>
 */
public final class MultiWorldPlatform {

    private static volatile MultiWorldPlatformApi api;

    private MultiWorldPlatform() {
    }

    public static MultiWorldPlatformApi api() {
        final MultiWorldPlatformApi current = api;
        if (current == null) {
            throw new IllegalStateException(
                    "MultiWorldPlatform is not loaded or has not started yet");
        }
        return current;
    }

    public static Optional<MultiWorldPlatformApi> apiIfReady() {
        return Optional.ofNullable(api);
    }

    static void install(final MultiWorldPlatformApi implementation) {
        api = implementation;
    }

    static void uninstall(final MultiWorldPlatformApi implementation) {
        if (api == implementation) {
            api = null;
        }
    }
}
