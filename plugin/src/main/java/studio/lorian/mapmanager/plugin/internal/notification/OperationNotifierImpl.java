package studio.lorian.mapmanager.plugin.internal.notification;

import studio.lorian.mapmanager.api.result.OperationResult;

/** 操作通知端口的占位实现。 */
public final class OperationNotifierImpl implements OperationNotifier {
    @Override
    public void notify(NotificationTarget target, OperationResult<?> result) {
        // TODO: 统一发送成功、失败和部分完成通知。
    }
}
