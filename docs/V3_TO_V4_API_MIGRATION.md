# v3 外部插件迁移到 v4 API

本文面向目前编译依赖 MapManager v3 的 Paper 插件开发者。v4 API 使用新的 Java 包、模型和服务，不兼容 v3 的 Kotlin API；迁移时需要替换依赖、服务发现和业务调用。

完整功能差异见 [v3/v4 功能对照表](API_INVENTORY.md)，v4 服务的详细契约见 [API 调用指南](API_GUIDE.md)，公开类型索引见 [Javadoc 导出包](exports/mapmanager-api-4.0.0-SNAPSHOT-javadoc.zip)。

## 1. 替换编译依赖

v3 扩展插件通常依赖 `work.alsace.mapmanager:MapManager-Core`；v4 将公开 API 拆成独立 Maven 模块：

```xml
<dependency>
    <groupId>studio.lorian.mapmanager</groupId>
    <artifactId>mapmanager-api</artifactId>
    <version>4.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

从当前 v4 源码安装 API 到本地 Maven 仓库：

```shell
mvn -pl api -am install
```

Gradle Kotlin DSL：

```kotlin
dependencies {
    compileOnly("studio.lorian.mapmanager:mapmanager-api:4.0.0-SNAPSHOT")
}
```

使用与服务器上 MapManager 插件完全相同的 API 版本。API 应保持 `provided` / `compileOnly`；不要把 API 再 shade 或打包进扩展插件。v4 插件会提供对应 API 类，重复打包可能导致类加载器中的 `MapManager` 类型不一致，进而查不到服务。

## 2. 声明 Paper 插件依赖

v3 的 `plugin.yml` `depend` 配置不能替代 v4 的 Paper 插件类路径声明。调用 v4 API 的扩展插件应使用 `paper-plugin.yml`：

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

`join-classpath: true` 让扩展插件访问 MapManager 提供的 API 类型。保留 `plugin.yml` 且未加入 MapManager 的类路径时，Java 编译通过也不代表运行时能加载 `studio.lorian.mapmanager.api` 类型。

## 3. 获取 v4 服务

v3 示例常从插件实例或实现类取得 `MapManager`。v4 通过 Bukkit `ServicesManager` 注册 `studio.lorian.mapmanager.api.MapManager`。存储迁移及启动初始化完成前服务可能尚未注册；监听注册和撤销事件，并在撤销时清空缓存：

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

取得服务后使用 `maps()`、`roles()`、`access()`、`settings()`、`aliases()`、`travel()`、`worldLifeCycle()` 和 `worldVersions()` 获取分类服务。不要强转到 `MapManagerPlugin` 或 plugin 内部实现类。

## 4. 常见 v3 调用到 v4 的替换

| v3 调用/对象 | v4 替换 | 迁移注意事项 |
|---|---|---|
| `MapManager.getInstance()` / 插件对象强转 | `Bukkit.getServicesManager().load(MapManager.class)` | 结果可能为 `null`；服务可能在异步迁移完成后才注册。监听服务注册和撤销事件。 |
| `getMapAgent().containsWorld(name)` | `maps().findByWorldName(name) != null` | 新 API 返回不可变 `MapInstance` 快照。 |
| `getMapAgent().getWorlds()` | `maps().listMaps()` | 从每个地图快照读取 `worldName()`；返回内容只包含 MMC 托管地图。 |
| `getWorldGroupName(name)` / `getWorldListByGroup(group)` | `findByWorldName(name).groupId()` / `maps().listGroupMaps(group)` | 先处理地图不存在的情况。 |
| `getMapAgent().addPlayer/removePlayer(name, MapGroup, playerName)` | `roles().addMember/removeMember(mapId, playerId, MapRole)` | 将地图名解析为 `mapId`，将玩家名解析为 UUID；结果改为 `OperationResult<Void>`。ADMIN/BUILDER 按组生效，VISITOR 按单图生效。 |
| `getAdminSet/getBuilderSet/getVisitorSet`、`getPlayers` | `roles().assignedMembers(mapId, role)` 或 `roles().members(mapId, role)` | 返回 UUID 集合；`assignedMembers` 只列直接授予成员，`members` 包含有效继承角色。 |
| `isAdmin(playerName, world)` | `roles().hasRole(mapId, playerId, MapRole.ADMIN)` | v4 使用 MMC UUID 角色数据判断，不以 LP 查询作为地图准入来源。 |
| `publicizeWorld/privatizeWorld/isPublic` | `settings().setPublic(mapId, enabled)`；查询 `MapInstance.settings().isPublic()` | 修改返回 `OperationResult`。扩展插件需要自行授权操作用户。 |
| `setWorldAlias/getWorldAlias` | `aliases().setAlias(mapId, alias)`；查询 `MapInstance.displayName()` | 展示名以纯文本保存，MV 别名颜色随公开状态处理。 |
| `setPhysical/isPhysical`、`setExploded/isExploded` | `settings().setPhysical/setExplosion(mapId, enabled)`；查询地图快照中的 `settings()` | v4 只有地图级设置，没有 v3 的全局物理/爆炸覆盖。 |
| `getSpawnLocation/setSpawn` | `settings().getSpawn/setSpawn(mapId, SpawnPoint)` | 写出生点会同步 MMC、Bukkit 和 Multiverse。 |
| `getOwnerWorlds/getAccessWorlds` | `access().administratedMaps(playerId)` / `access().accessibleMaps(playerId)` | 返回地图快照；准入取决于 MMC 角色、地图公开状态和 ACTIVE 状态。 |
| `hasPermission(user, node)` | `access().decide(playerId, mapId)` | 查询地图准入决策；LP `multiverse.access.*` 不再决定 v4 地图访问权。 |
| `createWorld/newWorld` | `worldLifeCycle().create(new CreateMapRequest(..., WorldType, Environment, parameters))` | 生成器和环境独立指定；所有者 ID 使用 UUID，`ownerId == null` 时不会自动分配首位管理员。 |
| `importWorld` | `worldLifeCycle().importWorld(new ImportMapRequest(..., WorldType, Environment))` | 只从服务端根目录导入，要求有效 `level.dat` 且版本兼容；不含生成参数，保留已有区块并为后续生成配置生成器和环境。 |
| `initWorld` | `worldLifeCycle().initialize(worldName)` | 需要已加载且已登记的地图。 |
| `deleteWorld/removeWorld` | `worldLifeCycle().delete(worldName)` | 受配置中的删除名称正则、在线玩家、默认世界及目录安全检查限制。 |
| `getVersionCheck()` | `worldVersions()` | 读取地图 dataVersion 并与服务器版本比较。 |
| `getDynamicWorld().loadWorld/unloadWorldLater` | 暂无等价 API | v4 当前不提供按需加载和延迟卸载。目标世界须已加载才能通过 `travel().teleport` 传送。 |
| `getLuckPerms()` / `syncWithLuckPerms()` | 暂无对外替代 | LuckPerms 由 v4 plugin 内部用于投影 `worldbase` 建造权限；不要直接用 LP 同步 MMC 角色或判断进图权限。 |
| `getMainYaml()`、`save()`、`reload()` | 暂无对外替代 | 扩展插件配置由扩展插件自己管理；MMC 服务修改会自动持久化。 |
| `getNodeMap/getGroupMap` | `maps().listMaps()`、`maps().findGroup/listGroupMaps()` | v4 不暴露可变 repository/map；调用方改为读取快照并调用服务写入。 |
| `getPlayer/getUniqueID/isPlayerRegister` | 使用扩展插件自己的 Bukkit 玩家/UUID/首次加入管理 | `PlayerGateway` 虽在 API 模块声明，但当前没有通过 `MapManager` getter 暴露；v4 没有独立的历史注册状态查询。 |
| `getWorldParentFolder/getWorldFolder` | 无 `MapManager` 对外 getter | 世界目录适配器不对外提供；优先使用 Bukkit 对已加载世界的 API，不要依赖 plugin 内部目录实现。 |
| `getMVWorld/getMVWorldManager/getLoadedWorld` | 使用 Bukkit API 处理 Bukkit 世界对象 | v4 API 不暴露 Multiverse 对象，避免调用方依赖后端实现。 |

完整的 v3 功能保留、行为变化和缺失项见 [API_INVENTORY.md](API_INVENTORY.md)。

## 5. Java 调用示例

以下示例按世界名查询地图、检查 UUID 玩家准入，再请求传送：

```java
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.MapManager;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.model.MapStatus;
import studio.lorian.mapmanager.api.result.OperationResult;
import studio.lorian.mapmanager.api.service.AccessDecision;

public final class ExampleAddon extends JavaPlugin {
    public void teleport(Player player, MapManager manager) {
        MapInstance map = manager.maps().findByWorldName("arena");
        if (map == null || map.status() != MapStatus.ACTIVE) return;

        UUID playerId = player.getUniqueId();
        AccessDecision decision = manager.access().decide(playerId, map.id());
        if (!decision.allowed()) return;

        OperationResult<Void> result = manager.travel().teleport(playerId, map.id());
        if (result instanceof OperationResult.Success<?>) {
            player.sendMessage("已传送到 " + map.displayName());
        } else if (result instanceof OperationResult.Partial<?> partial) {
            getLogger().warning("传送部分完成：" + partial.reason());
        } else if (result instanceof OperationResult.Failure<?> failure) {
            getLogger().warning("传送失败：" + failure.reason());
        }
    }
}
```

## 6. 更新调用语义时的检查清单

- 将 v3 字符串玩家名转换为 UUID；不要把用户名继续作为权限主键。
- 对旧地图名先查询 `MapInstance`，再保存或传递稳定的 `mapId`；重命名/别名不是地图 ID。
- 对每个修改结果处理 `Success`、`Failure` 和 `Partial`。`Partial` 表示 MMC 数据已经处理，但 LuckPerms 等投影尚未全部完成。
- `MapRoleService` 和多数设置方法不接收操作者参数，不会替扩展插件做管理员授权；在插件自己的命令/API 层验证操作者。
- 生命周期、传送、踢出、别名、公开状态和出生点相关调用在服务器主线程执行。不要从异步回调直接操作 Bukkit/Multiverse。
- 不再假设可通过 API 直接修改 LP、MV 实例、全局 YAML、JSON 文件或 repository。
- 按需加载、延迟卸载、全局物理/爆炸覆盖、手动 save/reload/sync 目前没有 v4 API 替代；如扩展插件依赖这些行为，需要调整功能设计。

## 7. 迁移后的验收

1. 将编译依赖替换为与服务器一致的 `mapmanager-api` 版本，并确认 API 未被打入扩展插件 JAR。
2. 确认 `paper-plugin.yml` 声明 `MapManager-Core` 依赖和 `join-classpath: true`。
3. 测试 MapManager 服务尚未注册、注册和撤销三个阶段；缓存服务引用需在撤销时清空。
4. 测试不存在地图、非 ACTIVE 地图、未授权玩家、公开地图及已加载/未加载目标世界。
5. 测试角色/设置 API 的操作者授权、`Partial` 结果处理和主线程调用。
