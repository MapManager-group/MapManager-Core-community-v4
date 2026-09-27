package studio.lorian.mapmanager.plugin.internal.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.WorldType;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.MapService;
import studio.lorian.mapmanager.api.service.MapTravelService;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.plugin.internal.notification.NotificationTarget;
import studio.lorian.mapmanager.plugin.internal.notification.OperationNotifier;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;

import java.util.*;

/**
 * 注册并分派 /world 子命令；每个子命令在注册时声明执行方和权限。
 */
public final class MapWorldCommand extends Command {
    private final JavaPlugin plugin;
    private final MapService maps;
    private final MapTravelService travel;
    private final WorldLifeCycleService lifeCycle;
    private final PlayerGateway players;
    private final OperationNotifier notifier;
    private final Map<String, Subcommand> subcommands;

    /**
     * 创建 /world 主命令。
     */
    public MapWorldCommand(JavaPlugin plugin, MapService maps, MapTravelService travel,
                           WorldLifeCycleService lifeCycle, PlayerGateway players, OperationNotifier notifier) {
        this("world", plugin, maps, travel, lifeCycle, players, notifier);
    }

    /**
     * 创建主命令或 /wtp 传送别名。
     */
    public MapWorldCommand(String name, JavaPlugin plugin, MapService maps, MapTravelService travel,
                           WorldLifeCycleService lifeCycle, PlayerGateway players, OperationNotifier notifier) {
        super(name);
        this.plugin = plugin;
        this.maps = maps;
        this.travel = travel;
        this.lifeCycle = lifeCycle;
        this.players = players;
        this.notifier = notifier;
        this.subcommands = Map.of(
                "create", new Subcommand(true, null, OperationName.CREATE, this::create),
                "import", new Subcommand(true, null, OperationName.IMPORT, this::importWorld),
                "init", new Subcommand(true, null, OperationName.INITIALIZE, this::initialize),
                "tp", new Subcommand(false, "mapmanager.world", OperationName.TELEPORT, this::teleport)
        );
        setUsage(name.equals("wtp") ? "/wtp <地图名>" : "/world <create|import|init|tp> ...");
        registerPlayerPermissions();
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
        if (subcommand.consoleOnly && !(sender instanceof ConsoleCommandSender)) {
            fail(target, subcommand.operation, FailureReason.NO_PERMISSION);
            return true;
        }
        if (!subcommand.consoleOnly && !(sender instanceof Player)) {
            fail(target, subcommand.operation, FailureReason.INVALID_INPUT);
            return true;
        }
        if (subcommand.permission != null && !sender.hasPermission(subcommand.permission)) {
            fail(target, subcommand.operation, FailureReason.NO_PERMISSION);
            return true;
        }
        subcommand.handler.execute(sender, target, args);
        return true;
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

    private void fail(NotificationTarget target, OperationName operation, FailureReason reason) {
        notifier.notify(target, new OperationResult.Failure<>(operation, UUID.randomUUID(), reason));
    }

    private void registerPlayerPermissions() {
        subcommands.values().stream().map(Subcommand::permission).filter(Objects::nonNull)
                .distinct().forEach(permission -> {
                    if (plugin.getServer().getPluginManager().getPermission(permission) == null) {
                        plugin.getServer().getPluginManager().addPermission(
                                new Permission(permission, PermissionDefault.FALSE));
                    }
                });
    }

    @FunctionalInterface
    private interface Handler {
        void execute(CommandSender sender, NotificationTarget target, String[] args);
    }

    private record Subcommand(boolean consoleOnly, String permission, OperationName operation, Handler handler) {
        private Subcommand {
            if (!consoleOnly && (permission == null || permission.isBlank()))
                throw new IllegalArgumentException("玩家子命令必须声明权限节点");
            if (consoleOnly && permission != null)
                throw new IllegalArgumentException("仅控制台子命令不能配置玩家权限节点");
        }
    }
}
