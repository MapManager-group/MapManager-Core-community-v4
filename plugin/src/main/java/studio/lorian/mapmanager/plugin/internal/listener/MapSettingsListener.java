package studio.lorian.mapmanager.plugin.internal.listener;

import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;

/** 对已登记地图执行单地图物理和爆炸设置。 */
public final class MapSettingsListener implements Listener {
    private final MapRepository maps;

    /** 创建地图设置监听器。 */
    public MapSettingsListener(MapRepository maps) { this.maps = maps; }

    /** 根据世界名称查找已登记的地图。 */
    private MapInstance map(String world) { return maps.findByWorldName(world); }

    /** 根据地图物理设置取消方块物理更新。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysics(BlockPhysicsEvent event) {
        MapInstance map = map(event.getBlock().getWorld().getName());
        if (map != null && !map.settings().physical()) event.setCancelled(true);
    }

    /** 根据地图物理设置处理落沙，并在取消生成时放回方块。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFallingBlock(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)) return;
        MapInstance map = map(event.getLocation().getWorld().getName());
        if (map != null && !map.settings().physical()) {
            event.setCancelled(true);
            event.getLocation().getBlock().setType(falling.getBlockData().getMaterial(), false);
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
