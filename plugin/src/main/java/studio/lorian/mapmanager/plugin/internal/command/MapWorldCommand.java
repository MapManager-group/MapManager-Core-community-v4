package studio.lorian.mapmanager.plugin.internal.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.model.CreateMapRequest;
import studio.lorian.mapmanager.api.model.ImportMapRequest;
import studio.lorian.mapmanager.api.model.WorldType;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.PlayerGateway;
import studio.lorian.mapmanager.api.service.WorldLifeCycleService;
import studio.lorian.mapmanager.plugin.internal.notification.NotificationTarget;
import studio.lorian.mapmanager.plugin.internal.notification.OperationNotifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 创建、导入及初始化地图命令，接收名称、类型和可选参数。 */
public final class MapWorldCommand extends Command {
    private final JavaPlugin plugin;
    private final WorldLifeCycleService lifeCycle;
    private final PlayerGateway players;
    private final OperationNotifier notifier;

    public MapWorldCommand(JavaPlugin plugin, WorldLifeCycleService lifeCycle,
                            PlayerGateway players, OperationNotifier notifier) {
        this("world", plugin, lifeCycle, players, notifier);
    }

    /** 为统一命令和独立的 create/import 命令建立相同的执行入口。 */
    public MapWorldCommand(String name, JavaPlugin plugin, WorldLifeCycleService lifeCycle,
                           PlayerGateway players, OperationNotifier notifier) {
        super(name);
        this.plugin = plugin;
        this.lifeCycle = lifeCycle;
        this.players = players;
        this.notifier = notifier;
        setUsage(name.equals("world")
                ? "/world <create|import|init> <地图名> [flat|normal|void|nether|end] [alias=名称] [group=组名] [admin=玩家名] [参数=值 ...]"
                : "/" + name + " <地图名> [flat|normal|void|nether|end] [alias=名称] [group=组名] [admin=玩家名] [参数=值 ...]");
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        // 独立命令补上子命令名，其余解析与 /world 共用。
        if (!getName().equals("world")) {
            String[] expanded = new String[args.length + 1];
            System.arraycopy(args, 0, expanded, 1, args.length);
            expanded[0] = getName();
            args = expanded;
        }
        NotificationTarget target = sender instanceof Player player
                ? NotificationTarget.player(player.getName()) : NotificationTarget.console();
        if (args.length < 2 || (!args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("import")
                && !args[0].equalsIgnoreCase("init"))) {
            sender.sendMessage(getUsage());
            return true;
        }
        if (args[0].equalsIgnoreCase("init")) {
            if (!sender.hasPermission("mapmanager.command.init"))
                notifier.notify(target, new OperationResult.Failure<>(OperationName.INITIALIZE, UUID.randomUUID(),
                        FailureReason.NO_PERMISSION));
            else if (args.length != 2) invalid(target, OperationName.INITIALIZE);
            else notifier.notify(target, lifeCycle.initialize(args[1]));
            return true;
        }
        boolean importing = args[0].equalsIgnoreCase("import");
        OperationName operation = importing ? OperationName.IMPORT : OperationName.CREATE;
        if (!sender.hasPermission("mapmanager.command." + args[0].toLowerCase(Locale.ROOT))) {
            notifier.notify(target, new OperationResult.Failure<>(operation, UUID.randomUUID(), FailureReason.NO_PERMISSION));
            return true;
        }
        if (!importing && args.length < 3) {
            sender.sendMessage(getUsage());
            return true;
        }
        if (!importing && args[2].contains("=")) {
            invalid(target, operation);
            return true;
        }
        final WorldType type;
        try {
            type = args.length > 2 && !args[2].contains("=")
                    ? WorldType.valueOf(args[2].toUpperCase(Locale.ROOT)) : WorldType.NORMAL;
        } catch (IllegalArgumentException exception) {
            invalid(target, operation);
            return true;
        }
        String alias = null;
        String group = null;
        String admin = null;
        Map<String, String> parameters = new HashMap<>();
        for (int i = importing && (args.length < 3 || args[2].contains("=")) ? 2 : 3; i < args.length; i++) {
            int separator = args[i].indexOf('=');
            if (separator < 1 || separator == args[i].length() - 1) {
                invalid(target, operation);
                return true;
            }
            String key = args[i].substring(0, separator);
            String value = args[i].substring(separator + 1);
            if (key.equals("alias")) alias = value;
            else if (key.equals("group")) group = value;
            else if (key.equals("admin")) admin = value;
            else if (importing || parameters.putIfAbsent(key, value) != null) {
                invalid(target, operation);
                return true;
            }
        }
        final String worldName = args[1];
        final String displayName = alias;
        final String groupId = group;
        if (admin == null) {
            UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
            notifier.notify(target, importing
                    ? lifeCycle.importWorld(new ImportMapRequest(worldName, displayName, groupId, owner, type))
                    : lifeCycle.create(new CreateMapRequest(worldName, displayName, groupId, owner, type, parameters)));
            return true;
        }
        players.resolveId(admin).whenComplete((owner, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || owner == null) {
                notifier.notify(target, new OperationResult.Failure<>(operation, UUID.randomUUID(),
                        FailureReason.PLAYER_NOT_FOUND));
            } else {
                notifier.notify(target, importing
                        ? lifeCycle.importWorld(new ImportMapRequest(worldName, displayName, groupId, owner, type))
                        : lifeCycle.create(new CreateMapRequest(worldName, displayName, groupId, owner,
                                type, parameters)));
            }
        }));
        return true;
    }

    private void invalid(NotificationTarget target, OperationName operation) {
        notifier.notify(target, new OperationResult.Failure<>(operation, UUID.randomUUID(),
                FailureReason.INVALID_INPUT));
    }
}
