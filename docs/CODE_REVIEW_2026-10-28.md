# MMC v4 代码审查与修复（2026-10-28）

版本由 `4.0.1-SNAPSHOT` 更新为 `4.0.2-SNAPSHOT`。审查覆盖 api/plugin 的 69 个生产 Java 文件、双语资源、配置、Maven 打包和现有测试/集成入口。公开 API 源码没有修改，接口、模型、构造器和结果码保持原签名；现有命令及参数继续可用，新增 help 只提供说明。未修改 v3 或其他项目。

## 已修复的问题

P2 表示可触发的逻辑、权限或数据一致性问题；P3 表示用法和反馈缺口。严重程度按本仓库入口及触发条件评估，不把插件内部受信任调用等同于远程攻击面。

| 级别 | 触发条件与原问题 | 修复与验证 |
|---|---|---|
| P2 | 删除已移除地图记录后继续恢复，权限适配器返回 false 时仍删除恢复请求并报告成功，可能残留建造权限且失去恢复线索 | [WorldLifeCycleServiceImpl](../plugin/src/main/java/studio/lorian/mapmanager/plugin/impl/WorldLifeCycleServiceImpl.java) 的收尾检查撤销成员、删组、移除继承结果；失败保留请求与组角色，入队但未完成返回 Partial。DeletedPlanRecoveryTest 覆盖三个失败分支和重试 |
| P2 | `/world public false` 保存后，原先仅靠公开状态进入的在线玩家没有立即接受复核 | [MapWorldCommand](../plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/command/MapWorldCommand.java) 在保存成功或别名同步部分完成后调用现有严格准入复核；不影响仍有角色的玩家。关闭严格模式仍按既有配置不强制移出 |
| P2 | `isCompatible(String)` 在验证世界名之前解析和读取路径，已有的绝对路径、嵌套路径或规范化路径可能被当成地图名接受 | [WorldVersionServiceImpl](../plugin/src/main/java/studio/lorian/mapmanager/plugin/impl/WorldVersionServiceImpl.java) 先检查单个目录名；WorldVersionServiceImplTest 使用真实 NBT 文件覆盖合法路径和非法名称。Path 重载仍支持显式目录查询，此项不是 API 文件读取权限隔离机制 |
| P2 | 玩家当前已在默认世界，但重生目标指向无权进入的地图，默认出生点因与当前世界相同被拒绝，导致不必要断线 | [MapAccessListener](../plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/listener/MapAccessListener.java) 允许这个安全回退；真实 Paper 合成重生事件验证落点返回默认世界 |
| P2 | API setSpawn 对世界名先忽略大小写比较，但存储要求使用地图记录的准确名称，大小写变体引发存储校验异常 | [MapSettingsServiceImpl](../plugin/src/main/java/studio/lorian/mapmanager/plugin/impl/MapSettingsServiceImpl.java) 按记录名称保存出生点；真实 Paper 使用 PROBE-FLAT 输入并核对保存结果 |
| P2 | 删除没有地图但仍有角色的空组时，JSON 拒绝操作，而 MySQL 外键会级联清理；缓存逻辑也按级联设计 | [JsonStorage](../plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/storage/JsonStorage.java) 级联清理该组 ADMIN/BUILDER；仍被地图引用时继续拒绝，写失败不发布状态。真实 JSON 重开及 CachedStorage 回归通过 |
| P2 | NETHER/THE_END 的内置 FLAT 层设置 JSON 损坏时，部分解析异常未转换为 API 的 INVALID_INPUT，而是直接抛出 | [FlatChunkGenerator](../plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/world/FlatChunkGenerator.java) 在请求解析边界统一包装参数错误；生命周期测试覆盖损坏 JSON、数组、null 和错误 layers 类型，确认无 PENDING 数据 |
| P2 | LP 基础组保护只比较小写字面值，迁移数据或内部调用的 DEFAULT/WorldBase 变体未被同样拒绝 | [WorldBaseProjectionImpl](../plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/permission/WorldBaseProjectionImpl.java) 与 [QueuedWorldBaseProjection](../plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/permission/QueuedWorldBaseProjection.java) 统一忽略大小写保护 default/worldbase，含队列及成员账本加载；适配器测试确认拒绝发生在 LP 调用或入队前。正常创建本来只接收小写组名，未发现普通玩家直接选择任意组名的命令入口 |
| P2 | 禁用物理时恢复落沙只写 Material，丢弃朝向、水浸等 BlockData 状态 | [MapSettingsListener](../plugin/src/main/java/studio/lorian/mapmanager/plugin/internal/listener/MapSettingsListener.java) 恢复原始 BlockData 并关闭物理更新；测试核对同一状态对象被写回 |
| P3 | 传送补全遗漏未加载地图；创建选项、省略类型、空组、公开别名、生物群系和恢复请求等候选不完整；无逐项帮助 | 两个命令类补齐上下文候选与 help，双语文案同步；真实服务/存储单测及 Paper 补全调用通过。新生物群系补全通过 Keyed 读取键，避免旧版 Biome 类与新版接口的调用指令差异 |
| P3 | 现有 Paper 集成脚本写死 4.0.0 JAR 路径，版本更新后可能失败或误测旧包 | run.py 按根 POM 选择插件及 API；三轮隔离测试实际加载 4.0.2-SNAPSHOT |

## 命令补全与帮助范围

- `/world help [子命令]`、`/mapadmin help [子命令]` 提供参数、权限、默认值和风险确认说明；空主命令显示完整帮助，`/wtp` 保留传送用法提示。
- `/world tp`、`/wtp`：按真实 MMC 准入推荐已加载及未加载地图，不暴露无准入的私有地图。
- `/world admin|builder|visitor`：info、获准管理时的 add/remove、在线玩家或对应直接授权成员；不把有效继承成员混入撤销候选。
- `/world kick`：同图在线玩家；physics/explosion/public：现有参数与确认；setname：当前显示名；setspawn：没有额外参数。
- `/mapadmin`：生成器、支持的环境、选项键、现有地图组（含空组）、在线管理员、生物群系、布尔值、导入目录、地图、恢复文件、迁移模式、export 子命令。
- 新地图名、种子、高度、别名、导出文件名、任意生成器和 property 值由用户输入；补全不猜测这些值。删除确认码继续人工输入，确认与执行安全检查保留。

## 安全风险与现有约束

**公开 API 属于受信任插件入口。** 角色、设置和生命周期服务没有操作者参数，无法替 HTTP/审批插件鉴别请求者。外部插件应在自己的入口授权，不能把收到的 mapId/playerId 直接交给写 API。目录名和操作状态校验仍由 MMC 执行；删除 API 按既有契约不要求命令专用的名称正则和确认码。为保持接口，本次没有增加操作者或令牌参数。

**LP 地图组名称复用需要管理员控制。** WorldBaseProjectionImpl 按 groupId 加载或创建同名 LP 组，并让地图 ADMIN/BUILDER 继承它；最后一张图删除时还会删除该组。若把地图分组配置成现有全服管理组，地图角色可能继承额外权限，删图还可能影响该全服组。基础组 default/worldbase 已有保护且本次修正大小写；其他既有组名继续保留原行为，避免破坏 v3 迁移和现有投影。创建/导入的 group= 只交给受信任控制台或 API 管理方，使用地图专用组名；不要复用含全局管理权限的 LP 组。

**撤权生效与外部投影完成是两件事。** MMC 准入立即依据最新数据，LP 建造权限撤销可能等待持久 FIFO；队首持续失败会延后后续任务。Partial 不能被调用方当成“完全失败、没有变更”。保持队列文件并检查重试日志。直接 API 撤权或关闭公开状态不会自行复核既有在线会话；调用方若要求当场移出，应使用新的准入决策检查在线玩家并安排返回安全世界。原生角色命令及本次关闭公开命令走现有监听器复核。

**关闭 strict-map-entry 会停用监听强制准入。** 此时其他插件传送、加入与重生不会由 MMC 拦截，原生传送命令自身的准入仍执行。未登记世界也一直由外部世界管理/保护配置负责。此行为按配置保留。

**运维数据应限制本地访问。** config.yml 保存数据库凭据，存储快照包含 UUID、角色和地图信息；不要把这些目录交给公共下载服务。RCON 有全服维护能力，结果及删除确认码按现有约定输出服务器控制台。用户消息占位符使用 Placeholder.unparsed，本次没有发现通过别名或玩家名注入 MiniMessage 标签的路径；SQL 值使用 PreparedStatement，未发现命令输入拼接 SQL 值的路径。这不是第三方依赖漏洞扫描结论。

## 结构与冗余处理

MapAdminCommand.execute 改为正常的子命令分派，迁移、导出、报告和解析放在子函数。创建/导入使用内部 Registration 保存一次解析后的枚举、选项及可选身份，不再逐分支复制错误处理；生命周期仍做公开 API 必需的业务校验。命令复用现有的前缀匹配与排序方法，不引入命令框架或新依赖。

删除内部 options/importOptions 重复的请求空值/枚举检查和显而易见的辅助函数注释。没有删除持久文件校验、删除目录身份检查或异步角色解析后的实时授权复核；它们保护不同边界，不能等同于对已确定类型反复检查。没有重构整个生命周期或添加未经使用的工具、配置、并发协议。

## 验证结果与限制

- 首轮 169 项 Maven 单测通过（API 5、plugin 164；基线 158），0 failures/errors/skipped；实际服务、JSON 重开和缓存均参与新回归。
- Paper 26.2 build 129、MV 5.7.2、LP 5.5.71 的三轮隔离启动、重启与停用全部通过，含新增补全、帮助、出生点和重生回退检查。未改动原 server 目录，临时实例已关闭。
- `api/src/main/java` 无 diff；只更新 API 模块的 Maven 版本。双语资源同键测试通过；构建仍有既有的重复 MANIFEST 资源提示，未发现打包失败。
- 详细执行记录与报告路径见 [TEST_CASES.md](TEST_CASES.md)。

未运行真实 MySQL、Paper 1.20.1/26.3、真实玩家客户端 TAB/登录/重生或生产压测；不能把本次合成事件测试写成这些场景已验收。没有进行依赖 CVE 审计或生产部署。

## 同日补审：按六项要求收紧实现

再次检查全部未提交改动及调用方，先补回归复现问题，再修复：

- setname 补全重复读取当前地图。设置补全现在共用一个处理函数，一次查询和授权后直接使用同一快照；测试核对实际调用次数。
- 创建补全在环境候选和选项候选中重复解析生成器。现在仅解析已输入完成的类型一次，内部选项函数接收 WorldType；已输入非法类型时不再推荐无法执行的后续选项。
- 版本查询回退不再调用另一个公开的名称校验入口，直接将已校验名称拼至查询出的父目录。Registration 不再额外复制只由命令内部持有的参数 Map；公开请求构造器保留自身不可变性约束。
- 平坦设置此前在 ID 编码、层高计算和生成器构造中反复解析。请求侧只在层高解析时校验层设置，内部 generatorId 只编码已确认的设置；构造生成器时面对持久 ID，独立解析一次并把 JsonObject 交给层解析函数。API 非法层设置及 surface_y 仍在持久化前拒绝；没有为此增加配置、缓存或结果包装类型。
- 恢复补全只推荐实际的请求文件，不把同名 .json 目录混入候选；去掉预先检查目录再列目录的重复操作，缺失目录仍由现有 IOException 路径返回空候选。
- `/wtp` 空参现在显示已有用法；单项维护帮助不再附带无关的创建参数。去掉固定 snapshot 文件名推荐，自由文件名由操作者填写，避免推荐一个可能覆盖既有导出的名称。
- load/recover/operations 的参数数量检查移至处理函数，主分派只路由命令。补充完全成功和共享地图组的删除恢复回归，以及持久任务/成员账本中的保留组大小写校验。

| 要求 | 本次核对结果 |
|---|---|
| 不增加猜测竞态或罕见边界复杂度 | 未引入锁、令牌、并发协议、重试层或新的可配置策略；沿用已有调度与队列 |
| 外部数据一次校验 | 命令字符串、公开请求和持久文件在各自入口校验；内部 WorldType、JsonObject、已校验名称和本地参数 Map 直接使用；删除目录身份与异步返回后的实时授权仍保留 |
| 主函数保留正常流程 | 命令主分派不处理生成选项及维护参数细节，交给解析和处理函数 |
| 注释简洁且说明约束 | 新注释仅解释 Biome 二进制兼容和已校验设置的编码约束；generatorId JavaDoc 写明输入前提及返回值 |
| 测真实实现、少 mock | 继续运行真实命令、生成器、存储和恢复逻辑；外部适配器替身只提供失败/待同步信号，测试不复制业务判断；Paper 探针直接调用公开 API |
| 使用现有流程 | 沿用 JUnit、Maven 和 run.py；没有新增依赖、工具入口、配置项或发布流程；公开 API 源码无改动 |

补审验证：`mvn -o --no-transfer-progress clean package` 通过，API 5 项、plugin 168 项，共 173 项，0 failures/errors/skipped。相对上轮增加 4 项用例，并扩展恢复文件、帮助、非法平坦 JSON、生成器持久 ID 和调用次数断言；先运行新增断言，已复现原有错误后再修复。使用最终 JAR 复跑同一 run.py，Paper 26.2/MV 5.7.2/LP 5.5.71 三轮全部通过，新增真实 API 非法层设置校验同样通过。报告：`/var/folders/hs/bq2njdx955169mcb_b3mf6jw0000gn/T/mmc-v4-todo-6o7whaou/report.json`。复查后未发现剩余阻断项；公开 API、存储格式和依赖不变，版本仍为本次 4.0.2-SNAPSHOT。
