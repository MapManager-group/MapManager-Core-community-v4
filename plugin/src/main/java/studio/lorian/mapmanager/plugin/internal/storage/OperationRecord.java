package studio.lorian.mapmanager.plugin.internal.storage;

import studio.lorian.mapmanager.api.result.FailureReason;
import studio.lorian.mapmanager.api.result.OperationName;

import java.util.UUID;

public record OperationRecord(
        UUID id,
        OperationName name,
        String worldName,
        OperationStage stage,
        FailureReason reason
) {
}
