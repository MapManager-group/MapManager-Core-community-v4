package studio.lorian.mapmanager.plugin.internal.notification;

import studio.lorian.mapmanager.api.result.OperationResult;

/** One internal route for command feedback and operation logging. */
public interface OperationNotifier {
    void notify(NotificationTarget target, OperationResult<?> result);
}
