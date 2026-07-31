# Release Integrity

## Immutable baseline

从 `0.3.1` 开始，提交到 `dist/`、上传到 GitHub Releases、提供给用户或关联到 Git 标签的制品均视为不可变。

历史 JAR 保留在 `dist/` 中用于下载和回归比较。即使历史制品包含旧作者信息、旧许可证、旧依赖范围、文档缺失或已知问题，也不会重新打包或覆盖；相关限制通过文档和后续版本说明。

## Artifact policy

- JAR 命名格式：`halo-ai-console-MAJOR.MINOR.PATCH.jar`。
- SHA-256 文件命名格式：`halo-ai-console-MAJOR.MINOR.PATCH.jar.sha256`。
- 已发布版本不得重新构建、覆盖、改名或追加 `(1)`、`final`、`fixed` 等后缀。
- 修复已发布制品时必须增加版本号并生成新制品。

## Tag policy

- 稳定版标签使用 `vMAJOR.MINOR.PATCH`。
- 预发布标签使用对应的 SemVer 预发布版本。
- 标签发布后不得移动、强制更新、删除后重建或指向不同制品。

## Checksum policy

每个新发布 JAR 都生成独立 SHA-256 文件。校验值生成后不得再修改 JAR；若 JAR 内容必须调整，应增加版本号并重新发布。

## Historical artifacts

- `0.3.0` 及更早版本属于历史制品，其元数据和许可证反映当时的打包状态。
- `0.3.1` 是当前不可变发布基线，首次完整采用 GNU AGPL v3、独立隐私文档和明确的 AI Foundation 最低版本。
- `0.3.2` 在不改变 API、存储格式或权限语义的前提下统一中文操作文案。
- `0.3.3` 补充应用市场要求的主页和问题反馈元数据，并默认不自动启用插件。
- `0.3.4` 规避 Halo 对预发布依赖范围的解析缺陷，并在插件启动阶段强制校验 AI Foundation 最低版本；同时恢复界面中的 `Token` 和 `Job` 通用术语。

## Current release

```text
Halo AI Console: 0.3.4
Halo: >=2.25.0
AI Foundation: >=1.0.0-beta.5
License: GNU AGPL v3
Artifact: halo-ai-console-0.3.4.jar
Checksum: halo-ai-console-0.3.4.jar.sha256
```
