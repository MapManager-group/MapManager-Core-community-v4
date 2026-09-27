package studio.lorian.mapmanager.plugin.internal.notification;

import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import studio.lorian.mapmanager.api.result.OperationResult;

/** 将操作结果统一格式化并发送给玩家或控制台。 */
public final class OperationNotifierImpl implements OperationNotifier {
    @Override
    public void notify(NotificationTarget target, OperationResult<?> result) {
        Component message;
        if (result instanceof OperationResult.Success<?> success) {
            message = Messages.component("result.success", "operation", Messages.text("operations." + success.operation()),
                    "operationId", success.operationId());
        } else if (result instanceof OperationResult.Failure<?> failure) {
            message = Messages.component("result.failure", "reason", Messages.text("reasons." + failure.reason()),
                    "operation", Messages.text("operations." + failure.operation()), "operationId", failure.operationId());
        } else if (result instanceof OperationResult.Partial<?> partial) {
            message = Messages.component("result.partial", "reason", Messages.text("reasons." + partial.reason()),
                    "operation", Messages.text("operations." + partial.operation()), "operationId", partial.operationId());
        } else return;
        notify(target, message);
    }

    @Override
    public void notify(NotificationTarget target, String message) {
        notify(target, Component.text(message));
    }

    @Override
    public void notify(NotificationTarget target, Component message) {
        if (target.kind() == NotificationTarget.Kind.LOG_ONLY) {
            Bukkit.getLogger().info(PlainTextComponentSerializer.plainText().serialize(message));
        } else if (target.kind() == NotificationTarget.Kind.CONSOLE) {
            Bukkit.getConsoleSender().sendMessage(message);
        } else {
            var player = Bukkit.getPlayerExact(target.playerName());
            if (player != null) player.sendMessage(message);
            else Bukkit.getLogger().info(target.playerName() + ": "
                    + PlainTextComponentSerializer.plainText().serialize(message));
        }
    }
}
