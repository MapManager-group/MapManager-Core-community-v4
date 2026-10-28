package studio.lorian.mapmanager.plugin.internal.listener;

import java.util.function.Predicate;

import org.bukkit.entity.FallingBlock;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;

/** 全局保护装饰实体支撑；对已登记非默认地图执行方块物理和爆炸设置。 */
public final class MapSettingsListener implements Listener {
    private final Predicate<String> defaultWorld;
    private final MapRepository maps;

    /** 创建地图设置监听器。 */
    public MapSettingsListener(MapRepository maps) {
        this(maps, world -> false);
    }

    public MapSettingsListener(MapRepository maps, Predicate<String> defaultWorld) {
        this.defaultWorld = defaultWorld;
        this.maps = maps;
    }

    /** 根据世界名称查找已登记的地图。 */
    private MapInstance map(String world) { return defaultWorld.test(world) ? null : maps.findByWorldName(world); }

    /** 根据地图物理设置取消方块物理更新。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysics(BlockPhysicsEvent event) {
        MapInstance map = map(event.getBlock().getWorld().getName());
        if (map != null && !map.settings().physical()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPhysics(HangingBreakEvent event) {
        if (cancelEntityPhysics(event.getCause().name())) event.setCancelled(true);
    }

    static boolean cancelEntityPhysics(String cause) {
        return cause.equals("PHYSICS") || cause.equals("OBSTRUCTION");
    }

    /** 26.3 的坐垫使用新事件；通过条件注册保留旧版 Paper 兼容。 */
    public void registerEntityPhysics(JavaPlugin plugin) {
        try {
            Class<? extends Event> type = Class.forName("io.papermc.paper.event.entity.EntityBreakEvent")
                    .asSubclass(Event.class);
            var getCause = type.getMethod("getCause");
            plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.HIGHEST,
                    (listener, event) -> {
                        try {
                            if (cancelEntityPhysics(((Enum<?>) getCause.invoke(event)).name()))
                                ((Cancellable) event).setCancelled(true);
                        } catch (ReflectiveOperationException error) {
                            throw new EventException(error);
                        }
                    }, plugin, true);
        } catch (ClassNotFoundException ignored) {
            // 旧版服务器的悬挂实体继续由 HangingBreakEvent 处理。
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException("EntityBreakEvent.getCause is unavailable", error);
        }
    }

    /** 根据地图物理设置处理落沙，并在取消生成时放回方块。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFallingBlock(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)) return;
        MapInstance map = map(event.getLocation().getWorld().getName());
        if (map != null && !map.settings().physical()) {
            event.setCancelled(true);
            event.getLocation().getBlock().setBlockData(falling.getBlockData(), false);
        }
    }

    /** 根据地图爆炸设置取消方块爆炸。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        MapInstance map = map(event.getBlock().getWorld().getName());
        if (map != null && !map.settings().explosion()) event.setCancelled(true);
    }

    /** 根据地图爆炸设置取消实体爆炸。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        MapInstance map = map(event.getLocation().getWorld().getName());
        if (map != null && !map.settings().explosion()) event.setCancelled(true);
    }
}
