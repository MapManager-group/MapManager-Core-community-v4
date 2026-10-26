# plugin 模块

默认世界以启动时 Multiverse-Core 的默认世界为准（Multiverse 启用前暂用服务器首个已加载世界）。它始终允许玩家进入，即使存在旧 MMC 私有地图或非 ACTIVE 记录；MMC 不赋予该世界有效角色、不应用单地图方块物理/爆炸设置（坐垫、画、展示框的实体支撑保护为全局规则，默认世界也生效），也不允许修改其别名、出生点、设置或执行生命周期管理。已有地图/角色记录保留用于追溯，但在默认世界上不生效。默认世界变更后需重启 MMC 以刷新识别。 此前已写入 Multiverse 配置或 gamerule 的值不会猜测恢复，需按服务端配置调整。

默认世界不投影 `worldbase` 建造权限：启动后的地图组同步会异步移除已有的默认世界上下文继承，后续同步也不会重建；同组其他地图仍正常投影。实际放置/破坏限制由现有保护插件和 LuckPerms 配置执行；MMC 不新增方块破坏/放置拦截，也不撤销管理员另行配置的全局或其他插件权限。

Paper 入口会在读取业务配置前按 v4 内置资源补齐 `config.yml` 中缺少的节点，保留已有值和注释；执行 v3 正式迁移并成功写入完成标记后，会删除旧版 `global` 配置。没有变更时不重写文件；无效 YAML 或配置节点类型冲突会阻止启动并保留原文件。默认使用 `plugins/MapManager-Core/maps.json`；MySQL 需要先创建数据库及授权账户，插件只自动创建表。选择的后端保存在 `storage-backend.txt`，更改后端或 MySQL 目标前须显式迁移数据。启动读取失败会禁用插件；不会用空数据库覆盖原数据。

启动时通过 LuckPerms API 异步检查 `worldbase`：已存在时不修改；缺失时创建、继承 `default` 并保存。权限节点仍由管理员手动添加。初始化失败会记录错误并禁用插件。创建/导入地图及修改组角色时会维护地图组继承和成员投影。

地图准入监听由 `access-control.strict-map-entry` 控制，默认 `true`。开启时，插件会在传送前校验目标地图，并在传送后复核实际世界；玩家加入服务器、重生或失去当前地图最后一项有效进入权限时也会检查。未获准的玩家会返回 Multiverse 默认世界出生点；默认世界不可用或传送失败时会断开连接；默认世界始终放行准入。设为 `false` 会同时停用传送、登录、重生及撤权后的监听检查。`/world tp` 和 `/wtp` 仍按 MMC 准入结果判断是否允许传送。

`/mapadmin create <地图名> [生成器类型] [环境]` 和 `/mapadmin import <地图名> [生成器类型] [环境]` 使用独立的生成器类型与维度环境；默认分别为 `flat` 和 `normal`。生成器类型为 `flat`、`normal`、`void`，环境为 `normal`、`nether`、`the_end`、`sift`。`normal` 使用原版标准地形生成器，`flat` 使用平坦生成器，`void` 使用 VoidGen；环境分别映射主世界、下界和末地。`sift` 是预留环境，当前请求会返回 `UNSUPPORTED_ENVIRONMENT`，不会创建或导入地图。

创建时 `flat.height` 仅用于 FLAT，不能与 generator-settings 同时使用。flat.height 表示表层草方块的绝对 Y 坐标：0 对应 Y=0，-60 对应 Y=-60。生成时读取目标世界实际 minHeight（含）与 maxHeight（不含），范围为 minHeight ≤ Y < maxHeight，不固定限制为 -64～319 或 -2000～2000；破限高数据包须已在目标世界生效。从世界最低 Y 放基岩，中间填泥土，指定 Y 放草方块；表层等于最低 Y 时仅生成草方块。出生点在表层上方一格。指定 flat.height 时各环境使用内置生成器，Y 编码进 Multiverse generator ID 供重启恢复；旧 layers ID 继续按原层数读取，已有区块不重写。内置生成器在准备生物群系提供器、开始区块生成前读取目标 WorldInfo 校验；创建后和恢复激活前也复核实际范围。超出范围通过操作结果的 detail 返回所填 Y 和实际上下界，MMW 可将其传到审批响应、申请记录详情及站内信。未指定 flat.height 的默认行为及手工 generator-settings 保持不变。导入命令同样可选生成器与环境，但不接受生成参数；已有区块按存档内容保留，选项保存在 Multiverse 配置中并影响后续生成。导入会读取 `level.dat` 的 `Data.DataVersion`，并与当前 Bukkit 服务器 dataVersion 比较；高于服务器版本或缺少有效版本信息的地图会被拒绝。`MapManager.worldVersions()` 提供公开版本查询接口，NBT 解析实现位于 plugin 模块。

创建和导入地图均拒绝固定保留名：`cache`、`config`、`world`、`log`、`test`、`overworld`、`default`、`worldbase`、`apply`、`public`、`private`，以及以 `plugin`、`version`、`admin`、`mapmanager`、`builder`、`visitor`、`lib`、`mmc` 开头的名称。`creation.blocked-world-name-regex` 可额外配置 Java 正则，匹配完整地图名；默认 `.*_bak$`，会拒绝 `_bak` 结尾的名称。例如 `(?:lobby|temp.*|.*_bak)` 可同时禁止 `lobby`、`temp` 前缀和 `_bak` 后缀。正则无效会阻止启动；内置限制始终生效。

地图记录包含 UUID、世界名、组 ID、展示名、`MapSettings`、持久状态（`PENDING`、`ACTIVE`、`ARCHIVED`、`DELETING`、`DELETED`）和可空出生点。`MapGroup.worldNames` 从地图与组的关系计算，保存组时只写组 ID。访客与地图关联；管理员和建筑师与组关联。操作阶段单独持久化。当前地图管理员可用 `/world public [true|false|info]` 修改或查询公开状态，公开地图允许所有玩家进入；`/world setname <名称>` 修改地图显示名和 Multiverse 别名；`/world setspawn` 将当前位置设置为 MMC、Bukkit 和 Multiverse 出生点。物理和爆炸设置可用 `/world physics [true|false|info]` 与 `/world explosion [true|false|info]` 查看或修改；关闭立即持久化，开启经风险二次确认后持久化。关闭地图物理会取消方块物理事件并阻止落沙实体生成。画、展示框（含荧光展示框）及 26.3 坐垫因支撑丢失或方块挤占而破坏的行为始终全局禁止，不读取地图物理开关，默认世界和未登记世界也生效；玩家主动拆除不受此规则影响。26.3 的 EntityBreakEvent 按服务器能力条件注册，旧版服务器仍使用悬挂实体事件；关闭爆炸会取消方块与实体爆炸事件。未登记世界不受单地图方块物理和爆炸设置影响，但同样受全局实体支撑保护。不提供 v3 全局覆盖。

新建和导入的地图从加载前的 PENDING 记录开始默认 `physical=false`、`explosion=false`，命令与 API 创建/导入共用此规则；恢复沿用已保存设置。已有地图及 v3 迁移设置不会自动改写；显式执行 `/mapadmin init <地图>` 或 API `initialize` 会先持久化关闭物理和爆炸，再重放世界初始化，保留公开状态、角色和地图状态；后续世界初始化失败时也保持关闭。

设置成功提示显示实际参数：物理、爆炸、公开状态显示 true/false，名称显示新名称，出生点显示世界、坐标和朝向。开启物理或爆炸须先执行 `/world physics true` 或 `/world explosion true` 阅读风险说明，再在 10 秒内执行对应的 `true confirm` 命令；确认绑定玩家、地图 UUID 与设置类型，消费后失效，并重新校验当前地图管理员身份。关闭设置立即生效。风险说明分别为：“开启物理后，一些非正常放置的方块和实体可能会全部掉落，你确认要开启吗？”、“开启爆炸后，所有爆炸物均可以正常破坏周围方块，你确认要开启吗？”。全局装饰实体支撑保护仍独立生效；公开 API 设置方法由调用方自行处理授权与交互确认。

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

控制台或 RCON 先执行 `/mapadmin delete <地图名>`，然后在 10 秒内执行 `/mapadmin delete <6 位随机字符串>`；玩家不能执行。`config.yml` 的 `deletion.allowed-world-name-regex` 使用 Java 正则匹配整个地图名，默认 `.*_bak$`；仅 `/mapadmin delete` 命令执行该正则限制，生命周期 API 不受此正则限制。删除检查默认世界、玩家、目录和版本；先保存完整请求及目录身份，再变更世界。世界删除成功后将 LP 清理任务持久化，再清理 MMC 地图、角色和空组；LP 等待同步时返回 Partial。

## 维护与恢复

以下命令仅允许控制台或 RCON，玩家（含 OP）和命令方块不能绕过发送者检查。操作结果与删除确认码仍输出至服务器控制台：

| 命令 | 行为 |
|---|---|
| `/mapadmin create <地图> [生成器] [环境] [选项...]` | 创建地图；保留创建参数校验和 admin 用户名/UUID 解析 |
| `/mapadmin import <地图> [生成器] [环境] [alias=... group=... admin=...]` | 导入地图；不接受生成参数 |
| `/mapadmin init <地图>` | 关闭物理和爆炸，再重放已加载地图的初始化规则 |
| `/mapadmin delete <地图或确认码>` | 申请删除后 10 秒内输入一次性六位码确认 |
| `/mapadmin load <地图>` | 加载已登记 ACTIVE 地图，校验目录和版本；传送也会按准入结果自动加载 |
| `/mapadmin recover <地图>` | 按完整请求继续创建/导入或已确认的删除；不猜测缺失请求 |
| `/mapadmin operations` | 列出未完成和失败操作的 ID、世界、阶段和原因 |
| `/mapadmin migration dry-run` | 生成 v3 差异报告，目标数据不变 |
| `/mapadmin migration apply` | 再次预检后正式导入或继续冻结计划的部分写入 |
| `/mapadmin storage export <文件名>` | 异步导出完整快照到 `exports/`；文件名只含字母、数字、下划线或连字符 |

启动在发布 API 前自动继续 `world-recovery/` 中的请求。失败保留 PENDING/DELETING；恢复核对地图与目录身份。删除预检分别返回 `INVALID_MAP_STATE`（状态不允许）、`WORLD_HAS_PLAYERS`（图内有在线玩家）或 `RECOVERY_CONFLICT`（恢复请求操作类型或地图 UUID 冲突）；命令入口的状态检查同样返回 `INVALID_MAP_STATE`。目录被替换、出现符号链接或旧版本没有原始请求时停止并提示核查。六位确认码仍用于发起新的 `/mapadmin delete`，恢复仅继续已经持久化的删除。Paper 26 已登记维度读取共享主世界 `level.dat`；经典导入目录仍要求自身有效 `level.dat`。

事件读取使用不可变内存索引，写入仍同步持久化；LP 在后台执行和自动重试，返回 `Partial(PERMISSION_SYNC_PENDING)`，MMC 角色已经生效。全局 FIFO 的失败任务会延后后续投影。详细配置见 [STORAGE.md](STORAGE.md) 和 [MIGRATION.md](MIGRATION.md)。创建选项 `admin=` 同时接受用户名和 UUID。

`PlayerGateway.resolveId` 优先在线玩家和服务端缓存。未命中时先读取 `config/paper-global.yml` 中已启用的 Velocity/Bungee 代理的 `online-mode`，否则读取 `server.properties`；正版模式异步向 Mojang 补全 UUID，离线模式按 `OfflinePlayer:<用户名>` 计算 UUID。`WorldGateway` 只提供目录查询：先读取 `server.properties` 的 `level-name`，读取不到时使用 `Bukkit.getWorlds().get(0).getName()`；存在 `<level-name>/dimensions/minecraft` 时以此作为地图父目录，否则使用服务端工作目录。路径查询不会创建目录。创建和导入等操作统一由 `WorldLifeCycleService` 定义。

默认通知语言为 `zh_CN`，可在 `config.yml` 设置 `language`；内置语言有 `zh_CN` 和 `en_US`。启动时插件把内置语言复制到数据目录的 `langs/`，自定义翻译可编辑对应 YAML；选定语言文件缺失或格式错误会导致插件启动失败，缺失翻译键回退到中文。

## 控制台与 RCON 命令入口

所有生命周期与运维命令统一使用 `/mapadmin`：create、import、init、delete、load、recover、operations、migration、storage。`/world` 与 `/wtp` 只提供玩家地图操作。旧 `/world create|import|init|delete` 不再执行；创建/导入选项保持原格式，删除改用 `/mapadmin delete <地图>` 后输入 `/mapadmin delete <六位码>`。
