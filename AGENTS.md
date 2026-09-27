# MapManager v4 类清单与待办

> 每次开始处理本仓库的任务时，先读取本文件；新增、删除或调整类的职责与完成状态后，同步更新本文件。以下状态以 **2026-09-27** 的源码为准。v3 仓库仅作功能和迁移格式参考。

## 边界与状态说明

- `api` 放对外模型及接口，`plugin` 放实现。`PlayerGateway`、`WorldGateway` 接口在 `api.service`；存储接口 `MapRepository`、`RoleRepository`、`OperationRepository` 仍只在 `plugin` 内部。
- MMC 是地图准入和角色的权威来源；LuckPerms 只投影 `worldbase` 建造权限；Multiverse-Core 负责世界操作。`WorldGateway` **只查存储目录**，创建、导入、删除等由 `WorldLifeCycleService` 定义。
- 状态：**契约/数据**＝类型已定义；**已实现**＝列出的功能有实际代码；**部分实现**＝有真实逻辑但仍缺关键环节；**占位**＝方法仍返回 `null`/`false`/空值或空方法体。
- 当前插件初始化 JSON/MySQL 存储并异步确保 LP `worldbase` 组存在；创建、导入、初始化、地图传送用例及 `/world create|import|init|tp`、`/wtp` 已接入 Multiverse 与 LP，但尚未注册完整 API 和准入监听器，不能作为完整的地图管理插件部署。其他占位服务仍未完成。

## api 模块：入口、模型与结果

源码根目录：`api/src/main/java/studio/lorian/mapmanager/api/`。

| 类型 | 作用 | 状态与待办 |
|---|---|---|
| `MapManager` | 地图、角色、准入、设置、别名、传送、生命周期服务的统一入口 | 契约；待所有服务可用后由插件注册到服务管理器 |
| `CreateMapRequest` | 创建地图所需名称、组、可选首位管理员、世界类型和多组生成参数 | 数据；参数在创建用例中校验 |
| `ImportMapRequest` | 导入服务端根目录现有世界所需参数 | 数据；导入流程会校验目录和 `level.dat` |
| `MapGroup` | 组 ID 与组内世界名的不可变快照 | 数据；世界名由地图的 `groupId` 关联计算 |
| `MapInstance` | 地图 UUID、世界名、组、展示名、设置、持久状态及出生点快照 | 数据；生命周期状态转移待接入 |
| `MapRole` | `ADMIN`、`BUILDER`、`VISITOR` 三种角色 | 数据；LP 投影待接入 |
| `MapSettings` | 单地图物理、爆炸、公开状态 | 数据；不包含 PVP 或全局覆盖 |
| `MapStatus` | `PENDING`、`ACTIVE`、`ARCHIVED`、`DELETING`、`DELETED` | 数据；创建及导入流程会从 `PENDING` 转为 `ACTIVE`，失败恢复仍待实现 |
| `SpawnPoint` | 不依赖 Bukkit 的世界名、坐标与朝向 | 数据；与 Multiverse 出生点同步待实现 |
| `WorldLoadState` | 世界不存在、未加载、已加载的运行时状态 | 数据；查询实现待接入 |
| `WorldType` | 普通、平坦、虚空、下界、末地世界类型 | 数据；生成/导入映射待实现 |
| `FailureReason` | 统一失败原因代码 | 数据；随业务实现补充必要原因，避免在命令中散写字符串 |
| `OperationName` | 统一操作名称代码 | 数据；随业务操作使用 |
| `OperationResult` | `Success`、`Failure`、`Partial` 及 operation ID | 契约/数据；通知器尚未使用它统一分发消息 |

## api 模块：服务接口

| 类型 | 作用 | 状态与待办 |
|---|---|---|
| `AccessDecision` | 准入结果、有效角色及拒绝原因 | 数据；规则由 `MapAccessServiceImpl` 计算 |
| `MapAccessService` | 判断能否入图，列出可访问及可管理地图 | 契约；事件层全路径准入拦截和进入后复核待实现 |
| `MapRoleService` | 查询、授予和撤销地图角色 | 契约；组角色的 LP 投影和授权检查待实现 |
| `MapService` | 地图和地图组快照查询 | 契约；已有存储实现 |
| `MapSettingsService` | 修改单地图公开、展示名、物理、爆炸和出生点 | 契约；外部世界设置同步及调用方授权待实现 |
| `MapAliasService` | 两个重载按地图公开状态同步 MV 别名：不传别名用地图名，传别名用指定文字 | 契约；公开图使用 `&2`，非公开图使用 `&3` |
| `MapTravelService` | 入图传送和踢出地图 | 契约；实现检查 MMC 准入并按需加载世界后传送，踢出地图待实现 |
| `PlayerGateway` | 异步解析用户名到 UUID；在线状态、当前世界、传送与断线操作 | 契约；UUID 查询及前两项在线查询已实现，传送/断线待实现 |
| `WorldGateway` | 根据 `level-name` 和维度目录定位地图父目录及地图目录 | 契约；目录实现已完成，`level-name` 不可用时回退到第一个已加载世界 |
| `WorldLifeCycleService` | 创建、导入、初始化、删除、加载及延迟卸载地图 | 契约；创建、导入和初始化已实现，其余世界操作仍占位；世界操作只在这里编排 |

## plugin 模块：入口与业务实现

源码根目录：`plugin/src/main/java/studio/lorian/mapmanager/plugin/`。

| 类型 | 作用 | 状态与待办 |
|---|---|---|
| `MapManagerPlugin` | Paper 主类；加载配置/存储，装配生命周期服务和 `/world`、`/wtp` 命令，并取得 LuckPerms API 异步初始化 `worldbase` | 部分实现；LP 初始化失败会禁用插件；尚未注册准入监听器，也未发布完整 API |
| `MapWorldCommand` | 通过子命令注册表分派 `/world create|import|init|tp`，并把 `/wtp` 映射到 `tp` | 已实现；`create/import/init` 仅允许控制台且不检查命令权限；玩家命令须声明权限，`tp` 使用默认关闭的 `mapmanager.command.tp` |
| `MapManagerImpl` | 组装并返回各业务服务 | 部分实现；已注入别名服务、存储、操作记录、LP 投影和玩家网关；旅行服务接入准入及生命周期加载，踢出与其他生命周期用例仍待实现 |
| `MapServiceImpl` | 从 repository 查询地图及组 | 已实现；尚未通过主类发布 API |
| `MapRoleServiceImpl` | 组范围管理员/建筑师、地图范围访客的查询和持久化 | 部分实现；组角色写入仅返回 `Partial`，LP 投影与调用方授权待接入 |
| `MapAccessServiceImpl` | 依据 `ACTIVE` 状态、MMC 角色和 `isPublic` 计算准入 | 部分实现；没有 Bukkit 监听器拦截传送、登录、重生及实际换世界 |
| `MapSettingsServiceImpl` | 将单地图设置及出生点写入 repository；公开状态切换时读取 MV 原别名、去除前导颜色代码后换色；展示名修改委托别名服务 | 部分实现；出生点与世界后端同步、调用方授权待接入；别名同步失败返回 Partial |
| `MapAliasServiceImpl` | 无别名参数时使用地图名；显式别名时清除颜色代码并保存纯文本，再根据 MMC `isPublic` 设置、保存 MV 别名 | 已实现；存储别名不含颜色代码，MV 非公开为 `&3<name>`、公开为 `&2<name>`，需在主线程调用 |
| `MapTravelServiceImpl` | 检查入图准入、按需加载并传送玩家；将玩家移出地图 | 部分实现；`teleport` 要求主线程、检查在线状态和 MMC 准入，加载地图后传送至保存出生点或世界默认出生点；`kickFromMap` 待实现 |
| `WorldLifeCycleServiceImpl` | 创建、导入、初始化、删除、加载和延迟卸载的用例入口 | 部分实现；创建校验 4–16 位小写地图名并生成世界；导入只接收服务端根目录下含 `level.dat` 的同名目录；两者通过 `registerWorld` 共用状态、角色、LP 投影并调用 `initializeWorld`，未传别名时用地图名、传别名时用指定文字；手动 `initialize` 使用已保存别名；之后应用 MV 属性、生物生成与 gamerule；失败留下 `PENDING` 供恢复；加载操作已实现，删除及延迟卸载仍占位；调用要求主线程且 LP 写入目前会同步等待 |

## plugin 模块：迁移、通知、权限和玩家

| 类型 | 作用 | 状态与待办 |
|---|---|---|
| `LegacyRoleGrant` | v3 成员授予的中间数据，保留世界、角色、玩家名与 UUID | 数据；迁移读取和名称解析待接入 |
| `LegacySnapshot` | v3 地图、角色及告警的只读快照 | 数据；由迁移器填充；正式导入尚未实现 |
| `V3MigrationSource` | 读取旧格式的内部端口 | 契约；只读源文件解析待实现 |
| `V3MigrationSourceImpl` | v3 源数据读取实现位置 | 占位；`read` 返回 `null`；需读 `worlds.json`、`groups.json` 与相关设置 |
| `NotificationTarget` | 通知目标：玩家、控制台或仅日志 | 数据；实际分发待接入 |
| `OperationNotifier` | 统一分发操作结果的内部端口 | 契约；通知实现待接入 |
| `OperationNotifierImpl` | 将成功、失败、部分完成转换为消息，并发送给在线玩家或控制台 | 已实现基本格式；失败统一输出 `failed, <reason>, <operation>`；本地化和更细的通知策略待补 |
| `WorldBaseProjection` | LP `worldbase` 创建及地图组继承、成员、清理的内部端口 | 契约；不能作为 MMC 准入判断来源 |
| `WorldBaseProjectionImpl` | 缺失时创建 `worldbase` 并继承 `default`；创建同名 LP 地图组并按世界上下文继承 `worldbase`，同步首位管理员 LP 组成员 | 部分实现；已有 `worldbase` 不修改；移除地图组仍占位，成员撤销及失败重试待完成 |
| `PlayerGatewayImpl` | Paper 在线/缓存 UUID 查询、online-mode 判定、异步 Mojang 查询、离线 UUID 计算及玩家操作 | 部分实现；在线玩家传送已实现且要求主线程，`disconnect` 仍占位；使用 Bukkit 对象时注意主线程 |

## plugin 模块：存储与世界目录

| 类型 | 作用 | 状态与待办 |
|---|---|---|
| `MapRepository` | 地图和组的内部持久化接口 | 契约；不得移入公开 API |
| `RoleRepository` | 组角色和地图访客的内部持久化接口 | 契约；不得移入公开 API |
| `OperationRepository` | 生命周期操作记录的内部持久化接口 | 契约；恢复流程尚未消费这些记录 |
| `OperationRecord` | 操作 ID、名称、世界、阶段和失败原因 | 数据；创建流程已写入各阶段；恢复流程尚未消费 |
| `OperationStage` | 开始、世界已变更、数据已保存、完成、失败 | 数据；创建流程已使用；崩溃恢复流程待实现 |
| `StorageConfig` | 解析并校验 JSON/MySQL 配置 | 已实现；对应资源为 `plugin/src/main/resources/config.yml` |
| `StorageException` | 存储异常的统一运行时类型 | 已实现 |
| `StorageValidation` | 校验地图、组 ID、出生点及世界名规范化键 | 已实现；为 plugin 内部工具类 |
| `JsonStorage` | 单文件保存地图、组、授权与操作；临时文件原子替换 | 已实现；真实服务器故障恢复与压力验证尚未完成 |
| `MySqlStorage` | JDBC 保存地图、组、角色与操作；建表、唯一索引、外键 | 已实现；实际 MySQL 集成、建表升级和连接性能尚未验证 |
| `WorldGatewayImpl` | 读取 `server.properties` 的 `level-name` 并按目录结构定位地图 | 已实现；缺失/无效/读取失败时取 `Bukkit.getWorlds().get(0)`；不负责创建世界 |
| `WorldVersionValidator` | 世界导入前的版本兼容性检查端口 | 契约；实现待完成 |
| `WorldVersionValidatorImpl` | 世界版本检查实现位置 | 占位；`canImport` 固定返回 `false` |

## 嵌套类型

| 类型 | 作用 | 状态与待办 |
|---|---|---|
| `OperationResult.Success`、`Failure`、`Partial` | 成功、失败和外部同步待完成的结果记录 | 数据；由各业务用例按真实执行结果返回 |
| `NotificationTarget.Kind` | 玩家、控制台、仅日志三种通知目标 | 数据；通知分发待实现 |
| `StorageConfig.Type` | JSON/MySQL 后端选择值 | 数据；由配置解析使用 |
| `JsonStorage.Grant` | JSON 文件中的角色授予项 | 内部数据；随角色记录读写 |
| `JsonStorage.Document` | 带版本号的 JSON 文件整体结构 | 内部数据；格式升级迁移待设计 |
| `JsonStorage.State` | JSON 后端的内存状态及写入前副本 | 内部实现；用于原子写入 |
| `MySqlStorage.SqlTask` | JDBC 连接上的数据库操作函数 | 内部实现；由 `inConnection` 统一执行和转换异常 |

## 当前主要待办

1. 完成 Multiverse 删除、创建与导入失败恢复和延迟卸载；世界创建/导入只在 `WorldLifeCycleService` 编排。
2. 完成全路径 Bukkit 准入监听，并把高频判断从直接同步 I/O 调整为适合主线程的读取方式。
3. 在已完成的 `worldbase` 与创建时地图组投影基础上，实现其他角色变更时的 LP 同步和失败重试；补齐玩家断线、踢出地图和其他命令。
4. 完成 v3 dry-run、备份、正式迁移与冲突报告；完成 JSON/MySQL 后端切换的显式迁移。
5. 业务服务可用后注册 API，再做 Maven 打包与 Paper/Multiverse/LuckPerms 集成验证。
