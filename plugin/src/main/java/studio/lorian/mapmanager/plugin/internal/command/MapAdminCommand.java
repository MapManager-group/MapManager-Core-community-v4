package studio.lorian.mapmanager.plugin.internal.command;

import org.bukkit.command.*;
import org.bukkit.Bukkit;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.plugin.internal.migration.V3Migration;
import studio.lorian.mapmanager.plugin.internal.notification.*;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** 控制台与 RCON 维护入口；世界恢复在主线程，报告和备份在异步线程执行。 */
public final class MapAdminCommand extends Command {
    private static final String CONFIRM_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long DELETE_CONFIRM_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private record PendingDeletion(UUID mapId, String worldName, String code, long issuedAtNanos) {}
    private PendingDeletion pendingDeletion;
    private final PlayerGateway players;
    private final Pattern deletionPattern;
    private final JavaPlugin plugin;
    private final CachedStorage storage;
    private final WorldLifeCycleService lifecycle;
    private final V3Migration migration;
    private final OperationNotifier notifier;
    private final Runnable afterMigration;
    public MapAdminCommand(JavaPlugin plugin, CachedStorage storage, WorldLifeCycleService lifecycle,
                           V3Migration migration, OperationNotifier notifier, Runnable afterMigration, PlayerGateway players, Pattern deletionPattern) {
        super("mapadmin"); this.plugin = plugin; this.storage = storage; this.lifecycle = lifecycle;
        this.players = players; this.deletionPattern = deletionPattern;
        setUsage(Messages.text("maintenance.usage"));
        this.migration = migration; this.notifier = notifier; this.afterMigration = afterMigration;
    }
    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage(Messages.component("reasons.NO_PERMISSION")); return true;
        }
        NotificationTarget target = NotificationTarget.console();
        try {
            if (args.length > 0 && args[0].equalsIgnoreCase("create")) create(sender, target, args);
            else if (args.length > 0 && args[0].equalsIgnoreCase("import")) importWorld(sender, target, args);
            else if (args.length > 0 && args[0].equalsIgnoreCase("init")) initialize(sender, target, args);
            else if (args.length > 0 && args[0].equalsIgnoreCase("delete")) delete(sender, target, args);
            else if (args.length == 2 && args[0].equalsIgnoreCase("recover")) notifier.notify(target, lifecycle.recover(args[1]));
            else if (args.length == 2 && args[0].equalsIgnoreCase("load")) notifier.notify(target, lifecycle.load(args[1]));
            else if (args.length == 1 && args[0].equalsIgnoreCase("operations")) {
                complete(CompletableFuture.supplyAsync(() -> storage.allOperations().stream()
                        .filter(record -> record.stage() != OperationStage.COMPLETED)
                        .map(record -> Messages.text("maintenance.operation_entry", "id", record.id(), "world", record.worldName(),
                                "operation", Messages.text("operations." + record.name()), "stage", record.stage(),
                                "reason", record.reason() == null ? "-" : Messages.text("reasons." + record.reason())))
                        .collect(java.util.stream.Collectors.joining("\n"))), false);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("migration")
                    && Set.of("dry-run", "apply").contains(args[1])) {
                complete(migration.migrate(args[1].equals("dry-run")), args[1].equals("apply"));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("storage") && args[1].equals("export")
                    && args[2].matches("[a-zA-Z0-9_-]{1,64}")) {
                Path file = plugin.getDataFolder().toPath().resolve("exports").resolve(args[2] + ".json");
                complete(CompletableFuture.supplyAsync(() -> {
                    try {
                        synchronized (storage) { StorageSnapshot.capture(storage, storage, storage).write(file); }
                        return Messages.text("maintenance.exported", "file", file);
                    } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                }), false);
            } else notifier.notifyKey(target, "maintenance.usage");
        } catch (RuntimeException error) { notifier.notifyKey(target, "maintenance.failed", "error", error.getMessage()); }
        return true;
    }
    private void complete(CompletableFuture<String> future, boolean refresh) {
        future.whenComplete((message, error) -> {
            if (!plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) notifier.notifyKey(NotificationTarget.console(), "maintenance.failed", "error", error.getMessage());
                else {
                    if (refresh) afterMigration.run();
                    notifier.notify(NotificationTarget.console(), message.isBlank() ? Messages.text("maintenance.no_operations") : message);
                }
            });
        });
    }
    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if ((!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender))
                || args.length == 0) return List.of();
        String command = args[0].toLowerCase(Locale.ROOT);
        String current = args[args.length - 1];
        try {
            if (args.length == 1) return suggestions(current, List.of("create", "import", "init", "delete", "load", "recover", "operations", "migration", "storage"));
            return switch (command) {
                case "create" -> creationSuggestions(command, args);
                case "import" -> args.length == 2 ? suggestions(current, importableWorldNames()) : creationSuggestions(command, args);
                case "delete" -> args.length == 2 ? suggestions(current, storage.findAll().stream()
                        .filter(map -> map.status() == MapStatus.ACTIVE || map.status() == MapStatus.DELETING)
                        .filter(map -> deletionPattern.matcher(map.worldName()).matches()).map(MapInstance::worldName).toList()) : List.of();
                case "init", "load", "recover" -> args.length == 2 ? suggestions(current, storage.findAll().stream().map(MapInstance::worldName).toList()) : List.of();
                case "migration" -> args.length == 2 ? suggestions(current, List.of("dry-run", "apply")) : List.of();
                case "storage" -> args.length == 2 ? suggestions(current, List.of("export")) : List.of();
                default -> List.of();
            };
        } catch (StorageException error) { return List.of(); }
    }

    private static List<String> importableWorldNames() {
        try (Stream<Path> entries = Files.list(Path.of("").toAbsolutePath())) {
            return entries.filter(path -> !Files.isSymbolicLink(path) && Files.isDirectory(path))
                    .filter(path -> Files.isRegularFile(path.resolve("level.dat")))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("[a-z0-9_-]{4,16}"))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private List<String> creationSuggestions(String command, String[] args) {
        boolean creating = command.equals("create");
        String current = args[args.length - 1];
        if (args.length == 3 && !current.contains("=")) {
            List<String> types = Arrays.stream(WorldType.values())
                    .map(type -> type.name().toLowerCase(Locale.ROOT)).toList();
            if (creating) return suggestions(current, types);
            List<String> choices = new ArrayList<>(types);
            choices.addAll(optionKeys(false, args));
            return suggestions(current, choices);
        }
        if (args.length == 4 && !current.contains("=") && isWorldType(args[2])) {
            List<String> choices = Arrays.stream(Environment.values())
                    .map(environment -> environment.name().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            choices.addAll(optionKeys(creating, args));
            return suggestions(current, choices);
        }
        if (args.length < 3) return List.of();
        if (current.startsWith("group=")) {
            List<String> choices = new ArrayList<>();
            choices.add("group=");
            storage.findAll().stream().map(MapInstance::groupId).distinct()
                    .forEach(group -> choices.add("group=" + group));
            return suggestions(current, choices);
        }
        if (current.startsWith("admin="))
            return suggestions(current, Bukkit.getOnlinePlayers().stream()
                    .map(player -> "admin=" + player.getName()).toList());
        if (creating && current.startsWith("structures="))
            return suggestions(current, List.of("structures=true", "structures=false"));
        return suggestions(current, optionKeys(creating, args));
    }

    private static boolean isWorldType(String value) {
        try {
            WorldType.valueOf(value.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static List<String> optionKeys(boolean creating, String[] args) {
        List<String> keys = new ArrayList<>(List.of("alias=", "group=", "admin="));
        if (creating) keys.addAll(List.of("flat.height=", "seed=", "structures=", "biome=",
                "generator=", "generator-settings=", "property."));
        Set<String> used = new HashSet<>();
        for (int index = 2; index < args.length - 1; index++) {
            int separator = args[index].indexOf('=');
            if (separator > 0) used.add(args[index].substring(0, separator));
        }
        keys.removeIf(key -> used.contains(key.substring(0, key.length() - 1)));
        return keys;
    }

    private static List<String> suggestions(String prefix, Collection<String> choices) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void create(CommandSender sender, NotificationTarget target, String[] args) {
        createOrImport(sender, target, args, false);
    }

    private void importWorld(CommandSender sender, NotificationTarget target, String[] args) {
        createOrImport(sender, target, args, true);
    }

    private void createOrImport(CommandSender sender, NotificationTarget target, String[] args, boolean importing) {
        OperationName operation = importing ? OperationName.IMPORT : OperationName.CREATE;
        if (args.length < 2) {
            fail(target, operation, FailureReason.INVALID_INPUT);
            return;
        }

        int optionsStart = 2;
        WorldType type = WorldType.FLAT;
        Environment environment = Environment.NORMAL;
        if (args.length > 2 && !args[2].contains("=")) {
            try {
                type = WorldType.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                fail(target, operation, FailureReason.INVALID_INPUT);
                return;
            }
            optionsStart = 3;
            if (args.length > 3 && !args[3].contains("=")) {
                try {
                    environment = Environment.valueOf(args[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    fail(target, operation, FailureReason.INVALID_INPUT);
                    return;
                }
                optionsStart = 4;
            }
        }

        String alias = null;
        String group = null;
        String admin = null;
        Map<String, String> parameters = new HashMap<>();
        for (int i = optionsStart; i < args.length; i++) {
            int separator = args[i].indexOf('=');
            if (separator < 1 || separator == args[i].length() - 1) {
                fail(target, operation, FailureReason.INVALID_INPUT);
                return;
            }
            String key = args[i].substring(0, separator);
            String value = args[i].substring(separator + 1);
            switch (key) {
                case "alias" -> {
                    if (alias != null) {
                        fail(target, operation, FailureReason.INVALID_INPUT);
                        return;
                    }
                    alias = value;
                }
                case "group" -> {
                    if (group != null) {
                        fail(target, operation, FailureReason.INVALID_INPUT);
                        return;
                    }
                    group = value;
                }
                case "admin" -> {
                    if (admin != null) {
                        fail(target, operation, FailureReason.INVALID_INPUT);
                        return;
                    }
                    admin = value;
                }
                default -> {
                    if (importing || parameters.putIfAbsent(key, value) != null) {
                        fail(target, operation, FailureReason.INVALID_INPUT);
                        return;
                    }
                }
            }
        }

        String worldName = args[1];
        String displayName = alias;
        String groupId = group;
        WorldType worldType = type;
        Environment worldEnvironment = environment;
        Map<String, String> creationParameters = Map.copyOf(parameters);
        if (admin == null) {
            runCreateOrImport(target, importing, worldName, displayName, groupId, null,
                    worldType, worldEnvironment, creationParameters);
            return;
        }
        resolveMemberId(admin).whenComplete((owner, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || owner == null) {
                fail(target, operation, FailureReason.PLAYER_NOT_FOUND);
                return;
            }
            runCreateOrImport(target, importing, worldName, displayName, groupId, owner,
                    worldType, worldEnvironment, creationParameters);
        }));
    }

    private void runCreateOrImport(NotificationTarget target, boolean importing,
                                   String worldName, String displayName, String groupId, UUID owner,
                                   WorldType type, Environment environment, Map<String, String> parameters) {
        OperationResult<MapInstance> result = importing
                ? lifecycle.importWorld(new ImportMapRequest(worldName, displayName, groupId, owner, type, environment))
                : lifecycle.create(new CreateMapRequest(worldName, displayName, groupId, owner,
                        type, environment, parameters));
        notifier.notify(target, result);
    }

    private void initialize(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length != 2) {
            fail(target, OperationName.INITIALIZE, FailureReason.INVALID_INPUT);
            return;
        }
        notifier.notify(target, lifecycle.initialize(args[1]));
    }

    /** 控制台先选地图，再用十秒内有效的一次性确认码执行删除。 */
    private void delete(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length != 2) {
            fail(target, OperationName.DELETE, FailureReason.INVALID_INPUT);
            notifier.notifyKey(target, "command.delete_usage");
            return;
        }
        String input = args[1];
        PendingDeletion pending = pendingDeletion;
        if (pending != null && pending.code().equals(input)) {
            pendingDeletion = null;
            if (System.nanoTime() - pending.issuedAtNanos() >= DELETE_CONFIRM_NANOS) {
                fail(target, OperationName.DELETE, FailureReason.INVALID_INPUT);
                notifier.notifyKey(target, "command.delete_expired");
                return;
            }
            try {
                MapInstance current = storage.findByWorldName(pending.worldName());
                if (current == null || !current.id().equals(pending.mapId())
                        || !deletionPattern.matcher(current.worldName()).matches()) {
                    fail(target, OperationName.DELETE, FailureReason.INVALID_INPUT);
                    return;
                }
                notifier.notify(target, lifecycle.delete(current.worldName()));
            } catch (StorageException exception) {
                fail(target, OperationName.DELETE, FailureReason.STORAGE_FAILED);
            }
            return;
        }
        if (pending != null) {
            if (System.nanoTime() - pending.issuedAtNanos() < DELETE_CONFIRM_NANOS) {
                fail(target, OperationName.DELETE, FailureReason.INVALID_INPUT);
                notifier.notifyKey(target, "command.delete_wrong_code");
                return;
            }
            pendingDeletion = null;
        }
        if (!deletionPattern.matcher(input).matches()) {
            fail(target, OperationName.DELETE, FailureReason.ACCESS_DENIED);
            notifier.notifyKey(target, "command.delete_name_denied");
            return;
        }
        try {
            MapInstance map = storage.findByWorldName(input);
            if (map == null) {
                fail(target, OperationName.DELETE, FailureReason.MAP_NOT_FOUND);
                return;
            }
            if (map.status() != MapStatus.ACTIVE && map.status() != MapStatus.DELETING) {
                fail(target, OperationName.DELETE, FailureReason.OPERATION_IN_PROGRESS);
                return;
            }
            char[] chars = new char[6];
            for (int i = 0; i < chars.length; i++)
                chars[i] = CONFIRM_CHARACTERS.charAt(RANDOM.nextInt(CONFIRM_CHARACTERS.length()));
            String code = new String(chars);
            pendingDeletion = new PendingDeletion(map.id(), map.worldName(), code, System.nanoTime());
            notifier.notifyKey(target, "command.delete_confirm", "world", map.worldName(), "code", code);
        } catch (StorageException exception) {
            fail(target, OperationName.DELETE, FailureReason.STORAGE_FAILED);
        }
    }

    private CompletableFuture<UUID> resolveMemberId(String input) {
        try {
            return CompletableFuture.completedFuture(UUID.fromString(input));
        } catch (IllegalArgumentException exception) {
            return players.resolveId(input);
        }
    }

    private void fail(NotificationTarget target, OperationName operation, FailureReason reason) {
        notifier.notify(target, new OperationResult.Failure<>(operation, UUID.randomUUID(), reason));
    }
}
