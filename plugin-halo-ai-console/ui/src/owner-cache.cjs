(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.HaloAiOwnerCache = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function createOwnerCache(storage, prefix = "halo-ai-console") {
    let owner = "";

    function setOwner(nextOwner) {
      const normalized = String(nextOwner || "").trim();
      if (!normalized) throw new Error("缓存 owner 不能为空。");
      const previous = owner;
      owner = normalized;
      return { owner, changed: Boolean(previous && previous !== owner) };
    }

    function key(name) {
      return owner ? `${prefix}:${encodeURIComponent(owner)}:${name}` : "";
    }

    function read(name) {
      const scoped = key(name);
      if (!scoped) return null;
      try {
        return storage.getItem(scoped);
      } catch (_) {
        return null;
      }
    }

    function write(name, value) {
      const scoped = key(name);
      if (!scoped) return false;
      try {
        storage.setItem(scoped, value);
        return true;
      } catch (_) {
        return false;
      }
    }

    function remove(name) {
      const scoped = key(name);
      if (!scoped) return false;
      try {
        storage.removeItem(scoped);
        return true;
      } catch (_) {
        return false;
      }
    }

    return { setOwner, key, read, write, remove, owner: () => owner };
  }

  return { createOwnerCache };
});
