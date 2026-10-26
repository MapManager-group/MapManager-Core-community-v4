package mmc.probe;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.MapManager;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.model.WorldType;
import studio.lorian.mapmanager.api.result.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.options.RemoveWorldOptions;
import org.mvplugins.multiverse.core.world.options.UnloadWorldOptions;

/** 只作为隔离测试服的外部插件编译；API 由 join-classpath 提供，不嵌入另一份 API。 */
public final class ApiProbe extends JavaPlugin implements Listener {
    public static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private int checks;
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PROBE INITIAL_SERVICE=" + (api() != null));
        getServer().getCommandMap().register("mmcprobe", new Command("mmcprobe") {
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                try {
                    if (args.length == 1 && args[0].equals("suite")) suite();
                    else if (args.length == 1 && args[0].equals("load")) {
                        var loaded = MultiverseCoreApi.get().getWorldManager().getLoadedWorld("probe-flat").get();
                        check(MultiverseCoreApi.get().getWorldManager().unloadWorld(UnloadWorldOptions.world(loaded)).isSuccess(), "native unload");
                        check(Bukkit.getWorld("probe-flat") == null, "unload precondition");
                        accepted(api().worldLifeCycle().load("probe-flat"));
                        check(Bukkit.getWorld("probe-flat") != null, "public lifecycle load");
                        getLogger().info("PROBE LOAD_PASS");
                    } else if (args.length == 1 && args[0].equals("projection")) {
                        LuckPerms lp = getServer().getServicesManager().load(LuckPerms.class);
                        var owner = lp.getUserManager().loadUser(OWNER);
                        var guest = lp.getUserManager().loadUser(UUID.fromString("00000000-0000-0000-0000-000000000002"));
                        var group = lp.getGroupManager().loadGroup("team");
                        CompletableFuture.allOf(owner, guest, group).whenComplete((ignored, error) -> getServer().getScheduler().runTask(ApiProbe.this, () -> {
                            try {
                                check(error == null, "LP async query");
                                check(owner.join().getNodes().stream().anyMatch(n -> n instanceof InheritanceNode node && node.getGroupName().equals("team")), "owner projection");
                                check(guest.join().getNodes().stream().noneMatch(n -> n instanceof InheritanceNode node && node.getGroupName().equals("team")), "guest revocation projection");
                                check(group.join().orElseThrow().getNodes().stream().filter(n -> n instanceof InheritanceNode node && node.getGroupName().equals("worldbase")).count() == 3, "all group world contexts");
                                getLogger().info("PROBE PROJECTION_PASS");
                            } catch (Throwable failure) { getLogger().log(java.util.logging.Level.SEVERE, "PROBE FAIL", failure); }
                        }));
                    } else if (args.length == 1 && args[0].equals("remove-left")) {
                        var world = MultiverseCoreApi.get().getWorldManager().getWorld("left_bak").get();
                        check(MultiverseCoreApi.get().getWorldManager().removeWorld(RemoveWorldOptions.world(world)).isSuccess(), "remove MV only");
                        getLogger().info("PROBE REMOVE_LEFT_PASS");
                    } else if (args.length == 1 && args[0].equals("recovery")) {
                        MapInstance map = api().maps().findByWorldName("probe-nether");
                        check(map != null && map.status() == MapStatus.ACTIVE, "existing pending recovered");
                        check(api().roles().hasRole(map.id(), OWNER, MapRole.ADMIN), "owner recovered");
                        MapInstance fresh = api().maps().findByWorldName("resume-none");
                        check(fresh != null && fresh.status() == MapStatus.ACTIVE, "pre-map-save journal recovered");
                        check(api().roles().hasRole(fresh.id(), OWNER, MapRole.ADMIN), "journal owner retained");
                        check(api().maps().findByWorldName("resume_bak") == null && Bukkit.getWorld("resume_bak") == null,
                                "deleting interruption recovered");
                        check(api().maps().findByWorldName("gone_bak") == null, "missing-folder MV registration recovered");
                        check(api().maps().findByWorldName("left_bak") == null, "orphan-folder deletion recovered");
                        check(api().maps().findByWorldName("guard_bak").status() == MapStatus.DELETING,
                                "replaced directory remains protected");
                        getLogger().info("PROBE RECOVERY_PASS");
                    } else if (args.length == 1 && args[0].equals("identity")) {
                        for (World world : Bukkit.getWorlds()) getLogger().info("PROBE DIR " + world.getName() + " "
                                + Files.readAttributes(world.getWorldFolder().toPath(), java.nio.file.attribute.BasicFileAttributes.class).fileKey());
                    } else if (args.length == 1 && args[0].equals("disable"))
                        getServer().getPluginManager().disablePlugin(getServer().getPluginManager().getPlugin("MapManager-Core"));
                } catch (Throwable error) { getLogger().log(java.util.logging.Level.SEVERE, "PROBE FAIL", error); }
                return true;
            }
        });
    }
    @EventHandler public void registered(ServiceRegisterEvent event) {
        if (event.getProvider().getService().equals(MapManager.class)) {
            check(event.getProvider().getProvider() == api(), "same shared API class/provider");
            getLogger().info("PROBE REGISTER_PASS");
        }
    }
    @EventHandler public void unregistered(ServiceUnregisterEvent event) {
        if (event.getProvider().getService().equals(MapManager.class)) getServer().getScheduler().runTask(this, () -> {
            check(api() == null, "service revoked"); getLogger().info("PROBE UNREGISTER_PASS");
        });
    }
    private MapManager api() { return getServer().getServicesManager().load(MapManager.class); }
    private <T> T accepted(OperationResult<T> result) {
        if (result instanceof OperationResult.Success<T> success) return success.value();
        if (result instanceof OperationResult.Partial<T> partial && partial.reason() == FailureReason.PERMISSION_SYNC_PENDING) return partial.value();
        throw new AssertionError(result);
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private void suite() {
        check(api() != null, "service");
        check(api().maps() != null && api().roles() != null && api().settings() != null && api().access() != null
                && api().aliases() != null && api().travel() != null && api().worldVersions() != null && api().worldLifeCycle() != null, "service graph");
        for (Environment environment : List.of(Environment.NORMAL, Environment.NETHER, Environment.THE_END)) {
            String name = environment == Environment.NORMAL ? "probe-flat" : environment == Environment.NETHER ? "probe-nether" : "probe-end";
            MapInstance map = accepted(api().worldLifeCycle().create(new CreateMapRequest(name, name, "team", OWNER,
                    WorldType.FLAT, environment, Map.of("flat.height", "64"))));
            check(map.status() == MapStatus.ACTIVE, "active " + name);
            World world = Bukkit.getWorld(name); check(world != null, "loaded " + name);
            check(world.getEnvironment().name().equals(environment.name()), "environment " + name);
            check(world.getBlockAt(0, world.getMinHeight(), 0).getType() == Material.BEDROCK, "bedrock " + name);
            check(world.getBlockAt(0, 64, 0).getType() == Material.GRASS_BLOCK, "flat layers " + name);
        }
        UUID guest = UUID.fromString("00000000-0000-0000-0000-000000000002");
        MapInstance map = api().maps().findByWorldName("probe-flat");
        check(api().roles().hasRole(map.id(), OWNER, MapRole.ADMIN), "owner role");
        check(!api().access().decide(guest, map.id()).allowed(), "private denies guest");
        Player fake = fakePlayer(guest, Bukkit.getWorld("probe-flat"));
        check(teleportEvent(fake).isCancelled(), "other-plugin teleport denied");
        accepted(api().roles().addMember(map.id(), guest, MapRole.VISITOR));
        check(!teleportEvent(fake).isCancelled(), "visitor teleport accepted");
        accepted(api().roles().removeMember(map.id(), guest, MapRole.VISITOR));
        check(teleportEvent(fake).isCancelled(), "revocation visible immediately");
        PlayerRespawnEvent respawn = new PlayerRespawnEvent(fake, Bukkit.getWorld("probe-flat").getSpawnLocation(), false);
        Bukkit.getPluginManager().callEvent(respawn);
        check(respawn.getRespawnLocation().getWorld() == Bukkit.getWorld("world"), "respawn fallback");
        accepted(api().roles().addMember(map.id(), guest, MapRole.BUILDER));
        accepted(api().roles().addMember(map.id(), guest, MapRole.ADMIN));
        accepted(api().roles().removeMember(map.id(), guest, MapRole.ADMIN));
        check(api().roles().hasRole(map.id(), guest, MapRole.BUILDER), "other role preserved");
        accepted(api().roles().removeMember(map.id(), guest, MapRole.BUILDER));
        check(!api().access().decide(guest, map.id()).allowed(), "last role revoked");
        accepted(api().settings().setPublic(map.id(), true)); check(!teleportEvent(fake).isCancelled(), "public guest permitted");
        accepted(api().settings().setPublic(map.id(), false));
        MapInstance deleted = accepted(api().worldLifeCycle().create(new CreateMapRequest("probe_bak", "Probe", "delete-team", OWNER,
                WorldType.FLAT, Environment.NORMAL, Map.of("flat.height", "8"))));
        accepted(api().worldLifeCycle().delete(deleted.worldName()));
        check(api().maps().findByWorldName(deleted.worldName()) == null && Bukkit.getWorld(deleted.worldName()) == null, "API deletion");
        check(api().worldLifeCycle().delete("world") instanceof OperationResult.Failure<?>, "default world protected");
        getLogger().info("PROBE SUITE_PASS checks=" + checks);
    }
    private PlayerTeleportEvent teleportEvent(Player player) {
        PlayerTeleportEvent event = new PlayerTeleportEvent(player, Bukkit.getWorld("world").getSpawnLocation(),
                Bukkit.getWorld("probe-flat").getSpawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        Bukkit.getPluginManager().callEvent(event); return event;
    }
    private Player fakePlayer(UUID id, World world) {
        return (Player) Proxy.newProxyInstance(getClassLoader(), new Class[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getWorld" -> world;
            case "getName" -> "ProbeGuest";
            case "isOnline" -> true;
            case "getServer" -> getServer();
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "ProbeGuest";
            default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
        });
    }
}
