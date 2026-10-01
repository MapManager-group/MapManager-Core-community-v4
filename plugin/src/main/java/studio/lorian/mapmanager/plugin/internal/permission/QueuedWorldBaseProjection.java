package studio.lorian.mapmanager.plugin.internal.permission;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;
import studio.lorian.mapmanager.plugin.internal.storage.StorageException;
import studio.lorian.mapmanager.plugin.internal.storage.StorageSnapshot;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** 单工作线程、持久 FIFO；失败保留队首并退避重试，防止旧授予覆盖新撤销。 */
public final class QueuedWorldBaseProjection implements WorldBaseProjection, AutoCloseable {
    // ponytail: 一个全局 FIFO 保证撤权顺序；某任务持续失败会延后其他组，确有吞吐需求时改为按组队列。
    private record Job(UUID id, String action, String group, Set<String> worlds, UUID player, boolean enabled) { }
    private final WorldBaseProjection delegate;
    private final Path file;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MMC-permission-sync"); thread.setDaemon(true); return thread;
    });
    private final Map<UUID, CompletableFuture<Boolean>> waiting = new HashMap<>();
    private List<Job> jobs;
    private final Map<String, Set<UUID>> known;
    private boolean closed;
    private int failures;
    private long retryAt;

    public QueuedWorldBaseProjection(WorldBaseProjection delegate, Path file, Logger logger) throws IOException {
        this.delegate = delegate; this.file = file; this.logger = logger;
        Path inventory = file.resolveSibling("permission-members.json");
        known = Files.exists(inventory) ? gson.fromJson(Files.readString(inventory),
                new TypeToken<Map<String, Set<UUID>>>() { }.getType()) : new HashMap<>();
        if (known == null || known.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null
                || Set.of("default", "worldbase").contains(e.getKey()) || e.getValue().contains(null)))
            throw new IOException(Messages.text("maintenance.queue_invalid"));
        jobs = Files.exists(file) ? gson.fromJson(Files.readString(file), new TypeToken<List<Job>>() { }.getType()) : List.of();
        if (jobs == null || jobs.stream().anyMatch(j -> j == null || j.id() == null || j.group() == null
                || Set.of("default", "worldbase").contains(j.group()) || j.worlds() == null
                || !Set.of("ENSURE", "MEMBER", "REMOVE_GROUP", "REMOVE_WORLD").contains(j.action())
                || (j.action().equals("MEMBER") && j.player() == null)))
            throw new IOException(Messages.text("maintenance.queue_invalid"));
        worker.scheduleWithFixedDelay(this::drain, 100, 100, TimeUnit.MILLISECONDS);
    }

    private synchronized CompletableFuture<Boolean> enqueue(String action, String group, Set<String> worlds, UUID player, boolean enabled) {
        if (closed) throw new IllegalStateException(Messages.text("maintenance.queue_closed"));
        if (group == null || Set.of("default", "worldbase").contains(group))
            throw new IllegalArgumentException(Messages.text("errors.reserved_group"));
        Job job = new Job(UUID.randomUUID(), action, group, Set.copyOf(worlds), player, enabled);
        Set<UUID> tracked = new HashSet<>(known.getOrDefault(group, Set.of()));
        if (player != null) tracked.add(player);
        Map<String, Set<UUID>> inventory = new HashMap<>(known); inventory.put(group, Set.copyOf(tracked));
        try { StorageSnapshot.writeJson(file.resolveSibling("permission-members.json"), gson.toJson(inventory)); }
        catch (IOException error) { throw new StorageException(Messages.text("maintenance.queue_save_failed"), error); }
        known.clear(); known.putAll(inventory);
        List<Job> next = new ArrayList<>(jobs); next.add(job);
        persist(next); jobs = List.copyOf(next);
        CompletableFuture<Boolean> result = new CompletableFuture<>(); waiting.put(job.id(), result); return result;
    }
    private void persist(List<Job> next) {
        try { StorageSnapshot.writeJson(file, gson.toJson(next)); }
        catch (IOException e) { throw new StorageException(Messages.text("maintenance.queue_save_failed"), e); }
    }
    private void drain() {
        Job job;
        synchronized (this) {
            if (closed || jobs.isEmpty() || System.currentTimeMillis() < retryAt) return;
            job = jobs.getFirst();
        }
        try {
            boolean success = switch (job.action()) {
                case "ENSURE" -> delegate.ensureGroupInheritance(job.group(), job.worlds()).join();
                case "MEMBER" -> delegate.setBuilderAccess(job.group(), job.player(), job.enabled());
                case "REMOVE_GROUP" -> delegate.removeGroup(job.group());
                case "REMOVE_WORLD" -> delegate.removeWorldInheritance(job.group(), job.worlds().iterator().next());
                default -> false;
            };
            if (!success) throw new IllegalStateException(Messages.text("reasons.PERMISSION_SYNC_FAILED"));
            synchronized (this) {
                List<Job> next = jobs.stream().filter(j -> !j.id().equals(job.id())).toList();
                persist(next); jobs = next; failures = 0; retryAt = 0;
                CompletableFuture<Boolean> future = waiting.remove(job.id()); if (future != null) future.complete(true);
            }
        } catch (RuntimeException error) {
            synchronized (this) { failures++; retryAt = System.currentTimeMillis() + Math.min(60_000, 1_000L << Math.min(failures, 6)); }
            logger.warning(Messages.text("maintenance.queue_retry", "group", job.group(), "error", error.getMessage()));
        }
    }
    @Override public CompletableFuture<Boolean> ensureWorldBase() { return delegate.ensureWorldBase(); }
    @Override public CompletableFuture<Boolean> ensureGroupInheritance(String group, Set<String> worlds) { return enqueue("ENSURE", group, worlds, null, false); }
    @Override public boolean setBuilderAccess(String group, UUID player, boolean enabled) { enqueue("MEMBER", group, Set.of(), player, enabled); return true; }
    @Override public boolean removeGroup(String group) { enqueue("REMOVE_GROUP", group, Set.of(), null, false); return true; }
    @Override public boolean removeWorldInheritance(String group, String world) { enqueue("REMOVE_WORLD", group, Set.of(world), null, false); return true; }
    @Override public synchronized boolean pending() { return !jobs.isEmpty(); }
    /** 历史投影成员用于启动时补偿“MMC 写成功但队列写入前中断”的撤权。 */
    public synchronized Map<String, Set<UUID>> knownMembers() { return Map.copyOf(known); }
    @Override public synchronized void close() {
        closed = true; worker.shutdownNow();
        waiting.values().forEach(f -> f.completeExceptionally(new IllegalStateException(Messages.text("maintenance.queue_closed"))));
        waiting.clear();
    }
}
