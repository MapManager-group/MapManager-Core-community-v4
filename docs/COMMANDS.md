# 命令手册

游戏内输入 `/world help [子命令]`；控制台或 RCON 输入 `mapadmin help [子命令]`。地图角色与默认世界规则见[安装指南](README.md)。

## 玩家命令

| 命令 | 使用条件与作用 |
|---|---|
| `/world tp <地图名>`、`/wtp <地图名>` | 准入通过后按需加载地图并传送 |
| `/world admin add\|remove <玩家名或UUID>` | 当前地图管理员；修改整个地图组的管理员 |
| `/world builder add\|remove <玩家名或UUID>` | 当前地图管理员；修改整个地图组的建筑者 |
| `/world visitor add\|remove <玩家名或UUID>` | 当前地图管理员；修改当前地图访客 |
| `/world admin info`、`/world builder info`、`/world visitor info` | 可进入当前地图的玩家；列出该角色的直接授权成员 |
| `/world public [true\|false\|info]` | 当前地图管理员；设置或查询公开状态 |
| `/world physics [true\|false\|info]` | 当前地图管理员；设置或查询方块物理 |
| `/world explosion [true\|false\|info]` | 当前地图管理员；设置或查询爆炸破坏 |
| `/world setname <展示名>` | 当前地图管理员；保存纯文本展示名并同步 MV 别名，支持空格 |
| `/world setspawn` | 当前地图管理员；将当前位置设为出生点 |
| `/world kick <在线玩家>` | 当前地图管理员；将同图玩家送到默认世界出生点 |

三个开关省略参数时查询当前值；`public` 也接受 `on/enable/yes`、`off/disable/no` 和 `status`。开启物理或爆炸先显示风险提示，再在 10 秒内执行相应命令：

```text
/world physics true confirm
/world explosion true confirm
```

确认仅对同一玩家、地图和设置有效；关闭不需确认。严格准入开启时，撤销角色或关闭公开会复核在线玩家，仍有其他有效角色的玩家保留。

## 控制台与 RCON

`/mapadmin` 不接受玩家（包括 OP）或命令方块调用。以下示例使用控制台格式；RCON 的结果和删除确认码同样输出到服务器控制台。

| 命令 | 作用 |
|---|---|
| `mapadmin create <地图名> [类型] [环境] [选项...]` | 创建并初始化地图 |
| `mapadmin import <地图名> [类型] [环境] [选项...]` | 导入并初始化已有世界 |
| `mapadmin init <地图名>` | 对已加载的 MMC 地图重新应用初始化规则 |
| `mapadmin delete <地图名>` | 申请删除，取得 6 位确认码 |
| `mapadmin delete <确认码>` | 在 10 秒内确认删除原地图 |
| `mapadmin load <地图名>` | 加载已登记的 ACTIVE 地图 |
| `mapadmin recover <地图名>` | 按保存的请求继续创建、导入或删除 |
| `mapadmin operations` | 列出所有非 COMPLETED 操作，包括 FAILED |
| `mapadmin migration dry-run` | 预检 v3 数据并写差异报告 |
| `mapadmin migration apply` | 执行 v3 迁移或按冻结计划恢复 |
| `mapadmin storage export <文件名>` | 导出到插件数据目录 `exports/<文件名>.json` |

删除须匹配配置的允许名称正则，禁止默认世界、首个 Bukkit 世界及仍有在线玩家的地图。确认绑定原地图 ID；码失效后重新申请。恢复还会核对目录身份，不能用新目录替换待删除目录。导出文件名须为 1–64 位字母、数字、下划线或连字符，不带路径和扩展名；导出会覆盖同名文件。

## 创建与导入

地图名须为 4–16 位小写字母、数字、下划线或连字符；组 ID 为 1–16 位相同字符，默认取地图名，禁止 `default`、`worldbase`。地图名还受内置保留名与配置正则限制。

内置保留名为 `cache/config/world/log/test/overworld/default/worldbase/apply/public/private`，以及以 `plugin/version/admin/mapmanager/builder/visitor/lib/mmc` 开头的名称。展示名去除颜色后须非空且不超过 255 字符，默认取地图名。

类型为 `flat`、`normal`、`void`；环境为 `normal`、`nether`、`the_end`。二者分别默认 `flat` 和 `normal`；要填环境必须先填类型。`void` 需要 VoidGen；`sift` 会明确返回不支持。

选项按空格分隔的 `键=值` 传入，顺序不限，重复键会被拒绝；引号不提供含空格值的分组能力。

| 选项 | 创建 | 导入 | 含义 |
|---|---|---|---|
| `alias=<文本>` | 是 | 是 | 展示名 |
| `group=<组ID>` | 是 | 是 | 地图组 |
| `admin=<玩家名或UUID>` | 是 | 是 | 新增管理员；省略不新增，已有组管理员仍生效 |
| `flat.height=<Y>` | 是 | 否 | FLAT 表层草方块的绝对 Y |
| `seed=<整数>` | 是 | 否 | 64 位种子 |
| `structures=true\|false` | 是 | 否 | 结构生成，默认 false |
| `biome=<生物群系>` | 是 | 否 | 生物群系 |
| `generator=<生成器>` | 是 | 否 | 自定义生成器 |
| `generator-settings=<参数>` | 是 | 否 | 生成器参数 |
| `property.<属性名>=<值>` | 是 | 否 | Multiverse 世界属性 |

`flat.height` 仅用于 FLAT，不能与 `generator-settings` 同时使用。使用内置生成器时，Y 按目标世界实际 `minHeight ≤ Y < maxHeight` 校验；出生点在草方块表层上方。`0` 就是 Y=0，不是层数。扩展高度须由目标世界已经生效的数据包提供。

```text
mapadmin create buildworld flat normal flat.height=0 alias=建筑地图 admin=Steve
mapadmin create nethermap normal nether group=buildworld admin=Steve
mapadmin import oldbuild normal normal group=buildworld admin=Steve
```

导入目录须直接位于服务端根目录，非符号链接，并有有效的 `level.dat`；高于当前服务端数据版本的存档被拒绝。导入不替换已有区块，类型和环境影响 MV 配置及后续区块生成。创建、导入与初始化的规则见[安装指南](README.md)。

## 自动补全

- `/world tp`、`/wtp` 按 MMC 准入推荐地图，包括尚未加载的地图；`access-control.strict-map-entry=false` 时，额外推荐拥有 `multiverse.access.<地图名>` 权限的已登记 ACTIVE 地图。候选去重并按输入前缀过滤；实际传送仍执行 MMC 准入检查。
- 角色移除仅推荐直接授权成员，踢人仅推荐同图在线玩家。
- 设置命令补全开关、确认参数和当前展示名；帮助补全子命令。
- `/mapadmin` 补全类型、已支持环境、选项键、现有组、在线管理员、生物群系、导入目录、地图、实际恢复请求及维护子命令。
- 新地图名、别名、种子、高度、导出文件名、自定义生成器和属性值由操作者填写；删除确认码人工输入。
