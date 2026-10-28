package studio.lorian.mapmanager.plugin.internal.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class QueuedWorldBaseProjectionTest {
    @TempDir Path directory;
    static class Projection implements WorldBaseProjection {
        CompletableFuture<Boolean> inheritance = CompletableFuture.completedFuture(true);
        final List<Boolean> changes = new CopyOnWriteArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean();
        @Override public CompletableFuture<Boolean> ensureWorldBase() { return CompletableFuture.completedFuture(false); }
        @Override public CompletableFuture<Boolean> ensureGroupInheritance(String group, Set<String> worlds) { return inheritance; }
        @Override public boolean setBuilderAccess(String group, UUID player, boolean enabled) {
            assertTrue(Thread.currentThread().getName().startsWith("MMC-permission-sync"));
            if (fail.get()) throw new IllegalStateException("temporary failure"); changes.add(enabled); return true;
        }
        @Override public boolean removeGroup(String group) { return true; }
        @Override public boolean removeWorldInheritance(String group, String world) { return true; }
    }
    private QueuedWorldBaseProjection queue(Projection projection) throws Exception {
        return new QueuedWorldBaseProjection(projection, directory.resolve("queue.json"), Logger.getLogger("test"));
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean());
    }
    @Test void mainCallerNeverWaitsForLuckPermsAndMutationsRemainOrdered() throws Exception {
        Projection projection = new Projection(); projection.inheritance = new CompletableFuture<>(); UUID player = UUID.randomUUID();
        try (var queue = queue(projection)) {
            CompletableFuture<Boolean> future = queue.ensureGroupInheritance("team", Set.of("arena"));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                assertTrue(queue.setBuilderAccess("team", player, true)); assertTrue(queue.setBuilderAccess("team", player, false));
            });
            assertFalse(future.isDone()); assertTrue(queue.pending());
            projection.inheritance.complete(true); future.get(3, TimeUnit.SECONDS); await(() -> !queue.pending());
            assertEquals(List.of(true, false), projection.changes); assertEquals(Set.of(player), queue.knownMembers().get("team"));
        }
    }
    @Test void failedJobsSurviveRestartAndRetryBeforeLaterRevocations() throws Exception {
        Projection failing = new Projection(); failing.fail.set(true); UUID player = UUID.randomUUID();
        try (var queue = queue(failing)) {
            queue.setBuilderAccess("team", player, true); queue.setBuilderAccess("team", player, false);
            Thread.sleep(250); assertTrue(queue.pending());
        }
        Projection recovered = new Projection();
        try (var queue = queue(recovered)) {
            assertEquals(Set.of(player), queue.knownMembers().get("team"));
            await(() -> !queue.pending()); assertEquals(List.of(true, false), recovered.changes);
        }
    }
    @Test void failureAutomaticallyRetriesWithoutRepeatingTheCommand() throws Exception {
        Projection projection = new Projection(); projection.fail.set(true);
        try (var queue = queue(projection)) {
            queue.setBuilderAccess("team", UUID.randomUUID(), true);
            Thread.sleep(300); projection.fail.set(false);
            await(() -> !queue.pending()); assertEquals(List.of(true), projection.changes);
        }
    }
    @Test void reservedGroupVariantsNeverEnterThePersistentQueue() throws Exception {
        try (var queue = queue(new Projection())) {
            for (String group : List.of("DEFAULT", "Default", "WorldBase", "WORLDBASE")) {
                assertThrows(IllegalArgumentException.class, () -> queue.ensureGroupInheritance(group, Set.of("arena")));
                assertThrows(IllegalArgumentException.class, () -> queue.setBuilderAccess(group, UUID.randomUUID(), true));
                assertThrows(IllegalArgumentException.class, () -> queue.removeGroup(group));
                assertThrows(IllegalArgumentException.class, () -> queue.removeWorldInheritance(group, "arena"));
            }
            assertFalse(queue.pending());
            assertTrue(queue.knownMembers().isEmpty());
            assertFalse(Files.exists(directory.resolve("queue.json")));
        }
    }

    @Test void reservedGroupsInPersistedJobsAndInventoriesAreRejected() throws Exception {
        var file = directory.resolve("queue.json");
        Files.writeString(file, "[{\"id\":\"" + UUID.randomUUID() + "\",\"action\":\"REMOVE_GROUP\",\"group\":\"WorldBase\",\"worlds\":[],\"enabled\":false}]");
        assertThrows(java.io.IOException.class, () -> queue(new Projection()));
        Files.writeString(file, "[]");
        Files.writeString(directory.resolve("permission-members.json"), "{\"Default\":[]}");
        assertThrows(java.io.IOException.class, () -> queue(new Projection()));
    }

    @Test void corruptJournalsAreRejectedRatherThanDropped() throws Exception {
        Files.writeString(directory.resolve("queue.json"), "null");
        assertThrows(java.io.IOException.class, () -> queue(new Projection()));
    }
}
