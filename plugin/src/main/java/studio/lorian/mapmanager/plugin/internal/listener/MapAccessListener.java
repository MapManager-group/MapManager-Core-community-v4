package studio.lorian.mapmanager.plugin.internal.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.service.MapAccessService;
import studio.lorian.mapmanager.api.service.MapService;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;

import java.util.UUID;
import java.util.logging.Level;

/** 按 MMC 准入规则处理玩家进入地图、登录位置、重生和撤权后的移出。 */
public final class MapAccessListener implements Listener {
    private final JavaPlugin plugin;
    private final MapService maps;
    private final MapAccessService access;
    private final boolean strictEnabled;

    /** 创建地图准入监听器并注入地图、准入服务及严格校验开关。 */
    public MapAccessListener(JavaPlugin plugin, MapService maps, MapAccessService access, boolean strictEnabled) {
        this.plugin = plugin;
        this.maps = maps;
        this.access = access;
        this.strictEnabled = strictEnabled;
    }

    /** 在传送前校验玩家是否有权进入目标地图。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!strictEnabled || event.getTo() == null || event.getTo().getWorld() == null) return;
        if (canEnter(event.getPlayer(), event.getTo().getWorld())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Messages.component("access.denied", "world", event.getTo().getWorld().getName()));
    }

    /** 在传送事件完成后排队复核玩家的实际所在地图。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void afterTeleport(PlayerTeleportEvent event) {
        if (strictEnabled) scheduleCheck(event.getPlayer());
    }

    /** 在世界切换完成后排队复核玩家的当前地图准入。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void afterWorldChange(PlayerChangedWorldEvent event) {
        if (strictEnabled) scheduleCheck(event.getPlayer());
    }

    /** 玩家加入服务器后排队检查其登录所在地图。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void afterJoin(PlayerJoinEvent event) {
        if (strictEnabled) scheduleCheck(event.getPlayer());
    }

    /** 在重生位置生效前校验玩家是否有权进入目标地图。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!strictEnabled || event.getRespawnLocation().getWorld() == null) return;
        if (canEnter(event.getPlayer(), event.getRespawnLocation().getWorld())) return;

        String deniedWorld = event.getRespawnLocation().getWorld().getName();
        Location fallback = defaultSpawn(event.getPlayer());
        if (fallback == null) {
            disconnect(event.getPlayer());
            return;
        }
        event.setRespawnLocation(fallback);
        event.getPlayer().sendMessage(Messages.component("access.returned_to_default",
                "world", deniedWorld));
    }

    /** 重生完成后排队复核玩家的实际所在地图。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void afterRespawn(PlayerRespawnEvent event) {
        if (strictEnabled) scheduleCheck(event.getPlayer());
    }

    /** MMC 角色撤销持久化后，复核玩家当前地图的准入权限。 */
    public void enforceAfterPermissionRemoval(UUID playerId) {
        if (!strictEnabled) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) checkCurrentWorld(player, "access.permission_revoked");
    }

    /** 启动迁移完成后，复核当前已在线玩家所在地图的准入权限。 */
    public void checkOnlinePlayers() {
        if (strictEnabled) Bukkit.getOnlinePlayers().forEach(this::scheduleCheck);
    }

    /** 将玩家的当前地图检查排入主线程，并在执行时确认玩家仍在线。 */
    private void scheduleCheck(Player player) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (plugin.isEnabled() && online != null) checkCurrentWorld(online, "access.returned_to_default");
        });
    }

    /** 检查玩家当前地图；无权进入时送回默认世界，校验异常时断开连接。 */
    private void checkCurrentWorld(Player player, String messageKey) {
        try {
            MapInstance map = maps.findByWorldName(player.getWorld().getName());
            if (map == null || access.decide(player.getUniqueId(), map.id()).allowed()) return;
            returnToDefault(player, map.worldName(), messageKey);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not verify map access for " + player.getName(), exception);
            disconnect(player);
        }
    }

    /** 判断玩家是否可以进入指定世界；未登记为地图的世界默认允许进入。 */
    private boolean canEnter(Player player, World world) {
        try {
            MapInstance map = maps.findByWorldName(world.getName());
            return map == null || access.decide(player.getUniqueId(), map.id()).allowed();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not verify map access for " + player.getName()
                    + " entering " + world.getName(), exception);
            return false;
        }
    }

    /** 将玩家送回默认世界，并在无法安全传送时断开连接。 */
    private void returnToDefault(Player player, String deniedWorld, String messageKey) {
        Location fallback = defaultSpawn(player);
        try {
            if (fallback == null || !player.teleport(fallback)) {
                disconnect(player);
                return;
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not move " + player.getName() + " to the default world", exception);
            disconnect(player);
            return;
        }
        player.sendMessage(Messages.component(messageKey, "world", deniedWorld));
    }

    /** 获取玩家有权进入且不同于当前世界的 Multiverse 默认世界出生点。 */
    private Location defaultSpawn(Player player) {
        try {
            var defaultWorld = MultiverseCoreApi.get().getWorldManager().getDefaultWorld().getOrNull();
            if (defaultWorld == null) return null;
            Location spawn = defaultWorld.getSpawnLocation();
            if (spawn == null || spawn.getWorld() == null || spawn.getWorld().equals(player.getWorld())) return null;
            return canEnter(player, spawn.getWorld()) ? spawn : null;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not find a safe default world for " + player.getName(), exception);
            return null;
        }
    }

    /** 因无法确认或恢复安全的地图位置而断开玩家连接。 */
    private void disconnect(Player player) {
        plugin.getLogger().warning("Disconnecting " + player.getName()
                + " because a safe default world could not be reached.");
        player.kick(Messages.component("access.recovery_failed"));
    }
}
