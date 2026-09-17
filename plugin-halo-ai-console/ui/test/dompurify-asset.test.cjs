const test = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");

test("bundled DOMPurify asset matches the locked security release", () => {
  const asset = readFileSync(resolve(__dirname, "../../assets/dompurify.min.js"), "utf8");

  assert.match(asset, /DOMPurify 3\.4\.15/);
  assert.match(asset, /Apache license 2\.0 and Mozilla Public License 2\.0/);
});
