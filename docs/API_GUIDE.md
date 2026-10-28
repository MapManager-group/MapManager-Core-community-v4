# MapManager v4 外部插件 API 调用指南

默认世界以启动时 Multiverse-Core 的默认世界为准（Multiverse 启用前暂用服务器首个已加载世界）。它始终允许玩家进入，即使存在旧 MMC 私有地图或非 ACTIVE 记录；MMC 不赋予该世界有效角色、不应用单地图方块物理/爆炸设置（坐垫、画、展示框的实体支撑保护为全局规则，默认世界也生效），也不允许修改其别名、出生点、设置或执行生命周期管理。已有地图/角色记录保留用于追溯，但在默认世界上不生效。默认世界变更后需重启 MMC 以刷新识别。 此前已写入 Multiverse 配置或 gamerule 的值不会猜测恢复，需按服务端配置调整。

默认世界不投影 `worldbase` 建造权限：启动后的地图组同步会异步移除已有的默认世界上下文继承，后续同步也不会重建；同组其他地图仍正常投影。实际放置/破坏限制由现有保护插件和 LuckPerms 配置执行；MMC 不新增方块破坏/放置拦截，也不撤销管理员另行配置的全局或其他插件权限。

本文说明其他 Paper 插件如何依赖并获取 MapManager 服务，以及各服务的当前调用范围。公开契约在 `mapmanager-api` 模块；不要依赖 `plugin` 模块的实现类、repository、LuckPerms 或 Multiverse 对象。

需要从 v3 插件迁移时，先看 [v3→v4 插件 API 迁移指南](V3_TO_V4_API_MIGRATION.md)；完整功能差异见 [v3/v4 对照表](API_INVENTORY.md)。历史 `4.0.0-SNAPSHOT` API 的 HTML Javadoc 导出包（本次未重新导出）：[`mapmanager-api-4.0.0-SNAPSHOT-javadoc.zip`](exports/mapmanager-api-4.0.0-SNAPSHOT-javadoc.zip)。

## 添加编译依赖

外部插件使用与服务端 MapManager 相同版本的 API。当前源码版本为 `4.0.2-SNAPSHOT`；本地从源码构建时，在 MapManager-v4 仓库根目录运行：

```shell
mvn -pl api -am install
```

在外部插件的 `pom.xml` 中添加 provided 依赖：

```xml
<dependency>
    <groupId>studio.lorian.mapmanager</groupId>
    <artifactId>mapmanager-api</artifactId>
    <version>4.0.2-SNAPSHOT</version>
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

别名、公开状态和出生点会调用 Bukkit/Multiverse，必须在服务器主线程调用。物理/爆炸只修改存储设置；`setPhysical` 仅影响普通方块和落沙，画/展示框及 26.3 坐垫的支撑丢失/挤占破坏固定全局禁止，不由该 API 控制。API 不接受操作者，因此设置服务也不会自动执行地图管理员校验。

### 传送和踢出

- `travel().teleport(playerId, mapId)` 检查 MMC 准入后传送到地图出生点；出生点未设置时使用世界默认出生点。
- `travel().kickFromMap(operatorId, playerId)` 要求操作者是当前地图管理员、两名玩家都在线且位于同一地图；目标传到 Multiverse 默认世界出生点。
- 这两项操作必须在主线程调用。入图传送在准入通过后，通过生命周期服务按需加载已登记的 ACTIVE 世界；加载会检查目录与版本。

### 世界生命周期

- `worldLifeCycle().create(CreateMapRequest)` 创建世界并注册地图。
- `worldLifeCycle().importWorld(ImportMapRequest)` 导入服务端根目录的世界；需要有效 `level.dat` 和不高于服务器的世界数据版本。导入请求不包含生成参数；地图按存档中的区块数据导入，所选生成器用于后续新区块。
- `worldLifeCycle().load(worldName)` 加载已登记 ACTIVE 地图；目标目录与版本须有效，Multiverse 中须有世界登记。
- `worldLifeCycle().recover(worldName)` 根据持久请求继续未完成创建、导入或已经确认的删除；缺少请求或地图/目录身份冲突时停止。旧版本没有完整请求的 PENDING 记录不能自动猜测恢复。
- `worldLifeCycle().initialize(worldName)` 先持久化关闭已加载 MMC 地图的物理和爆炸，再重放 Multiverse 属性和 gamerule；保留公开状态、角色、出生点和地图状态，返回更新后的地图快照。后续世界初始化失败时，两项仍保持关闭。
- `worldLifeCycle().delete(worldName)` 删除地图及世界；不受命令专用的 `deletion.allowed-world-name-regex` 限制，仍校验名称格式，并拒绝默认世界、首个加载世界、含在线玩家的世界或目录不符合预期的地图。删除拒绝原因分别为 `INVALID_MAP_STATE`（非 ACTIVE/DELETING）、`WORLD_HAS_PLAYERS`（图内有在线玩家）和 `RECOVERY_CONFLICT`（恢复请求不是 DELETE 或地图 UUID 不匹配）；调用方应根据 `Failure.reason()` 分支处理，不能将其统一视为操作忙。六位、10 秒确认码只适用于 `/mapadmin delete` 命令；直接调用此 API 不经过命令确认，外部插件仍须自行授权调用者。

生命周期操作必须在主线程调用。创建请求中的 `ownerId` 可为 null；若为空，地图不会自动获得首位管理员。创建/导入的初始设置为物理开启、爆炸关闭、私有。两个请求都通过 `WorldType` 和 `Environment` 独立指定生成器及维度，例如：

```java
new CreateMapRequest("arena", "Arena", "team", ownerId,
        WorldType.FLAT, Environment.NETHER, Map.of());
new ImportMapRequest("arena-copy", "Arena Copy", "team", ownerId,
        WorldType.NORMAL, Environment.THE_END);
```

生成器可选 `FLAT`、`NORMAL`（原版标准地形）和 `VOID`（需要 VoidGen）；环境可选 `NORMAL`、`NETHER`、`THE_END`。`Environment.SIFT` 是预留值，当前返回 `FailureReason.UNSUPPORTED_ENVIRONMENT`。便捷构造器默认 `WorldType.FLAT` 和 `Environment.NORMAL`。`flat.height` 仅用于创建 FLAT，表示绝对表层 Y（如 0、-60）而非总层数；内置生成器在准备生物群系提供器时按目标 WorldInfo 的 minHeight ≤ Y < maxHeight 校验，创建成功后及恢复激活前再次复核实际世界高度，支持生效的数据包扩展；导入不接受生成参数，但会按请求设置环境和后续新区块生成器。按需加载与操作恢复通过 `load` 和 `recover` 调用；恢复继续原步骤，不自动删除创建/导入留下的世界。创建失败且确认无 Bukkit/Multiverse 登记、无实际世界目录及根目录暂存路径时，删除身份匹配且无出生点的 PENDING 占位及恢复请求，返回 Failure，允许同名重试；FAILED 审计及组角色保留。启动同样清理符合条件的旧失败创建；已有世界/目录或无法确认时保留 Partial/PENDING 供恢复。

### 世界版本

`worldVersions().serverDataVersion()` 返回当前服务器 dataVersion；`mapDataVersion(Path)` 读取指定目录 `level.dat` 的版本，无法读取时返回空 `OptionalInt`；Paper 26 已存在的维度目录缺少独立 level.dat 时读取根世界共用文件；`isCompatible(Path/String)` 判断地图版本是否不高于服务器版本。该服务不创建、导入或修改地图。

### 地图实际目录

`worldDirectories().worldDirectory(worldName)` 返回地图实际目录：已加载世界使用服务器实际存档目录，未加载世界使用 MMC 当前版本的目录规则；`worldParentDirectory()` 返回 MMC 地图父目录。只读查询，不创建或加载世界。在主线程调用并缓存结果；不要在扩展插件中再次拼接服务器目录或复制 Minecraft 版本判断。需要包含未加载地图时先用 `maps().listMaps()` 枚举登记地图。

`PlayerGateway` 尚未通过 `MapManager` getter 暴露。目录入口在本次 `4.0.0-SNAPSHOT` 源码中新增，扩展插件须使用同步更新的 MMC 插件/API。

## 返回值、线程和权限

修改类方法返回 `OperationResult<T>`：

- `Success<T>`：操作完成；读取 `value()`（`Void` 操作的值为 null）。
- `Failure<T>`：操作未完成；读取稳定的 `reason()` 失败代码。
- `Partial<T>`：MMC 数据已处理，但 Multiverse 或 LuckPerms 等外部同步未全部完成；应报告原因。`PERMISSION_SYNC_PENDING` 表示 LP 已入持久队列，地图/角色可按 MMC 状态立即生效，调用方无需重复提交；自动重试没有固定完成期限。

每种结果都包含 `operation()` 和 `operationId()`，可用于日志与故障排查。`detail()` 为可空的具体失败说明；Failure/Partial 可携带实际表层 Y 和目标世界上下界，调用方应保留并展示此文本，同时保留稳定 reason。旧 Failure/Partial 构造器继续可用，未指定详情时返回 null；使用 detail 的调用方需要同步更新 Core 插件与 API。不要只判断“不是 Failure”就当作完整成功；显式处理 `Partial`。

```java
if (result instanceof OperationResult.Success<?> success) {
    getLogger().info("Completed " + success.operation() + " " + success.operationId());
} else if (result instanceof OperationResult.Partial<?> partial) {
    getLogger().warning("Partially completed " + partial.operation() + ": " + partial.reason());
} else if (result instanceof OperationResult.Failure<?> failure) {
    getLogger().warning("Failed " + failure.operation() + ": " + failure.reason());
}
```

服务方法是同步调用，没有统一的异步结果契约。生命周期、传送、踢出、别名、公开状态和出生点操作要求主线程；从异步任务调用时，应先调度回主线程。组角色变更同步写 MMC，但不等待 LuckPerms 网络操作：投影持久化后返回 `Partial(PERMISSION_SYNC_PENDING)`，失败后台自动重试，重启继续。入队失败返回 `Partial(PERMISSION_SYNC_FAILED)` 或存储原因。角色撤销立即改变 MMC 准入结果。地图/角色查询读取不可变内存索引；存储写入仍同步。所有角色或设置写入都由调用方负责校验操作者权限。

### 加载与恢复示例

```java
MapManager mmc = Bukkit.getServicesManager().load(MapManager.class);
if (mmc == null) return; // 尚未就绪或插件已禁用
OperationResult<MapInstance> loaded = mmc.worldLifeCycle().load("arena");
OperationResult<MapInstance> recovered = mmc.worldLifeCycle().recover("arena");
```

两项操作都在主线程调用；`recover` 仅用于已有恢复请求，不替代新的创建/导入参数校验或删除授权。`PlayerGateway.disconnect` 也要求主线程，原因按纯文本处理；该适配器仍没有 `MapManager` getter。

4.0.2 保持公开接口和模型签名不变。`worldVersions().isCompatible(String)` 仅接受单个合法世界名，不接收绝对路径或目录片段；显式目录查询仍使用 Path 重载。`setSpawn` 接受同名世界的大小写变体并按地图记录的世界名保存。授权与在线会话边界见 [审查报告](CODE_REVIEW_2026-10-28.md)。
