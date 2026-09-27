# MapManager v4 目录与调用关系

本文记录当前源码中的模块边界和关键方法依赖。类的完成状态见 [AGENTS.md](AGENTS.md)，设计目标见 [ARCHITECTURE.md](ARCHITECTURE.md)。

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
│     │     ├─ migration/                     v3 数据迁移入口及模型
│     │     ├─ notification/                  OperationResult 通知
│     │     ├─ permission/                    LuckPerms worldbase 投影
│     │     ├─ player/                        Paper 玩家适配
│     │     ├─ storage/                       内部仓储接口、JSON/MySQL 实现
│     │     └─ world/                         世界目录查询及版本检查
│     └─ resources/                           config.yml、paper-plugin.yml
├─ AGENTS.md                                 类职责与完成状态，处理任务前先读
├─ ARCHITECTURE.md                           设计原则和待实现能力
└─ API_INVENTORY.md                          v3 功能分类参考
```

`api` 不引用 Bukkit、LuckPerms、Multiverse 或存储实现。`plugin.impl` 实现公开服务，`plugin.internal` 承载仅插件可见的适配器与仓储接口。当前目录已按这些职责分开，无需为角色命令另建一套存储层。

## 启动与服务依赖

| 入口或方法 | 直接依赖 | 结果或副作用 |
|---|---|---|
| `MapManagerPlugin.onEnable` | `StorageConfig` → `JsonStorage`/`MySqlStorage`；LuckPerms → `WorldBaseProjectionImpl`；`PlayerGatewayImpl`；`MapManagerImpl` | 装配服务、注册命令、异步确保 `worldbase` 存在 |
| `MapWorldCommand.tabComplete` | 子命令权限；`MapAccessService.accessibleMaps`；`MapService`；`MapRoleService`；在线玩家与服务端世界目录 | 补全 `/world` 和 `/wtp`，过滤无权使用的子命令及不可进入的传送目标 |
| `MapManagerImpl` | `MapRepository`、`RoleRepository`、`OperationRepository`、`WorldBaseProjection`、`PlayerGateway` | 组装地图、角色、准入、别名、设置、传送和生命周期服务 |
| `MapAccessServiceImpl.decide` | `MapRepository.findById` → `MapRoleService.hasRole` | 按 MMC 地图状态、角色和公开状态决定准入；不读取 LP |
| `WorldLifeCycleServiceImpl.create/importWorld` | 存储、Multiverse、`WorldBaseProjection`、`MapAliasService` | 创建或导入地图、写入首位管理员、初始化地图并转换状态 |
| `MapTravelServiceImpl.teleport` | `MapAccessService`、`WorldLifeCycleService.load`、`PlayerGateway` | 准入通过后加载并传送 |

## 地图人员权限调用关系

```text
/world admins|builders|visitors
  → MapWorldCommand（玩家当前世界 → MapService.findByWorldName）
  → MapRoleService.assignedMembers（只查直接授予）
  → RoleRepository.members（组 ADMIN/BUILDER；地图 VISITOR）

/world admin|builder|visitor add|remove <用户名>
  → MapWorldCommand（mapmanager.world + 当前地图 ADMIN 身份）
  → PlayerGateway.resolveId（在线 → 缓存 → Mojang/离线 UUID）
  → 主线程复核当前地图及 ADMIN 身份
  → MapRoleService.addMember/removeMember
  → RoleRepository.add/remove（MMC 权威数据）
  → ADMIN/BUILDER 时：WorldBaseProjection.ensureGroupInheritance
                    → WorldBaseProjection.setBuilderAccess（LP 地图组成员投影）
  → OperationNotifier.notify（Success/Failure/Partial）
```

`MapRoleService.members/hasRole` 返回有效角色：管理员也具有建筑者、访客能力，建筑者也具有访客能力。`assignedMembers` 只返回直接授予，用于名单显示和准确撤销。管理员、建筑者的授予保存在组范围；访客保存在地图范围。撤销管理员或建筑者后，服务会检查另一种组角色是否仍存在，再决定是否移除玩家的 LP 地图组节点。访客不会获得 `worldbase`。重复执行角色修改会重试 LP 投影；若 MMC 写入成功而 LP 同步失败，结果为 `Partial(PERMISSION_SYNC_FAILED)`。

当前公开的 `MapRoleService.addMember/removeMember` 没有操作者参数，因此角色管理权限由 `/world` 命令入口检查；直接调用该 API 的其他插件需要自行验证操作者。LP 同步目前会等待异步 API 完成，命令执行可能阻塞服务器主线程；后续若改为全异步角色操作，应同时调整 API 结果契约和通知时机。全路径准入监听与自动重试队列仍未实现。
