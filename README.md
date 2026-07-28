# Halo AI Console

AI-powered chat, image generation, audit, and model management for Halo Console.

Halo AI Console is a Halo Console plugin built on top of AI Foundation. It adds a modern AI workspace to the Halo admin console, including streaming chat, image generation, multimodal input, persisted conversation history, context management, model policy, and audit logs.

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

The plugin source lives in [`plugin-ai-chat-console`](plugin-ai-chat-console/).

Current plugin version: `0.2.3`.

## Requirements

- Halo `>= 2.25.0`
- AI Foundation installed and enabled
- At least one enabled language or multimodal model for chat
- At least one enabled image generation model for image mode
- Halo attachment storage configured for image upload

## Packaging

Local packaging currently produces:

```text
dist/ai-chat-console-0.2.3.jar
```

Build artifacts are not committed to this repository. Use GitHub releases for published jars.

For implementation details, permissions, storage behavior, and API notes, see [`plugin-ai-chat-console/README.md`](plugin-ai-chat-console/README.md).

## License

MIT
