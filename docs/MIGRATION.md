# v3 数据迁移

这是服务器数据升级流程；扩展插件改代码见 [API 迁移](V3_TO_V4_API_MIGRATION.md)。

## 升级步骤

1. 停服，备份世界、LuckPerms 和整个 `plugins/MapManager-Core/`，移除 v3 插件 JAR。
2. 保留数据目录中的旧 `worlds.json`、`groups.json`，安装 v4 及其依赖。
3. 建议先设置 `migration.dry-run: true` 启动，检查 `v3-migration-report.json` 的旧值、新值、动作、角色和冲突。
4. 停服，将 dry-run 改回 false 后启动执行。完成后出现 `v3-migration.done`；再核对地图、角色、公开状态和出生点。

启动在同一插件数据目录检查两个旧 JSON：都没有则跳过，只有一个则失败并禁用插件，有完成标记则不重复导入。启动预检或正式迁移失败均会阻止命令和 API 注册；dry-run 通过后可启用现有地图服务，但不写入迁移目标或完成标记。

插件先用 v4 默认配置补齐缺少节点，保留已有值和注释，移除旧 `!MapManagerConfig` 标记。YAML 无效或节点类型冲突时停止并保留原文件；成功迁移后才移除旧 `global` 配置。

## 数据映射

| 来源 | 目标 |
|---|---|
| `worlds.json` 世界与 group | ACTIVE 地图及所属地图组 |
| `physical`、`exploded` | 单地图物理与爆炸设置；不按新建地图默认值覆盖旧值 |
| 世界 visitors | 单图 VISITOR |
| `groups.json` admins/builders | 组 ADMIN/BUILDER |
| LP default 组公开节点 | 地图公开状态 |
| Multiverse 别名、出生点 | 展示名、出生点 |

玩家名先全部解析为 UUID，再开始写目标。迁移读取 LP/MV 元数据，不修改它们；之后正常启动的权限投影会按 MMC 状态同步 LP。旧全局物理/爆炸覆盖不迁移为 v4 全局规则。

## 校验与中断恢复

- JSON、字段类型、名称、组关系和成员须有效；重复地图、缺少组或未解析 UUID 会停止迁移。计划构建后输出报告，目标冲突也记录其中。
- 正式执行保存 `v3-source-backup/`、完整目标快照 `v3-target-backup.json` 和冻结计划 `v3-migration-plan.json`，最后写完成标记。
- 中断后启动或 `mapadmin migration apply` 按冻结计划幂等补齐，不重新解析 UUID 或覆盖冲突；源文件必须与首次备份逐字节一致。
- `mapadmin migration dry-run` 可在已启用实例预检。已有冻结计划时仍检查源备份；不要依赖 dry-run 来忽略源变化。
- 旧版本若已部分迁移却没有冻结计划，先人工核查目标和备份，不能删除标记后盲目重跑。

迁移成功并验收后，旧 JSON 和迁移备份可按服务器备份策略移出运行目录；保留完成标记，避免再次触发导入。实现入口为 [V3Migration](../plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/migration/V3Migration.java)。
