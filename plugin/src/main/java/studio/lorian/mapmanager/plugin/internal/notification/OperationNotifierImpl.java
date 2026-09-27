package studio.lorian.mapmanager.plugin.internal.notification;

import org.bukkit.Bukkit;
import studio.lorian.mapmanager.api.result.OperationResult;

/** 将操作结果统一格式化并发送给玩家或控制台。 */
public final class OperationNotifierImpl implements OperationNotifier {
    @Override
    public void notify(NotificationTarget target, OperationResult<?> result) {
        String message;
        if (result instanceof OperationResult.Success<?> success) {
            message = "success, " + success.operation() + ", " + success.operationId();
        } else if (result instanceof OperationResult.Failure<?> failure) {
            message = "failed, " + failure.reason() + ", " + failure.operation();
        } else if (result instanceof OperationResult.Partial<?> partial) {
            message = "failed, " + partial.reason() + ", " + partial.operation()
                    + " (pending, " + partial.operationId() + ")";
        } else return;
        notify(target, message);
    }

    @Override
    public void notify(NotificationTarget target, String message) {
        if (target.kind() == NotificationTarget.Kind.LOG_ONLY) {
            Bukkit.getLogger().info(message);
        } else if (target.kind() == NotificationTarget.Kind.CONSOLE) {
            Bukkit.getConsoleSender().sendMessage(message);
        } else {
            var player = Bukkit.getPlayerExact(target.playerName());
            if (player != null) player.sendMessage(message);
            else Bukkit.getLogger().info(target.playerName() + ": " + message);
        }
    }
}
