# 存储与后端

## 配置与数据目录

配置资源：[`plugin/src/main/resources/config.yml`](../plugin/src/main/resources/config.yml)。默认 JSON 后端在插件数据目录保存 `maps.json`；MySQL 后端启动时自动建表，数据库和账户需由管理员预先创建。`storage-backend.txt` 记录选中的存储目标；若后端或 MySQL 目标变化，启动会拒绝继续，避免静默切换到另一份数据。

JSON 写入使用临时文件后替换；MySQL 使用 JDBC 和 InnoDB 外键约束。两个实现共享 plugin 内部的 `MapRepository`、`RoleRepository`、`OperationRepository` 接口。更换后端需先显式迁移数据，当前尚未提供迁移命令或工具。

## MySQL 表

| 表 | 内容 |
|---|---|
| `mm_groups` | 地图组 ID |
| `mm_maps` | 地图、设置 JSON、出生点 JSON；世界名规范化后唯一 |
| `mm_group_roles` | 组管理员和建筑者 |
| `mm_map_visitors` | 单地图访客 |
| `mm_operations` | 生命周期操作阶段和失败原因 |

地图访客授权关联地图并级联清理；仍被地图引用的组不能删除。`MapGroup.worldNames` 由地图记录计算，不独立重复存储。

## 边界

存储接口、配置类、JSON/SQL 实体和连接实现仅属于 `plugin`。API 模块不暴露存储细节。操作记录已持久化，但崩溃恢复尚未消费这些记录。生产 MySQL 集成、升级和性能行为尚未验证。
