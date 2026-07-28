# Halo AI Console

AI-powered chat, image generation, audit, and model management for Halo Console.

Halo AI Console is a community-maintained Halo Console plugin built on top of AI Foundation. It adds a modern AI workspace to the Halo admin console, including streaming chat, image generation, multimodal input, persisted conversation history, context management, model policy, and audit logs.

This project is not an official Halo project and does not use the official Halo logo or branding assets.

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

Current plugin version: `0.2.4`.

## Requirements

- Halo `>= 2.25.0`
- AI Foundation installed and enabled
- At least one enabled language or multimodal model for chat
- At least one enabled image generation model for image mode
- Halo attachment storage configured for image upload

## Packaging

Packaged artifacts are committed under:

```text
dist/halo-ai-console-0.2.4.jar
```

The `dist/` directory keeps historical packaged jars for quick download and regression comparison.

For implementation details, permissions, storage behavior, and API notes, see [`plugin-halo-ai-console/README.md`](plugin-halo-ai-console/README.md).

## Privacy

Halo AI Console stores conversation snapshots, personal settings, job records, image cache metadata, usage counters, and audit logs in Halo-managed ConfigMaps. When a user sends a chat, image, or file request, the relevant message content and attachment references are forwarded to the AI Foundation model selected by the user or administrator. The final third-party model provider depends on the AI Foundation configuration of the Halo installation.

The plugin does not include telemetry, analytics SDKs, external CDN scripts, or hard-coded third-party API keys. Administrators should review their AI Foundation model providers and Halo attachment storage policies before enabling the plugin for other users.

## Support

Report issues at [GitHub Issues](https://github.com/joshuaJJM/halo-ai-console/issues).

## License

MIT
