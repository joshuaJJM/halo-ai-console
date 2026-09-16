const test = require("node:test");
const assert = require("node:assert/strict");
const { activeJobs, cancellationDisposition, kind } = require("../src/job-status.cjs");

test("all persisted Job terminal states settle the browser task", () => {
  assert.equal(kind("success"), "success");
  assert.equal(kind("completed"), "success");
  assert.equal(kind("cancelled"), "cancelled");
  assert.equal(kind("interrupted"), "failure");
  assert.equal(kind("error"), "failure");
});

test("only pending and running are active", () => {
  assert.equal(kind("pending"), "active");
  assert.equal(kind("running"), "active");
  assert.equal(kind("unknown-provider-state"), "unknown");
});

test("only the persisted cancelled state permits a local cancellation terminal state", () => {
  assert.equal(cancellationDisposition("cancelled"), "confirmed");
  assert.equal(cancellationDisposition("success"), "terminal");
  assert.equal(cancellationDisposition("error"), "terminal");
  assert.equal(cancellationDisposition("running"), "pending");
  assert.equal(cancellationDisposition("unknown"), "pending");
});

test("recovery keeps every active Job instead of selecting only the first one", () => {
  const jobs = activeJobs([
    { id: "job-a", status: "running" },
    { id: "job-b", status: "pending" },
    { id: "job-c", status: "success" },
  ]);
  assert.deepEqual(jobs.map((job) => job.id), ["job-a", "job-b"]);
});
