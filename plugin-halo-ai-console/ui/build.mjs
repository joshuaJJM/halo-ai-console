import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const uiDir = dirname(fileURLToPath(import.meta.url));
const output = resolve(process.argv[2] || resolve(uiDir, "dist/main.js"));
const parts = await Promise.all([
  readFile(resolve(uiDir, "src/owner-cache.cjs"), "utf8"),
  readFile(resolve(uiDir, "src/session-target.cjs"), "utf8"),
  readFile(resolve(uiDir, "src/session-version.cjs"), "utf8"),
  readFile(resolve(uiDir, "src/job-status.cjs"), "utf8"),
  readFile(resolve(uiDir, "main.js"), "utf8"),
]);

await mkdir(dirname(output), { recursive: true });
await writeFile(output, `${parts.join("\n\n")}\n`, "utf8");
