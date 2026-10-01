# 2026-10-01 HTTP 测试环境接口验收

## 结论与范围

**不能判定所有功能正常。** 已通过源码梳理全部 32 个 HTTP 操作，并实际执行 177 次请求（包含重复复现）：MMW 103 次、Tuff service 36 次、Tuff message 38 次。已确认读取、部分可恢复写入与大部分边界校验正常，同时发现可复现问题。审核、创建/导入、下载、替换、实际删除和站内信推送未完成成功流程，不计为通过。

测试日期：2026-10-01，Asia/Shanghai。只使用用户指定的三个测试地址与 Hanamizu_ 账号；报告及证据不保存密码、AES key、JWT、上传凭证或邮箱。请求日志见 [脱敏证据](HTTP_TEST_EVIDENCE_2026-10-01.jsonl)，每行包含服务、方法、路径、场景、HTTP 状态、响应与网关请求编号。

| 服务标识 | 地址 |
| --- | --- |
| w / MMW | `https://api.voxisle.art/mapmanager/test/v1` |
| s / Tuff service | `https://api.voxisle.art/mapmanager/tuff_calcite/service/v1` |
| m / Tuff message | `https://api.voxisle.art/mapmanager/tuff_calcite/message/v1` |

源码入口：MapManager-web 控制器与 `docs/openapi.json`；MapManagerTuff 的 tuff-service/tuff-message 控制器。飞书审批是长连接回调，不是额外 HTTP 路由，本轮没有触发或验证飞书回调。

## 可复现问题

### 1. 站内信同步与列表、详情不一致（影响正常使用）

同一登录令牌下：

- `GET /inbox/sync` 返回 `unreadCount=1`，消息 ID `331116725609093`，收件人包含 `Hanamizu_`。
- `GET /inbox/list?pageNo=1&pageSize=10` 返回空数组、`total=0`。
- `GET /inbox/detail?id=331116725609093` 返回 403 `user.insufficient_access_permission`。
- 标题、已读/未读过滤均为空；重复请求仍出现上述结果。

示例请求编号：sync `08ee750122e89772ce3fce2b5f648e46`；list `34bebe3f0ead9914a6792c1b9c45b910`；detail `4571363808ec66715413da05e6f61a58`。

源码中 `InboxService.sync` 可直接读取 Redis 缓存，而列表和详情查询 MySQL，并连接消息主表与收件人表。这支持优先核查缓存与数据库一致性、服务是否连接同一数据库、消息主表/收件人状态与关联关系。**目前没有数据库或日志证据，不能断言是哪一种原因。** 不应把详情 403 简单视为普通权限不足。

建议由部署方只读核对消息 ID 对应主表、收件人表和 Redis 用户消息缓存，确认同步结果是否来自当前库；不要直接清空全部 Redis。

### 2. 站内信分页信息丢失（已定位源码）

`GET /inbox/list?pageNo=2&pageSize=1` 实际返回 `pageNo=1,pageSize=0`。其他合法分页同样返回错误 pageSize。

请求编号：`04b63c47453b807cb5dfed465d8b2c53`。

`MapManagerTuff/tuff-message/src/main/java/work/alsace/mapmanager/tuffmessage/application/repository/impl/InboxRepository.java:73` 在把 PageHelper 查询列表转换为普通列表后重新 `PageInfo.of(...)`，丢失原分页对象的页号、总数与页大小。该问题与上面的“查不到消息”是两个问题，不应假定修复分页就能解决缓存/持久数据不一致。

### 3. 参数错误被返回为 500（Tuff 与 MMW 人员接口）

| 服务 | 复现方式 | 实际 | 预期 |
| --- | --- | --- | --- |
| message | `GET /inbox/detail` 缺少 id | 500 `system.internal_error` | 400 参数错误 |
| message | `GET /inbox/detail?id=x` | 500 `system.internal_error` | 400 参数错误 |
| service | `POST /login`，JSON 为 `{` | 500 `system.internal_error` | 400 参数错误 |
| message | `POST /inbox/read`，JSON 为 `{` | 500 `system.internal_error` | 400 参数错误 |
| message | `DELETE /inbox/delete`，JSON 为 `{` | 500 `system.internal_error` | 400 参数错误 |
| MMW | `POST /mapinfo/builder/add`，JSON 为 `{` | 500 | 400 参数错误 |

Tuff `ExceptionFilter` 只为 `MethodArgumentNotValidException` 等设置专门处理；缺参、类型不匹配和消息解析异常落入 Throwable 的 500 处理。MMW 人员控制器直接使用 `context.bodyAsClass(Map.class)`，而申请接口使用已有 `RequestUtil`；同样非法 JSON 在 `/map/apply` 中正确返回 400。本轮只测试 builder/add 的非法 JSON，不把未调用的其他人员入口算作已复现。

### 4. MMW CORS 的 Cookie 模式不成立（条件性问题）

预检与实际响应同时返回：

```http
Access-Control-Allow-Origin: *
Access-Control-Allow-Credentials: true
```

使用跨域 `fetch(..., {credentials: 'include'})` 时，浏览器不会接受这种组合。使用原始 Authorization JWT 且不携带跨域 Cookie 的方式不受这一条件影响；本轮验证的是响应头，没有运行浏览器。源码对应 `FilterController.corsFilter`。

## 契约差异与已排除误报

- MMW 玩家接口要求原始 JWT；添加 Bearer 后返回 403，符合文档。Tuff 两服务支持原始 JWT 和 Bearer。
- 申请列表 `activating` 在 `WorldMapApplicationStatus` 中表示“已经过管理员批准，世界地图生效”，属于正常状态，不是卡在处理中。
- builder 添加/撤销返回 200 和 `PERMISSION_SYNC_PENDING`，MMC 直接角色列表已按请求变化。符合部分完成契约；没有服务器日志，无法确认 LuckPerms 后台投影最终完成。
- ZIP 内缺失或包含多个 level.dat 返回 415；损坏 level.dat、未来 DataVersion 的 ZIP 返回 200 并取得 storageKey。当前上传源码只检查存档布局，版本检查在导入执行/替换阶段，不能把上传成功视为存档兼容已通过。已修正 MapManager-web `docs/API_GUIDE.md` 中把版本检查写在上传阶段的文字。没有执行这些损坏存档的导入或替换。
- `POST /mapinfo/setting` 使用空别名返回 417；字段缺失返回 400。空别名更适合在输入阶段返回 400，目前记录为校验契约可改进项；随后确认原地图字段未改变。
- Tuff 服务器列表配置名为 `test1`，既有申请的 `serverName` 为 `testserver`。可能影响飞书回调按记录路由，需核查部署配置与历史记录；没有实际回调证据，不列为已确认故障。
- 站内信 `/microservices/inbox/push` 公网地址返回网关 403 HTML，而非应用 JSON。符合源码“不能开放公网”的边界；无法由此证明内部推送正常或异常。

## 全部接口覆盖表

“正常”仅指列出的场景；“部分”表示成功业务链尚未完成；所有保护接口均测试过无令牌拒绝。单账号场景不证明不同用户之间的数据隔离。

| 服务 | 方法 | 路径 | 已执行场景 | 结论 |
| --- | --- | --- | --- | --- |
| service | POST | `/login` | AES 加密密码登录成功、缺字段 400、非法 JSON | 部分异常：非法 JSON 500；未测试验证码激活后登录 |
| service | GET | `/login/captcha` | 返回 required=false | 当前未激活状态正常，未验证触发/验证码流程 |
| service | GET | `/health` | 200 online | 正常 |
| service | GET | `/lastseen` | 上次在线时间、无效令牌、Bearer、错误方法 | 正常 |
| service | GET | `/servers/list` | 正确返回测试 MMW URL | 正常；服务器名差异待核查 |
| service | GET | `/map/apply/list` | 1/2 页、页大小、筛选、非法参数、用户身份来自令牌 | 已有记录读取正常 |
| service | GET | `/map/replace/list` | 空列表、分页、筛选、非法参数 | 空记录场景正常，非空记录未验证 |
| MMW | GET | `/map/admins` | 管理地图列表 | 正常 |
| MMW | GET | `/map/access` | 可访问地图列表 | 正常；没有第二用户/公开地图对照 |
| MMW | GET | `/mapinfo/{worldName}` | 详情、有效角色继承、不存在地图拒绝 | 正常 |
| MMW | POST | `/mapinfo/setting` | 别名、爆炸、物理变更并恢复、缺字段、空别名 | 写入/恢复正常；publicize 本轮保持原值 |
| MMW | POST | `/delete` | 未登录拒绝、空 body 校验 | 部分：实际删除未执行 |
| MMW | POST | `/map/download` | 未登录拒绝、空 body 校验 | 部分：密码与最终下载链接未验证 |
| MMW | POST | `/map/replace` | 未登录拒绝、空 body 校验 | 部分：替换、恢复与异步通知未验证 |
| MMW | POST | `/map/apply` | 20 种名称/维度/参数/密码/QQ/理由/导入条件/重复地图场景，非法 JSON | 校验正常；新申请→审核→创建/导入未验证 |
| MMW | POST | `/map/upload` | 缺少 multipart、ZIP 布局、损坏/未来版本、路径名异常、小型有效布局 ZIP | 上传与布局校验通过；NBT/版本校验不在上传阶段 |
| MMW | POST | `/admin/map/review` | 无令牌、用户 JWT、现有本地微服务 JWT 均拒绝 | 部分：缺实际部署微服务 JWT；未审批任何申请 |
| MMW | GET | `/mapinfo/admin/list` | 直接管理员列表、缺参、不存在地图 | 正常 |
| MMW | POST | `/mapinfo/admin/add` | 缺参、未注册用户、已有管理员重复拒绝 | 部分：未给第二账号授权 |
| MMW | POST | `/mapinfo/admin/del` | 缺参、未分配 UUID 拒绝 | 部分：保留唯一原管理员，没有测试撤销真实管理员 |
| MMW | GET | `/mapinfo/builder/list` | 修改前后直接建筑者列表 | 正常 |
| MMW | POST | `/mapinfo/builder/add` | 添加自己、重复拒绝、未注册拒绝、缺参、非法 JSON | 正常流程通过；非法 JSON 500 |
| MMW | POST | `/mapinfo/builder/del` | 撤销本轮授予、缺参、未分配 UUID | 正常；LP 最终同步未验证 |
| MMW | GET | `/mapinfo/visitor/list` | 修改前后直接访客列表 | 正常 |
| MMW | POST | `/mapinfo/visitor/add` | 添加自己、重复拒绝、未注册拒绝、缺参 | 正常 |
| MMW | POST | `/mapinfo/visitor/del` | 撤销本轮授予、缺参、未分配 UUID | 正常 |
| message | GET | `/inbox/list` | 页大小/页号、标题、已读状态、非法参数 | 异常：分页丢失；与 sync 不一致 |
| message | GET | `/inbox/sync` | 原始 JWT/Bearer、缓存返回未读消息 | 返回可读取；与 list/detail 不一致，不能判为整体正常 |
| message | GET | `/inbox/detail` | sync 消息 ID、无权限/不存在 ID、缺参、错误类型 | 异常：本人 sync 消息 403；错误参数 500 |
| message | POST | `/microservices/inbox/push` | 公网未认证调用 | 网关拒绝符合内部边界；成功推送未验证 |
| message | POST | `/inbox/read` | 缺参/空列表拒绝、非法 JSON | 部分：非法 JSON 500；真实已读未验证 |
| message | DELETE | `/inbox/delete` | 缺参/空列表拒绝、非法 JSON | 部分：非法 JSON 500；真实删除未验证 |

## 数据变更与恢复

- 使用原有测试地图 `test26100102`；别名、physics、explosion 暂时变更后已恢复。最终详情与最初快照完全一致。
- ADMIN 始终保留 `Hanamizu_`。只临时为自己直接添加 BUILDER/VISITOR，再撤销本轮授予。最终直接 builder/visitor 名单均为空，与初始一致。
- 未删除世界、消息或已有角色；未提交有效新申请、未批准/驳回申请、未替换地图区块。
- 上传测试中有 3 个小型 ZIP 被接受（损坏 NBT、未来版本、最小有效布局），服务端可能保留解压缓存。没有上传删除接口，无法从这些端点清理；报告不承诺完全无残留。
- 自动审批曾拒绝对未知消息 ID 1 执行删除，以及以用户 JWT 对申请 ID 1 发送 accept。两条被拒绝命令均没有执行；后续采用空 body/空 ID 列表，完成不触及实际记录的拒绝测试。无需批准这些未知目标。

## 完成剩余验收所需信息

1. 可用于下载/替换的测试地图管理密码（与登录密码不同）。
2. 实际测试环境的 MMW 审核微服务 JWT，以及允许审批、导入/替换/删除的专用测试地图和记录。当前本地配置 JWT 与部署不匹配。
3. 允许访问的站内信内部推送地址、微服务 JWT，以创建本轮专用消息后验证 list/detail/read/delete 并清理。
4. 第二个已注册测试账号，验证 admin 加/删及真正的跨账号权限隔离。
5. 如需断言世界区块、LP 投影、邮件/飞书最终送达、异常恢复正常，还需要测试服务器日志/控制台或独立游戏验收。

本轮只更新测试报告、证据与矛盾文档，没有修改业务源码或部署服务。
