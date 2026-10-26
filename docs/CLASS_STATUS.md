# MapManager v4 类清单与待办

> 开始任务先读根目录 `AGENTS.md` 和 `docs/INDEX.md`；新增、删除或调整类职责与状态后同步更新本文件。以下状态以 **2026-10-01** 的源码为准。v3 仓库仅作功能和迁移格式参考。

## 边界与状态说明

- `api` 放对外模型及接口，`plugin` 放实现。`PlayerGateway`、`WorldGateway` 接口在 `api.service`；存储接口 `MapRepository`、`RoleRepository`、`OperationRepository` 仍只在 `plugin` 内部。
- MMC 是地图准入和角色的权威来源；LuckPerms 只投影 `worldbase` 建造权限；Multiverse-Core 负责世界操作。`WorldGateway` **只查存储目录**，创建、导入、删除等由 `WorldLifeCycleService` 定义。
- 状态：**契约/数据**＝类型已定义；**已实现**＝列出的功能有实际代码；**部分实现**＝有真实逻辑但仍缺关键环节；**占位**＝方法仍返回 `null`/`false`/空值或空方法体。
- 当前插件通过显式后端迁移装配 CachedStorage 索引，恢复完整生命周期请求并重放权限队列；异步确保 LP `worldbase` 组存在；Paper 加载顺序使 MapManager-Core 先于 Multiverse-Core 启用，以便 Multiverse 加载地图时可解析内置生成器；地图设置和准入监听在 `onEnable` 装配服务时注册，v3 JSON 迁移等待 Multiverse-Core 启用后执行。迁移与 `worldbase` 初始化均成功后，插件在主线程复核在线玩家、注册地图命令，并通过 Bukkit ServicesManager 发布 `MapManager`。`api` 与 `plugin` 单元测试由 Maven Surefire 在 `test` 阶段执行。当前目录和方法依赖见 [STRUCTURE.md](STRUCTURE.md)。

默认世界例外：`MapAccessServiceImpl` 和 `MapAccessListener` 放行默认世界；`MapRoleServiceImpl` 在该世界不返回有效角色并拒绝角色修改；设置/别名/生命周期服务拒绝托管修改，`MapSettingsListener` 对默认世界跳过单地图方块物理/爆炸设置，全局实体支撑保护仍生效。`WorldBaseProjectionImpl` 清除并过滤其 `worldbase` 世界上下文继承，同组其他地图保持正常。启动时由 `MapManagerPlugin` 在 MV 启用后确定默认世界名，再发布服务。

## api 模块：入口、模型与结果

源码根目录：`api/src/main/java/studio/lorian/mapmanager/api/`。

| 类型               | 作用                                                         | 状态与待办                                                         |
|--------------------|--------------------------------------------------------------|--------------------------------------------------------------------|
| `MapManager`       | 地图、角色、准入、设置、别名、传送、生命周期及目录服务的统一入口   | 注册逻辑已实现；存储迁移与 LP `worldbase` 初始化成功后由插件以 `Normal` 优先级发布，禁用时撤销 |
| `CreateMapRequest` | 创建地图所需名称、组、可选首位管理员、生成器类型、维度环境和生成参数 | 数据；参数在创建用例中校验                                      |
| `ImportMapRequest` | 导入服务端根目录现有世界的名称、生成器类型、维度环境和所有者 | 数据；不包含生成参数；流程校验目录和 `level.dat`                 |
| `MapGroup`         | 组 ID 与组内世界名的不可变快照                               | 数据；世界名由地图的 `groupId` 关联计算                            |
| `MapInstance`      | 地图 UUID、世界名、组、展示名、设置、持久状态及出生点快照    | 数据；创建/导入以 PENDING 保存，完成后 ACTIVE；失败保留记录并按持久请求恢复；删除成功移除记录 |
| `MapRole`          | `ADMIN`、`BUILDER`、`VISITOR` 三种角色                       | 数据；组角色已接 LP 投影，MMC 仍为权威来源                         |
| `MapSettings`      | 单地图物理、爆炸、公开状态                                   | 数据；不包含 PVP 或全局覆盖                                        |
| `MapStatus`        | `PENDING`、`ACTIVE`、`ARCHIVED`、`DELETING`、`DELETED`       | 数据；PENDING、ACTIVE、DELETING 用于生命周期；ARCHIVED/DELETED 仅为预留枚举值 |
| `SpawnPoint`       | 不依赖 Bukkit 的世界名、坐标与朝向                           | 数据；生命周期与设置服务都会将出生点写入 MMC，并同步 Bukkit/Multiverse |
| `WorldType`        | `FLAT`、原版 `NORMAL`、`VOID` 地形生成器类型                 | 数据；与维度环境独立组合；VOID 依赖 VoidGen                     |
| `Environment`      | `NORMAL`、`NETHER`、`THE_END`、预留的 `SIFT` 维度环境        | 数据；NORMAL/NETHER/THE_END 已实现；SIFT 返回不支持结果          |
| `FailureReason`    | 统一失败原因代码                                             | 数据；随业务实现补充必要原因，避免在命令中散写字符串               |
| `OperationName`    | 统一操作名称代码                                             | 数据；随业务操作使用                                               |
| `OperationResult`  | `Success`、`Failure`、`Partial` 及 operation ID              | 契约/数据；Failure/Partial 可带 detail，旧构造器兼容；通知器优先展示具体失败详情                  |

## api 模块：服务接口

| 类型                    | 作用                                                                   | 状态与待办                                                                                                                           |
|-------------------------|------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `AccessDecision`        | 准入结果、有效角色及拒绝原因                                           | 数据；规则由 `MapAccessServiceImpl` 计算                                                                                             |
| `MapAccessService`      | 判断能否入图，列出可访问及可管理地图                                   | 契约；`MapAccessListener` 用其执行传送、登录、重生、换世界和撤权后的准入检查                                                         |
| `MapRoleService`        | 查询有效角色与直接授予成员、授予和撤销地图角色                         | 契约；MMC 角色同步持久化；LP 投影异步入持久队列，返回 Partial(PERMISSION_SYNC_PENDING)；外部调用方自行授权 |
| `MapService`            | 地图和地图组快照查询                                                   | 契约；已有存储实现                                                                                                                   |
| `MapSettingsService`    | 修改单地图公开、展示名、物理、爆炸和出生点                             | 契约；出生点会写入 MMC 并同步 Bukkit/Multiverse；公开状态和展示名同步 MV 别名，玩家命令由地图管理员授权                   |
| `MapAliasService`       | 两个重载按地图公开状态同步 MV 别名：不传别名用地图名，传别名用指定文字 | 契约；公开图使用 `&2`，非公开图使用 `&3`                                                                                             |
| `MapTravelService`      | 入图传送和踢出地图                                                     | 契约；按 MMC 准入检查后按需加载 ACTIVE 地图再传送；踢出验证同图与管理员，目的地为 MV 默认出生点 |
| `PlayerGateway`         | 异步解析用户名到 UUID；在线状态、当前世界、传送与断线操作              | 契约；身份解析、在线查询、主线程传送和断线已实现；断线原因按纯文本处理 |
| `WorldGateway`          | 根据 `level-name` 和维度目录定位地图父目录及地图目录                   | 契约；通过 `MapManager.worldDirectories()` 公开只读实际目录查询；已加载世界优先实际存档路径，未加载沿用 MMC 目录规则                                                                    |
| `WorldLifeCycleService` | 创建、导入、初始化、删除地图                                           | 契约；创建、导入、初始化、删除、load 和 recover 已实现；世界操作只在此服务编排 |
| `WorldVersionService`   | 查询地图 level.dat 数据版本并判断服务器兼容性                         | 契约；由 `MapManager.worldVersions()` 暴露，导入流程使用该服务校验         |

## plugin 模块：入口与业务实现

源码根目录：`plugin/src/main/java/studio/lorian/mapmanager/plugin/`。

| 类型                        | 作用                                                                                                                                                   | 状态与待办                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MapManagerPlugin`          | Paper 主类；补齐配置、加载语言文件与存储，装配服务、命令和监听器，并取得 LuckPerms API 异步初始化 `worldbase` | 已实现；启动时按 v4 资源补齐缺失配置节点并保留原值；先注册监听，等待 MV、迁移和 worldbase 初始化；恢复生命周期、重放 LP 队列并按 MMC 复核历史投影成员，随后发布 API 和注册命令；失败禁用 |
| `MapWorldCommand` | /world 与 /wtp 玩家命令 | 已实现；设置成功反馈参数，开启物理/爆炸有风险说明及 10 秒二次确认；准入、设置、角色、传送和踢出；角色名单使用 admin/builder/visitor info；控制台生命周期操作已迁至 MapAdminCommand |
| `MapSettingsListener` | 按地图设置控制方块物理、落沙和爆炸；全局保护装饰实体支撑 | 已实现；方块物理/爆炸仅作用于已登记非默认世界；画、展示框及 26.3 坐垫的支撑丢失/挤占破坏在所有世界禁止 |
| `MapAccessListener` | 拦截和复核地图进入、加入、重生及权限撤销后的当前地图 | 已实现；`access-control.strict-map-entry` 默认开启，禁用时整套检查均停用；无权进入时返回 MV 默认世界出生点，无法安全返回时断开连接 |
| `MapManagerImpl`            | 组装并返回各业务服务                                                                                                                                   | 已实现；装配地图、角色、准入、设置、别名、旅行、版本与生命周期服务；使用 CachedStorage 和持久化异步 LP 投影 |
| `MapServiceImpl`            | 从 repository 查询地图及组                                                                                                                             | 已实现；通过主类注册的 `MapManager` 服务向外提供                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `MapRoleServiceImpl`        | 组管理员/建筑者和单图访客的直接与有效角色查询、持久化、LP 投影                                                                                         | 已实现；角色范围及继承由 MMC 决定；高频判断读取内存索引；LP 入队不等待网络，成功持久化后立即生效，投影返回待同步结果 |
| `MapAccessServiceImpl`      | 依据 `ACTIVE` 状态、MMC 角色和 `isPublic` 计算准入                                                                                                     | 已实现；由监听器和传送服务统一允许有效角色或公开地图，严格校验开启时也检查传送、登录、重生、实际换世界及撤权后的有效权限                                                                                                                                                                                                                                                                                                                                                                                                 |
| `MapSettingsServiceImpl`    | 将单地图设置及出生点写入 repository；公开状态切换时读取 MV 原别名、去除前导颜色代码后换色；展示名修改委托别名服务                                      | 已实现；出生点写入 MMC 并同步 Bukkit/Multiverse，公开状态和显示名同步 MV 别名；地图管理员授权由命令入口执行                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `MapAliasServiceImpl`       | 无别名参数时使用地图名；显式别名时清除颜色代码并保存纯文本，再根据 MMC `isPublic` 设置、保存 MV 别名                                                   | 已实现；存储别名不含颜色代码，MV 非公开为 `&3<name>`、公开为 `&2<name>`，需在主线程调用                                                                                                                                                                                                                                                                                                                                                                                                            |
| `MapTravelServiceImpl`      | 检查入图准入并传送玩家；将玩家移出地图                                                                                                       | 已实现；主线程检查在线状态和 MMC 准入，通过生命周期 load 加载目标再传送；踢出按当前地图管理员与同图检查 |
| `WorldLifeCycleServiceImpl` | 创建、导入、初始化和删除的用例入口 | 已实现；保留名称、参数、版本、默认世界、在线玩家和目录边界校验；创建和导入在世界加载前默认关闭物理和爆炸，显式初始化也持久化关闭两项并保留公开状态；完整请求先落 RecoveryJournal，世界初始化后保存 level.dat，再登记 ACTIVE；LP 持久异步投影；启动及显式 recover 继续创建/导入/删除；删除目录身份不符时停止，保留恢复记录；删除预检区分 INVALID_MAP_STATE、WORLD_HAS_PLAYERS 与 RECOVERY_CONFLICT |
| `FlatChunkGenerator` | 按绝对表层 Y 或旧编码层设置生成平坦区块 | 已实现；flat.height 按世界实际范围校验并定位表层，出生点在上方一格；兼容旧 layers ID；在目标 WorldInfo 生物群系准备阶段校验，最终创建/恢复再复核；坐标、扩展高度、详情回传代理单测通过，真实数据包环境待验收 |

| `MapAdminCommand` | 控制台与 RCON 生命周期及维护命令 | 已实现；create/import/init/delete、load/recover、operations、migration、storage export；包含命令专用删除名称正则、六位一次性删除确认和创建/导入参数补全 |

## plugin 模块：迁移、通知、权限和玩家

| 类型                      | 作用                                                                                                                                                              | 状态与待办                                                                                          |
|---------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `LegacyRoleGrant`         | v3 成员授予的中间数据，保留世界、角色、玩家名与 UUID                                                                                                              | 数据；读取旧成员名，迁移时解析 UUID                                                                      |
| `LegacySnapshot`          | v3 地图、组、角色及告警的只读快照                                                                                                                                     | 数据；由源文件解析器填充，供启动迁移使用                                                                |
| `V3MigrationSource`       | 读取旧格式的内部端口                                                                                                                                              | 契约；源文件只读解析已实现                                                                          |
| `V3MigrationSourceImpl`   | v3 源数据读取实现位置                                                                                                                                             | 已实现；解析并交叉校验 `worlds.json`、`groups.json`，对无效数据拒绝导入                              |
| `V3Migration` | 读取 v3 文件及 LP 公开节点、解析 UUID、备份源文件，并幂等写入 v4 仓储 | 已实现；dry-run 生成前后差异报告，正式迁移备份源和目标、冻结含 UUID 的计划，按幂等步骤继续部分写入；成功后清除 v3 `global` 配置；源变更或目标冲突时停止 |
| `NotificationTarget`      | 通知目标：玩家、控制台或仅日志                                                                                                                                    | 数据；已由通知实现按目标分发                                                                          |
| `OperationNotifier`       | 统一分发操作结果、Component 及语言键提示的内部端口                                                                                                                          | 契约；由命令调用其实现                                                                              |
| `OperationNotifierImpl`   | 将成功、失败、部分完成转换为消息，并发送给在线玩家或控制台；普通提示走同一分发方法                                                                                | 已实现；按 Success/Failure/Partial 分发玩家、控制台或日志通知，玩家结果隐藏操作 ID，完整结果发到控制台；MiniMessage 与中英文语言资源覆盖 |
| `Messages` | 加载 `langs/zh_CN.yml` 与所选语言，缺项回退中文；以 MiniMessage 生成 Component，日志使用纯文本 | 已实现；内置 zh_CN、en_US，语言文件可扩展 |
| `WorldBaseProjection`     | LP `worldbase` 创建及地图组继承、成员、清理的内部端口                                                                                                             | 契约；不能作为 MMC 准入判断来源                                                                     |
| `WorldBaseProjectionImpl` | 缺失时创建 `worldbase` 并继承 `default`；维护 LP 地图组世界上下文继承和成员投影；删除地图时清理对应继承或最后一个地图组 | 已实现；由后台队列调用 LP 写入，已有 worldbase 不修改；初始化失败后允许再次尝试；清理接口可重复执行 |
| `PlayerGatewayImpl`       | Paper 在线/缓存 UUID 查询、online-mode 判定、异步 Mojang 查询、离线 UUID 计算及玩家操作                                                                           | 已实现；主线程在线查询、传送与断线；UUID 网络请求异步；disconnect 拒绝异步调用、空输入或离线玩家 |

| `QueuedWorldBaseProjection` | 持久 FIFO 投影队列与历史成员账本 | 已实现；后台执行、指数退避重试、重启重放，历史成员用于补偿撤权；关闭保留未完成任务 |

## plugin 模块：存储与世界目录

| 类型                        | 作用                                                          | 状态与待办                                                                 |
|-----------------------------|---------------------------------------------------------------|----------------------------------------------------------------------------|
| `MapRepository`             | 地图和组的内部持久化接口，支持删除空组                        | 契约；不得移入公开 API                                                     |
| `RoleRepository`            | 组角色和地图访客的内部持久化接口                              | 契约；不得移入公开 API                                                     |
| `OperationRepository`       | 生命周期操作记录的内部持久化接口                              | 契约；完整记录查询、未结束阶段查询及持久化；恢复流程读取阶段并完成或记录失败 |
| `OperationRecord`           | 操作 ID、名称、世界、阶段和失败原因                           | 数据；创建、导入、初始化、加载和删除阶段持久化；恢复关联原操作 ID |
| `OperationStage`            | 开始、世界已变更、数据已保存、完成、失败                      | 数据；STARTED/WORLD_CHANGED/DATA_SAVED/COMPLETED/FAILED；结合完整请求恢复中断 |
| `StorageConfig`             | 解析并校验 JSON/MySQL 配置                                    | 已实现；对应资源为 `plugin/src/main/resources/config.yml`                  |
| `StorageException`          | 存储异常的统一运行时类型                                      | 已实现                                                                     |
| `StorageValidation`         | 校验地图、组 ID、出生点及世界名规范化键                       | 已实现；为 plugin 内部工具类                                               |
| `JsonStorage`               | 单文件保存地图、组、授权与操作；临时文件原子替换              | 已实现；格式版本 1，原子替换及重启读取；恢复和迁移均有故障注入自动测试 |
| `MySqlStorage`              | JDBC 保存地图、组、角色与操作；建表、唯一索引、外键           | 已实现；MySQL 8.4.11 建表、约束、双向迁移及中断恢复已验证；建表升级和生产连接性能未验证                        |
| `WorldGatewayImpl`          | 读取 `server.properties` 的 `level-name` 并按目录结构定位地图 | 已实现；缺失/无效/读取失败时取 `Bukkit.getWorlds().get(0)`；不负责创建世界 |
| `WorldVersionServiceImpl`   | 公开地图版本服务的 Bukkit 实现                                | 已实现；读取服务器 dataVersion 并调用 NBT 工具检查地图兼容性               |
| `LevelDatVersionReader`     | 读取 level.dat 中的地图数据版本                              | 已实现；从 `Data.DataVersion` 读取 int，格式无效或字段缺失时返回空值       |
| `CachedStorage` | 写入成功后发布地图、组和角色不可变索引 | 已实现；事件读无 JDBC/文件 I/O、无写锁等待；不直接修改底层仓储 |
| `StorageSnapshot` | 完整备份、预检和幂等写入 | 已实现；包含空组、地图、角色和全部操作；目标冲突时不开始写入 |
| `StorageBackendMigration` | 显式后端/目标切换 | 已实现；校验源指纹，备份、恢复部分写入，核对完整目标后更新后端标记；真实 MySQL 连接验证见测试文档 |
| `RecoveryJournal` | 生命周期完整请求及目录身份 | 已实现；原子落盘，保留 owner、生成参数和地图身份；拒绝损坏请求及不匹配目录 |

## 嵌套类型

| 类型                                            | 作用                                 | 状态与待办                                     |
|-------------------------------------------------|--------------------------------------|------------------------------------------------|
| `OperationResult.Success`、`Failure`、`Partial` | 成功、失败和外部同步待完成的结果记录 | 数据；由各业务用例按真实执行结果返回；Failure/Partial 支持可空 detail           |
| `NotificationTarget.Kind`                       | 玩家、控制台、仅日志三种通知目标     | 数据；通知分发已实现                           |
| `StorageConfig.Type`                            | JSON/MySQL 后端选择值                | 数据；由配置解析使用                           |
| `JsonStorage.Grant`                             | JSON 文件中的角色授予项              | 内部数据；随角色记录读写                       |
| `RecoveryJournal.Plan`、`StorageSnapshot.Grant` | 完整恢复请求与迁移授权条目 | 数据；仅 plugin 内部持久化，不属于公开 API |
| `QueuedWorldBaseProjection.Job`、`CachedStorage.ReadState` | 队列任务与不可变查询索引 | 内部数据；执行和发布由各自服务控制 |
| `JsonStorage.Document`                          | 带版本号的 JSON 文件整体结构         | 内部数据；当前支持格式版本 1，未知或不完整版本明确拒绝；跨后端迁移使用 StorageSnapshot |
| `JsonStorage.State`                             | JSON 后端的内存状态及写入前副本      | 内部实现；用于原子写入                         |
| `MySqlStorage.SqlTask`                          | JDBC 连接上的数据库操作函数          | 内部实现；由 `inConnection` 统一执行和转换异常 |

## 本轮待办处理情况

| 原待办 | 实现结果 | 验证与边界 |
|---|---|---|
| 创建/导入失败与删除中断恢复 | 完整请求与地图身份落盘；启动自动恢复及 `/mapadmin recover`；删除检查目录 fileKey/uid.dat | 故障注入单测、隔离 Paper 重启验证；旧版本无完整请求的 PENDING 记录需人工核查，不能猜测所有者或生成器 |
| 高频准入读取 | `CachedStorage` 不可变地图/组/角色索引，读取不访问数据库或文件，也不等待写锁 | 单测禁止底层读取，检查失败写入不发布新状态；真实玩家及生产负载验证需独立环境 |
| LP 非阻塞、重试、断线和维护命令 | LP 持久 FIFO、后台执行、退避重试与历史成员补偿；disconnect 与 `/mapadmin` 已实现 | 单测覆盖失败重试、重启与撤权顺序；全局 FIFO 的失败任务会延后其他组 |
| v3 预检/报告/安全恢复与存储切换 | dry-run、源/目标备份、冻结迁移计划及幂等恢复；显式 JSON/MySQL 后端/目标迁移 | 单测覆盖冲突、源改变、中断；真实 MySQL 8.4.11 双向迁移、目标变更、冲突和中断验证通过 |
| 打包、依赖与外部 API 验证 | Maven 测试门禁及可重复的真实服务器外部插件验收脚本 | 运行方式、实际结果及未覆盖项见 [TEST_CASES.md](TEST_CASES.md) |

SIFT 仍为明确未支持的预留环境；延迟卸载、PVP 和 v3 全局覆盖不在上述待办的实现范围内。仓储写入仍同步持久化；本轮消除的是事件路径中的读取 I/O 和主线程 LP 网络等待。
