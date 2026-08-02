# Changelog

本项目遵循 Semantic Versioning。历史制品一经发布即保持不变。

## [0.3.5] - 2026-08-02

### Changed

- 将插件自有 Java 类从 `run.halo.aichatconsole` 迁移至 `io.github.joshuajj.haloaiconsole`，并同步更新端点导入与组件索引。

### Compatibility

- Requires Halo `>=2.25.0`.
- Requires AI Foundation `>=1.0.0-beta.5`.
- 插件 ID、HTTP API、权限、Halo Extension GVK 与 ConfigMap 存储格式保持不变；已有会话、消息、调用日志和图片缓存无需迁移。
- 从 `0.3.4` 热升级时，请在 Halo 插件页执行一次“重载插件”，或重启 Halo，以刷新包名迁移后的后端路由。
- 受 Halo 2.25.x 的 java-semver 解析限制，`plugin.yaml` 保持必装依赖 `ai-foundation: "*"`；插件启动校验器仍会严格拒绝缺失、无效或低于 `1.0.0-beta.5` 的 AI Foundation。

### Security

- 降低项目自有类与 Halo 官方或其他插件类发生命名空间冲突及维护方身份误解的风险。

### Privacy

- 未新增数据收集、第三方传输、遥测或外部资源。

## [0.3.4] - 2026-07-31

### Changed

- 将界面和用户提示中的通用技术术语恢复为 `Token` 和 `Job`，其他操作说明继续使用中文。

### Fixed

- 规避 Halo 2.25.x 对 AI Foundation 预发布依赖范围的解析缺陷，防止插件在安装阶段因 `LexerException` 失败。
- 新增启动期兼容性校验；AI Foundation 缺失、版本无效或低于 `1.0.0-beta.5` 时，插件会失败关闭并提供可操作的升级提示。

### Compatibility

- Requires Halo `>=2.25.0`.
- Requires AI Foundation `>=1.0.0-beta.5`.
- 受 Halo 当前 java-semver 解析限制，`plugin.yaml` 使用必装依赖 `ai-foundation: "*"`；实际最低版本由插件启动校验器强制执行。
- 未修改 API、存储格式、权限语义或模型调用逻辑。

### Privacy

- 未新增数据收集、第三方传输、遥测或外部资源。

## [0.3.3] - 2026-07-31

### Fixed

- 补充 Halo 应用市场要求的插件主页和问题反馈地址。
- 将插件安装后的默认启用状态改为关闭，由管理员确认配置后手动启用。

### Compatibility

- Requires Halo `>=2.25.0`.
- Requires AI Foundation `>=1.0.0-beta.5`.
- 未修改 API、存储格式、权限语义或模型调用逻辑。

### Privacy

- 未新增数据收集、第三方传输、遥测或外部资源。

## [0.3.2] - 2026-07-30

### Changed

- 将聊天操作、收藏筛选、模型类型、审计状态和个人数据操作提示统一为中文。
- 将面向用户的上传、配额、权限、上下文和图像生成错误改为清晰的中文提示。
- 将插件说明和管理员设置中的操作术语统一为中文。

### Compatibility

- Requires Halo `>=2.25.0`.
- Requires AI Foundation `>=1.0.0-beta.5`.
- 未修改 API 路径、存储格式、状态值或权限语义。

### Privacy

- 未新增数据收集、第三方传输、遥测或外部资源。

## [0.3.1] - 2026-07-29

### Changed

- 项目许可证改为 GNU AGPL v3。
- AI Foundation 强制依赖范围改为 `>=1.0.0-beta.5`。
- Favorite 操作增加图标。

### Compatibility

- Requires Halo `>=2.25.0`.
- Requires AI Foundation `>=1.0.0-beta.5`.

## [0.3.0] - 2026-07-28

### Changed

- 模型执行迁移到 AI Foundation Java SDK 的 `AiModelService`。
- 后台任务不再保存或重放用户浏览器 Cookie。
