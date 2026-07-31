# Changelog

本项目遵循 Semantic Versioning。历史制品一经发布即保持不变。

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
