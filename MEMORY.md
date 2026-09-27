# MapManager Core v4 项目记忆

## 项目定位

- 本仓库是独立的 v4 项目。`/Users/hanamizu/IdeaProjects/MapManager-Core-community/` 是 v3 功能与迁移数据的参考，不在原项目重构。
- 尽量保留 v3 的用户功能：地图创建、导入、初始化、删除、加载/卸载、角色成员、公开状态、地图设置、传送、出生点和管理命令。
- Maven 双模块：`api`（对外接口和模型）、`plugin`（Paper 插件入口、业务实现与内部端口）。`groupId` 和 Java 包根为 `studio.lorian.mapmanager`；源码为 Java，目标版本 Java 21。

## 已确定的职责边界

- MMC 是地图、地图组、角色和**所有入图准入判断**的权威数据源。不能再以 `multiverse.access.<world>` 或 LuckPerms 查询结果决定能否进入地图。
- `MapRole` 为 `ADMIN`、`BUILDER`、`VISITOR`。管理员和建筑师按地图组共享，访客按单张地图授予；管理员同时具有建筑师能力。公开地图允许访客进入，不授予建造权限。公开状态保存在 `MapSettings.isPublic`，不再作为 `MapInstance` 的独立字段。
- LuckPerms 仅投影建造能力：启动时缺失 `worldbase` 则自动创建并继承 `default`，服主手工配置其权限节点。地图权限组继承与成员同步仍待实现。LP 同步失败须明确报告并允许重试，不能改变 MMC 的准入权威。
- Multiverse-Core 只执行世界创建、导入、加载、卸载、出生点/世界配置和删除。`WorldLifeCycleService` 负责编排元数据与世界操作。
- 准入实现必须覆盖命令和其他插件触发的传送、登录、重生、实际换世界等路径；事件前置拦截并在进入后复核。
- 地图设置仅由各地图管理，保留物理、爆炸和 `isPublic`；不提供 `GlobalSettings` 或 PVP 设置。JSON 与数据库存储是可选后端；`MapRepository`、`RoleRepository`、`OperationRepository` 仅属于 `plugin` 内部，不进入公开 API。
- 修改操作使用 `OperationResult`（`Success`、`Failure`、`Partial`）和统一 `OperationNotifier`；失败信息结构为 `failed, <reason>, <operation>`。
- v3 迁移需读取 `worlds.json`、`groups.json` 及关联的 Multiverse/LP 状态，先做 dry-run、备份、冲突报告，再正式导入；玩家主键转换为 UUID。

## 当前代码状态（2026-09-27）

- `api` 已声明 `MapInstance`、`MapGroup`、`MapRole`、请求/设置/结果类型，以及地图查询、角色、准入、设置、传送、世界生命周期服务接口；公开 JavaDoc 为中文。
- `plugin` 已有 `MapManagerPlugin` 主类和 `paper-plugin.yml`。当前 Paper API 编译基线为 1.20.1，manifest 的 `api-version` 为 `1.20`；LuckPerms、Multiverse-Core 为必需依赖，VoidGen 为可选依赖。Maven 打包配置会将 `api` 模块包含进插件 JAR。
- JSON/MySQL 存储、配置加载、地图查询、地图设置、角色持久化、准入决策及 LP `worldbase` 启动初始化已有实现；世界、LP 地图组投影、通知、迁移等仍是**占位实现**。返回的 `null`、`false`、空集合及空方法体不是业务行为。
- 主类会加载所选存储；尚未注册 API 服务、命令或监听器，并会记录未完成警告。当前代码不能作为可用的地图管理插件部署。
- 已用 `javac --release 21` 和本机缓存的 Paper 1.20.1 API 编译源码，POM XML 与 `paper-plugin.yml` YAML 语法检查通过；尚未执行 Maven 打包或 Paper 服务端加载。

## 后续实现顺序

1. 完成 v3 迁移、存储后端之间的显式迁移，以及生产数据恢复流程。
2. 完成 MMC 角色与全路径入图准入规则，再接入 LuckPerms 的 `worldbase` 投影。
3. 接入 Multiverse-Core 生命周期操作及失败恢复，随后实现设置、传送、命令和通知。
4. 业务服务可用后再在主类注册 API、命令和监听器，并进行 Maven 构建与服务端集成验证。

详细设计见 [ARCHITECTURE.md](ARCHITECTURE.md)；v3 接口对照见 [API_INVENTORY.md](API_INVENTORY.md)。
