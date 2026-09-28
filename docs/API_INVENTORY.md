# v3 接口盘点与 v4 分类

本清单依据 v3 `MapManager-API/src/main/kotlin` 中的全部接口和模型，并对照 `MapManager-Core` 的命令调用。v3 文件只用于识别能力，v4 不直接复制暴露存储状态的旧签名。v4 接口使用 Java 编写。

| v3 来源 | v3 能力 | v4 归属 |
|---|---|---|
| `MapManager` | 获取 `DynamicWorld`、`MapAgent`、`MainYaml`、`VersionCheck`、LP | `api/MapManager` 只暴露分类服务；LP、配置文件与版本检查实例由 plugin 管理 |
| `MapAgent.newWorld/deleteWorld` | 地图记录创建与删除 | `WorldLifeCycleService` 统一编排世界和 MMC 数据 |
| `MapAgent.addPlayer/removePlayer/getPlayers/isAdmin` | admin、builder、visitor 成员管理 | `MapRoleService`；UUID 为玩家主键 |
| `MapAgent.publicizeWorld/privatizeWorld/isPublic` | 地图公开状态 | `MapSettingsService.setPublic`、`MapSettings.isPublic` |
| `MapAgent.getWorldAlias/setWorldAlias` | 地图显示名 | `MapInstance.displayName`、`MapSettingsService.setDisplayName` |
| `MapAgent.setPhysical/isPhysical/setExploded/isExploded` | 地图及全局物理、爆炸设置 | 仅迁移各地图设置到 `MapSettings`；不保留全局覆盖 |
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
| `VersionCheck` | 世界文件版本检查 | plugin 内部 `WorldVersionValidator`，不进入公开 API |
| `WorldNode`、`WorldGroup`、`MapGroup`、`MMWorldType` | 地图数据、组、角色、世界类型 | `MapInstance`、`MapGroup`、`MapRole`、`WorldType`；不返回可变持久实体 |

v3 命令中未由独立公开接口覆盖的能力也已预留：`/world setspawn` 对应 `MapSettingsService` 的出生点方法；`/world tp`、`/world kick` 对应 `MapTravelService`（目标世界按需加载尚未实现）；`/worldtp`、`/mapadmin` 的保存、重载、同步属于 v3 能力参考，v4 尚无对应命令。统一失败通知是 plugin 内部 `OperationNotifier`，公共结果为 `OperationResult`。

`PlayerGateway` 和 `WorldGateway` 接口位于 `api.service`，实现位于 `plugin`。迁移入口 `V3MigrationSource`、存储接口 `MapRepository`/`RoleRepository`/`OperationRepository` 及 LP 投影接口 `WorldBaseProjection` 仍位于 `plugin.internal`；存储细节不进入公开 API。
