# 消息与语言

`config.yml` 的 `language` 默认 `zh_CN`。内置 [zh_CN.yml](../plugin/src/main/resources/langs/zh_CN.yml) 和 [en_US.yml](../plugin/src/main/resources/langs/en_US.yml) 首次复制到数据目录 `langs/`；运行时读取选定文件，修改后重启。

选定文件缺失或 YAML 无效会阻止启动；缺少单个键时回退内置中文。新增或修改可见文案须同步两种语言，通过 `Messages` 或 `OperationNotifier.notifyKey` 输出，不使用 `ChatColor`。

## 渲染与结果

- MiniMessage 输出 Adventure Component；动态参数用未解析占位符，避免被当作格式标签。日志使用纯文本。
- 玩家结果用 `result.player_success/failure/partial`，隐藏操作 ID；完整结果同时发送控制台，用 `result.success/failure/partial` 保留 ID。
- `OperationResult.detail` 非空时优先作为纯文本展示；稳定 reason 代码用于程序分支。依赖库原始异常详情未必有翻译。
- 设置成功文案带实际参数；物理/爆炸开启使用风险提示和 10 秒确认键。帮助键为 `command.help_*`、`maintenance.help_*`，缺键仍按中文回退。

Multiverse 别名的 `&2`（公开）和 `&3`（私有）是 MV 配置格式；MMC 展示名保存纯文本，不将这些颜色码用于聊天消息。
