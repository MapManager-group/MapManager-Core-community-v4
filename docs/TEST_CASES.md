# MapManager v4 已实现功能测试用例

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
| UNIT-SVC-01 至 UNIT-SVC-07       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/MapServicesTest.java`                     | 角色组/单图范围、有效角色继承、公开和 ACTIVE 准入、LP 部分失败、物理/爆炸字段隔离、`MapManager` 服务图 getter |
| UNIT-WORLD-01 至 UNIT-WORLD-04   | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/world/WorldGatewayImplTest.java`      | `server.properties` 地图根目录定位、维度目录选择和世界名路径穿越防护                                          |
| UNIT-NBT-01 至 UNIT-NBT-03       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/world/LevelDatVersionReaderTest.java` | 压缩 NBT 读取数据版本；文件损坏、字段缺失或类型无效时返回空值                                                 |
| UNIT-ALIAS-01                    | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/MapAliasServiceImplTest.java`             | legacy、section 与 RGB 颜色代码清理                                                                           |
| UNIT-MIG-01 至 UNIT-MIG-02       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/migration/V3MigrationSourceTest.java` | v3 地图、组、角色范围和物理/爆炸设置解析；组地图清单不一致时拒绝                                              |
| UNIT-MSG-01 至 UNIT-MSG-03       | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/notification/MessagesTest.java`       | 英文资源与安全占位符、缺项中文回退、占位参数校验                                                              |
| UNIT-CONFIG-01 至 UNIT-CONFIG-05 | `plugin/src/test/java/studio/lorian/mapmanager/plugin/internal/storage/StorageConfigTest.java`       | JSON 默认值、MySQL 配置读取与空白裁剪、未知类型及必填项/端口/数据库名校验                                     |
| UNIT-LIFE-01 至 UNIT-LIFE-06     | `plugin/src/test/java/studio/lorian/mapmanager/plugin/impl/WorldLifeCycleServiceImplTest.java`       | 创建和导入覆盖三种生成器与三种已实现环境的组合；SIFT 保持未支持；校验 FLAT 层高和非 FLAT 的 flat.height 拒绝 |

当前共 40 个 JUnit 用例，会在 `mvn test`、`mvn package` 及更晚的 Maven 生命周期自动执行。服务注册事件/插件类路径、Bukkit 事件、Multiverse/LuckPerms 实际写入、MySQL 建表及完整启动迁移仍需真实集成环境，不纳入这批单元测试。

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
| LIFE-01 | 控制台执行 `/world create arena flat normal`                               | 以 FLAT 生成器和 NORMAL 环境创建世界、登记地图和组，初始化 MV 配置，地图最终为 ACTIVE，默认组/别名为 `arena`             |
| LIFE-02 | 玩家执行 create、import、init 或 delete                                    | 返回无权限结果；世界、地图、存储均未改变                                                                                 |
| LIFE-03 | 为新地图指定 `group=team admin=AdminA`                                     | 地图进入 `team`；管理员授予保存到组；LP 地图组继承 `worldbase` 并投影 AdminA                                             |
| LIFE-04 | 在 `arena` 所在组创建 `arena2`                                             | 共用同一组；组的地图列表包含两张图，组管理员/建筑者对两图生效                                                            |
| LIFE-05 | 创建地图时省略 alias/group                                                 | 两者均使用世界名                                                                                                         |
| LIFE-06 | 创建地图时提供带 Minecraft 颜色代码的 alias                                | 保存别名文本时去掉颜色代码；MV 别名根据公开状态附加颜色                                                                  |
| LIFE-07 | 组合 `flat/normal/void` 与 `normal/nether/the_end` 创建并导入               | Multiverse 配置中的生成器和 Bukkit 环境分别匹配请求；FLAT+非 NORMAL 使用编码的内置生成器；VOID 需要 VoidGen       |
| LIFE-08 | 创建或导入时选择 `sift` 环境                                               | 返回 `UNSUPPORTED_ENVIRONMENT`；不改变世界或登记 PENDING 地图                                                          |
| LIFE-09 | FLAT+NORMAL 使用 `flat.height=2`、`320`；FLAT+NETHER/THE_END 使用 `2`、`254` | 各组合在对应高度范围内通过校验；超出环境上限拒绝                                                                     |
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
| LIFE-20 | 在根目录准备有效未注册世界，执行 `/world import arena-copy normal nether` | 以 NORMAL 生成器和 NETHER 环境导入，执行初始化、角色投影和 ACTIVE 登记；现有区块不变 |
| LIFE-21 | 导入目录不存在、不是目录或缺少 level.dat                                   | 返回 WORLD_NOT_FOUND 或 INVALID_INPUT；不创建 MMC 地图记录                                                               |
| LIFE-22 | 创建或导入 VOID 生成器但 VoidGen 未启用                                    | 返回世界后端失败；不注册为 ACTIVE                                                                                        |
| LIFE-23 | 导入目录为符号链接或名称试图逃逸世界根目录                                 | 输入校验或目录检查拒绝，不触碰根目录外文件                                                                               |
| LIFE-24 | 创建/导入过程中分别令 MV 操作、初始化、存储或 LP 投影失败                  | 返回 Failure/Partial；记录 operation ID 和失败阶段；保留源码定义的 PENDING/失败记录以供检查                              |
| LIFE-25 | 成功创建或导入后检查世界属性                                               | difficulty peaceful、creative、自动加载开启、出生区块常驻关闭；普通生物生成关闭、MISC 开启；规定 gamerule 全部生效并保存 |
| LIFE-26 | 对已加载且已登记地图执行 `/world init <name>`                              | 控制台重放 MV 与 gamerule 初始化；不重建角色、不改变地图状态；返回成功                                                   |
| LIFE-27 | init 目标不存在、未登记或未加载                                            | 分别返回 MAP_NOT_FOUND 或 WORLD_NOT_FOUND；不产生其他世界变更                                                            |
| LIFE-28 | init 名称含非法字符或由玩家执行                                            | 返回 INVALID_INPUT/NO_PERMISSION；不执行 MV 操作                                                                         |
| LIFE-29 | 在 FLAT+NETHER/THE_END 创建 `flat.height=64` 或自定义 `generator-settings`；检查区块和 Environment | 生成预期平坦层和 biome；选定环境保留；非法方块、非法 JSON 和总层高超过 254 在登记前拒绝 |
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
| SET-12   | 物理/爆炸关闭但地图未登记、设置开启的对照世界发生事件           | 未登记世界不受本监听器影响；不同地图设置互不串扰                                  |
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
| ROLE-11 | `/world admins\|builders\|visitors` 由可进入当前地图玩家执行       | 输出直接授予该角色的玩家，不把继承角色错误列为直接授予                               |
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
| TRV-06 | teleport 玩家传送失败                                     | 返回 WORLD_BACKEND_FAILED，通知包含操作 ID                                                                                               |
| TRV-07 | 管理员在同一地图执行 `/world kick <online-player>`        | 目标到 MV 默认世界出生点；操作者收到结果，目标收到被踢提示                                                                               |
| TRV-08 | 操作者不是 ADMIN，目标离线或目标不在同一世界              | 分别返回 NO_PERMISSION、PLAYER_NOT_FOUND、PLAYER_NOT_IN_MAP；不传送                                                                      |
| TRV-09 | 在未登记地图执行 kick；尝试踢出 MV 默认地图中的玩家       | 返回 MAP_NOT_FOUND 或 WORLD_BACKEND_FAILED；世界状态不变                                                                                 |
| TRV-10 | MV 默认世界不存在、默认出生点无效或 teleport 失败         | 返回 WORLD_BACKEND_FAILED；目标位置保持不变或遵循 Bukkit 实际结果                                                                        |
| CMD-01 | 分别以玩家/控制台执行 `/world`、未知子命令、`/wtp` 空参数 | 显示对应多语言用法                                                                                                                       |
| CMD-02 | 为不同角色检查 `/world` 一级补全                          | 仅出现当前发送者可调用的命令；ADMIN 专属子命令对非管理员隐藏                                                                             |
| CMD-03 | 补全 `/world tp`、`/wtp`                                  | 只建议该玩家可进入的地图                                                                                                                 |
| CMD-04 | 补全 `/world kick`                                        | 只建议当前同图在线玩家                                                                                                                   |
| CMD-05 | 控制台补全 init/delete/import/create                      | init 仅提示非 DELETED 地图；delete 仅提示 ACTIVE/DELETING 且命名符合删除正则的图；import 限服务器根目录候选；create/import 分别补全生成器、环境和参数键 |
| CMD-06 | 成员命令补全 add/remove 和成员名                          | 只按准入/ADMIN 权限提供操作；在线玩家、同图目标和已有成员提示正确；无缓存名称时可用 UUID                                                 |

## 删除与操作记录

| ID     | 前置条件与操作                                       | 预期结果                                                                                   |
|--------|------------------------------------------------------|--------------------------------------------------------------------------------------------|
| DEL-01 | 默认配置对 `arena_bak` 执行两步删除                  | 第一步产生 6 位确认码并绑定地图 ID；10 秒内输入后删除世界及 MMC/LP 关联                    |
| DEL-02 | 尝试删除不符合 `.*_bak$` 的地图                      | 命令/API 均拒绝；世界和地图数据保持                                                        |
| DEL-03 | 玩家执行 delete                                      | 返回 NO_PERMISSION；不产生确认码或删除操作                                                 |
| DEL-04 | 对错误码、过期码、已消费码、另一地图确认码执行第二步 | 分别拒绝；只有匹配且未过期的一次性码能确认对应地图                                         |
| DEL-05 | 删除 MV 默认世界或 Bukkit 首个加载世界               | 拒绝 ACCESS_DENIED；文件、地图和玩家不变                                                   |
| DEL-06 | 删除含在线玩家地图                                   | 拒绝 OPERATION_IN_PROGRESS；不卸载/删除世界                                                |
| DEL-07 | 地图状态为非 ACTIVE/DELETING，或地图记录不存在       | 返回 OPERATION_IN_PROGRESS/MAP_NOT_FOUND                                                   |
| DEL-08 | ACTIVE 地图的 MV 世界、目录或 level.dat 缺失         | 返回 WORLD_NOT_FOUND；不误删 MMC 记录                                                      |
| DEL-09 | 世界路径指向根目录外、符号链接或不符合预期父目录     | 拒绝 INVALID_INPUT；不得删除目录外内容                                                     |
| DEL-10 | 删除同组中仍有其他地图的一张地图                     | 删除该图并移除此世界的 LP 继承；保留组、其他地图和组角色                                   |
| DEL-11 | 删除组中最后一张地图                                 | 清除该组 LP 成员/组继承，移除组角色、地图和空组                                            |
| DEL-12 | MV 删除、LP 清理或存储删除中途失败                   | 保留 DELETING 与失败 operation 阶段；修复原因后可重新走确认并安全重试                      |
| DEL-13 | 检查成功生命周期操作记录                             | operation ID 唯一，阶段依次包含 STARTED、WORLD_CHANGED、DATA_SAVED（创建/导入）、COMPLETED |
| DEL-14 | 检查生命周期失败操作记录                             | 阶段记录 FAILED 和对应 FailureReason；通知 operation ID 与仓储记录一致                     |

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
| MIG-12 | 首次迁移中途目标仓储写入失败后重试                         | 验证当前实现的部分写入冲突保护；人工核查后恢复，不把自动恢复当作已实现能力 |

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
| ADP-08 | 调用当前 disconnect 实现                                                          | 返回 false；此项记录为占位行为，不判定为功能通过                    |
| ADP-09 | server.properties 中 level-name 合法且世界存在 dimensions/minecraft               | worldParentDirectory 返回该维度目录                                 |
| ADP-10 | level-name 目录无 dimensions/minecraft                                            | worldParentDirectory 返回服务端目录                                 |
| ADP-11 | level-name 缺失、空白、路径穿越、读取错误                                         | 回退 Bukkit 首个已加载世界名；没有已加载世界时抛出明确异常          |
| ADP-12 | worldDirectory 使用合法名称、null、路径分隔符或非法字符                           | 合法名解析至父目录子目录；非法名抛出参数错误；不创建目录            |
| MSG-01 | 分别以玩家、控制台、LOG_ONLY 发送字符串、Component、操作结果                      | 目标收到格式正确消息；LOG_ONLY 只写纯文本日志                       |
| MSG-02 | 发送 Success、Failure、Partial 操作结果                                           | 分别显示本地化操作名、原因和 operation ID；Partial 明确提示部分完成 |
| MSG-03 | 比较 zh_CN/en_US 的新增物理、爆炸及命令键                                         | 两种资源键齐全且 YAML 可解析；自定义语言缺项按中文回退              |

## 目前不列为通过项的能力

以下在当前源码中标为未实现、占位或尚未接入，不能作为已实现功能验收：按需加载未加载世界、`PlayerGateway.disconnect`、PVP/全局物理或全局爆炸覆盖、JSON 与 MySQL 自动迁移、迁移 dry-run 与自动恢复、未实现的 API 授权入口。实际 Paper 服务注册、插件类路径及服务事件仍需执行 API-01 至 API-07 的集成用例；地图准入监听用例列在 ACCESS-01 至 ACCESS-07。

## 执行记录

JUnit 单元用例由 Maven 构建自动执行，具体结果见各模块 `target/surefire-reports/`。逐项执行 Paper/MV/LP 集成用例时记录服务器和插件版本；MySQL 用例记录数据库版本及 schema 初始化结果。本文列出的人工验收场景仍须逐项记录结果、证据、版本、执行人和日期。
