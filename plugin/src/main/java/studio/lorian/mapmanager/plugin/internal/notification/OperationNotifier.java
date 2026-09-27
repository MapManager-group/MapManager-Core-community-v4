package studio.lorian.mapmanager.plugin.internal.notification;

import studio.lorian.mapmanager.api.result.OperationResult;
import net.kyori.adventure.text.Component;

/** 命令结果与操作提示的统一通知入口。 */
public interface OperationNotifier {
    void notify(NotificationTarget target, OperationResult<?> result);

    /** 发送无需操作结果包装的提示文本。 */
    void notify(NotificationTarget target, String message);

    void notify(NotificationTarget target, Component message);

    /** 按语言键发送提示，参数按名称和值成对传入。 */
    default void notifyKey(NotificationTarget target, String key, Object... arguments) {
        notify(target, Messages.component(key, arguments));
    }
}
