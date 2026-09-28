# 仓库任务入口

每次开始本仓库任务时，先读本文件，再按任务类型阅读 [`docs/INDEX.md`](docs/INDEX.md) 指定的文档。若任务跨多个领域，阅读所有相关文档。文档与源码冲突时以当前源码为准，并在同一任务中修正文档。

## 不可违反的项目边界

- `api` 只放公开模型和接口；实现与存储放在 `plugin`。`MapRepository`、`RoleRepository`、`OperationRepository` 不得移入公开 API。
- MMC 是地图元数据、角色和所有地图准入判断的权威来源。LuckPerms 只投影建造权限；Multiverse-Core 执行世界操作。
- `WorldGateway` 只查询存储目录；世界创建、导入、初始化、删除等由 `WorldLifeCycleService` 编排。
- 严格遵守用户任务中明确的功能边界，不顺带改动无关模块。
- 通知和面向用户的错误使用 `Messages`、MiniMessage 与语言资源；默认语言 `zh_CN`，新增可见文案需同步 `langs/zh_CN.yml` 和 `langs/en_US.yml`。不使用 ChatColor。

## 文档维护

- 类职责或完成状态变化时，同步更新 [`docs/CLASS_STATUS.md`](docs/CLASS_STATUS.md)。
- 架构边界、目录调用关系、命令/配置、迁移或存储行为变化时，更新对应专题文档及 [`docs/INDEX.md`](docs/INDEX.md)。
- 新增或移动 Markdown 时，将它放在 `docs/` 并更新索引中的任务阅读路径；根目录仅保留本文件作为仓库入口。
- 记录“已实现”前核对当前源码；清楚区分已实现、部分实现、占位和设计目标。
