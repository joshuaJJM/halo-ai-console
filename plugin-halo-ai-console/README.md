# Halo AI Console

AI-powered chat, image generation, audit, and model management for Halo Console.

Halo AI Console is a community-maintained Halo Console plugin backed by AI Foundation. It adds a modern AI workspace to the Halo admin console, including streaming chat, image generation, multimodal input, persisted conversation history, context management, model policy, and audit logs.

This project is not an official Halo project and does not use the official Halo logo or branding assets.

## Features

- Requires AI Foundation `>= 1.0.0-beta.5`.
- Adds a `Halo AI` console route and a settings / call-log route.
- Uses the AI Foundation Java SDK for chat, multimodal input, summaries, and image generation. The Console API is used only to discover enabled model options.
- `Default` model selection means the default model of the requested capability: language / multimodal for chat, image generation for `/image` or image mode.
- Administrators can configure global model policy in the Halo plugin settings: keep capability defaults, specify model names, and restrict the list of models users may select. The backend also rejects chat/image jobs that use a model outside the allow-list.
- Chat and image jobs are now pushed back to the browser through the plugin job SSE endpoint, with throttled running-state persistence to avoid excessive Kubernetes API writes during long streaming replies.
- Editing a user message supports saving only or saving and regenerating the following assistant response.
- Stores each conversation snapshot in its own Halo ConfigMap and keeps per-user settings, jobs, call logs, and image cache records in user-scoped plugin storage. Browser localStorage is only a fallback cache.
- Supports session rename, delete, global session/message/tag/favorite search, conversation-level tags, favorites, message editing, stop generation, retry, Markdown/plain-text copy, export, drag-and-drop upload, simple text-file ingestion, token estimates, conversation summaries, and context compression.
- Uploads user images through a plugin backend proxy before forwarding them to the Halo attachment library. The per-image size limit is checked before the Halo upload call and again when session/image-cache metadata is saved.
- Automatically or manually compresses old messages when needed. The summary is saved into session memory so later model calls keep the important facts even when old message rows are not included.
- Detects legacy `AiChatSession` / `AiChatMessage` / call-log / image-cache extension objects, prompts the user to migrate them into the per-user ConfigMap store, and records a backend migration marker so the prompt does not repeat across browsers.
- Legacy extension objects are copied but not deleted when Halo reports missing indices for the old extension types. The migration still returns success with `legacyDeleteSkipped` and `deleteWarnings` so the new store is usable without risking data loss.
- Renders Markdown, code blocks, lightweight Mermaid flowcharts, and common LaTeX fragments with local bundled assets. Rendered HTML is sanitized before insertion with bundled official DOMPurify `3.4.12` and falls back to a strict allow-list sanitizer before the script finishes loading; no external MathJax, highlight.js, or DOMPurify CDN is used.
- Backend jobs enforce the plugin global settings for model allow-list, context limits, generated image size, image-cache retention, and combined reasoning/output length. The persistent per-user/per-day ConfigMap is the cross-instance source of truth for concurrent reservations, request timestamps, and daily token usage; the JVM `usageStates` map is only a local cache/fast-path and is never the sole quota authority. Limit violations return `429 Too Many Requests` where applicable.

## Permissions

The plugin installs these role templates:

- `Halo AI`: can open and use the AI chat UI, manage its own sessions/messages/favorites, upload images through the plugin proxy, view its own call logs, and save personal rendering/context settings.
- `View own AI audit logs`: can view the audit-log view for the current user.
- `Manage Halo AI Console`: can read all users' logs, run legacy migration, and access admin-only console endpoints. All-log and migration endpoints also perform backend permission checks in addition to Halo RBAC.
- Global plugin settings are exposed through Halo's plugin settings page via `settingName` / `configMapName` and should be managed by users who already have Halo plugin management permission.
- `GET /me/export` exports the current user's sessions, personal settings, logs, jobs, and attachment references as JSON. The UI states that logs and jobs are each capped at 10,000 records. `DELETE /me/data` returns per-resource `steps`, `failures`, `success`, and `partialFailure` fields so callers can distinguish a complete deletion from a partial deletion; audit-log deletion follows the administrator policy `allowUserAuditLogDeletion`, and Halo attachments are not deleted by the plugin.

## Privacy and Data Processing

- Conversation snapshots, message content, user settings, job records, image cache metadata, usage counters, and audit logs are stored in Halo-managed ConfigMaps.
- Chat, image, and file inputs are forwarded to AI Foundation and then to the model provider configured by the Halo administrator. The plugin itself does not hard-code provider credentials.
- Uploaded images pass through the plugin backend proxy and are then stored by the configured Halo attachment storage.
- Image-cache ConfigMap entries are cleaned using the administrator-configured `imageCacheRetentionDays` value, defaulting to 30 days. This removes plugin cache entries only; it does not delete the corresponding Halo attachments.
- Audit logs may contain user identifiers, model names, token estimates, timestamps, request duration, IP address, browser, operating system, operation status, and error messages.
- The plugin does not include telemetry, analytics SDKs, external CDN scripts, or hard-coded third-party API keys.
- Administrators should review the enabled AI Foundation providers, retention settings, and attachment storage policy before granting the plugin roles to other users.

## APIs Used

- AI Foundation model discovery: `/apis/console.api.aifoundation.halo.run/v1alpha1/model-options?enabled=true`
- Model invocation: AI Foundation Java SDK through `ExtensionGetter`, `AiModelService`, `LanguageModel`, and `ImageGenerationModel`; no Console `test-*` endpoint is used.
- Plugin text-generation bridge for titles and summaries: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/models/{model}/generate-text`
- Plugin attachment upload proxy: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/attachments/upload`
- Plugin global model/settings policy: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/global-settings`
- Bundled DOMPurify asset: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/assets/dompurify.min.js`
- Legacy storage migration: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/migration/legacy/status` and `/migration/legacy`
- Personal data export/deletion: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/me/export` and `/me/data`
- Plugin storage: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/*`

## Notes

- Chat and image requests use backend job endpoints. The backend owns the AI Foundation stream and writes partial/final assistant output back into the session store, so closing the browser does not lose the model response.
- `POST /jobs/{name}/cancel` cancels the running backend subscription when the user clicks stop.
- The upload proxy prevents oversized images on the normal plugin path. Users who independently have direct Halo attachment upload permission may still call Halo's attachment API outside this plugin; enforce a Halo-level upload limit too if that must be impossible.
- Job state and call logs are written to separate ConfigMaps to reduce lost updates between streaming job writes and log writes. Existing per-user store records are still read for backward compatibility.
- Prompt token usage is estimated on the backend from the actual request messages sent to AI Foundation. The frontend estimate is only for display.
- Daily token usage is maintained in per-user/per-day usage records, so quota checks do not depend on the visible call-log page size.
- Per-minute request windows, running-job reservations, and daily token usage are persisted in Halo ConfigMaps and updated with optimistic-lock retries, so limits also apply across multiple Halo instances.
- Each plugin instance writes a heartbeat. On startup, jobs left in `running` or `pending` by an inactive instance are marked as `interrupted` with a retry hint and their persisted reservations are released. Completed, errored, cancelled, and interrupted jobs/logs are periodically cleaned according to the plugin global settings.
- Background jobs resolve AI Foundation's enabled `AiModelService` extension and continue independently of the browser session. User cookies are not retained or replayed for model calls.

## Build Artifact

Current plugin version: `0.3.2`.

Local packaged jar: `dist/halo-ai-console-0.3.2.jar`.

Historical packaged jars are committed under the repository `dist/` directory for quick download and regression comparison.

## 中文说明

Halo AI Console 是社区维护的 Halo Console 插件，不是 Halo 官方插件。它通过 AI Foundation 发现模型并执行聊天、图像生成和多模态任务。用户选择“默认”时，使用的是 AI Foundation 中对应能力的默认模型：普通聊天使用语言模型，带图片输入时使用多模态模型，`/image` 或图像模式使用图像生成模型。

插件本身免费。使用模型可能产生由 AI Foundation 中配置的第三方服务商收取的 API 费用，费用由 Halo 站点管理员承担。停用相关模型、删除 API 配置或禁用插件即可停止新的调用。

聊天历史、后台任务、调用日志、图片缓存和个人设置使用 Halo ConfigMap 存储。设置页提供“导出我的数据”和“删除我的数据”：删除会话、后台任务、图片缓存、用量记录和个人设置；审计日志是否删除由管理员策略决定，Halo 附件仍需在附件管理中单独删除。完整说明见仓库根目录的 [`PRIVACY.md`](../PRIVACY.md)。

### AI Foundation 模型调用

从 `0.3.0` 开始，聊天、多模态、摘要和图像生成均通过 AI Foundation Java SDK 的 `AiModelService` 调用，不再依赖 Console `test-*` 接口，也不再由后台 Job 保存或重放用户 Cookie。模型列表仍通过 AI Foundation 的只读 `model-options` Console API 获取。

### 本地打包资源和许可证

- `assets/dompurify.min.js`：DOMPurify `3.4.12`，上游许可证为 Apache License 2.0 或 Mozilla Public License 2.0，详见 [DOMPurify LICENSE](https://github.com/cure53/DOMPurify/blob/3.4.12/LICENSE)。
- JAR 内同时包含根目录 `LICENSE`、`THIRD-PARTY-NOTICES.md` 和 `licenses/DOMPURIFY-LICENSE.txt`，便于离线查看许可证和归属信息。
- Markdown：优先使用 Halo Console 已提供的 `RichTextEditor.defaultMarkdownParser`；没有该运行时能力时使用插件内置的最小 Markdown 解析器。插件没有打包 `marked` 或 `markdown-it`。
- Mermaid：没有打包 Mermaid 官方库；`main.js` 中的是只支持常见流程图箭头语法的轻量兼容渲染器，属于本项目代码。
- 代码高亮：`assets/highlight-lite.js` 和 `highlight-lite.css` 是本项目的轻量高亮实现，不是 `highlight.js` 的再分发版本，也没有引入 highlight.js 的许可证义务。
- LaTeX：没有打包 MathJax。当前仅使用本项目的有限格式处理逻辑，不能视为完整 TeX/LaTeX 引擎；不保证复杂宏包、环境或公式都能渲染。
- 图标：使用 Halo Console 运行时提供的图标组件；没有额外打包图标库。
- Java 依赖：使用 Halo 和 AI Foundation 运行时提供的 API/依赖，不在插件中重复打包；具体许可证由对应运行时项目负责。
- 本插件自身许可证：GNU Affero General Public License v3.0（`AGPL-3.0-only`），见 [`LICENSE`](../LICENSE)。

## Support

Report issues at [GitHub Issues](https://github.com/joshuaJJM/halo-ai-console/issues).

## Requirements

- Halo `>= 2.25.0`
- AI Foundation installed and enabled
- At least one enabled language / multimodal model for chat
- At least one enabled image generation model for image mode
- Halo attachment storage configured for image upload
