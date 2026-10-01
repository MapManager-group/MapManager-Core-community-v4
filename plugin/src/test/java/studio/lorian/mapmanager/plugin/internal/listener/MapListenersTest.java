package studio.lorian.mapmanager.plugin.internal.listener;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.service.*;
import studio.lorian.mapmanager.plugin.internal.storage.MapRepository;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class MapListenersTest {
    private final World world = proxy(World.class, (o, m, a) -> switch (m.getName()) {
        case "getName" -> "arena"; case "getBlockAt" -> this.block; default -> throw new AssertionError(m);
    });
    private Material restored;
    private final Block block = proxy(Block.class, (o, m, a) -> switch (m.getName()) {
        case "getWorld" -> world;
        case "setType" -> { restored = (Material) a[0]; assertEquals(false, a[1]); yield null; }
        default -> throw new AssertionError(m);
    });
    private MapInstance map(boolean physical, boolean explosion) {
        return new MapInstance(UUID.randomUUID(), "arena", "team", "Arena", new MapSettings(physical, explosion, false), MapStatus.ACTIVE, null);
    }
    private MapSettingsListener settings(MapInstance map) {
        return new MapSettingsListener(proxy(MapRepository.class, (o, m, a) -> {
            assertEquals("findByWorldName", m.getName()); assertEquals("arena", a[0]); return map;
        }));
    }
    @Test void physicsAndExplosionsRespectIndependentSettings() {
        for (boolean physical : new boolean[]{true, false}) for (boolean explosion : new boolean[]{true, false}) {
            var listener = settings(map(physical, explosion));
            var physics = new BlockPhysicsEvent(block, proxy(BlockData.class, (o, m, a) -> Material.SAND)); listener.onPhysics(physics);
            assertEquals(!physical, physics.isCancelled());
            var blockExplosion = new BlockExplodeEvent(block, new ArrayList<>(), 1); listener.onBlockExplode(blockExplosion);
            assertEquals(!explosion, blockExplosion.isCancelled());
            Entity entity = proxy(Entity.class, (o, m, a) -> { throw new AssertionError(m); });
            var entityExplosion = new EntityExplodeEvent(entity, new Location(world, 0, 64, 0), new ArrayList<>(), 1);
            listener.onEntityExplode(entityExplosion); assertEquals(!explosion, entityExplosion.isCancelled());
        }
    }
    @Test void unregisteredWorldAndAlreadyCancelledEventsArePreserved() {
        var listener = settings(null);
        var event = new BlockPhysicsEvent(block, proxy(BlockData.class, (o, m, a) -> Material.SAND)); listener.onPhysics(event); assertFalse(event.isCancelled());
        event.setCancelled(true); listener.onPhysics(event); assertTrue(event.isCancelled());
        var explosion = new BlockExplodeEvent(block, new ArrayList<>(), 1); listener.onBlockExplode(explosion); assertFalse(explosion.isCancelled());
    }
    @Test void disabledPhysicsRestoresFallingBlockWithoutPhysicsUpdate() {
        BlockData data = proxy(BlockData.class, (o, m, a) -> Material.SAND);
        FallingBlock falling = proxy(FallingBlock.class, (o, m, a) -> switch (m.getName()) {
            case "getLocation" -> new Location(world, 0, 64, 0); case "getBlockData" -> data; default -> throw new AssertionError(m);
        });
        var event = new EntitySpawnEvent(falling); settings(map(false, true)).onFallingBlock(event);
        assertTrue(event.isCancelled()); assertEquals(Material.SAND, restored);
        restored = null; event = new EntitySpawnEvent(falling); settings(map(true, false)).onFallingBlock(event);
        assertFalse(event.isCancelled()); assertNull(restored);
    }
    @Test void otherEntitiesAreUnaffectedByPhysicsSetting() {
        Entity entity = proxy(Entity.class, (o, m, a) -> new Location(world, 0, 64, 0));
        var event = new EntitySpawnEvent(entity); settings(map(false, false)).onFallingBlock(event);
        assertFalse(event.isCancelled()); assertNull(restored);
    }
    @Test void strictTeleportRejectsDeniedEntryAndAllowsAuthorizedOrUnregisteredWorlds() {
        UUID id = UUID.randomUUID(); List<Object> messages = new ArrayList<>();
        Player player = proxy(Player.class, (o, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> id; case "sendMessage" -> { messages.add(a[0]); yield null; } default -> throw new AssertionError(m);
        });
        MapInstance registered = map(true, false);
        for (boolean allowed : new boolean[]{false, true}) {
            MapService maps = proxy(MapService.class, (o, m, a) -> registered);
            MapAccessService access = proxy(MapAccessService.class, (o, m, a) -> new AccessDecision(allowed, null, FailureReason.ACCESS_DENIED));
            var listener = new MapAccessListener(null, maps, access, true);
            var event = new PlayerTeleportEvent(player, new Location(world, 0, 0, 0), new Location(world, 0, 64, 0));
            listener.onTeleport(event); assertEquals(!allowed, event.isCancelled());
        }
        assertEquals(1, messages.size());
        MapService unknown = proxy(MapService.class, (o, m, a) -> null);
        var event = new PlayerTeleportEvent(player, new Location(world, 0, 0, 0), new Location(world, 0, 64, 0));
        new MapAccessListener(null, unknown, null, true).onTeleport(event); assertFalse(event.isCancelled());
    }
    @Test void disabledStrictEntryBypassesEntireListenerIncludingRevocationAndOnlineChecks() {
        var listener = new MapAccessListener(null, null, null, false);
        var event = new PlayerTeleportEvent(null, new Location(world, 0, 0, 0), new Location(world, 0, 64, 0));
        listener.onTeleport(event); assertFalse(event.isCancelled());
        listener.afterTeleport(event); listener.afterJoin(null); listener.afterWorldChange(null);
        listener.onRespawn(null); listener.afterRespawn(null);
        listener.enforceAfterPermissionRemoval(UUID.randomUUID()); listener.checkOnlinePlayers();
    }
}
