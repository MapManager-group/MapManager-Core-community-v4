package studio.lorian.mapmanager.plugin.internal.notification;

import studio.lorian.mapmanager.api.result.OperationResult;

/** 命令结果与操作提示的统一通知入口。 */
public interface OperationNotifier {
    void notify(NotificationTarget target, OperationResult<?> result);

    /** 发送无需操作结果包装的提示文本。 */
    void notify(NotificationTarget target, String message);
}
