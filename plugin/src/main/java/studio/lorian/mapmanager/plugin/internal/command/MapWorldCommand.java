package studio.lorian.mapmanager.plugin.internal.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapRole;
import studio.lorian.mapmanager.api.model.SpawnPoint;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.MapService;
import studio.lorian.mapmanager.api.service.MapAccessService;
import studio.lorian.mapmanager.api.service.AccessDecision;
import studio.lorian.mapmanager.api.service.MapRoleService;
import studio.lorian.mapmanager.api.service.MapSettingsService;
import studio.lorian.mapmanager.api.service.MapTravelService;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.plugin.internal.listener.MapAccessListener;
import studio.lorian.mapmanager.plugin.internal.notification.NotificationTarget;
import studio.lorian.mapmanager.plugin.internal.notification.OperationNotifier;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 注册并分派 /world 子命令；玩家操作由 MMC 地图角色与准入规则授权。
 */
public final class MapWorldCommand extends Command {
    private final JavaPlugin plugin;
    private final MapService maps;
    private final MapAccessService access;
    private final MapRoleService roles;
    private final MapSettingsService settings;
    private final MapTravelService travel;
    private final PlayerGateway players;
    private final OperationNotifier notifier;
    private final MapAccessListener accessListener;
    private final Map<String, Subcommand> subcommands;

    /**
     * 创建 /world 主命令。
     */
    public MapWorldCommand(JavaPlugin plugin, MapService maps, MapRoleService roles, MapAccessService access,
                           MapSettingsService settings,
                           MapTravelService travel,
                           PlayerGateway players, OperationNotifier notifier,
                           MapAccessListener accessListener) {
        this("world", plugin, maps, roles, access, settings, travel, players, notifier, accessListener);
    }

    /**
     * 创建主命令或 /wtp 传送别名。
     */
    public MapWorldCommand(String name, JavaPlugin plugin, MapService maps, MapRoleService roles,
                           MapAccessService access, MapSettingsService settings, MapTravelService travel,
                           PlayerGateway players, OperationNotifier notifier,
                           MapAccessListener accessListener) {
        super(name);
        this.plugin = plugin;
        this.maps = maps;
        this.access = access;
        this.roles = roles;
        this.settings = settings;
        this.travel = travel;
        this.players = players;
        this.notifier = notifier;
        this.accessListener = accessListener;
        this.subcommands = Map.ofEntries(
                Map.entry("tp", new Subcommand(OperationName.TELEPORT, this::teleport)),
                Map.entry("kick", new Subcommand(OperationName.KICK, this::kick)),
                Map.entry("physics", new Subcommand(OperationName.SET_PHYSICAL,
                        (sender, target, args) -> changeSetting((Player) sender, target, args, true))),
                Map.entry("explosion", new Subcommand(OperationName.SET_EXPLOSION,
                        (sender, target, args) -> changeSetting((Player) sender, target, args, false))),
                Map.entry("public", new Subcommand(OperationName.SET_PUBLIC, this::changePublic)),
                Map.entry("setname", new Subcommand(OperationName.SET_ALIAS, this::setName)),
                Map.entry("setspawn", new Subcommand(OperationName.SET_SPAWN, this::setSpawn)),
                Map.entry("admin", new Subcommand(OperationName.ADD_MEMBER,
                        (sender, target, args) -> changeMember(sender, target, args, MapRole.ADMIN))),
                Map.entry("builder", new Subcommand(OperationName.ADD_MEMBER,
                        (sender, target, args) -> changeMember(sender, target, args, MapRole.BUILDER))),
                Map.entry("visitor", new Subcommand(OperationName.ADD_MEMBER,
                        (sender, target, args) -> changeMember(sender, target, args, MapRole.VISITOR)))
        );
        setUsage(Messages.text(name.equals("wtp") ? "command.usage_wtp" : "command.usage_world"));
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
            sender.sendMessage(Messages.component(getName().equals("wtp") ? "command.usage_wtp" : "command.usage_world"));
            return true;
        }

        Subcommand subcommand = subcommands.get(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            sender.sendMessage(Messages.component(getName().equals("wtp") ? "command.usage_wtp" : "command.usage_world"));
            return true;
        }
        NotificationTarget target = sender instanceof Player player
                ? NotificationTarget.player(player.getName()) : NotificationTarget.console();
        OperationName operation = subcommand.operation;
        if (operation == OperationName.ADD_MEMBER && args.length > 1) {
            if (args[1].equalsIgnoreCase("remove")) operation = OperationName.REMOVE_MEMBER;
            else if (args[1].equalsIgnoreCase("info")) operation = OperationName.LIST_MEMBERS;
        }
        if (!(sender instanceof Player)) {
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
                        .filter(entry -> sender instanceof Player)
                        .filter(entry -> !Set.of("kick", "physics", "explosion",
                                "public", "setname", "setspawn").contains(entry.getKey()) || admin)
                        .filter(entry -> !Set.of("admin", "builder", "visitor").contains(entry.getKey()) || canEnter)
                        .map(Map.Entry::getKey).toList());
            }
            String command = args[0].toLowerCase(Locale.ROOT);
            Subcommand subcommand = subcommands.get(command);
            if (subcommand == null || !(sender instanceof Player)) return List.of();
            String currentArg = args[args.length - 1];
            return switch (command) {
                case "tp" -> args.length == 2
                        ? suggestions(currentArg, accessibleWorldNames((Player) sender)) : List.of();
                case "kick" -> args.length == 2
                        ? kickSuggestions((Player) sender, currentArg) : List.of();
                case "admin", "builder", "visitor" -> roleSuggestions((Player) sender, command, args);
                case "physics", "explosion" -> args.length == 2
                        && manageableSettingMap((Player) sender) ? suggestions(currentArg, List.of("true", "false", "info")) : List.of();
                case "public" -> args.length == 2 && manageableSettingMap((Player) sender)
                        ? suggestions(currentArg, List.of("true", "false", "info")) : List.of();
                default -> List.of();
            };
        } catch (StorageException exception) {
            return List.of();
        }
    }

    private List<String> accessibleWorldNames(Player player) {
        return access.accessibleMaps(player.getUniqueId()).stream()
                .filter(map -> Bukkit.getWorld(map.worldName()) != null)
                .map(MapInstance::worldName).toList();
    }

    private List<String> kickSuggestions(Player sender, String prefix) {
        MapInstance map = maps.findByWorldName(sender.getWorld().getName());
        if (map == null || access.decide(sender.getUniqueId(), map.id()).effectiveRole() != MapRole.ADMIN)
            return List.of();
        return suggestions(prefix, sender.getWorld().getPlayers().stream().map(Player::getName).toList());
    }

    private List<String> roleSuggestions(Player sender, String command, String[] args) {
        if (args.length > 3) return List.of();
        MapInstance map = maps.findByWorldName(sender.getWorld().getName());
        if (map == null) return List.of();
        AccessDecision decision = access.decide(sender.getUniqueId(), map.id());
        if (!decision.allowed()) return List.of();
        boolean admin = decision.effectiveRole() == MapRole.ADMIN;
        if (args.length == 2) return suggestions(args[1], admin ? List.of("add", "remove", "info") : List.of("info"));
        if (!admin) return List.of();
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
            notifier.notifyKey(NotificationTarget.player(member.getName()), "command.kicked",
                    "operator", operator.getName(), "world", worldName);
    }

    /**
     * 当前地图决定角色作用范围；名单只显示直接授予的成员。
     */
    private void listMembers(CommandSender sender, NotificationTarget target, String[] args, MapRole role) {
        if (args.length != 2) {
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
                notifier.notifyKey(target, "command.public_map");
            List<String> names = roles.assignedMembers(map.id(), role).stream()
                    .map(id -> {
                        String name = Bukkit.getOfflinePlayer(id).getName();
                        return name == null ? id.toString() : name;
                    })
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            notifier.notifyKey(target, "command.members_header", "world", map.worldName(),
                    "role", roleName(role), "count", names.size());
            if (names.isEmpty()) notifier.notifyKey(target, "command.members_empty");
            else names.forEach(name -> notifier.notifyKey(target, "command.members_entry", "player", name));
        } catch (StorageException exception) {
            fail(target, OperationName.LIST_MEMBERS, FailureReason.STORAGE_FAILED);
        }
    }

    /**
     * 先验证当前地图管理员，再异步解析用户名，最后在主线程重新验证并修改角色。
     */
    private void changeMember(CommandSender sender, NotificationTarget target, String[] args, MapRole role) {
        if (args.length > 1 && args[1].equalsIgnoreCase("info")) {
            listMembers(sender, target, args, role);
            return;
        }
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
                    if (operation == OperationName.REMOVE_MEMBER && !(result instanceof OperationResult.Failure<?>))
                        accessListener.enforceAfterPermissionRemoval(memberId);
                    if (result instanceof OperationResult.Success<?>)
                        notifier.notifyKey(target, operation == OperationName.ADD_MEMBER
                                ? "command.member_added" : "command.member_removed",
                                "player", args[2], "role", roleName(role));
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

    private boolean manageableSettingMap(Player player) {
        MapInstance map = maps.findByWorldName(player.getWorld().getName());
        return map != null && access.decide(player.getUniqueId(), map.id()).effectiveRole() == MapRole.ADMIN;
    }

    private void changeSetting(Player actor, NotificationTarget target, String[] args, boolean physics) {
        OperationName operation = physics ? OperationName.SET_PHYSICAL : OperationName.SET_EXPLOSION;
        if (args.length > 2 || (args.length == 2 && !Set.of("true", "false", "info").contains(args[1].toLowerCase(Locale.ROOT)))) {
            notifier.notifyKey(target, "command.setting_usage", "setting", physics ? "physics" : "explosion");
            return;
        }
        MapInstance map = manageableCurrentMap(actor, target, operation);
        if (map == null) return;
        if (args.length < 2 || args[1].equalsIgnoreCase("info")) {
            notifier.notifyKey(target, "command.setting_info", "setting", physics ? "physics" : "explosion",
                    "value", physics ? map.settings().physical() : map.settings().explosion());
            return;
        }
        boolean enabled = Boolean.parseBoolean(args[1]);
        notifier.notify(target, physics ? settings.setPhysical(map.id(), enabled) : settings.setExplosion(map.id(), enabled));
    }

    private void changePublic(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length > 2 || (args.length == 2
                && !Set.of("true", "false", "on", "enable", "yes", "off", "disable", "no", "info", "status")
                .contains(args[1].toLowerCase(Locale.ROOT)))) {
            fail(target, OperationName.SET_PUBLIC, FailureReason.INVALID_INPUT);
            notifier.notifyKey(target, "command.setting_usage", "setting", "public");
            return;
        }
        Player actor = (Player) sender;
        MapInstance map = manageableCurrentMap(actor, target, OperationName.SET_PUBLIC);
        if (map == null) return;
        if (args.length == 1 || args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("status")) {
            notifier.notifyKey(target, "command.setting_info", "setting", "public",
                    "value", map.settings().isPublic());
            return;
        }
        boolean enabled = Set.of("true", "on", "enable", "yes").contains(args[1].toLowerCase(Locale.ROOT));
        notifier.notify(target, settings.setPublic(map.id(), enabled));
    }

    private void setName(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length < 2) {
            fail(target, OperationName.SET_ALIAS, FailureReason.INVALID_INPUT);
            notifier.notifyKey(target, "command.setname_usage");
            return;
        }
        Player actor = (Player) sender;
        MapInstance map = manageableCurrentMap(actor, target, OperationName.SET_ALIAS);
        if (map == null) return;
        String displayName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        notifier.notify(target, settings.setDisplayName(map.id(), displayName));
    }

    private void setSpawn(CommandSender sender, NotificationTarget target, String[] args) {
        if (args.length != 1) {
            fail(target, OperationName.SET_SPAWN, FailureReason.INVALID_INPUT);
            notifier.notifyKey(target, "command.setspawn_usage");
            return;
        }
        Player actor = (Player) sender;
        MapInstance map = manageableCurrentMap(actor, target, OperationName.SET_SPAWN);
        if (map == null) return;
        var location = actor.getLocation();
        SpawnPoint spawn = new SpawnPoint(map.worldName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        notifier.notify(target, settings.setSpawn(map.id(), spawn));
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

    private static String roleName(MapRole role) { return Messages.text("roles." + role); }

    private void fail(NotificationTarget target, OperationName operation, FailureReason reason) {
        notifier.notify(target, new OperationResult.Failure<>(operation, UUID.randomUUID(), reason));
    }

    @FunctionalInterface
    private interface Handler {
        void execute(CommandSender sender, NotificationTarget target, String[] args);
    }

    private record Subcommand(OperationName operation, Handler handler) {
    }
}
