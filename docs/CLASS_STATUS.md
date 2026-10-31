# 类职责与实现状态

本表对应当前 4.0.2-SNAPSHOT 源码。接口/数据表示契约已定义；实现表示有实际执行逻辑，验证范围见[测试指南](TEST_CASES.md)。修改类型职责或完成状态时同步本表，边界和调用关系见[架构](ARCHITECTURE.md)。

## api

根目录：[api](../api/src/main/java/studio/lorian/mapmanager/api/)。

| 类型 | 职责 / 状态 |
|---|---|
| `MapManager` | 九个业务服务入口；插件就绪后注册，关闭时撤销 |
| `CreateMapRequest`、`ImportMapRequest` | 创建/导入请求数据；前者复制生成参数，后者不接收生成参数 |
| `MapInstance`、`MapGroup` | 不可变地图/组快照，组内世界由地图关系计算 |
| `MapRole` | ADMIN、BUILDER、VISITOR；继承和作用范围已实现 |
| `MapSettings` | 单图物理、爆炸、公开数据，不含 PVP |
| `MapStatus` | PENDING、ACTIVE、DELETING 已用于流程；ARCHIVED、DELETED 仅预留枚举 |
| `SpawnPoint` | 世界名、坐标与朝向，不依赖 Bukkit |
| `WorldType`、`Environment` | FLAT/NORMAL/VOID 与 NORMAL/NETHER/THE_END 可组合；SIFT 未支持 |
| `OperationName`、`FailureReason` | 稳定操作名和失败原因代码 |
| `OperationResult` | Success/Failure/Partial 及操作 ID；失败、部分完成可带 detail |
| `AccessDecision` | 是否允许、有效角色、拒绝原因 |
| `MapService` | 地图、组查询接口，已接缓存仓储 |
| `MapRoleService` | 直接/有效角色查询、授予和撤销接口，写入后投影 LP |
| `MapAccessService` | 准入、可访问和可管理地图接口 |
| `MapSettingsService`、`MapAliasService` | 设置、出生点及 MV 别名接口 |
| `MapTravelService` | 入图传送、检查同图管理员的踢出接口 |
| `WorldLifeCycleService` | create/importWorld/initialize/delete/load/recover 接口 |
| `WorldVersionService` | dataVersion 查询与兼容性接口 |
| `WorldGateway` | 只读目录接口，通过 worldDirectories() 提供 |
| `PlayerGateway` | 身份与玩家操作接口；有内部实现，但没有 MapManager 获取入口 |

## plugin 入口与业务

根目录：[plugin](../plugin/src/main/java/studio/lorian/mapmanager/plugin/)。以下均已实现。

| 类型 | 职责 |
|---|---|
| `MapManagerPlugin` | 配置、依赖、存储和服务装配；等待迁移/MV/worldbase，恢复后发布 API 和命令 |
| `MapManagerImpl` | 返回装配后的九个业务服务，目录查询优先实际世界路径 |
| `MapServiceImpl` | 地图与组查询 |
| `MapRoleServiceImpl` | 组角色和单图访客，默认世界豁免，持久化后入 LP 队列 |
| `MapAccessServiceImpl` | MMC 状态、角色和公开准入；默认世界直接放行 |
| `MapSettingsServiceImpl` | 持久化设置及出生点，同步 Bukkit/MV；调用方负责授权 |
| `MapAliasServiceImpl` | 展示名纯文本化、按公开状态设置 MV 颜色和保存 |
| `MapTravelServiceImpl` | 准入、按需加载、传送与同图踢出 |
| `WorldLifeCycleServiceImpl` | 创建、导入、初始化、删除、加载、恢复；请求及操作阶段落盘 |
| `WorldVersionServiceImpl` | 读取服务端 dataVersion 并查询存档版本；String 入口先校验世界名 |
| `MapWorldCommand` | 玩家命令、帮助、未登记默认世界传送、补全（非严格模式兼容 MV 准入权限候选）、授权、开关确认和在线准入复核 |
| `MapAdminCommand` | 控制台/RCON 生命周期与维护命令、解析、帮助和补全 |
| `MapAccessListener` | 严格准入的传送、加入、重生、换世界和撤权复核，安全返回或断线；向命令提供严格开关状态 |
| `MapSettingsListener` | 单图物理、完整 BlockData 落沙还原和爆炸；全局实体支撑保护 |

## plugin 内部适配、迁移与通知

| 类型 | 职责 / 状态 |
|---|---|
| `LegacyRoleGrant`、`LegacySnapshot` | v3 解析后的角色及源数据 |
| `V3MigrationSource`、`V3MigrationSourceImpl` | 旧 JSON、LP 公开状态、MV 别名/出生点读取接口与实现 |
| `V3Migration` | 预检、报告、UUID 解析、备份、冻结计划及幂等恢复；已实现 |
| `Messages` | 语言加载、缺键中文回退、MiniMessage 与纯文本；已实现 |
| `NotificationTarget` | 玩家、控制台、日志目标数据 |
| `OperationNotifier`、`OperationNotifierImpl` | 结果与语言键分发接口和实现；玩家隐藏 ID、控制台保留 |
| `WorldBaseProjection`、`WorldBaseProjectionImpl` | worldbase、地图组、上下文继承和成员投影接口与实现；不决定准入 |
| `QueuedWorldBaseProjection` | 持久 FIFO、后台重试、成员账本和重启重放；已实现 |
| `PlayerGatewayImpl` | Paper 缓存/在线身份、代理与服务端模式判断、异步 Mojang 查询、离线 UUID、传送与断线；已实现 |
| `WorldGatewayImpl` | level-name 与维度布局目录定位，不操作世界；已实现 |
| `LevelDatVersionReader` | NBT Data.DataVersion；独立维度目录可回退父存档；已实现 |
| `FlatChunkGenerator` | 内置平坦生成、持久 ID 与绝对表层 Y；已实现 |

## plugin 内部存储

| 类型 | 职责 / 状态 |
|---|---|
| `MapRepository`、`RoleRepository`、`OperationRepository` | 地图/组、角色、操作内部接口，不公开给扩展插件 |
| `OperationRecord`、`OperationStage` | 操作审计数据和阶段 |
| `StorageConfig`、`StorageValidation`、`StorageException` | 配置解析、持久数据约束与异常类型；已实现 |
| `JsonStorage` | 格式版本 1、临时替换、成功后发布、空组角色级联；已实现 |
| `MySqlStorage` | JDBC 五表、唯一索引、外键与角色级联；已实现 |
| `CachedStorage` | 写成功后发布不可变地图/组/角色索引；已实现 |
| `StorageSnapshot` | 完整快照、冲突预检和幂等写入；已实现 |
| `StorageBackendMigration` | 来源指纹、备份、目标核对、后端标记与配置保存收尾；已实现 |
| `RecoveryJournal` | 完整请求、地图 ID 和删除目录身份原子落盘；已实现 |

嵌套类型随所属类维护：结果记录、通知目标、存储类型、JSON 文档/状态/授权、快照授权、恢复计划、队列任务、缓存读状态、命令 Registration 和 SQL 任务均属于其所在类，不是额外服务。

未实现项：SIFT 环境、归档用例、PVP、空闲卸载、龙蛋保护、全局物理/爆炸覆盖和配置热重载。内部有实现不代表已经公开 API；特别是 PlayerGateway 没有获取入口。
