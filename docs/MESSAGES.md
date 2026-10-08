# 通知、错误和语言

## 语言资源

- 默认语言由 `config.yml` 的 `language: zh_CN` 指定。
- 内置资源为 `plugin/src/main/resources/langs/zh_CN.yml` 和 `en_US.yml`；启动时复制到插件数据目录 `langs/`，运行时读取用户选定的文件。
- 选定语言文件缺失或 YAML 无效会导致插件启动失败。某个翻译键缺失时回退到内置中文资源。
- 可见文案和错误需使用语言键；新增或修改键时同时更新中英文资源。

## MiniMessage 与通知

`Messages` 使用 MiniMessage 生成 Adventure `Component`，占位符采用未解析占位符，避免参数内容被解释为格式标签。玩家操作结果使用 `result.player_success`、`result.player_failure`、`result.player_partial`，不传入操作 ID；完整结果同时发送到控制台，使用原 `result.success/failure/partial` 模板保留 ID。新增键缺失时回退内置中文，已有自定义结果模板仅用于控制台/日志。日志输出使用纯文本序列化。新增用户提示通过 `OperationNotifier.notifyKey` 或 `Messages` 语言键发送。

项目通知不使用 Bukkit `ChatColor`。Multiverse 别名中的 `&2`、`&3` 是写入 Multiverse 配置的旧式颜色格式，不是聊天消息格式；保留它们是为了设置 MV 别名颜色。

## 错误文本范围

启动、迁移、存储和业务层主动生成的诊断由 `Messages` 本地化。部分底层库或 Paper 自身抛出的原始异常文本可能随依赖版本变化，当前会作为异常详情附加输出，未必有翻译。
