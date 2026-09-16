const test = require("node:test");
const assert = require("node:assert/strict");
const { createSessionTarget, resolveSessionTarget } = require("../src/session-target.cjs");

test("async completion keeps the session captured before the user switches conversations", () => {
  const sessionA = { id: "session-a", messages: [] };
  const sessionB = { id: "session-b", messages: [] };
  const target = createSessionTarget(sessionA, "alice");

  const result = resolveSessionTarget(target, {
    owner: "alice",
    sessions: [sessionA, sessionB],
    selectedSessionId: sessionB.id,
    deletedSessionIds: new Set(),
    blockedSessionIds: new Set(),
  });

  assert.equal(result.session, sessionA);
  assert.equal(result.reason, "");
});

test("an identity change rejects an old asynchronous target", () => {
  const target = createSessionTarget({ id: "session-a" }, "alice");
  const result = resolveSessionTarget(target, {
    owner: "bob",
    sessions: [{ id: "session-a" }],
    deletedSessionIds: new Set(),
    blockedSessionIds: new Set(),
  });

  assert.equal(result.session, null);
  assert.equal(result.reason, "owner-changed");
});

test("deleted and conflicted sessions cannot receive delayed model output", () => {
  const session = { id: "session-a" };
  const target = createSessionTarget(session, "alice");
  const deleted = resolveSessionTarget(target, {
    owner: "alice",
    sessions: [session],
    deletedSessionIds: new Set([session.id]),
    blockedSessionIds: new Set(),
  });
  const conflicted = resolveSessionTarget(target, {
    owner: "alice",
    sessions: [session],
    deletedSessionIds: new Set(),
    blockedSessionIds: new Set([session.id]),
  });

  assert.equal(deleted.reason, "session-deleted");
  assert.equal(conflicted.reason, "session-conflict");
});
