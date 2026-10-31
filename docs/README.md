# MapManager Core v4

MapManager Core（MMC）管理地图元数据、地图角色和准入，通过 Multiverse-Core 执行世界操作，通过 LuckPerms 投影建造权限。

支持地图创建、导入、初始化、删除、按需加载与中断恢复；支持地图组角色、单图访客、公开状态、展示名、出生点、物理和爆炸设置。数据可保存到 JSON 或 MySQL，支持 v3 数据迁移和外部插件 API。

## 安装

| 项目 | 要求 |
|---|---|
| 服务端 | Paper；编译依赖为 1.20.1 API，插件声明 `api-version: '1.20'` |
| Java | 至少 21；服务端要求更高版本时以服务端为准，Paper 26.2 验收使用 JDK 25 |
| 必需插件 | LuckPerms、Multiverse-Core |
| 可选插件 | VoidGen；创建或导入 `void` 类型时必需 |

1. 停服，备份世界、LuckPerms 和 `plugins/MapManager-Core/`。
2. 将依赖插件及 MMC JAR 放入 `plugins/`，启动服务器。
3. 编辑生成的 `plugins/MapManager-Core/config.yml`，完整重启使配置生效。
4. 在 LuckPerms 的 `worldbase` 组配置服务器需要的建造权限。首次缺失时 MMC 仅创建该组并继承 `default`，不代配具体权限。

从 v3 升级前先看[数据迁移](MIGRATION.md)。当前实际验收版本与未覆盖环境见[测试指南](TEST_CASES.md)，编译目标不代表所有 Paper 版本都已实测。

## 配置

完整默认值见 [config.yml](../plugin/src/main/resources/config.yml)。常用项如下：

| 配置 | 默认值 | 作用 |
|---|---|---|
| `language` | `zh_CN` | 语言文件位于数据目录 `langs/`，内置中英文 |
| `storage.type` | `json` | `json` 或 `mysql`；MySQL 数据库和账户需提前创建 |
| `storage.mysql.*` | 见配置资源 | MySQL 连接，默认启用 SSL；部署前更换示例密码 |
| `storage.migrate-from` | 空字符串 | 显式切换后端时填写来源，见[存储](STORAGE.md) |
| `storage.migration-source.mysql.*` | 见配置资源 | 切换时使用的原 MySQL 连接 |
| `creation.blocked-world-name-regex` | `.*_bak$` | 禁止创建/导入的地图名，匹配完整名称 |
| `deletion.allowed-world-name-regex` | `.*_bak$` | 允许命令删除的地图名，匹配完整名称 |
| `access-control.strict-map-entry` | `true` | 检查外部传送、登录、重生、换世界及命令撤权后的准入 |
| `migration.dry-run` | `false` | 启动时仅预检 v3 数据，不写入目标 |

直接更改存储类型或 MySQL 目标会被拒绝，须走[显式切换流程](STORAGE.md)。放宽删除正则前确认目标范围；API 删除不使用该命令正则。

## 地图角色与准入

| 身份 | 范围 | 能力 |
|---|---|---|
| `ADMIN` | 地图组 | 管理组内地图和成员，包含建筑者、访客能力 |
| `BUILDER` | 地图组 | 进入组内地图，并获得 `worldbase` 建造权限 |
| `VISITOR` | 单张地图 | 进入该图，不获得建造权限 |
| 公开地图玩家 | 单张地图 | 允许进入，不产生访客授权记录 |

MMC 根据角色和公开状态决定准入；OP 或 `multiverse.access.<世界>` 节点不能替代 MMC 角色。未登记世界由服务器原有配置管理。关闭严格准入会停用监听器的强制检查，MMC 自身传送仍检查准入。

`/wtp <默认世界名>` 和 `/world tp <默认世界名>` 可直接传送至默认世界出生点，无需登记为 MMC 地图；TAB 同样推荐该世界。Multiverse 默认世界始终允许进入，旧 MMC 记录的状态、角色和单图物理/爆炸设置不对它生效；MMC 不管理它的设置、生命周期，也不投影建造权限。变更默认世界后需重启 MMC。已有 gamerule/MV 配置不会自动还原，建造限制仍由现有保护插件和 LP 配置控制。

使用地图专用的 LP 组名：MMC 会复用同名 LP 组并让管理员/建筑者继承，最后一张地图删除时还可能删除该组。不要把 `group=` 指向全服管理组；`default`、`worldbase` 已被禁止。

角色保存后立即影响 MMC 准入；LP 投影使用持久队列。`Partial(PERMISSION_SYNC_PENDING)` 表示 MMC 变更已生效、LP 尚待同步，不能按完全失败重做。队首持续失败会延后后续任务，需检查重试日志并保留队列文件。

## 开始使用

在控制台创建并指定首位管理员；不填 `admin` 不会自动分配管理员：

```text
mapadmin create buildworld flat normal alias=建筑地图 group=buildworld admin=Steve
```

有准入权限的玩家使用 `/wtp buildworld` 进入。管理员在图内执行：

```text
/world builder add Alex
/world visitor add Pat
/world setspawn
/world public true
```

全部参数、生成选项、补全和删除确认见[命令手册](COMMANDS.md)。

## 运行规则与运维

创建/导入默认私有，物理和爆炸均关闭；`init` 再次关闭两项并应用初始化规则，保留公开状态、角色、出生点和地图状态。初始化使用和平难度、创造模式、自动加载、关闭出生点常驻和常规生物生成，并关闭随机刻、火焰蔓延、天气、昼夜、生物破坏、生物生成和进度播报。

关闭物理会阻止方块物理和落沙，关闭爆炸会阻止方块及实体爆炸破坏。画、展示框及兼容服务端中的坐垫，其支撑丢失和方块挤占破坏始终被全局拦截，默认世界和未登记世界也适用；主动拆除仍允许。

遇到失败先用 `mapadmin operations` 查看，再按[存储与恢复](STORAGE.md)处理。业务快照不能替代世界和整个插件目录的备份。数据库凭据、角色快照和 RCON 仅交给受信任运维方，勿放到公共下载目录。

当前未实现 PVP 开关、空闲卸载、龙蛋传送保护、v3 全局物理/爆炸覆盖和配置热重载；`sift` 仅为预留环境。

## 从源码构建

使用 JDK 21 或以上及 Maven，在仓库根目录执行：

```shell
mvn clean package
```

产物为 `plugin/target/mapmanager-plugin-4.0.2-SNAPSHOT.jar`。Paper、LuckPerms、Multiverse-Core 由服务端提供。外部插件接入见 [API 指南](API_GUIDE.md)，仓库开发从[架构](ARCHITECTURE.md)开始。
