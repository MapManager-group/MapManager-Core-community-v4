# 测试与验收

本项目使用 Maven/JUnit 5；真实依赖验收使用现有 Python 脚本，不另建测试流程。

## 运行

在仓库根目录使用 JDK 21 或以上：

```shell
mvn clean test
mvn clean package
```

真实 Paper 验收需已有 `server/` 运行依赖，按服务端版本选择 JDK。以当前 Paper 26.2 为例：

```shell
python3 plugin/src/test/integration/run.py --runtime server --java-home /path/to/jdk-25
```

真实 MySQL 验收另需本机 MySQL 二进制目录：

```shell
python3 plugin/src/test/integration/run_mysql.py --mysql-base /path/to/mysql --runtime server --java-home /path/to/jdk-25
```

脚本编译当前版本的外部 API 探针，使用临时目录和 loopback 实例，结束后停止自己启动的进程；不应指向生产数据。完整选项使用脚本 `--help`。

## 自动化回归入口

源码根目录：[API 测试](../api/src/test/java/)、[插件测试](../plugin/src/test/java/)。测试直接调用真实服务、命令、存储和生成器；需要模拟外部失败时使用适配器替身。

| 领域 | 测试类 | 关键断言 |
|---|---|---|
| API 模型 | `ApiModelContractTest` | 请求构造、参数快照、组数据与结果兼容 |
| 地图服务 | `MapServicesTest`、`MapServiceFailureTest`、`MapAliasServiceImplTest` | 组角色继承、默认世界、公开状态、别名同步与失败结果 |
| 传送 | `MapTravelServiceImplTest` | 准入、按需加载、在线状态与踢出条件 |
| 生命周期 | `WorldLifeCycleServiceImplTest`、`WorldLifeCycleValidationTest` | 创建/导入/初始化/删除/加载；非法请求在副作用前拒绝 |
| 失败与恢复 | `RecoveryJournalTest`、`DeletedPlanRecoveryTest`、`FailedCreationRetryTest` | 请求重开、目录身份、失败占位释放、删除收尾失败/待同步/共享组 |
| 命令 | `MapAdminCommandTest`、`MapWorldCommandTest`、`CommandCompletionTest` | 帮助、参数、默认选项、确认、当前值、授权与一次查询的上下文补全；非严格模式 MV 权限候选、状态过滤与去重；未登记默认世界的传送、失败结果及补全 |
| 监听 | `MapListenersTest` | 撤权和关闭公开复核、默认世界回退、物理/爆炸、落沙 BlockData 与全局实体保护 |
| 存储 | `JsonStorageTest`、`StorageConfigTest`、`StorageRecoveryTest` | 实际 JSON 重开、约束、失败不发布缓存、快照、后端切换与配置保存失败恢复 |
| v3 迁移 | `V3MigrationSourceTest`、`V3MigrationRecoveryTest` | 实际旧 JSON、UUID/关系冲突、报告、冻结计划、源变化和中断重试 |
| LP | `DefaultWorldProjectionTest`、`QueuedWorldBaseProjectionTest` | 默认世界不投影、基础组保护、失败重试、重启及历史成员撤权 |
| 消息 | `MessagesTest` | 语言加载、双语同键、缺键回退、参数不解析、玩家隐藏操作 ID |
| 世界工具 | `FlatChunkGeneratorTest`、`WorldGatewayImplTest`、`WorldVersionServiceImplTest`、`LevelDatVersionReaderTest` | 真实 NBT、实际路径、非法名称、生成层设置与绝对 Y 边界 |

## 真实依赖验收

[run.py](../plugin/src/test/integration/run.py) 配合 [ApiProbe.java](../plugin/src/test/integration/ApiProbe.java) 检查：

- 公开 API 的类加载、服务异步注册、撤销及停用清理。
- 默认世界不被托管，worldbase 和地图组继承/撤销，初始化规则实际写入 MV/Bukkit。
- FLAT/NORMAL、NETHER/THE_END、绝对表层 Y、私有和物理/爆炸默认值、按需加载。
- 命令及补全、帮助、设置参数、出生点世界名规范化、重生回退和完整请求重启恢复。

[run_mysql.py](../plugin/src/test/integration/run_mysql.py) 配合 [StorageSqlProbe.java](../plugin/src/test/integration/StorageSqlProbe.java) 检查真实 JDBC 建表、外键/唯一约束、JSON/MySQL 双向迁移、MySQL 换目标、冲突、中断及配置落盘后的独立 JVM 重开。它不包含 Paper 生命周期验收。

## 发布前人工检查

| 场景 | 应有行为 |
|---|---|
| 首次安装 / v3 升级 | 依赖缺失或迁移冲突阻止启用；成功后服务与命令可用，检查迁移报告和备份 |
| 成员身份 | 管理员/建筑者跨同组生效，访客仅单图；公开不是角色，OP 不越过 MMC |
| 真实客户端传送 | 未加载但可进入地图能按需加载；无权地图拦截，登录/重生返回默认世界 |
| 角色撤销、关闭公开 | 严格模式复核在线玩家；仍有其他有效角色者保留；关闭严格模式遵循配置 |
| 开关与 TAB | 无参查询；物理/爆炸开启需 10 秒同图确认；无权私有地图不出现在补全中 |
| 初始化 | 关闭物理与爆炸，保留公开、角色、出生点及原状态；实际 gamerule 与运行设置一致 |
| 绝对表层 Y | 测世界 minHeight、maxHeight-1 和越界值；扩展数据包须已生效 |
| 删除与恢复 | 在线玩家阻止删除；确认码过期/地图替换不误删；删除目录身份变化拒绝恢复 |
| 依赖故障 | LP 变更已保存则返回 Partial，队列重试和重启不丢失；MV 失败保留可恢复线索 |
| 版本兼容 | 在目标 Paper、MV、LP、VoidGen 组合实测，特别检查新版实体事件与 Biome 接口兼容 |

直接 API 修改的授权及在线会话处理由调用方验收，不能用原生命令测试代替。测试数据和凭据不得写入仓库。

## 最近验证基线

以下是代码版本 4.0.2-SNAPSHOT 的既有结果，不表示每次文档修改都重新执行：

| 验证 | 结果 |
|---|---|
| 2026-10-28 Maven | 173 项通过：API 5、plugin 168，0 失败/错误/跳过 |
| 2026-10-28 隔离服务器 | Paper 26.2 build 129、MV 5.7.2、LP 5.5.71；三轮启动、重启与停用通过 |
| 2026-10-27 真实 MySQL | MySQL 8.4.11：22 项 JDBC/迁移检查及两次独立 JVM 重开（各 5 项）通过 |

未覆盖真实玩家客户端 TAB/登录/重生、Paper 1.20.1/26.3、生产负载、数据库大版本升级及第三方依赖漏洞扫描；合成事件和编译成功不能替代这些验收。
