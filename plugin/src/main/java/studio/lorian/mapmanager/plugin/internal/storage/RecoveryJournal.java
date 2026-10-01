package studio.lorian.mapmanager.plugin.internal.storage;

import com.google.gson.Gson;
import studio.lorian.mapmanager.api.model.*;
import studio.lorian.mapmanager.api.result.OperationName;
import studio.lorian.mapmanager.plugin.internal.notification.Messages;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 生命周期外部副作用前保存完整请求和原地图身份；恢复不猜测生成器或所有者。 */
public final class RecoveryJournal {
    public record Plan(UUID operationId, OperationName operation, MapInstance map,
                       CreateMapRequest create, ImportMapRequest imported,
                       String environment, String generator, String generatorSettings,
                       String worldIdentity, String directoryIdentity, Set<UUID> members) { }
    private final Path directory;
    private final Gson gson = new Gson();
    public RecoveryJournal(Path directory) { this.directory = directory; }
    private Path file(String world) {
        if (world == null || !world.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException(Messages.text("errors.invalid_world_name"));
        return directory.resolve(world + ".json");
    }
    public void save(Plan plan) {
        try { StorageSnapshot.writeJson(file(plan.map().worldName()), gson.toJson(plan)); }
        catch (IOException error) { throw new StorageException(Messages.text("maintenance.recovery_save_failed"), error); }
    }
    public Plan read(String world) {
        Path file = file(world);
        if (!Files.exists(file)) return null;
        try {
            Plan plan = gson.fromJson(Files.readString(file), Plan.class);
            if (plan == null || plan.operationId() == null || plan.operation() == null || plan.map() == null
                    || !plan.map().worldName().equals(world) || plan.members() == null)
                throw new IllegalArgumentException(Messages.text("maintenance.recovery_invalid"));
            StorageValidation.map(plan.map());
            if (plan.operation() == OperationName.CREATE && (plan.create() == null || plan.imported() != null
                    || !world.equals(plan.create().worldName()))
                    || plan.operation() == OperationName.IMPORT && (plan.imported() == null || plan.create() != null
                    || !world.equals(plan.imported().worldName()))
                    || plan.operation() == OperationName.DELETE && (plan.create() != null || plan.imported() != null))
                throw new IllegalArgumentException(Messages.text("maintenance.recovery_invalid"));
            return plan;
        } catch (IOException | RuntimeException error) { throw new StorageException(Messages.text("maintenance.recovery_invalid"), error); }
    }
    public List<Plan> all() {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().map(path -> read(path.getFileName().toString().replaceFirst("\\.json$", ""))).toList();
        } catch (IOException error) { throw new StorageException(Messages.text("maintenance.recovery_invalid"), error); }
    }
    public void remove(String world) {
        try { Files.deleteIfExists(file(world)); }
        catch (IOException error) { throw new StorageException(Messages.text("maintenance.recovery_save_failed"), error); }
    }
    public static String directoryIdentity(Path folder) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Object key = Files.readAttributes(folder, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            return key == null ? null : key.toString();
        } catch (IOException error) { throw new StorageException(Messages.text("maintenance.recovery_invalid"), error); }
    }
    public static String identity(Path folder) {
        Path uid = folder.resolve("uid.dat");
        if (!Files.isRegularFile(uid, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            byte[] bytes = Files.readAllBytes(uid);
            if (bytes.length != 16) throw new IOException(Messages.text("maintenance.recovery_invalid"));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException error) { throw new StorageException(Messages.text("maintenance.recovery_invalid"), error); }
    }
}
