(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.HaloAiJobStatus = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const ACTIVE = new Set(["pending", "running"]);
  const SUCCESS = new Set(["success", "completed"]);
  const CANCELLED = new Set(["cancelled", "canceled"]);
  const FAILURE = new Set(["error", "failed", "interrupted"]);

  function normalize(status) {
    return String(status || "").trim().toLowerCase();
  }

  function kind(status) {
    const value = normalize(status);
    if (ACTIVE.has(value)) return "active";
    if (SUCCESS.has(value)) return "success";
    if (CANCELLED.has(value)) return "cancelled";
    if (FAILURE.has(value)) return "failure";
    return "unknown";
  }

  function cancellationDisposition(status) {
    const state = kind(status);
    if (state === "cancelled") return "confirmed";
    if (state === "success" || state === "failure") return "terminal";
    return "pending";
  }

  function activeJobs(jobs) {
    return Array.isArray(jobs) ? jobs.filter((job) => kind(job?.status) === "active") : [];
  }

  return { kind, normalize, cancellationDisposition, activeJobs };
});
