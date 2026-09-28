# MapManager v4 目录与调用关系

本文记录当前源码中的模块边界和关键方法依赖。类的完成状态见 [CLASS_STATUS.md](CLASS_STATUS.md)，设计目标见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 当前目录

```text
MapManager-v4/
├─ api/                                      对外 Maven 模块
│  └─ src/main/java/studio/lorian/mapmanager/api/
│     ├─ MapManager.java                     统一服务入口契约
│     ├─ model/                              地图、组、角色、设置、状态、请求模型
│     ├─ result/                             操作名、失败原因、操作结果
│     └─ service/                            地图、角色、准入、世界及玩家接口
├─ plugin/                                   Paper 插件 Maven 模块，依赖 api
│  └─ src/main/
│     ├─ java/studio/lorian/mapmanager/plugin/
│     │  ├─ MapManagerPlugin.java             启动及对象装配
│     │  ├─ impl/                             API 服务实现与生命周期编排
│     │  └─ internal/
│     │     ├─ command/                       /world 与 /wtp 适配
│     │     ├─ listener/                      地图设置与准入 Bukkit 事件适配
│     │     ├─ migration/                     v3 数据迁移入口及模型
│     │     ├─ notification/                  OperationResult 通知与 MiniMessage 语言文件
│     │     ├─ permission/                    LuckPerms worldbase 投影
│     │     ├─ player/                        Paper 玩家适配
│     │     ├─ storage/                       内部仓储接口、JSON/MySQL 实现
│     │     └─ world/                         世界目录查询及版本检查
│     └─ resources/                           config.yml、paper-plugin.yml、langs/*.yml
├─ AGENTS.md                                 任务入口和强制项目边界
└─ docs/                                     项目说明、索引与专题文档
   ├─ INDEX.md                               按任务类型选择必读文档
   ├─ CLASS_STATUS.md                        类职责与完成状态
   ├─ ARCHITECTURE.md                        架构设计与边界
   ├─ API_INVENTORY.md                       v3 功能分类参考
   ├─ API_GUIDE.md                            外部 Paper 插件 API 调用指南
   ├─ MIGRATION.md                           v3 数据迁移
   ├─ STORAGE.md                             JSON/MySQL 存储
   ├─ MESSAGES.md                            MiniMessage 与语言资源
   ├─ PLUGIN.md                              插件配置和命令用法
   └─ README.md                              英文用户说明
```

`api` 不引用 Bukkit、LuckPerms、Multiverse 或存储实现。`plugin.impl` 实现公开服务，`plugin.internal` 承载仅插件可见的适配器与仓储接口。当前目录已按这些职责分开，无需为角色命令另建一套存储层。

## 启动与服务依赖

| 入口或方法 | 直接依赖 | 结果或副作用 |
|---|---|---|
| `MapManagerPlugin.onEnable` | `StorageConfig` → `JsonStorage`/`MySqlStorage`；`V3Migration`；`Messages`；LuckPerms → `WorldBaseProjectionImpl`；`PlayerGatewayImpl`；`MapManagerImpl`；`access-control.strict-map-entry` | 装配服务并注册地图设置/准入监听；存储迁移和 `worldbase` 初始化均成功后，在主线程注册 `MapManager` 服务、复核在线玩家并注册命令 |
| `Messages` | `config.yml` 的 `language`、插件数据目录 `langs/<语言代码>.yml`、内置中文资源 | 生成 MiniMessage Component；缺项回退中文，日志与异常使用纯文本 |
| `V3Migration.migrate` | `V3MigrationSourceImpl`、LP 默认组只读节点、MV 别名/出生点只读元数据、`PlayerGateway.resolveId`、`MapRepository`、`RoleRepository` | 校验并备份 v3 JSON；UUID 全部解析成功后写入 MMC 存储，成功写完成标记；不写 LP 或 MV |
| `MapWorldCommand.tabComplete` | 执行方与 MMC 准入；`MapAccessService.accessibleMaps`；`MapService`；`MapRoleService`；在线玩家与服务端世界目录 | 补全 `/world` 和 `/wtp`，过滤无权使用的子命令及不可进入的传送目标；`/wtp` 包含公开地图 |
| `MapManagerImpl` | `MapRepository`、`RoleRepository`、`OperationRepository`、`WorldBaseProjection`、`PlayerGateway` | 组装地图、角色、准入、别名、设置、传送和生命周期服务 |
| `WorldVersionServiceImpl` / `LevelDatVersionReader` | Bukkit dataVersion、Querz NBT | 读取 `level.dat` 的 `Data.DataVersion`，判断地图版本是否不高于当前服务器；导入前执行校验 |
| `MapAccessServiceImpl.decide` | `MapRepository.findById` → `MapRoleService.hasRole` | 按 MMC 地图状态、角色和公开状态决定准入；不读取 LP |
| `MapAccessListener` | `PlayerTeleportEvent`、`PlayerChangedWorldEvent`、`PlayerJoinEvent`、`PlayerRespawnEvent`；`MapService`；`MapAccessService`；MV 默认世界 | 严格校验开启时预先拦截并在事件后复核地图进入；加入和重生时检查；撤权后复核当前地图；无法安全送回默认世界时断开连接 |
| `MapWorldCommand.public/setname/setspawn` | 当前 MMC 地图、`MapAccessService`、`MapSettingsService` | 只允许当前地图管理员修改公开状态、显示名和出生点；出生点取玩家当前位置并同步 MMC、Bukkit 与 Multiverse |
| `MapSettingsServiceImpl.setSpawn` | `MapRepository`、Bukkit `World`、Multiverse `MultiverseWorld` | 先持久化 MMC 出生点，再更新 Bukkit 和 Multiverse 出生点并保存 Multiverse 配置；外部同步失败返回 `Partial` |
| `WorldLifeCycleServiceImpl.create/importWorld` | 内置与配置的地图名限制、存储、Multiverse、`WorldBaseProjection`、`MapAliasService`、`WorldVersionService` | 导入前校验 `level.dat` 版本；创建或导入地图后写入首位管理员、初始化地图并转换状态 |
| `MapWorldCommand.delete` → `WorldLifeCycleServiceImpl.delete` | 控制台一次性确认码、删除名称正则、`WorldGateway`、Bukkit、Multiverse、操作记录、`WorldBaseProjection`、地图与角色存储 | 首次输入地图名取得 10 秒有效的 6 位确认码；二次输入确认码后检查 MV 默认世界、服务器首个加载世界、在线玩家和目录；删除世界后清理 LP 投影与 MMC 数据；失败保留 `DELETING` 供重试 |
| `MapTravelServiceImpl.teleport` | `MapAccessService`、`MapRepository`、`PlayerGateway` | 准入通过后传送到已加载世界；按需加载尚未实现 |
| `MapWorldCommand.kick` → `MapTravelServiceImpl.kickFromMap` | Bukkit 在线玩家、内部地图存储、`MapAccessService.decide`、Multiverse 默认世界 | 服务按操作者当前世界定位地图，确认管理员及目标在同图，再使用 MV 默认世界出生点传送 |

## 地图人员权限调用关系

```text
/world admins|builders|visitors
  → MapWorldCommand（玩家当前世界 → MapService.findByWorldName）
  → MapRoleService.assignedMembers（只查直接授予）
  → RoleRepository.members（组 ADMIN/BUILDER；地图 VISITOR）

/world admin|builder|visitor add|remove <用户名>
  → MapWorldCommand（当前地图 MMC ADMIN 身份）
  → PlayerGateway.resolveId（在线 → 缓存 → Mojang/离线 UUID）
  → 主线程复核当前地图及 ADMIN 身份
  → MapRoleService.addMember/removeMember
  → RoleRepository.add/remove（MMC 权威数据）
  → ADMIN/BUILDER 时：WorldBaseProjection.ensureGroupInheritance
                    → WorldBaseProjection.setBuilderAccess（LP 地图组成员投影）
  → OperationNotifier.notify（Success/Failure/Partial）
  → 成功或部分完成的撤权：MapAccessListener 复核目标玩家当前地图，失去准入时移至默认世界
```

`MapRoleService.members/hasRole` 返回有效角色：管理员也具有建筑者、访客能力，建筑者也具有访客能力。`assignedMembers` 只返回直接授予，用于名单显示和准确撤销。管理员、建筑者的授予保存在组范围；访客保存在地图范围。撤销管理员或建筑者后，服务会检查另一种组角色是否仍存在，再决定是否移除玩家的 LP 地图组节点。访客不会获得 `worldbase`。重复执行角色修改会重试 LP 投影；若 MMC 写入成功而 LP 同步失败，结果为 `Partial(PERMISSION_SYNC_FAILED)`。

当前公开的 `MapRoleService.addMember/removeMember` 没有操作者参数，因此角色管理权限由 `/world` 命令入口检查；直接调用该 API 的其他插件需要自行验证操作者。存储迁移和 LP `worldbase` 初始化成功后，`MapManager` 会注册到 Bukkit ServicesManager。LP 同步目前会等待异步 API 完成，命令执行可能阻塞服务器主线程；后续若改为全异步角色操作，应同时调整 API 结果契约和通知时机。`access-control.strict-map-entry` 默认开启，关闭后传送、加入、重生和撤权后的准入复核均停用。自动重试队列仍未实现。外部插件接入方式见 [API_GUIDE.md](API_GUIDE.md)。

`MapSettingsListener` 由插件启动时注册，按 `MapRepository.findByWorldName` 读取当前地图设置，控制方块物理、落沙实体和方块/实体爆炸；`/world physics|explosion` 经 `MapSettingsService` 写入同一存储。

`MapAccessListener` 在 `HIGHEST` 阶段校验传送目标，并在 `MONITOR` 阶段排队复核实际所在世界；同时检查登录落点、重生目标和世界切换。玩家因撤销角色而失去当前地图准入时，会被送至 MV 默认世界出生点。默认世界不可用、玩家也无权进入默认地图或传送失败时会断开玩家。v3 迁移完成时，启动期间已在线玩家也会接受复核。
