# v3 自动迁移

## 启动触发

插件启动时检查 v4 数据目录的相邻插件目录 `MapManager-Core`。只有 `worlds.json` 和 `groups.json` 同时存在时才迁移；都不存在则跳过，只有一个存在则迁移失败并禁用插件。迁移完成后写 `v3-migration.done`，后续启动不再重复导入。

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
- 当前没有 dry-run、目标库备份和详细差异报告。
- 完成标记在所有写入完成后才创建。若迁移中途失败，可能已有部分记录写入；重试前需核查目标存储，以防冲突或重复角色授予。

实现入口：`plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/migration/`；启动装配位于 `MapManagerPlugin`。
