package studio.lorian.mapmanager.api.result;

import java.util.UUID;

/**
 * 公开 API 操作的执行结果。
 *
 * <p>每种结果都包含操作名称和唯一操作 ID。通知器按语言文件展示操作名和失败原因；原因代码本身保持稳定，供 API 调用方使用。
 *
 * @param <T> 操作成功或部分成功时返回的数据类型
 */
public sealed interface OperationResult<T>
        permits OperationResult.Success, OperationResult.Failure, OperationResult.Partial {

    /**
     * 返回此结果对应的操作名称。
     *
     * @return 操作名称
     */
    OperationName operation();

    /**
     * 返回日志和通知中用于关联此操作的标识。
     *
     * @return 唯一操作 ID
     */
    UUID operationId();

    /** 操作完全成功。
     *
     * @param <T> 结果数据类型
     * @param operation 此结果对应的操作
     * @param operationId 唯一操作标识
     * @param value 操作结果；当操作类型为 {@link Void} 时可以为 {@code null}
     */
    record Success<T>(OperationName operation, UUID operationId, T value) implements OperationResult<T> {
    }

    /** 操作失败，且没有成功结果数据。
     *
     * @param <T> 预期的结果数据类型
     * @param operation 此结果对应的操作
     * @param operationId 唯一操作标识
     * @param reason 稳定的失败原因代码
     */
    record Failure<T>(OperationName operation, UUID operationId,
                      FailureReason reason) implements OperationResult<T> {
    }

    /** MMC 数据已成功处理，但外部副作用尚未完成。
     *
     * <p>例如 MMC 已保存玩家角色，但仍需重试同步 LuckPerms 权限投影。
     *
     * @param <T> 结果数据类型
     * @param operation 此结果对应的操作
     * @param operationId 唯一操作标识
     * @param value 已完成部分产生的结果
     * @param reason 剩余工作未完成的原因代码
     */
    record Partial<T>(OperationName operation, UUID operationId, T value,
                      FailureReason reason) implements OperationResult<T> {
    }
}
