package studio.lorian.mapmanager.plugin.internal.notification;

import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import studio.lorian.mapmanager.api.result.OperationResult;

/** 将操作结果统一格式化并发送给玩家或控制台。 */
public final class OperationNotifierImpl implements OperationNotifier {
    @Override
    public void notify(NotificationTarget target, OperationResult<?> result) {
        String key;
        String reason = "";
        if (result instanceof OperationResult.Success<?>) {
            key = "success";
        } else if (result instanceof OperationResult.Failure<?> failure) {
            key = "failure";
            reason = Messages.text("reasons." + failure.reason());
        } else if (result instanceof OperationResult.Partial<?> partial) {
            key = "partial";
            reason = Messages.text("reasons." + partial.reason());
        } else return;
        if (result.detail() != null && !result.detail().isBlank()) reason = result.detail();
        String operation = Messages.text("operations." + result.operation());
        Component consoleMessage = Messages.component("result." + key,
                "operation", operation, "reason", reason, "operation_id", result.operationId());
        if (target.kind() == NotificationTarget.Kind.PLAYER) {
            notify(NotificationTarget.console(), consoleMessage);
            notify(target, Messages.component("result.player_" + key, "operation", operation, "reason", reason));
        } else notify(target, consoleMessage);
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
