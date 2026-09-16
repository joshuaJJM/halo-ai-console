const test = require("node:test");
const assert = require("node:assert/strict");
const { applyServerVersion, baseVersion, samePersistedContent } = require("../src/session-version.cjs");

test("a newly created session starts its first compare-and-swap write at version zero", () => {
  assert.equal(baseVersion({ id: "new-chat" }, null), 0);
  assert.equal(baseVersion({ _version: 0 }, { _version: 4 }), 4);
});

test("a Job event can advance the local snapshot version but never roll it back", () => {
  const session = { _version: 1 };
  assert.equal(applyServerVersion(session, 3), true);
  assert.equal(session._version, 3);
  assert.equal(applyServerVersion(session, 2), false);
  assert.equal(session._version, 3);
});

test("a completed Job does not turn an equivalent pending snapshot into a refresh conflict", () => {
  const local = {
    id: "session-a", _version: 4, title: "测试", createdAt: 100, updatedAt: 200,
    messages: [{ id: "assistant-a", role: "assistant", content: "ALPHA-OK", streaming: false, updatedAt: 200 }],
  };
  const server = {
    ...local, _version: 5, updatedAt: 300,
    messages: [{ ...local.messages[0], promptTokens: 3, completionTokens: 2, totalTokens: 5, updatedAt: 300 }],
  };
  assert.equal(samePersistedContent(local, server), true);
});
