package studio.lorian.mapmanager.api.service;

import studio.lorian.mapmanager.api.model.SpawnPoint;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 玩家身份查询和在线玩家操作接口。 */
public interface PlayerGateway {
    /**
     * 依次查询在线玩家和服务端缓存；未命中时依据有效 online-mode 查询 Mojang 或计算离线 UUID。
     * 可能发生网络请求，因此结果异步返回；回调可能运行在异步线程。
     * 无对应正版玩家或查询失败时结果为 {@code null}。
     *
     * @param playerName 玩家用户名
     * @return UUID 查询结果
     */
    CompletableFuture<UUID> resolveId(String playerName);

    /**
     * 查询指定玩家是否在线。
     *
     * @param playerId 玩家 UUID
     * @return 在线时为 {@code true}
     */
    boolean isOnline(UUID playerId);

    /**
     * 查询在线玩家当前所在世界。
     *
     * @param playerId 玩家 UUID
     * @return 世界名；玩家不在线时为 {@code null}
     */
    String currentWorld(UUID playerId);

    /**
     * 将在线玩家传送到指定位置。
     *
     * @param playerId 玩家 UUID
     * @param point 目标位置
     * @return 成功时为 {@code true}
     */
    boolean teleport(UUID playerId, SpawnPoint point);

    /**
     * 断开在线玩家的连接。
     *
     * @param playerId 玩家 UUID
     * @param reason 断开原因
     * @return 成功时为 {@code true}
     */
    boolean disconnect(UUID playerId, String reason);
}
