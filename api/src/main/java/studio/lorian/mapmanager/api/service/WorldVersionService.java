package studio.lorian.mapmanager.api.service;

import java.nio.file.Path;
import java.util.OptionalInt;

/** 查询地图 level.dat 数据版本并判断其能否由当前服务器导入。 */
public interface WorldVersionService {
    /**
     * 返回当前服务器的数据版本。
     *
     * @return Bukkit 当前服务器的 dataVersion
     */
    int serverDataVersion();

    /**
     * 读取地图目录中 level.dat 保存的数据版本。
     *
     * @param worldFolder 地图目录
     * @return 读取成功时返回数据版本；文件或版本字段无效时为空
     */
    OptionalInt mapDataVersion(Path worldFolder);

    /**
     * 判断地图目录是否包含有效且不高于服务器的数据版本。
     *
     * @param worldFolder 地图目录
     * @return 版本可读取且不高于服务器版本时为 true
     */
    boolean isCompatible(Path worldFolder);

    /**
     * 按服务器世界目录中的名称判断地图版本是否兼容。
     *
     * @param worldName 地图世界名称
     * @return 版本可读取且不高于服务器版本时为 true
     */
    boolean isCompatible(String worldName);
}
