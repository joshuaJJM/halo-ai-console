# Halo AI Console

AI-powered chat, image generation, audit, and model management for Halo Console.

Halo AI Console 是一个由社区维护的 Halo Console 插件，基于 AI Foundation 提供聊天、图像生成、多模态输入、会话历史、上下文管理、模型策略和审计日志能力。

本项目不是 Halo 官方项目，不使用 Halo 官方 Logo 或品牌资源。

## Highlights

- AI chat workspace inside Halo Console
- AI Foundation model discovery and capability-aware default model selection
- Chat, multimodal input, and image generation through backend jobs
- Server-sent events for live generation updates without frontend polling
- Persisted conversation history, favorites, tags, search, and message editing
- Context compression, session memory, and token usage estimates
- User-scoped settings plus administrator model policy and usage limits
- Audit logs for user, model, token usage, timing, IP, browser, and operating system
- Markdown rendering with local bundled sanitization assets

## Repository

The plugin source lives in [`plugin-halo-ai-console`](plugin-halo-ai-console/).

Current plugin version: `0.2.7`.

## Requirements

- Halo `>= 2.25.0`
- AI Foundation installed and enabled
- At least one enabled language or multimodal model for chat
- At least one enabled image generation model for image mode
- Halo attachment storage configured for image upload

## Packaging

Packaged artifacts are committed under:

```text
dist/halo-ai-console-0.2.7.jar
```

The `dist/` directory keeps historical packaged jars for quick download and regression comparison.

For implementation details, permissions, storage behavior, third-party resources, and API notes, see [`plugin-halo-ai-console/README.md`](plugin-halo-ai-console/README.md) and [`PRIVACY.md`](PRIVACY.md).

## 中文说明

### 功能

- 在 Halo Console 中提供 AI 聊天和图像生成工作区。
- 根据 AI Foundation 的能力默认值选择语言、多模态和图像生成模型。
- 支持后台 Job、SSE 推送、停止生成、会话历史、搜索、标签、收藏、上下文摘要和消息编辑。
- 支持图片及常见文本文件上传；图片通过插件后端代理上传到 Halo 附件库。
- 支持 Markdown、代码块、轻量 Mermaid 和有限 LaTeX 格式；渲染结果会经过本地打包的 DOMPurify 清理。
- 支持按用户权限查看自己的调用记录或全部审计记录，并在插件设置中配置模型白名单、限流、配额和保留策略。

### 安装和配置

1. 安装并启用 AI Foundation，配置至少一个语言/多模态模型和一个图像生成模型。
2. 安装 `dist/halo-ai-console-0.2.7.jar`。
3. 在 Halo 插件设置中配置默认模型、允许使用的模型、并发与配额、图片大小和 Job/日志保留策略。
4. 为用户授予 Halo AI 角色；需要查看全部审计记录或迁移旧数据时，再授予对应的管理员权限。

### 费用

插件本身免费。使用模型可能产生由 AI Foundation 中配置的第三方服务商收取的 API 费用，费用由 Halo 站点管理员承担。停用相关模型、删除 API 配置或禁用插件即可停止新的调用。

### 隐私和数据

完整的数据类型、默认保留时间、个人数据导出/删除、附件处理、卸载行为、审计日志权限和第三方资源清单请阅读 [`PRIVACY.md`](PRIVACY.md)。

## Privacy

Halo AI Console stores conversation snapshots, personal settings, job records, image cache metadata, usage counters, and audit logs in Halo-managed ConfigMaps. When a user sends a chat, image, or file request, the relevant message content and attachment references are forwarded to the AI Foundation model selected by the user or administrator. The final third-party model provider depends on the AI Foundation configuration of the Halo installation. See [`PRIVACY.md`](PRIVACY.md) for the complete policy.

## Support

Report issues at [GitHub Issues](https://github.com/joshuaJJM/halo-ai-console/issues).

## License

MIT
