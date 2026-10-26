# MapManager Core v4

MapManager Core（MMC）是面向 Paper 服务器的地图管理插件。它管理地图元数据、地图角色和玩家准入，并通过 Multiverse-Core 执行世界操作，通过 LuckPerms 投影建造权限。

## 功能

- 创建地图、导入已有世界、初始化和安全删除地图。
- 按地图组管理管理员和建筑者，按单张地图管理访客。
- 设置地图公开状态、展示名、出生点、方块物理和爆炸破坏。
- 检查地图进入权限，并在传送、登录、重生和角色撤销后执行复核。
- 使用 JSON 或 MySQL 保存地图和角色数据；可将 v3 的地图与角色数据迁移到 v4。
- 向其他 Paper 插件注册 `MapManager` 服务。扩展插件接入方法见 [API_GUIDE.md](API_GUIDE.md)。

已实现 ACTIVE 地图按需加载、完整请求落盘和启动恢复，以及 LP 持久队列重试。当前未实现 PVP 开关、空闲卸载、龙蛋传送保护和全局方块物理/爆炸覆盖；旧 PENDING 记录缺少完整请求时需人工核查。

创建和导入地图均默认禁用物理和爆炸，执行 `init` 初始化也会关闭两项。设置命令成功时显示实际参数；开启物理或爆炸先显示风险说明，再于 10 秒内执行 `/world physics true confirm` 或 `/world explosion true confirm`。关闭无需确认，详见 [PLUGIN.md](PLUGIN.md)。

## 安装

### 环境要求

| 项目 | 要求 |
|---|---|
| 服务端 | Paper；插件 API 目标版本为 1.20 |
| Java | Java 21 |
| 必需插件 | LuckPerms、Multiverse-Core |
| 可选插件 | VoidGen；只有创建或导入 `void` 类型地图时需要 |

### 安装步骤

1. 停服并备份世界目录、LuckPerms 数据和 `plugins/MapManager-Core/`。
2. 在服务器安装并启用 LuckPerms 和 Multiverse-Core。
3. 获取 MapManager Core v4 JAR，放入服务器的 `plugins/` 目录。若从源码构建，见下方[开发](#开发)章节。
4. 启动服务器。首次启动会生成 `plugins/MapManager-Core/config.yml` 和所选存储文件。
5. 按需修改配置，完整重启服务器使配置生效。

如需把 v3 数据迁移到 v4，请在启动 v4 前确认旧 `worlds.json` 和 `groups.json` 均位于 `plugins/MapManager-Core/`，并备份旧数据。检测到两个文件后，插件会在启动时自动迁移并备份源文件；迁移错误会阻止插件启用。详细限制见 [MIGRATION.md](MIGRATION.md)。

## 配置

首次启动生成的默认配置：

```yaml
language: zh_CN

storage:
  type: json # json | mysql
  mysql:
    host: localhost
    port: 3306
    database: mapmanager
    username: mapmanager
    password: change-me
    use-ssl: true

deletion:
  allowed-world-name-regex: '.*_bak$'

creation:
  blocked-world-name-regex: '.*_bak$'

access-control:
  strict-map-entry: true
```

- `language` 支持 `zh_CN` 和 `en_US`。插件会把语言文件复制到数据目录的 `langs/`；自定义翻译可编辑对应文件。
- `storage.type` 默认为 `json`，文件为 `plugins/MapManager-Core/maps.json`。选择 `mysql` 时，数据库和账户需提前创建，插件只创建表。
- 存储后端及 MySQL 目标不会自动迁移数据。安装后若需要切换，请先使用专门的数据迁移方案；直接修改配置会被插件拒绝，以免误连空库。详见 [STORAGE.md](STORAGE.md)。
- `access-control.strict-map-entry` 默认为 `true`。设为 `false` 会停用外部传送、登录、重生及撤销角色后的自动准入检查；`/world tp` 仍执行 MMC 权限检查。
- 两个名称正则都匹配完整地图名。默认情况下，创建/导入会拒绝 `_bak` 结尾名称，`/mapadmin delete` 命令只允许 `_bak` 结尾地图，生命周期 API 不受删除正则限制。内置保留名称及名称格式限制始终生效。修改删除正则前请确认目标范围。
- 如需取消命令删除的 `_bak` 后缀限制，可将 `deletion.allowed-world-name-regex` 改为 `'.*'`。默认世界、首个 Bukkit 世界、在线玩家和目录边界检查仍然生效。

## 权限说明

Multiverse 默认世界始终允许进入，MMC 不向其投影 `worldbase` 建造权限；实际建造保护由现有保护插件执行。旧 MMC 角色和物理/爆炸设置在默认世界不生效。

MMC 不使用自定义 Bukkit 权限节点决定地图操作权限。地图准入以 MMC 中的角色和公开状态为准；OP 身份或 LuckPerms 的 `multiverse.access.<地图名>` 节点不能替代 MMC 地图角色。

| 角色/身份 | 范围 | 能力 |
|---|---|---|
| `ADMIN` | 地图组 | 管理该组地图的成员和设置；同时拥有建筑者、访客能力 |
| `BUILDER` | 地图组 | 进入组内地图，并获得 `worldbase` 组提供的建造权限 |
| `VISITOR` | 单张地图 | 进入被授予的地图，不获得建造权限 |
| 公开地图玩家 | 单张公开地图 | 任何玩家都可进入；不会自动创建 `VISITOR` 授权记录 |
| 控制台 / RCON | 全服操作 | 执行创建、导入、初始化和删除命令 |

建造权限由服务器管理员配置在 LuckPerms 的 `worldbase` 组中。MMC 负责将地图管理员/建筑者映射到地图组并投影到 LuckPerms；LuckPerms 不负责地图准入。已有 `worldbase` 组不会被插件覆盖，首次缺失时插件只创建该组并继承 `default`，不会替服务器配置具体建造权限。

组角色变更会先写入 MMC，再投影到 LuckPerms。若 MMC 已保存但 LuckPerms 更新失败，命令会报告部分完成；权限投影在持久队列中自动重试，重启后继续；待同步返回 Partial(PERMISSION_SYNC_PENDING)，MMC 角色已生效。

建议创建或导入地图时通过 `admin=<玩家名>` 指定首位管理员。若省略该参数，地图不会自动分配管理员；之后只有 MMC 地图管理员能在游戏内授予角色。

## 快速使用

1. 在控制台创建地图并指定管理员：

   ```text
   /mapadmin create buildworld flat normal alias=建筑地图 group=buildworld admin=Steve
   ```

2. 在地图组管理员所在的地图内授予角色：

   ```text
   /world builder add Alex
   /world visitor add Pat
   ```

3. 管理员可设置出生点、展示名和地图规则：

   ```text
   /world setspawn
   /world setname 建筑地图
   /world physics false
   /world explosion false
   /world public true
   ```

4. 有权进入地图的玩家传送：

   ```text
   /world tp buildworld
   /wtp buildworld
   ```

地图管理员/建筑者按地图组生效；访客授权只对当前地图生效。一个组中的地图共享管理员和建筑者，但各自有独立访客名单及地图设置。

## 命令

| 命令 | 使用者 | 说明 |
|---|---|---|
| `/mapadmin create <地图名> [生成器类型] [环境] [选项...]` | 控制台 / RCON | 创建并初始化地图；默认生成器为 `flat`，环境为 `normal` |
| `/mapadmin import <地图名> [生成器类型] [环境] [选项...]` | 控制台 / RCON | 导入服务端根目录下的已有世界；默认生成器为 `flat`，环境为 `normal` |
| `/mapadmin init <地图名>` | 控制台 / RCON | 对已加载的 MMC 地图重新应用初始化规则 |
| `/mapadmin delete <地图名>` | 控制台 / RCON | 申请删除并取得一次性确认码；随后运行 `/mapadmin delete <6位确认码>`，须在 10 秒内完成 |
| `/world tp <地图名>`、`/wtp <地图名>` | 玩家 | 准入通过后按需加载 ACTIVE 地图并传送 |
| `/world admin add\|remove <玩家名或UUID>` | 当前地图管理员 | 授予或移除当前地图组管理员 |
| `/world builder add\|remove <玩家名或UUID>` | 当前地图管理员 | 授予或移除当前地图组建筑者 |
| `/world visitor add\|remove <玩家名或UUID>` | 当前地图管理员 | 授予或移除当前地图访客 |
| `/world admin info`、`/world builder info`、`/world visitor info` | 可进入当前地图的玩家 | 查看当前地图直接授予的各角色成员 |
| `/world public [true\|false\|info]` | 当前地图管理员 | 修改或查看地图公开状态 |
| `/world physics [true\|false\|info]` | 当前地图管理员 | 修改或查看方块物理设置；关闭后阻止方块物理和落沙 |
| `/world explosion [true\|false\|info]` | 当前地图管理员 | 修改或查看方块及实体爆炸破坏设置 |
| `/world setname <展示名>` | 当前地图管理员 | 修改地图展示名和 Multiverse 别名 |
| `/world setspawn` | 当前地图管理员 | 将当前位置设为地图出生点 |
| `/world kick <在线玩家>` | 当前地图管理员 | 将同一地图中的目标玩家送到 Multiverse 默认世界出生点 |

生成器类型与维度环境是两个独立选项，创建和导入都可组合使用：生成器类型为 `flat`、`normal`、`void`，环境为 `normal`、`nether`、`the_end`、`sift`。`normal` 生成器使用原版标准地形，`flat` 生成平坦地形，`void` 需要 VoidGen。环境 `sift` 是预留值，当前会返回“不支持”错误，不会创建或导入地图。省略生成器和环境时分别默认为 `flat` 与 `normal`。

创建命令还支持以下选项：

地图名须为 4–16 位小写字母、数字、下划线或连字符；组 ID 须为 1–16 位相同字符，不能使用 `default` 或 `worldbase`。`void` 生成器需要安装 VoidGen。`flat.height` 仅适用于 flat，不能与 generator-settings 同时使用。flat.height 表示表层草方块的绝对 Y 坐标：0 对应 Y=0，-60 对应 Y=-60。生成时读取目标世界实际 minHeight（含）与 maxHeight（不含），范围为 minHeight ≤ Y < maxHeight，不固定限制为 -64～319 或 -2000～2000；破限高数据包须已在目标世界生效。旧世界已保存层数设置继续按原义读取，新请求改用绝对 Y。

| 选项 | 作用 |
|---|---|
| `alias=<文本>` | 地图展示名，默认地图名 |
| `group=<组ID>` | 地图组，默认地图名 |
| `admin=<玩家名或UUID>` | 首位地图管理员，可省略但地图将没有管理员 |
| `flat.height=<Y>` | 表层绝对 Y，实际范围 minHeight 至 maxHeight-1，支持生效的数据包扩展 |
| `seed=<数值>` | 世界种子 |
| `structures=true\|false` | 是否生成结构 |
| `biome=<生物群系>` | 设置生物群系 |
| `generator=<生成器>`、`generator-settings=<参数>` | Multiverse 生成器配置 |
| `property.<属性名>=<值>` | 设置 Multiverse 世界属性 |

导入命令同样可指定生成器类型和环境，另支持 `alias`、`group` 和 `admin`，不接受生成参数。导入不会替换存档已有区块；所选环境和生成器用于 Multiverse 配置及后续新生成区块。导入世界须直接位于服务端根目录，并包含有效的 `level.dat`；版本高于当前服务器的数据会被拒绝。

## 地图规则与准入

新建和导入地图均默认关闭方块物理和爆炸破坏，并设为私有。地图设置随地图记录保存；未登记到 MMC 的世界不受单地图方块物理/爆炸设置影响。坐垫、画、展示框（含荧光展示框）的支撑丢失和方块挤占破坏始终全局禁止，包括默认世界和未登记世界，与地图物理开关无关；主动拆除仍允许。

创建、导入和手动初始化会设置和平难度、创造模式、自动加载、出生点内存选项、生物生成分类及随机刻、火焰、天气、昼夜、生物破坏、生物生成和进度播报规则。公开地图 Multiverse 别名使用绿色 `&2`，私有地图使用深青色 `&3`；展示名保存为纯文本。

启用严格准入后，插件会检查玩家传送目标和实际进入的世界，也会检查登录、重生及撤销角色后的当前地图。无权进入时会尝试将玩家送回 Multiverse 默认世界出生点；无法安全返回时会断开玩家。关闭严格准入后，其他插件直接发起的传送不再由这组监听器拦截。

## 开发

### 构建与测试

项目使用 Java 21 和 Maven，包含两个模块：

- `api`：外部插件可使用的模型、接口和结果类型。
- `plugin`：Paper 插件入口、命令、事件、存储和 Multiverse/LuckPerms 实现。

在仓库根目录运行：

```shell
mvn test
mvn clean package
```

插件 JAR 构建输出为 `plugin/target/mapmanager-plugin-4.0.0-SNAPSHOT.jar`；将该文件复制到服务器的 `plugins/` 目录。Paper、LuckPerms 和 Multiverse-Core 是运行时依赖，不会随插件重复打包；MySQL 驱动及 API 等依赖由构建配置处理。

### 外部插件接入

扩展插件可通过 Bukkit `ServicesManager` 获取 `MapManager`。使用与服务端相同版本的 `mapmanager-api`，Maven 依赖设为 `provided`，并在 `paper-plugin.yml` 声明 `MapManager-Core` 服务依赖和 `join-classpath: true`。服务在迁移及启动初始化成功后异步注册，扩展插件应处理服务尚未就绪和撤销事件。

完整依赖示例、服务获取示例和 API 线程限制见 [API_GUIDE.md](API_GUIDE.md)。已有 v3 扩展插件的依赖和接口迁移步骤见 [V3_TO_V4_API_MIGRATION.md](V3_TO_V4_API_MIGRATION.md)，逐项功能差异见 [API_INVENTORY.md](API_INVENTORY.md)。公开 API 不暴露 repository、JSON/MySQL 实体、LuckPerms 或 Multiverse 实现对象。新增或调整存储及世界生命周期行为时，应遵守 [ARCHITECTURE.md](ARCHITECTURE.md) 中的模块边界。

## 相关文档

- [插件配置与运行行为](PLUGIN.md)
- [权限、API 与 v3 功能对照](API_INVENTORY.md)
- [外部插件 API 接入](API_GUIDE.md)
- [v3 数据迁移说明](MIGRATION.md)
- [存储后端说明](STORAGE.md)
- [自动化测试与验收用例](TEST_CASES.md)

## 运维与故障恢复

以下命令仅控制台或 RCON 可执行，操作结果与删除确认码仍输出至服务器控制台；创建、导入、初始化和删除也统一使用 `/mapadmin`，参数见上方命令表。旧 `/world` 生命周期子命令已移除：

| 命令 | 用途 |
|---|---|
| `/mapadmin load <地图>` | 加载已登记 ACTIVE 地图 |
| `/mapadmin recover <地图>` | 按已保存请求继续创建/导入/删除 |
| `/mapadmin operations` | 查看未完成与失败操作 |
| `/mapadmin migration dry-run` | 预检 v3 数据，生成差异报告，不写目标 |
| `/mapadmin migration apply` | 备份并迁移，失败后使用冻结计划恢复 |
| `/mapadmin storage export <文件名>` | 导出地图、角色和全部操作快照 |

创建失败且确认未产生世界及目录时，会释放 PENDING 占位和恢复请求，允许重新申请同名地图；重启同样清理有完整请求及 FAILED 记录的旧失败占位。已有世界/目录的失败操作保留恢复数据。重启自动恢复其余有完整请求的操作；删除恢复会核对目录身份，拒绝被替换的目录。LP 投影失败在后台自动重试，MMC 角色立即生效；全局 FIFO 中持续失败的任务会延后后续任务。导出仅含业务数据，完整备份须保留插件数据目录、世界文件和 MySQL 数据；显式切换存储成功后自动清空 `storage.migrate-from` 并保存配置；保存失败会保留完成标记供重启收尾。详见 [STORAGE.md](STORAGE.md)，迁移见 [MIGRATION.md](MIGRATION.md)。
