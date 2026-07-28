# Halo AI Console

AI-powered chat, image generation, audit, and model management for Halo Console.

Halo AI Console is a community-maintained Halo Console plugin backed by AI Foundation. It adds a modern AI workspace to the Halo admin console, including streaming chat, image generation, multimodal input, persisted conversation history, context management, model policy, and audit logs.

This project is not an official Halo project and does not use the official Halo logo or branding assets.

## Features

- Adds a `Halo AI` console route and a settings / call-log route.
- Uses AI Foundation console APIs for model options, chat streaming, image generation, and image streaming when the installed AI Foundation version exposes it.
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
- Backend jobs enforce the plugin global settings for model allow-list, atomic concurrent-job reservation, in-memory sliding-window per-minute rate limits, persisted daily token quota, context message count, context character count, image count, session size, generated image size, and combined reasoning/output length. Limit violations return `429 Too Many Requests` where applicable.

## Permissions

The plugin installs these role templates:

- `Halo AI`: can open and use the AI chat UI, manage its own sessions/messages/favorites, upload images through the plugin proxy, view its own call logs, and save personal rendering/context settings.
- `View own AI audit logs`: can view the audit-log view for the current user.
- `Manage Halo AI Console`: can read all users' logs, run legacy migration, and access admin-only console endpoints. All-log and migration endpoints also perform backend permission checks in addition to Halo RBAC.
- Global plugin settings are exposed through Halo's plugin settings page via `settingName` / `configMapName` and should be managed by users who already have Halo plugin management permission.

## Privacy and Data Processing

- Conversation snapshots, message content, user settings, job records, image cache metadata, usage counters, and audit logs are stored in Halo-managed ConfigMaps.
- Chat, image, and file inputs are forwarded to AI Foundation and then to the model provider configured by the Halo administrator. The plugin itself does not hard-code provider credentials.
- Uploaded images pass through the plugin backend proxy and are then stored by the configured Halo attachment storage.
- Audit logs may contain user identifiers, model names, token estimates, timestamps, request duration, IP address, browser, operating system, operation status, and error messages.
- The plugin does not include telemetry, analytics SDKs, external CDN scripts, or hard-coded third-party API keys.
- Administrators should review the enabled AI Foundation providers, retention settings, and attachment storage policy before granting the plugin roles to other users.

## APIs Used

- AI Foundation models: `/apis/console.api.aifoundation.halo.run/v1alpha1/model-options?enabled=true`
- AI Foundation chat stream: tries `/apis/console.api.aifoundation.halo.run/v1alpha1/models/{model}/chat/ui-message/stream`, then falls back to `/test-chat/ui-message/stream` when the installed AI Foundation only exposes console test routes.
- AI Foundation image generation is consumed by a backend job. For the locally verified AI Foundation `1.0.0-beta.4`, the available console endpoint is non-streaming `/test-image-generation`; the plugin uses that first and can fall back to non-streaming `/image-generation` if a later AI Foundation exposes it.
- Plugin attachment upload proxy: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/attachments/upload`
- Plugin global model/settings policy: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/global-settings`
- Bundled DOMPurify asset: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/assets/dompurify.min.js`
- Legacy storage migration: `/apis/console.api.halo-ai-console.halo.run/v1alpha1/migration/legacy/status` and `/migration/legacy`
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
- Background jobs still call AI Foundation through the current user's console credentials because AI Foundation exposes these console endpoints as user-scoped APIs. Long-running service-identity execution would require a supported internal/service API from AI Foundation.

## Build Artifact

Current plugin version: `0.2.4`.

Local packaged jar: `dist/halo-ai-console-0.2.4.jar`.

Historical packaged jars are committed under the repository `dist/` directory for quick download and regression comparison.

## Support

Report issues at [GitHub Issues](https://github.com/joshuaJJM/halo-ai-console/issues).

## Requirements

- Halo `>= 2.25.0`
- AI Foundation installed and enabled
- At least one enabled language / multimodal model for chat
- At least one enabled image generation model for image mode
- Halo attachment storage configured for image upload
