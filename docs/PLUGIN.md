# plugin 模块

Paper 入口会读取 `config.yml` 并初始化 JSON 或 MySQL 存储。默认使用 `plugins/MapManager-Core/maps.json`；MySQL 需要先创建数据库及授权账户，插件只自动创建表。选择的后端保存在 `storage-backend.txt`，更改后端或 MySQL 目标前须显式迁移数据。启动读取失败会禁用插件；不会用空数据库覆盖原数据。

启动时通过 LuckPerms API 异步检查 `worldbase`：已存在时不修改；缺失时创建、继承 `default` 并保存。权限节点仍由管理员手动添加。初始化失败会记录错误并禁用插件。创建/导入地图及修改组角色时会维护地图组继承和成员投影。

地图准入监听由 `access-control.strict-map-entry` 控制，默认 `true`。开启时，插件会在传送前校验目标地图，并在传送后复核实际世界；玩家加入服务器、重生或失去当前地图最后一项有效进入权限时也会检查。未获准的玩家会返回 Multiverse 默认世界出生点；默认世界不可用、玩家也无权进入默认地图或传送失败时会断开连接。设为 `false` 会同时停用传送、登录、重生及撤权后的监听检查。`/world tp` 和 `/wtp` 仍按 MMC 准入结果判断是否允许传送。

`/mapadmin create <地图名> [生成器类型] [环境]` 和 `/mapadmin import <地图名> [生成器类型] [环境]` 使用独立的生成器类型与维度环境；默认分别为 `flat` 和 `normal`。生成器类型为 `flat`、`normal`、`void`，环境为 `normal`、`nether`、`the_end`、`sift`。`normal` 使用原版标准地形生成器，`flat` 使用平坦生成器，`void` 使用 VoidGen；环境分别映射主世界、下界和末地。`sift` 是预留环境，当前请求会返回 `UNSUPPORTED_ENVIRONMENT`，不会创建或导入地图。

创建时 `flat.height` 只适用于 `flat` 生成器，不能与 `generator-settings` 同时使用；在 `normal` 环境总层高可为 2–320，在 `nether`、`the_end` 环境为 2–254。高度从底到顶依次为 1 层基岩、`N-2` 层泥土和 1 层草方块，例如 `flat.height=64` 会生成 1 层基岩、62 层泥土和 1 层草方块。导入命令同样可选生成器与环境，但不接受生成参数；已有区块按存档内容保留，选项保存在 Multiverse 配置中并影响后续生成。导入会读取 `level.dat` 的 `Data.DataVersion`，并与当前 Bukkit 服务器 dataVersion 比较；高于服务器版本或缺少有效版本信息的地图会被拒绝。`MapManager.worldVersions()` 提供公开版本查询接口，NBT 解析实现位于 plugin 模块。

创建和导入地图均拒绝固定保留名：`cache`、`config`、`world`、`log`、`test`、`overworld`、`default`、`worldbase`、`apply`、`public`、`private`，以及以 `plugin`、`version`、`admin`、`mapmanager`、`builder`、`visitor`、`lib`、`mmc` 开头的名称。`creation.blocked-world-name-regex` 可额外配置 Java 正则，匹配完整地图名；默认 `.*_bak$`，会拒绝 `_bak` 结尾的名称。例如 `(?:lobby|temp.*|.*_bak)` 可同时禁止 `lobby`、`temp` 前缀和 `_bak` 后缀。正则无效会阻止启动；内置限制始终生效。

地图记录包含 UUID、世界名、组 ID、展示名、`MapSettings`、持久状态（`PENDING`、`ACTIVE`、`ARCHIVED`、`DELETING`、`DELETED`）和可空出生点。`MapGroup.worldNames` 从地图与组的关系计算，保存组时只写组 ID。访客与地图关联；管理员和建筑师与组关联。操作阶段单独持久化。当前地图管理员可用 `/world public [true|false|info]` 修改或查询公开状态，公开地图允许所有玩家进入；`/world setname <名称>` 修改地图显示名和 Multiverse 别名；`/world setspawn` 将当前位置设置为 MMC、Bukkit 和 Multiverse 出生点。物理和爆炸设置可用 `/world physics [true|false|info]` 与 `/world explosion [true|false|info]` 查看或修改；修改立即持久化。关闭物理会取消方块物理事件并阻止落沙实体生成；关闭爆炸会取消方块与实体爆炸事件。未登记世界不受影响。不提供 v3 全局覆盖。

MySQL 自动创建五张 InnoDB 表：

| 表 | 用途 | 主键和索引 |
|---|---|---|
| `mm_groups` | 地图组 ID | `id` 主键 |
| `mm_maps` | 地图与设置 | UUID 主键；标准化世界名唯一；`(group_id, status)` 和 `status` 索引；`group_id` 外键引用组 |
| `mm_group_roles` | 组管理员/建筑师 | `(group_id, role, player_id)` 主键；玩家 ID 索引；组外键 |
| `mm_map_visitors` | 单地图访客 | `(map_id, player_id)` 主键；玩家 ID 索引；地图外键 |
| `mm_operations` | 操作恢复阶段 | UUID 主键；`stage` 索引 |

`settings_json` 和 `spawn_json` 在 `mm_maps` 中各占一个 JSON 字段，出生点可为 `NULL`。删除地图时访客授权级联删除；仍有地图引用的组不能删除。JSON 后端以相同的逻辑关系写入单文件，每次写入使用临时文件和原子替换。

地图查询、地图设置、角色持久化与准入决策已接上存储。`/world admin|builder|visitor info` 仅允许能进入当前地图的玩家查询直接授予成员，`/world admin|builder|visitor add|remove <用户名或 UUID>`、`/world kick`、`/world public`、`/world setname` 和 `/world setspawn` 仅由 MMC 当前地图组管理员执行；`/world tp` 与 `/wtp` 可进入玩家有地图角色或公开的已加载地图，补全使用相同准入结果。玩家命令不检查 Bukkit 权限节点。组角色持久化后立即改变 MMC 准入；LP 入队返回 `Partial(PERMISSION_SYNC_PENDING)`，失败自动重试和重启重放。启动时会检查相邻插件目录 `MapManager-Core`；其中同时存在 `worlds.json` 和 `groups.json` 时，插件备份旧文件并迁移地图、组和角色数据到所选仓储。严格准入监听已实现并默认开启；LP 由持久队列异步执行和自动重试。存储迁移和 LP `worldbase` 初始化成功后，`MapManager` 会注册到 Bukkit ServicesManager，外部插件依赖与调用方法见 [API_GUIDE.md](API_GUIDE.md)。

控制台先执行 `/mapadmin delete <地图名>`，然后在 10 秒内执行 `/mapadmin delete <6 位随机字符串>`；玩家不能执行。`config.yml` 的 `deletion.allowed-world-name-regex` 使用 Java 正则匹配整个地图名，默认 `.*_bak$`；仅 `/mapadmin delete` 命令执行该正则限制，生命周期 API 不受此正则限制。删除检查默认世界、玩家、目录和版本；先保存完整请求及目录身份，再变更世界。世界删除成功后将 LP 清理任务持久化，再清理 MMC 地图、角色和空组；LP 等待同步时返回 Partial。

## 维护与恢复

以下命令仅允许控制台，玩家或 OP 不能绕过发送者检查：

| 命令 | 行为 |
|---|---|
| `/mapadmin create <地图> [生成器] [环境] [选项...]` | 创建地图；保留创建参数校验和 admin 用户名/UUID 解析 |
| `/mapadmin import <地图> [生成器] [环境] [alias=... group=... admin=...]` | 导入地图；不接受生成参数 |
| `/mapadmin init <地图>` | 重放已加载地图的初始化规则 |
| `/mapadmin delete <地图或确认码>` | 申请删除后 10 秒内输入一次性六位码确认 |
| `/mapadmin load <地图>` | 加载已登记 ACTIVE 地图，校验目录和版本；传送也会按准入结果自动加载 |
| `/mapadmin recover <地图>` | 按完整请求继续创建/导入或已确认的删除；不猜测缺失请求 |
| `/mapadmin operations` | 列出未完成和失败操作的 ID、世界、阶段和原因 |
| `/mapadmin migration dry-run` | 生成 v3 差异报告，目标数据不变 |
| `/mapadmin migration apply` | 再次预检后正式导入或继续冻结计划的部分写入 |
| `/mapadmin storage export <文件名>` | 异步导出完整快照到 `exports/`；文件名只含字母、数字、下划线或连字符 |

启动在发布 API 前自动继续 `world-recovery/` 中的请求。失败保留 PENDING/DELETING；恢复核对地图与目录身份。目录被替换、出现符号链接或旧版本没有原始请求时停止并提示核查。六位确认码仍用于发起新的 `/mapadmin delete`，恢复仅继续已经持久化的删除。Paper 26 已登记维度读取共享主世界 `level.dat`；经典导入目录仍要求自身有效 `level.dat`。

事件读取使用不可变内存索引，写入仍同步持久化；LP 在后台执行和自动重试，返回 `Partial(PERMISSION_SYNC_PENDING)`，MMC 角色已经生效。全局 FIFO 的失败任务会延后后续投影。详细配置见 [STORAGE.md](STORAGE.md) 和 [MIGRATION.md](MIGRATION.md)。创建选项 `admin=` 同时接受用户名和 UUID。

`PlayerGateway.resolveId` 优先在线玩家和服务端缓存。未命中时先读取 `config/paper-global.yml` 中已启用的 Velocity/Bungee 代理的 `online-mode`，否则读取 `server.properties`；正版模式异步向 Mojang 补全 UUID，离线模式按 `OfflinePlayer:<用户名>` 计算 UUID。`WorldGateway` 只提供目录查询：先读取 `server.properties` 的 `level-name`，读取不到时使用 `Bukkit.getWorlds().get(0).getName()`；存在 `<level-name>/dimensions/minecraft` 时以此作为地图父目录，否则使用服务端工作目录。路径查询不会创建目录。创建和导入等操作统一由 `WorldLifeCycleService` 定义。

默认通知语言为 `zh_CN`，可在 `config.yml` 设置 `language`；内置语言有 `zh_CN` 和 `en_US`。启动时插件把内置语言复制到数据目录的 `langs/`，自定义翻译可编辑对应 YAML；选定语言文件缺失或格式错误会导致插件启动失败，缺失翻译键回退到中文。

## 控制台命令入口

所有生命周期与运维命令统一使用 `/mapadmin`：create、import、init、delete、load、recover、operations、migration、storage。`/world` 与 `/wtp` 只提供玩家地图操作。旧 `/world create|import|init|delete` 不再执行；创建/导入选项保持原格式，删除改用 `/mapadmin delete <地图>` 后输入 `/mapadmin delete <六位码>`。
