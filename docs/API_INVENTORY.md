# v3 接口盘点与 v4 分类

本清单依据 v3 `MapManager-API/src/main/kotlin` 中的全部接口和模型，并对照 `MapManager-Core` 的命令调用。v3 文件只用于识别能力，v4 不直接复制暴露存储状态的旧签名。v4 接口使用 Java 编写。

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
| `MapAgent.getUniqueID/getPlayer/isPlayerRegister` | 玩家名解析、在线/历史玩家查找 | `api.service.PlayerGateway`；用户名查询在实现中完成，角色等公开服务仍使用 UUID |
| `MapAgent.getWorldParentFolder/getWorldFolder` | 世界目录访问 | `api.service.WorldGateway`；目录查询返回 JDK `Path` |
| `MapAgent.save/reload` | 旧 JSON 文件保存、重载 | plugin 管理命令和内部 repository；公开 API 不操作文件 |
| `MapAgent.setLuckPerms/syncWithLuckPerms` | LP 实例注入与数据同步 | plugin 内部 `WorldBaseProjection`；仅投影 `worldbase` 能力 |
| `MapAgent.ignoreColor` | 地图显示字符串处理 | plugin 内部展示工具，不作为服务接口 |
| `DynamicWorld.createWorld/importWorld/initWorld/removeWorld` | MV 世界创建、导入、初始化和删除 | `WorldLifeCycleService` 统一编排；`WorldGateway` 仅负责目录查询 |
| `DynamicWorld.loadWorld/unloadWorldLater/cancelUnloadTask` | 按需加载和延迟卸载 | v4 尚未实现按需加载和延迟卸载；公开 `WorldLifeCycleService` 当前无 `load` 方法 |
| `DynamicWorld.hasLoaded/isExist/isExtraLoad` | 世界运行状态 | plugin 内部世界状态查询；延迟任务标记留在 plugin 内部（当前公开 `WorldLifeCycleService` 无 `state` 方法） |
| `DynamicWorld.getCorrectName/getCorrectUnloadedName/getWorlds/getPotentialWorlds` | 名称匹配及补全 | `MapService` 查询与 plugin 命令补全；不公开 MV 世界列表 |
| `DynamicWorld.getOwnerWorlds/getAccessWorlds` | 管理/可进入地图列表 | `MapAccessService.administratedMaps/accessibleMaps`；MMC 自己判定 |
| `DynamicWorld.getSpawnLocation/getDefaultSpawnLocation` | 地图及默认出生点 | `MapSettingsService.getSpawn`、plugin 安全世界配置 |
| `DynamicWorld.getMVWorldManager/getLoadedWorld/getCorrectWorld/getMVWorld` | MV 原生对象 | 留在 plugin 的 Multiverse 实现内部，不泄漏到 API |
| `DynamicWorld.hasPermission` | LP/Multiverse 访问节点查询 | 移除旧准入方式，由 `MapAccessService.decide` 替代 |
| `MainYaml`、`MainConfig` | YAML 全局设置读写 | 仅迁移地图自身设置；全局覆盖不进入 v4 API |
| `VersionCheck` | 世界文件版本检查 | `api.service.WorldVersionService` 提供服务器版本、地图版本读取和兼容性判断；NBT 解析留在 plugin |
| `WorldNode`、`WorldGroup`、`MapGroup`、`MMWorldType` | 地图数据、组、角色、世界类型 | `MapInstance`、`MapGroup`、`MapRole`、`WorldType`；不返回可变持久实体 |

## v3 用户功能迁移差异

| v3 功能 | v4 当前状态 | 差异说明 |
|---|---|---|
| 地图创建、导入、初始化、删除 | 已实现 | 由 `WorldLifeCycleService` 编排并持久化操作阶段；失败自动恢复仍未实现 |
| 地图组管理员/建筑者、单图访客 | 已实现 | MMC 角色为权威数据，组管理员/建筑者投影至 LuckPerms；投影自动重试未实现 |
| 公开/私有地图 | 部分实现 | `MapSettingsService.setPublic` 和准入决策已实现，但没有 `/world public` 管理命令 |
| 地图显示名/Multiverse 别名 | 部分实现 | `MapAliasService` 和 `MapSettingsService.setDisplayName` 可供内部服务使用，创建/导入和初始化会同步；没有 `/world setname` 命令，完整 `MapManager` API 也未注册到 Bukkit 服务 |
| 地图出生点 | 部分实现 | `MapSettingsService.getSpawn/setSpawn` 只读写 MMC 出生点；没有 `/world setspawn`，手动设置不会同步 Bukkit/Multiverse 出生点 |
| 地图物理和爆炸 | 已实现 | 单地图开关、状态查询和物理/落沙/两类爆炸事件均已接通；不提供全局覆盖 |
| PVP 设置 | 未实现 | v3 `/world pvp` 读写并保存 Multiverse PVP；v4 `MapSettings`、设置服务和命令均无对应能力 |
| 地图传送 `/world tp`、`/wtp` | 部分实现 | MMC 准入与已加载地图传送已实现；未加载地图不会按需加载；v3 的 `/worldtp` 名称未作为 v4 命令注册 |
| 踢出当前地图玩家 | 已实现 | 管理员只能踢出同图在线玩家，目标传送到 Multiverse 默认世界出生点 |
| 传送准入监听 | 已实现 | `MapAccessListener` 默认启用 `access-control.strict-map-entry`，用 MMC 检查传送目标、传送后实际世界、登录、重生和角色撤销后的当前地图；关闭配置会停用整套检查。v3 的 LP `multiverse.access.*` 不是 v4 的准入来源 |
| 未加载地图按需加载及延迟卸载 | 未实现 | v3 有加载、取消卸载与延迟卸载实现；v4 传送要求目标世界已加载，也没有对应状态和任务 |
| 导入世界版本兼容检查 | 已实现 | plugin 使用 Querz NBT 读取 `Data.DataVersion`，并与 Bukkit 服务器 dataVersion 比较；导入会拒绝版本更高或版本字段无效的地图，公开服务可查询地图版本和兼容性 |
| 龙蛋传送保护 | 未实现 | v3 `BlockListener` 取消玩家右键龙蛋事件；v4 设置监听器中没有对应事件处理 |
| LuckPerms `apply` 共享权限组 | 未迁移 | v3 地图组继承 `apply`（无世界上下文）；v4 地图组仅维护 `default` 和带世界上下文的 `worldbase`。v4 将 MMC 作为准入来源，旧 `public` LP 组也不再代表公开状态 |
| `/mapadmin save`、`reload`、`sync` 与 `/write` | 无直接命令等价物 | v4 对业务数据自动持久化；没有手动导出/重载命令、LuckPerms 全量重同步命令或操作记录查看/恢复命令。LP 同步失败后可重复角色修改重试，但没有自动重试队列 |
| 全局 physics/explosion 管理命令 | 未保留 | v3 有 `/mapadmin physics|explosion true|false|clear` 和配置字段，v4 明确只保留单地图设置。另经检查，v3 当前监听器调用的 `isPhysical/isExploded` 只读取地图节点，未读取这些全局值，因此不能把旧全局配置描述为当前有效的事件覆盖 |
| 对外 `MapManager` API 使用 | 未接通 | v4 定义并组装了 `MapManager` 服务对象，但主类尚未注册到 Bukkit ServicesManager；其他插件无法通过标准服务注册获取该实例 |

## 命令和策略变化（不是缺失实现）

- v3 使用独立 `/create`、`/import`、`/delete`、`/init` 及 `n:`/`e:` 参数；v4 收敛为 `/world create|import|delete|init` 和 `key=value` 参数。
- v3 多数玩家操作依赖 Bukkit/LuckPerms 权限节点；v4 玩家地图操作以 MMC 角色及地图准入判断为准，创建/导入/初始化/删除改为控制台专用。
- v3 和 v4 `/world create`、`/world import` 未指定类型时均默认 FLAT。
- v3 创建/导入未提供 owner 时使用命令发送者；v4 控制台命令未提供 `admin=<玩家>` 时不会添加首位管理员，地图仍可创建但没有默认管理员。
- v3 `/world tp` 根据 LuckPerms `multiverse.access.<world>` 授权；v4 根据 MMC ACTIVE 状态、角色和 `isPublic` 授权。这是准入权威的替换，而不是待迁移的 LP 访问节点检查。

统一失败通知是 plugin 内部 `OperationNotifier`，公共结果为 `OperationResult`。以上“未实现”按当前 v4 源码判断；API 类型存在但没有命令、事件或 ServicesManager 入口的能力明确标为“部分实现”或“未接通”。

`PlayerGateway` 和 `WorldGateway` 接口位于 `api.service`，实现位于 `plugin`。迁移入口 `V3MigrationSource`、存储接口 `MapRepository`/`RoleRepository`/`OperationRepository` 及 LP 投影接口 `WorldBaseProjection` 仍位于 `plugin.internal`；存储细节不进入公开 API。
