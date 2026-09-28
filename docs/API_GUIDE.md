# MapManager v4 外部插件 API 调用指南

本文说明其他 Paper 插件如何依赖并获取 MapManager 服务，以及各服务的当前调用范围。公开契约在 `mapmanager-api` 模块；不要依赖 `plugin` 模块的实现类、repository、LuckPerms 或 Multiverse 对象。

## 添加编译依赖

外部插件使用与服务端 MapManager 相同版本的 API。当前源码版本为 `4.0.0-SNAPSHOT`；本地从源码构建时，在 MapManager-v4 仓库根目录运行：

```shell
mvn -pl api -am install
```

在外部插件的 `pom.xml` 中添加 provided 依赖：

```xml
<dependency>
    <groupId>studio.lorian.mapmanager</groupId>
    <artifactId>mapmanager-api</artifactId>
    <version>4.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

`provided` 表示 API 类型由服务端的 MapManager 提供。不要把 `mapmanager-api` 再打包进扩展插件，否则 Paper 隔离的类加载器可能产生不同的 `MapManager` 类型，导致服务查询失败。

## 声明 Paper 插件依赖

扩展插件的 `paper-plugin.yml` 声明 MapManager 在前加载，并允许扩展插件访问它的 API 类：

```yaml
name: ExampleAddon
version: '1.0.0'
main: example.addon.ExampleAddon
api-version: '1.20'
dependencies:
  server:
    MapManager-Core:
      load: BEFORE
      required: true
      join-classpath: true
```

Paper 插件依赖和 `join-classpath` 的配置见 [Paper 插件文档](https://docs.papermc.io/paper/dev/getting-started/paper-plugins/)。

## 获取并跟踪服务

MapManager 在存储初始化、v3 数据迁移和 LuckPerms `worldbase` 初始化都成功后才注册服务。迁移是异步的，所以扩展插件的 `onEnable` 可能先于 API 注册执行。启动时先查询一次，再监听服务注册/撤销事件：

```java
package example.addon;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.MapManager;

public final class ExampleAddon extends JavaPlugin implements Listener {
    private MapManager mapManager;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        refreshMapManager();
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == MapManager.class) refreshMapManager();
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == MapManager.class) refreshMapManager();
    }

    private void refreshMapManager() {
        mapManager = Bukkit.getServicesManager().load(MapManager.class);
        if (mapManager == null) getLogger().info("MapManager API is not ready");
    }
}
```

`ServicesManager.load` 返回当前最高优先级服务；服务尚未注册时返回 `null`。MapManager 使用 `ServicePriority.Normal`。服务事件与查询接口见 [ServicesManager](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/plugin/ServicesManager.html)、[ServiceRegisterEvent](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/event/server/ServiceRegisterEvent.html) 和 [ServiceUnregisterEvent](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/event/server/ServiceUnregisterEvent.html)。插件禁用后 MapManager 会撤销自己的服务注册；扩展插件应在撤销事件后重新查询服务，不能长期缓存已撤销的 provider。

## 查询地图并调用服务

`MapManager` 是服务入口，调用方按需取得地图、角色、准入、设置、别名、旅行、生命周期和版本服务：

```java
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.MapManager;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.AccessDecision;

public final class MapTeleportExample extends JavaPlugin {
    private void teleportIfAllowed(Player player, MapManager mapManager) {
        MapInstance map = mapManager.maps().findByWorldName("arena");
        if (map == null || map.status() != MapStatus.ACTIVE) return;

        AccessDecision access = mapManager.access().decide(player.getUniqueId(), map.id());
        if (!access.allowed()) return;

        OperationResult<Void> result = mapManager.travel().teleport(player.getUniqueId(), map.id());
        if (result instanceof OperationResult.Success<?>) {
            getLogger().info("Teleport completed");
        } else if (result instanceof OperationResult.Partial<?> partial) {
            getLogger().warning("Teleport partially completed: " + partial.reason());
        } else if (result instanceof OperationResult.Failure<?> failure) {
            getLogger().warning("Teleport failed: " + failure.reason());
        }
    }
}
```

地图和地图组查询：

| 调用                             | 行为                                       |
|----------------------------------|--------------------------------------------|
| `maps().findById(UUID)`          | 按稳定 ID 查询；不存在返回 `null`          |
| `maps().findByWorldName(String)` | 按服务器世界名查询；非 MMC 地图返回 `null` |
| `maps().listMaps()`              | 返回不可变地图快照列表                     |
| `maps().findGroup(String)`       | 查询地图组；不存在返回 `null`              |
| `maps().listGroupMaps(String)`   | 返回指定组的地图列表                       |

`MapInstance`、`MapGroup`、`MapSettings`、`SpawnPoint` 和请求 record 是不可变快照。更新设置或成员时调用相应服务，再重新查询地图获取新快照。

## 服务目录

### 地图角色与准入

- `roles().assignedMembers(mapId, role)` 返回直接授予成员；管理员/建筑者按组查询，访客按单图查询。
- `roles().members(mapId, role)` 和 `roles().hasRole(mapId, playerId, role)` 返回有效角色。有效等级为 ADMIN > BUILDER > VISITOR；管理员具有建筑者和访客能力，建筑者具有访客能力。
- `roles().addMember(mapId, playerId, role)` / `removeMember(mapId, playerId, role)` 修改 MMC 授权；管理员、建筑者的变更同时投影到 LuckPerms 建造组，访客不投影。
- `access().decide(playerId, mapId)` 返回是否允许进入、有效角色或拒绝原因。只有 ACTIVE 地图能通过；公开地图允许无角色玩家进入，此时 `effectiveRole` 为 `null`，不会写入 VISITOR 授权。
- `access().accessibleMaps(playerId)` 包含该玩家能进入的 ACTIVE 地图（含公开地图）；`access().administratedMaps(playerId)` 只包含其为 ADMIN 的地图。

角色修改 API 没有操作者参数，也不会替扩展插件判断谁发起了修改。扩展插件必须在调用 `addMember/removeMember` 前自行完成管理员授权；同样，访问决策服务只回答某玩家能否进入地图，不会自动保护扩展插件自己的功能或命令。MMC 的严格准入监听负责服务器内的实际地图进入，不替代扩展插件自己的操作授权。

### 地图设置和别名

- `settings().setPublic(mapId, enabled)` 更新公开状态并刷新 Multiverse 别名颜色。
- `settings().setDisplayName(mapId, displayName)` 更新展示名并同步 Multiverse 别名。
- `settings().setPhysical(mapId, enabled)` / `settings().setExplosion(mapId, enabled)` 修改该地图的方块物理/爆炸设置并立即保存。
- `settings().getSpawn(mapId)` / `settings().setSpawn(mapId, spawnPoint)` 查询或更新出生点；设置出生点会写 MMC，并同步 Bukkit 和 Multiverse。
- `aliases().setAlias(mapId)` 将别名重置为世界名；`setAlias(mapId, alias)` 保存纯文本展示名，并按公开状态给 MV 别名加颜色。

别名、公开状态和出生点会调用 Bukkit/Multiverse，必须在服务器主线程调用。物理/爆炸只修改存储设置。API 不接受操作者，因此设置服务也不会自动执行地图管理员校验。

### 传送和踢出

- `travel().teleport(playerId, mapId)` 检查 MMC 准入后传送到地图出生点；出生点未设置时使用世界默认出生点。
- `travel().kickFromMap(operatorId, playerId)` 要求操作者是当前地图管理员、两名玩家都在线且位于同一地图；目标传到 Multiverse 默认世界出生点。
- 这两项操作必须在主线程调用。目标世界必须已加载；当前 API 不会按需加载地图。

### 世界生命周期

- `worldLifeCycle().create(CreateMapRequest)` 创建世界并注册地图。
- `worldLifeCycle().importWorld(ImportMapRequest)` 导入服务端根目录的世界；需要有效 `level.dat` 和不高于服务器的世界数据版本。
- `worldLifeCycle().initialize(worldName)` 重放已加载 MMC 地图的 Multiverse 属性和 gamerule。
- `worldLifeCycle().delete(worldName)` 删除地图及世界；受 `deletion.allowed-world-name-regex` 限制，并拒绝默认世界、首个加载世界、含在线玩家的世界或目录不符合预期的地图。

生命周期操作必须在主线程调用。创建请求中的 `ownerId` 可为 null；若为空，地图不会自动获得首位管理员。创建/导入的初始设置为物理开启、爆炸关闭、私有。`CreateMapRequest` 和 `ImportMapRequest` 的便利构造器默认 `WorldType.FLAT`；需要其他类型时显式传入 `WorldType`。按需加载、操作崩溃恢复和自动补偿目前不属于已实现 API。

### 世界版本

`worldVersions().serverDataVersion()` 返回当前服务器 dataVersion；`mapDataVersion(Path)` 读取指定目录 `level.dat` 的版本，无法读取时返回空 `OptionalInt`；`isCompatible(Path/String)` 判断地图版本是否不高于服务器版本。该服务不创建、导入或修改地图。

`PlayerGateway` 和 `WorldGateway` 虽然属于 `api` 模块，但当前没有作为 `MapManager` getter 暴露；外部插件应使用 `MapManager` 的业务服务，不应自行获取 plugin 内部适配器。

## 返回值、线程和权限

修改类方法返回 `OperationResult<T>`：

- `Success<T>`：操作完成；读取 `value()`（`Void` 操作的值为 null）。
- `Failure<T>`：操作未完成；读取稳定的 `reason()` 失败代码。
- `Partial<T>`：MMC 数据可能已写入，但 Multiverse 或 LuckPerms 等外部同步未全部完成；应向调用者报告部分成功并记录原因。

每种结果都包含 `operation()` 和 `operationId()`，可用于日志与故障排查。不要只判断“不是 Failure”就当作完整成功；显式处理 `Partial`。

```java
if (result instanceof OperationResult.Success<?> success) {
    getLogger().info("Completed " + success.operation() + " " + success.operationId());
} else if (result instanceof OperationResult.Partial<?> partial) {
    getLogger().warning("Partially completed " + partial.operation() + ": " + partial.reason());
} else if (result instanceof OperationResult.Failure<?> failure) {
    getLogger().warning("Failed " + failure.operation() + ": " + failure.reason());
}
```

服务方法是同步调用，没有统一的异步结果契约。生命周期、传送、踢出、别名、公开状态和出生点操作要求主线程；从异步任务调用时，应先调度回主线程。组角色变更会等待 LuckPerms 投影完成，可能阻塞调用线程。所有角色或设置写入都由调用方负责校验操作者权限。
