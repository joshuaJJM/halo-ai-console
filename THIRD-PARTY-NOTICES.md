# Third-Party Notices

Halo AI Console bundles or uses the following third-party resources:

## DOMPurify 3.4.12

Copyright (c) Cure53 and contributors.

The bundled file is `plugin-halo-ai-console/assets/dompurify.min.js`. Its upstream license notice declares Apache License 2.0 and Mozilla Public License 2.0. The Apache License 2.0 text from the corresponding upstream release is included in [`licenses/DOMPURIFY-LICENSE.txt`](licenses/DOMPURIFY-LICENSE.txt). The bundled file also retains its upstream license banner.

## Halo and AI Foundation runtime APIs

The plugin uses APIs and runtime dependencies supplied by Halo and AI Foundation. These dependencies are not repackaged as separate third-party libraries in this JAR; their own notices and licenses remain the responsibility of the corresponding runtime distributions.

## Project-authored compatibility code

The lightweight Markdown fallback, Mermaid-compatible flowchart renderer, code highlighter, and LaTeX compatibility formatter are authored by this project and are covered by the GNU Affero General Public License v3.0 (`AGPL-3.0-only`). No `marked`, `markdown-it`, Mermaid, MathJax, or `highlight.js` distribution is bundled.
