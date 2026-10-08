package studio.lorian.mapmanager.api.service;

import java.nio.file.Path;

/** 地图存储目录查询接口。世界创建和导入由 {@link WorldLifeCycleService} 管理。 */
public interface WorldGateway {
    /**
     * 根据主地图下的 {@code dimensions/minecraft} 目录是否存在，定位地图的父目录。
     * 无法从 {@code server.properties} 读取 {@code level-name} 时使用服务器第一个世界的名称。
     *
     * @return 地图父目录的绝对路径
     */
    Path worldParentDirectory();

    /**
     * 返回指定地图的存储目录，不创建目录。通过 {@link studio.lorian.mapmanager.api.MapManager#worldDirectories()}
     * 获取的查询入口优先返回已加载世界实际存档路径；未加载世界按父目录规则定位。
     *
     * @param worldName 安全的单个世界目录名
     * @return 地图目录的绝对路径
     */
    Path worldDirectory(String worldName);
}
