package org.multiworldplatform.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.multiworldplatform.api.MultiWorldPlatformApi;
import org.multiworldplatform.api.MultiWorldPlatformApi.CreateOptions;
import org.multiworldplatform.api.MultiWorldPlatformApi.WorldInfo;
import org.multiworldplatform.api.MultiWorldPlatformApi.WorldKind;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.SystemSubject;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.exception.CommandException;
import org.spongepowered.api.command.parameter.ArgumentReader;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;

/**
 * Raw command entry point. Raw parsing keeps all flags in the exact syntax
 * requested by the specification and makes {@code /mwp} and {@code /cwp}
 * interchangeable.
 */
public final class WorldCommand implements Command.Raw {

    private static final List<String> SUBCOMMANDS = List.of(
            "list", "create", "import", "export", "load", "unload", "destroy", "tp",
            "gamerule");

    private final MultiWorldPlatformApi api;

    public WorldCommand(final MultiWorldPlatformApi api) {
        this.api = api;
    }

    @Override
    public CommandResult process(final CommandCause cause,
            final ArgumentReader.Mutable arguments) throws CommandException {
        final String input = arguments.remaining().trim();
        if (input.isEmpty()) {
            cause.audience().sendMessage(this.help());
            return CommandResult.success();
        }
        final String[] tokens = input.split("\\s+");
        final String subcommand = tokens[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "list" -> this.list(cause, tokens);
            case "create" -> this.create(cause, tokens);
            case "import" -> this.importWorld(cause, tokens);
            case "export" -> this.export(cause, tokens);
            case "load" -> this.load(cause, tokens);
            case "unload" -> this.unload(cause, tokens);
            case "destroy" -> this.destroy(cause, tokens);
            case "tp" -> this.teleport(cause, tokens);
            case "gamerule" -> this.gameRule(cause, tokens);
            default -> {
                cause.audience().sendMessage(Component.text("Unknown subcommand: " + subcommand,
                        NamedTextColor.RED));
                for (final String line : this.helpLines()) {
                    cause.audience().sendMessage(Component.text(line));
                }
                yield CommandResult.success();
            }
        };
    }

    private CommandResult list(final CommandCause cause, final String[] tokens) {
        if (tokens.length != 1) {
            return usage(cause, "/mwp list");
        }
        if (!this.allowed(cause, "list")) {
            return denied(cause);
        }
        this.complete(cause, this.api.listWorlds(), (worlds, error) -> {
            if (error != null) {
                return;
            }
            cause.audience().sendMessage(Component.text("[MultiWorldPlatform]"));
            for (final WorldInfo world : worlds) {
                cause.audience().sendMessage(Component.text("- " + world.name() + ": "
                        + world.key().formatted()
                        + " [" + (world.loaded() ? "loaded" : "unloaded") + ", "
                        + world.generator() + "]"));
            }
        });
        return CommandResult.success();
    }

    private CommandResult create(final CommandCause cause, final String[] tokens) {
        if (!this.allowed(cause, "create")) {
            return denied(cause);
        }
        if (tokens.length < 3) {
            return usage(cause,
                    "/mwp create <world_name> <environment> [--seed=<int>] "
                            + "[--world-type=normal|flat|void] [--no-structures] "
                            + "[--no-adjust-spawn]");
        }
        final CreateOptions.Builder options = CreateOptions.builder();
        try {
            final ResourceKey environment = this.api.keyForName(tokens[2]);
            if (!environment.equals(ResourceKey.minecraft("overworld"))
                    && !environment.equals(ResourceKey.minecraft("the_nether"))
                    && !environment.equals(ResourceKey.minecraft("the_end"))) {
                throw new IllegalArgumentException("Unsupported environment: " + tokens[2]);
            }
            for (int index = 3; index < tokens.length; index++) {
                final String token = tokens[index];
                if (token.startsWith("--seed=")) {
                    options.seed(Long.parseLong(token.substring("--seed=".length())));
                } else if (token.startsWith("--world-type=")) {
                    options.kind(WorldKind.valueOf(
                            token.substring("--world-type=".length()).toUpperCase(Locale.ROOT)));
                } else if (token.equals("--no-structures")) {
                    options.generateStructures(false);
                } else if (token.equals("--no-adjust-spawn")) {
                    options.adjustSpawn(false);
                } else {
                    throw new IllegalArgumentException("Unknown create option: " + token);
                }
            }
            final CompletableFuture<WorldInfo> created = this.api.createWorld(tokens[1],
                    environment, options.build());
            this.complete(cause, created, (world, error) -> {
                if (error == null) {
                    cause.audience().sendMessage(Component.text("Created and loaded "
                            + world.key().formatted(), NamedTextColor.GREEN));
                }
            });
            return CommandResult.success();
        } catch (final IllegalArgumentException error) {
            cause.audience().sendMessage(Component.text(error.getMessage(), NamedTextColor.RED));
            return CommandResult.error(Component.text(error.getMessage()));
        }
    }

    private CommandResult importWorld(final CommandCause cause, final String[] tokens) {
        if (!this.allowed(cause, "import")) {
            return denied(cause);
        }
        if (tokens.length != 2) {
            return usage(cause, "/mwp import <world_name>");
        }
        this.complete(cause, this.api.importWorld(tokens[1]), (world, error) -> {
            if (error == null) {
                cause.audience().sendMessage(Component.text("Imported "
                        + world.key().formatted() + " (unloaded)", NamedTextColor.GREEN));
            }
        });
        return CommandResult.success();
    }

    private CommandResult export(final CommandCause cause, final String[] tokens) {
        if (!this.allowed(cause, "export")) {
            return denied(cause);
        }
        if (tokens.length != 2) {
            return usage(cause, "/mwp export <world_name>");
        }
        this.complete(cause, this.api.exportWorld(tokens[1]), (path, error) -> {
            if (error == null) {
                cause.audience().sendMessage(Component.text("Exported to " + path,
                        NamedTextColor.GREEN));
            }
        });
        return CommandResult.success();
    }

    private CommandResult load(final CommandCause cause, final String[] tokens) {
        if (!this.allowed(cause, "load")) {
            return denied(cause);
        }
        if (tokens.length != 2) {
            return usage(cause, "/mwp load <world_name>");
        }
        this.complete(cause, this.api.loadWorld(tokens[1]), (world, error) -> {
            if (error == null) {
                cause.audience().sendMessage(Component.text("Loaded " + world.key().formatted(),
                        NamedTextColor.GREEN));
            }
        });
        return CommandResult.success();
    }

    private CommandResult unload(final CommandCause cause, final String[] tokens) {
        if (!this.allowed(cause, "unload")) {
            return denied(cause);
        }
        if (tokens.length < 2) {
            return usage(cause, "/mwp unload <world_name> [--tp-player=<world_name>]");
        }
        String destination = null;
        for (int index = 2; index < tokens.length; index++) {
            if (tokens[index].startsWith("--tp-player=")) {
                destination = tokens[index].substring("--tp-player=".length());
            } else {
                return usage(cause, "/mwp unload <world_name> [--tp-player=<world_name>]");
            }
        }
        final String target = destination;
        this.complete(cause, this.api.unloadWorld(tokens[1], target), (unloaded, error) -> {
            if (error == null) {
                cause.audience().sendMessage(Component.text(unloaded
                        ? "World unloaded; players were transferred."
                        : "World was not unloaded.", unloaded
                        ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
        });
        return CommandResult.success();
    }

    private CommandResult destroy(final CommandCause cause, final String[] tokens) {
        if (!this.allowed(cause, "destroy")) {
            return denied(cause);
        }
        if (tokens.length < 2 || tokens.length > 3
                || (tokens.length == 3 && !tokens[2].equals("--auto"))) {
            return usage(cause, "/mwp destroy <world_name> [--auto]");
        }
        this.complete(cause, this.api.deleteWorld(tokens[1], tokens.length == 3),
                (deleted, error) -> {
                    if (error == null) {
                        cause.audience().sendMessage(Component.text(deleted
                                ? "World deleted permanently."
                                : "World was not deleted.", deleted
                                ? NamedTextColor.GREEN : NamedTextColor.RED));
                    }
                });
        return CommandResult.success();
    }

    private CommandResult teleport(final CommandCause cause, final String[] tokens) {
        if (!this.allowed(cause, "tp")) {
            return denied(cause);
        }
        if (tokens.length < 2 || tokens.length > 3) {
            return usage(cause, "/mwp tp <world_name> [player]");
        }
        final ServerPlayer player;
        if (tokens.length == 2) {
            player = cause.root() instanceof ServerPlayer root ? root : null;
        } else {
            if (!this.allowed(cause, "tp.other")) {
                return denied(cause);
            }
            player = Sponge.server().player(tokens[2]).orElse(null);
        }
        if (player == null) {
            return usage(cause, "/mwp tp <world_name> <player>");
        }
        final String playerName = player.name();
        this.complete(cause, this.api.teleport(player, tokens[1]), (teleported, error) -> {
            if (error == null) {
                cause.audience().sendMessage(Component.text(
                        "Teleported " + playerName + " to " + tokens[1], NamedTextColor.GREEN));
            }
        });
        return CommandResult.success();
    }

    private CommandResult gameRule(final CommandCause cause, final String[] tokens) {
        if (!this.allowed(cause, "gamerule")) {
            return denied(cause);
        }
        if (tokens.length == 3) {
            this.complete(cause, this.api.getGameRule(tokens[1], tokens[2]),
                    (value, error) -> {
                        if (error == null) {
                            cause.audience().sendMessage(Component.text(tokens[2] + " = "
                                    + value.orElse("<not configured>")));
                        }
                    });
            return CommandResult.success();
        }
        if (tokens.length != 4) {
            return usage(cause, "/mwp gamerule <world_name> <rule> [value]");
        }
        this.complete(cause, this.api.setGameRule(tokens[1], tokens[2], tokens[3]),
                (ignored, error) -> {
                    if (error == null) {
                        cause.audience().sendMessage(Component.text(
                                "Saved game rule " + tokens[2] + "=" + tokens[3],
                                NamedTextColor.GREEN));
                    }
                });
        return CommandResult.success();
    }

    private boolean allowed(final CommandCause cause, final String operation) {
        if (cause.subject() instanceof SystemSubject) {
            return true;
        }
        if (Sponge.pluginManager().plugin("luckperms").isPresent()) {
            return cause.subject().hasPermission("multiworldplatform." + operation)
                    || cause.subject().hasPermission("commonworldplatform." + operation);
        }
        return cause.subject().hasPermission("multiworldplatform." + operation)
                || cause.subject().hasPermission("commonworldplatform." + operation)
                || cause.subject().hasPermission("minecraft.command.op");
    }

    private <T> void complete(final CommandCause cause, final CompletableFuture<T> future,
            final BiConsumer<T, Throwable> completion) {
        future.whenComplete((value, error) -> {
            final Throwable causeError = error == null ? null : rootCause(error);
            if (causeError != null) {
                cause.audience().sendMessage(Component.text(
                        "MultiWorldPlatform: " + causeError.getMessage(), NamedTextColor.RED));
            }
            try {
                completion.accept(value, causeError);
            } catch (final RuntimeException completionError) {
                cause.audience().sendMessage(Component.text(
                        "MultiWorldPlatform: " + completionError.getMessage(),
                        NamedTextColor.RED));
            }
        });
    }

    private static CommandResult denied(final CommandCause cause) {
        cause.audience().sendMessage(Component.text("You do not have permission.",
                NamedTextColor.RED));
        return CommandResult.error(Component.text("Permission denied"));
    }

    private static CommandResult usage(final CommandCause cause, final String usage) {
        cause.audience().sendMessage(Component.text("Usage: " + usage, NamedTextColor.YELLOW));
        return CommandResult.error(Component.text(usage));
    }

    @Override
    public List<CommandCompletion> complete(final CommandCause cause,
            final ArgumentReader.Mutable arguments) {
        final String input = arguments.remaining();
        final List<String> words = new ArrayList<>(Arrays.asList(input.trim().split("\\s+")));
        if (words.size() <= 1 && !input.endsWith(" ")) {
            return SUBCOMMANDS.stream()
                    .filter(value -> value.startsWith(words.isEmpty() ? "" : words.get(0)))
                    .map(CommandCompletion::of).toList();
        }
        return List.of();
    }

    @Override
    public boolean canExecute(final CommandCause cause) {
        return true;
    }

    @Override
    public Optional<Component> shortDescription(final CommandCause cause) {
        return Optional.of(Component.text("Manage MultiWorldPlatform worlds"));
    }

    @Override
    public Optional<Component> extendedDescription(final CommandCause cause) {
        return Optional.of(this.help());
    }

    @Override
    public Component usage(final CommandCause cause) {
        return this.help();
    }

    private Component help() {
        final var result = Component.text();
        for (final String line : this.helpLines()) {
            result.append(Component.text(line), Component.newline());
        }
        return result.build();
    }

    private List<String> helpLines() {
        return List.of(
                "/mwp list",
                "/mwp create <world_name> <environment> [--seed=<int>] "
                        + "[--world-type=normal|flat|void] [--no-structures] "
                        + "[--no-adjust-spawn]",
                "/mwp import <world_name>",
                "/mwp export <world_name>",
                "/mwp load <world_name>",
                "/mwp unload <world_name> [--tp-player=<world_name>]",
                "/mwp destroy <world_name> [--auto]",
                "/mwp tp <world_name> [player]",
                "/mwp gamerule <world_name> <rule> [value]",
                "/cwp is a compatible alias");
    }

    private static Throwable rootCause(final Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null && result.getCause() != result
                && (result instanceof CompletionException
                        || result instanceof java.util.concurrent.ExecutionException)) {
            result = result.getCause();
        }
        return result;
    }
}
