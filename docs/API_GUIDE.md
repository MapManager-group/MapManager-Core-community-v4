# 外部插件 API

公开契约在 [api 模块](../api/src/main/java/studio/lorian/mapmanager/api/)。只依赖模型、接口和结果类型，不调用 plugin 实现、仓储或 LP/MV 对象。v3 调用替换见 [API 迁移](V3_TO_V4_API_MIGRATION.md)。

## 依赖

在 MMC 仓库运行 `mvn -pl api -am install`，扩展插件使用与服务端一致的版本：

```xml
<dependency>
    <groupId>studio.lorian.mapmanager</groupId>
    <artifactId>mapmanager-api</artifactId>
    <version>4.0.2-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

API 由 MMC 提供，不要再次打包进扩展插件。扩展插件的 `paper-plugin.yml`：

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

## 服务就绪与调用示例

MMC 在存储、迁移和 `worldbase` 初始化完成后注册 `ServicePriority.Normal` 服务，可能晚于扩展插件的 `onEnable`。先查询，再监听注册和撤销；服务不可用时停用依赖它的入口。

```java
package example.addon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import studio.lorian.mapmanager.api.MapManager;
import studio.lorian.mapmanager.api.model.MapInstance;
import studio.lorian.mapmanager.api.result.OperationResult;

public final class ExampleAddon extends JavaPlugin implements Listener {
    private MapManager mmc;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        refreshService();
    }

    @EventHandler
    public void registered(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == MapManager.class) refreshService();
    }

    @EventHandler
    public void unregistered(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == MapManager.class) refreshService();
    }

    private void refreshService() {
        mmc = Bukkit.getServicesManager().load(MapManager.class);
    }

    public void teleport(Player player, String worldName) {
        if (mmc == null) {
            player.sendMessage("地图服务尚未就绪");
            return;
        }
        MapInstance map = mmc.maps().findByWorldName(worldName);
        if (map == null) {
            player.sendMessage("地图不存在");
            return;
        }
        OperationResult<Void> result = mmc.travel().teleport(player.getUniqueId(), map.id());
        if (result instanceof OperationResult.Failure<?> failure) {
            player.sendMessage(failure.detail() != null ? failure.detail() : failure.reason().name());
        } else if (result instanceof OperationResult.Partial<?> partial) {
            player.sendMessage(partial.detail() != null ? partial.detail() : partial.reason().name());
        }
    }
}
```

示例的传送方法在主线程调用；传送服务负责准入检查和按需加载，不需预先重复检查状态、权限或加载世界。扩展插件的可见提示应接入自己的语言资源。

## 服务目录

以下均通过 `MapManager` 获取；精确参数和返回类型见对应接口。

| 入口 / 接口 | 能力 |
|---|---|
| `maps()` / [MapService](../api/src/main/java/studio/lorian/mapmanager/api/service/MapService.java) | 按 ID/世界名查地图、列地图、查组和组内地图；单条不存在返回 null |
| `roles()` / [MapRoleService](../api/src/main/java/studio/lorian/mapmanager/api/service/MapRoleService.java) | 直接成员 `assignedMembers`、有效成员 `members`、`hasRole`、授予/撤销 |
| `access()` / [MapAccessService](../api/src/main/java/studio/lorian/mapmanager/api/service/MapAccessService.java) | `decide`、可进入地图、可管理地图 |
| `settings()` / [MapSettingsService](../api/src/main/java/studio/lorian/mapmanager/api/service/MapSettingsService.java) | 公开、展示名、物理、爆炸和出生点 |
| `aliases()` / [MapAliasService](../api/src/main/java/studio/lorian/mapmanager/api/service/MapAliasService.java) | 按公开状态同步 MV 别名；不传 alias 时使用世界名，传入时使用指定展示名 |
| `travel()` / [MapTravelService](../api/src/main/java/studio/lorian/mapmanager/api/service/MapTravelService.java) | `teleport`；`kickFromMap` 会检查操作者为同图管理员 |
| `worldLifeCycle()` / [WorldLifeCycleService](../api/src/main/java/studio/lorian/mapmanager/api/service/WorldLifeCycleService.java) | `create/importWorld/initialize/delete/load/recover` |
| `worldVersions()` / [WorldVersionService](../api/src/main/java/studio/lorian/mapmanager/api/service/WorldVersionService.java) | 服务器和地图 dataVersion、存档兼容性 |
| `worldDirectories()` / [WorldGateway](../api/src/main/java/studio/lorian/mapmanager/api/service/WorldGateway.java) | 只读父目录和实际世界目录；不执行世界操作 |

`MapInstance`、`MapGroup` 是不可变快照，写入后重新查询。`ADMIN`、`BUILDER` 按组，`VISITOR` 按图；有效角色继承顺序为 ADMIN → BUILDER → VISITOR。公开只允许进入，不授予角色。撤销名单应使用直接成员查询。

`access().decide` 对一般地图要求 ACTIVE，再检查角色或公开状态；默认世界始终允许。`accessibleMaps` 仅返回已登记且允许进入的记录，默认世界若没有记录也不会凭空出现；`administratedMaps` 仅返回 ACTIVE 的非默认地图。

## 生命周期与目录

- `CreateMapRequest` 可指定名称、展示名、组、首位管理员、类型、环境及生成参数；省略类型和环境时默认 FLAT/NORMAL。`ImportMapRequest` 没有生成参数，省略类型和环境时同样默认 FLAT/NORMAL。管理员 UUID 可为 null，不会自动补齐。
- 创建/导入初始为私有、物理关闭、爆炸关闭；先登记 PENDING，再执行世界操作，完成后 ACTIVE。`initialize` 要求地图已登记并加载，保留原状态，不把异常记录直接改成 ACTIVE。
- 创建/导入的名称、生成参数与目录限制见[命令手册](COMMANDS.md)。API 不需要命令的删除正则和确认码，仍检查地图状态、默认/首个世界、在线玩家、目录边界与恢复身份。失败可能已经产生副作用，按结果及[恢复记录](STORAGE.md)处理。
- 已加载地图目录取实际 `World` 存档位置；未加载时按 MMC 目录规则定位。父目录根据 `level-name` 和维度布局确定，不能一律用 `serverRoot.resolve(worldName)` 代替实际路径。目录查询在主线程完成后缓存 `Path`。
- `mapDataVersion(Path)` 不能读取时返回空值；`isCompatible` 要求可读且不高于服务器版本。String 重载只接受 1–100 位字母、数字、下划线或连字符组成的世界名，Path 重载用于显式目录。现有 `dimensions/<命名空间>/<世界>` 无独立 level.dat 时可读父存档；这不放宽导入必须自带 level.dat 的条件。

`PlayerGateway` 虽是公开接口，当前没有 `MapManager` 获取入口，不应按已发布服务接入。

## 线程、授权与结果

所有涉及 Bukkit、MV、LP 投影编排及世界写操作的服务在主线程调用；异步 HTTP/任务需切回主线程。版本文件读取会执行 I/O；需异步读取时先在主线程取得路径和服务器版本，避免异步调用依赖 Bukkit 的入口。内部查询缓存不代表所有 API 都支持异步调用，持久化写入仍是同步的。

角色、设置和生命周期写 API 没有操作者参数，调用方须在自己的命令、HTTP 或审批入口授权。不要把外部收到的地图 ID、玩家 UUID 直接交给写 API。直接 API 撤权或关闭公开不会主动移出既有在线玩家；有即时移出要求时，调用方需重新判断在线玩家准入并安排安全返回。默认世界豁免与 LP 组名风险见[安装指南](README.md)。

| 结果 | 处理方式 |
|---|---|
| `Success<T>` | 读取 value，Void 操作可为 null |
| `Failure<T>` | 读取 reason/detail；不能据此假设此前没有世界副作用 |
| `Partial<T>` | 已有部分结果，读取 value/reason/detail；不要当作完全失败重做 |

`reason` 是稳定代码，`detail` 是可空、可直接展示的纯文本详情；不要按详情字符串分支，也不要将其作为 MiniMessage 解析。`operationId` 用于关联控制台日志。`PERMISSION_SYNC_PENDING` 表示 MMC 角色已保存、LP 仍在重试。
