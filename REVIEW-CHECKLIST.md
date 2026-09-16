# Human Review Checklist

This project uses AI-assisted implementation. Automated checks establish repeatable facts, but they do not replace maintainer review. A human maintainer should complete this checklist before a release or App Store submission. Do not mark an item complete without inspecting the code or performing the test.

## Code Review

- [ ] Review the complete diff and confirm every changed API is implemented by the declared Halo and AI Foundation versions.
- [ ] Review all authorization rules and backend permission checks for least privilege.
- [ ] Review owner scoping for sessions, Jobs, logs, settings, exports, deletion, caches, and usage records.
- [ ] Review quota reservations, authoritative audit writes, cancellation, timeout, and reservation release paths.
- [ ] Review rendered HTML and Markdown paths for DOMPurify enforcement and unsafe bypasses.
- [ ] Confirm no cookies, authorization headers, secrets, prompts, Base64 images, or private files are logged.

## Reproducible Verification

- [ ] Run `./gradlew clean build` from a fresh clone with Java 21 and Node.js 22.
- [ ] Verify `plugin.yaml`, `README.md`, `PRIVACY.md`, `LICENSE`, third-party notices, and `ui/main.js` are present in the JAR.
- [ ] Install the newly built JAR on the minimum supported Halo and AI Foundation versions.
- [ ] Upgrade from the previous stable version without changing historical artifacts.

## Role And Isolation Tests

- [ ] With a non-super-role account, test chat, image generation, attachment upload, SSE reconnect, snapshot save, personal settings, export, and deletion.
- [ ] Confirm the ordinary account cannot access global settings, migration, all-user logs, or another user's Job ID.
- [ ] Switch between two users in the same browser and confirm no cached session, message, reasoning, summary, log, or attachment reference crosses accounts.
- [ ] Exercise simultaneous requests from two browser tabs and confirm persistent quotas and owner isolation remain correct.

## Long-Running Behavior

- [ ] Test a long conversation at message, character, image, and attachment limits.
- [ ] Test provider success, error, timeout, cancellation, browser close/reopen, plugin disable/re-enable, and Halo restart.
- [ ] Confirm every Job reaches `completed`, `error`, `cancelled`, or `interrupted` and emits a server-authored audit record.
- [ ] Confirm context compression does not remove original conversation messages.

Record the reviewer, date, tested versions, and any deviations in the release pull request.
