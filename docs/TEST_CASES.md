# MapManager v4 已实现功能测试用例

默认世界回归：`MapServicesTest.defaultWorldAllowsEntryWithoutRolesAndKeepsSharedGroupOnOtherMaps` 验证自由准入、角色失效、同组隔离和设置拒绝；`MapServicesTest.defaultWorldRejectsManagementAndIgnoresHistoricalMmcSpawn` 验证别名/出生点/生命周期管理拒绝及旧出生点绕过；`MapListenersTest.defaultWorldBypassesStoredSettingsAndAccessDenials` 验证默认世界跳过事件约束；`DefaultWorldProjectionTest` 验证旧默认世界 worldbase 继承被移除且不重建，并保留其他世界继承。实服还需验证 Multiverse 默认世界识别、LP 后台落盘与实际保护插件组合。

本清单依据当前源码整理，区分可在本地自动运行的单元测试和需要 Paper/依赖服务的集成验收。状态以 [`CLASS_STATUS.md`](CLASS_STATUS.md) 为准；标记“部分实现”的流程仍测试已存在的分支，同时记录未实现的行为，不将设计目标算作通过。自动测试使用 JUnit Jupiter，由 Maven Surefire 在 `test` 阶段运行；`mvn package` 会先运行测试，任一测试失败都会阻止进入打包阶段。

## 测试环境与数据

- 自动测试环境：Maven 和 JDK 21，`JAVA_HOME` 指向 JDK 21。
- 集成验收环境：兼容版本的 Paper、Multiverse-Core、LuckPerms；按场景准备 VoidGen、可用 Mojang 查询网络和 MySQL。
- 准备默认世界 `world`，并准备无玩家在线、可以删除的测试世界目录；删除测试建议用 `alpha_bak`、`beta_bak`。
- 准备地图 `arena`（私有、ACTIVE、组 `team`）、同组地图 `arena2`、另一组地图 `other`、管理员 `AdminA`、建筑者 `BuilderB`、访客 `VisitorC`、无角色玩家 `GuestD`。
- 准备两个存储配置：JSON；独立测试 MySQL。需要故障分支时使用错误凭据、不可写目录、无效配置或可控的适配器替身。
- 每个写入类用例结束后，分别查询服务/API、命令返回和持久存储，确认三者一致。结果对象检查 `operation`、`operationId`、`Success/Failure/Partial` 与失败原因。

## 自动化自测与打包门禁

从仓库根目录执行：

```shell
mvn clean test
mvn clean package
```

第二条命令按 Maven 生命周期先执行 `test`，再执行 `package`。不要在发布构建中传入 `-DskipTests` 或 `-Dmaven.test.skip=true`。Surefire XML 和文本报告分别写入模块的 `target/surefire-reports/`。

当前自动测试不启动 Paper，也不连接 LuckPerms、Multiverse、Mojang 或 MySQL；这些依赖的运行时行为仍按后续章节在集成环境验收。

| 自动用例                         | 测试类                                                                                               | 覆盖点                                                                                                        |
|----------------------------------|------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| UNIT-API-01 至 UNIT-API-04       | `api/src/test/java/studio/lorian/mapmanager/api/model/ApiModelContractTest.java`                     | 创建/导入请求默认 FLAT+NORMAL、生成器与环境独立指定、参数防御性复制、地图组世界集合不可变                       |
| UNIT-STO-01 至 UNIT-STO-05       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/storage/JsonStorageTest.java`         | JSON 重启恢复、大小写不敏感查询、角色和操作持久化、非法状态不提交、地图删除访客清理、重复授权及损坏文件拒绝   |
| UNIT-SVC-01 至 UNIT-SVC-13       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/MapServicesTest.java`                     | 角色组/单图范围、有效成员去重、撤权回退、ACTIVE 准入和列表过滤、LP 部分失败/待同步、访客不投影、物理/爆炸字段隔离、服务 getter |
| UNIT-WORLD-01 至 UNIT-WORLD-04   | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/world/WorldGatewayImplTest.java`      | `server.properties` 地图根目录定位、维度目录选择和世界名路径穿越防护；`MapServicesTest` 另验证公开目录入口优先实际已加载路径、未加载查询不创建目录                                          |
| UNIT-NBT-01 至 UNIT-NBT-04       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/world/LevelDatVersionReaderTest.java` | 压缩 NBT 与 Paper 26 共用根文件；文件损坏、字段缺失或类型无效时返回空值                                                 |
| UNIT-ALIAS-01                    | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/MapAliasServiceImplTest.java`             | legacy、section 与 RGB 颜色代码清理                                                                           |
| UNIT-MIG-01 至 UNIT-MIG-02       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/migration/V3MigrationSourceTest.java` | v3 地图、组、角色范围和物理/爆炸设置解析；组地图清单不一致时拒绝                                              |
| UNIT-MSG-01 至 UNIT-MSG-07       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/notification/MessagesTest.java`       | 英文与安全占位符、中文回退、参数校验、双语键及结果枚举齐全；三种结果通知及玩家/控制台/日志分流；双语玩家隐藏 ID、控制台保留 ID                                                              |
| UNIT-CONFIG-01 至 UNIT-CONFIG-05 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/storage/StorageConfigTest.java`       | JSON 默认值、MySQL 配置读取与空白裁剪、未知类型及必填项/端口/数据库名校验                                     |
| UNIT-LIFE-01 至 UNIT-LIFE-06     | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/WorldLifeCycleServiceImplTest.java`       | 创建和导入覆盖三种生成器与三种已实现环境的组合；SIFT 保持未支持；校验绝对表层 Y 编码和非 FLAT 的 flat.height 拒绝 |
| UNIT-TRAVEL-01 至 UNIT-TRAVEL-13 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/MapTravelServiceImplTest.java` | 传送输入/线程/在线/准入校验、按需加载、Failure/Partial 传播、出生点和朝向、错误世界和传送失败、踢出同图与管理员校验 |
| UNIT-VERSION-01 至 UNIT-VERSION-03 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/WorldVersionServiceImplTest.java` | 旧版/同版/未来版本兼容判断、损坏或缺失 NBT、世界名定位及路径逃逸拒绝 |
| UNIT-FLAT-01 至 UNIT-FLAT-06 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/world/FlatChunkGeneratorTest.java` | 生成器 ID 往返、实际层材料/顺序/范围、负最小高度、旧层数高度截断、绝对表层 Y、实际及扩展范围校验、出生点和非法设置 |
| UNIT-LISTENER-01 至 UNIT-LISTENER-09 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/listener/MapListenersTest.java` | 物理与爆炸设置独立、未登记世界、落沙恢复、其他实体、画/展示框全局支撑保护（地图物理开/关、未登记世界、默认世界）和通用实体破坏原因、严格传送准入及关闭整套监听 |
| UNIT-VALIDATE-01 至 UNIT-VALIDATE-08 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/WorldLifeCycleValidationTest.java` | 创建/导入名称和保留名、组/别名/参数校验、SIFT、异步拒绝、API 删除不要求 _bak 后缀且仍校验地图状态；删除预检区分状态、在线玩家和恢复类型/UUID 冲突，拒绝时保留地图及恢复请求；拒绝时不新增地图/组/操作记录 |
| UNIT-FAILURE-01 至 UNIT-FAILURE-06 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/MapServiceFailureTest.java` | 角色写入失败返回 Failure、写后读取或 LP 异常返回 Partial、无效角色输入、物理/爆炸读写失败 |
| UNIT-ADMIN-01 至 UNIT-ADMIN-10 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/command/MapAdminCommandTest.java` | 控制台/RCON 放行，玩家及命令方块拒绝、路由、默认值、非法/重复选项、导入参数拒绝、一次性/过期删除码、身份与状态保护、补全 |
| UNIT-CMD-01 至 UNIT-CMD-07 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/command/MapWorldCommandTest.java` | world/wtp 共用传送、设置/别名/出生点路由、管理员授权、只读 info、角色 info 名单/准入、存储错误及补全过滤 |
| UNIT-RECOVERY-01 至 UNIT-RECOVERY-03 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/RecoveryJournalTest.java` | 完整请求重启、目录身份变更拒绝、符号链接与路径穿越拒绝 |
| UNIT-CACHE-01 至 UNIT-CACHE-09 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/storage/StorageRecoveryTest.java` | 快照恢复和冲突、事件读取隔离、缓存写失败不发布、重命名/换组索引、旧快照不可变、角色清理与删除保护、后端切换标记 |
| UNIT-QUEUE-01 至 UNIT-QUEUE-04 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/permission/QueuedWorldBaseProjectionTest.java` | LP 不阻塞调用者、FIFO 撤权顺序、失败重试与重启恢复、损坏任务文件拒绝 |
| UNIT-MIGREC-01 至 UNIT-MIGREC-04 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/migration/V3MigrationRecoveryTest.java` | v3 预检、冻结计划中断恢复、源变化和目标冲突保护 |

当前共 122 个 JUnit 用例，会在 `mvn test`、`mvn package` 及更晚的 Maven 生命周期自动执行。上述事件单测直接调用监听器，未启动 Bukkit 事件总线。服务注册/类路径、真实玩家登录/重生/换世界与安全回退、Multiverse/LuckPerms 实际写入、MySQL 建表和完整启动迁移仍需真实集成环境，不纳入普通 Maven 单元门禁。测试替身会在每个测试结束后恢复 Bukkit 静态服务器。

## 启动、配置与本地化

| ID      | 前置条件与操作                                           | 预期结果                                                                          |
|---------|----------------------------------------------------------|-----------------------------------------------------------------------------------|
| BOOT-01 | 首次用默认配置启动                                       | 创建插件数据目录、默认配置及内置语言文件；选择 JSON；插件不因配置初始化失败而禁用 |
| BOOT-02 | 指定 `language: en_US` 启动                              | 英文资源被选中；启动和命令文案使用英文                                            |
| BOOT-03 | 选择语言文件中缺少一个键                                 | 缺失键回退到内置中文；其他英文键仍正常                                            |
| BOOT-04 | 选择的语言代码对应文件不存在                             | 启动失败并输出可定位的语言文件错误                                                |
| BOOT-05 | 语言 YAML 语法无效                                       | 启动失败并记录语言文件错误                                                        |
| BOOT-06 | 配置无效的创建名称正则或删除名称正则                     | 插件启动失败，日志指出初始化错误                                                  |
| BOOT-07 | LuckPerms API 服务不可用                                 | 插件禁用并输出依赖不可用原因                                                      |
| BOOT-08 | `worldbase` 不存在时启动                                 | 创建 `worldbase`，使其继承 `default` 并保存                                       |
| BOOT-09 | `worldbase` 已存在且包含自定义节点时启动                 | 不重置或覆盖已存在组及其节点                                                      |
| BOOT-10 | 使用 JSON 启动后改成 MySQL，或改 MySQL 连接目标          | 检测到 `storage-backend.txt` 不匹配，拒绝静默换库并禁用启动                       |
| BOOT-11 | 使用有效 MySQL 配置首次启动                              | 连接成功并创建所需五张 InnoDB 表及索引/外键                                       |
| BOOT-12 | MySQL 主机不可达、凭据错误或数据库不存在                 | 启动报告存储连接错误；原数据不被空数据覆盖                                        |
| BOOT-13 | 删除 `access-control.strict-map-entry` 或显式设为 `true` | 严格地图准入监听默认开启                                                          |

## 对外 API 服务注册与调用

| ID     | 前置条件与操作                                                                                      | 预期结果                                                                                       |
|--------|-----------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| API-01 | 正常启动 MapManager-Core，等待存储、迁移及 `worldbase` 初始化完成后调用 `ServicesManager.load(MapManager.class)` | 返回非 null 的 `MapManager` provider；注册优先级为 `Normal`                                    |
| API-02 | 外部 Paper 插件声明 `MapManager-Core` 依赖和 `join-classpath: true`，在 `onEnable` 查询并监听服务事件 | 初次查询可为 null；收到 `ServiceRegisterEvent` 后可通过同一个 `MapManager.class` 查询到服务       |
| API-03 | 分别令 v3 迁移或 LuckPerms `worldbase` 初始化失败后启动                                              | MapManager-Core 禁用；没有发布 `MapManager` 服务                                                |
| API-04 | 正常启动后禁用 MapManager-Core，且服务器没有其他 `MapManager` provider                               | 发布 `ServiceUnregisterEvent`；事件后 `ServicesManager.load(MapManager.class)` 返回 null           |
| API-05 | 服务注册后读取 `maps/roles/access/settings/aliases/travel/worldLifeCycle/worldVersions` getter        | 所有 getter 返回非 null 服务实例；地图查询与版本查询返回值符合各自接口契约                       |
| API-06 | 外部插件未打包另一份 `mapmanager-api`，通过 joined classpath 查询服务                                | 服务键与调用方的 `MapManager.class` 类型一致，不出现服务查询返回 null或类转换错误                |
| API-07 | 外部插件调用角色或地图设置写方法前后检查自己的操作者授权                                             | MapManager 只执行业务变更，不代替调用方授权；未授权请求由外部插件拦截且 MMC 数据不变              |

## 地图创建、导入与初始化

| ID      | 前置条件与操作                                                             | 预期结果                                                                                                                 |
|---------|----------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| LIFE-01 | 控制台执行 `/mapadmin create arena flat normal`                               | 以 FLAT 生成器和 NORMAL 环境创建世界、登记地图和组，初始化 MV 配置，地图最终为 ACTIVE，默认组/别名为 `arena`             |
| LIFE-02 | 玩家执行 create、import、init 或 delete                                    | 返回无权限结果；世界、地图、存储均未改变                                                                                 |
| LIFE-03 | 为新地图指定 `group=team admin=AdminA`                                     | 地图进入 `team`；管理员授予保存到组；LP 地图组继承 `worldbase` 并投影 AdminA                                             |
| LIFE-04 | 在 `arena` 所在组创建 `arena2`                                             | 共用同一组；组的地图列表包含两张图，组管理员/建筑者对两图生效                                                            |
| LIFE-05 | 创建地图时省略 alias/group                                                 | 两者均使用世界名                                                                                                         |
| LIFE-06 | 创建地图时提供带 Minecraft 颜色代码的 alias                                | 保存别名文本时去掉颜色代码；MV 别名根据公开状态附加颜色                                                                  |
| LIFE-07 | 组合 `flat/normal/void` 与 `normal/nether/the_end` 创建并导入               | Multiverse 配置中的生成器和 Bukkit 环境分别匹配请求；FLAT+非 NORMAL 使用编码的内置生成器；VOID 需要 VoidGen       |
| LIFE-08 | 创建或导入时选择 `sift` 环境                                               | 返回 `UNSUPPORTED_ENVIRONMENT`；不改变世界或登记 PENDING 地图                                                          |
| LIFE-09 | FLAT 使用 flat.height=0、-60 及实际上下界；生效的数据包扩展世界使用 -2000、2000 | 草方块位于指定 Y，出生点在 Y+1；按目标世界实际 minHeight～maxHeight-1 校验，超出时报错                                                                     |
| LIFE-10 | structures 分别使用 `true`、`false`、其他文本                              | 两个布尔值映射正确；其他文本拒绝，世界不创建                                                                             |
| LIFE-11 | 在 NORMAL/VOID 生成器使用 `flat.height`；FLAT 同时提供 `flat.height` 与 `generator-settings` | 分别拒绝创建，错误信息指向参数冲突或适用生成器                                                                      |
| LIFE-12 | 测试合法 seed、biome、generator、generator-settings、property.*            | 参数传入 MV 创建选项或世界属性；创建成功                                                                                 |
| LIFE-13 | 提供未知参数键、空值、超过 4096 字符的值或非法 property 键                 | 请求被拒绝，不执行世界创建                                                                                               |
| LIFE-14 | 世界名测试 4/16 字符及小写字母、数字、`_`、`-`                             | 长度及字符范围内允许；3/17 字符、大写、空格、路径分隔符拒绝                                                              |
| LIFE-15 | 逐一尝试内置保留名、保留前缀及默认的 `_bak` 后缀                           | 创建和导入都拒绝；额外正则不能放宽内置限制                                                                               |
| LIFE-16 | 将额外拦截正则改为匹配 `lobby`、`temp.*` 的表达式                          | 完整地图名命中正则时拒绝，未命中名称允许                                                                                 |
| LIFE-17 | 组 ID 测试长度边界、非法字符、`default`、`worldbase`                       | 合法组 ID 接受；非法字符/长度及 LP 基础组名称拒绝                                                                        |
| LIFE-18 | 展示名为 null/空、255 字符、256 字符；清理颜色后为空                       | 空值回退地图名；最大长度允许；超长或清理后空白的别名拒绝                                                                 |
| LIFE-19 | 对 MMC、Bukkit 或 MV 中已存在的世界名再次创建/导入                         | 返回 ALREADY_EXISTS；既有世界和记录不变                                                                                  |
| LIFE-20 | 在根目录准备有效未注册世界，执行 `/mapadmin import arena-copy normal nether` | 以 NORMAL 生成器和 NETHER 环境导入，执行初始化、角色投影和 ACTIVE 登记；现有区块不变 |
| LIFE-21 | 导入目录不存在、不是目录或缺少 level.dat                                   | 返回 WORLD_NOT_FOUND 或 INVALID_INPUT；不创建 MMC 地图记录                                                               |
| LIFE-22 | 创建或导入 VOID 生成器但 VoidGen 未启用                                    | 返回世界后端失败；不注册为 ACTIVE                                                                                        |
| LIFE-23 | 导入目录为符号链接或名称试图逃逸世界根目录                                 | 输入校验或目录检查拒绝，不触碰根目录外文件                                                                               |
| LIFE-24 | 创建/导入过程中分别令 MV 操作、初始化、存储或 LP 投影失败                  | 后端/存储失败保留完整请求及 PENDING 供恢复；LP 入队后地图 ACTIVE，返回 Partial(PERMISSION_SYNC_PENDING)，后台重试                              |
| LIFE-25 | 成功创建或导入后检查世界属性                                               | difficulty peaceful、creative、自动加载开启、出生区块常驻关闭；普通生物生成关闭、MISC 开启；规定 gamerule 全部生效并保存 |
| LIFE-26 | 对已加载且已登记地图执行 `/mapadmin init <name>`                              | 先持久化关闭物理和爆炸，再重放 MV 与 gamerule 初始化；保留公开状态、出生点和角色，不改变地图状态；返回的地图快照包含关闭后的设置                                                   |
| LIFE-27 | init 目标不存在、未登记或未加载                                            | 分别返回 MAP_NOT_FOUND 或 WORLD_NOT_FOUND；不产生其他世界变更                                                            |
| LIFE-28 | init 名称含非法字符或由玩家执行                                            | 返回 INVALID_INPUT/NO_PERMISSION；不执行 MV 操作                                                                         |
| LIFE-29 | 在 FLAT+NETHER/THE_END 创建 `flat.height=64` 或自定义 `generator-settings`；检查区块和 Environment | flat.height=64 时草方块 Y=64；生成预期 biome 并保留环境；手工 layers 设置保持原校验，非法方块、JSON 和总层数超过 254 在登记前拒绝 |
| LIFE-30 | 创建 FLAT+NETHER/THE_END 后重启；确认 MapManager-Core 先于 Multiverse-Core 启用并进入新区块 | Multiverse 解析持久化生成器 ID；新生成区块层设置正确，Environment 仍与所选维度一致 |

## 地图设置、别名与事件

| ID       | 前置条件与操作                                                  | 预期结果                                                                          |
|----------|-----------------------------------------------------------------|-----------------------------------------------------------------------------------|
| SET-01   | 地图管理员执行 `/world physics info` 和 `/world explosion info` | 显示当前地图对应存储值，不写入数据                                                |
| SET-02   | 管理员分别设置 physics true/false、explosion true/false         | 返回结构化成功结果；仅目标地图对应字段改变，其他设置保持原值                      |
| SET-03   | 重启服务器后再次查询刚才修改的设置                              | JSON/MySQL 中的设置仍保留，命令和事件使用新值                                     |
| SET-04   | 同组管理员、非管理员、建筑者、访客、公开地图陌生玩家修改设置    | 同组 ADMIN 有权修改；其他角色均返回 NO_PERMISSION；设置不变                       |
| SET-05   | 玩家在未登记世界使用设置命令，或控制台使用玩家子命令            | 分别返回 MAP_NOT_FOUND/INVALID_INPUT；不写入设置                                  |
| SET-06   | 设置命令缺少参数、参数超出两个、输入非 true/false/info          | 显示用法；数据不改变                                                              |
| SET-07   | physics=false 时触发 BlockPhysicsEvent                          | 事件取消；其他未登记世界或 physics=true 地图事件不被本监听器取消                  |
| SET-08   | physics=false 时生成 FallingBlock                               | 生成事件取消，并在生成位置以无物理更新方式恢复对应方块材质                        |
| SET-09   | physics=true 时生成 FallingBlock                                | 本监听器不取消实体生成，也不执行方块恢复                                          |
| SET-10   | explosion=false 时触发 BlockExplodeEvent 与 EntityExplodeEvent  | 对应事件取消，爆炸不破坏方块                                                      |
| SET-11   | explosion=true 时触发两类爆炸事件                               | 本监听器不取消爆炸事件                                                            |
| SET-12   | 物理/爆炸关闭但地图未登记、设置开启的对照世界发生事件           | 未登记世界的方块物理和爆炸不受影响；不同地图设置互不串扰；实体支撑保护仍全局生效                                  |
| SET-13 | 在物理开启/关闭、默认和未登记世界移除坐垫/画/展示框的支撑方块或挤占其位置 | PHYSICS/OBSTRUCTION 事件取消，实体不掉落；直接敲击拆除仍允许 |
| SET-13   | API 调用 setPhysical/setExplosion 使用有效地图 UUID             | 只更新字段且保留 explosion/physical、public、spawn、displayName、status 等其他值  |
| SET-14   | API 设置使用 null UUID、未知 UUID或存储写失败                   | 分别返回 INVALID_INPUT、MAP_NOT_FOUND、STORAGE_FAILED；不报告成功                 |
| ALIAS-01 | 设置展示名，分别包含颜色码、空文本、超长文本                    | 保存/同步遵循别名服务输入校验；颜色码不进入持久展示文本                           |
| ALIAS-02 | 调用 setAlias(mapId) 与 setAlias(mapId, "Custom")               | 无参数版本重置到世界名；指定版本保存指定纯文本并更新 MV                           |
| ALIAS-03 | 私有地图和公开地图执行 alias 同步                               | MV 别名分别使用 `&3` 和 `&2` 前缀；持久名称不含该前缀                             |
| ALIAS-04 | setPublic true/false 后检查地图字段和 MV alias                  | 公开字段与 alias 颜色同步；保留原 alias 文字                                      |
| ALIAS-05 | 当前 MV 不存在对应世界、MV 操作失败、存储失败                   | 返回 Failure 或 Partial，状态与错误原因准确；MMC 已保存而同步失败时不伪报完全成功 |

## 角色、准入判定与成员命令

| ID      | 前置条件与操作                                                      | 预期结果                                                                          |
|---------|---------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| ROLE-01 | 在一个地图组授予 AdminA ADMIN，查询同组两张图                       | 两图均能查到直接 ADMIN；有效 BUILDER、VISITOR 也包含 AdminA                       |
| ROLE-02 | 授予 BuilderB BUILDER                                               | 组内图均可进入；有效 VISITOR 包含 BuilderB；直接 ADMIN/BUILDER 列表不混入继承角色 |
| ROLE-03 | 授予 VisitorC 在 arena 的 VISITOR                                   | 只对 arena 生效，不影响 arena2 或 other                                           |
| ROLE-04 | 同一玩家同时获多个角色，逐个移除                                    | 有效角色取剩余来源；移除一个角色不会抹去其他来源仍生效的角色                      |
| ROLE-05 | 对 ACTIVE 地图添加/移除 ADMIN、BUILDER、VISITOR                     | MMC 记录正确；ADMIN/BUILDER 同步 LP 地图组成员；VISITOR 不产生 LP 投影            |
| ROLE-06 | 添加/移除组角色时另一组角色仍存在                                   | LP 建造成员保持；两种组角色都不存在后移除 LP 建造成员                             |
| ROLE-07 | 对相同角色重复添加/移除                                             | 操作幂等；不会产生重复授权；同时可重试 LP 投影                                    |
| ROLE-08 | 对 PENDING/DELETING 等非 ACTIVE 地图修改成员                        | 返回 ACCESS_DENIED；授权不变                                                      |
| ROLE-09 | 成员修改使用 null map/player/role 或无效地图 ID                     | 返回 INVALID_INPUT 或 MAP_NOT_FOUND；存储不变                                     |
| ROLE-10 | MMC 角色存储失败；MMC 成功但 LP 写入失败                            | 前者返回 STORAGE_FAILED；后者返回 Partial，并保留 MMC 权威角色记录                |
| ROLE-11 | `/world admin\|builder\|visitor info` 由可进入当前地图玩家执行       | 输出直接授予该角色的玩家，不把继承角色错误列为直接授予                               |
| ROLE-12 | 无准入权限玩家执行名单命令                                          | 返回 ACCESS_DENIED；不泄露成员名单                                                |
| ROLE-13 | 管理员执行 admin/builder/visitor add/remove，目标用用户名及 UUID    | 名称解析到正确 UUID；UUID 可直接使用；添加/撤销结果与查询一致                     |
| ROLE-14 | 非管理员执行成员变更；玩家在未登记地图执行                          | 返回 NO_PERMISSION/MAP_NOT_FOUND，授权不变                                        |
| ROLE-15 | 目标用户名格式无效、不存在或 Mojang 查询返回空                      | 返回 PLAYER_NOT_FOUND；不产生成员记录                                             |
| ROLE-16 | 操作者开始异步解析后切换地图或失去 ADMIN，再完成解析                | 主线程二次检查阻止变更；新地图和非管理员权限不被绕过                              |
| ROLE-17 | 默认 private 地图：ADMIN、BUILDER、VISITOR、陌生玩家分别调用 decide | 三类授权玩家允许并得到最高有效角色；陌生玩家拒绝 ACCESS_DENIED                    |
| ROLE-18 | public=true 且玩家无显式角色                                        | 允许进入，effectiveRole 为空；公开访问不虚构 VISITOR 或建造权限                   |
| ROLE-19 | 地图状态非 ACTIVE，或 mapId/playerId 为 null/不存在                 | 准入拒绝并返回相应 ACCESS_DENIED、INVALID_INPUT、MAP_NOT_FOUND                    |
| ROLE-20 | 查询 accessibleMaps/administratedMaps                               | accessible 仅含可进入 ACTIVE 地图；administrated 仅含玩家是 ADMIN 的 ACTIVE 地图  |

### Bukkit 地图准入监听

| ID      | 前置条件与操作                                                                  | 预期结果                                                                 |
|---------|---------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| ACCESS-01 | `access-control.strict-map-entry: true`，无权玩家用原版 `/tp` 进入私有地图  | 传送被取消，玩家留在原世界并收到拒绝提示                                 |
| ACCESS-02 | 开启严格校验，无权玩家通过 `/mvtp` 或其他插件传送                            | 目标进入被拦截；若其他插件绕过预检，事件后复核将玩家送回默认世界         |
| ACCESS-03 | 开启严格校验，有权玩家通过原版、Multiverse 或其他插件传送                     | 传送成功并留在授权地图                                                   |
| ACCESS-04 | 开启严格校验，无权玩家位于私有地图时登录或在该图重生                         | 返回 MV 默认世界出生点；无法安全返回时断开连接                            |
| ACCESS-05 | 开启严格校验，在线玩家被撤销最后一项有效进入权限，玩家仍在该地图             | 立即返回 MV 默认世界；若仍有其他角色或地图公开则保持当前地图               |
| ACCESS-06 | 将严格校验关闭，执行 ACCESS-01、ACCESS-04、ACCESS-05                         | 传送、登录/重生检查及撤权移出均不执行                                     |
| ACCESS-07 | 开启严格校验，MV 默认世界不可用、玩家无权进入该默认地图或回传失败             | 玩家连接被断开，不会滞留在未授权地图                                      |

## 地图传送、踢出与命令补全

| ID     | 前置条件与操作                                            | 预期结果                                                                                                                                 |
|--------|-----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| TRV-01 | 已准入玩家执行 `/world tp arena` 和 `/wtp arena`          | 两种命令等效，传送到保存出生点；返回成功                                                                                                 |
| TRV-02 | 地图无保存出生点                                          | 传送到该世界默认出生点                                                                                                                   |
| TRV-03 | 访客进入私有图但无授权；目标地图非 ACTIVE                 | 分别拒绝 ACCESS_DENIED；玩家位置不变                                                                                                     |
| TRV-04 | 目标地图记录不存在、世界未加载、spawn 属于错误世界        | 返回 MAP_NOT_FOUND、WORLD_NOT_FOUND、WORLD_BACKEND_FAILED；不传送                                                                        |
| TRV-05 | 玩家离线、UUID 无效或在非主线程调用传送服务               | 返回 PLAYER_NOT_FOUND 或 INVALID_INPUT；不触碰 Bukkit 传送                                                                               |
| TRV-06 | teleport 玩家传送失败                                     | 返回 WORLD_BACKEND_FAILED，玩家通知不含操作 ID，控制台保留 ID                                                                                               |
| TRV-07 | 管理员在同一地图执行 `/world kick <online-player>`        | 目标到 MV 默认世界出生点；操作者收到结果，目标收到被踢提示                                                                               |
| TRV-08 | 操作者不是 ADMIN，目标离线或目标不在同一世界              | 分别返回 NO_PERMISSION、PLAYER_NOT_FOUND、PLAYER_NOT_IN_MAP；不传送                                                                      |
| TRV-09 | 在未登记地图执行 kick；尝试踢出 MV 默认地图中的玩家       | 返回 MAP_NOT_FOUND 或 WORLD_BACKEND_FAILED；世界状态不变                                                                                 |
| TRV-10 | MV 默认世界不存在、默认出生点无效或 teleport 失败         | 返回 WORLD_BACKEND_FAILED；目标位置保持不变或遵循 Bukkit 实际结果                                                                        |
| CMD-01 | 分别以玩家/控制台执行 `/world`、未知子命令、`/wtp` 空参数 | 显示对应多语言用法                                                                                                                       |
| CMD-02 | 为不同角色检查 `/world` 一级补全                          | 仅出现当前发送者可调用的命令；ADMIN 专属子命令对非管理员隐藏                                                                             |
| CMD-03 | 补全 `/world tp`、`/wtp`                                  | 只建议该玩家可进入的地图                                                                                                                 |
| CMD-04 | 补全 `/world kick`                                        | 只建议当前同图在线玩家                                                                                                                   |
| CMD-05 | 控制台补全 `/mapadmin init/delete/import/create`                      | init 提示已登记地图；delete 仅提示 ACTIVE/DELETING 且命名符合删除正则的图；import 限服务器根目录候选；create/import 分别补全生成器、环境和参数键 |
| CMD-06 | 成员命令补全 add/remove 和成员名                          | 只按准入/ADMIN 权限提供操作；在线玩家、同图目标和已有成员提示正确；无缓存名称时可用 UUID                                                 |

## 删除与操作记录

| ID     | 前置条件与操作                                       | 预期结果                                                                                   |
|--------|------------------------------------------------------|--------------------------------------------------------------------------------------------|
| DEL-01 | 默认配置对 `arena_bak` 执行两步删除                  | 第一步产生 6 位确认码并绑定地图 ID；10 秒内输入后删除世界及 MMC/LP 关联                    |
| DEL-02 | 命令及 API 分别删除不符合 `.*_bak$` 的地图                      | 命令拒绝且数据保持；API 不因正则拒绝，继续其他安全校验                                                        |
| DEL-03 | 玩家执行 delete                                      | 返回 NO_PERMISSION；不产生确认码或删除操作                                                 |
| DEL-04 | 对错误码、过期码、已消费码、另一地图确认码执行第二步 | 分别拒绝；只有匹配且未过期的一次性码能确认对应地图                                         |
| DEL-05 | 删除 MV 默认世界或 Bukkit 首个加载世界               | 拒绝 ACCESS_DENIED；文件、地图和玩家不变                                                   |
| DEL-06 | 删除含在线玩家地图                                   | 拒绝 WORLD_HAS_PLAYERS；提示玩家离开，不卸载/删除世界                                                |
| DEL-07 | 地图状态为非 ACTIVE/DELETING，或地图记录不存在       | 返回 INVALID_MAP_STATE/MAP_NOT_FOUND                                                   |
| DEL-08 | ACTIVE 地图的 MV 世界、目录或 level.dat 缺失         | 返回 WORLD_NOT_FOUND；不误删 MMC 记录                                                      |
| DEL-09 | 世界路径指向根目录外、符号链接或不符合预期父目录     | 拒绝 INVALID_INPUT；不得删除目录外内容                                                     |
| DEL-10 | 删除同组中仍有其他地图的一张地图                     | 删除该图并移除此世界的 LP 继承；保留组、其他地图和组角色                                   |
| DEL-11 | 删除组中最后一张地图                                 | 清除该组 LP 成员/组继承，移除组角色、地图和空组                                            |
| DEL-12 | MV 删除、LP 清理或存储删除中途失败                   | 保留 DELETING 与失败 operation 阶段；修复原因后可重新走确认并安全重试                      |
| DEL-13 | 检查成功生命周期操作记录                             | operation ID 唯一，阶段依次包含 STARTED、WORLD_CHANGED、DATA_SAVED（创建/导入）、COMPLETED |
| DEL-14 | 检查生命周期失败操作记录                             | 阶段记录 FAILED 和对应 FailureReason；控制台通知 operation ID 与仓储记录一致，玩家通知不含 ID                     |
| DEL-15 | 存在 CREATE/IMPORT 恢复请求，或 DELETE 请求的地图 UUID 不匹配 | 返回 RECOVERY_CONFLICT；地图和恢复请求不变，不调用世界后端 |

## JSON/MySQL 存储与数据校验

| ID     | 前置条件与操作                                                                            | 预期结果                                                       |
|--------|-------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| STO-01 | JSON 首次启动后写入地图、组、角色、操作，再重启                                           | 数据完整恢复；字段、UUID、枚举、spawn/settings 和关联保持      |
| STO-02 | JSON 重复保存相同地图及组角色                                                             | 保存为幂等更新；查询不重复返回记录                             |
| STO-03 | 用大小写变化的世界名查询/重复登记                                                         | 世界名键按规范化规则匹配；唯一约束阻止大小写变体重复地图       |
| STO-04 | 写入非法地图、组关系、角色记录、操作记录或 spawn                                          | StorageValidation/JSON 读取校验拒绝无效数据并给出存储错误      |
| STO-05 | JSON 临时文件替换成功；模拟写失败                                                         | 成功时原子更新文件；写失败时内存状态不提前提交，旧文件仍可读取 |
| STO-06 | JSON 文件损坏、版本/必要字段不支持                                                        | 启动拒绝读取，不创建空仓储覆盖损坏文件                         |
| STO-07 | MySQL 首次创建 schema 并对地图、组、角色、operation 做 CRUD                               | 五类数据正确持久化，重启后可查                                 |
| STO-08 | MySQL 插入重复地图 UUID、标准化世界名、角色主键或无父组记录                               | 主键/唯一索引/外键拒绝冲突；现有数据完整                       |
| STO-09 | 删除地图后检查 visitor 外键级联；删除仍有关联地图的组                                     | 访客授权随地图删除；有关联地图时组删除被外键拒绝               |
| STO-10 | JSON 与 MySQL 分别验证空结果、findById、findByWorldName、findGroup、findAll、组内地图列表 | 查询边界返回空值/空集合或完整快照，两个后端语义一致            |
| STO-11 | operation unfinished 查询包含 STARTED/FAILED 与 COMPLETED                                 | 只返回未完成阶段；完成记录不再列入 unfinished                  |
| STO-12 | MySQL 运行中短暂断连、SQL 异常                                                            | 返回 StorageException/Failure；不会将存储失败伪装为成功        |

## v3 数据迁移

| ID     | 前置条件与操作                                             | 预期结果                                                                   |
|--------|------------------------------------------------------------|----------------------------------------------------------------------------|
| MIG-01 | 相邻 v3 插件目录不存在旧数据                               | 启动记录 no_data；不改动地图数据                                           |
| MIG-02 | worlds.json 与 groups.json 同时存在且数据有效              | 读取并交叉校验后导入地图、组、角色；备份源文件；生成完成标记               |
| MIG-03 | 仅存在两个 JSON 文件之一                                   | 迁移失败并禁用插件；不当作空数据导入                                       |
| MIG-04 | worlds.json/groups.json JSON 语法或字段类型无效            | 迁移失败；指出源文件/字段；不写完成标记                                    |
| MIG-05 | 重复世界、缺失组、组世界列表与地图关系不符                 | 迁移拒绝不一致数据；目标仓储不被视为迁移成功                               |
| MIG-06 | v3 地图 ID/世界名/组与 v4 记录冲突                         | 迁移中止并说明冲突，不覆盖目标数据                                         |
| MIG-07 | v3 角色玩家名解析为在线、缓存、正版 Mojang 或离线 UUID     | 各模式得到预期 UUID；ADMIN/BUILDER 按组，VISITOR 按单图保存                |
| MIG-08 | 存在无法解析的玩家名                                       | 迁移失败并指出玩家；不生成完成标记                                         |
| MIG-09 | 有效迁移包含 physical、exploded、公开节点、MV alias、spawn | 地图设置与出生点/展示名映射正确；公开状态从旧 LP 信息读取                  |
| MIG-10 | 已有 v3 迁移完成标记后再次启动                             | 显示 done_before，不重复导入、不重复添加角色                               |
| MIG-11 | 源备份失败或完成标记写入失败                               | 迁移失败并禁用插件；日志指出备份/标记失败                                  |
| MIG-12 | 首次迁移中途目标仓储写入失败后重试                         | 验证当前实现的冻结计划幂等重试；恢复完整数据，源变化或目标冲突时拒绝 |

## 玩家、世界目录与通知适配

| ID     | 前置条件与操作                                                                    | 预期结果                                                            |
|--------|-----------------------------------------------------------------------------------|---------------------------------------------------------------------|
| ADP-01 | 玩家 UUID 解析输入 null、短于 3/长于 16、含非法字符                               | 返回 null UUID 结果；不向 Mojang 发请求                             |
| ADP-02 | 解析在线玩家名、缓存离线玩家名                                                    | 优先返回在线/缓存 UUID，不发外部请求                                |
| ADP-03 | 离线模式未缓存用户名                                                              | 按 `OfflinePlayer:<name>` 计算 UUID；不请求 Mojang                  |
| ADP-04 | 正版模式未缓存用户名且 Mojang 可用/查询失败                                       | 成功返回 UUID；失败记录本地化警告并返回 null                        |
| ADP-05 | online-mode 从已启用 Velocity、Bungee 配置、server.properties、运行时默认逐级取值 | 优先级与配置启用条件正确；无有效配置时采用服务器运行模式            |
| ADP-06 | 以 finite 坐标传送在线玩家到已加载世界                                            | 主线程传送成功并应用坐标、yaw、pitch                                |
| ADP-07 | 传送使用 null、NaN/Infinity 坐标、离线玩家、未加载世界或异步线程                  | 返回 false，不执行无效传送                                          |
| ADP-08 | 调用当前 disconnect 实现                                                          | 主线程在线玩家收到纯文本踢出原因并返回 true；离线、空值、异步调用返回 false                    |
| ADP-09 | server.properties 中 level-name 合法且世界存在 dimensions/minecraft               | worldParentDirectory 返回该维度目录                                 |
| ADP-10 | level-name 目录无 dimensions/minecraft                                            | worldParentDirectory 返回服务端目录                                 |
| ADP-11 | level-name 缺失、空白、路径穿越、读取错误                                         | 回退 Bukkit 首个已加载世界名；没有已加载世界时抛出明确异常          |
| ADP-12 | worldDirectory 使用合法名称、null、路径分隔符或非法字符                           | 合法名解析至父目录子目录；非法名抛出参数错误；不创建目录            |
| MSG-01 | 分别以玩家、控制台、LOG_ONLY 发送字符串、Component、操作结果                      | 目标收到格式正确消息；LOG_ONLY 只写纯文本日志                       |
| MSG-02 | 发送 Success、Failure、Partial 操作结果                                           | 玩家显示本地化操作名及适用原因，不含 operation ID；控制台保留 ID；Partial 明确提示部分完成 |
| MSG-03 | 比较 zh_CN/en_US 的新增物理、爆炸及命令键                                         | 两种资源键齐全且 YAML 可解析；自定义语言缺项按中文回退              |

## 目前不列为通过项的能力

以下在当前源码中标为未实现、占位或尚未接入，不能作为已实现功能验收：延迟卸载、PVP/全局方块物理或全局爆炸覆盖、SIFT 环境、API 操作者授权入口（调用方负责）。JSON/MySQL 不会静默切换，必须显式指定迁移源。实际 Paper 服务注册、插件类路径及服务事件仍需执行 API-01 至 API-07 的集成用例；地图准入监听用例列在 ACCESS-01 至 ACCESS-07。

## 执行记录

JUnit 单元用例由 Maven 构建自动执行，具体结果见各模块 `target/surefire-reports/`。逐项执行 Paper/MV/LP 集成用例时记录服务器和插件版本；MySQL 用例记录数据库版本及 schema 初始化结果。本文列出的人工验收场景仍须逐项记录结果、证据、版本、执行人和日期。

## 本轮新增自动用例

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| `StorageRecoveryTest` | 7 | 完整快照、空组、全部操作、幂等重试、全量冲突预检、缓存无底层读取、失败写入不发布、显式切换校验、完成标记中断收尾 |
| `QueuedWorldBaseProjectionTest` | 4 | 调用线程不等待 LP、授权撤销 FIFO、失败自动重试、重启重放、损坏队列拒绝 |
| `RecoveryJournalTest` | 3 | 原始 owner/环境/参数持久化、目录替换和 UID 改变拒绝、路径穿越/符号链接拒绝 |
| `V3MigrationRecoveryTest` | 4 | dry-run 不写目标、部分写入后冻结计划恢复、源改变拒绝、冲突报告不覆盖 |
| 既有 NBT / Messages 测试扩展 | 2 | Paper 26 共用根 level.dat、双语键及全部操作/原因枚举覆盖 |

## 恢复与维护验收场景

| ID | 操作 | 预期 |
|---|---|---|
| REC-01 | 在保存完整创建请求后、保存地图前中断 | 重启恢复原 UUID、owner、组、生成参数与 ACTIVE 状态 |
| REC-02 | 世界创建/导入成功后、初始化或角色步骤中断 | 使用现有世界继续，不创建第二张图，不丢原 owner |
| REC-03 | 删除中断于世界仍在、目录已无但 MV 记录仍在、MV 已移除但目录仍在 | 重启分别继续世界删除、移除 MV 记录、按持久身份清理剩余目录，最终清理角色和空组 |
| REC-04 | 删除中断后用不同目录替换原世界 | 拒绝删除替换目录，保留 DELETING 与失败原因 |
| REC-05 | 旧 PENDING 无完整请求 | 保留数据并提示人工核查，不猜测 owner 或生成器 |
| QUE-01 | LP 写入不可用后授予/撤销并重启 | MMC 角色立即生效；Partial 表示待同步；队列落盘并自动重试；不乱序恢复已撤销权限 |
| MAINT-01 | 控制台或 RCON 执行 load/recover/operations/export；玩家及命令方块执行维护命令 | 控制台与 RCON 可执行，结果仍输出服务器控制台；玩家及命令方块被拒绝 |
| MIG-13 | v3 dry-run，再 apply，注入半完成写入 | dry-run 只有报告；apply 先备份与冻结计划，重试补齐一致子集；完成才写标记 |
| SQL-01 | JSON → MySQL → 另一 MySQL → JSON | 空组、地图、出生点、角色、全部操作一致；切换前校验源指纹 |
| SQL-02 | 目标冲突、源变化、部分写入或标记后归档前中断 | 冲突不覆盖，源变化拒绝，部分写入恢复，标记后归档安全收尾 |
| PERF-01 | 在事件查询时禁止仓储访问 | 缓存查询仍成功；不表示生产负载性能已验收 |

## 可重复的真实集成测试

先执行 `mvn clean package`，再运行（运行时目录提供 Paper JAR、libraries、versions、cache 以及 LP/MV；脚本复制至临时目录）：

```shell
python3 plugin/src/test/integration/run.py --runtime server --java-home /Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
python3 plugin/src/test/integration/run_mysql.py --mysql-base /private/tmp/mysql-8.4.11-macos15-arm64 --runtime server --java-home /Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
```

MySQL 脚本要求预先准备兼容系统的 MySQL 二进制，创建独立临时 datadir 和测试库，只绑定回环地址，结束关闭实例。两组真实集成因依赖运行时/端口/二进制，不属于普通 Maven 单元门禁。

### 2026-10-01 实际结果

- JDK 21 / Maven：60 个 JUnit 用例由打包前 test 阶段执行，最终报告见各模块 Surefire。
- Paper 26.2 build 129、Multiverse-Core 5.7.2、LuckPerms 5.5.71：三轮启停全部通过。验证外部 API 服务注册/撤销与共用类路径、三种 FLAT 环境与方块层、实际 LP 授权撤销、ACTIVE 加载、删除保护、原 owner 恢复、三类删除中断、替换目录拒绝、v3 dry-run/apply 及导出。报告位于 `/var/folders/hs/bq2njdx955169mcb_b3mf6jw0000gn/T/mmc-v4-todo-zdsji0sd/report.json`，同目录保留各轮日志。
- MySQL 8.4.11：17 项真实 JDBC 检查通过，包含双向迁移、换目标、部分写入恢复、冲突/源变化拒绝、幂等角色及外键清理；复跑脚本同样通过，报告 `/var/folders/hs/bq2njdx955169mcb_b3mf6jw0000gn/T/mmc-mysql-97e6247i/snapshot/mysql-report.json`。
- 准入监听通过合成 Bukkit 传送/重生事件验证；真实客户端登录、重生、在线踢出、生产规模性能和数据库升级尚未验收，不能据此标为通过。测试实例已关闭。

## 控制台命令迁移验收（2026-10-01）

create/import/init/delete 从 `/world` 移至 `/mapadmin`，旧入口不再执行或补全这些子命令。`MapAdminCommandTest` 新增 3 个自动用例：创建维度/参数路由、导入参数拒绝与初始化路由；六位一次性删除码及同名地图身份校验；非控制台执行及补全拒绝。该轮 Maven 门禁为 63 个用例；后续单元覆盖补全见下文。删除确认仍为 10 秒有效，直接 `confirm` 无效。

迁移后使用新构建复跑 Paper 26.2/MV 5.7.2/LP 5.5.71 三轮集成测试全部通过；测试脚本使用 `/mapadmin create` 创建四张地图并验证恢复、维护、服务注册与撤销。报告：`/var/folders/hs/bq2njdx955169mcb_b3mf6jw0000gn/T/mmc-v4-todo-jou2f3vy/report.json`；测试服结束已关闭。

## 单元测试覆盖补全（2026-10-01）

以当前工作区为基线，原 63 个用例全部通过；本次新增 59 个用例，合计 122 个（api 4、plugin 118）。补充传送/按需加载、世界版本、实际区块层生成、监听器、角色失败语义、生命周期输入保护、玩家/控制台命令、缓存快照及通知分流。仅修改测试和本清单，不修改业务源码或增加依赖。

验证使用 JDK 21 执行 `mvn -o --no-transfer-progress clean test`，实际 122 个测试全部通过：0 failures、0 errors、0 skipped。未启动 `server` 测试服或连接外部服务；上文历史集成结果不属于本次验证。尚未覆盖为普通单元门禁的运行时路径包括完整 Multiverse 创建/初始化/删除/恢复、LP 适配器实际节点写入、玩家身份解析与 Mojang 网络、登录/重生后的调度复核及安全默认世界回退。这些路径继续使用集成验收，不能将本次单测通过视为全路径或 100% 覆盖率。

## 设置反馈与创建/导入保护验收

`MessagesTest` 验证中文风险原文、参数渲染和双语键一致性；`MapWorldCommandTest` 覆盖设置参数反馈、开启二次确认、防重放、过期、地图/设置/玩家绑定、权限复核、关闭取消确认、非法确认参数与失败不报成功。

真实 Paper 验收：通过 `/mapadmin create`、`/mapadmin import` 和 API 分别创建/导入地图，确认初次加载即阻止方块更新、落沙和爆炸；重启后设置仍为 false。依次执行 physics/explosion 的 true、true confirm，核对风险说明、10 秒有效期和成功参数。已有地图和 v3 迁移维持原设置；显式执行 `/mapadmin init` 或 API `initialize` 后两项均关闭，公开状态等其他数据保留，重启后仍关闭；后续世界初始化失败时也保持关闭。此场景须在测试服另行验证。
