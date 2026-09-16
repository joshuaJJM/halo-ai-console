(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.HaloAiSessionTarget = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function createSessionTarget(session, owner) {
    const sessionId = String(session?.id || "").trim();
    const normalizedOwner = String(owner || "").trim();
    return sessionId && normalizedOwner ? Object.freeze({ owner: normalizedOwner, sessionId }) : null;
  }

  function resolveSessionTarget(target, context) {
    const owner = String(context?.owner || "").trim();
    if (!target || target.owner !== owner) {
      return { session: null, reason: "owner-changed" };
    }
    if (context?.deletedSessionIds?.has(target.sessionId)) {
      return { session: null, reason: "session-deleted" };
    }
    const session = (context?.sessions || []).find((item) => item?.id === target.sessionId) || null;
    if (!session) {
      return { session: null, reason: "session-missing" };
    }
    if (context?.blockedSessionIds?.has(target.sessionId)) {
      return { session: null, reason: "session-conflict" };
    }
    return { session, reason: "" };
  }

  return { createSessionTarget, resolveSessionTarget };
});
