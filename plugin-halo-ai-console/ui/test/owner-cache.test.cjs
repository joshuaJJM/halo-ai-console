const test = require("node:test");
const assert = require("node:assert/strict");
const { createOwnerCache } = require("../src/owner-cache.cjs");

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key),
  };
}

test("the same browser cannot read another login owner's cached conversations", () => {
  const cache = createOwnerCache(memoryStorage());
  cache.setOwner("alice");
  cache.write("sessions", "alice-private-session");

  const switched = cache.setOwner("bob");

  assert.equal(switched.changed, true);
  assert.equal(cache.read("sessions"), null);
  cache.write("sessions", "bob-private-session");
  cache.setOwner("alice");
  assert.equal(cache.read("sessions"), "alice-private-session");
});

test("cache access is disabled until the server confirms an authenticated owner", () => {
  const cache = createOwnerCache(memoryStorage());
  assert.equal(cache.key("sessions"), "");
  assert.equal(cache.read("sessions"), null);
  cache.write("sessions", "must-not-be-written");
  cache.setOwner("alice");
  assert.equal(cache.read("sessions"), null);
});

test("storage failures degrade to an uncached view", () => {
  const unavailableStorage = {
    getItem: () => { throw new Error("SecurityError"); },
    setItem: () => { throw new Error("QuotaExceededError"); },
    removeItem: () => { throw new Error("SecurityError"); },
  };
  const cache = createOwnerCache(unavailableStorage);
  cache.setOwner("alice");
  assert.equal(cache.read("sessions"), null);
  assert.equal(cache.write("sessions", "draft"), false);
  assert.equal(cache.remove("sessions"), false);
});
