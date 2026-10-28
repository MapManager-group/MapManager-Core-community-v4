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
│  └─ src/test/java/                          API 模型契约单元测试
├─ plugin/                                   Paper 插件 Maven 模块，依赖 api
│  └─ src/main/
│     ├─ java/studio/lorian/mapmanager/plugin/
│     │  ├─ MapManagerPlugin.java             启动及对象装配
│     │  ├─ impl/                             API 服务实现与生命周期编排
│     │  └─ internal/
│     │     ├─ command/                       /world、/wtp 玩家命令与 /mapadmin 控制台命令
│     │     ├─ listener/                      地图设置与准入 Bukkit 事件适配
│     │     ├─ migration/                     v3 数据迁移入口及模型
│     │     ├─ notification/                  OperationResult 通知与 MiniMessage 语言文件
│     │     ├─ permission/                    LuckPerms worldbase 投影
│     │     ├─ player/                        Paper 玩家适配
│     │     ├─ storage/                       内部仓储接口、JSON/MySQL 实现
│     │     └─ world/                         世界目录查询（`worldDirectories()` 公开实际存档路径）、版本检查及平坦区块与绝对表层 Y 生成器
│     └─ resources/                           config.yml、paper-plugin.yml、langs/*.yml
│  ├─ src/test/integration/                    真实 Paper 外部插件验收脚本
│  └─ src/test/java/                           API 契约、生命周期选项、服务规则、存储和适配器单元测试
├─ AGENTS.md                                 任务入口和强制项目边界
└─ docs/                                     项目说明、索引与专题文档
   ├─ INDEX.md                               按任务类型选择必读文档
   ├─ CLASS_STATUS.md                        类职责与完成状态
   ├─ ARCHITECTURE.md                        架构设计与边界
   ├─ API_INVENTORY.md                       v3 功能分类参考
   ├─ API_GUIDE.md                            外部 Paper 插件 API 调用指南
   ├─ V3_TO_V4_API_MIGRATION.md               v3 外部插件迁移指南
   ├─ MIGRATION.md                           v3 数据迁移
   ├─ STORAGE.md                             JSON/MySQL 存储
   ├─ MESSAGES.md                            MiniMessage 与语言资源
   ├─ PLUGIN.md                              插件配置和命令用法
   ├─ PROJECT_NOTES.md                       项目约定和阶段记录
   ├─ CODE_REVIEW_2026-10-28.md               4.0.2 全代码审查与安全风险
   ├─ TEST_CASES.md                          自动化测试与集成验收用例
   └─ README.md                              中文用户说明
```

`api` 不引用 Bukkit、LuckPerms、Multiverse 或存储实现。`plugin.impl` 实现公开服务，`plugin.internal` 承载仅插件可见的适配器与仓储接口。当前目录已按这些职责分开，无需为角色命令另建一套存储层。

## 自动测试与打包

`api/src/test` 和 `plugin/src/test` 使用 JUnit Jupiter；父 POM 配置 Maven Surefire，在 Maven `test` 阶段运行。`mvn package` 会先执行测试阶段，测试失败时 Maven 生命周期停止，不生成该构建的最终插件包。当前自动用例覆盖 API 模型、JSON 仓储和存储配置、地图服务规则、创建/导入生成器与环境选项、世界目录、NBT 版本读取、别名清理、v3 源文件解析和语言回退；需真实 Paper 服务生命周期、Multiverse-Core、LuckPerms、MySQL 或 Mojang 的场景保留为集成验收。用例映射见 [TEST_CASES.md](TEST_CASES.md)。

## 启动与服务依赖

| 入口或方法 | 直接依赖 | 结果或副作用 |
|---|---|---|
| `MapManagerPlugin.onEnable` | `StorageConfig` → `JsonStorage`/`MySqlStorage`；`V3Migration`；`Messages`；LuckPerms → `WorldBaseProjectionImpl`；`PlayerGatewayImpl`；`MapManagerImpl`；`access-control.strict-map-entry` | MapManager-Core 先于 Multiverse-Core 启用以注册可持久化的自定义生成器；v3 迁移等待 Multiverse-Core 启用后执行；存储迁移和 `worldbase` 初始化均成功后，在主线程注册 `MapManager` 服务、复核在线玩家并注册命令 |
| `Messages` | `config.yml` 的 `language`、插件数据目录 `langs/<语言代码>.yml`、内置中文资源 | 生成 MiniMessage Component；缺项回退中文，日志与异常使用纯文本 |
| `V3Migration.migrate` | `V3MigrationSourceImpl`、LP 默认组只读节点、MV 别名/出生点只读元数据、`PlayerGateway.resolveId`、`MapRepository`、`RoleRepository`、`config.yml` | 校验并备份 v3 JSON；UUID 全部解析成功后写入 MMC 存储，成功写完成标记并清除旧 `global` 配置；不写 LP 或 MV |
| `MapWorldCommand.tabComplete` | 执行方与 MMC 准入；`MapAccessService.accessibleMaps`；`MapService`；`MapRoleService`；在线玩家 | 补全 `/world` 和 `/wtp`，过滤无权使用的子命令及不可进入的传送目标；`/wtp` 包含公开与未加载地图；提供逐项 help |
| `MapAdminCommand.tabComplete` | CachedStorage、控制台或 RCON 身份、在线玩家和服务端世界目录 | 补全 create/import 的生成器、环境与选项；导入不补全生成参数；空组、生物群系及恢复请求按上下文补全；提供逐项 help；删除仅补全符合正则的 ACTIVE/DELETING 地图 |
| `MapManagerImpl` | `MapRepository`、`RoleRepository`、`OperationRepository`、`WorldBaseProjection`、`PlayerGateway` | 组装地图、角色、准入、别名、设置、传送和生命周期服务 |
| `WorldVersionServiceImpl` / `LevelDatVersionReader` | Bukkit dataVersion、Querz NBT | 读取 `level.dat` 的 `Data.DataVersion`，判断地图版本是否不高于当前服务器；导入前执行校验 |
| `MapAccessServiceImpl.decide` | `MapRepository.findById` → `MapRoleService.hasRole` | 按 MMC 地图状态、角色和公开状态决定准入；不读取 LP |
| `MapAccessListener` | `PlayerTeleportEvent`、`PlayerChangedWorldEvent`、`PlayerJoinEvent`、`PlayerRespawnEvent`；`MapService`；`MapAccessService`；MV 默认世界 | 严格校验开启时预先拦截并在事件后复核地图进入；加入和重生时检查；撤权后复核当前地图；无法安全送回默认世界时断开连接 |
| `MapWorldCommand.public/setname/setspawn` | 当前 MMC 地图、`MapAccessService`、`MapSettingsService` | 只允许当前地图管理员修改公开状态、显示名和出生点；出生点取玩家当前位置并同步 MMC、Bukkit 与 Multiverse |
| `MapSettingsServiceImpl.setSpawn` | `MapRepository`、Bukkit `World`、Multiverse `MultiverseWorld` | 先持久化 MMC 出生点，再更新 Bukkit 和 Multiverse 出生点并保存 Multiverse 配置；外部同步失败返回 `Partial` |
| `WorldLifeCycleServiceImpl.create/importWorld` | 内置与配置的地图名限制、存储、Multiverse、`FlatChunkGenerator`、`WorldBaseProjection`、`MapAliasService`、`WorldVersionService` | 创建/导入分别接收 `WorldType` 生成器和 `Environment` 环境；导入前校验 `level.dat` 版本且不接受生成参数；FLAT 指定 flat.height 或在 NETHER/THE_END 环境时使用 FlatChunkGenerator，指定 Y 在准备生物群系提供器及激活前按世界实际范围校验，保留 Multiverse 原始错误至 OperationResult.detail；SIFT 环境保留为未支持的预留值；地图后续写入首位管理员、初始化并转换状态；失败创建确认无世界/目录后释放占位及恢复请求，启动清理旧 FAILED 占位，保留失败审计和组角色 |
| `MapAdminCommand.delete` → `WorldLifeCycleServiceImpl.delete` | 命令专用的控制台/RCON 一次性确认码与删除名称正则、`WorldGateway`、Bukkit、Multiverse、操作记录、`WorldBaseProjection`、地图与角色存储 | 首次输入地图名取得 10 秒有效的 6 位确认码；二次输入确认码后检查 MV 默认世界、服务器首个加载世界、在线玩家和目录；世界删除后先将 LP 清理任务落盘，再清理 MMC；失败保留恢复请求，重启继续并验证目录身份 |
| `MapTravelServiceImpl.teleport` | `MapAccessService`、`MapRepository`、`PlayerGateway` | 准入通过后通过生命周期 load 加载 ACTIVE 世界再传送 |
| `MapWorldCommand.kick` → `MapTravelServiceImpl.kickFromMap` | Bukkit 在线玩家、内部地图存储、`MapAccessService.decide`、Multiverse 默认世界 | 服务按操作者当前世界定位地图，确认管理员及目标在同图，再使用 MV 默认世界出生点传送 |

默认世界策略：`MapManagerPlugin` 在主线程识别默认世界，通过共享的名称判断注入准入、角色、设置、别名、旅行、生命周期、监听器与 LP 投影；后台 LP 队列仅查询缓存名称。默认世界自由进入，历史角色无效，托管修改被拒绝，单地图方块物理/爆炸事件跳过（全局实体支撑保护仍生效），历史 `worldbase` 默认世界继承在地图组同步时清除。修改 MV 默认世界后需重启 MMC。

## 地图人员权限调用关系

```text
/world admin|builder|visitor info
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

`MapRoleService.members/hasRole` 返回有效角色：管理员也具有建筑者、访客能力，建筑者也具有访客能力。`assignedMembers` 只返回直接授予，用于名单显示和准确撤销。管理员、建筑者的授予保存在组范围；访客保存在地图范围。撤销管理员或建筑者后，服务会检查另一种组角色是否仍存在，再决定是否移除玩家的 LP 地图组节点。访客不会获得 `worldbase`。LP 入队返回 `Partial(PERMISSION_SYNC_PENDING)`，自动重试和重启重放不要求重复角色命令。

当前公开的 `MapRoleService.addMember/removeMember` 没有操作者参数，因此角色管理权限由 `/world` 命令入口检查；直接调用该 API 的其他插件需要自行验证操作者。存储迁移和 LP `worldbase` 初始化成功后，`MapManager` 会注册到 Bukkit ServicesManager。LP 写入在后台 FIFO 工作线程执行，命令不等待网络；MMC 写入和本地队列落盘仍同步。`access-control.strict-map-entry` 默认开启，关闭后传送、加入、重生和撤权后的准入复核均停用。自动重试队列已实现。外部插件接入方式见 [API_GUIDE.md](API_GUIDE.md)。

`MapSettingsListener` 由插件启动时注册，按 `MapRepository.findByWorldName` 读取当前地图设置，控制方块物理、落沙实体和方块/实体爆炸；画/展示框的悬挂事件以及 26.3 坐垫的 EntityBreakEvent 则全局取消 PHYSICS/OBSTRUCTION 破坏，不查询地图仓储或物理开关，默认和未登记世界也生效；新事件按服务器能力条件注册；`/world physics|explosion` 开启时由命令层验证 10 秒内、同玩家/地图/设置的一次性确认，再经 `MapSettingsService` 写入同一存储；关闭无需确认，设置成功反馈实际参数。创建和导入在加载世界前写入物理和爆炸均关闭的 PENDING 记录，恢复沿用该快照。显式 `initialize` 在重放世界设置前持久化关闭两项，保留公开状态、角色、出生点与地图状态；结果返回更新后的地图快照。

`MapAccessListener` 在 `HIGHEST` 阶段校验传送目标，并在 `MONITOR` 阶段排队复核实际所在世界；同时检查登录落点、重生目标和世界切换。玩家因撤销角色而失去当前地图准入时，会被送至 MV 默认世界出生点。默认世界不可用或传送失败时会断开玩家；默认世界始终放行准入。v3 迁移完成时，启动期间已在线玩家也会接受复核。

## 恢复、缓存与维护调用链

- `MapManagerPlugin` → `StorageBackendMigration` → `CachedStorage` → JSON/MySQL；迁移验证及备份归档完成后自动清空来源配置并原子保存，完成标记在保存成功后移除；事件只读不可变索引，写入成功后发布索引。
- 生命周期 → `RecoveryJournal` 保存完整请求与目录身份 → MV 世界变更 → MMC 数据 → `QueuedWorldBaseProjection` → LP 后台 FIFO；启动与 recover 继续原操作。
- `MapAdminCommand` 编排 create/import/init/delete、load/recover、操作查询、v3 预检/正式迁移及快照导出。
- Paper 26 的维度目录可共用根世界 level.dat；版本读取只在已存在、非符号链接的维度目录缺少独立文件时回退。经典导入仍要求导入目录自己的 level.dat。
