# MapManager Core v4 项目记忆

## 项目定位

- 本仓库是独立的 v4 项目。`/Users/hanamizu/IdeaProjects/MapManager-Core-community/` 是 v3 功能与迁移数据的参考，不在原项目重构。
- 尽量保留 v3 的用户功能：地图创建、导入、初始化、删除、加载/卸载、角色成员、公开状态、地图设置、传送、出生点和管理命令。
- Maven 双模块：`api`（对外接口和模型）、`plugin`（Paper 插件入口、业务实现与内部端口）。`groupId` 和 Java 包根为 `studio.lorian.mapmanager`；源码为 Java，目标版本 Java 21。

## 已确定的职责边界

- MMC 是地图、地图组、角色和**所有入图准入判断**的权威数据源。不能再以 `multiverse.access.<world>` 或 LuckPerms 查询结果决定能否进入地图。
- `MapRole` 为 `ADMIN`、`BUILDER`、`VISITOR`。管理员和建筑师按地图组共享，访客按单张地图授予；管理员同时具有建筑师能力。公开地图允许访客进入，不授予建造权限。公开状态保存在 `MapSettings.isPublic`，不再作为 `MapInstance` 的独立字段。
- LuckPerms 仅投影建造能力：启动时缺失 `worldbase` 则自动创建并继承 `default`，服主手工配置其权限节点。地图组继承与成员已投影到 LP；同步失败会明确报告，重复操作可重试，自动重试仍待实现。LP 不能改变 MMC 的准入权威。
- Multiverse-Core 执行世界创建、导入、初始化、出生点/世界配置和删除。`WorldLifeCycleService` 负责编排元数据与世界操作；按需加载和卸载尚未实现。
- 准入实现必须覆盖命令和其他插件触发的传送、登录、重生、实际换世界等路径；事件前置拦截并在进入后复核。
- 地图设置仅由各地图管理，保留物理、爆炸和 `isPublic`；不提供 `GlobalSettings` 或 PVP 设置。JSON 与数据库存储是可选后端；`MapRepository`、`RoleRepository`、`OperationRepository` 仅属于 `plugin` 内部，不进入公开 API。
- 修改操作使用 `OperationResult`（`Success`、`Failure`、`Partial`）和统一 `OperationNotifier`；通知通过 MiniMessage 和语言文件本地化，默认语言为 `zh_CN`，API 保留稳定原因码。
- v3 启动迁移读取 `worlds.json`、`groups.json` 及只读的 Multiverse/LP 状态；有两个源文件时先备份，解析玩家 UUID、检查冲突，再写入所选仓储。dry-run 和详细差异报告仍待实现。

## 当前代码状态（2026-09-28）

- `api` 已声明 `MapInstance`、`MapGroup`、`MapRole`、请求/设置/结果类型，以及地图查询、角色、准入、设置、传送、世界生命周期服务接口；公开 JavaDoc 为中文。
- `plugin` 已有 `MapManagerPlugin` 主类和 `paper-plugin.yml`。当前 Paper API 编译基线为 1.20.1，manifest 的 `api-version` 为 `1.20`；LuckPerms、Multiverse-Core 为必需依赖，VoidGen 为可选依赖。Maven 打包配置会将 `api` 模块包含进插件 JAR。
- JSON/MySQL 存储、地图查询、地图设置、角色持久化、准入决策、可配置的全路径准入监听、生命周期、MiniMessage 多语言通知及 v3 JSON 启动迁移已有实现。LP `worldbase` 与地图组投影已实现；自动重试仍待补充。
- 主类加载所选存储，注册地图设置和准入监听；检测相邻 `MapManager-Core` 目录中的 `worlds.json` 与 `groups.json`，迁移及 LP `worldbase` 初始化成功后注册 `MapManager` 服务、复核在线玩家并注册 `/world`、`/wtp`；任一初始化失败会禁用插件。
- `access-control.strict-map-entry` 默认开启，关闭后传送、登录、重生和撤权后的准入检查都停用。默认消息语言为 `zh_CN`，内置 `en_US`；语言文件位于 `plugin/src/main/resources/langs/`，运行时从插件数据目录加载。

## 后续实现顺序

1. 将准入检查的高频同步存储读取调整为适合主线程的方式，并完成其他插件组合下的服务器集成验证。
2. 完成创建、导入和删除失败恢复；完成 LP 投影异步重试及玩家断线实现。
3. 为已实现的 v3 自动迁移补充 dry-run 和差异报告，并完成 JSON/MySQL 后端切换流程。
4. 完成 Maven 构建及 Paper、Multiverse-Core、LuckPerms 集成验证，并验证外部插件 API 调用。

详细设计见 [ARCHITECTURE.md](ARCHITECTURE.md)；v3 接口对照见 [API_INVENTORY.md](API_INVENTORY.md)。
