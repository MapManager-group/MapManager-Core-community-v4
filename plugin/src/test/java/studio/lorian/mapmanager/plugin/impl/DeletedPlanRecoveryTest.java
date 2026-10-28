package studio.lorian.mapmanager.plugin.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.*;
import studio.lorian.mapmanager.plugin.internal.permission.WorldBaseProjection;
import studio.lorian.mapmanager.plugin.internal.storage.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static studio.lorian.mapmanager.plugin.testsupport.BukkitTestServer.proxy;

class DeletedPlanRecoveryTest {
    @TempDir Path directory;

    @Test void completedCleanupReturnsSuccessAndPreservesOtherGroups() throws Exception {
        for (boolean shared : List.of(false, true)) {
            Path data = directory.resolve(Boolean.toString(shared));
            var store = new JsonStorage(data.resolve("maps.json"));
            var journal = new RecoveryJournal(data.resolve("world-recovery"));
            var map = new MapInstance(UUID.randomUUID(), "gone_bak", "team", "Gone", new MapSettings(false, false, false), MapStatus.DELETING, null);
            UUID member = UUID.randomUUID();
            var plan = new RecoveryJournal.Plan(UUID.randomUUID(), OperationName.DELETE, map, null, null,
                    null, null, null, null, null, Set.of(member));
            journal.save(plan);
            for (String group : List.of("team", "unrelated")) {
                store.saveGroup(new MapGroup(group, Set.of()));
                store.add(group, MapRole.ADMIN, member);
            }
            if (shared) store.save(new MapInstance(UUID.randomUUID(), "remaining", "team", "Remaining", map.settings(), MapStatus.ACTIVE, null));
            List<String> cleanup = new ArrayList<>();
            var projection = proxy(WorldBaseProjection.class, (o, method, args) -> {
                if (method.getName().equals("pending")) return false;
                cleanup.add(method.getName());
                assertEquals("team", args[0]);
                if (method.getName().equals("setBuilderAccess")) {
                    assertEquals(member, args[1]);
                    assertEquals(false, args[2]);
                } else if (method.getName().equals("removeWorldInheritance")) {
                    assertEquals(map.worldName(), args[1]);
                }
                return true;
            });
            var service = new WorldLifeCycleServiceImpl(store, store, store, projection, null, null,
                    Pattern.compile("(?!)"), Logger.getLogger("recovery"), data);
            assertInstanceOf(OperationResult.Success.class, service.finishDeletedPlan(plan));
            assertEquals(shared ? List.of("removeWorldInheritance") : List.of("setBuilderAccess", "removeGroup"), cleanup);
            assertEquals(shared, store.findGroup("team") != null);
            assertEquals(shared ? Set.of(member) : Set.of(), store.members("team", MapRole.ADMIN));
            assertEquals(Set.of(member), store.members("unrelated", MapRole.ADMIN));
            assertNull(journal.read(map.worldName()));
            assertEquals(OperationStage.COMPLETED, store.find(plan.operationId()).stage());
        }
    }

    @Test void cleanupFailuresRetainJournalRolesAndAuditUntilRetrySucceeds() throws Exception {
        for (String failing : List.of("setBuilderAccess", "removeGroup", "removeWorldInheritance")) {
            Path data = directory.resolve(failing);
            var store = new JsonStorage(data.resolve("maps.json"));
            var journal = new RecoveryJournal(data.resolve("world-recovery"));
            var map = new MapInstance(UUID.randomUUID(), "gone_bak", "team", "Gone", new MapSettings(false, false, false), MapStatus.DELETING, null);
            UUID member = UUID.randomUUID(), id = UUID.randomUUID();
            var plan = new RecoveryJournal.Plan(id, OperationName.DELETE, map, null, null, null, null, null, null, null, Set.of(member));
            journal.save(plan);
            store.saveGroup(new MapGroup("team", Set.of()));
            store.add("team", MapRole.ADMIN, member);
            if (failing.equals("removeWorldInheritance"))
                store.save(new MapInstance(UUID.randomUUID(), "other", "team", "Other", map.settings(), MapStatus.ACTIVE, null));
            var operation = new OperationRecord(id, OperationName.DELETE, map.worldName(), OperationStage.WORLD_CHANGED, null);
            store.save(operation);
            boolean[] fail = {true};
            var projection = proxy(WorldBaseProjection.class, (o, method, args) ->
                    method.getName().equals("pending") || !(fail[0] && method.getName().equals(failing)));
            var service = new WorldLifeCycleServiceImpl(store, store, store, projection, null, null,
                    Pattern.compile("(?!)"), Logger.getLogger("recovery"), data);
            assertEquals(FailureReason.PERMISSION_SYNC_FAILED,
                    assertInstanceOf(OperationResult.Failure.class, service.finishDeletedPlan(plan)).reason());
            assertEquals(plan, journal.read(map.worldName()));
            assertEquals(operation, store.find(id));
            assertEquals(Set.of(member), store.members("team", MapRole.ADMIN));
            fail[0] = false;
            assertEquals(FailureReason.PERMISSION_SYNC_PENDING,
                    assertInstanceOf(OperationResult.Partial.class, service.finishDeletedPlan(plan)).reason());
            assertNull(journal.read(map.worldName()));
            assertEquals(OperationStage.COMPLETED, store.find(id).stage());
            assertEquals(!failing.equals("removeWorldInheritance"), store.findGroup("team") == null);
        }
    }
}
