# v3 自动迁移

## 启动触发

插件启动时检查 v4 数据目录的相邻插件目录 `MapManager-Core`。只有 `worlds.json` 和 `groups.json` 同时存在时才迁移；都不存在则跳过，只有一个存在则迁移失败并禁用插件。迁移完成后写 `v3-migration.done`，后续启动不再重复导入。

读取业务配置和开始迁移前，插件会用 v4 内置 `config.yml` 补齐现有文件缺少的节点，已有值和注释会保留；v3 的 `!MapManagerConfig` 标记会移除。正式迁移成功并写入完成标记后，会删除 v3 专用的 `global` 配置；预检或迁移失败时保留它。没有变更时不重写文件。配置 YAML 无效或节点类型与 v4 结构冲突时，插件会停止启动并保留原文件。

迁移在命令注册前运行。插件会把旧文件备份到 v4 数据目录的 `v3-source-backup/`。迁移失败会阻止命令注册并禁用插件。

## 数据来源与映射

| v3 来源 | v4 目标 |
|---|---|
| `worlds.json` 世界条目 | MMC 地图记录，状态为 `ACTIVE` |
| 世界 `group` | 地图组 ID；世界名和组关系交叉校验 |
| 世界 `physical`、`exploded` | 地图 `MapSettings` |
| 世界 `visitors` | 单地图 `VISITOR` 授权 |
| `groups.json` 的 `admins`、`builders` | 地图组 `ADMIN`、`BUILDER` 授权 |
| LP `default` 组中的公开节点 | 地图公开状态 |
| Multiverse 别名和出生点 | 地图展示名和出生点 |

玩家名解析为 UUID。迁移期间只读取旧 JSON、LP 公开节点和 MV 元数据；不会写 LP/MV，不需要调用 v3 插件。数据写入当前选择的 JSON 或 MySQL 仓储。

## 校验与限制

- JSON 必须可解析；地图名、组名、成员和布尔字段需符合约束。
- `groups.json` 的世界列表必须与 `worlds.json` 中的组关系一致；重复世界名、缺少组或无效条目会拒绝迁移。
- 玩家 UUID 必须全部解析成功；现存 MMC 地图/组冲突会中止。
- `migration.dry-run: true` 或 `/mapadmin migration dry-run` 只预检，生成 `v3-migration-report.json`：地图旧值、新值、动作、组、角色和目标快照；冲突也写入报告。
- 正式执行先备份源文件与完整目标快照 `v3-target-backup.json`，冻结含已解析 UUID 和当时 MV/LP 元数据的 `v3-migration-plan.json`；全部步骤完成后才写完成标记。
- 中断后的启动或 `/mapadmin migration apply` 按同一计划补齐缺失项，不重新解析身份、不覆盖冲突。源文件须与 `v3-source-backup/` 逐字节一致；源改变或目标冲突时停止。
- dry-run 不写源备份、计划或完成标记，现有地图服务仍会启用；正式执行再次预检。旧版本部分迁移但无冻结计划时仍需先人工核查目标。

实现入口：`plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/migration/`；启动装配位于 `MapManagerPlugin`。
