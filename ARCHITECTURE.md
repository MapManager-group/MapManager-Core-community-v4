# MapManager Core v4 架构

## 1. 目标和依据

v4 是独立的新项目，不在 MapManager-Core-community（v3）内修改。v3 仓库和它的 README、源码只作为功能与数据迁移参考；其中的安装说明、依赖要求或实现选择不自动成为 v4 的指令。若另有附件，附件内容也只作为参考材料，除非用户明确把其中某条作为需求。

需要保留的 v3 用户功能包括：地图创建、导入、初始化和删除；地图组共享管理员与建筑师；单地图访客；公开状态；物理和爆炸设置；地图别名、出生点、成员管理、踢出玩家、地图传送；按需加载；配置保存、重载和状态查看。v4 可以重组实现，但不应丢失这些用户可见行为。

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

| 模块 | 职责 | 可见边界 |
|---|---|---|
| `api` | 对外模型、服务接口、操作结果 | 不依赖 plugin、存储、LuckPerms 或 Multiverse 的类型 |
| `plugin` | 插件入口、业务用例、地图准入、生命周期编排、命令和事件适配 | 依赖 api；存储与适配接口留在内部包 |

当前包结构以 [STRUCTURE.md](STRUCTURE.md) 的源码目录和方法依赖为准。下面保留早期的能力划分作为后续扩展参考：

```text
api/src/main/java/studio/lorian/mapmanager/api/
  model/       MapInstance, MapRole, MapSettings
  service/     MapService, MapRoleService, MapSettingsService, MapAccessService,
               MapTravelService, WorldLifeCycleService
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

`api` 是调用方编译依赖，`plugin` 已具备 Paper 插件入口和接口占位实现。业务功能完成前不会注册对外 API 服务。`PlayerGateway` 和 `WorldGateway` 的接口位于 `api.service`，实现位于 `plugin`；`WorldGateway` 仅查询目录并返回 JDK `Path`，创建和导入等操作统一由 `WorldLifeCycleService` 定义。API 不暴露 repository、DAO、存储配置类、数据库连接或 JSON/SQL 实体。v3 接口分类见 [API_INVENTORY.md](API_INVENTORY.md)。

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
    OperationResult<MapInstance> load(String worldName);
}
```

以上为能力边界节选；完整接口见 `api/src/main/java`。API 中只出现 MMC 自己的类型和 JDK 类型；不把 Bukkit、LuckPerms 或 Multiverse 的实现对象泄漏给调用方。世界目录查询的 `Path` 由 `WorldGateway` 提供。

## 4. 准入和 LuckPerms

### MMC 是唯一准入来源

`MapAccessService` 根据 MMC 持久数据计算角色、公开状态和准入决策。不得用 `multiverse.access.<world>` 或 LP 的权限查询作为地图准入依据。删除 v3 的 LP `multiverse.access.*` 同步逻辑，避免两套权威互相覆盖。

准入监听必须覆盖所有实际进入地图的路径：

1. 在传送/世界切换等可取消事件的高优先级阶段检查目标世界，拒绝未授权进入。
2. 在 `MONITOR` 阶段排队进行实际位置复核，处理其他插件绕开或覆盖预检查后直接把玩家放入目标世界的情况。
3. 对出生重生、登录落点和其他会改变所在世界的入口做对应检查。若目标不允许，送往已配置的安全世界出生点；安全世界不存在或传送失败时按配置执行断开连接并记录原因。
4. 玩家在目标地图权限发生变化时，可按策略立即移出已失去访问权的玩家。

事件监听仅负责接 Bukkit 事件和执行准入结果；规则集中在 `MapAccessService`，避免命令传送、插件传送各自实现权限判断。

### LuckPerms 只提供 `worldbase` 能力

插件启动时若 LP 中没有 `worldbase`，则异步创建并使其继承 `default`；已有组保持原样。服主手工维护 `worldbase` 内的权限节点。MMC 为地图角色维护子组/继承关系：地图组内 admin、builder 角色对应的 LP 组继承 `worldbase`；visitor 不继承。角色成员可异步镜像到 LP，MMC 数据仍是角色和地图访问状态的权威来源。

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

所有 Bukkit/Multiverse 世界对象操作在主线程执行；文件扫描、数据库和 LuckPerms 网络/存储调用异步执行。通过调度器回到主线程更新世界状态，命令响应只在操作完成或明确进入后台阶段后发出。

## 6. 内部存储边界

内部存储接口仅放在 plugin 的 `storage` 包中：`MapRepository`、`RoleRepository`、`OperationRepository`。服务层依赖这些接口；JSON 和数据库 adapter 将实现这些接口。它们不加入 `api`，不通过 Bukkit ServicesManager 注册。

配置见 [plugin/src/main/resources/config.yml](plugin/src/main/resources/config.yml)。当前支持 JSON 和 MySQL。MySQL 表结构、索引及关联见 [plugin/README.md](plugin/README.md)。JSON 使用临时文件原子替换；MySQL 使用外键约束。切换后端需要显式迁移，启动时检查 `storage-backend.txt`，避免静默读取另一套数据。

## 7. 统一操作结果和通知

所有修改数据或外部世界状态的用例返回统一结果，不在各命令/listener 中散落字符串：

`OperationResult` 声明为 `Success`、`Failure`、`Partial` 三种结果；每个结果都包含 `OperationName` 和 UUID 类型的 operation ID。`Partial` 表示 MMC 状态已成功写入，但 LuckPerms 等外部投影仍待同步。具体签名见 `api/src/main/java/studio/lorian/mapmanager/api/result/OperationResult.java`。

`FailureReason` 使用稳定代码（如 `MAP_NOT_FOUND`、`ALREADY_EXISTS`、`INVALID_NAME`、`NO_PERMISSION`、`WORLD_BACKEND_FAILED`、`STORAGE_FAILED`、`LP_SYNC_FAILED`），展示文本通过语言文件本地化。统一 `OperationNotifier` 面向命令发送者、控制台日志和扩展事件分发结果。失败的标准结构是 `failed, <reason>, <operation>`；玩家界面可以翻译为自然语言，但日志/API 事件保留稳定代码、operation ID 和 operation 名称。

命令层只做参数解析、发送者上下文检查和权限节点校验，然后调用一个应用用例并交给 notifier。Tab 补全也使用同一个准入/可见性规则，不根据 LP `multiverse.access.*` 单独过滤。

## 8. v3 数据迁移

迁移器读取 v3 `worlds.json`、`groups.json`，以及安装环境中 Multiverse 的世界元数据和 LP 组信息。它只读取旧格式，写入新 repository，不在迁移过程中调用 v3 插件。

| v3 字段/信息 | v4 目标 |
|---|---|
| `WorldNode.group` | `MapInstance.groupId` |
| `WorldNode.visitors` | 单地图 VISITOR 授予；由名称解析 UUID |
| `WorldNode.exploded`、`physical` | 各地图的 `MapSettings` |
| `WorldGroup.admins`、`builders` | 地图组范围的 ADMIN、BUILDER 授予；名称解析 UUID |
| `WorldGroup.worlds` | 与 `MapInstance.groupId` 的关系交叉校验 |
| alias、出生点、Multiverse 设置 | 读取并保留到地图元数据/世界配置中 |
| v3 的公开 LP `multiverse.access.*` 状态 | 转成 `MapSettings.isPublic` 后不再用该节点准入 |

迁移运行 `dry-run` 时报告可迁移/冲突/缺失条目，不写入目标存储。正式迁移需备份源文件和目标库；通过来源路径、内容摘要、迁移版本和结果记录确保重复执行幂等。无法解析 UUID 的名字保留为待处理条目，不静默丢弃。迁移完成后核对地图数、组成员数、访客数、设置数，并逐项列出差异。

## 9. 安全、并发和失败约定

- 以 UUID 为玩家主键，世界名称规范化后唯一；显示名与内部 ID 分离。
- 对所有命令输入做边界校验；玩家命令权限和应用服务授权都要校验，避免绕过命令直接调用 API。
- 世界创建/删除对同一世界串行化；跨线程调用 Bukkit 对象要禁止。
- 每个 lifecycle 操作有稳定 operation ID、开始时间、当前阶段、失败原因，便于崩溃恢复和 `/mapadmin operations` 查询。
- 数据写入成功但 LP 镜像失败时，地图角色仍按 MMC 数据生效，结果应明确标记部分失败并允许重试同步。
- 缺失可选依赖时在启动期给出明确诊断；Multiverse-Core 是世界生命周期所需依赖，LuckPerms 连接器只影响 `worldbase` 能力镜像，不影响 MMC 准入判断。

## 10. 建议实现顺序

1. 建立 API 模块和类型：`MapInstance`、`MapRole`、结果类型、服务接口。
2. 建立 Core 内部 repository 和 JSON backend，先完成启动加载、原子保存、校验。
3. 实现 MMC 准入策略与所有 Bukkit 入口复核；准入不依赖 LP/MV access 节点。
4. 实现角色管理用例、统一 notifier、命令解析框架。
5. 接入 LuckPerms 子组到 `worldbase` 的投影及重试同步。
6. 接入 Multiverse lifecycle adapter 与带阶段记录的 create/import/init/delete/load。
7. 完成 v3 dry-run/正式迁移及差异报告。
8. 按 v3 命令和功能清单迁移剩余地图设置、传送和管理命令。
