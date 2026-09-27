package studio.lorian.mapmanager.plugin.internal.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.model.WorldType;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.MapService;
import studio.lorian.mapmanager.api.service.MapAccessService;
import studio.lorian.mapmanager.api.service.AccessDecision;
import studio.lorian.mapmanager.api.service.MapRoleService;
import studio.lorian.mapmanager.api.service.MapTravelService;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.plugin.internal.notification.NotificationTarget;
import studio.lorian.mapmanager.plugin.internal.notification.OperationNotifier;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 注册并分派 /world 子命令；玩家操作由 MMC 地图角色与准入规则授权。
 */
public final class MapWorldCommand extends Command {
    private static final String CONFIRM_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long DELETE_CONFIRM_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JavaPlugin plugin;
    private final MapService maps;
    private final MapAccessService access;
    private final MapRoleService roles;
    private final MapTravelService travel;
    private final WorldLifeCycleService lifeCycle;
    private final PlayerGateway players;
    private final OperationNotifier notifier;
    private final Pattern deletionPattern;
    private final Map<String, Subcommand> subcommands;
    private PendingDeletion pendingDeletion;

    private record PendingDeletion(UUID mapId, String worldName, String code, long issuedAtNanos) {}

    /**
     * 创建 /world 主命令。
     */
    public MapWorldCommand(JavaPlugin plugin, MapService maps, MapRoleService roles, MapAccessService access,
                           MapTravelService travel,
                           WorldLifeCycleService lifeCycle, PlayerGateway players, OperationNotifier notifier,
                           Pattern deletionPattern) {
        this("world", plugin, maps, roles, access, travel, lifeCycle, players, notifier, deletionPattern);
    }

    /**
     * 创建主命令或 /wtp 传送别名。
     */
    public MapWorldCommand(String name, JavaPlugin plugin, MapService maps, MapRoleService roles,
                           MapAccessService access, MapTravelService travel,
                           WorldLifeCycleService lifeCycle, PlayerGateway players, OperationNotifier notifier,
                           Pattern deletionPattern) {
        super(name);
        this.plugin = plugin;
        this.maps = maps;
        this.access = access;
        this.roles = roles;
        this.travel = travel;
        this.lifeCycle = lifeCycle;
        this.players = players;
        this.notifier = notifier;
        this.deletionPattern = deletionPattern;
        this.subcommands = Map.ofEntries(
                Map.entry("create", new Subcommand(true, OperationName.CREATE, this::create)),
                Map.entry("import", new Subcommand(true, OperationName.IMPORT, this::importWorld)),
                Map.entry("init", new Subcommand(true, OperationName.INITIALIZE, this::initialize)),
                Map.entry("delete", new Subcommand(true, OperationName.DELETE, this::delete)),
                Map.entry("tp", new Subcommand(false, OperationName.TELEPORT, this::teleport)),
                Map.entry("kick", new Subcommand(false, OperationName.KICK, this::kick)),
                Map.entry("admins", new Subcommand(false, OperationName.LIST_MEMBERS,
                        (sender, target, args) -> listMembers(sender, target, args, MapRole.ADMIN))),
                Map.entry("builders", new Subcommand(false, OperationName.LIST_MEMBERS,
                        (sender, target, args) -> listMembers(sender, target, args, MapRole.BUILDER))),
                Map.entry("visitors", new Subcommand(false, OperationName.LIST_MEMBERS,
                        (sender, target, args) -> listMembers(sender, target, args, MapRole.VISITOR))),
                Map.entry("admin", new Subcommand(false, OperationName.ADD_MEMBER,
                        (sender, target, args) -> changeMember(sender, target, args, MapRole.ADMIN))),
                Map.entry("builder", new Subcommand(false, OperationName.ADD_MEMBER,
                        (sender, target, args) -> changeMember(sender, target, args, MapRole.BUILDER))),
                Map.entry("visitor", new Subcommand(false, OperationName.ADD_MEMBER,
                        (sender, target, args) -> changeMember(sender, target, args, MapRole.VISITOR)))
        );
        setUsage(name.equals("wtp") ? "/wtp <地图名>"
                : "/world <create|import|init|delete|tp|kick|admins|builders|visitors|admin|builder|visitor> ...");
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] input) {
        String[] args = input;
        // /wtp <地图名> 与 /world tp <地图名> 进入同一子命令处理器。
        if (getName().equals("wtp")) {
            args = new String[input.length + 1];
            args[0] = "tp";
            System.arraycopy(input, 0, args, 1, input.length);
        }
        if (args.length == 0) {
            sender.sendMessage(getUsage());
            return true;
        }

        Subcommand subcommand = subcommands.get(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            sender.sendMessage(getUsage());
            return true;
        }
        NotificationTarget target = sender instanceof Player player
                ? NotificationTarget.player(player.getName()) : NotificationTarget.console();
        OperationName operation = subcommand.operation;
        if (operation == OperationName.ADD_MEMBER && args.length > 1 && args[1].equalsIgnoreCase("remove"))
            operation = OperationName.REMOVE_MEMBER;
        if (subcommand.consoleOnly && !(sender instanceof ConsoleCommandSender)) {
            fail(target, operation, FailureReason.NO_PERMISSION);
            return true;
        }
        if (!subcommand.consoleOnly && !(sender instanceof Player)) {
            fail(target, operation, FailureReason.INVALID_INPUT);
            return true;
        }
        subcommand.handler.execute(sender, target, args);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 0) return List.of();
        try {
            if (getName().equals("wtp"))
                return args.length == 1 && sender instanceof Player
                        ? suggestions(args[0], accessibleWorldNames((Player) sender)) : List.of();
            if (args.length == 1) {
                MapInstance current = sender instanceof Player player
                        ? maps.findByWorldName(player.getWorld().getName()) : null;
                AccessDecision decision = current == null ? null
                        : access.decide(((Player) sender).getUniqueId(), current.id());
                boolean canEnter = decision != null && decision.allowed();
                boolean admin = canEnter && decision.effectiveRole() == MapRole.ADMIN;
                return suggestions(args[0], subcommands.entrySet().stream()
                        .filter(entry -> entry.getValue().consoleOnly ? sender instanceof ConsoleCommandSender
                                : sender instanceof Player)
                        .filter(entry -> !Set.of("admin", "builder", "visitor", "kick").contains(entry.getKey()) || admin)
                        .filter(entry -> !Set.of("admins", "builders", "visitors").contains(entry.getKey()) || canEnter)
                        .map(Map.Entry::getKey).toList());
            }
            String command = args[0].toLowerCase(Locale.ROOT);
            Subcommand subcommand = subcommands.get(command);
            if (subcommand == null || (subcommand.consoleOnly && !(sender instanceof ConsoleCommandSender))
                    || (!subcommand.consoleOnly && !(sender instanceof Player))) return List.of();
            String currentArg = args[args.length - 1];
            return switch (command) {
                case "tp" -> args.length == 2
                        ? suggestions(currentArg, accessibleWorldNames((Player) sender)) : List.of();
                case "kick" -> args.length == 2
                        ? kickSuggestions((Player) sender, currentArg) : List.of();
                case "init" -> args.length == 2
                        ? suggestions(currentArg, maps.listMaps().stream()
                        .filter(map -> map.status() != MapStatus.DELETED).map(MapInstance::worldName).toList())
                        : List.of();
                case "delete" -> args.length == 2
                        ? suggestions(currentArg, maps.listMaps().stream()
                                .filter(map -> map.status() == MapStatus.ACTIVE || map.status() == MapStatus.DELETING)
                                .filter(map -> deletionPattern.matcher(map.worldName()).matches())
                                .map(MapInstance::worldName).toList())
                        : List.of();
                case "import" -> args.length == 2 ? suggestions(currentArg, importableWorldNames())
                        : creationSuggestions(command, args);
                case "create" -> creationSuggestions(command, args);
                case "admin", "builder", "visitor" -> roleSuggestions((Player) sender, command, args);
                default -> List.of();
            };
        } catch (StorageException exception) {
            return List.of();
        }
    }

    private List<String> accessibleWorldNames(Player player) {
        return access.accessibleMaps(player.getUniqueId()).stream().map(MapInstance::worldName).toList();
    }

    private List<String> kickSuggestions(Player sender, String prefix) {
        MapInstance map = maps.findByWorldName(sender.getWorld().getName());
        if (map == null || access.decide(sender.getUniqueId(), map.id()).effectiveRole() != MapRole.ADMIN)
            return List.of();
        return suggestions(prefix, sender.getWorld().getPlayers().stream().map(Player::getName).toList());
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
        if (args.length == 3 && (creating || !current.contains("="))) {
            List<String> types = Arrays.stream(WorldType.values())
                    .map(type -> type.name().toLowerCase(Locale.ROOT)).toList();
            if (creating) return suggestions(current, types);
            List<String> choices = new ArrayList<>(types);
            choices.addAll(optionKeys(false, args));
            return suggestions(current, choices);
        }
        if (args.length < 3) return List.of();
        if (current.startsWith("group=")) {
            List<String> choices = new ArrayList<>();
            choices.add("group=");
            maps.listMaps().stream().map(MapInstance::groupId).distinct()
                    .forEach(group -> choices.add("group=" + group));
            return suggestions(current, choices);
        }
        if (current.startsWith("admin="))
            return suggestions(current, Bukkit.getOnlinePlayers().stream()
                    .map(player -> "admin=" + player.getName()).toList());
        if (current.startsWith("structures="))
            return suggestions(current, List.of("structures=true", "structures=false"));
        return suggestions(current, optionKeys(creating, args));
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

    private List<String> roleSuggestions(Player sender, String command, String[] args) {
        if (args.length > 3) return List.of();
        MapInstance map = maps.findByWorldName(sender.getWorld().getName());
        if (map == null || access.decide(sender.getUniqueId(), map.id()).effectiveRole() != MapRole.ADMIN)
            return List.of();
        if (args.length == 2) return suggestions(args[1], List.of("add", "remove"));
        MapRole role = MapRole.valueOf(command.toUpperCase(Locale.ROOT));
        if (args[1].equalsIgnoreCase("add"))
            return suggestions(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        if (!args[1].equalsIgnoreCase("remove")) return List.of();
        return suggestions(args[2], roles.assignedMembers(map.id(), role).stream()
                .map(id -> {
                    String name = Bukkit.getOfflinePlayer(id).getName();
                    return name == null ? id.toString() : name;
                }).toList());
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
        if (args.length < (importing ? 2 : 3) || (!importing && args[2].contains("="))) {
            fail(target, operation, FailureReason.INVALID_INPUT);
            return;
        }

        int optionsStart = 2;
        WorldType type = WorldType.NORMAL;
        if (args.length > 2 && !args[2].contains("=")) {
            try {
                type = WorldType.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                fail(target, operation, FailureReason.INVALID_INPUT);
                return;
            }
            optionsStart = 3;
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
        Map<String, String> creationParameters = Map.copyOf(parameters);
        if (admin == null) {
            runCreateOrImport(target, importing, worldName, displayName, groupId, null,
                    worldType, creationParameters);
            return;
        }
        players.resolveId(admin).whenComplete((owner, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || owner == null) {
                fail(target, operation, FailureReason.PLAYER_NOT_FOUND);
                return;
            }
            runCreateOrImport(target, importing, worldName, displayName, groupId, owner,
                    worldType, creationParameters);
        }));
    }

    private void runCreateOrImport(NotificationTarget target, boolean importing,
                                   String worldName, String displayName, String groupId, UUID owner,
                                   WorldType type, Map<String, String> parameters) {
        OperationResult<MapInstance> result = importing
                ? lifeCycle.importWorld(new ImportMapRequest(worldName, displayName, groupId, owner, type))
                : lifeCycle.create(new CreateMapRequest(worldName, displayName, groupId, owner, type, parameters));
        notifier.notify(target, result);
    }

    private void initialize(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length != 2) {
            fail(target, OperationName.INITIALIZE, FailureReason.INVALID_INPUT);
            return;
        }
        notifier.notify(target, lifeCycle.initialize(args[1]));
    }

    /** 控制台先选地图，再用十秒内有效的一次性确认码执行删除。 */
    private void delete(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length != 2) {
            fail(target, OperationName.DELETE, FailureReason.INVALID_INPUT);
            notifier.notify(target, "用法: /world delete <地图名>，然后在 10 秒内执行 /world delete <确认码>");
            return;
        }
        String input = args[1];
        PendingDeletion pending = pendingDeletion;
        if (pending != null && pending.code().equals(input)) {
            pendingDeletion = null;
            if (System.nanoTime() - pending.issuedAtNanos() >= DELETE_CONFIRM_NANOS) {
                fail(target, OperationName.DELETE, FailureReason.INVALID_INPUT);
                notifier.notify(target, "删除确认码已过期，请重新指定地图。");
                return;
            }
            try {
                MapInstance current = maps.findByWorldName(pending.worldName());
                if (current == null || !current.id().equals(pending.mapId())
                        || !deletionPattern.matcher(current.worldName()).matches()) {
                    fail(target, OperationName.DELETE, FailureReason.INVALID_INPUT);
                    return;
                }
                notifier.notify(target, lifeCycle.delete(current.worldName()));
            } catch (StorageException exception) {
                fail(target, OperationName.DELETE, FailureReason.STORAGE_FAILED);
            }
            return;
        }
        if (pending != null) {
            if (System.nanoTime() - pending.issuedAtNanos() < DELETE_CONFIRM_NANOS) {
                fail(target, OperationName.DELETE, FailureReason.INVALID_INPUT);
                notifier.notify(target, "确认码不正确；请使用上一步提供的 6 位字符串。");
                return;
            }
            pendingDeletion = null;
        }
        if (!deletionPattern.matcher(input).matches()) {
            fail(target, OperationName.DELETE, FailureReason.ACCESS_DENIED);
            notifier.notify(target, "地图名不符合 deletion.allowed-world-name-regex，或确认码无效。");
            return;
        }
        try {
            MapInstance map = maps.findByWorldName(input);
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
            notifier.notify(target, "确认删除地图 " + map.worldName() + "：请在 10 秒内执行 /world delete " + code);
        } catch (StorageException exception) {
            fail(target, OperationName.DELETE, FailureReason.STORAGE_FAILED);
        }
    }

    private void teleport(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length != 2) {
            fail(target, OperationName.TELEPORT, FailureReason.INVALID_INPUT);
            return;
        }
        MapInstance map;
        try {
            map = maps.findByWorldName(args[1]);
        } catch (StorageException exception) {
            fail(target, OperationName.TELEPORT, FailureReason.STORAGE_FAILED);
            return;
        }
        if (map == null) {
            fail(target, OperationName.TELEPORT, FailureReason.MAP_NOT_FOUND);
            return;
        }
        Player player = (Player) sender;
        notifier.notify(target, travel.teleport(player.getUniqueId(), map.id()));
    }

    /**
     * 将当前 MMC 地图中的在线玩家送至 Multiverse 默认世界出生点。
     */
    private void kick(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length != 2) {
            fail(target, OperationName.KICK, FailureReason.INVALID_INPUT);
            return;
        }
        Player operator = (Player) sender;
        Player member = Bukkit.getPlayerExact(args[1]);
        if (member == null) {
            fail(target, OperationName.KICK, FailureReason.PLAYER_NOT_FOUND);
            return;
        }
        String worldName = operator.getWorld().getName();
        OperationResult<Void> result = travel.kickFromMap(operator.getUniqueId(), member.getUniqueId());
        notifier.notify(target, result);
        if (result instanceof OperationResult.Success<?>)
            notifier.notify(NotificationTarget.player(member.getName()),
                    "§7你已被 " + operator.getName() + " 移出地图 " + worldName + "。");
    }

    /**
     * 当前地图决定角色作用范围；名单只显示直接授予的成员。
     */
    private void listMembers(CommandSender sender, NotificationTarget target, String[] args, MapRole role) {
        if (args.length != 1) {
            fail(target, OperationName.LIST_MEMBERS, FailureReason.INVALID_INPUT);
            return;
        }
        try {
            MapInstance map = maps.findByWorldName(((Player) sender).getWorld().getName());
            if (map == null) {
                fail(target, OperationName.LIST_MEMBERS, FailureReason.MAP_NOT_FOUND);
                return;
            }
            AccessDecision decision = access.decide(((Player) sender).getUniqueId(), map.id());
            if (!decision.allowed()) {
                fail(target, OperationName.LIST_MEMBERS, decision.denialReason());
                return;
            }
            if (role == MapRole.VISITOR && map.settings().isPublic())
                sender.sendMessage("§a当前地图公开，所有玩家均可参观。");
            List<String> names = roles.assignedMembers(map.id(), role).stream()
                    .map(id -> {
                        String name = Bukkit.getOfflinePlayer(id).getName();
                        return name == null ? id.toString() : name;
                    })
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            sender.sendMessage("§b" + map.worldName() + " 的" + roleName(role) + "（" + names.size() + " 人）：");
            if (names.isEmpty()) sender.sendMessage("§7暂无直接授予的成员。");
            else names.forEach(name -> sender.sendMessage("§3> §b" + name));
        } catch (StorageException exception) {
            fail(target, OperationName.LIST_MEMBERS, FailureReason.STORAGE_FAILED);
        }
    }

    /**
     * 先验证当前地图管理员，再异步解析用户名，最后在主线程重新验证并修改角色。
     */
    private void changeMember(CommandSender sender, NotificationTarget target, String[] args, MapRole role) {
        OperationName operation = args.length > 1 && args[1].equalsIgnoreCase("remove")
                ? OperationName.REMOVE_MEMBER : OperationName.ADD_MEMBER;
        if (args.length != 3 || (!args[1].equalsIgnoreCase("add") && !args[1].equalsIgnoreCase("remove"))) {
            fail(target, operation, FailureReason.INVALID_INPUT);
            return;
        }
        Player actor = (Player) sender;
        MapInstance selectedMap = manageableCurrentMap(actor, target, operation);
        if (selectedMap == null) return;
        UUID actorId = actor.getUniqueId();
        resolveMemberId(args[2]).whenComplete((memberId, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player currentActor = Bukkit.getPlayer(actorId);
                if (currentActor == null) return;
                MapInstance map = manageableCurrentMap(currentActor, target, operation);
                if (map == null) return;
                if (!map.id().equals(selectedMap.id())) {
                    fail(target, operation, FailureReason.INVALID_INPUT);
                    return;
                }
                if (error != null || memberId == null) {
                    fail(target, operation, FailureReason.PLAYER_NOT_FOUND);
                    return;
                }
                try {
                    OperationResult<Void> result = operation == OperationName.ADD_MEMBER
                            ? roles.addMember(map.id(), memberId, role)
                            : roles.removeMember(map.id(), memberId, role);
                    notifier.notify(target, result);
                    if (result instanceof OperationResult.Success<?>)
                        notifier.notify(target, "§a已" + (operation == OperationName.ADD_MEMBER ? "添加" : "移除")
                                + " " + args[2] + " 的" + roleName(role) + "角色。");
                } catch (StorageException exception) {
                    fail(target, operation, FailureReason.STORAGE_FAILED);
                }
            });
        });
    }

    private CompletableFuture<UUID> resolveMemberId(String input) {
        try {
            return CompletableFuture.completedFuture(UUID.fromString(input));
        } catch (IllegalArgumentException exception) {
            return players.resolveId(input);
        }
    }

    private MapInstance manageableCurrentMap(Player actor, NotificationTarget target, OperationName operation) {
        try {
            MapInstance map = maps.findByWorldName(actor.getWorld().getName());
            if (map == null) {
                fail(target, operation, FailureReason.MAP_NOT_FOUND);
                return null;
            }
            if (access.decide(actor.getUniqueId(), map.id()).effectiveRole() != MapRole.ADMIN) {
                fail(target, operation, FailureReason.NO_PERMISSION);
                return null;
            }
            return map;
        } catch (StorageException exception) {
            fail(target, operation, FailureReason.STORAGE_FAILED);
            return null;
        }
    }

    private static String roleName(MapRole role) {
        return switch (role) {
            case ADMIN -> "管理员";
            case BUILDER -> "建筑者";
            case VISITOR -> "访客";
        };
    }

    private void fail(NotificationTarget target, OperationName operation, FailureReason reason) {
        notifier.notify(target, new OperationResult.Failure<>(operation, UUID.randomUUID(), reason));
    }

    @FunctionalInterface
    private interface Handler {
        void execute(CommandSender sender, NotificationTarget target, String[] args);
    }

    private record Subcommand(boolean consoleOnly, OperationName operation, Handler handler) {
    }
}
