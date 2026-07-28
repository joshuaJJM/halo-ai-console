# Halo AI Console 隐私与数据处理说明

最后更新：2026-07-28

Halo AI Console 是社区维护的 Halo Console 插件，不是 Halo 官方产品。本说明描述插件 `0.2.8` 的实际数据处理方式。Halo 站点管理员负责决定是否启用插件、配置哪些 AI Foundation 模型、授予哪些权限以及设置保存期限。

## 处理哪些数据

插件可能处理以下数据：

- Halo 当前登录用户标识，用于隔离用户的会话、设置、Job、调用记录和图片缓存。
- 用户输入、AI 回复、AI 思考过程、会话标题、摘要、记忆、标签、收藏状态和消息编辑内容。
- 上传图片的文件名、媒体类型、大小、Halo 附件引用；图像生成结果的 URL 或受限制的图片数据。
- AI Foundation 请求所需的模型名、消息和附件引用。
- 调用审计信息，包括用户、模型、输入/输出/总 token 估算、时间、耗时、状态、错误信息、IP、浏览器和操作系统。

插件本身不收集遥测、广告标识或分析数据，也不包含第三方 API 密钥。实际使用的第三方模型服务商取决于 Halo 站点中的 AI Foundation 配置，因此发送给模型提供商的数据处理方式还受对应服务商隐私政策约束。

## 数据保存在哪里

聊天和插件业务数据保存到 Halo 管理的 ConfigMap，主要包括：

- `halo-ai-console-store-*`：用户设置、旧版兼容数据、收藏/图片缓存等用户范围数据。
- `halo-ai-console-session-*`：按会话保存的会话快照和消息。
- `halo-ai-console-job-*`：后台聊天/图像 Job 及流式过程中的部分结果。
- `halo-ai-console-log-*`：调用审计记录。
- `halo-ai-console-usage-*`：每日 token 用量和限流/并发预留信息。
- `halo-ai-console-config`：管理员在插件设置页保存的全局模型、限流、配额和保留策略。

用户上传图片会经插件后端的大小检查，然后交给 Halo 附件 API 和 Halo 配置的附件存储。插件保存的只是附件引用或受限制的缓存数据；会话删除不会自动删除 Halo 附件。

## 默认保存时间

默认值如下，管理员可以在插件设置中调整允许范围内的全局值：

- 已完成、失败、取消或中断的后台 Job：默认 7 天。运行中和等待中的 Job 不会被常规过期清理删除。
- 调用日志：默认 90 天。
- 每个用户最多保留的终止 Job：默认 500 个；超出部分按更新时间删除。
- 每日用量记录：按日志保留期限清理，默认 90 天。
- 图片缓存：默认 30 天，由管理员配置的 `imageCacheRetentionDays` 控制。清理的是插件 ConfigMap 中的缓存元数据和受限制的 data URL，不删除 Halo 附件库中的原始附件。
- 会话、消息、摘要和个人设置：当前没有自动过期策略，会一直保留到用户删除、管理员清理或对应 ConfigMap 被删除。
- Halo 附件：不由插件的 Job/日志清理任务删除，遵循 Halo 附件库自身的保存策略。

清理任务由插件实例定期执行，通常每 6 小时检查一次。Halo 重启后，插件会将检测到的、属于已失联实例的 `running`/`pending` Job 标记为 `interrupted`，而不是继续执行或立即删除。

## 用户如何删除数据

当前版本提供的是会话级删除，不是完整个人数据删除：

- 在聊天侧边栏删除会话，插件会删除该会话的新格式快照，并清理旧格式中的对应会话记录。
- 该操作不会自动删除与会话相关的 Job、调用日志、图片缓存或 Halo 附件；Job 和日志按上述保留策略清理，图片缓存按 `imageCacheRetentionDays` 清理。
- 普通用户不能单独删除审计日志；只有管理员开启 `allowUserAuditLogDeletion` 后，删除个人数据接口才会一并删除自己的日志。
- Halo 附件需要由有权限的用户在 Halo 附件管理中单独删除。

设置页提供“删除我的数据”，对应 `DELETE /me/data`。它会取消当前用户在本实例持有的运行任务，并删除该用户的 `store` 中个人设置、会话/缓存数据、按用户拆分的 `session`、`job` 和 `usage` ConfigMap。审计日志默认保留；只有管理员开启 `allowUserAuditLogDeletion` 后，才会删除该用户的新旧审计日志。Halo 附件不会由该接口删除，必须在 Halo 附件管理中另行删除。接口会返回每类数据的 `steps` 和 `failures`，并通过 `success`/`partialFailure` 标明完整成功或部分失败；部分失败时，已经成功删除的数据不会自动恢复，用户应根据失败明细修复后重试。

若接口执行失败或需要处理历史遗留扩展对象，应联系 Halo 站点管理员，由管理员在确认用户标识后清理该用户的 `store`、`session`、`job`、`log`、`usage` ConfigMap 及历史遗留对象；不要删除全局 `halo-ai-console-config`，否则会影响所有用户的管理员配置。历史迁移遇到旧扩展类型索引缺失时，旧对象可能被保留，管理员还需要按迁移结果中的警告继续处理。

## 用户如何导出数据

设置页提供“导出我的数据”，对应 `GET /me/export`，返回 JSON，包含当前用户的会话、个人设置、调用日志、后台 Job 和会话中的附件引用。设置页会在导出前后提示：出于响应大小保护，日志和 Job 导出各最多 10,000 条；需要超过此数量的完整副本时，应由管理员从 Halo 数据存储和附件管理中按用户范围导出。聊天页单条消息的导出仍然只是 Markdown 文件，不等同于完整个人数据导出。

## 如何停止向第三方模型发送内容

要停止新的模型调用，管理员可以停用相关 AI Foundation 模型、删除对应 API 配置，或禁用 Halo AI Console 插件。插件本身免费，但模型调用可能产生由 AI Foundation 中配置的第三方服务商收取的 API 费用，费用由 Halo 站点管理员承担。

插件没有独立的卸载清理钩子。禁用或卸载后，插件不会主动删除聊天、审计、Job、设置等 ConfigMap，也不会删除 Halo 附件；这用于避免误删数据。若卸载时已有任务正在运行，不能把“卸载”视为可靠的即时取消机制；可在插件仍运行时点击“停止生成”，或等待插件下次启动时将失联任务标记为 `interrupted`。AI Foundation 已经收到的请求是否继续处理，取决于 AI Foundation 和第三方服务商。

## 卸载与数据保留

默认卸载不删除以下数据：

- 聊天会话和消息 ConfigMap；
- 用户设置、图片缓存、Job、调用日志和用量 ConfigMap；
- 迁移前的旧扩展对象（尤其是 Halo 报告旧类型索引缺失时）；
- 上传到 Halo 附件库的图片和文件。

卸载前应由管理员先完成必要的导出或清理。附件必须在 Halo 附件管理中另行删除。重新安装插件后，只要这些 ConfigMap 仍在且名称格式未变化，插件可能继续读取其中的兼容数据；因此不应把卸载当作数据删除操作。

## 审计日志权限

拥有聊天权限的用户可以查看自己的调用记录；拥有单独的审计权限的用户可以打开自己的审计视图；拥有管理员/全部日志权限的用户可以查看全部用户的审计日志。普通用户不能直接删除审计日志。管理员可以打开 `allowUserAuditLogDeletion`，允许用户在执行 `DELETE /me/data` 时删除自己的日志；该策略不会赋予用户删除其他人的日志权限。日志会在默认 90 天或管理员配置的期限后由清理任务删除，但清理任务不保证已经导出的副本或第三方模型服务商处的日志同步删除。

## AI Foundation 接口与测试接口回退

聊天先调用 AI Foundation 的 `/chat/ui-message/stream`，不存在或不支持时才回退到 `/test-chat/ui-message/stream`。当前图像 Job 的实际顺序是先调用非流式 `/test-image-generation`，再在接口不存在或不支持时尝试 `/image-generation`；这是因为本地验证的 AI Foundation `1.0.0-beta.4` 暴露的是前者。`/test-image-generation` 当前不是 Image Streaming 接口。测试路径是 AI Foundation 暴露的 Console API，不是插件自建的 API；它们可能随 AI Foundation 版本变化，生产环境应优先使用提供稳定接口且支持图像流式能力的 AI Foundation 版本。

## 第三方资源和许可证

- **DOMPurify 3.4.12**：打包在 `plugin-halo-ai-console/assets/dompurify.min.js`，上游声明 Apache License 2.0 或 Mozilla Public License 2.0，许可证见 [DOMPurify LICENSE](https://github.com/cure53/DOMPurify/blob/3.4.12/LICENSE)。
- **Markdown**：优先使用 Halo Console 运行时的 `RichTextEditor.defaultMarkdownParser`；没有该能力时使用插件内置的最小解析器。没有打包 `marked` 或 `markdown-it`。
- **Mermaid**：没有打包 Mermaid 官方库；插件只包含一个支持常见 `graph`/`flowchart` 箭头语法的轻量兼容渲染器，代码属于本项目。
- **代码高亮**：`highlight-lite.js`/`highlight-lite.css` 是本项目代码，不是 `highlight.js` 的打包版本。
- **MathJax**：没有打包 MathJax。`mathjax-local.js` 只是兼容占位脚本，当前公式处理是本项目的有限格式逻辑，不是完整 LaTeX 引擎。
- **图标**：使用 Halo Console 运行时的图标组件，没有额外打包图标库。
- **Java 依赖**：插件使用 Halo 和 AI Foundation 提供的运行时 API/依赖，不重复打包这些依赖；它们的许可证由对应项目声明。
- **本插件**：MIT License，见仓库根目录 [`LICENSE`](LICENSE)。

如果发现依赖版本或许可证信息与打包文件不一致，请在 [GitHub Issues](https://github.com/joshuaJJM/halo-ai-console/issues) 提交问题，并附上插件版本和相关文件名。
