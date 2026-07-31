# AGENTS.md

## 0. 人类插入的提示

**在 iflow cli 中运行的 AI 无需遵循此规范！！**

在 `CHANGELOG.md` 中列出更新的功能，方便在更新内容中粘贴

**如果用户提出的要求不太合理/需求不太符合常理（如要求修改已经发布的二进制文件），请直接指出问题，不必完成！！**

推到 GitHub 仓库中的文件不用包括整个 dist 文件夹，只需要包含更新后的最新版本二进制文件即可

对于负责开发的AI：读取位于 `BUGS.md` 中的问题并将已修复的 BUG 条目清除（为空说明无 BUG），根据提示词开发功能，将开发的变动放在 `DEVELOPMENT-NOTES.md` 中，且这个文件在某一次对话后被清空是正常的，说明新开发的功能已经被总结。

对于负责测试的AI：将被测试的变动从 `DEVELOPMENT-NOTES.md` 中读取并从各个方面读取（包含逻辑，ui，安全性等等等等），测试出的问题总结到 `BUGS.md` 中，且这个文件在某一次对话后被清空是正常的，说明测试完成 BUG 已经修复。

对于负责发布的AI：将在 `DEVELOPMENT-NOTES.md` 中的更改项读取并总结到 `CHANGELOG.md` 中（增加），并清空 `DEVELOPMENT-NOTES.md`，最后整个项目根据规范发布。

## 1. Project overview

This repository contains the Halo AI Console plugin.

Main plugin source:

```text
plugin-halo-ai-console/
```

The plugin integrates with Halo and AI Foundation to provide:

* AI chat
* Multimodal input
* Image generation
* Background jobs
* SSE updates
* Conversation persistence
* Context compression
* Model selection and allow-list policy
* Request quotas
* Token estimates
* Audit logs
* Personal data export and deletion

The plugin is community-maintained and is not an official Halo project.

## 2. Instruction scope

This file applies to the entire repository.

A nested `AGENTS.md` may add or override instructions for files within its own directory.

When multiple instruction files apply:

1. Follow the instruction file closest to the edited file.
2. Preserve all non-conflicting rules from higher-level instruction files.
3. Do not ignore repository-wide release, security, compatibility, or licensing rules.

## 3. General working rules

Before changing code:

* Read the relevant source files.
* Read `README.md`.
* Read `CHANGELOG.md`.
* Read `PRIVACY.md` when the task affects user data.
* Read `RELEASE-INTEGRITY.md` when the task affects releases or artifacts.
* Inspect the current plugin version.
* Inspect the current Halo and AI Foundation compatibility ranges.

When implementing changes:

* Keep the change focused on the requested task.
* Do not rewrite unrelated code.
* Do not rename public APIs without a clear reason.
* Preserve backward compatibility unless the task explicitly requires a breaking change.
* Do not silently remove migration code.
* Do not silently change storage formats.
* Do not delete historical release artifacts.
* Do not modify previously published artifacts.
* Do not add telemetry, analytics, tracking SDKs, or external CDN dependencies.
* Do not add hard-coded credentials, API keys, cookies, tokens, secrets, or provider endpoints.
* Do not store or replay user browser cookies for background model calls.
* Use the AI Foundation Java SDK for model execution.
* Use Console APIs only for capabilities not available through the Java SDK, such as read-only model discovery where necessary.
* Prefer clear error handling over silent fallback.
* Keep all user-visible errors understandable and actionable.
* Avoid catching exceptions without logging or returning a meaningful failure state.

## 4. Semantic Versioning

All plugin versions must follow Semantic Versioning.

Required format:

```text
MAJOR.MINOR.PATCH
```

Examples:

```text
0.3.1
1.0.0
1.2.4
```

Pre-release versions may use:

```text
0.4.0-alpha.1
0.4.0-beta.1
0.4.0-rc.1
```

Do not use invalid version forms such as:

```text
0.3
v0.3.1
0.3.1 final
0.3.1-final
0.3.1(2)
0.3.01
latest
new
fixed
```

The `v` prefix may be used for Git tags:

```text
v0.3.1
```

The `v` prefix must not appear in `plugin.yaml`.

Correct:

```yaml
version: "0.3.1"
```

Incorrect:

```yaml
version: "v0.3.1"
```

## 5. Version increment rules

Use the following rules when choosing the next version.

### 5.1 PATCH

Increase `PATCH` for backward-compatible fixes.

Examples:

* Bug fixes
* Security fixes that preserve the public contract
* Image-generation timeout fixes
* Error-message improvements
* Documentation corrections
* Metadata corrections
* Dependency-range corrections
* Logging fixes
* Performance fixes that do not change public behavior
* Release process improvements
* Compatibility checks that do not remove supported environments

Example:

```text
0.3.1 → 0.3.2
```

### 5.2 MINOR

Increase `MINOR` for backward-compatible new functionality.

Examples:

* Add PDF parsing
* Add tool calling
* Add image editing
* Add knowledge-base support
* Add new administrator settings
* Add new public API endpoints
* Add new storage capabilities
* Add a new user-facing feature
* Add a new export format
* Add new model capability support

Example:

```text
0.3.2 → 0.4.0
```

### 5.3 MAJOR

Increase `MAJOR` for incompatible changes.

Examples:

* Remove a public API
* Make old configuration invalid
* Make old sessions unreadable
* Replace the storage format without automatic migration
* Remove support for previously supported Halo versions
* Remove support for previously supported AI Foundation versions
* Change permission semantics incompatibly
* Require manual user migration before the plugin can start
* Change exported data format incompatibly
* Change plugin identity or resource names in a way that breaks upgrades

Example:

```text
1.4.2 → 2.0.0
```

While the project remains below `1.0.0`, major architectural changes may use a new minor version, but all breaking behavior must be explicitly documented under:

```text
Breaking Changes
```

Do not hide breaking changes inside a patch release.

## 6. Pre-release rules

Use pre-release versions for major or risky changes before publishing a stable release.

Recommended sequence:

```text
0.4.0-alpha.1
0.4.0-alpha.2
0.4.0-beta.1
0.4.0-beta.2
0.4.0-rc.1
0.4.0
```

Definitions:

* `alpha`: incomplete or experimental
* `beta`: feature-complete but still under testing
* `rc`: release candidate
* no suffix: stable release

Published pre-release artifacts are immutable.

Do not replace:

```text
0.4.0-beta.1
```

with different content.

Release:

```text
0.4.0-beta.2
```

instead.

## 7. Published artifact immutability

A published artifact must never be changed.

Once any JAR has been:

* committed to the release directory;
* uploaded to GitHub Releases;
* submitted to the Halo App Store;
* sent to users;
* distributed publicly;
* referenced by a release tag;

it must be treated as immutable.

Never:

* rebuild a JAR using the same version;
* overwrite an existing JAR;
* replace a GitHub Release asset with different content;
* move an existing release tag;
* delete and recreate an existing release tag;
* modify historical JAR metadata;
* repackage historical JAR files to change author, license, README, or icon;
* use operating-system duplicate suffixes such as `(1)` or `(2)`.

If a released artifact has a problem, publish a new version.

Example:

```text
halo-ai-console-0.3.1.jar
```

contains a bug.

Correct action:

```text
halo-ai-console-0.3.2.jar
```

Incorrect action:

```text
halo-ai-console-0.3.1(2).jar
halo-ai-console-0.3.1-final.jar
halo-ai-console-0.3.1-fixed.jar
```

## 8. Historical artifacts

Do not alter historical release files, even when they contain:

* outdated author metadata;
* outdated documentation;
* old license text;
* old dependency declarations;
* incomplete notices;
* known bugs.

Historical issues must be explained in documentation, not repaired by rewriting old artifacts.

Use:

```text
RELEASE-INTEGRITY.md
```

to document historical artifact issues.

Starting from the release-integrity baseline version, all future artifacts must remain immutable.

## 9. Version synchronization

When changing the plugin version, update every current-version reference in the same change.

Check at least:

```text
plugin-halo-ai-console/plugin.yaml
plugin-halo-ai-console/build.gradle
plugin-halo-ai-console/README.md
plugin-halo-ai-console/JAR-README.md
README.md
PRIVACY.md
CHANGELOG.md
RELEASE-INTEGRITY.md
```

Also verify:

* generated JAR filename;
* embedded `plugin.yaml` inside the JAR;
* documentation installation examples;
* compatibility tables;
* release notes;
* SHA-256 checksum filename;
* Git tag name;
* GitHub Release title.

All current-version references must match.

Historical version references in the changelog or release notes must not be replaced.

## 10. Halo compatibility rules

Halo compatibility must use a legal Semantic Versioning range.

Example:

```yaml
requires: ">=2.25.0"
```

Rules:

* Do not add whitespace before or after the range.
* Use a complete three-part version.
* Do not use vague text such as `latest`.
* Do not lower the minimum version without compatibility testing.
* Do not raise the minimum version in a patch release unless the previous declared compatibility was incorrect and the correction is documented.
* If compatibility is intentionally broken, document it clearly.

Correct:

```yaml
requires: ">=2.25.0"
```

Incorrect:

```yaml
requires: " >=2.25.0 "
requires: "2.25"
requires: "latest"
requires: "*"
```

## 11. AI Foundation compatibility rules

The plugin must declare the minimum tested AI Foundation version.

Do not use the following as a general compatibility policy:

```yaml
ai-foundation: "*"
```

when the plugin depends on specific SDK APIs.

Known Halo 2.25.x exception: its bundled java-semver parser cannot parse pre-release
minimum ranges such as `>=1.0.0-beta.5` in plugin dependency metadata. While that
platform defect applies, the descriptor may use the required dependency
`ai-foundation: "*"` only when the plugin also fails closed during startup by reading
the installed AI Foundation version and directly enforcing the documented minimum.
The README and changelog must explain this two-layer compatibility check. Do not
replace the current workaround with the unparseable pre-release range.

Use a tested minimum version, for example:

```yaml
pluginDependencies:
  ai-foundation: ">=1.0.0-beta.5"
```

Rules:

* The range must match the SDK used at compile time.
* The minimum version must be verified in a real Halo environment.
* The minimum version must support every API used by the plugin.
* The README must list the same minimum version.
* The changelog must mention compatibility-range changes.
* A dependency-range correction without functional changes is normally a patch release.
* Requiring a newer dependency because of a new feature is normally a minor release.
* Removing support for previously supported dependency versions may be breaking.

Before release, verify:

* text generation;
* streaming chat;
* multimodal input;
* image generation;
* cancellation;
* model selection;
* default model resolution;
* model allow-list enforcement.

## 12. AI Foundation SDK rules

Use the AI Foundation Java SDK for model execution.

Preferred backend entry point:

```java
AiModelService
```

Acquire the service through Halo extension APIs, such as:

```java
ExtensionGetter
```

Use appropriate capabilities:

```java
languageModel()
languageModel(modelName)
imageGenerationModel()
imageGenerationModel(modelName)
embeddingModel()
rerankingModel()
```

Do not:

* call Console `test-*` endpoints for production model execution;
* save user cookies for background jobs;
* replay browser credentials;
* hard-code provider-specific endpoints;
* depend on provider-specific model IDs when AI Foundation metadata names are required;
* package the AI Foundation API dependency using `implementation`.

Use:

```groovy
compileOnly
```

for the AI Foundation API.

Do not use:

```groovy
implementation
```

for runtime-provided API classes, because this may create duplicate types across plugin classloaders.

## 13. Background job rules

Chat and image generation may run as background jobs.

All jobs must have explicit terminal states.

Expected states include:

```text
pending
running
completed
error
cancelled
interrupted
```

A job must never remain permanently in:

```text
pending
running
```

without timeout, heartbeat, recovery, or failure handling.

Every background job must:

* record an owner;
* record creation time;
* record update time;
* record the selected model;
* record operation type;
* transition to a terminal state;
* release concurrency reservations;
* write an audit record;
* notify the browser through SSE where applicable;
* handle cancellation;
* handle timeout;
* handle plugin shutdown;
* handle Halo restart;
* handle inactive instance recovery.

Cleanup logic must not delete currently active jobs.

## 14. Image-generation rules

Image generation must use the AI Foundation image-generation SDK.

The image-generation flow must handle:

* default image model;
* explicitly selected image model;
* text-to-image;
* image-to-image where supported;
* response URL;
* response Base64;
* empty image result;
* invalid media type;
* attachment-save failure;
* provider timeout;
* provider authentication failure;
* unsupported model capability;
* cancellation;
* image-size limits;
* image-count limits.

Image jobs must have a maximum execution time.

A stalled provider call must transition the job to:

```text
error
```

The error path must:

* save the final Job state;
* release concurrency reservations;
* write a call log;
* expose a clear user-facing error;
* notify connected SSE clients.

Do not show a job as indefinitely generating when the backend has failed.

## 15. Streaming and SSE rules

Streaming output must remain functional through the plugin Job SSE endpoint.

SSE behavior must account for:

* browser reconnect;
* network interruption;
* reverse-proxy buffering;
* reverse-proxy timeout;
* duplicate events;
* missed events;
* multiple browser tabs;
* browser sleep and wake;
* completed jobs opened after completion.

The stored Job state is the source of truth.

SSE is a delivery mechanism, not the only copy of the result.

Do not rely exclusively on an in-memory stream for final output.

## 16. Storage rules

Current plugin data may be stored in Halo-managed ConfigMaps.

Storage changes require special care.

Do not:

* silently rename ConfigMaps;
* silently change key formats;
* remove legacy readers without migration;
* delete legacy data before confirming successful migration;
* mix global settings and user data in unsafe ways;
* allow one user to read another user's data;
* store secrets in user-visible ConfigMaps.

When changing storage:

* document the old format;
* document the new format;
* provide an automatic migration where possible;
* preserve rollback safety;
* report partial migration failures;
* avoid data loss;
* write compatibility tests.

A storage-format change requiring manual migration is potentially breaking.

## 17. Personal data rules

The plugin must maintain personal-data controls.

The user-data export endpoint must clearly state:

* what is included;
* what is excluded;
* record limits;
* whether attachments are embedded or referenced;
* whether logs are truncated;
* whether Jobs are truncated.

The personal-data deletion endpoint must clearly state:

* which resources were deleted;
* which resources failed;
* whether deletion was partial;
* whether audit logs were retained;
* whether Halo attachments were retained.

Do not claim that Halo attachments were deleted when the plugin only deleted attachment references.

Do not silently delete audit logs when administrator policy requires retention.

Any change affecting data collection, storage, export, deletion, retention, or third-party transfer must update:

```text
PRIVACY.md
```

## 18. Privacy rules

The plugin must clearly disclose that:

* messages may be sent to AI Foundation;
* AI Foundation may forward content to a configured model provider;
* files and images may be sent to third-party providers;
* uploaded images may be stored in Halo attachment storage;
* audit logs may contain identifiers, model names, timestamps, errors, IP information, browser information, or usage estimates;
* model-provider charges are the responsibility of the Halo administrator.

Do not add undisclosed:

* telemetry;
* analytics;
* third-party scripts;
* tracking pixels;
* external CDN resources;
* hidden network requests.

Privacy documentation must match actual behavior.

## 19. Security rules

Never commit:

```text
API keys
access tokens
refresh tokens
cookies
session IDs
private keys
passwords
provider secrets
authorization headers
personal test credentials
```

Use placeholders in documentation:

```text
YOUR_API_KEY
example-token
https://example.com
```

Validate all user-controlled input.

Apply limits to:

* uploaded file size;
* image size;
* image count;
* prompt size;
* context size;
* output size;
* reasoning size;
* request rate;
* concurrent jobs;
* daily usage.

Rendered HTML must be sanitized before insertion.

Do not bypass DOMPurify or the strict fallback sanitizer.

Do not introduce unsafe HTML rendering to support Markdown features.

Do not trust model-generated HTML.

Do not expose administrator-only APIs through user roles.

Always verify authorization in the backend, even when the frontend hides an action.

## 20. Permission rules

Permissions must follow least privilege.

User roles may access only their own:

* sessions;
* jobs;
* logs;
* image caches;
* usage records;
* personal settings;
* exports;
* deletion endpoints.

Administrator permissions must be required for:

* viewing all users' logs;
* running global migration;
* accessing administrator-only endpoints;
* changing global settings;
* managing global model policy.

Do not rely only on frontend route visibility.

Every sensitive backend endpoint must perform authorization checks.

## 21. Audit-log rules

Audit logs must be written for significant model operations.

Audit records may include:

* owner;
* operation;
* model;
* status;
* start time;
* finish time;
* duration;
* estimated token usage;
* error type;
* limited error message;
* request metadata;
* client metadata where appropriate.

Do not store full secrets in audit logs.

Do not store authorization headers or cookies.

Limit error-message size before persistence.

Audit-log deletion must respect:

```text
allowUserAuditLogDeletion
```

## 22. Token and quota rules

Frontend token counts are display estimates only.

Quota enforcement must occur on the backend.

Persisted per-user and per-day usage records must remain the cross-instance source of truth.

Do not rely solely on in-memory maps for:

* request windows;
* concurrent reservations;
* daily usage;
* job ownership;
* completed usage.

All reservation paths must release reservations on:

* success;
* error;
* timeout;
* cancellation;
* interruption;
* startup recovery.

Token estimates must not be described as exact billing usage unless actual provider usage is available.

## 23. Dependency rules

Before adding a dependency:

* confirm it is necessary;
* confirm its license;
* confirm whether it is bundled or runtime-provided;
* update third-party notices where required;
* avoid duplicating Halo runtime dependencies;
* avoid duplicating AI Foundation runtime dependencies;
* avoid external CDN loading;
* prefer small, auditable dependencies.

For bundled third-party code, update:

```text
THIRD-PARTY-NOTICES.md
licenses/
```

Do not remove required upstream license files.

## 24. License rules

The project license must be consistent across:

```text
LICENSE
plugin.yaml
README.md
JAR-README.md
repository metadata
release notes
source headers, if used
```

Current project license:

```text
GNU AGPL v3
```

Do not leave stale claims that the project itself is MIT-licensed.

Third-party dependencies retain their own licenses.

For example, DOMPurify licensing must remain documented separately.

Changing the project license requires:

* explicit user approval;
* review of prior contributor rights;
* updating all license references;
* updating packaged license files;
* updating release notes;
* publishing a new version;
* never rewriting historical artifacts.

## 25. Documentation rules

Documentation must remain accurate.

Update the README when changing:

* requirements;
* installation;
* model invocation;
* storage behavior;
* permissions;
* APIs;
* privacy behavior;
* export behavior;
* deletion behavior;
* release artifact name;
* minimum Halo version;
* minimum AI Foundation version;
* license.

Avoid unsupported claims such as:

* “fully secure”;
* “never loses data”;
* “exact token billing”;
* “all files are deleted”;
* “all models are supported”;
* “official Halo plugin”.

Use precise wording and document limitations.

## 26. Changelog rules

Maintain:

```text
CHANGELOG.md
```

Use sections such as:

```text
Added
Changed
Deprecated
Removed
Fixed
Security
Compatibility
Breaking Changes
```

Every release must have:

* version;
* date;
* user-relevant changes;
* compatibility requirements;
* migration notes where applicable.

Example:

```markdown
## [0.3.2] - 2026-08-01

### Fixed

- Prevented image-generation jobs from remaining indefinitely in the running state.
- Improved image-provider timeout reporting.

### Compatibility

- Requires Halo >= 2.25.0.
- Requires AI Foundation >= 1.0.0-beta.5.
```

Do not describe a major new feature only under `Fixed`.

Do not omit breaking changes.

## 27. Release-integrity rules

Maintain:

```text
RELEASE-INTEGRITY.md
```

The document must explain:

* historical artifact issues;
* the immutable-release baseline;
* tag policy;
* checksum policy;
* artifact naming policy;
* whether historical JARs are retained.

Do not repeatedly rewrite historical artifacts to improve metadata.

Document historical limitations instead.

## 28. Artifact naming rules

Release JAR filename format:

```text
halo-ai-console-MAJOR.MINOR.PATCH.jar
```

Examples:

```text
halo-ai-console-0.3.1.jar
halo-ai-console-0.4.0-beta.1.jar
halo-ai-console-1.0.0.jar
```

Checksum filename format:

```text
halo-ai-console-0.3.1.jar.sha256
```

Do not use:

```text
halo-ai-console-0.3.1(2).jar
halo-ai-console-final.jar
halo-ai-console-latest.jar
halo-ai-console-fixed.jar
halo-ai-console-new.jar
```

## 29. Build rules

Build from a clean working tree.

Preferred release build:

```bash
./gradlew clean build
```

Before release:

* ensure tests pass;
* ensure the working tree is clean;
* ensure the version is correct;
* ensure the JAR embeds the correct `plugin.yaml`;
* ensure no secrets are included;
* ensure license files are packaged;
* ensure third-party notices are packaged;
* ensure documentation inside the JAR is current.

Verify the embedded plugin metadata:

```bash
unzip -p build/libs/halo-ai-console-0.3.1.jar plugin.yaml
```

The embedded version must match the filename and source `plugin.yaml`.

## 30. Test rules

Run the applicable test suite before committing.

At minimum, release testing should cover:

### Installation

* Fresh install
* Upgrade from the previous stable version
* Plugin enable
* Plugin disable
* Plugin restart
* Halo restart

### Compatibility

* Minimum supported Halo version
* A newer supported Halo version
* Minimum supported AI Foundation version
* A newer supported AI Foundation version

### Chat

* Default language model
* Explicit language model
* Streaming output
* Stop generation
* Retry
* Edit and regenerate
* Context compression
* Summary generation
* Browser close and reopen

### Multimodal

* Image input
* Unsupported media type
* Oversized image
* Missing attachment
* Provider without multimodal capability

### Image generation

* Default image model
* Explicit image model
* Successful URL result
* Successful Base64 result
* Provider error
* Invalid API key
* Unsupported image model
* Empty result
* Timeout
* Cancellation
* Attachment-save failure

### Jobs

* Pending to running
* Running to completed
* Running to error
* Running to cancelled
* Running to interrupted
* Reservation release
* Restart recovery
* Stale Job cleanup

### Permissions

* Normal user
* Audit-log viewer
* Administrator
* Cross-user access denial
* Unauthorized global-settings access
* Unauthorized migration access

### Personal data

* Export
* Export record limits
* Complete deletion
* Partial deletion
* Audit-log retention policy
* Attachment retention notice

## 31. Release checklist

Before creating a release, complete all items.

### Version

* [ ] Version follows Semantic Versioning.
* [ ] Version increment matches the change type.
* [ ] Version has not been used before.
* [ ] All current-version references match.
* [ ] The JAR filename matches the version.
* [ ] The embedded `plugin.yaml` matches the version.

### Compatibility

* [ ] Halo range is valid.
* [ ] AI Foundation range is valid.
* [ ] No leading or trailing spaces exist.
* [ ] Minimum supported versions were tested.
* [ ] Compatibility table was updated.

### Code and tests

* [ ] Clean build succeeded.
* [ ] Automated tests passed.
* [ ] Manual smoke tests passed.
* [ ] Upgrade test passed.
* [ ] Image generation passed.
* [ ] Background Job terminal states were verified.
* [ ] Reservation release was verified.
* [ ] Permission boundaries were verified.

### Privacy and security

* [ ] No secrets are committed.
* [ ] Privacy documentation matches behavior.
* [ ] Personal-data controls still work.
* [ ] Audit logging still works.
* [ ] Rendered HTML remains sanitized.
* [ ] Upload limits remain enforced.

### Licensing

* [ ] Project license references are consistent.
* [ ] Third-party notices are current.
* [ ] Required license files are included in the JAR.
* [ ] No stale MIT project-license references remain if the project is AGPL-3.0.

### Release integrity

* [ ] Historical artifacts were not modified.
* [ ] New artifact has a unique filename.
* [ ] SHA-256 checksum was generated.
* [ ] Git tag is new and permanent.
* [ ] GitHub Release was created.
* [ ] Release notes match `CHANGELOG.md`.

## 32. Git tag rules

Stable release tag format:

```text
v0.3.1
```

Pre-release tag format:

```text
v0.4.0-beta.1
```

Tags must be annotated where possible.

Example:

```bash
git tag -a v0.3.1 -m "Release 0.3.1"
git push origin v0.3.1
```

Never:

* move a release tag;
* force-update a release tag;
* delete and recreate a public release tag;
* attach different artifacts to the same version.

## 33. Checksum rules

Generate SHA-256 for each published JAR.

Linux or macOS:

```bash
sha256sum halo-ai-console-0.3.1.jar
```

Windows PowerShell:

```powershell
Get-FileHash .\halo-ai-console-0.3.1.jar -Algorithm SHA256
```

Publish the checksum beside the JAR.

Do not modify the JAR after generating the checksum.

## 34. GitHub Release rules

Each release should include:

```text
halo-ai-console-0.3.1.jar
halo-ai-console-0.3.1.jar.sha256
```

Release title:

```text
Halo AI Console 0.3.1
```

Release tag:

```text
v0.3.1
```

Release notes should include:

* summary;
* changes;
* fixes;
* compatibility;
* migration notes;
* known limitations;
* checksum information.

Do not replace assets after release.

Publish a new patch version instead.

## 35. Commit rules

Commit messages should describe the real change.

Good examples:

```text
Fix image job timeout handling
Add document parsing support
Require AI Foundation beta.5
Release 0.3.2
```

Avoid vague messages:

```text
update
fix
changes
new
final
```

Do not claim a release was created unless:

* version files were updated;
* artifact was built;
* artifact was verified;
* changelog was updated;
* tag and release steps are ready or completed.

## 36. Pull request rules

A pull request should include:

* problem statement;
* implementation summary;
* compatibility impact;
* privacy impact;
* security impact;
* storage impact;
* tests performed;
* versioning recommendation.

For release-related pull requests, state explicitly:

```text
Recommended version: PATCH / MINOR / MAJOR
```

Do not mix unrelated refactors into a release-fix pull request.

## 37. Refactoring rules

Refactoring without behavior change should not silently alter:

* API paths;
* storage keys;
* role names;
* ConfigMap prefixes;
* plugin metadata;
* release artifact contents;
* model-selection semantics;
* default settings.

Large refactors should be separated from urgent bug fixes.

Add regression tests before changing critical behavior.

## 38. Migration rules

Migrations must be:

* repeatable;
* owner-scoped where applicable;
* safe to retry;
* explicit about partial failure;
* non-destructive until new data is verified;
* logged without exposing secrets.

Do not delete old data immediately after copying unless the new data has been verified.

If deletion is skipped, return warnings.

If a migration changes user-visible behavior, document it in the changelog.

## 39. Error-handling rules

Errors must transition operations into a clear failure state.

Do not:

* leave Jobs running forever;
* swallow errors;
* expose raw stack traces to normal users;
* expose secrets in error messages;
* return a success response for failed operations without a warning field.

Backend logs may include technical details.

User-facing errors should include:

* what failed;
* likely cause;
* next action;
* safe reference information.

Limit persisted error strings to a reasonable length.

## 40. User-interface rules

The UI must clearly communicate:

* selected model;
* generation state;
* cancellation state;
* error state;
* export limits;
* attachment-retention behavior;
* audit-log retention behavior;
* compatibility requirements where relevant.

Do not label an operation as completed until the backend Job is completed.

Do not hide partial deletion failure.

Do not imply that attachments are deleted when only references are deleted.

## 41. Backward-compatibility rules

Before removing or changing behavior, search for:

* existing stored data;
* frontend callers;
* API consumers;
* role templates;
* migration code;
* historical settings;
* README promises.

Preserve compatibility readers for at least one documented migration period unless explicitly approved otherwise.

A compatibility shim may be removed only when:

* migration has been available;
* removal is documented;
* version increment is appropriate;
* upgrade behavior has been tested.

## 42. Performance rules

Avoid excessive ConfigMap writes during streaming.

Use throttled persistence for partial output.

Do not persist every token separately.

Avoid loading all users' data for a single-user operation.

Apply pagination or limits to large collections.

Do not increase export limits without considering memory use and response size.

Performance optimizations must preserve correctness and user ownership boundaries.

## 43. Observability rules

Important background operations should produce sufficient logs for diagnosis.

Log:

* Job ID;
* owner-safe identifier where appropriate;
* model name;
* operation type;
* transition;
* duration;
* terminal status;
* error class.

Do not log:

* full prompts by default;
* cookies;
* authorization headers;
* API keys;
* full Base64 images;
* private file contents.

## 44. Prohibited actions

Codex must not perform the following unless explicitly requested and justified:

* overwrite published JARs;
* rewrite historical artifacts;
* reuse an existing version;
* move release tags;
* remove migration logic;
* delete user data;
* weaken backend authorization;
* disable HTML sanitization;
* add hard-coded provider credentials;
* add external tracking;
* add external CDN scripts;
* change the project license;
* change plugin identity;
* force-push release branches;
* silently raise compatibility requirements;
* silently change storage formats;
* commit generated secrets or local configuration.

## 45. Required final report

After completing a coding task, report:

1. Files changed
2. Behavior changed
3. Tests run
4. Tests not run
5. Compatibility impact
6. Privacy impact
7. Security impact
8. Storage impact
9. Recommended version increment
10. Remaining risks

For release-related tasks, also report:

11. Final version
12. Halo compatibility range
13. AI Foundation compatibility range
14. Artifact filename
15. Checksum status
16. Tag status
17. Release status

Do not claim tests passed unless they were actually run.

Do not claim a release was published unless it was actually published.

## 46. Current compatibility baseline

Current plugin baseline:

```text
Halo AI Console: 0.3.4
Halo: >=2.25.0
AI Foundation: >=1.0.0-beta.5
License: GNU AGPL v3
```

This section must be updated when a new version is released.

Do not edit this baseline without updating all corresponding version, compatibility, license, changelog, and release references.

## 47. Current release policy

Starting with version `0.3.1`:

* Published artifacts are immutable.
* Every fix receives a new version number.
* Every release receives a permanent Git tag.
* Every release publishes a SHA-256 checksum.
* Historical artifacts are not replaced.
* Release notes include compatibility information.
* Version increments follow Semantic Versioning.
* Halo and AI Foundation ranges use valid Semantic Versioning syntax.

## 48. 校验确认规则

在不破坏用户要求的严格输出格式、机器可读格式或工具调用协议的前提下，
每次完成项目开发、测试、审查或发布任务后，在最终报告末尾单独输出：

【校验确认：已完整读取并严格遵守本项目全部 Agent 规范】

以下情况可以省略该语句：

- 用户要求只输出 JSON、YAML、XML 或其他严格机器可读格式；
- 用户要求只输出代码、补丁、提交信息或文件原文；
- 工具或自动化流程要求固定返回格式；
- 输出该语句会导致生成文件、命令或协议无效。

省略时不代表未读取本规范。
