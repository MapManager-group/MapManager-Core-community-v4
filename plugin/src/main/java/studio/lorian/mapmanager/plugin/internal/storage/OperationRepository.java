package studio.lorian.mapmanager.plugin.internal.storage;

import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;

import java.util.List;
import java.util.UUID;

public interface OperationRepository {
    OperationRecord find(UUID operationId);
    List<OperationRecord> unfinished();
    default List<OperationRecord> allOperations() { return unfinished(); }
    void save(OperationRecord record);
}
