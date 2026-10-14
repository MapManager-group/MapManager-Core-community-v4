# v3 接口盘点与 v4 分类

默认世界以启动时 Multiverse-Core 的默认世界为准（Multiverse 启用前暂用服务器首个已加载世界）。它始终允许玩家进入，即使存在旧 MMC 私有地图或非 ACTIVE 记录；MMC 不赋予该世界有效角色、不应用单地图方块物理/爆炸设置（坐垫、画、展示框的实体支撑保护为全局规则，默认世界也生效），也不允许修改其别名、出生点、设置或执行生命周期管理。已有地图/角色记录保留用于追溯，但在默认世界上不生效。默认世界变更后需重启 MMC 以刷新识别。

默认世界不投影 `worldbase` 建造权限：启动后的地图组同步会异步移除已有的默认世界上下文继承，后续同步也不会重建；同组其他地图仍正常投影。实际放置/破坏限制由现有保护插件和 LuckPerms 配置执行；MMC 不新增方块破坏/放置拦截，也不撤销管理员另行配置的全局或其他插件权限。

本清单依据 v3 `MapManager-API/src/main/kotlin` 中的全部接口和模型，并对照 `MapManager-Core` 的命令调用。v3 文件只用于识别能力，v4 不直接复制暴露存储状态的旧签名。v4 接口使用 Java 编写。

外部插件开发者可从 [v3→v4 API 迁移指南](V3_TO_V4_API_MIGRATION.md) 查看依赖、服务获取和调用替换步骤；v4 服务的完整调用示例见 [API_GUIDE.md](API_GUIDE.md)，公开类型的 HTML Javadoc 在 [导出包](exports/mapmanager-api-4.0.0-SNAPSHOT-javadoc.zip)。

| v3 来源 | v3 能力 | v4 归属 |
|---|---|---|
| `MapManager` | 获取 `DynamicWorld`、`MapAgent`、`MainYaml`、`VersionCheck`、LP | `api/MapManager` 暴露分类服务和 `WorldVersionService`；LP 与配置文件仍由 plugin 管理 |
| `MapAgent.newWorld/deleteWorld` | 地图记录创建与删除 | `WorldLifeCycleService` 统一编排世界和 MMC 数据 |
| `MapAgent.addPlayer/removePlayer/getPlayers/isAdmin` | admin、builder、visitor 成员管理 | `MapRoleService`；UUID 为玩家主键 |
| `MapAgent.publicizeWorld/privatizeWorld/isPublic` | 地图公开状态 | `MapSettingsService.setPublic`、`MapSettings.isPublic` |
| `MapAgent.getWorldAlias/setWorldAlias` | 地图显示名 | `MapInstance.displayName`、`MapSettingsService.setDisplayName` |
| `MapAgent.setPhysical/isPhysical/setExploded/isExploded` | 地图及全局物理、爆炸设置 | 地图级设置已迁移到 `MapSettings`，`/world physics`、`/world explosion` 和事件监听已实现；v3 全局配置命令未保留 |
| `MapAgent.getWorldGroupName/getWorldListByGroup` | 地图和地图组关系 | `MapService.findGroup/listGroupMaps`、`MapInstance.groupId` |
| `MapAgent.getAdminSet/getBuilderSet/getVisitorSet` | 成员列表 | `MapRoleService.members` 返回只读集合 |
| `MapAgent.containsWorld/getWorlds` | 托管地图查询 | `MapService.findByWorldName/listMaps` |
| `MapAgent.getNodeMap/getGroupMap` | 直接获取可变存储对象 | 不公开；用 `MapService` 的快照查询替代 |
| `MapAgent.getUniqueID/getPlayer/isPlayerRegister` | 玩家名解析、在线玩家对象和历史注册检查 | `api.service.PlayerGateway.resolveId/isOnline/currentWorld`；不暴露 Bukkit `Player`，也没有独立的注册状态查询；角色等公开服务使用 UUID |
| `MapAgent.getWorldParentFolder/getWorldFolder` | 世界目录访问 | `MapManager.worldDirectories()` / `api.service.WorldGateway`；目录查询返回实际 JDK `Path` |
| `MapAgent.save/reload` | 旧 JSON 文件保存、重载 | repository 自动持久化并发布缓存；外部文件修改需停服处理后重启，没有对应 save/reload 命令，公开 API 不操作文件 |
| `MapAgent.setLuckPerms/syncWithLuckPerms` | LP 实例注入与数据同步 | plugin 内部 `WorldBaseProjection`；仅投影 `worldbase` 能力 |
| `MapAgent.ignoreColor` | 地图显示字符串处理 | plugin 内部展示工具，不作为服务接口 |
| `DynamicWorld.createWorld/importWorld/initWorld/removeWorld` | MV 世界创建、导入、初始化和删除 | `WorldLifeCycleService` 统一编排；`WorldGateway` 仅负责目录查询 |
| `DynamicWorld.loadWorld/unloadWorldLater/cancelUnloadTask` | 按需加载和延迟卸载 | `WorldLifeCycleService.load` 已实现按需加载，传送会自动加载；延迟卸载尚未实现 |
| `DynamicWorld.hasLoaded/isExist/isExtraLoad` | 查询 MV 世界存在/加载状态及额外加载标记 | 没有等价的公开状态服务；生命周期实现仅在具体操作中查询 MV/Bukkit/目录状态，不维护额外加载或延迟卸载标记 |
| `DynamicWorld.getCorrectName/getCorrectUnloadedName/getWorlds/getPotentialWorlds` | 名称匹配及补全 | `MapService` 查询与 plugin 命令补全；不公开 MV 世界列表 |
| `DynamicWorld.getOwnerWorlds/getAccessWorlds` | 管理/可进入地图列表 | `MapAccessService.administratedMaps/accessibleMaps`；MMC 自己判定 |
| `DynamicWorld.getSpawnLocation/getDefaultSpawnLocation` | 地图及默认出生点 | `MapSettingsService.getSpawn`、plugin 安全世界配置 |
| `DynamicWorld.getMVWorldManager/getLoadedWorld/getCorrectWorld/getMVWorld` | MV 原生对象 | 留在 plugin 的 Multiverse 实现内部，不泄漏到 API |
| `DynamicWorld.hasPermission` | LP/Multiverse 访问节点查询 | 移除旧准入方式，由 `MapAccessService.decide` 替代 |
| `MainYaml`、`MainConfig` | YAML 全局设置读写 | 仅迁移地图自身设置；全局覆盖不进入 v4 API |
| `VersionCheck` | 世界文件版本检查 | `api.service.WorldVersionService` 提供服务器版本、地图版本读取和兼容性判断；NBT 解析留在 plugin |
| `WorldNode`、`WorldGroup`、`MapGroup`、`MMWorldType` | 地图数据、组、角色、世界类型 | `MapInstance`、`MapGroup`、`MapRole`、`WorldType`；不返回可变持久实体 |

## v3 功能与 v4 当前实现对照

下表按 v3 当前源码中的命令、接口和事件行为核对 v4；“已实现”表示核心行为有对应实现，不表示旧命令/API 签名兼容。

| 功能 | v3 实际行为 | v4 实际行为 | 对照结论 |
|---|---|---|---|
| 创建地图 | `/create n:... [e:... a:... c:... g:... o:...]`；未指定 owner 时用发送者；类型为 `flat/normal/void_gen/nether/the_end` | `/mapadmin create <地图名> [WorldType] [Environment] [选项...]`；WorldType 为 `FLAT/NORMAL/VOID`，Environment 为 `NORMAL/NETHER/THE_END/SIFT`（SIFT 预留且当前不支持）；二者可独立组合；另支持 `flat.height`、`seed`、`structures`、`biome`、`generator`、`generator-settings`、`property.*` | v4 将生成器和环境分开；命令 token 与颜色设置方式不兼容；不传 `admin=` 时不创建首位管理员；FLAT 层高按环境限制 |
| 导入地图 | `/import` 接受与创建相近的参数，导入前校验目录、世界名和版本；导入选项始终设置 `VoidGen:{}` | `/mapadmin import <地图名> [WorldType] [Environment] [alias=... group=... admin=...]`；校验根目录 `level.dat`、路径及版本；按生成器和环境配置 MV；不接受生成参数 | 已实现；版本校验保留，已有区块按存档数据保留，所选生成器用于后续新区块；v4 未提供 v3 的 `c:` 显式颜色参数 |
| 初始化设置 | 自动加载、和平、创造、关闭常见生物生成，设置随机刻/火焰/天气/昼夜/生物破坏/生物生成/进度播报规则 | 创建、导入、手动初始化调用同组初始化规则，并校验配置是否成功 | 核心规则基本一致；别名颜色和失败记录策略不同 |
| 删除地图 | `/delete <地图>` 后 10 秒内 `/delete confirm`；会先将在线玩家送回默认出生点，再卸载并清理 LP/MV 数据 | `/mapadmin delete <地图>` 由控制台发起，一次性六位码 10 秒有效；有在线玩家时拒绝，另校验默认世界、路径、符号链接和名称规则 | 已实现但确认交互和在线玩家策略不同；v4 增加删除保护 |
| 地图组管理员/建筑者、单图访客 | ADMIN/BUILDER 作用于地图组，VISITOR 作用于单图；名单及权限主要从 LP 节点维护 | MMC UUID 角色为权威；管理员/建筑者仍按组作用，访客按地图作用；建筑权限投影到 LP `worldbase` | 核心角色功能已实现；数据权威从 LP 切换到 MMC，直接修改 LP 不再改变准入结果 |
| `apply` 共享建筑权限组 | 新地图权限组无上下文继承 `apply`，可共享该组权限 | 地图组继承 `default` 和带世界上下文的 `worldbase`，不继承 `apply` | 未迁移；依赖旧 `apply` 权限的服务器需重新配置建筑权限 |
| 公开/私有地图 | 通过 `default` LP 组的 `multiverse.access.<world>` 表示公开状态 | 由 MMC `MapSettings.isPublic` 存储；公开地图对任何玩家开放访客准入 | 功能已实现，存储和权威不同；旧 LP 节点不是 v4 的公开状态 |
| PVP 开关 | `/world pvp` 查询/修改并保存 Multiverse PVP | 地图设置模型、设置服务、命令及迁移均没有 PVP 字段 | 未实现；迁移不会保留 v3 的自定义 PVP 值 |
| 物理/爆炸开关 | 单地图默认 `physical=true`、`exploded=false`；`/world blockupdate\|physics\|physical`、`/world explosion\|exploded` 控制开关；监听方块物理、落沙、方块爆炸和实体爆炸 | 单地图默认 `physical=true`、`explosion=false`；命令为 `/world physics`、`/world explosion`，对应监听事件已接通 | 对已登记地图的默认值和开关效果一致；旧命令别名未全部保留；未登记世界的普通方块物理/爆炸行为不同：v3 默认取消，v4 不干预；v4 装饰实体的支撑破坏固定全局禁止 |
| 全局物理/爆炸设置 | 有 `/mapadmin physics\|explosion` 和 YAML 字段；但 v3 监听实际读取地图节点，不读取全局值 | 不提供全局设置命令或字段，只提供单地图开关 | 管理入口未保留；按当前 v3 事件实现，全局开关本身没有生效的事件覆盖效果 |
| 敏感命令日志过滤 | Log4j filter 隐藏包含 `/world download`、`/world changepassword`、`/mapadmin.md changepassword` 的服务器命令日志（含 namespaced 命令） | 没有安装对应 Log4j filter；这些命令也不属于 v4 命令 | v3 的日志抑制行为未迁移；若其他插件仍提供这些命令，其服务器命令日志不再被此插件过滤 |
| 龙蛋传送保护 | 玩家右键龙蛋时取消交互，禁止触发传送 | 没有对应事件处理 | 未实现 |
| 地图别名和出生点 | `/world setname`（另有 `rename` 别名）与 `/world setspawn`；Multiverse 保存别名/出生点 | `/world setname`、`/world setspawn`；MMC、Bukkit、Multiverse 同步 | 核心功能已实现；v4 无 `rename` 子命令别名，别名颜色跟随公开状态 |
| 玩家传送与快捷命令 | `/world tp`，独立 `/worldtp`（别名 `/wtp`）；按 LP `multiverse.access.*` 判断 | `/world tp` 和 `/wtp`；按 MMC ACTIVE 状态、角色及公开状态判断，准入通过后按需加载 ACTIVE 地图 | 已实现但权限来源不同；旧 `/worldtp` 名称没有注册 |
| 准入事件覆盖 | `PlayerTeleportEvent` 检查目标权限；登录、换世界、重生后的二次校验代码已注释 | 严格模式检查传送目标、实际进入世界、登录、重生和角色撤销后的在线位置 | v4 覆盖更完整；v3 直接设置 `multiverse.access.*` 的插件不会自动赋予 v4 准入 |
| 踢出地图玩家 | 地图管理员可用 `/world kick` 将玩家送到默认出生点 | 管理员可踢出同图在线玩家，送到 Multiverse 默认世界出生点 | 已实现 |
| 按需加载/延迟卸载 | API 可加载未加载世界；额外加载世界空闲 12000 tick 后卸载，玩家加入可取消待执行卸载 | 公开 load 已实现，传送会按需加载；无延迟卸载和额外加载标记 | 按需加载已实现；延迟卸载未保留 |
| 世界版本检查 | `VersionCheck` 读取 `level.dat`，还调用 Multiverse 的目录名校验；导入流程会拒绝不兼容版本 | `WorldVersionService` 读取 `Data.DataVersion` 并与服务器版本比较；导入会校验 | 已实现，校验实现和公开 API 形态不同 |
| 世界名称和目录安全 | 创建时将名称转成小写；导入经 Multiverse 世界名/目录校验 | 创建/导入限制名称格式、保留名称和配置的拦截正则；删除还检查目标目录边界及符号链接 | v4 校验更严格；旧脚本或不符合新命名规则的地图操作可能需要调整 |
| 手动保存/重载/同步 | `/mapadmin save\|reload\|sync`；`/write` 输出节点/组数据；`sync` 从 LP 重建缓存数据 | JSON/MySQL repository 自动持久化；无对应管理命令或全量 LP 重同步服务 | 手动运维入口未迁移；`/write` 的调试输出也未迁移 |
| 对外 API | 其他插件通过旧 `MapManager` 对象取得 Kotlin 服务，接口直接暴露 LP、MV、Bukkit 类型和可变映射 | `MapManager` 通过 Bukkit ServicesManager 注册；公开稳定模型/接口，存储和 LP 实现在 plugin | 已提供新的服务入口，但不是 v3 源码或二进制兼容 API；获取方式见 [API_GUIDE.md](API_GUIDE.md) |
| 存储后端 | `worlds.json`、`groups.json` 和 YAML，部分变更需手动保存 | JSON 或 MySQL repository 自动保存，含操作阶段记录；完整请求支持启动恢复与手动 recover | 已迁移并扩展；不是旧文件结构的直接兼容读写，v3 JSON 通过迁移流程导入 |

### 命令和策略差异

- v3 的 `/create`、`/import`、`/init`、`/delete` 是独立命令并按权限节点授权；v4 将创建、导入、初始化、删除合并到 `/mapadmin`，仅允许控制台执行；玩家操作使用 `/world`。
- v3 与 v4 创建/导入未指定生成器时均默认为 `FLAT`；v4 环境默认为 `NORMAL`。v3 未提供 group 时用地图名，v4 也默认地图名。
- v3 创建/导入未提供 owner 时使用命令发送者；v4 控制台命令未提供 `admin=<玩家>` 时不添加管理员。
- v3 传送补全及准入读取 LuckPerms 的 `multiverse.access.<world>`；v4 按 MMC ACTIVE 状态、角色和 `isPublic` 判定。

旧 API 中的 MV 对象、LP 对象、可变节点映射和文件读写接口不再公开；相应查询和操作按职责进入 v4 服务。以上未实现项和行为差异以两边当前源码为准。

统一失败通知是 plugin 内部 `OperationNotifier`，公共结果为 `OperationResult`。以上“未实现”按当前 v4 源码判断；API 类型存在但没有命令、事件或 ServicesManager 入口的能力明确标为“部分实现”或“未接通”。

`PlayerGateway` 和 `WorldGateway` 接口位于 `api.service`，实现位于 `plugin`。迁移入口 `V3MigrationSource`、存储接口 `MapRepository`/`RoleRepository`/`OperationRepository` 及 LP 投影接口 `WorldBaseProjection` 仍位于 `plugin.internal`；存储细节不进入公开 API。
