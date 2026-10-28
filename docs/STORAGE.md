# 存储与后端

## 配置与数据目录

配置资源：[`plugin/src/main/resources/config.yml`](../plugin/src/main/resources/config.yml)。默认 JSON 后端在插件数据目录保存 `maps.json`；MySQL 后端启动时自动建表，数据库和账户需由管理员预先创建。`storage-backend.txt` 记录选中的存储目标；若后端或 MySQL 目标变化，启动会拒绝继续，避免静默切换到另一份数据。

JSON 写入使用临时文件后替换；MySQL 使用 JDBC 和 InnoDB 外键约束。两个实现共享 plugin 内部的 `MapRepository`、`RoleRepository`、`OperationRepository` 接口。更换后端通过下述显式启动迁移；`/mapadmin storage export <文件名>` 将完整快照保存到 `exports/<文件名>.json`，包含空组、地图、角色和所有操作。导出快照不替代整个插件目录备份；恢复请求和 LP 队列也需保留。

## MySQL 表

| 表 | 内容 |
|---|---|
| `mm_groups` | 地图组 ID |
| `mm_maps` | 地图、设置 JSON、出生点 JSON；世界名规范化后唯一 |
| `mm_group_roles` | 组管理员和建筑者 |
| `mm_map_visitors` | 单地图访客 |
| `mm_operations` | 生命周期操作阶段和失败原因 |

地图访客授权关联地图并级联清理；仍被地图引用的组不能删除；删除空组时，JSON 与 MySQL 均级联移除该组 ADMIN/BUILDER 授权。`MapGroup.worldNames` 由地图记录计算，不独立重复存储。

## 边界

存储接口、配置类、JSON/SQL 实体和连接实现仅属于 `plugin`。API 模块不暴露存储细节。操作阶段与 `world-recovery/` 完整请求共同用于生命周期恢复。主类通过 `CachedStorage` 暴露地图/组/角色不可变索引；高频读取不执行 JDBC/文件 I/O，也不等待写锁。持久化写成功后才发布新快照，禁止绕过缓存直接修改底层仓储；离线改文件后需重启。

## 显式后端切换

1. 停服并备份整个插件数据目录；准备新的独立 MySQL 数据库，或空的 JSON 目标。
2. 修改 `storage.type` 与新目标的 `storage.mysql`，在 `storage.migrate-from` 填原后端 `json` 或 `mysql`。
3. 原后端为 MySQL 时，在 `storage.migration-source.mysql` 填原连接。源指纹必须与 `storage-backend.txt` 一致；同为 MySQL 的不同目标也按此流程迁移。
4. 启动先保存 `storage-transfer.json` 完整源快照，目标只能为空或为该快照的精确子集。冲突检查通过后幂等补齐数据，核对完整目标一致后原子更新后端标记。
5. 成功后以 `storage-transfer-completed.txt` 记录已核对的目标，再更新后端标记并归档迁移备份。随后自动将 `storage.migrate-from` 设为空字符串，原子保存到 `config.yml`，最后移除完成标记；不再使用的原连接配置由管理员自行清理。

收尾只处理与当前目标匹配的完成标记；普通启动、未完成或目标不匹配的迁移不清空来源配置。若配置保存失败，恢复内存中的来源值并保留完成标记，启动报错；修复写入问题后重启会重试保存，无需重新复制已迁移数据。

数据迁移失败时标记保持原目标；修复连接/写入问题后继续同一份快照。源变化或目标有额外/不一致记录时停止，不覆盖目标。由 MySQL 切回 JSON 时，原有 `maps.json` 若并非当前快照的子集需先归档，不能直接覆盖。迁移时两个后端都不能由外部进程继续写入。

## 恢复与权限队列

- `world-recovery/<world>.json`：副作用前保存原地图 ID、请求、所有者、生成参数；删除包含目录身份和成员快照。完成后清理；失败创建确认没有世界及目录时，释放 PENDING 占位并删除该恢复请求，保留 FAILED 操作审计和地图组角色，启动同样处理旧失败记录。其他失败和未明确失败的中断请求继续保留。
- `permission-sync.json`：LP 未完成 FIFO，后台执行和自动重试；停服保留队列。
- `permission-members.json`：历史投影成员，启动时按 MMC 状态补偿入队前中断的撤权。
- JSON 当前版本为 1；未知格式拒绝读取。MySQL 当前五表没有新增列，迁移快照版本为 1。

实际验证结果与适用版本见 [TEST_CASES.md](TEST_CASES.md)；生产负载和数据库大版本升级仍需独立验收。

删除恢复在地图元数据已移除的收尾分支，也须确认权限撤销、删除组或移除世界继承成功后才清理请求。返回 false 时保留组角色与请求供重试；入持久队列但未执行完时返回 PERMISSION_SYNC_PENDING。
