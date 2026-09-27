# plugin 模块

Paper 入口会读取 `config.yml` 并初始化 JSON 或 MySQL 存储。默认使用 `plugins/MapManager-Core/maps.json`；MySQL 需要先创建数据库及授权账户，插件只自动创建表。选择的后端保存在 `storage-backend.txt`，更改后端或 MySQL 目标前须显式迁移数据。启动读取失败会禁用插件；不会用空数据库覆盖原数据。

启动时通过 LuckPerms API 异步检查 `worldbase`：已存在时不修改；缺失时创建、继承 `default` 并保存。权限节点仍由管理员手动添加。初始化失败会记录错误并禁用插件；地图组继承和成员投影尚未实现。

地图记录包含 UUID、世界名、组 ID、展示名、`MapSettings`、持久状态（`PENDING`、`ACTIVE`、`ARCHIVED`、`DELETING`、`DELETED`）和可空出生点。`MapGroup.worldNames` 从地图与组的关系计算，保存组时只写组 ID。访客与地图关联；管理员和建筑师与组关联。操作阶段单独持久化。

MySQL 自动创建五张 InnoDB 表：

| 表 | 用途 | 主键和索引 |
|---|---|---|
| `mm_groups` | 地图组 ID | `id` 主键 |
| `mm_maps` | 地图与设置 | UUID 主键；标准化世界名唯一；`(group_id, status)` 和 `status` 索引；`group_id` 外键引用组 |
| `mm_group_roles` | 组管理员/建筑师 | `(group_id, role, player_id)` 主键；玩家 ID 索引；组外键 |
| `mm_map_visitors` | 单地图访客 | `(map_id, player_id)` 主键；玩家 ID 索引；地图外键 |
| `mm_operations` | 操作恢复阶段 | UUID 主键；`stage` 索引 |

`settings_json` 和 `spawn_json` 在 `mm_maps` 中各占一个 JSON 字段，出生点可为 `NULL`。删除地图时访客授权级联删除；仍有地图引用的组不能删除。JSON 后端以相同的逻辑关系写入单文件，每次写入使用临时文件和原子替换。

地图查询、地图设置、角色持久化与准入决策已接上存储。组角色的 LuckPerms 投影、世界生命周期、传送、命令和 v3 迁移仍是占位实现；组角色修改会返回 `Partial`，不会假报权限同步成功。当前版本不会发布完整 `MapManager` API，也不能作为可用的地图管理插件部署。

`PlayerGateway.resolveId` 优先在线玩家和服务端缓存。未命中时先读取 `config/paper-global.yml` 中已启用的 Velocity/Bungee 代理的 `online-mode`，否则读取 `server.properties`；正版模式异步向 Mojang 补全 UUID，离线模式按 `OfflinePlayer:<用户名>` 计算 UUID。`WorldGateway` 只提供目录查询：先读取 `server.properties` 的 `level-name`，读取不到时使用 `Bukkit.getWorlds().get(0).getName()`；存在 `<level-name>/dimensions/minecraft` 时以此作为地图父目录，否则使用服务端工作目录。路径查询不会创建目录。创建和导入等操作统一由 `WorldLifeCycleService` 定义。
