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

import static studio.lorian.mapmanager.plugin.internal.command.MapWorldCommand.suggestions;

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
            String command = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            switch (command) {
                case "create", "import" -> createOrImport(target, args, command.equals("import"));
                case "init" -> initialize(target, args);
                case "delete" -> delete(target, args);
                case "load", "recover" -> loadOrRecover(target, args, command.equals("recover"));
                case "operations" -> listOperations(target, args);
                case "migration" -> migrate(target, args);
                case "storage" -> export(target, args);
                case "help" -> showHelp(target, args);
                default -> notifier.notifyKey(target, "maintenance.usage");
            }
        } catch (RuntimeException error) { notifier.notifyKey(target, "maintenance.failed", "error", error.getMessage()); }
        return true;
    }

    private void loadOrRecover(NotificationTarget target, String[] args, boolean recovering) {
        if (args.length != 2) {
            notifier.notifyKey(target, "maintenance.usage");
            return;
        }
        notifier.notify(target, recovering ? lifecycle.recover(args[1]) : lifecycle.load(args[1]));
    }

    private void listOperations(NotificationTarget target, String[] args) {
        if (args.length != 1) {
            notifier.notifyKey(target, "maintenance.usage");
            return;
        }
        complete(CompletableFuture.supplyAsync(this::operationReport), false);
    }

    private String operationReport() {
        return storage.allOperations().stream().filter(record -> record.stage() != OperationStage.COMPLETED)
                .map(record -> Messages.text("maintenance.operation_entry", "id", record.id(), "world", record.worldName(),
                        "operation", Messages.text("operations." + record.name()), "stage", record.stage(),
                        "reason", record.reason() == null ? "-" : Messages.text("reasons." + record.reason())))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void migrate(NotificationTarget target, String[] args) {
        if (args.length != 2 || !Set.of("dry-run", "apply").contains(args[1])) {
            notifier.notifyKey(target, "maintenance.usage");
            return;
        }
        complete(migration.migrate(args[1].equals("dry-run")), args[1].equals("apply"));
    }

    private void export(NotificationTarget target, String[] args) {
        if (args.length != 3 || !args[1].equals("export") || !args[2].matches("[a-zA-Z0-9_-]{1,64}")) {
            notifier.notifyKey(target, "maintenance.usage");
            return;
        }
        Path file = plugin.getDataFolder().toPath().resolve("exports").resolve(args[2] + ".json");
        complete(CompletableFuture.supplyAsync(() -> {
            try {
                synchronized (storage) { StorageSnapshot.capture(storage, storage, storage).write(file); }
                return Messages.text("maintenance.exported", "file", file);
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }), false);
    }

    private void showHelp(NotificationTarget target, String[] args) {
        List<String> commands = List.of("create", "import", "init", "delete", "load", "recover", "operations", "migration", "storage");
        String topic = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        notifier.notifyKey(target, "maintenance.help_header");
        for (String command : commands.contains(topic) ? List.of(topic) : commands)
            notifier.notifyKey(target, "maintenance.help_" + command);
        if (!commands.contains(topic) || topic.equals("create") || topic.equals("import"))
            notifier.notifyKey(target, "maintenance.help_options");
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
            if (args.length == 1) return suggestions(current, List.of("create", "import", "init", "delete", "load", "recover", "operations", "migration", "storage", "help"));
            return switch (command) {
                case "create" -> creationSuggestions(command, args);
                case "import" -> args.length == 2 ? suggestions(current, importableWorldNames()) : creationSuggestions(command, args);
                case "delete" -> args.length == 2 ? suggestions(current, storage.findAll().stream()
                        .filter(map -> map.status() == MapStatus.ACTIVE || map.status() == MapStatus.DELETING)
                        .filter(map -> deletionPattern.matcher(map.worldName()).matches()).map(MapInstance::worldName).toList()) : List.of();
                case "init", "load" -> args.length == 2 ? suggestions(current, storage.findAll().stream()
                        .filter(map -> !command.equals("load") || map.status() == MapStatus.ACTIVE)
                        .map(MapInstance::worldName).toList()) : List.of();
                case "recover" -> args.length == 2 ? suggestions(current, recoveryWorldNames(plugin.getDataFolder().toPath().resolve("world-recovery"))) : List.of();
                case "help" -> args.length == 2 ? suggestions(current, List.of("create", "import", "init", "delete", "load", "recover", "operations", "migration", "storage")) : List.of();
                case "migration" -> args.length == 2 ? suggestions(current, List.of("dry-run", "apply")) : List.of();
                case "storage" -> args.length == 2 ? suggestions(current, List.of("export")) : List.of();
                default -> List.of();
            };
        } catch (StorageException error) { return List.of(); }
    }

    static List<String> recoveryWorldNames(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("[A-Za-z0-9_-]{1,100}\\.json"))
                    .map(name -> name.substring(0, name.length() - 5)).toList();
        } catch (IOException error) { return List.of(); }
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
        if (args.length < 3) return List.of();
        String current = args[args.length - 1];
        WorldType type = WorldType.FLAT;
        boolean explicitType = args.length > 3 && !args[2].contains("=");
        if (explicitType) {
            try { type = WorldType.valueOf(args[2].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException error) { return List.of(); }
        }
        if (args.length == 3 && !current.contains("=")) {
            List<String> types = Arrays.stream(WorldType.values())
                    .map(choice -> choice.name().toLowerCase(Locale.ROOT)).toList();
            List<String> choices = new ArrayList<>(types);
            choices.addAll(optionKeys(creating, type, args));
            return suggestions(current, choices);
        }
        if (args.length == 4 && !current.contains("=") && explicitType) {
            List<String> choices = Arrays.stream(Environment.values()).filter(environment -> environment != Environment.SIFT)
                    .map(environment -> environment.name().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            choices.addAll(optionKeys(creating, type, args));
            return suggestions(current, choices);
        }
        if (current.startsWith("group=")) {
            List<String> choices = new ArrayList<>();
            choices.add("group=");
            storage.allGroups().stream().map(studio.lorian.mapmanager.api.model.MapGroup::id)
                    .forEach(group -> choices.add("group=" + group));
            return suggestions(current, choices);
        }
        if (current.startsWith("admin="))
            return suggestions(current, Bukkit.getOnlinePlayers().stream()
                    .map(player -> "admin=" + player.getName()).toList());
        // Biome 在新 Paper 中由枚举变为接口，通过共同的 Keyed 调用保留二进制兼容。
        if (creating && current.startsWith("biome="))
            return suggestions(current, org.bukkit.Registry.BIOME.stream()
                    .map(biome -> "biome=" + ((org.bukkit.Keyed) biome).getKey()).toList());
        if (creating && current.startsWith("structures="))
            return suggestions(current, List.of("structures=true", "structures=false"));
        return suggestions(current, optionKeys(creating, type, args));
    }

    private static List<String> optionKeys(boolean creating, WorldType type, String[] args) {
        List<String> keys = new ArrayList<>(List.of("alias=", "group=", "admin="));
        if (creating) keys.addAll(List.of("flat.height=", "seed=", "structures=", "biome=",
                "generator=", "generator-settings=", "property."));
        if (creating && type != WorldType.FLAT)
            keys.remove("flat.height=");
        Set<String> used = new HashSet<>();
        for (int index = 2; index < args.length - 1; index++) {
            int separator = args[index].indexOf('=');
            if (separator > 0) used.add(args[index].substring(0, separator));
        }
        if (used.contains("flat.height")) keys.remove("generator-settings=");
        if (used.contains("generator-settings")) keys.remove("flat.height=");
        keys.removeIf(key -> key.endsWith("=") && used.contains(key.substring(0, key.length() - 1)));
        return keys;
    }

    private record Registration(String worldName, String alias, String group, String admin,
                                WorldType type, Environment environment, Map<String, String> parameters) { }

    private void createOrImport(NotificationTarget target, String[] args, boolean importing) {
        OperationName operation = importing ? OperationName.IMPORT : OperationName.CREATE;
        final Registration request;
        try { request = parseRegistration(args, importing); }
        catch (IllegalArgumentException error) {
            notifier.notify(target, new OperationResult.Failure<>(operation, UUID.randomUUID(),
                    FailureReason.INVALID_INPUT, error.getMessage()));
            return;
        }
        if (request.admin() == null) {
            runCreateOrImport(target, importing, request, null);
            return;
        }
        resolveMemberId(request.admin()).whenComplete((owner, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null || owner == null) fail(target, operation, FailureReason.PLAYER_NOT_FOUND);
                else runCreateOrImport(target, importing, request, owner);
            });
        });
    }

    private static Registration parseRegistration(String[] args, boolean importing) {
        if (args.length < 2) throw new IllegalArgumentException(Messages.text("errors.map_required"));
        int index = 2;
        WorldType type = WorldType.FLAT;
        Environment environment = Environment.NORMAL;
        if (index < args.length && !args[index].contains("=")) {
            try { type = WorldType.valueOf(args[index].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(Messages.text("errors.world_type", "value", args[index]));
            }
            index++;
            if (index < args.length && !args[index].contains("=")) {
                try { environment = Environment.valueOf(args[index].toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException(Messages.text("errors.environment", "value", args[index]));
                }
                index++;
            }
        }
        Map<String, String> options = new HashMap<>();
        for (; index < args.length; index++) {
            int separator = args[index].indexOf('=');
            if (separator < 1 || separator == args[index].length() - 1)
                throw new IllegalArgumentException(Messages.text("errors.option_format", "value", args[index]));
            String key = args[index].substring(0, separator);
            if (importing && !Set.of("alias", "group", "admin").contains(key))
                throw new IllegalArgumentException(Messages.text("errors.import_option", "key", key));
            if (options.putIfAbsent(key, args[index].substring(separator + 1)) != null)
                throw new IllegalArgumentException(Messages.text("errors.duplicate_option", "key", key));
        }
        String alias = options.remove("alias"), group = options.remove("group"), admin = options.remove("admin");
        return new Registration(args[1], alias, group, admin, type, environment, options);
    }

    private void runCreateOrImport(NotificationTarget target, boolean importing, Registration request, UUID owner) {
        OperationResult<MapInstance> result = importing
                ? lifecycle.importWorld(new ImportMapRequest(request.worldName(), request.alias(), request.group(), owner,
                        request.type(), request.environment()))
                : lifecycle.create(new CreateMapRequest(request.worldName(), request.alias(), request.group(), owner,
                        request.type(), request.environment(), request.parameters()));
        notifier.notify(target, result);
    }

    private void initialize(NotificationTarget target, String[] args) {
        if (args.length != 2) {
            fail(target, OperationName.INITIALIZE, FailureReason.INVALID_INPUT);
            return;
        }
        notifier.notify(target, lifecycle.initialize(args[1]));
    }

    /** 控制台先选地图，再用十秒内有效的一次性确认码执行删除。 */
    private void delete(NotificationTarget target, String[] args) {
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
                fail(target, OperationName.DELETE, FailureReason.INVALID_MAP_STATE);
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
