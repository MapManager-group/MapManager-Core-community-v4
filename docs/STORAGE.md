# 存储与恢复

配置见 [config.yml](../plugin/src/main/resources/config.yml)。默认 JSON；MySQL 数据库和账户须提前创建，插件自动建表。

## 数据与备份

以下路径均相对 `plugins/MapManager-Core/`：

| 文件 / 目录 | 用途 |
|---|---|
| `maps.json` | JSON 后端的组、地图、角色和操作，格式版本 1 |
| `storage-backend.txt` | 已选择的后端及 MySQL 目标指纹 |
| `world-recovery/<world>.json` | 创建/导入/删除的完整请求、原地图身份；删除还含目录身份和成员快照 |
| `permission-sync.json` | 未完成 LP 投影 FIFO |
| `permission-members.json` | 历史投影成员，启动补偿撤权 |
| `exports/<文件名>.json` | `mapadmin storage export <文件名>` 的业务快照 |

快照包含空组、地图、角色和全部操作，不含世界、恢复请求和 LP 队列。完整备份应停服保存世界、LP、整个插件数据目录及所用 MySQL 数据；没有通用快照导入命令，恢复备份需保持这些数据一致，不要只回滚地图记录或删除日志。

JSON 临时文件替换成功后才发布状态，未知格式拒绝读取。MySQL 五表为 `mm_groups`、`mm_maps`、`mm_group_roles`、`mm_map_visitors`、`mm_operations`；世界名规范化后唯一，访客随地图级联删除，空组删除级联清理组角色，仍有地图的组不能删除。

地图/组/角色读取使用不可变缓存，持久写成功后更新；离线修改文件后须重启。仓储及后端实现仅属于 plugin，不是公开 API。

## 切换 JSON / MySQL 或 MySQL 目标

直接修改后端或 MySQL 主机、端口、数据库会被拒绝。切换时：

1. 停服并完整备份，确保源和目标均无外部写入；准备空目标或与源精确一致的部分目标。
2. 设置目标 `storage.type`、`storage.mysql.*`，在 `storage.migrate-from` 填原类型 `json` 或 `mysql`。
3. 来源为 MySQL 时填写 `storage.migration-source.mysql.*`；源指纹须匹配原后端标记。MySQL 换目标也走此流程。
4. 启动保存 `storage-transfer.json` 源快照，检查目标无额外或不一致记录，再幂等补齐；核对完整目标后写 `storage-transfer-completed.txt`、更新后端标记并归档迁移备份。
5. 自动清空 `storage.migrate-from` 并原子保存配置，最后清除完成标记。旧连接配置按需自行清理。

源数据与备份不一致或目标冲突时停止，不覆盖目标；排除冲突后继续同一快照。MySQL 切回 JSON 时，旧 `maps.json` 若不是源快照子集，先在备份后移出运行目录。配置保存失败会保留完成标记并阻止启动，修复写入问题后重启即可收尾，不需重复制数据。不要手动删除迁移标记来绕过检查。

## 生命周期失败处理

先执行 `mapadmin operations`：它包含 FAILED 和其他非 COMPLETED 操作；内部 `unfinished()` 不包含 FAILED。再按地图处理：

| 现场 | 处理 |
|---|---|
| ACTIVE 已登记但未加载 | `mapadmin load <地图>`，或由正常传送按需加载 |
| 有完整创建/导入/删除请求 | `mapadmin recover <地图>`；启动也自动恢复可恢复请求 |
| 创建失败且没有运行世界、目录 | 自动释放 PENDING 和请求，可重新创建；保留 FAILED 审计、组和角色 |
| 失败后已有世界/目录，或中断尚未明确失败 | 保留完整请求，排查原错误后恢复，不删除占位强行重建 |
| 旧 PENDING 没有完整请求 | 人工核查世界和备份，不能猜原管理员或生成参数 |
| 删除目录已被替换 | 拒绝恢复；核对备份与目录身份，不继续删新目录 |

删除在元数据已移除的收尾阶段仍检查撤权、删组或移除继承结果；失败保留请求和组角色供重试。投影已入队但未完成时返回 PERMISSION_SYNC_PENDING。

LP 失败按持久 FIFO 自动重试，队首持续失败会阻塞后续投影；保留队列和历史成员文件，处理依赖或连接故障。MMC 准入已按新角色生效，不代表旧 LP 建造权限已撤销。v3 数据恢复另见[数据迁移](MIGRATION.md)。
