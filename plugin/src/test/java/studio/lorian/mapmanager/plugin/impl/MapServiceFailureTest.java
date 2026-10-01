package studio.lorian.mapmanager.plugin.impl;

import org.junit.jupiter.api.Test;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjection;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class MapServiceFailureTest {
    private final MapInstance map = new MapInstance(UUID.randomUUID(), "arena", "team", "Arena",
            new MapSettings(true, false, false), MapStatus.ACTIVE, null);
    private final UUID player = UUID.randomUUID();
    private final AtomicBoolean written = new AtomicBoolean();
    private final MapRepository maps = proxy(MapRepository.class, (o, m, a) -> {
        if (m.getName().equals("findById")) return map;
        if (m.getName().equals("findGroup")) return new MapGroup("team", Set.of("arena"));
        throw new AssertionError(m);
    });
    private RoleRepository roles(boolean failWrite, boolean failRead) {
        return proxy(RoleRepository.class, (o, m, a) -> {
            if (m.getName().equals("add") || m.getName().equals("remove")) {
                if (failWrite) throw new StorageException("write failed", null);
                written.set(true); return true;
            }
            if (m.getName().equals("members")) {
                if (failRead) throw new StorageException("read failed", null);
                return Set.of(player);
            }
            throw new AssertionError(m);
        });
    }
    @Test void roleWriteFailureReportsFailureBeforeProjection() {
        var service = new MapRoleServiceImpl(maps, roles(true, false), null);
        var result = assertInstanceOf(OperationResult.Failure.class, service.addMember(map.id(), player, MapRole.BUILDER));
        assertEquals(FailureReason.STORAGE_FAILED, result.reason()); assertFalse(written.get());
    }
    @Test void postWriteReadFailureReportsPartialInsteadOfFailure() {
        var service = new MapRoleServiceImpl(maps, roles(false, true), null);
        var result = assertInstanceOf(OperationResult.Partial.class, service.addMember(map.id(), player, MapRole.BUILDER));
        assertEquals(FailureReason.STORAGE_FAILED, result.reason()); assertTrue(written.get());
    }
    @Test void projectionExceptionAfterGrantReportsPartial() {
        var projection = proxy(WorldBaseProjection.class, (o, m, a) -> { throw new IllegalStateException("LP unavailable"); });
        var service = new MapRoleServiceImpl(maps, roles(false, false), projection);
        assertEquals(FailureReason.PERMISSION_SYNC_FAILED, assertInstanceOf(OperationResult.Partial.class,
                service.addMember(map.id(), player, MapRole.ADMIN)).reason()); assertTrue(written.get());
    }
    @Test void exceptionalInheritanceFutureReportsPartial() {
        var projection = proxy(WorldBaseProjection.class, (o, m, a) -> CompletableFuture.failedFuture(new IllegalStateException("LP unavailable")));
        var service = new MapRoleServiceImpl(maps, roles(false, false), projection);
        assertEquals(FailureReason.PERMISSION_SYNC_FAILED, assertInstanceOf(OperationResult.Partial.class,
                service.removeMember(map.id(), player, MapRole.ADMIN)).reason()); assertTrue(written.get());
    }
    @Test void roleInvalidInputDoesNotReachRepositories() {
        var service = new MapRoleServiceImpl(null, null, null);
        for (OperationResult<?> result : List.of(service.addMember(null, player, MapRole.ADMIN),
                service.removeMember(map.id(), null, MapRole.BUILDER), service.addMember(map.id(), player, null))) {
            assertEquals(FailureReason.INVALID_INPUT, assertInstanceOf(OperationResult.Failure.class, result).reason());
        }
    }
    @Test void settingsReadAndWriteStorageFailuresAreReported() {
        for (boolean failRead : new boolean[]{true, false}) {
            var repository = proxy(MapRepository.class, (o, m, a) -> {
                if (m.getName().equals("findById") && !failRead) return map;
                throw new StorageException("injected", null);
            });
            var service = new MapSettingsServiceImpl(repository, null);
            assertEquals(FailureReason.STORAGE_FAILED, assertInstanceOf(OperationResult.Failure.class,
                    service.setPhysical(map.id(), false)).reason());
            assertEquals(FailureReason.STORAGE_FAILED, assertInstanceOf(OperationResult.Failure.class,
                    service.setExplosion(map.id(), true)).reason());
        }
    }
}
