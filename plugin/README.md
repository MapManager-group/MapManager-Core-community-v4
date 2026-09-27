# plugin 模块

Paper 入口会读取 `config.yml` 并初始化 JSON 或 MySQL 存储。默认使用 `plugins/MapManager-Core/maps.json`；MySQL 需要先创建数据库及授权账户，插件只自动创建表。选择的后端保存在 `storage-backend.txt`，更改后端或 MySQL 目标前须显式迁移数据。启动读取失败会禁用插件；不会用空数据库覆盖原数据。

启动时通过 LuckPerms API 异步检查 `worldbase`：已存在时不修改；缺失时创建、继承 `default` 并保存。权限节点仍由管理员手动添加。初始化失败会记录错误并禁用插件。创建/导入地图及修改组角色时会维护地图组继承和成员投影。

创建和导入地图均拒绝固定保留名：`cache`、`config`、`world`、`log`、`test`、`overworld`、`default`、`worldbase`、`apply`、`public`、`private`，以及以 `plugin`、`version`、`admin`、`mapmanager`、`builder`、`visitor`、`lib`、`mmc` 开头的名称。`creation.blocked-world-name-regex` 可额外配置 Java 正则，匹配完整地图名；默认 `.*_bak$`，会拒绝 `_bak` 结尾的名称。例如 `(?:lobby|temp.*|.*_bak)` 可同时禁止 `lobby`、`temp` 前缀和 `_bak` 后缀。正则无效会阻止启动；内置限制始终生效。

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

地图查询、地图设置、角色持久化与准入决策已接上存储。`/world admins|builders|visitors` 仅允许能进入当前地图的玩家查询直接授予成员，`/world admin|builder|visitor add|remove <用户名>` 和 `/world kick` 仅由 MMC 当前地图组管理员执行；所有玩家可执行 `/world tp`，但入图仍由 MMC 准入判断。玩家命令不检查 Bukkit 权限节点。组角色会同步 LP 地图组成员，若 MMC 写入成功而 LP 同步失败则返回 `Partial`；重复命令可以重新投影。自动重试、全路径准入监听和 v3 迁移仍未实现，当前版本不会发布完整 `MapManager` API。

控制台先执行 `/world delete <地图名>`，然后在 10 秒内执行 `/world delete <6 位随机字符串>`；玩家不能执行。`config.yml` 的 `deletion.allowed-world-name-regex` 使用 Java 正则匹配整个地图名，默认 `.*_bak$`，只允许删除 `_bak` 结尾的地图；命令和生命周期 API 均执行该限制。删除前检查世界目录、主世界及在线玩家；删除中保留 `DELETING` 状态，失败后可重新走两步确认。删除世界成功后清理 LP 世界继承及 MMC 地图、角色和空组记录。

`PlayerGateway.resolveId` 优先在线玩家和服务端缓存。未命中时先读取 `config/paper-global.yml` 中已启用的 Velocity/Bungee 代理的 `online-mode`，否则读取 `server.properties`；正版模式异步向 Mojang 补全 UUID，离线模式按 `OfflinePlayer:<用户名>` 计算 UUID。`WorldGateway` 只提供目录查询：先读取 `server.properties` 的 `level-name`，读取不到时使用 `Bukkit.getWorlds().get(0).getName()`；存在 `<level-name>/dimensions/minecraft` 时以此作为地图父目录，否则使用服务端工作目录。路径查询不会创建目录。创建和导入等操作统一由 `WorldLifeCycleService` 定义。
