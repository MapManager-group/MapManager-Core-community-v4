# 架构与调用关系

## 模块边界

- `api`：公开模型、服务接口、结果类型；不暴露仓储、连接、JSON/SQL 实体或 LP/MV 实现。
- `plugin`：Paper 入口和实现。MMC 保存地图、角色、公开状态并决定准入；LuckPerms 只投影建造权限，Multiverse-Core 执行世界操作。
- `WorldGateway` 只查目录；世界变更统一由 `WorldLifeCycleService` 编排。

```text
api/src/main/java/studio/lorian/mapmanager/api/
  MapManager.java       服务入口
  model/                请求与不可变快照
  result/               操作名、失败码、结果
  service/              业务接口
plugin/src/main/java/studio/lorian/mapmanager/plugin/
  MapManagerPlugin.java Paper 装配与启停
  impl/                 服务实现
  internal/
    command/ listener/  命令和事件入口
    migration/          v3 预检、冻结计划与恢复
    notification/       语言、渲染与通知
    permission/ player/ LP 投影队列与玩家适配
    storage/ world/     仓储、缓存、恢复日志及世界工具
plugin/src/main/resources/ 配置、Paper 描述和双语资源
plugin/src/test/          单元测试及隔离服务器/数据库验收
```

具体类型见[类清单](CLASS_STATUS.md)，使用方法见[命令](COMMANDS.md)与 [API](API_GUIDE.md)。

## 启动与关闭

1. `MapManagerPlugin` 补齐配置、加载语言，执行显式后端切换并装配 `CachedStorage`、服务及 LP 持久队列；缺少必需依赖或配置失败则禁用。
2. 注册设置、准入监听器。MMC 先于 MV 启用，以便 MV 加载世界时解析内置生成器；v3 迁移等待 MV 启用。
3. 迁移和异步 `worldbase` 初始化完成后，回到主线程恢复生命周期、补偿历史投影成员、复核在线玩家，注册命令并发布 `MapManager` 服务。
4. 关闭时撤销服务注册并关闭队列，未完成任务留在磁盘。扩展插件须跟踪服务就绪与撤销。

默认世界先取首个 Bukkit 世界，再于 MV 启用后取其默认世界，缓存到重启。该世界准入放行、角色和单图设置豁免；权限投影清除它的 worldbase 上下文。其余管理与全局实体保护规则见[安装指南](README.md)。

## 主要调用路径

| 入口 | 调用路径 |
|---|---|
| 玩家传送 | `MapWorldCommand` → `MapTravelServiceImpl` → `MapAccessServiceImpl` → 生命周期 load → 玩家传送 |
| 外部传送、登录、重生 | `MapAccessListener` → 准入；拒绝时返回默认世界，不能安全返回则断开 |
| 角色修改 | 命令授权和身份解析 → `MapRoleServiceImpl` → 缓存仓储持久化 → LP 队列；命令另触发在线准入复核 |
| 地图设置 | 命令授权 → `MapSettingsServiceImpl` → 仓储；展示名/公开状态同步 MV 别名，出生点同步 Bukkit/MV |
| 创建、导入、删除 | `MapAdminCommand` → `WorldLifeCycleServiceImpl` → 完整请求、操作阶段 → MV/文件操作 → 元数据与权限清理 |
| 物理和爆炸事件 | `MapSettingsListener` → 缓存地图设置；实体支撑保护独立执行 |

角色按 ADMIN → BUILDER → VISITOR 继承；组角色覆盖组内地图，访客只属于单图。公开不是角色，LP 节点不参与准入。`strict-map-entry=false` 停用准入监听器，不停用传送服务自身的检查。

## 状态、恢复与持久化

创建/导入先保存 PENDING，完成后 ACTIVE；删除标为 DELETING，成功后移除记录。ARCHIVED、DELETED 仅为预留状态，没有归档用例。

操作阶段为 STARTED、WORLD_CHANGED、DATA_SAVED、COMPLETED、FAILED。阶段说明进度，完整请求保存原参数和地图身份；二者共同恢复，不能凭 PENDING 猜生成参数。`unfinished()` 排除 COMPLETED 与 FAILED，维护命令另列所有非 COMPLETED 记录。

JSON 使用临时文件替换，MySQL 使用 JDBC；`CachedStorage` 在持久化成功后发布不可变地图/组/角色索引，事件读取不做文件/JDBC I/O，也不等待写锁。操作记录查询仍访问底层仓储，写入仍同步持久化。禁止绕过缓存写底层仓储。详细文件、后端切换和失败收尾见[存储](STORAGE.md)。

LP 投影全局 FIFO、后台执行和退避重试；历史成员账本补偿撤权中断。入队成功不等于投影完成，MMC 角色先行生效，返回 Partial 告知同步待完成。队首故障可延后其他地图组。

内置 FLAT 生成器处理非主世界平坦地形及绝对表层 Y，持久 ID 保存生成设置，按世界实际高度验证；VOID 使用 VoidGen。导入严格使用服务端根目录，目录查询则兼容实际已加载世界位置，二者不能互相替代。

## 实现约定

外部命令字符串、公开请求和持久文件在各自边界校验一次，内部传递已解析的类型。异步身份解析后的授权复核检查当时的权限，删除目录身份检查保护实际目标；这两者不属于重复类型校验。

主流程保留正常编排，解析、校验和细节交给现有子函数；不增加猜测竞态的协议或无人使用的抽象。注释只解释不明显的约束，JavaDoc 写清关键前提、参数和结果。测试调用真实实现，外部失败才用适配器替身。可见消息遵循[消息规范](MESSAGES.md)。

API 写操作由调用方授权；线程、结果及在线会话责任见 [API 指南](API_GUIDE.md)。当前未实现能力统一列在[安装指南](README.md)，不维护已完成的阶段待办。
