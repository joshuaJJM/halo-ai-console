(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.HaloAiSessionVersion = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function versionOf(value) {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : 0;
  }

  function baseVersion(session, fallback) {
    return Math.max(versionOf(session?._version), versionOf(fallback?._version));
  }

  function applyServerVersion(session, incomingVersion) {
    if (!session) return false;
    const next = versionOf(incomingVersion);
    if (next <= versionOf(session._version)) return false;
    session._version = next;
    return true;
  }

  function comparableMessage(message) {
    const value = message || {};
    return {
      id: String(value.id || ""),
      role: String(value.role || ""),
      content: String(value.content || ""),
      reasoning: String(value.reasoning || ""),
      reasoningOpen: Boolean(value.reasoningOpen),
      images: Array.isArray(value.images) ? value.images : [],
      files: Array.isArray(value.files) ? value.files.map((file) => ({
        name: String(file?.name || ""),
        mediaType: String(file?.mediaType || ""),
        url: String(file?.url || ""),
        attachmentName: String(file?.attachmentName || ""),
      })) : [],
      favorite: Boolean(value.favorite),
      tags: Array.isArray(value.tags) ? value.tags : [],
    };
  }

  function samePersistedContent(left, right) {
    const comparable = (session) => ({
      id: String(session?.id || ""),
      title: String(session?.title || ""),
      memory: String(session?.memory || ""),
      tags: Array.isArray(session?.tags) ? session.tags : [],
      contextClearedAt: versionOf(session?.contextClearedAt),
      createdAt: versionOf(session?.createdAt),
      messages: Array.isArray(session?.messages) ? session.messages.map(comparableMessage) : [],
    });
    return JSON.stringify(comparable(left)) === JSON.stringify(comparable(right));
  }

  return { applyServerVersion, baseVersion, samePersistedContent, versionOf };
});
