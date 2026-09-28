# 文档索引与任务阅读规则

开始任务前先读仓库根目录的 [`AGENTS.md`](../AGENTS.md)，然后按任务范围读取以下文档。跨领域任务应组合阅读相关项；源码状态不确定时直接核对实现。

## 按任务阅读

| 任务类型 | 必读文档 |
|---|---|
| 任意代码或功能任务 | [`CLASS_STATUS.md`](CLASS_STATUS.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`STRUCTURE.md`](STRUCTURE.md) |
| API、模型、角色或准入 | [`CLASS_STATUS.md`](CLASS_STATUS.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`API_INVENTORY.md`](API_INVENTORY.md) |
| `/world` 命令、配置或玩家用法 | [`PLUGIN.md`](PLUGIN.md)、[`README.md`](README.md)、[`CLASS_STATUS.md`](CLASS_STATUS.md) |
| v3 数据迁移 | [`MIGRATION.md`](MIGRATION.md)、[`CLASS_STATUS.md`](CLASS_STATUS.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md) |
| JSON/MySQL 存储、schema 或后端切换 | [`STORAGE.md`](STORAGE.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`STRUCTURE.md`](STRUCTURE.md) |
| 通知、错误文本或翻译 | [`MESSAGES.md`](MESSAGES.md)、[`PLUGIN.md`](PLUGIN.md) |
| 仅审阅/文档任务 | 本索引及用户点名的文档；涉及实现现状时再读 `CLASS_STATUS.md` 和 `STRUCTURE.md` |
| 功能验收/测试设计 | [`TEST_CASES.md`](TEST_CASES.md)、[`CLASS_STATUS.md`](CLASS_STATUS.md)、相关专题文档 |

## 文档职责

- [`CLASS_STATUS.md`](CLASS_STATUS.md)：类型清单、当前实现状态、主要待办。修改类职责或状态时维护。
- [`ARCHITECTURE.md`](ARCHITECTURE.md)：架构边界、设计原则和未完成事项。
- [`STRUCTURE.md`](STRUCTURE.md)：目录、入口、关键调用关系。
- [`API_INVENTORY.md`](API_INVENTORY.md)：v3 能力到 v4 API/实现的对照。
- [`README.md`](README.md)：英文用户说明与命令用法。
- [`PLUGIN.md`](PLUGIN.md)：插件安装配置、数据结构、命令和运行行为，包括地图准入、公开状态、显示名、出生点及物理/爆炸设置。
- [`MIGRATION.md`](MIGRATION.md)：v3 自动迁移触发、字段映射、保护和限制。
- [`STORAGE.md`](STORAGE.md)：存储配置、文件/表结构、后端约束。
- [`MESSAGES.md`](MESSAGES.md)：语言选择、资源位置、MiniMessage 和翻译维护。
- [`PROJECT_NOTES.md`](PROJECT_NOTES.md)：长期项目约定和阶段记录；可能随源码演进，执行前核对源码。
- [`TEST_CASES.md`](TEST_CASES.md)：按当前实现整理的启动、命令、API、存储、迁移和适配器验收用例；不代表已经执行。

## 文档维护约定

- 所有项目 Markdown 放在本目录；根目录 `AGENTS.md` 是唯一例外，用于强制任务入口。
- 文档间使用相对链接。移动或新增文档后检查链接并更新本索引。
- 以当前源码和配置为准；不把 v3 能力、目标设计或待办写成 v4 已实现事实。
