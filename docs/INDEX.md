# 文档索引与任务阅读规则

开始任务前先读仓库根目录的 [`AGENTS.md`](../AGENTS.md)，然后按任务范围读取以下文档。跨领域任务应组合阅读相关项；源码状态不确定时直接核对实现。

## 按任务阅读

| 任务类型 | 必读文档 |
|---|---|
| 任意代码或功能任务 | [`CLASS_STATUS.md`](CLASS_STATUS.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`STRUCTURE.md`](STRUCTURE.md) |
| API、模型、角色或准入 | [`CLASS_STATUS.md`](CLASS_STATUS.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`API_INVENTORY.md`](API_INVENTORY.md)、[`API_GUIDE.md`](API_GUIDE.md) |
| v3 外部插件迁移到 v4 | [`V3_TO_V4_API_MIGRATION.md`](V3_TO_V4_API_MIGRATION.md)、[`API_INVENTORY.md`](API_INVENTORY.md)、[`API_GUIDE.md`](API_GUIDE.md) |
| `/world`、`/mapadmin` 命令、配置或玩家用法 | [`PLUGIN.md`](PLUGIN.md)、[`README.md`](README.md)、[`CLASS_STATUS.md`](CLASS_STATUS.md) |
| v3 预检、迁移或中断恢复 | [`MIGRATION.md`](MIGRATION.md)、[`CLASS_STATUS.md`](CLASS_STATUS.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md) |
| JSON/MySQL 存储、缓存、恢复备份或显式后端切换 | [`STORAGE.md`](STORAGE.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`STRUCTURE.md`](STRUCTURE.md) |
| 通知、错误文本或翻译 | [`MESSAGES.md`](MESSAGES.md)、[`PLUGIN.md`](PLUGIN.md) |
| 仅审阅/文档任务 | 本索引及用户点名的文档；涉及实现现状时再读 `CLASS_STATUS.md` 和 `STRUCTURE.md` |
| 功能验收/测试设计 | [`TEST_CASES.md`](TEST_CASES.md)、[`CLASS_STATUS.md`](CLASS_STATUS.md)、相关专题文档 |

## 文档职责

- [`CLASS_STATUS.md`](CLASS_STATUS.md)：类型清单、当前实现状态、主要待办。修改类职责或状态时维护。
- [`ARCHITECTURE.md`](ARCHITECTURE.md)：架构边界、设计原则和未完成事项。
- [`STRUCTURE.md`](STRUCTURE.md)：目录、入口、关键调用关系。
- [`API_INVENTORY.md`](API_INVENTORY.md)：v3 能力到 v4 API/实现的对照。
- [`API_GUIDE.md`](API_GUIDE.md)：外部 Paper 插件依赖、获取及调用 v4 API 的指南。
- [`V3_TO_V4_API_MIGRATION.md`](V3_TO_V4_API_MIGRATION.md)：v3 外部插件迁移到 v4 的依赖、服务获取、调用替换与行为差异。
- [`README.md`](README.md)：中文用户介绍、安装、权限、命令、配置和开发接入。
- [`PLUGIN.md`](PLUGIN.md)：插件安装配置、数据结构、命令和运行行为，包括准入、设置、控制台 create/import/init/delete、维护命令、按需加载和生命周期恢复。
- [`MIGRATION.md`](MIGRATION.md)：v3 自动迁移触发、字段映射、保护和限制。
- [`STORAGE.md`](STORAGE.md)：存储配置、文件/表结构、后端约束。
- [`MESSAGES.md`](MESSAGES.md)：语言选择、资源位置、MiniMessage 和翻译维护。
- [`PROJECT_NOTES.md`](PROJECT_NOTES.md)：长期项目约定和阶段记录；可能随源码演进，执行前核对源码。
- [`TEST_CASES.md`](TEST_CASES.md)：自动化单元测试映射，以及需 Paper/依赖服务的启动、命令、API、存储和迁移验收用例。

## HTTP 测试环境验收

- [2026-10-01 MMW / Tuff HTTP 测试报告](HTTP_TEST_REPORT_2026-10-01.md)：32 个 HTTP 操作的实际覆盖、已发现问题、恢复结果与未完成流程；[脱敏请求证据](HTTP_TEST_EVIDENCE_2026-10-01.jsonl)。

## 文档维护约定

- 所有项目 Markdown 放在本目录；根目录 `AGENTS.md` 是唯一例外，用于强制任务入口。
- 文档间使用相对链接。移动或新增文档后检查链接并更新本索引。
- 以当前源码和配置为准；不把 v3 能力、目标设计或待办写成 v4 已实现事实。
