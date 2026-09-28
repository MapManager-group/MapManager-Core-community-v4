# MapManager Core v4 架构

## 1. 目标和依据

v4 是独立的新项目，不在 MapManager-Core-community（v3）内修改。v3 仓库和它的 README、源码只作为功能与数据迁移参考；其中的安装说明、依赖要求或实现选择不自动成为 v4 的指令。若另有附件，附件内容也只作为参考材料，除非用户明确把其中某条作为需求。

v4 的目标是保留 v3 用户功能：地图创建、导入、初始化和删除；地图组共享管理员与建筑师；单地图访客；公开状态；物理和爆炸设置；地图别名、出生点、成员管理、踢出玩家、地图传送；按需加载；配置保存、重载和状态查看。当前实现进度见 [CLASS_STATUS.md](CLASS_STATUS.md) 和 [STRUCTURE.md](STRUCTURE.md)。

## 2. 模块与依赖方向

采用两个 Maven 模块，Maven groupId 和 Java 包根为 `studio.lorian.mapmanager`：

```text
api (mapmanager-api)
    ↑
plugin (mapmanager-plugin)
  ├─ application / domain
  ├─ internal storage ports + JSON / database adapters
  ├─ LuckPerms adapter
  ├─ Multiverse-Core adapter
  └─ Bukkit commands / listeners / notifications
```

| 模块     | 职责                                                       | 可见边界                                            |
|----------|------------------------------------------------------------|-----------------------------------------------------|
| `api`    | 对外模型、服务接口、操作结果                               | 不依赖 plugin、存储、LuckPerms 或 Multiverse 的类型 |
| `plugin` | 插件入口、业务用例、地图准入、生命周期编排、命令和事件适配 | 依赖 api；存储与适配接口留在内部包                  |
| `server` | 本地mc测试服，用于测试插件各项功能是否正常                 | -                                                   |

当前包结构以 [STRUCTURE.md](STRUCTURE.md) 的源码目录和方法依赖为准。下面保留早期的能力划分作为后续扩展参考：

```text
api/src/main/java/studio/lorian/mapmanager/api/
  model/       MapInstance, MapRole, MapSettings
  service/     MapService, MapRoleService, MapSettingsService, MapAccessService,
               MapTravelService, WorldLifeCycleService, WorldVersionService
  result/      OperationResult, FailureReason
  event/       面向扩展插件的公开事件

plugin/src/main/java/studio/lorian/mapmanager/plugin/
  bootstrap/   插件启动、依赖检查、服务注册
  application/ 用例与事务/补偿流程
  domain/      内部规则与策略
  access/      准入判定和 Bukkit 事件监听
  lifecycle/   WorldLifeCycleService 实现及恢复任务
  permission/  LuckPerms 投影适配器
  world/       Multiverse-Core 适配器
  storage/     内部 repository、JSON/数据库实现、迁移
  command/     解析、校验、用例调用、结果通知
  notification/统一通知路由与消息格式
```

`api` 是调用方编译依赖，`plugin` 已有 Paper 入口和实际业务实现。主类装配服务并注册 `/world`、`/wtp` 命令及 `MapAccessListener`，但尚未通过 Bukkit ServicesManager 注册 `MapManager`。`PlayerGateway`、`WorldGateway` 和 `WorldVersionService` 接口位于 `api.service`，实现位于 `plugin`；`WorldGateway` 仅查询目录并返回 JDK `Path`，地图版本服务公开版本读取和兼容性判断，创建和导入等操作统一由 `WorldLifeCycleService` 定义。API 不暴露 repository、DAO、存储配置类、数据库连接或 JSON/SQL 实体。v3 接口分类见 [API_INVENTORY.md](API_INVENTORY.md)。

## 3. API 核心类型

### `MapInstance`

代表 MMC 管理的一张地图，不等同于 Bukkit `World` 或 `MultiverseWorld`。包含稳定的地图 ID、世界名、显示名、地图组 ID、地图设置（其中包含公开状态）、持久状态和可空出生点。访客集合通过 `MapRoleService` 查询。世界是否加载、世界对象和文件位置由运行时适配器管理。

### `MapRole`

```java
public enum MapRole {
    ADMIN, BUILDER, VISITOR
}
```

角色判断由 MMC 数据完成，玩家 ID 使用 UUID；玩家名仅用于显示和旧数据导入。角色范围：

- `ADMIN`：地图组范围；可以管理该组成员与地图设置，并拥有 builder 权限。
- `BUILDER`：地图组范围；允许进入组内地图，并获得继承自 LP `worldbase` 的地图建造权限。
- `VISITOR`：单地图范围；仅允许进入被授权的地图，不获得 `worldbase` 权限。
- `PUBLIC` 是地图访问策略，不是第四种角色。公开地图允许访客进入，但不会授予建造权限。

同一玩家可能同时有多种来源，权限取最高有效角色：ADMIN > BUILDER > VISITOR。移除某角色只删除该来源的授予，不应意外清除其他来源仍然有效的角色。

### 服务接口

```java
public interface WorldLifeCycleService {
    OperationResult<MapInstance> create(CreateMapRequest request);
    OperationResult<MapInstance> importWorld(ImportMapRequest request);
    OperationResult<MapInstance> initialize(String worldName);
    OperationResult<Void> delete(String worldName);
}

public interface WorldVersionService {
    int serverDataVersion();
    OptionalInt mapDataVersion(Path worldFolder);
    boolean isCompatible(Path worldFolder);
    boolean isCompatible(String worldName);
}
```

以上为当前接口。`WorldVersionService` 使用 Querz NBT 读取 `level.dat` 的 `Data.DataVersion`，并与 Bukkit 当前服务器 dataVersion 比较；NBT 实现留在 plugin。API 尚未暴露按需加载方法；当前 `MapTravelServiceImpl` 也要求目标世界已加载。API 中只出现 MMC 自己的类型和 JDK 类型；不把 Bukkit、LuckPerms 或 Multiverse 的实现对象泄漏给调用方。

## 4. 准入和 LuckPerms

### MMC 是唯一准入来源

`MapAccessService` 根据 MMC 持久数据计算角色、公开状态和准入决策。不得用 `multiverse.access.<world>` 或 LP 的权限查询作为地图准入依据。删除 v3 的 LP `multiverse.access.*` 同步逻辑，避免两套权威互相覆盖。

`MapAccessListener` 通过 `access-control.strict-map-entry` 控制，默认启用；关闭时传送/换世界检查、加入检查、重生检查和撤销权限后的移出均停用。启用时覆盖所有实际进入地图的路径：

1. 在 `PlayerTeleportEvent` 高优先级检查目标世界并取消未授权传送，覆盖原版传送命令、Multiverse 及调用 Bukkit 传送 API 的插件。
2. 在 `MONITOR` 阶段排队复核传送后的实际世界，并监听 `PlayerChangedWorldEvent`，处理其他插件覆盖预检查或直接改变世界的情况。
3. 在玩家加入和重生时检查当前/目标世界；目标不允许时送往 Multiverse 默认世界出生点。
4. 角色撤销后重新计算当前地图的有效准入；只有实际失去进入权的在线玩家才会被移出。管理员、建筑者、访客或公开状态仍允许进入时不会被移出。
5. 默认世界不可用、玩家没有权限进入默认世界或传送失败时断开连接，避免玩家留在未授权地图。

事件监听仅负责接 Bukkit 事件和执行准入结果；规则集中在 `MapAccessService`，避免命令传送、插件传送各自实现权限判断。v3 JSON 迁移完成后还会复核已在线玩家，避免启动期间进入迁移后才登记的地图。

### LuckPerms 只提供 `worldbase` 能力

插件启动时若 LP 中没有 `worldbase`，则异步创建并使其继承 `default`；已有组保持原样。服主手工维护 `worldbase` 内的权限节点。MMC 为地图角色维护投影：地图组对应的 LP 组按世界上下文继承 `worldbase`，管理员和建筑者成员镜像到地图组，访客不投影。MMC 数据仍是角色和地图访问状态的权威来源；LP 写入目前会同步等待，自动重试队列尚未实现。

MMC 不创建或修改 `worldbase` 内的权限节点，不把所有玩家加入 `worldbase`，也不使用 LP 的任何地图访问节点决定是否可入图。对 LP 写入失败应返回可识别的失败结果，并进入重试/重同步队列；不得把数据存储成功伪装成 LP 完全同步成功。

## 5. Multiverse-Core 和地图生命周期

Multiverse-Core 仅是世界操作后端：创建、导入、加载、出生点/世界配置更新和世界删除。地图成员、访问权、地图组、公开状态与 MMC 的元数据由 MMC 管理。

`WorldLifeCycleService` 编排操作，不让命令直接调用 Multiverse。生命周期可采用以下状态：

```text
UNREGISTERED → PENDING → ACTIVE
ACTIVE → ARCHIVED / DELETING → DELETED
失败 → 在操作记录中保存失败阶段和原因，地图记录保留当前状态
```

世界文件操作和数据库事务不能组成一个原子事务，因此 create/import/delete 必须按步骤记录 operation ID 和阶段。失败时执行有边界的补偿：清除本次创建的 MMC 元数据或标记待恢复；删除操作在数据文件真正删除前验证地图 ID、世界目录和在线玩家，并保留操作记录。显式删除世界需要与 v3 一致的确认步骤。

所有 Bukkit/Multiverse 世界对象操作在主线程执行；用户名 UUID 解析等可能访问网络的操作异步执行。当前存储访问与部分 LuckPerms 操作仍在调用线程同步等待，后续异步化时需保证 Bukkit 对象操作回到主线程。

## 6. 内部存储边界

内部存储接口仅放在 plugin 的 `storage` 包中：`MapRepository`、`RoleRepository`、`OperationRepository`。服务层依赖这些接口；JSON 和数据库 adapter 将实现这些接口。它们不加入 `api`，不通过 Bukkit ServicesManager 注册。

配置见 [../plugin/src/main/resources/config.yml](../plugin/src/main/resources/config.yml)。当前支持 JSON 和 MySQL。MySQL 表结构、索引及关联见 [PLUGIN.md](PLUGIN.md)。JSON 使用临时文件原子替换；MySQL 使用外键约束。切换后端需要显式迁移，启动时检查 `storage-backend.txt`，避免静默读取另一套数据。

## 7. 统一操作结果和通知

所有修改数据或外部世界状态的用例返回统一结果，不在各命令/listener 中散落字符串：

`OperationResult` 声明为 `Success`、`Failure`、`Partial` 三种结果；每个结果都包含 `OperationName` 和 UUID 类型的 operation ID。`Partial` 表示 MMC 状态已成功写入，但 LuckPerms 等外部投影仍待同步。具体签名见 `api/src/main/java/studio/lorian/mapmanager/api/result/OperationResult.java`。

`FailureReason` 使用稳定代码，展示文本通过插件数据目录中的 `langs/<语言代码>.yml` 本地化，默认语言为 `zh_CN`；缺失翻译项回退到内置中文资源。通知和内部启动/存储诊断使用 `Messages`；部分底层库异常详情及 Java 插件框架自身产生的报错仍由依赖原样提供，未经过语言文件转换。`OperationNotifier` 将操作名和失败原因渲染为 MiniMessage Component，发送给命令调用者；日志使用相同模板的纯文本。API 结果继续保留原因代码、operation ID 和操作名称，供调用方处理。

命令层只做参数解析、发送者上下文检查和权限节点校验，然后调用一个应用用例并交给 notifier。Tab 补全也使用同一个准入/可见性规则，不根据 LP `multiverse.access.*` 单独过滤。

## 8. v3 数据迁移

启动迁移器在相邻的 `MapManager-Core` 插件数据目录中检测 v3 `worlds.json`、`groups.json`，并读取 LP 默认组公开节点及 Multiverse 别名、出生点。它只读取这些旧数据和元数据，写入所选新 repository，不调用 v3 插件；如果两个 JSON 文件只存在一个，迁移失败并禁用插件。

| v3 字段/信息 | v4 目标 |
|---|---|
| `WorldNode.group` | `MapInstance.groupId` |
| `WorldNode.visitors` | 单地图 VISITOR 授予；由名称解析 UUID |
| `WorldNode.exploded`、`physical` | 各地图的 `MapSettings` |
| `WorldGroup.admins`、`builders` | 地图组范围的 ADMIN、BUILDER 授予；名称解析 UUID |
| `WorldGroup.worlds` | 与 `MapInstance.groupId` 的关系交叉校验 |
| Multiverse alias、出生点 | 读取并保存为地图展示名和出生点；其他 Multiverse 设置不会复制 |
| v3 的公开 LP `multiverse.access.*` 状态 | 转成 `MapSettings.isPublic` 后不再用该节点准入 |

当前启动迁移在导入前检查地图/组冲突，备份两份源 JSON 到 v4 数据目录的 `v3-source-backup/`，解析所有玩家 UUID 后写入选定仓储，并写入 `v3-migration.done` 标记。无法解析 UUID 或遇到冲突会使迁移失败；dry-run、目标数据库备份及详细差异报告尚未实现。全部写入成功后，完成标记会阻止重复导入；如果写入过程中失败，标记尚未生成，部分已写入的数据可能使重试冲突或重复角色记录，因此迁移失败后需先核查目标仓储。

## 9. 安全、并发和失败约定

- 以 UUID 为玩家主键，世界名称规范化后唯一；显示名与内部 ID 分离。
- 对所有命令输入做边界校验；玩家命令权限和应用服务授权都要校验，避免绕过命令直接调用 API。
- 世界创建/删除对同一世界串行化；跨线程调用 Bukkit 对象要禁止。
- 每个 lifecycle 操作有稳定 operation ID、当前阶段和失败原因；操作记录尚未用于崩溃恢复，当前也没有 `/mapadmin operations` 命令。
- 数据写入成功但 LP 镜像失败时，地图角色仍按 MMC 数据生效，结果应明确标记部分失败并允许重试同步。
- 缺失可选依赖时在启动期给出明确诊断；Multiverse-Core 是世界生命周期所需依赖，LuckPerms 连接器只影响 `worldbase` 能力镜像，不影响 MMC 准入判断。

## 10. 尚未完成的工作

1. 完成创建、导入和删除失败恢复；让 LP 投影异步执行并支持自动重试；实现玩家断线 API。
2. 为 v3 自动迁移增加 dry-run、目标备份及详细差异报告；实现 JSON/MySQL 显式后端迁移。
3. 将 `MapManager` 注册到 Bukkit ServicesManager，随后完成构建和 Paper、Multiverse-Core、LuckPerms 集成验证。
