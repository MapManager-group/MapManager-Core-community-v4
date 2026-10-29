# v3 扩展插件迁移到 v4

v4 不兼容 v3 实现类调用。先按 [API 指南](API_GUIDE.md)更新 provided 依赖、Paper 依赖声明和服务就绪处理；服务器旧数据升级另见[数据迁移](MIGRATION.md)。

## 调用替换

| v3 用法 | v4 用法与差异 |
|---|---|
| 通过主类单例获取内部对象 | Bukkit ServicesManager 获取 `MapManager`，处理服务尚未就绪和撤销 |
| `WorldManager` / `World` / `Group` | `maps()` 查询 `MapInstance`、`MapGroup`；返回不可变快照 |
| 读取或改写 admins/builders/visitors 集合 | `roles()`；管理员/建筑者按组，访客按地图，使用 UUID |
| LP 节点判断公开或准入 | `access().decide`；公开和角色由 MMC 保存，LP 仅投影建造权限 |
| 修改世界名或别名 | `settings().setDisplayName` 或 `aliases().setAlias`；不能重命名存档目录 |
| 物理、爆炸、公开、出生点 | `settings()`；设置写入 MMC，必要时同步 MV |
| 直接通过 MV 传送、踢人 | `travel().teleport` / `kickFromMap` |
| 创建、导入、初始化、删除世界 | `worldLifeCycle()`；通过请求模型传入参数，按 OperationResult 处理 |
| 自行拼世界目录 | `worldDirectories().worldDirectory`；已加载世界可能在维度子目录 |
| 版本工具 | `worldVersions()`；dataVersion 判断，不是游戏版本字符串比较 |
| 直接访问 JSON、repository 或存储实现 | 不提供公开入口；改用业务服务 |

v3 的具体重载不做同名兼容，参数以当前 [API 源码](../api/src/main/java/studio/lorian/mapmanager/api/)为准。`PlayerGateway` 当前没有服务获取入口。

## 行为差异

- 角色用 UUID 保存；没有默认地图所有者，创建/导入时显式传 `ownerId`。组角色和单图访客不能合并成同一成员列表。
- ADMIN、BUILDER 继承 `worldbase` 建造权限，替代 v3 的 `apply` 基础组；公开地图只授予准入。`assignedMembers` 是直接授权，`members/hasRole` 包含角色继承。
- 默认世界始终允许进入，旧角色和单图设置不生效；不再投影该世界的建造权限，也不接受其管理操作。
- 创建/导入默认物理关闭、爆炸关闭、私有；初始化再次关闭物理与爆炸。API 不执行玩家命令的交互确认。
- 一般地图只有 ACTIVE 可进入，未加载时传送服务按需加载；PENDING/DELETING 不是普通可用地图。中断恢复依赖完整请求，不能只改状态值。
- 角色变更保存后立即影响准入，LP 可能返回 Partial 并后台重试；直接 API 写入不复核当前在线会话。调用方授权、线程及结果处理见 [API 指南](API_GUIDE.md)。
- 生命周期命令统一为控制台/RCON 的 `/mapadmin`；旧独立命令和 `/world` 生命周期子命令不保留。旧 `/worldtp`、`/world rename` 也未注册，按当前手册的 `/world tp`、`/wtp`、`/world setname` 等入口替换。

## 当前没有替代入口的能力

PVP 开关、延迟/空闲卸载、全局物理/爆炸覆盖、龙蛋传送保护、旧日志过滤，以及旧式手动 save/reload/sync 未实现。不要把 v3 配置或方法按名字直接映射；外部插件仍依赖这些能力时，先调整集成范围。完整现行命令见[命令手册](COMMANDS.md)。
