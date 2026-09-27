package studio.lorian.mapmanager.plugin.internal.storage;

/** 持久化读取或写入失败。 */
public final class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) { super(message, cause); }
}
