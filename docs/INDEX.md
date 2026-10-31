# 文档索引

先读根目录 [AGENTS.md](../AGENTS.md)，再按任务阅读。描述与实现冲突时，以源码为准并修正文档。

## 使用插件

| 读者 / 任务 | 文档 |
|---|---|
| 安装、配置、权限和日常运维 | [安装指南](README.md) |
| 玩家操作、控制台维护、补全与确认 | [命令手册](COMMANDS.md) |
| v3 数据升级 | [数据迁移](MIGRATION.md) |
| JSON/MySQL、备份、后端切换与故障恢复 | [存储](STORAGE.md) |

## 开发与维护

| 任务 | 阅读路径 |
|---|---|
| 任意代码修改 | [架构与调用关系](ARCHITECTURE.md) → [类职责与状态](CLASS_STATUS.md) |
| 外部插件接入、模型、角色或准入 | [API 指南](API_GUIDE.md) |
| v3 扩展插件升级 | [API 迁移](V3_TO_V4_API_MIGRATION.md) → [API 指南](API_GUIDE.md) |
| 命令、配置、地图规则（含默认世界传送、严格开关与 MV 权限补全） | [命令手册](COMMANDS.md) → [安装指南](README.md) |
| 生命周期、迁移或存储 | [架构](ARCHITECTURE.md)及对应的[存储](STORAGE.md) / [数据迁移](MIGRATION.md)专题 |
| 通知与翻译、创建/导入详细校验报错 | [消息规范](MESSAGES.md) → [命令手册](COMMANDS.md) |
| 测试、验收或审查 | [测试指南](TEST_CASES.md)及所审查领域的文档 |

修改类职责或实现状态时更新类清单；修改行为时更新对应指南。Markdown 放在 `docs/`，根目录仅保留 `AGENTS.md`；文档间使用相对链接，不保留重复说明和失效的阶段记录。
