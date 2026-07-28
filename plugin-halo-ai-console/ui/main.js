(function () {
  "use strict";

  const { definePlugin } = window.HaloUiShared;
  const { h, ref, computed, onMounted, onActivated, onBeforeUnmount, watch, markRaw, nextTick } = window.Vue;
  const components = window.HaloComponents || {};
  const axios = window.HaloApiClient && window.HaloApiClient.axiosInstance;

  const API = "/apis/console.api.aifoundation.halo.run/v1alpha1";
  const CHAT_API = "/apis/console.api.halo-ai-console.halo.run/v1alpha1";
  const STORE_KEY = "halo-ai-console:sessions";
  const SELECTED_KEY = "halo-ai-console:selected";
  const SETTINGS_KEY = "halo-ai-console:settings";
  const LOG_KEY = "halo-ai-console:call-logs";
  const SIDEBAR_KEY = "halo-ai-console:sidebar-collapsed";
  const MIGRATION_DISMISSED_KEY = "halo-ai-console:legacy-migration-dismissed";
  const EMPTY_TITLE = "新的聊天";

  function uid(prefix) {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  }

  function escapeHtml(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function inlineMarkdown(value) {
    let text = escapeHtml(value);
    text = text.replace(/`([^`]+)`/g, "<code>$1</code>");
    text = text.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    text = text.replace(/\*([^*]+)\*/g, "<em>$1</em>");
    text = text.replace(/\[([^\]]+)]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
    return text;
  }

  function protectLatexSource(markdown) {
    const tokens = [];
    const push = (raw, body, block) => {
      const token = `HALOAISOURCE${tokens.length}TOKEN`;
      tokens.push({ token, raw, body, block });
      return token;
    };
    const text = String(markdown || "")
      .replace(/\\\[([\s\S]+?)\\\]/g, (raw, body) => push(raw, body, true))
      .replace(/\$\$([\s\S]+?)\$\$/g, (raw, body) => push(raw, body, true))
      .replace(/\\\(([\s\S]+?)\\\)/g, (raw, body) => push(raw, body, false))
      .replace(/(^|[^\\])\$([^$\n]+?)\$/g, (raw, prefix, body) => `${prefix}${push(raw.slice(prefix.length), body, false)}`);
    return { text, tokens };
  }

  function restoreLatexTokens(html, tokens) {
    let output = String(html || "");
    for (const item of tokens) {
      const encodedToken = escapeHtml(item.token);
      const rendered = item.block
        ? `<div class="math-block" data-source="${escapeHtml(item.body)}">${escapeHtml(item.body)}</div>`
        : `<span class="math-inline" data-source="${escapeHtml(item.body)}">${escapeHtml(item.body)}</span>`;
      output = output.split(item.token).join(rendered).split(encodedToken).join(rendered);
    }
    return output;
  }

  function ensureDomPurify() {
    if (window.DOMPurify?.sanitize || document.querySelector("script[data-ai-chat-dompurify='true']")) return;
    const script = document.createElement("script");
    script.dataset.aiChatDompurify = "true";
    script.src = `${CHAT_API}/assets/dompurify.min.js`;
    script.defer = true;
    document.head.appendChild(script);
  }
  ensureDomPurify();

  function sanitizeRenderedHtml(html) {
    if (window.DOMPurify?.sanitize) {
      return window.DOMPurify.sanitize(String(html || ""), {
        USE_PROFILES: { html: true },
        ADD_TAGS: ["div", "span", "sup", "sub"],
        ADD_ATTR: ["target", "rel", "class", "data-source", "role", "aria-label"],
        ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
      });
    }
    if (window.Sanitizer) {
      try {
        const sanitizer = new window.Sanitizer();
        const fragment = sanitizer.sanitizeFor("div", String(html || ""));
        return fragment?.innerHTML || "";
      } catch (_) {}
    }
    const template = document.createElement("template");
    template.innerHTML = String(html || "");
    const allowedTags = new Set(["A", "P", "BR", "STRONG", "EM", "CODE", "PRE", "SPAN", "DIV", "UL", "OL", "LI", "BLOCKQUOTE", "H1", "H2", "H3", "H4", "H5", "H6", "HR", "TABLE", "THEAD", "TBODY", "TR", "TH", "TD", "IMG", "SUP", "SUB", "SVG", "G", "RECT", "LINE", "PATH", "TEXT", "DEFS", "MARKER", "POLYGON"]);
    const allowedAttrs = new Set(["href", "src", "alt", "title", "class", "target", "rel", "data-source", "viewbox", "width", "height", "x", "y", "x1", "y1", "x2", "y2", "rx", "ry", "d", "fill", "stroke", "stroke-width", "font-size", "text-anchor", "marker-end", "points", "id", "orient", "markerwidth", "markerheight", "refx", "refy"]);
    const safeUrl = (value, image) => {
      const text = String(value || "").trim();
      if (!text) return false;
      if (text.startsWith("#") || text.startsWith("/")) return true;
      if (/^https?:\/\//i.test(text)) return true;
      if (!image && /^mailto:/i.test(text)) return true;
      return image && /^data:image\/(?:png|jpe?g|gif|webp);base64,/i.test(text);
    };
    const walk = (node) => {
      for (const child of Array.from(node.children || [])) {
        if (!allowedTags.has(child.tagName)) {
          child.replaceWith(document.createTextNode(child.textContent || ""));
          continue;
        }
        for (const attr of Array.from(child.attributes)) {
          const name = attr.name.toLowerCase();
          if (name.startsWith("on") || !allowedAttrs.has(name)) {
            child.removeAttribute(attr.name);
            continue;
          }
          if (name === "href" && !safeUrl(attr.value, false)) child.removeAttribute(attr.name);
          if (name === "src" && !safeUrl(attr.value, true)) child.removeAttribute(attr.name);
          if (name === "class") {
            child.setAttribute("class", attr.value.split(/\s+/).filter((item) => /^[A-Za-z0-9_-]{1,40}$/.test(item)).slice(0, 8).join(" "));
          }
        }
        if (child.tagName === "A") {
          child.setAttribute("target", "_blank");
          child.setAttribute("rel", "noreferrer noopener");
        }
        walk(child);
      }
    };
    walk(template.content);
    return template.innerHTML;
  }

  function normalizeMarkdownText(value) {
    return String(value || "")
      .replace(/\r\n/g, "\n")
      .replace(/([^\n])```/g, "$1\n```")
      .replace(/```([A-Za-z0-9_+-]+)?[ \t]+(?=\S)/g, (_, lang) => `\`\`\`${lang || ""}\n`)
      .replace(/([^\n])(\s*-{3,}\s*)(?=#{1,6})/g, "$1\n$2\n")
      .replace(/([^\n])(\s+#{1,6}\s*)/g, "$1\n$2")
      .replace(/([^\n])(#{1,6})(?=[^\s#])/g, "$1\n$2 ")
      .replace(/(^|\n)(#{1,6})(?=[^\s#])/g, "$1$2 ")
      .replace(/([^\n])(\s+\d+[.)]\s*)/g, "$1\n$2")
      .replace(/(^|\n)(\d+[.)])(?=\S)/g, "$1$2 ");
  }

  function normalizeCodeBlock(value, lang) {
    let code = String(value || "").replace(/\r\n/g, "\n").trimEnd();
    const language = String(lang || "").toLowerCase();
    if (!/^py|python$/.test(language) || code.split("\n").length > 2 || code.length < 80) {
      return code;
    }

    code = code
      .replace(/;/g, "\n")
      .replace(/([^\n])#/g, "$1\n#")
      .replace(/(#[^\n]*?)(?=(?:[A-Za-z_]\w*\s*(?:[+\-*/]?=)|for\s+|if\s+|while\s+|def\s+|class\s+|print\s*\(|return\b))/g, "$1\n")
      .replace(/([^\n])\b(for\s+[^:\n]+:)/g, "$1\n$2")
      .replace(/([^\n])\b(if\s+[^:\n]+:)/g, "$1\n$2")
      .replace(/([^\n])\b(while\s+[^:\n]+:)/g, "$1\n$2")
      .replace(/([^\n])\b(print\s*\()/g, "$1\n$2")
      .replace(/:\s*(?=(?:#|[A-Za-z_]\w*\s*(?:[+\-*/]?=)|for\s+|if\s+|while\s+|print\s*\(|return\b))/g, ":\n");

    const lines = code.split("\n").map((line) => line.trim()).filter(Boolean);
    let indent = 0;
    return lines.map((line) => {
      if (/^(elif|else|except|finally)\b/.test(line)) indent = Math.max(0, indent - 1);
      const rendered = `${"    ".repeat(indent)}${line}`;
      if (/:$/.test(line) && !line.startsWith("#")) indent += 1;
      return rendered;
    }).join("\n");
  }

  function renderMermaidDiagram(source) {
    const lines = String(source || "").replace(/\r\n/g, "\n").split("\n").map((line) => line.trim()).filter(Boolean);
    const edges = [];
    const labels = new Map();
    const labelFor = (raw) => {
      const text = String(raw || "").trim();
      const match = /^([A-Za-z0-9_-]+)(?:\[(.+)]|\((.+)\)|\{(.+)})?$/.exec(text);
      if (!match) return text.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 24) || "node";
      const id = match[1];
      const label = match[2] || match[3] || match[4] || id;
      if (label !== id || !labels.has(id)) {
        labels.set(id, label.replace(/^["']|["']$/g, ""));
      }
      return id;
    };
    for (const line of lines.slice(0, 40)) {
      if (/^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram)/i.test(line)) continue;
      const match = /^(.+?)\s*[-=.]*-{1,2}>+\s*(.+)$/.exec(line);
      if (match) {
        const from = labelFor(match[1]);
        const to = labelFor(match[2]);
        edges.push([from, to]);
        if (!labels.has(from)) labels.set(from, from);
        if (!labels.has(to)) labels.set(to, to);
      }
    }
    if (!edges.length) {
      return `<pre class="md-code"><span>mermaid</span><code>${escapeHtml(source)}</code></pre>`;
    }
    const ordered = [];
    edges.slice(0, 40).forEach(([from, to]) => {
      if (!ordered.includes(from)) ordered.push(from);
      if (!ordered.includes(to)) ordered.push(to);
    });
    const nodes = ordered.slice(0, 24);
    const nodeHtml = nodes.map((id, index) => {
      const label = escapeHtml(labels.get(id) || id).slice(0, 36);
      const arrow = index < nodes.length - 1 ? `<span class="mermaid-arrow">→</span>` : "";
      return `<span class="mermaid-node">${label}</span>${arrow}`;
    }).join("");
    return `<div class="mermaid-diagram" role="img" aria-label="Mermaid diagram">${nodeHtml}</div>`;
  }

  function restoreMermaidBlocks(html) {
    if (!/mermaid|graph|flowchart/i.test(String(html || ""))) return html;
    const template = document.createElement("template");
    template.innerHTML = String(html || "");
    template.content.querySelectorAll("pre").forEach((pre) => {
      const marker = (pre.querySelector("span")?.textContent || "").trim().toLowerCase();
      const code = pre.querySelector("code")?.textContent || pre.textContent || "";
      const source = marker === "mermaid" ? code : code.replace(/^mermaid\s*\n/i, "");
      if (marker !== "mermaid" && !/^\s*(graph|flowchart)\b/i.test(source)) return;
      const wrapper = document.createElement("div");
      wrapper.innerHTML = renderMermaidDiagram(source);
      pre.replaceWith(...Array.from(wrapper.childNodes));
    });
    return template.innerHTML;
  }

  function renderWithExternalMarkdown(source) {
    const protectedLatex = protectLatexSource(source);
    const markdownSource = protectedLatex.text;
    const rte = window.RichTextEditor;
    if (rte?.defaultMarkdownParser?.parse && rte?.getHTMLFromFragment) {
      try {
        const doc = rte.defaultMarkdownParser.parse(markdownSource);
        const schema = doc?.type?.schema || rte.markDownSchema || rte.schema;
        const html = doc?.content && schema ? rte.getHTMLFromFragment(doc.content, schema) : "";
        if (html) return sanitizeRenderedHtml(restoreLatexTokens(html, protectedLatex.tokens));
      } catch (_) {}
    }
    const markdownit = window.markdownit || window.markdownIt;
    if (markdownit) {
      try {
        return sanitizeRenderedHtml(restoreLatexTokens(markdownit({ html: false, linkify: true, breaks: true }).render(markdownSource), protectedLatex.tokens));
      } catch (_) {}
    }
    if (window.marked?.parse) {
      try {
        return sanitizeRenderedHtml(restoreLatexTokens(window.marked.parse(markdownSource, { breaks: true, gfm: true, mangle: false, headerIds: false }), protectedLatex.tokens));
      } catch (_) {}
    }
    return "";
  }

  function renderMarkdown(source) {
    const text = normalizeMarkdownText(source);
    if (!text.trim()) return "";
    const external = /```mermaid\b/i.test(text) ? "" : renderWithExternalMarkdown(text);
    if (external) return restoreMermaidBlocks(external);

    const protectedLatex = protectLatexSource(text);
    const lines = protectedLatex.text.split("\n");
    const html = [];
    let paragraph = [];
    let list = [];
    let listType = "";
    let codeLang = "";
    let codeLines = null;

    const flushParagraph = () => {
      if (!paragraph.length) return;
      html.push(`<p>${inlineMarkdown(paragraph.join(" ").trim())}</p>`);
      paragraph = [];
    };
    const flushList = () => {
      if (!list.length) return;
      const tag = listType === "ol" ? "ol" : "ul";
      html.push(`<${tag}>${list.map((item) => `<li>${inlineMarkdown(item)}</li>`).join("")}</${tag}>`);
      list = [];
      listType = "";
    };
    const flushCode = () => {
      if (!codeLines) return;
      const lang = codeLang ? `<span>${escapeHtml(codeLang)}</span>` : "";
      if (String(codeLang || "").toLowerCase() === "mermaid") {
        html.push(renderMermaidDiagram(codeLines.join("\n")));
      } else {
        html.push(`<pre class="md-code">${lang}<code>${escapeHtml(normalizeCodeBlock(codeLines.join("\n"), codeLang))}</code></pre>`);
      }
      codeLines = null;
      codeLang = "";
    };
    const pushList = (type, value) => {
      flushParagraph();
      if (listType && listType !== type) flushList();
      listType = type;
      list.push(value);
    };

    for (const line of lines) {
      const trimmed = line.trim();
      const fence = /^```([A-Za-z0-9_+-]*)\s*$/.exec(trimmed);
      if (fence) {
        if (codeLines) {
          flushCode();
        } else {
          flushParagraph();
          flushList();
          codeLang = fence[1] || "";
          codeLines = [];
        }
        continue;
      }
      if (codeLines) {
        codeLines.push(line);
        continue;
      }

      if (!trimmed) {
        flushParagraph();
        flushList();
        continue;
      }
      const heading = /^(#{1,4})\s*(.+)$/.exec(trimmed);
      if (heading) {
        flushParagraph();
        flushList();
        const level = heading[1].length + 1;
        html.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`);
        continue;
      }
      const bullet = /^[-*]\s+(.+)$/.exec(trimmed);
      if (bullet) {
        pushList("ul", bullet[1]);
        continue;
      }
      const numbered = /^\d+[.)]\s+(.+)$/.exec(trimmed);
      if (numbered) {
        pushList("ol", numbered[1]);
        continue;
      }
      flushList();
      if (/^>\s+/.test(trimmed)) {
        flushParagraph();
        html.push(`<blockquote>${inlineMarkdown(trimmed.replace(/^>\s+/, ""))}</blockquote>`);
      } else {
        paragraph.push(trimmed);
      }
    }
    flushParagraph();
    flushList();
    if (codeLines) flushCode();
    return restoreMermaidBlocks(sanitizeRenderedHtml(restoreLatexTokens(html.join(""), protectedLatex.tokens)));
  }

  function ensureMathRenderer() {
    return Promise.resolve();
  }

  function renderSimpleLatex(source, display) {
    const greek = {
      alpha: "α", beta: "β", gamma: "γ", delta: "δ", epsilon: "ε", theta: "θ",
      lambda: "λ", mu: "μ", pi: "π", sigma: "σ", phi: "φ", omega: "ω",
      pm: "±", times: "×", cdot: "·", leq: "≤", geq: "≥", neq: "≠",
      infty: "∞",
    };
    let text = String(source || "").trim()
      .replace(/\\left|\\right/g, "")
      .replace(/\\,/g, " ");

    const render = (value) => renderSimpleLatex(value, false);
    for (let i = 0; i < 8; i += 1) {
      text = text.replace(/\\frac\s*\{([^{}]+)\}\s*\{([^{}]+)\}/g, (_, top, bottom) =>
        `<span class="math-frac"><span>${render(top)}</span><span>${render(bottom)}</span></span>`);
      text = text.replace(/\\sqrt\s*\{([^{}]+)\}/g, (_, body) =>
        `<span class="math-sqrt"><span>${render(body)}</span></span>`);
      text = text.replace(/\^\{([^{}]+)\}/g, (_, body) => `<sup>${render(body)}</sup>`);
      text = text.replace(/_\{([^{}]+)\}/g, (_, body) => `<sub>${render(body)}</sub>`);
    }
    text = escapeHtml(text)
      .replace(/&lt;(\/?(?:span|sup|sub)(?:\s+class=&quot;[A-Za-z0-9 -]+&quot;)?)&gt;/g, (_, tag) => `<${tag.replace(/&quot;/g, "\"")}>`)
      .replace(/\\([A-Za-z]+)/g, (_, name) => greek[name] || name)
      .replace(/\^([A-Za-z0-9+\-=])/g, "<sup>$1</sup>")
      .replace(/_([A-Za-z0-9+\-=])/g, "<sub>$1</sub>")
      .replace(/\s+/g, " ");
    return `<span class="${display ? "math-lite math-lite-block" : "math-lite"}">${text}</span>`;
  }

  function renderMathFallback(container, afterRender) {
    container.querySelectorAll(".math-inline,.math-block").forEach((node) => {
      if (node.dataset.rendered === "true") return;
      const source = node.dataset.source || node.textContent;
      node.dataset.source = source;
      if (!String(source || "").trim()) return;
      node.innerHTML = renderSimpleLatex(source, node.classList.contains("math-block"));
      node.dataset.rendered = "true";
    });
    if (typeof afterRender === "function") afterRender();
  }

  function renderMath(container, afterRender) {
    if (!container) return;
    renderMathFallback(container, afterRender);
  }

  function ensureHighlightJs() {
    if (window.hljs?.highlightElement) return Promise.resolve(window.hljs);
    if (window.haloAiChatHighlight?.highlightElement) return Promise.resolve(window.haloAiChatHighlight);
    if (!document.querySelector("style[data-ai-chat-highlight='true']")) {
      const style = document.createElement("style");
      style.dataset.aiChatHighlight = "true";
      style.textContent = ".markdown-body pre code .hljs-keyword{color:#93c5fd}.markdown-body pre code .hljs-string{color:#86efac}.markdown-body pre code .hljs-number{color:#fbbf24}.markdown-body pre code .hljs-comment{color:#94a3b8;font-style:italic}.markdown-body pre code .hljs-built_in{color:#f0abfc}";
      document.head.appendChild(style);
    }
    window.haloAiChatHighlight = {
      highlightElement(node) {
        if (!node || node.dataset.highlighted) return;
        let html = escapeHtml(node.textContent || "");
        html = html
          .replace(/(\/\/[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/)/g, '<span class="hljs-comment">$1</span>')
          .replace(/("[^"\n]*"|'[^'\n]*'|`[^`\n]*`)/g, '<span class="hljs-string">$1</span>')
          .replace(/\b(\d+(?:\.\d+)?)\b/g, '<span class="hljs-number">$1</span>')
          .replace(/\b(function|class|const|let|var|return|if|else|for|while|try|catch|finally|await|async|import|from|export|def|lambda|in|not|and|or|public|private|static|new|void|true|false|null|None|True|False)\b/g, '<span class="hljs-keyword">$1</span>')
          .replace(/\b(console|print|Math|JSON|String|Number|Array|Object|Map|Set)\b/g, '<span class="hljs-built_in">$1</span>');
        node.innerHTML = html;
        node.dataset.highlighted = "yes";
      },
    };
    return Promise.resolve(window.haloAiChatHighlight);
  }

  function highlightCode(container) {
    if (!container) return;
    ensureHighlightJs().then((hljs) => {
      if (!hljs?.highlightElement) return;
      container.querySelectorAll("pre code:not([data-highlighted])").forEach((node) => {
        try {
          hljs.highlightElement(node);
        } catch (_) {}
      });
    });
  }

  function loadSessions() {
    try {
      const parsed = JSON.parse(localStorage.getItem(STORE_KEY) || "[]");
      if (!Array.isArray(parsed)) return [];
      return parsed.map((session) => ({
        ...session,
        messages: Array.isArray(session.messages)
          ? session.messages.filter((message) => {
            const hasText = typeof message.content === "string" && message.content.trim().length > 0;
            const hasReasoning = typeof message.reasoning === "string" && message.reasoning.trim().length > 0;
            const hasFiles = Array.isArray(message.files) && message.files.length > 0;
            const hasImages = Array.isArray(message.images) && message.images.length > 0;
            const text = String(message.content || "");
            const isKnownFailure = text.includes("message parts must not be empty")
              || text.includes("模型没有返回文本内容")
              || text.startsWith("调用失败：")
              || text.startsWith('{"detail":')
              || text.startsWith("{ detail:");
            return (hasText || hasReasoning || hasFiles || hasImages) && !isKnownFailure;
          })
          : [],
      }));
    } catch (_) {
      return [];
    }
  }

  function saveSessions(sessions) {
    localStorage.setItem(STORE_KEY, JSON.stringify(sessions.slice(0, 50)));
  }

  function loadSettings() {
    try {
      return { lazyBatchSize: 60, olderBatchSize: 40, imageMaxSizeMb: 8, autoCompressPercent: 85, memoryText: "", ...JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}") };
    } catch (_) {
      return { lazyBatchSize: 60, olderBatchSize: 40, imageMaxSizeMb: 8, autoCompressPercent: 85, memoryText: "" };
    }
  }

  function saveSettings(settings) {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  }

  async function loadSettingsFromDb() {
    const { data } = await axios.get(`${CHAT_API}/settings`);
    const settings = { ...loadSettings(), ...(data || {}) };
    saveSettings(settings);
    return settings;
  }

  async function saveSettingsToDb(settings) {
    const { data } = await axios.put(`${CHAT_API}/settings`, settings);
    const saved = { ...loadSettings(), ...(data || settings) };
    saveSettings(saved);
    return saved;
  }

  function defaultGlobalSettings() {
    return {
      defaultLanguageModelMode: "default",
      defaultLanguageModel: "",
      defaultMultimodalModelMode: "default",
      defaultMultimodalModel: "",
      defaultImageModelMode: "default",
      defaultImageModel: "",
      allowedModels: [],
      imageMaxSizeMb: 8,
    };
  }

  async function loadGlobalSettingsFromDb() {
    const { data } = await axios.get(`${CHAT_API}/global-settings`);
    return { ...defaultGlobalSettings(), ...(data || {}) };
  }

  function loadCallLogs() {
    try {
      const parsed = JSON.parse(localStorage.getItem(LOG_KEY) || "[]");
      return Array.isArray(parsed) ? parsed : [];
    } catch (_) {
      return [];
    }
  }

  function saveCallLogs(logs) {
    localStorage.setItem(LOG_KEY, JSON.stringify(logs.slice(0, 300)));
  }

  function normalizeOption(item) {
    const annotations = item.metadata?.annotations || item.annotations || {};
    const isDefault = item.default === true
      || item.isDefault === true
      || item.defaultModel === true
      || item.spec?.default === true
      || item.status?.default === true
      || annotations["ai.halo.run/default"] === "true"
      || annotations["aifoundation.halo.run/default"] === "true";
    return {
      name: item.name || item.metadata?.name || item.value,
      label: item.displayName || item.label || item.name || item.metadata?.name || item.modelId,
      modelType: String(item.modelType || item.type || item.spec?.modelType || "").toLowerCase(),
      enabled: item.enabled !== false && item.available !== false,
      isDefault,
      raw: item,
    };
  }

  function isImageModelType(type) {
    const value = String(type || "").toLowerCase().replace(/[-_\s]/g, "");
    return value === "image" || value === "imagegeneration" || value === "texttoimage";
  }

  function isLanguageModelType(type) {
    const value = String(type || "").toLowerCase().replace(/[-_\s]/g, "");
    return !value || value === "language" || value === "chat" || value === "multimodal" || value === "vision";
  }

  function isMultimodalModel(model) {
    const raw = model?.raw || {};
    const type = String(model?.modelType || "").toLowerCase().replace(/[-_\s]/g, "");
    return type === "multimodal"
      || type === "vision"
      || raw.multimodal === true
      || raw.vision === true
      || raw.supportsVision === true
      || raw.supportsImage === true
      || raw.capabilities?.vision === true
      || raw.capabilities?.multimodal === true
      || raw.capabilities?.imageInput === true
      || raw.capabilities?.language?.imageInput === true
      || raw.spec?.multimodal === true
      || raw.spec?.vision === true
      || raw.spec?.supportsImage === true;
  }

  function normalizeSession(session) {
    return {
      id: session.id || uid("chat"),
      title: session.title || EMPTY_TITLE,
      memory: session.memory || "",
      tags: Array.isArray(session.tags) ? session.tags : [],
      contextClearedAt: session.contextClearedAt || 0,
      createdAt: session.createdAt || Date.now(),
      updatedAt: session.updatedAt || session.createdAt || Date.now(),
      messages: Array.isArray(session.messages) ? session.messages : [],
    };
  }

  function fileToData(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
  }

  function estimateTokens(text) {
    const value = String(text || "");
    const cjk = (value.match(/[\u3400-\u9fff]/g) || []).length;
    const words = (value.replace(/[\u3400-\u9fff]/g, " ").match(/[A-Za-z0-9_]+/g) || []).length;
    return Math.max(0, Math.ceil(cjk * 0.75 + words * 1.25 + value.length / 12));
  }

  function compactTokenCount(value) {
    const count = Math.max(0, Number(value) || 0);
    if (count >= 1000000) return `${(count / 1000000).toFixed(1)}m`;
    if (count >= 1000) return `${Math.round(count / 1000)}k`;
    return String(count);
  }

  function modelContextLimit(model) {
    const raw = model?.raw || {};
    const candidates = [
      model?.contextWindow,
      model?.maxContextTokens,
      raw.contextWindow,
      raw.maxContextTokens,
      raw.maxInputTokens,
      raw.spec?.contextWindow,
      raw.spec?.maxContextTokens,
      raw.spec?.maxInputTokens,
      raw.status?.contextWindow,
      raw.status?.maxContextTokens,
      raw.capabilities?.language?.contextWindow,
      raw.capabilities?.language?.maxInputTokens,
      raw.capabilities?.language?.maxContextTokens,
    ];
    const found = candidates.map(Number).find((item) => Number.isFinite(item) && item > 0);
    return found || 128000;
  }

  function downloadText(filename, text) {
    const blob = new Blob([text], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function chunkPayload(chunk) {
    return chunk?.delta || chunk?.text || chunk?.content || chunk?.data || "";
  }

  function chunkKind(chunk) {
    const type = String(chunk?.type || chunk?.chunkType || "");
    if (type.includes("reasoning")) return "reasoning";
    if (type.includes("text")) return "text";
    if (typeof chunk?.delta === "string") return "text";
    return "";
  }

  function collectGeneratedImages(payload) {
    const items = [
      ...(Array.isArray(payload?.images) ? payload.images : []),
      ...(Array.isArray(payload?.files) ? payload.files : []),
      ...(payload?.image ? [payload.image] : []),
      ...(payload?.file ? [payload.file] : []),
    ];
    return items.map((item) => {
      if (typeof item === "string") return item;
      return item?.url || item?.data || item?.base64 || item?.b64Json || item?.b64_json || "";
    }).filter(Boolean);
  }

  async function readUiMessageStream(response, onChunk) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const dispatchEvent = (event) => {
      const data = event.data.join("\n");
      const marker = data.trim();
      if (!marker || marker === "[DONE]" || marker === "DONE") return;
      try {
        const parsed = JSON.parse(data);
        const kind = chunkKind(parsed);
        const text = chunkPayload(parsed);
        if (kind && text) onChunk(kind, text, parsed);
        if (String(parsed?.type || event.event || "").includes("error") && (parsed.errorText || parsed.message)) {
          onChunk("text", parsed.errorText || parsed.message, parsed);
        }
      } catch (_) {
        onChunk("text", data, { event: event.event || "message" });
      }
    };
    const processFrame = (frame) => {
      const event = { event: "message", data: [], id: undefined, retry: undefined };
      for (const rawLine of frame.replace(/\r\n/g, "\n").split("\n")) {
        if (!rawLine || rawLine.startsWith(":")) continue;
        const separator = rawLine.indexOf(":");
        const field = separator >= 0 ? rawLine.slice(0, separator) : rawLine;
        const value = separator >= 0 ? rawLine.slice(separator + 1).replace(/^ /, "") : "";
        if (field === "event") event.event = value || "message";
        if (field === "data") event.data.push(value);
        if (field === "id") event.id = value;
        if (field === "retry") event.retry = Number(value);
      }
      dispatchEvent(event);
    };
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        if (buffer.trim()) processFrame(buffer);
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, "\n");
      const frames = buffer.split("\n\n");
      buffer = frames.pop() || "";
      for (const frame of frames) {
        processFrame(frame);
      }
    }
  }

  async function readGenericSseStream(response, onEvent) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const processFrame = (frame) => {
      const event = { event: "message", data: [], id: undefined, retry: undefined };
      for (const rawLine of frame.replace(/\r\n/g, "\n").split("\n")) {
        if (!rawLine || rawLine.startsWith(":")) continue;
        const separator = rawLine.indexOf(":");
        const field = separator >= 0 ? rawLine.slice(0, separator) : rawLine;
        const value = separator >= 0 ? rawLine.slice(separator + 1).replace(/^ /, "") : "";
        if (field === "event") event.event = value || "message";
        if (field === "data") event.data.push(value);
        if (field === "id") event.id = value;
        if (field === "retry") event.retry = Number(value);
      }
      onEvent(event);
    };
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        if (buffer.trim()) processFrame(buffer);
        break;
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
      const frames = buffer.split("\n\n");
      buffer = frames.pop() || "";
      for (const frame of frames) processFrame(frame);
    }
  }

  async function postModelStream(model, pathCandidates, body, options = {}) {
    let lastResponse;
    for (const path of pathCandidates) {
      const response = await fetch(`${API}/models/${encodeURIComponent(model)}/${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify(body),
        signal: options.signal,
      });
      if (response.status === 404 || response.status === 405) {
        lastResponse = response;
        continue;
      }
      return response;
    }
    return lastResponse;
  }

  async function postModelJson(model, pathCandidates, body, options = {}) {
    let lastError;
    for (const path of pathCandidates) {
      try {
        return await axios.post(`${API}/models/${encodeURIComponent(model)}/${path}`, body, { signal: options.signal });
      } catch (err) {
        const status = err?.response?.status;
        if (status === 404 || status === 405) {
          lastError = err;
          continue;
        }
        throw err;
      }
    }
    throw lastError || new Error("No compatible AI Foundation endpoint is available.");
  }

  const MarkdownContent = {
    name: "MarkdownContent",
    props: ["content", "onRendered"],
    mounted() { renderMath(this.$el, this.onRendered); highlightCode(this.$el); },
    updated() { renderMath(this.$el, this.onRendered); highlightCode(this.$el); },
    render() {
      return h("div", { class: "markdown-body", innerHTML: renderMarkdown(this.content || "") });
    },
  };

  const ChatView = {
    name: "HaloAiConsoleView",
    setup() {
      const sessions = ref(loadSessions());
      const selectedId = ref(localStorage.getItem(SELECTED_KEY) || "");
      const input = ref("");
      const mode = ref("chat");
      const modelName = ref("default");
      const models = ref([]);
      const files = ref([]);
      const loading = ref(false);
      const error = ref("");
      const chatEl = ref(null);
      const renamingId = ref("");
      const renameValue = ref("");
      const settings = ref(loadSettings());
      const globalSettings = ref(defaultGlobalSettings());
      const visibleMessageCount = ref(Number(settings.value.lazyBatchSize) || 60);
      const callLogs = ref(loadCallLogs());
      const abortController = ref(null);
      const activeJobId = ref("");
      const editingMessageId = ref("");
      const editingMessageValue = ref("");
      const dragActive = ref(false);
      const dbReady = ref(false);
      const sidebarCollapsed = ref(localStorage.getItem(SIDEBAR_KEY) === "true");
      const contextCompressing = ref(false);
      const sessionQuery = ref("");
      const showFavoritesOnly = ref(false);
      let persistTimer = 0;
      let lastAutoCompressionAt = 0;

      if (!sessions.value.length) {
        const id = uid("chat");
        sessions.value.push({ id, title: EMPTY_TITLE, memory: "", createdAt: Date.now(), updatedAt: Date.now(), messages: [] });
        selectedId.value = id;
      }

      const current = computed(() => sessions.value.find((item) => item.id === selectedId.value) || sessions.value[0]);
      const filteredSessions = computed(() => {
        const query = sessionQuery.value.trim().toLowerCase();
        if (!query) return sessions.value;
        return sessions.value.filter((session) => {
          const haystack = [
            session.title,
            session.id,
            session.memory,
            ...(session.tags || []),
            ...(session.messages || []).slice(-20).flatMap((message) => [
              message.content || "",
              message.reasoning || "",
              ...(message.tags || []),
              message.favorite ? "favorite starred bookmarked" : "",
            ]),
          ].join("\n").toLowerCase();
          return haystack.includes(query);
        });
      });
      const renderedMessages = computed(() => {
        const messages = current.value?.messages || [];
        return messages.slice(Math.max(0, messages.length - visibleMessageCount.value));
      });
      const favoriteMessages = computed(() => sessions.value.flatMap((session) =>
        (session.messages || [])
          .filter((message) => message.favorite)
          .map((message) => ({ ...message, _sourceMessage: message, _sessionTitle: session.title, _sessionId: session.id }))
      ));
      const conversationTags = computed(() => current.value?.tags || []);
      const visibleMessages = computed(() => showFavoritesOnly.value ? favoriteMessages.value : renderedMessages.value);
      const hiddenMessageCount = computed(() => Math.max(0, (current.value?.messages?.length || 0) - visibleMessageCount.value));
      const allowedModelSet = computed(() => {
        const allowed = Array.isArray(globalSettings.value.allowedModels) ? globalSettings.value.allowedModels : [];
        return new Set(allowed.filter(Boolean));
      });
      const allowedModels = computed(() => models.value.filter((item) => item.enabled && (!allowedModelSet.value.size || allowedModelSet.value.has(item.name))));
      const customDefaultModel = (modeKey, nameKey, pool) => {
        if (globalSettings.value?.[modeKey] !== "custom") return null;
        const name = String(globalSettings.value?.[nameKey] || "");
        return pool.find((item) => item.name === name) || null;
      };
      const languageModels = computed(() => allowedModels.value.filter((item) => isLanguageModelType(item.modelType)));
      const multimodalModels = computed(() => languageModels.value.filter((item) => isMultimodalModel(item)));
      const imageModels = computed(() => allowedModels.value.filter((item) => isImageModelType(item.modelType)));
      const defaultLanguageModel = computed(() =>
        customDefaultModel("defaultLanguageModelMode", "defaultLanguageModel", languageModels.value)
        || languageModels.value.find((item) => item.isDefault)
        || languageModels.value[0]);
      const defaultMultimodalModel = computed(() =>
        customDefaultModel("defaultMultimodalModelMode", "defaultMultimodalModel", multimodalModels.value)
        || multimodalModels.value.find((item) => item.isDefault)
        || multimodalModels.value[0]);
      const defaultImageModel = computed(() =>
        customDefaultModel("defaultImageModelMode", "defaultImageModel", imageModels.value)
        || imageModels.value.find((item) => item.isDefault)
        || imageModels.value[0]);
      const activeModel = computed(() => {
        if (modelName.value !== "default") return allowedModels.value.find((item) => item.name === modelName.value);
        return mode.value === "image" ? defaultImageModel.value : defaultLanguageModel.value;
      });
      const contextLimit = computed(() => modelContextLimit(activeModel.value));
      const contextUsed = computed(() => {
        const clearedAt = current.value?.contextClearedAt || 0;
        return (current.value?.messages || []).reduce((sum, message) => {
          if (clearedAt && (message.createdAt || 0) < clearedAt) return sum;
          const files = (message.files || []).reduce((fileSum, file) => fileSum + estimateTokens(file.name || file.title || ""), 0);
          return sum + estimateTokens(`${message.content || ""}\n${message.reasoning || ""}`) + files;
        }, 0);
      });
      const contextRemaining = computed(() => Math.max(0, contextLimit.value - contextUsed.value));
      const contextUsedPercent = computed(() => Math.min(100, Math.round((contextUsed.value / Math.max(1, contextLimit.value)) * 100)));
      const selectableModels = computed(() => [
        { label: "默认", value: "default" },
        ...allowedModels.value.map((item) => ({ label: `${item.label} (${item.modelType || "model"})`, value: item.name })),
      ]);

      function persist() {
        saveSessions(sessions.value);
        localStorage.setItem(SELECTED_KEY, selectedId.value);
        queuePersistDb();
      }

      function persistLocalOnly() {
        saveSessions(sessions.value);
        localStorage.setItem(SELECTED_KEY, selectedId.value);
      }

      function persistStreaming() {
        persistLocalOnly();
      }

      function flushStreamingPersist() {
        persist();
      }

      function toggleSidebar() {
        sidebarCollapsed.value = !sidebarCollapsed.value;
        localStorage.setItem(SIDEBAR_KEY, sidebarCollapsed.value ? "true" : "false");
      }

      async function compressContext() {
        if (!current.value || contextCompressing.value) return;
        const messages = current.value.messages || [];
        const recent = messages.slice(-8);
        const older = messages.slice(0, Math.max(0, messages.length - recent.length));
        if (!older.length) {
          window.alert("当前对话还没有可压缩的更早上下文。");
          return;
        }
        if (!window.confirm("将更早的对话压缩为摘要，并保留最近消息。继续吗？")) return;
        const model = defaultLanguageModel.value?.name || languageModels.value[0]?.name;
        if (!model) {
          error.value = "没有可用于压缩上下文的语言模型。";
          return;
        }
        contextCompressing.value = true;
        error.value = "";
        try {
          const source = older.map((message) => `${message.role === "user" ? "用户" : "AI"}：${message.content || ""}${message.reasoning ? `\n思考：${message.reasoning}` : ""}`).join("\n\n");
          const response = await postModelStream(model, ["chat/ui-message/stream", "test-chat/ui-message/stream"], {
              id: uid("compress"),
              trigger: "submit-message",
              messages: [{
                id: uid("compress-user"),
                role: "user",
                parts: [{
                  type: "text",
                  id: uid("compress-text"),
                  text: `请把以下对话压缩成一段给后续 AI 继续理解的上下文摘要。保留用户目标、关键事实、重要结论、未解决事项，删除寒暄和重复内容。\n\n${source}`,
                }],
              }],
              maxOutputTokens: 1200,
            });
          if (!response.ok) throw new Error(await response.text());
          let summary = "";
          await readUiMessageStream(response, (kind, text) => {
            if (kind === "text") summary += text;
          });
          summary = summary.trim();
          if (!summary) throw new Error("压缩模型没有返回摘要。");
          const now = Date.now();
          current.value.memory = [current.value.memory, summary].filter(Boolean).join("\n\n").slice(-12000);
          current.value.messages = [
            { id: uid("summary"), role: "assistant", content: `上下文摘要：\n\n${summary}`, createdAt: now - 1 },
            ...recent,
          ];
          current.value.contextClearedAt = 0;
          current.value.updatedAt = now;
          persist();
          scheduleScrollBottom();
        } catch (err) {
          error.value = err?.message || "上下文压缩失败。";
        } finally {
          contextCompressing.value = false;
        }
      }

      async function autoCompressContext() {
        if (!current.value || contextCompressing.value) return;
        const messages = current.value.messages || [];
        const recent = messages.slice(-8);
        const older = messages.slice(0, Math.max(0, messages.length - recent.length));
        const model = defaultLanguageModel.value?.name || languageModels.value[0]?.name;
        if (!older.length || !model) return;
        contextCompressing.value = true;
        try {
          const source = older.map((message) => `${message.role === "user" ? "User" : "AI"}: ${message.content || ""}${message.reasoning ? `\nReasoning: ${message.reasoning}` : ""}`).join("\n\n");
          const response = await postModelStream(model, ["chat/ui-message/stream", "test-chat/ui-message/stream"], {
              id: uid("auto-compress"),
              trigger: "submit-message",
              messages: [{
                id: uid("auto-compress-user"),
                role: "user",
                parts: [{
                  type: "text",
                  id: uid("auto-compress-text"),
                  text: `Summarize these old messages for future context. Preserve key facts, user preferences, constraints, decisions, unresolved tasks, names, URLs, filenames, and exact errors. Keep it concise.\n\n${source}`,
                }],
              }],
              maxOutputTokens: 1200,
            });
          if (!response.ok) return;
          let summary = "";
          await readUiMessageStream(response, (kind, text) => {
            if (kind === "text") summary += text;
          });
          summary = summary.trim();
          if (!summary) return;
          const now = Date.now();
          current.value.memory = [current.value.memory, summary].filter(Boolean).join("\n\n").slice(-12000);
          current.value.messages = [
            { id: uid("summary"), role: "assistant", content: `Context summary:\n\n${summary}`, createdAt: now - 1 },
            ...recent,
          ];
          current.value.contextClearedAt = 0;
          current.value.updatedAt = now;
          persist();
          scheduleScrollBottom();
        } finally {
          contextCompressing.value = false;
        }
      }

      async function loadDbSessions() {
        try {
          const { data } = await axios.get(`${CHAT_API}/sessions-with-messages`);
          if (Array.isArray(data) && data.length) {
            sessions.value = data.map(normalizeSession);
            if (!sessions.value.some((item) => item.id === selectedId.value)) {
              selectedId.value = sessions.value[0].id;
            }
            saveSessions(sessions.value);
          }
          dbReady.value = true;
        } catch (_) {
          dbReady.value = false;
        }
      }

      async function loadDbSettings() {
        try {
          settings.value = await loadSettingsFromDb();
          visibleMessageCount.value = Number(settings.value.lazyBatchSize) || 60;
        } catch (_) {}
      }

      async function loadDbGlobalSettings() {
        try {
          globalSettings.value = await loadGlobalSettingsFromDb();
          settings.value = { ...settings.value, imageMaxSizeMb: globalSettings.value.imageMaxSizeMb || settings.value.imageMaxSizeMb };
          if (modelName.value !== "default" && !allowedModels.value.some((item) => item.name === modelName.value)) {
            modelName.value = "default";
          }
        } catch (_) {}
      }

      async function migrateLegacyStorageIfNeeded() {
        if (localStorage.getItem(MIGRATION_DISMISSED_KEY) === "true") return;
        try {
          const { data } = await axios.get(`${CHAT_API}/migration/legacy/status`);
          const total = Number(data?.total || 0);
          if (!total) return;
          const ok = window.confirm(`检测到 ${total} 条旧版 AI 聊天数据。是否迁移到新版按用户拆分的存储？迁移完成后会删除旧版对象，避免脏数据残留。`);
          if (!ok) {
            localStorage.setItem(MIGRATION_DISMISSED_KEY, "true");
            return;
          }
          const migrated = await axios.post(`${CHAT_API}/migration/legacy`);
          localStorage.setItem(MIGRATION_DISMISSED_KEY, "true");
          const warningCount = Array.isArray(migrated.data?.deleteWarnings) ? migrated.data.deleteWarnings.length : 0;
          error.value = `旧版数据迁移完成：会话 ${migrated.data?.sessions || 0}，日志 ${migrated.data?.callLogs || 0}，图片缓存 ${migrated.data?.imageCaches || 0}${warningCount ? `；有 ${warningCount} 项旧对象因 Halo 索引缺失未能删除` : ""}`;
          await loadDbSessions();
          scheduleScrollBottom();
        } catch (_) {}
      }

      function queuePersistDb() {
        if (!current.value) return;
        window.clearTimeout(persistTimer);
        const snapshot = JSON.parse(JSON.stringify(current.value));
        persistTimer = window.setTimeout(() => saveSessionToDb(snapshot), 260);
      }

      async function saveSessionToDb(session) {
        if (!session?.id) return;
        try {
          await axios.put(`${CHAT_API}/sessions/${encodeURIComponent(session.id)}/snapshot`, session);
          dbReady.value = true;
        } catch (_) {
          dbReady.value = false;
        }
      }

      function scrollBottom() {
        nextTick(() => {
          window.requestAnimationFrame(() => {
            if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight;
          });
        });
      }

      function scheduleScrollBottom() {
        scrollBottom();
        window.setTimeout(scrollBottom, 80);
        window.setTimeout(scrollBottom, 260);
      }

      function scheduleSoftScrollBottom() {
        const el = chatEl.value;
        if (!el || el.scrollHeight - el.scrollTop - el.clientHeight < 160) {
          scheduleScrollBottom();
        }
      }

      function loadOlderMessages() {
        if (loadingOlderMessages) return;
        const el = chatEl.value;
        const beforeHeight = el?.scrollHeight || 0;
        const beforeTop = el?.scrollTop || 0;
        const total = current.value?.messages?.length || 0;
        loadingOlderMessages = true;
        visibleMessageCount.value = Math.min(total, visibleMessageCount.value + (Number(settings.value.olderBatchSize) || 40));
        nextTick(() => {
          try {
            if (!el) return;
            const heightDelta = el.scrollHeight - beforeHeight;
            el.scrollTop = beforeTop + heightDelta;
          } finally {
            window.setTimeout(() => { loadingOlderMessages = false; }, 80);
          }
        });
      }

      function handleMessagesScroll(event) {
        const el = event.currentTarget;
        if (el.scrollTop < 120 && hiddenMessageCount.value > 0) {
          loadOlderMessages();
        }
      }

      async function refreshModels() {
        error.value = "";
        try {
          const { data } = await axios.get(`${API}/model-options?enabled=true`);
          const items = Array.isArray(data) ? data : data.items || [];
          models.value = items.map(normalizeOption).filter((item) => item.name);
          if (modelName.value !== "default" && !allowedModels.value.some((item) => item.name === modelName.value)) {
            modelName.value = "default";
          }
          if (!models.value.length) error.value = "AI Foundation 没有可用模型，请先配置并启用模型。";
          if (models.value.length && !allowedModels.value.length) error.value = "管理员没有允许当前用户使用任何模型，请检查插件设置。";
        } catch (_) {
          error.value = "无法读取 AI Foundation 模型列表，请确认 AI Foundation 已安装、启用，并授予当前用户权限。";
        }
      }

      function newSession() {
        const id = uid("chat");
        sessions.value.unshift({ id, title: EMPTY_TITLE, memory: "", createdAt: Date.now(), updatedAt: Date.now(), messages: [] });
        selectedId.value = id;
        visibleMessageCount.value = Number(settings.value.lazyBatchSize) || 60;
        persist();
      }

      function appendCallLog(entry) {
        const log = { time: Date.now(), sessionId: current.value?.id, sessionTitle: current.value?.title, ...entry };
        callLogs.value.unshift(log);
        saveCallLogs(callLogs.value);
        axios.post(`${CHAT_API}/call-logs`, log).catch(() => {});
      }

      function removeSession(id) {
        sessions.value = sessions.value.filter((item) => item.id !== id);
        axios.delete(`${CHAT_API}/sessions/${encodeURIComponent(id)}`).catch(() => {});
        if (!sessions.value.length) newSession();
        if (selectedId.value === id) selectedId.value = sessions.value[0].id;
        persist();
      }

      function startRename(session, event) {
        event.stopPropagation();
        renamingId.value = session.id;
        renameValue.value = session.title || "";
      }

      function finishRename(session) {
        const nextTitle = renameValue.value.trim();
        if (nextTitle) {
          session.title = nextTitle.slice(0, 60);
          session.updatedAt = Date.now();
          persist();
        }
        renamingId.value = "";
        renameValue.value = "";
      }

      function cancelRename(event) {
        event.stopPropagation();
        renamingId.value = "";
        renameValue.value = "";
      }

      function cleanCurrentSession() {
        current.value.messages = current.value.messages.filter((message) => {
          const text = String(message.content || "").trim();
          const hasUsefulText = text && !text.includes("message parts must not be empty") && !text.includes("模型没有返回文本内容") && !text.startsWith("调用失败：") && !text.startsWith('{"detail":');
          const hasReasoning = String(message.reasoning || "").trim().length > 0;
          const hasFiles = Array.isArray(message.files) && message.files.length > 0;
          const hasImages = Array.isArray(message.images) && message.images.length > 0;
          return hasUsefulText || hasReasoning || hasFiles || hasImages;
        });
        current.value.updatedAt = Date.now();
        persist();
      }

      function clearContext() {
        if (!current.value) return;
        current.value.contextClearedAt = Date.now();
        current.value.updatedAt = Date.now();
        persist();
      }

      async function addPickedFiles(fileList) {
        const allFiles = Array.from(fileList || []);
        const textFiles = allFiles.filter(isTextLikeFile).slice(0, 6);
        if (textFiles.length) {
          const snippets = await Promise.all(textFiles.map(async (file) => {
            const text = await file.text();
            return `\n\n--- FILE: ${file.name} ---\n${text.slice(0, 30000)}`;
          }));
          input.value = `${input.value || ""}${snippets.join("\n")}`.slice(0, 90000);
        }
        const picked = allFiles.filter((file) => file.type?.startsWith("image/")).slice(0, 8);
        if (!picked.length) return;
        const maxBytes = Math.max(1, Number(settings.value.imageMaxSizeMb) || 8) * 1024 * 1024;
        const oversized = picked.find((file) => file.size > maxBytes);
        if (oversized) {
          error.value = `Image ${oversized.name} exceeds ${Math.round(maxBytes / 1024 / 1024)} MB.`;
          return;
        }
        const next = await Promise.all(picked.map(uploadImageToAttachment));
        files.value = [...files.value, ...next].slice(0, 8);
      }

      async function summarizeConversation() {
        if (!current.value || contextCompressing.value) return;
        const messages = (current.value.messages || []).filter((message) => message.role === "user" || message.role === "assistant");
        if (!messages.length) {
          window.alert("当前对话还没有可摘要的内容。");
          return;
        }
        const model = defaultLanguageModel.value?.name || languageModels.value[0]?.name;
        if (!model) {
          error.value = "没有可用于摘要的语言模型。";
          return;
        }
        contextCompressing.value = true;
        error.value = "";
        try {
          const source = messages.slice(-40).map((message) => `${message.role === "user" ? "用户" : "AI"}：${message.content || ""}${message.reasoning ? `\n思考：${message.reasoning}` : ""}`).join("\n\n");
          const response = await postModelStream(model, ["chat/ui-message/stream", "test-chat/ui-message/stream"], {
            id: uid("conversation-summary"),
            trigger: "submit-message",
            messages: [{
              id: uid("conversation-summary-user"),
              role: "user",
              parts: [{ type: "text", id: uid("conversation-summary-text"), text: `请给下面这段对话生成结构化摘要，包含：目标、已确认事实、关键结论、待办/未解决问题。不要编造。\n\n${source}` }],
            }],
            maxOutputTokens: 1000,
          });
          if (!response.ok) throw new Error(await response.text());
          let summary = "";
          await readUiMessageStream(response, (kind, text) => {
            if (kind === "text") summary += text;
          });
          summary = summary.trim();
          if (!summary) throw new Error("摘要模型没有返回内容。");
          const message = { id: uid("summary"), role: "assistant", content: `## 对话摘要\n\n${summary}`, createdAt: Date.now(), tags: ["summary"] };
          current.value.messages.push(message);
          current.value.tags = Array.from(new Set([...(current.value.tags || []), "summary"])).slice(0, 12);
          current.value.updatedAt = Date.now();
          persist();
          scheduleScrollBottom();
        } catch (err) {
          error.value = err?.message || "对话摘要失败。";
        } finally {
          contextCompressing.value = false;
        }
      }

      function isTextLikeFile(file) {
        const name = String(file?.name || "").toLowerCase();
        return file?.type?.startsWith("text/")
          || /\.(txt|md|markdown|json|csv|tsv|log|xml|yaml|yml)$/i.test(name);
      }

      async function uploadImageToAttachment(file) {
        const form = new FormData();
        form.append("file", file, file.name);
        const { data } = await axios.post(`${CHAT_API}/attachments/upload`, form, {
          headers: { "Content-Type": "multipart/form-data" },
        });
        const spec = data?.spec || data?.attachment?.spec || data || {};
        const status = data?.status || data?.attachment?.status || {};
        const url = status.permalink || status.url || spec.permalink || spec.url || spec.externalLink || spec.displayName;
        if (!url || !String(url).startsWith("/")) {
          if (!/^https?:\/\//.test(String(url || ""))) {
            throw new Error("Attachment upload succeeded but returned no usable URL.");
          }
        }
        return {
          name: file.name,
          mediaType: file.type || spec.mediaType || "application/octet-stream",
          url,
          size: file.size,
          attachmentName: data?.metadata?.name || data?.attachment?.metadata?.name,
        };
      }

      async function chooseFiles(event) {
        try {
          await addPickedFiles(event.target.files);
        } catch (err) {
          error.value = err?.response?.data?.detail || err?.message || "Image upload failed.";
        }
        event.target.value = "";
      }

      function handleDragOver(event) {
        event.preventDefault();
        dragActive.value = true;
      }

      function handleDragLeave(event) {
        if (!event.currentTarget.contains(event.relatedTarget)) dragActive.value = false;
      }

      async function handleDrop(event) {
        event.preventDefault();
        dragActive.value = false;
        try {
          await addPickedFiles(event.dataTransfer?.files);
        } catch (err) {
          error.value = err?.response?.data?.detail || err?.message || "Image upload failed.";
        }
      }

      function resolveModel(kind, hasImageInput = false) {
        if (modelName.value !== "default") return modelName.value;
        const model = kind === "image"
          ? defaultImageModel.value
          : (hasImageInput ? (defaultMultimodalModel.value || defaultLanguageModel.value) : defaultLanguageModel.value);
        if (model?.name) return model.name;
        const pool = kind === "image" ? imageModels.value : (hasImageInput ? multimodalModels.value : languageModels.value);
        if (!pool.length) throw new Error(kind === "image" ? "没有可用的图像生成模型。" : "没有可用的语言/多模态模型。");
        return pool[0].name;
      }

      function toUiMessages(messages, contextClearedAt) {
        const valid = messages.filter((item) => {
          if (item.role !== "user" && item.role !== "assistant") return false;
          if (contextClearedAt && (item.createdAt || 0) < contextClearedAt) return false;
          const text = String(item.content || "").trim();
          const hasFiles = Array.isArray(item.files) && item.files.length > 0;
          const isFailureText = text.includes("message parts must not be empty")
            || text.includes("模型没有返回文本内容")
            || text.startsWith("调用失败：")
            || text.startsWith('{"detail":');
          return (text || hasFiles) && !isFailureText;
        });
        const latestUserIndex = valid.map((item) => item.role).lastIndexOf("user");
        const scoped = latestUserIndex >= 0 ? valid.slice(Math.max(0, latestUserIndex - 6)) : valid.slice(-8);
        const mapped = scoped.map((item) => {
          const content = typeof item.content === "string" ? item.content.trim() : "";
          const isFailureText = content.includes("message parts must not be empty")
            || content.includes("模型没有返回文本内容")
            || content.startsWith("调用失败：")
            || content.startsWith('{"detail":');
          const parts = [
            ...(content && !isFailureText ? [{ type: "text", id: uid("text"), text: item.content }] : []),
            ...((item.files || []).map((file) => {
              const data = file.data || (String(file.url || "").startsWith("data:") ? file.url : undefined);
              const url = data ? undefined : file.url;
              return {
                type: "file",
                fileId: uid("file"),
                mediaType: file.mediaType,
                data,
                url,
                title: file.name,
              };
            })),
          ];
          return { id: item.id, role: item.role, parts };
        }).filter((item) => item.parts.length > 0);
        const memory = [settings.value.memoryText, current.value?.memory].filter(Boolean).join("\n\n").trim();
        if (memory) {
          mapped.unshift({
            id: uid("memory"),
            role: "user",
            parts: [{
              type: "text",
              id: uid("memory-text"),
              text: `Long-term memory and knowledge references. Use these facts when relevant, but do not mention them unless useful:\n\n${memory}`,
            }],
          });
        }
        return mapped;
      }

      async function generateSessionTitle(prompt) {
        if (!current.value || current.value.title !== EMPTY_TITLE) return;
        const model = defaultLanguageModel.value?.name || languageModels.value[0]?.name;
        if (!model) return;
        try {
          const response = await postModelStream(model, ["chat/ui-message/stream", "test-chat/ui-message/stream"], {
              id: uid("title"),
              trigger: "submit-message",
              messages: [{
                id: uid("title-user"),
                role: "user",
                parts: [{ type: "text", id: uid("title-text"), text: `请为下面这段对话生成一个不超过 12 个汉字的标题，只输出标题：\n${prompt}` }],
              }],
              maxOutputTokens: 64,
            });
          if (!response.ok) return;
          let title = "";
          await readUiMessageStream(response, (kind, text) => {
            if (kind === "text") title += text;
          });
          title = title.replace(/["“”'。.\n\r]/g, "").trim();
          if (title) {
            current.value.title = title.slice(0, 24);
            current.value.updatedAt = Date.now();
            persist();
          }
        } catch (_) {}
      }

      function applyJobUpdate(job, assistant, persistMode) {
        assistant.content = job.content || "";
        assistant.reasoning = job.reasoning || "";
        assistant.reasoningOpen = !!job.reasoningOpen;
        if (Array.isArray(job.images)) assistant.images = job.images;
        assistant.promptTokens = job.promptTokens || assistant.promptTokens || 0;
        assistant.completionTokens = job.completionTokens || 0;
        assistant.totalTokens = job.totalTokens || ((assistant.promptTokens || 0) + (assistant.completionTokens || 0));
        assistant.streaming = job.status === "running";
        current.value.updatedAt = Date.now();
        if (job.status === "success") {
          applyAutoTags(assistant);
          applySessionTags(current.value);
        }
        if (persistMode === "stream") {
          persistStreaming();
        } else {
          flushStreamingPersist();
        }
        scheduleScrollBottom();
      }

      async function pollChatJobLegacy(jobId, assistant) {
        for (;;) {
          if (abortController.value?.signal?.aborted) {
            throw new DOMException("Aborted", "AbortError");
          }
          const { data: job } = await axios.get(`${CHAT_API}/jobs/${encodeURIComponent(jobId)}`);
          applyJobUpdate(job, assistant, job.status === "running" ? "stream" : "final");
          if (job.status === "success") {
            return job;
          }
          if (job.status === "error") {
            throw new Error(job.error || "AI generation failed.");
          }
          if (job.status === "cancelled") {
            throw new DOMException("Aborted", "AbortError");
          }
          await new Promise((resolve) => window.setTimeout(resolve, 800));
        }
      }

      async function pollChatJob(jobId, assistant) {
        if (!window.EventSource) return pollChatJobLegacy(jobId, assistant);
        return new Promise((resolve, reject) => {
          let settled = false;
          let received = false;
          const source = new EventSource(`${CHAT_API}/jobs/${encodeURIComponent(jobId)}/events`);
          const cleanup = () => {
            source.close();
            abortController.value?.signal?.removeEventListener("abort", abortHandler);
          };
          const finish = (callback, value) => {
            if (settled) return;
            settled = true;
            cleanup();
            callback(value);
          };
          const abortHandler = () => finish(reject, new DOMException("Aborted", "AbortError"));
          const handleJob = (event) => {
            received = true;
            let job;
            try {
              job = JSON.parse(event.data || "{}");
            } catch (err) {
              finish(reject, err);
              return;
            }
            applyJobUpdate(job, assistant, job.status === "running" ? "stream" : "final");
            if (job.status === "success") {
              finish(resolve, job);
            } else if (job.status === "error") {
              finish(reject, new Error(job.error || "AI generation failed."));
            } else if (job.status === "cancelled") {
              finish(reject, new DOMException("Aborted", "AbortError"));
            }
          };
          source.addEventListener("job", handleJob);
          source.onerror = () => {
            if (settled) return;
            cleanup();
            if (received) {
              pollChatJobLegacy(jobId, assistant).then(resolve, reject);
            } else {
              pollChatJobLegacy(jobId, assistant).then(resolve, reject);
            }
          };
          abortController.value?.signal?.addEventListener("abort", abortHandler, { once: true });
        });
      }

      async function sendChat() {
        const hasImageInput = (current.value?.messages || []).some((message) => Array.isArray(message.files) && message.files.length > 0);
        const model = resolveModel("chat", hasImageInput);
        const startedAt = Date.now();
        cleanCurrentSession();
        const requestMessages = toUiMessages(current.value.messages, current.value.contextClearedAt || 0);
        if (!requestMessages.length) throw new Error("没有可发送的有效消息，请重新输入内容后再试。");
        const assistant = { id: uid("assistant"), role: "assistant", content: "", reasoning: "", reasoningOpen: true, createdAt: Date.now() };
        current.value.messages.push(assistant);
        abortController.value = new AbortController();
        const promptTokens = requestMessages.reduce((sum, item) => sum + item.parts.reduce((partSum, part) => partSum + estimateTokens(part.text || part.title || ""), 0), 0);
        assistant.promptTokens = promptTokens;
        assistant.streaming = true;
        persist();
        const { data: job } = await axios.post(`${CHAT_API}/jobs/chat`, {
          id: uid("job"),
          model,
          requestMessages,
          assistant,
          promptTokens,
          session: current.value,
        });
        activeJobId.value = job.id;
        await pollChatJob(job.id, assistant);
        assistant.streaming = false;
        assistant.completionTokens = estimateTokens(`${assistant.reasoning}\n${assistant.content}`);
        assistant.totalTokens = (assistant.promptTokens || 0) + (assistant.completionTokens || 0);
        appendCallLog({ type: "chat", model, status: "success", durationMs: Date.now() - startedAt, promptTokens: assistant.promptTokens, completionTokens: assistant.completionTokens, totalTokens: assistant.totalTokens });
      }

      async function sendImage(prompt, attachedFiles) {
        const model = resolveModel("image");
        const startedAt = Date.now();
        const payload = {
          prompt,
          inputImages: attachedFiles.map((file) => ({
            data: file.data || (String(file.url || "").startsWith("data:") ? file.url : undefined),
            url: (file.data || String(file.url || "").startsWith("data:")) ? undefined : file.url,
            mediaType: file.mediaType,
            filename: file.name,
          })),
          responseFormat: "URL",
        };
        const assistant = {
          id: uid("assistant"),
          role: "assistant",
          content: "正在生成图像...",
          images: [],
          streaming: true,
          createdAt: Date.now(),
          promptTokens: estimateTokens(prompt),
          completionTokens: 0,
          totalTokens: estimateTokens(prompt),
        };
        current.value.messages.push(assistant);
        abortController.value = new AbortController();
        persist();
        const { data: job } = await axios.post(`${CHAT_API}/jobs/image`, {
          id: uid("job"),
          model,
          prompt,
          payload,
          assistant,
          promptTokens: assistant.promptTokens,
          session: current.value,
        });
        activeJobId.value = job.id;
        await pollChatJob(job.id, assistant);
        assistant.streaming = false;
        current.value.updatedAt = Date.now();
        persist();
        appendCallLog({ type: "image", model, status: assistant.images?.length ? "success" : "empty", durationMs: Date.now() - startedAt, promptTokens: assistant.promptTokens, completionTokens: 0, totalTokens: assistant.totalTokens });
        return;
        const streamed = await tryStreamImageGeneration(model, payload, assistant);
        if (streamed) {
          assistant.streaming = false;
          assistant.content = assistant.images.length ? "已生成图像：" : "图像生成流结束，但没有返回图像。";
          appendCallLog({ type: "image", model, status: assistant.images.length ? "success" : "empty", durationMs: Date.now() - startedAt, promptTokens: assistant.promptTokens, completionTokens: 0, totalTokens: assistant.totalTokens });
          return;
        }
        const { data } = await postModelJson(model, ["test-image-generation", "image-generation"], payload, { signal: abortController.value?.signal });
        const images = data.images || data.files || [];
        assistant.streaming = false;
        assistant.content = images.length ? "已生成图像：" : "图像模型完成了请求，但没有返回图像。";
        assistant.images = images.map((item) => item.url || item.data || item.base64 || item.b64Json).filter(Boolean);
        current.value.updatedAt = Date.now();
        persist();
        appendCallLog({ type: "image", model, status: images.length ? "success" : "empty", durationMs: Date.now() - startedAt, promptTokens: estimateTokens(prompt), completionTokens: 0, totalTokens: estimateTokens(prompt) });
      }

      async function tryStreamImageGeneration(model, payload, assistant) {
        const response = await postModelStream(model, ["test-image-generation"], payload, {
          signal: abortController.value?.signal,
        });
        if (response.status === 404 || response.status === 405) return false;
        if (!response.ok) throw new Error(await response.text() || `AI Foundation 返回 ${response.status}`);
        await readGenericSseStream(response, (event) => {
          const text = event.data.join("\n");
          const marker = text.trim();
          if (!marker || marker === "[DONE]" || marker === "DONE") return;
          try {
            const parsed = JSON.parse(text);
            const nextImages = collectGeneratedImages(parsed);
            if (nextImages.length) {
              assistant.images = Array.from(new Set([...(assistant.images || []), ...nextImages]));
              assistant.content = "正在接收图像...";
            } else if (parsed.progress || parsed.status || parsed.message) {
              assistant.content = parsed.message || parsed.status || `正在生成图像... ${parsed.progress}%`;
            }
          } catch (_) {
            assistant.content = marker;
          }
          current.value.updatedAt = Date.now();
          persist();
          scheduleScrollBottom();
        });
        return true;
      }

      async function send() {
        const prompt = input.value.trim();
        if (!prompt || loading.value) return;
        error.value = "";
        loading.value = true;
        const attachedFiles = files.value.slice();
        input.value = "";
        files.value = [];
        current.value.messages.push({ id: uid("user"), role: "user", content: prompt, files: attachedFiles, createdAt: Date.now() });
        const shouldGenerateTitle = current.value.title === EMPTY_TITLE;
        if (shouldGenerateTitle) current.value.title = prompt.slice(0, 24);
        current.value.updatedAt = Date.now();
        persist();
        if (shouldGenerateTitle) generateSessionTitle(prompt);
        scheduleScrollBottom();
        try {
          const wantsImage = mode.value === "image" || /^\/image\b/i.test(prompt);
          if (wantsImage) {
            await sendImage(prompt.replace(/^\/image\b/i, "").trim() || prompt, attachedFiles);
          } else {
            await sendChat();
          }
        } catch (err) {
          if (err?.name === "AbortError") {
            current.value.messages.push({ id: uid("stopped"), role: "assistant", content: "已停止生成。", createdAt: Date.now() });
            return;
          }
          const message = err?.response?.data?.detail || err?.response?.data?.message || err.message || "AI 调用失败";
          error.value = message;
          current.value.messages.push({ id: uid("error"), role: "assistant", content: `调用失败：${message}`, createdAt: Date.now() });
        } finally {
          abortController.value = null;
          activeJobId.value = "";
          current.value.updatedAt = Date.now();
          persist();
          loading.value = false;
          scheduleScrollBottom();
        }
      }

      function stopGeneration() {
        if (activeJobId.value) {
          axios.post(`${CHAT_API}/jobs/${encodeURIComponent(activeJobId.value)}/cancel`).catch(() => {});
        }
        abortController.value?.abort();
      }

      function messageMarkdown(message) {
        return [message.reasoning ? `## 思考过程\n\n${message.reasoning}` : "", message.content || ""].filter(Boolean).join("\n\n");
      }

      function copyMarkdown(message) {
        navigator.clipboard?.writeText(messageMarkdown(message));
      }

      function copyPlainText(message) {
        const stripped = String(message.content || "")
          .replace(/```[A-Za-z0-9_+-]*\n([\s\S]*?)```/g, "$1")
          .replace(/[#>*_`[\]()-]/g, "")
          .replace(/\n{3,}/g, "\n\n")
          .trim();
        const text = [message.reasoning ? `思考过程:\n${message.reasoning}` : "", stripped].filter(Boolean).join("\n\n");
        navigator.clipboard?.writeText(text);
      }

      function exportMessage(message) {
        const name = `${current.value?.title || "ai-message"}-${message.id || Date.now()}.md`.replace(/[\\/:*?"<>|]/g, "-");
        const text = [message.reasoning ? `## 思考过程\n\n${message.reasoning}` : "", `## 回复\n\n${message.content || ""}`].filter(Boolean).join("\n\n");
        downloadText(name, text);
      }

      function toggleFavorite(message) {
        const target = message._sourceMessage || message;
        const ownerSession = message._sessionId ? sessions.value.find((session) => session.id === message._sessionId) : current.value;
        target.favorite = !target.favorite;
        target.updatedAt = Date.now();
        if (ownerSession) ownerSession.updatedAt = Date.now();
        persist();
      }

      function applyAutoTags(message) {
        const text = `${message.content || ""}\n${message.reasoning || ""}`.toLowerCase();
        const tags = [];
        const rules = [
          ["image", /image|图片|图像|照片|生成图/],
          ["code", /```|function|class |import |python|java|javascript|typescript|代码/],
          ["error", /error|exception|失败|报错|错误/],
          ["config", /config|配置|setting|设置|yaml|json/],
          ["security", /xss|csrf|权限|sanitizer|安全|漏洞/],
          ["summary", /摘要|压缩|context|上下文/],
        ];
        rules.forEach(([tag, pattern]) => {
          if (pattern.test(text)) tags.push(tag);
        });
        current.value.tags = Array.from(new Set([...(current.value?.tags || []), ...tags])).slice(0, 12);
      }

      function applySessionTags(session) {
        if (!session) return;
        const text = (session.messages || []).map((message) => `${message.content || ""}\n${message.reasoning || ""}`).join("\n").toLowerCase();
        const tags = [];
        [
          ["image", /image|图片|图像|照片|生成图/],
          ["code", /```|function|class |import |python|java|javascript|typescript|代码/],
          ["error", /error|exception|失败|报错|错误/],
          ["config", /config|配置|setting|设置|yaml|json/],
          ["security", /xss|csrf|权限|sanitizer|安全|漏洞/],
          ["summary", /摘要|压缩|context|上下文/],
        ].forEach(([tag, pattern]) => {
          if (pattern.test(text)) tags.push(tag);
        });
        session.tags = Array.from(new Set([...(session.tags || []), ...tags])).slice(0, 12);
      }

      async function retryMessage(message) {
        const messages = current.value.messages;
        const index = messages.indexOf(message);
        const user = messages.slice(0, index).reverse().find((item) => item.role === "user");
        if (!user || loading.value) return;
        current.value.messages = messages.slice(0, index).filter((item) => item !== message);
        input.value = user.content || "";
        await send();
      }

      async function regenerateFromUserMessage(message) {
        if (!current.value || loading.value) return;
        const messages = current.value.messages || [];
        const index = messages.indexOf(message);
        if (index < 0) return;
        current.value.messages = messages.slice(0, index + 1);
        current.value.updatedAt = Date.now();
        persist();
        scheduleScrollBottom();
        loading.value = true;
        error.value = "";
        const prompt = message.content || "";
        const attachedFiles = Array.isArray(message.files) ? message.files : [];
        try {
          const wantsImage = mode.value === "image" || /^\/image\b/i.test(prompt);
          if (wantsImage) {
            await sendImage(prompt.replace(/^\/image\b/i, "").trim() || prompt, attachedFiles);
          } else {
            await sendChat();
          }
        } catch (err) {
          if (err?.name === "AbortError") {
            current.value.messages.push({ id: uid("stopped"), role: "assistant", content: "已停止生成。", createdAt: Date.now() });
            return;
          }
          const messageText = err?.response?.data?.detail || err?.response?.data?.message || err.message || "AI 调用失败";
          error.value = messageText;
          current.value.messages.push({ id: uid("error"), role: "assistant", content: `调用失败：${messageText}`, createdAt: Date.now() });
        } finally {
          abortController.value = null;
          activeJobId.value = "";
          current.value.updatedAt = Date.now();
          persist();
          loading.value = false;
          scheduleScrollBottom();
        }
      }

      function startEditMessage(message) {
        editingMessageId.value = message.id;
        editingMessageValue.value = message.content || "";
      }

      async function saveEditMessage(message, regenerate) {
        const next = editingMessageValue.value.trim();
        if (!next) return;
        message.content = next;
        message.updatedAt = Date.now();
        editingMessageId.value = "";
        editingMessageValue.value = "";
        current.value.updatedAt = Date.now();
        persist();
        if (regenerate) {
          await regenerateFromUserMessage(message);
        }
      }

      let resizeObserver;
      let loadingOlderMessages = false;
      const handleReturnToView = () => {
        if (!document.hidden) scheduleScrollBottom();
      };

      onMounted(() => {
        loadDbSettings();
        loadDbGlobalSettings().then(refreshModels);
        loadDbSessions().then(() => migrateLegacyStorageIfNeeded()).then(scheduleScrollBottom);
        scheduleScrollBottom();
        window.addEventListener("focus", scheduleScrollBottom);
        window.addEventListener("pageshow", scheduleScrollBottom);
        document.addEventListener("visibilitychange", handleReturnToView);
        if (window.ResizeObserver) {
          resizeObserver = new ResizeObserver(scheduleSoftScrollBottom);
          nextTick(() => {
            if (chatEl.value) resizeObserver.observe(chatEl.value);
          });
        }
      });
      if (onActivated) onActivated(scheduleScrollBottom);
      if (onBeforeUnmount) onBeforeUnmount(() => {
        window.clearTimeout(persistTimer);
        window.removeEventListener("focus", scheduleScrollBottom);
        window.removeEventListener("pageshow", scheduleScrollBottom);
        document.removeEventListener("visibilitychange", handleReturnToView);
        resizeObserver?.disconnect();
      });
      watch(selectedId, () => {
        settings.value = loadSettings();
        loadDbSettings();
        visibleMessageCount.value = Number(settings.value.lazyBatchSize) || 60;
        persist();
        scheduleScrollBottom();
      });
      watch(() => current.value?.messages?.length, () => {
        const base = Number(settings.value.lazyBatchSize) || 60;
        visibleMessageCount.value = Math.max(base, Math.min(current.value?.messages?.length || 0, visibleMessageCount.value + 2));
        scheduleScrollBottom();
      });
      watch([contextUsedPercent, () => current.value?.messages?.length], () => {
        const threshold = Math.max(50, Math.min(98, Number(settings.value.autoCompressPercent) || 85));
        const now = Date.now();
        if (contextUsedPercent.value >= threshold && (current.value?.messages?.length || 0) > 12 && now - lastAutoCompressionAt > 90000) {
          lastAutoCompressionAt = now;
          autoCompressContext();
        }
      });

      const Button = components.VButton || "button";
      const Empty = components.VEmpty || "div";
      const Loading = components.VLoading || "span";

      function renderMessage(message) {
        return h("article", { key: message.id || `${message.role}-${message.createdAt}`, class: ["halo-ai-message", message.role] }, [
          h("div", { class: "avatar" }, message.role === "user" ? "你" : "AI"),
          h("div", { class: "bubble" }, [
            message.streaming ? h("div", { class: "streaming-pill" }, "生成中") : null,
            message.role === "user" && editingMessageId.value === message.id
              ? h("div", { class: "message-edit" }, [
                h("textarea", {
                  value: editingMessageValue.value,
                  onInput: (event) => { editingMessageValue.value = event.target.value; },
                  onKeydown: (event) => {
                    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) saveEditMessage(message);
                    if (event.key === "Escape") editingMessageId.value = "";
                  },
                }),
                h("div", { class: "message-actions" }, [
                  h("button", { onClick: () => saveEditMessage(message) }, "保存"),
                  h("button", { disabled: loading.value, onClick: () => saveEditMessage(message, true) }, "保存并重新生成"),
                  h("button", { onClick: () => { editingMessageId.value = ""; editingMessageValue.value = ""; } }, "取消"),
                ]),
              ])
              : null,
            message.reasoning ? h("details", {
              class: "reasoning",
              open: !!message.reasoningOpen,
              onToggle: (event) => { message.reasoningOpen = event.target.open; persist(); },
            }, [
              h("summary", "思考过程"),
              h(MarkdownContent, { content: message.reasoning, onRendered: scheduleSoftScrollBottom }),
            ]) : null,
            editingMessageId.value === message.id
              ? null
              : h(MarkdownContent, { content: message.content, onRendered: scheduleSoftScrollBottom }),
            message.files?.length ? h("div", { class: "attachments" }, message.files.map((file) => h("span", file.name))) : null,
            message.images?.length ? h("div", { class: "images" }, message.images.map((src) => h("img", { src }))) : null,
            message.role === "assistant" ? h("div", { class: "message-actions" }, [
              h("button", { onClick: () => copyMarkdown(message) }, "复制 MD"),
              h("button", { onClick: () => copyPlainText(message) }, "复制纯文本"),
              h("button", { onClick: () => exportMessage(message) }, "📄 Export"),
              h("button", { disabled: loading.value, onClick: () => retryMessage(message) }, "🔄 Retry"),
              h("button", { onClick: () => toggleFavorite(message) }, (message._sourceMessage || message).favorite ? "Unfavorite" : "Favorite"),
            ]) : null,
            message.role === "user" && editingMessageId.value !== message.id ? h("div", { class: "message-actions" }, [
              h("button", { onClick: () => startEditMessage(message) }, "编辑"),
            ]) : null,
            message.totalTokens ? h("div", { class: "token-stats" }, `tokens ≈ ${message.totalTokens}（输入 ${message.promptTokens || 0} / 输出 ${message.completionTokens || 0}）`) : null,
          ]),
        ]);
      }

      return () => h("div", {
        class: ["ai-chat-shell", sidebarCollapsed.value && "is-sidebar-collapsed", dragActive.value && "is-dragging"],
        onDragover: handleDragOver,
        onDragleave: handleDragLeave,
        onDrop: handleDrop,
      }, [
        h("style", AI_CHAT_CSS),
        dragActive.value ? h("div", { class: "drop-mask" }, "松开鼠标上传图片") : null,
        h("button", {
          class: "sidebar-toggle",
          type: "button",
          title: sidebarCollapsed.value ? "显示侧边栏" : "隐藏侧边栏",
          onClick: toggleSidebar,
        }, sidebarCollapsed.value ? "›" : "‹"),
        h("aside", { class: "ai-chat-sidebar" }, [
          h("div", { class: "brand" }, [h("strong", "Halo AI"), h("span", "Console Assistant")]),
          h("div", { class: "sidebar-actions" }, [
            h(Button, { type: "primary", size: "sm", onClick: newSession }, () => "新建聊天"),
            h(Button, { size: "sm", onClick: clearContext }, () => "清除上下文"),
            h(Button, { size: "sm", onClick: cleanCurrentSession }, () => "清理错误"),
            h(Button, { size: "sm", onClick: () => { showFavoritesOnly.value = !showFavoritesOnly.value; } }, () => showFavoritesOnly.value ? "All" : "Favorites"),
          ]),
          h("input", {
            class: "session-search",
            value: sessionQuery.value,
            placeholder: "搜索对话、消息、标签、收藏",
            onInput: (event) => { sessionQuery.value = event.target.value; },
          }),
          h("small", { class: "search-hint" }, "全局搜索当前用户的对话标题、最近消息、会话标签和收藏标记"),
          h("div", { class: "ai-chat-history" }, filteredSessions.value.map((session) =>
            h("div", {
              class: ["halo-ai-session", session.id === selectedId.value && "is-active"],
              onClick: () => { selectedId.value = session.id; },
            }, [
              renamingId.value === session.id
                ? h("input", {
                  class: "session-rename",
                  value: renameValue.value,
                  autofocus: true,
                  onClick: (event) => event.stopPropagation(),
                  onInput: (event) => { renameValue.value = event.target.value; },
                  onBlur: () => finishRename(session),
                  onKeydown: (event) => {
                    if (event.key === "Enter") finishRename(session);
                    if (event.key === "Escape") cancelRename(event);
                  },
                })
                : h("span", session.title || "未命名聊天"),
              h("small", new Date(session.updatedAt).toLocaleString()),
              h("div", { class: "session-actions" }, [
                h("b", { onClick: (event) => startRename(session, event) }, "重命名"),
                h("b", { class: "danger", onClick: (event) => { event.stopPropagation(); removeSession(session.id); } }, "删除"),
              ]),
            ])
          )),
        ]),
        h("main", { class: "ai-chat-main" }, [
          h("header", { class: "ai-chat-toolbar" }, [
            h("div", { class: "title" }, [h("strong", "AI 聊天"), h("span", "由 AI Foundation 提供模型能力")]),
            h("select", { value: mode.value, onChange: (event) => { mode.value = event.target.value; } }, [
              h("option", { value: "chat" }, "聊天"),
              h("option", { value: "image" }, "图像"),
            ]),
            h("select", { value: modelName.value, onChange: (event) => { modelName.value = event.target.value; } },
              selectableModels.value.map((item) => h("option", { value: item.value }, item.label))
            ),
            h("button", {
              class: "context-meter",
              type: "button",
              style: { "--context-used": `${contextUsedPercent.value}%` },
              title: `当前上下文约 ${contextUsed.value} tokens，剩余约 ${contextRemaining.value} / ${contextLimit.value}。点击压缩上下文。`,
              disabled: contextCompressing.value,
              onClick: compressContext,
            }, [
              h("strong", compactTokenCount(contextRemaining.value)),
              h("span", "剩余"),
            ]),
            h(Button, { size: "sm", disabled: contextCompressing.value, onClick: summarizeConversation }, () => "摘要对话"),
            h(Button, { size: "sm", onClick: refreshModels }, () => "刷新模型"),
          ]),
          conversationTags.value.length ? h("div", { class: "conversation-tags" }, conversationTags.value.map((tag) =>
            h("button", { type: "button", onClick: () => { sessionQuery.value = tag; } }, `#${tag}`)
          )) : null,
          error.value && h("div", { class: "ai-chat-error" }, error.value),
          h("section", { ref: chatEl, class: "halo-ai-messages", onScroll: handleMessagesScroll }, (showFavoritesOnly.value ? favoriteMessages.value.length : current.value.messages.length)
            ? [
              !showFavoritesOnly.value && hiddenMessageCount.value > 0
                ? h("button", { class: "load-older", onClick: loadOlderMessages }, `加载更早的 ${Math.min(Number(settings.value.olderBatchSize) || 40, hiddenMessageCount.value)} 条消息`)
                : null,
              ...visibleMessages.value.map(renderMessage),
            ]
            : h(Empty, { title: "还没有聊天历史", description: "从下方输入框开始一次对话。" })
          ),
          h("footer", { class: "ai-chat-composer" }, [
            files.value.length ? h("div", { class: "attachments pending" }, files.value.map((file) => h("span", file.name))) : null,
            h("textarea", {
              value: input.value,
              placeholder: "输入消息，支持 Markdown，LaTeX 将以源码显示。使用 /image 开头可调用图像生成模型。",
              onInput: (event) => { input.value = event.target.value; },
              onKeydown: (event) => {
                if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) send();
              },
            }),
            h("div", { class: "composer-actions" }, [
              h("label", { class: "file-button" }, ["添加文件", h("input", { type: "file", accept: "image/*,.txt,.md,.markdown,.json,.csv,.tsv,.log,.xml,.yaml,.yml,text/*", multiple: true, onChange: chooseFiles })]),
              h("span", { class: "hint" }, "Ctrl / Cmd + Enter 发送"),
              loading.value
                ? h(Button, { type: "secondary", onClick: stopGeneration }, () => "停止生成")
                : h(Button, { type: "primary", disabled: !input.value.trim(), onClick: send }, () => "发送"),
            ]),
          ]),
        ]),
      ]);
    },
  };

  const SettingsView = {
    name: "HaloAiConsoleSettings",
    setup() {
      const settings = ref(loadSettings());
      const logs = ref(loadCallLogs());
      const logScope = ref("own");
      const canViewAllLogs = ref(false);
      const logError = ref("");
      const Button = components.VButton || "button";
      const refreshLogs = async () => {
        try {
          const endpoint = logScope.value === "all" ? "audit-logs/all" : "audit-logs";
          const { data } = await axios.get(`${CHAT_API}/${endpoint}`);
          if (Array.isArray(data)) {
            logs.value = data;
            saveCallLogs(data);
          }
          logError.value = "";
        } catch (err) {
          if (logScope.value === "all") {
            canViewAllLogs.value = false;
            logScope.value = "own";
            logError.value = err?.response?.status === 403 ? "当前用户没有查看全部调用记录的权限。" : "无法读取全部调用记录。";
            refreshLogs();
          }
        }
      };
      const probeAllLogsPermission = async () => {
        try {
          await axios.get(`${CHAT_API}/audit-logs/all`);
          canViewAllLogs.value = true;
        } catch (_) {
          canViewAllLogs.value = false;
          if (logScope.value === "all") logScope.value = "own";
        }
      };
      const load = async () => {
        try {
          settings.value = await loadSettingsFromDb();
        } catch (_) {}
      };
      const save = async () => {
        settings.value.lazyBatchSize = Math.max(20, Math.min(200, Number(settings.value.lazyBatchSize) || 60));
        settings.value.olderBatchSize = Math.max(10, Math.min(100, Number(settings.value.olderBatchSize) || 40));
        settings.value.autoCompressPercent = Math.max(50, Math.min(98, Number(settings.value.autoCompressPercent) || 85));
        settings.value.memoryText = String(settings.value.memoryText || "").slice(0, 20000);
        settings.value = await saveSettingsToDb(settings.value);
      };
      const clearLogs = () => {
        logs.value = [];
        saveCallLogs([]);
      };
      onMounted(() => { load(); probeAllLogsPermission(); refreshLogs(); });
      return () => h("div", { class: "ai-chat-settings" }, [
        h("style", AI_CHAT_CSS),
        h("section", { class: "settings-panel" }, [
          h("h1", "AI 聊天设置"),
          h("label", [h("span", "初始渲染消息数"), h("input", {
            type: "number",
            min: 20,
            max: 200,
            value: settings.value.lazyBatchSize,
            onInput: (event) => { settings.value.lazyBatchSize = Number(event.target.value); },
          })]),
          h("label", [h("span", "每次加载更早消息数"), h("input", {
            type: "number",
            min: 10,
            max: 100,
            value: settings.value.olderBatchSize,
            onInput: (event) => { settings.value.olderBatchSize = Number(event.target.value); },
          })]),
          h("p", "聊天历史、消息编辑和调用记录会同步到 Halo 数据库；浏览器本地缓存只作为离线兜底。"),
          h("p", { class: "readonly-note" }, `图片大小上限由管理员在插件详情的“基本设置”中统一配置。当前前端提示值约 ${settings.value.imageMaxSizeMb || 8} MB，此处不可修改。`),
          h("label", [h("span", "自动压缩阈值（%）"), h("input", {
            type: "number",
            min: 50,
            max: 98,
            value: settings.value.autoCompressPercent,
            onInput: (event) => { settings.value.autoCompressPercent = Number(event.target.value); },
          })]),
          h("label", [h("span", "个人长期记忆 / 知识引用提示"), h("textarea", {
            value: settings.value.memoryText || "",
            onInput: (event) => { settings.value.memoryText = event.target.value; },
          })]),
          h(Button, { type: "primary", onClick: save }, () => "保存设置"),
        ]),
        h("section", { class: "settings-panel" }, [
          h("div", { class: "settings-title-row" }, [
            h("h2", "AI 调用审计"),
            h("div", { class: "settings-actions" }, [
              h(Button, {
                size: "sm",
                type: logScope.value === "own" ? "primary" : "secondary",
                onClick: () => { logScope.value = "own"; refreshLogs(); },
              }, () => "我的审计"),
              canViewAllLogs.value ? h(Button, {
                size: "sm",
                type: logScope.value === "all" ? "primary" : "secondary",
                onClick: () => { logScope.value = "all"; refreshLogs(); },
              }, () => "全部审计") : null,
              h(Button, { size: "sm", onClick: refreshLogs }, () => "刷新"),
              h(Button, { size: "sm", onClick: clearLogs }, () => "清空本地"),
            ]),
          ]),
          logError.value ? h("p", { class: "settings-error" }, logError.value) : null,
          logs.value.length
            ? h("div", { class: "call-log-list" }, logs.value.map((log) => h("div", { class: "call-log-item" }, [
              h("strong", `${log.owner || "当前用户"} · ${log.operation || log.type || "chat"}`),
              h("span", `${log.model || "-"} · tokens≈${log.totalTokens || 0}`),
              h("span", `${new Date(log.time).toLocaleString()} · ${log.durationMs || 0}ms`),
              h("span", `${log.ipAddress || "-"} · ${log.browser || "-"} / ${log.operatingSystem || "-"}`),
              h("small", `${log.status || "-"} · ${log.sessionTitle || log.sessionId || ""}`),
            ])))
            : h("p", "暂无审计记录。"),
        ]),
      ]);
    },
  };

  const AI_CHAT_CSS = `
.ai-chat-shell{position:relative;z-index:2;height:100dvh;max-height:100dvh;min-height:min(560px,100dvh);box-sizing:border-box;display:grid;grid-template-columns:292px minmax(0,1fr);background:linear-gradient(180deg,#f8fafc 0%,#eef2f7 100%);color:#101827;overflow:hidden;transition:grid-template-columns .22s ease}
.ai-chat-shell *{box-sizing:border-box}
.ai-chat-shell.is-sidebar-collapsed{grid-template-columns:0 minmax(0,1fr)}
.ai-chat-shell.is-sidebar-collapsed .ai-chat-sidebar{width:0;padding-left:0;padding-right:0;border-right-color:transparent;opacity:0;transform:translateX(-18px);pointer-events:none}
.sidebar-toggle{position:absolute;z-index:8;left:276px;top:50%;width:28px;height:54px;transform:translateY(-50%);border:1px solid #d7dee8;border-radius:0 999px 999px 0;background:#fff;color:#64748b;font-size:22px;line-height:1;cursor:pointer;box-shadow:0 10px 24px rgba(15,23,42,.13);transition:left .22s ease,background .16s ease,color .16s ease}
.sidebar-toggle:hover{background:#f8fafc;color:#2563eb}.ai-chat-shell.is-sidebar-collapsed .sidebar-toggle{left:0}
.ai-chat-sidebar{grid-column:1;width:292px;min-height:0;border-right:1px solid #e6eaf0;background:rgba(255,255,255,.86);backdrop-filter:blur(10px);padding:16px;display:flex;flex-direction:column;gap:14px;min-width:0;overflow:hidden;opacity:1;transform:translateX(0);transition:opacity .18s ease,transform .22s ease,padding .22s ease,width .22s ease,border-color .22s ease}
.brand{display:grid;gap:2px;padding:4px 2px 8px}.brand strong{font-size:18px}.brand span{font-size:12px;color:#64748b}
.sidebar-actions{display:grid;grid-template-columns:1fr auto auto;gap:8px}
.ai-chat-history{min-height:0;display:flex;flex-direction:column;gap:8px;overflow:auto;padding-right:2px}
.halo-ai-session{border:1px solid transparent;background:transparent;text-align:left;border-radius:16px;padding:12px;display:grid;gap:5px;cursor:pointer;transition:.16s ease}
.halo-ai-session:hover,.halo-ai-session.is-active{border-color:#d7dee9;background:#fff;box-shadow:0 10px 28px rgba(15,23,42,.07)}
.halo-ai-session span{font-size:14px;font-weight:650;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.halo-ai-session small{font-size:11px;color:#64748b}.session-actions{display:flex;gap:10px;align-items:center}.halo-ai-session b{font-size:11px;color:#2563eb;font-weight:500}.halo-ai-session b.danger{color:#ef4444}.session-rename{width:100%;border:1px solid #bfdbfe;border-radius:10px;padding:6px 8px;font-size:13px;outline:none;background:#fff}
.session-search{width:100%;height:36px;border:1px solid #d7dee8;border-radius:999px;padding:0 12px;background:#fff;outline:none;font-size:13px}.session-search:focus{border-color:#93c5fd;box-shadow:0 0 0 3px rgba(59,130,246,.12)}.search-hint{margin-top:-8px;color:#94a3b8;font-size:11px;line-height:1.4}
.ai-chat-main{grid-column:2;min-width:0;min-height:0;overflow:hidden;display:grid;grid-template-rows:auto auto auto minmax(0,1fr) auto}
.ai-chat-toolbar{grid-row:1;min-height:66px;border-bottom:1px solid #e6eaf0;background:rgba(255,255,255,.82);backdrop-filter:blur(10px);display:flex;align-items:center;gap:12px;padding:12px 18px}
.ai-chat-toolbar .title{display:grid;margin-right:auto}.ai-chat-toolbar strong{font-size:18px}.ai-chat-toolbar span{font-size:12px;color:#64748b}
.ai-chat-toolbar select{height:38px;border:1px solid #d7dee8;border-radius:999px;background:#fff;padding:0 12px;max-width:280px;outline:none}
.context-meter{width:54px;height:54px;border:0;border-radius:999px;background:conic-gradient(#2563eb var(--context-used),#e2e8f0 0);padding:3px;cursor:pointer;display:grid;place-items:center;box-shadow:0 10px 24px rgba(37,99,235,.12)}
.context-meter:disabled{opacity:.58;cursor:wait}.context-meter strong,.context-meter span{grid-area:1/1}.context-meter strong{width:48px;height:48px;border-radius:999px;background:#fff;display:flex;align-items:center;justify-content:center;font-size:12px;color:#0f172a;padding-top:0}.context-meter span{align-self:end;justify-self:center;margin-bottom:8px;font-size:9px;color:#64748b;transform:translateY(10px)}
.ai-chat-error{grid-row:3;margin:14px 20px 0;padding:12px 14px;border:1px solid #fecaca;background:#fff1f2;color:#b91c1c;border-radius:16px}
.conversation-tags{grid-row:2;display:flex;gap:8px;flex-wrap:wrap;padding:10px 20px 0}.conversation-tags button{border:1px solid #bfdbfe;background:#eff6ff;color:#1d4ed8;border-radius:999px;padding:4px 10px;font-size:12px;cursor:pointer}
.halo-ai-messages{grid-row:4;min-height:0;overflow:auto;padding:24px 22px;scroll-padding-bottom:24px;display:flex;flex-direction:column;gap:18px;overscroll-behavior:contain}
.load-older{align-self:center;border:1px solid #d7dee8;background:#fff;color:#64748b;border-radius:999px;padding:7px 14px;font-size:12px;cursor:pointer;box-shadow:0 8px 18px rgba(15,23,42,.06)}.load-older:hover{border-color:#bfdbfe;color:#2563eb}
.halo-ai-message{display:grid;grid-template-columns:38px minmax(0,1fr);gap:10px;align-items:flex-start}.halo-ai-message.user{grid-template-columns:minmax(0,1fr) 38px}.halo-ai-message.user .avatar{grid-column:2}.halo-ai-message.user .bubble{grid-column:1;grid-row:1;justify-self:end}
.avatar{width:34px;height:34px;border-radius:999px;background:#111827;color:#fff;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;box-shadow:0 8px 20px rgba(15,23,42,.14)}
.user .avatar{background:#2563eb}
.bubble{max-width:min(860px,82%);border:1px solid #e5e9f0;background:rgba(255,255,255,.96);border-radius:22px;padding:15px 17px;box-shadow:0 14px 34px rgba(15,23,42,.08);overflow:hidden}
.user .bubble{background:#111827;color:#fff;border-color:#111827;border-bottom-right-radius:8px}.assistant .bubble{border-bottom-left-radius:8px}
.markdown-body{font-size:14px;line-height:1.72;word-break:break-word}.markdown-body p{margin:0 0 10px}.markdown-body p:last-child{margin-bottom:0}
.markdown-body h1,.markdown-body h2,.markdown-body h3,.markdown-body h4,.markdown-body h5,.markdown-body h6{margin:14px 0 8px;line-height:1.35;font-weight:750}.markdown-body h1{font-size:1.45em}.markdown-body h2{font-size:1.3em}.markdown-body h3{font-size:1.15em}.markdown-body h4,.markdown-body h5,.markdown-body h6{font-size:1em}.markdown-body ul,.markdown-body ol{margin:8px 0 10px 20px;padding:0}.markdown-body li{margin:4px 0}.markdown-body hr{border:0;border-top:1px solid #e5e7eb;margin:14px 0}
.markdown-body code{border-radius:7px;background:#eef2f7;padding:2px 5px;font-size:.92em}.user .markdown-body code{background:#263244}
.markdown-body a{color:#2563eb;text-decoration:none}.user .markdown-body a{color:#93c5fd}.markdown-body blockquote{border-left:3px solid #cbd5e1;margin:10px 0;padding:4px 0 4px 12px;color:#64748b}
.markdown-body table{width:100%;border-collapse:collapse;margin:10px 0;display:block;overflow:auto}.markdown-body th,.markdown-body td{border:1px solid #e2e8f0;padding:6px 8px;text-align:left}.markdown-body th{background:#f8fafc;font-weight:700}
.user .markdown-body,.user .markdown-body p,.user .markdown-body li,.user .markdown-body h1,.user .markdown-body h2,.user .markdown-body h3,.user .markdown-body h4,.user .markdown-body h5,.user .markdown-body h6{color:#fff;background:transparent}
.user .markdown-body code{background:rgba(255,255,255,.12);color:#fff;border:0}.user .math-inline{background:rgba(255,255,255,.1);border-color:rgba(255,255,255,.18);color:#fff}.user .math-block{background:rgba(255,255,255,.08);border-color:rgba(255,255,255,.16);color:#fff}
.markdown-body pre,.md-code{position:relative;margin:12px 0;padding:14px 12px;background:#0f172a;color:#e5e7eb;border-radius:16px;overflow:auto}.md-code{padding-top:34px}.md-code span{position:absolute;top:8px;left:12px;font-size:11px;color:#94a3b8}.markdown-body pre code,.md-code code{display:block;white-space:pre;min-width:max-content;background:transparent;padding:0;color:inherit}
.latex-source-inline{font-family:ui-monospace,SFMono-Regular,Consolas,Liberation Mono,Menlo,monospace;background:#eef2f7;border:1px solid #dbe3ee;border-radius:7px;padding:1px 5px;color:#334155}.latex-source-block{margin:10px 0;padding:11px 12px;background:#f8fafc;border:1px solid #dbe3ee;border-radius:14px;overflow:auto}.latex-source-block code{font-family:ui-monospace,SFMono-Regular,Consolas,Liberation Mono,Menlo,monospace;white-space:pre-wrap;color:#334155;background:transparent;padding:0}
.math-inline{font-family:Cambria Math,Times New Roman,serif;background:#f8fafc;border:1px solid #e5e7eb;border-radius:6px;padding:1px 5px}.math-block{font-family:Cambria Math,Times New Roman,serif;text-align:center;background:#f8fafc;border:1px solid #e5e7eb;border-radius:14px;padding:10px;margin:10px 0;overflow:auto}
.math-lite{display:inline-flex;align-items:center;gap:2px;font-family:Cambria Math,Times New Roman,serif;font-size:1.05em;line-height:1.2}.math-lite-block{justify-content:center;min-width:100%}.math-frac{display:inline-grid;grid-template-rows:auto auto;vertical-align:middle;text-align:center;margin:0 2px}.math-frac>span:first-child{border-bottom:1px solid currentColor;padding:0 4px 2px}.math-frac>span:last-child{padding:2px 4px 0}.math-sqrt{display:inline-flex;align-items:flex-start;border-top:1px solid currentColor;margin-left:2px;padding-left:3px}.math-sqrt:before{content:"√";font-size:1.35em;line-height:.9;margin:-1px 2px 0 0}.mermaid-diagram{margin:12px 0;padding:12px;border:1px solid #dbe3ee;background:#f8fafc;border-radius:16px;overflow:auto;display:flex;align-items:center;gap:10px;flex-wrap:wrap}.mermaid-node{display:inline-flex;align-items:center;justify-content:center;min-height:34px;padding:6px 12px;border:1px solid #bfdbfe;border-radius:12px;background:#eff6ff;color:#0f172a;font-size:13px;font-weight:650}.mermaid-arrow{color:#64748b;font-weight:800}
.reasoning{margin-bottom:12px;border:1px solid #e3e8ef;background:#f8fafc;border-radius:16px;padding:9px 11px}.reasoning summary{cursor:pointer;color:#64748b;font-weight:650;font-size:13px}.reasoning .markdown-body{margin-top:8px;color:#475569;font-size:13px}
.streaming-pill{display:inline-flex;align-items:center;gap:6px;margin-bottom:10px;border:1px solid #bfdbfe;background:#eff6ff;color:#2563eb;border-radius:999px;padding:4px 10px;font-size:12px;font-weight:650}.streaming-pill:before{content:"";width:7px;height:7px;border-radius:999px;background:#2563eb;animation:pulse 1s ease-in-out infinite}@keyframes pulse{0%,100%{opacity:.35;transform:scale(.85)}50%{opacity:1;transform:scale(1.12)}}
.attachments{display:flex;gap:7px;flex-wrap:wrap;margin-top:10px}.attachments span{font-size:12px;border:1px solid #cbd5e1;border-radius:999px;padding:3px 9px;background:#f8fafc;color:#334155}.user .attachments span{background:rgba(255,255,255,.12);color:#e5e7eb;border-color:rgba(255,255,255,.18)}
.images{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:10px;margin-top:12px}.images img{width:100%;border-radius:16px;border:1px solid #e5e7eb}
.ai-chat-composer{grid-row:5;position:relative;z-index:4;align-self:stretch;align-content:start;min-height:0;border-top:1px solid #e6eaf0;background:#fff;padding:10px 18px 12px;display:grid;gap:8px;box-shadow:0 -10px 24px rgba(15,23,42,.08)}
.ai-chat-composer textarea{width:100%;height:72px;min-height:72px;max-height:72px;resize:none;border:1px solid #d7dee8;border-radius:18px;padding:13px 15px;font:inherit;line-height:1.45;outline:none;background:#fff;box-shadow:inset 0 1px 2px rgba(15,23,42,.04)}
.ai-chat-composer textarea:focus{border-color:#93c5fd;box-shadow:0 0 0 4px rgba(59,130,246,.12)}
.composer-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.file-button{font-size:13px;color:#2563eb;cursor:pointer}.file-button input{display:none}.hint{font-size:12px;color:#94a3b8;margin-right:auto}
.message-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.message-actions button{border:1px solid #d7dee8;background:#fff;border-radius:999px;padding:4px 9px;font-size:12px;color:#475569;cursor:pointer}.message-actions button:disabled{opacity:.45;cursor:not-allowed}.token-stats{margin-top:8px;font-size:11px;color:#94a3b8}.message-edit textarea{width:100%;min-height:92px;border:1px solid #d7dee8;border-radius:14px;padding:10px;font:inherit;resize:vertical;background:#fff;color:#0f172a}
.drop-mask{position:fixed;inset:0;z-index:10000;background:rgba(37,99,235,.12);border:3px dashed #60a5fa;display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:700;color:#1d4ed8;pointer-events:none}.ai-chat-settings{min-height:calc(100vh - 64px);background:#f1f5f9;padding:24px;display:grid;gap:18px;align-content:start}.settings-panel{max-width:1080px;background:#fff;border:1px solid #e2e8f0;border-radius:18px;padding:20px;box-shadow:0 12px 28px rgba(15,23,42,.06)}.settings-panel h1,.settings-panel h2{margin:0 0 16px}.settings-panel label{display:grid;gap:6px;margin:12px 0}.settings-panel input{height:38px;border:1px solid #d7dee8;border-radius:10px;padding:0 10px}.settings-panel textarea{min-height:120px;border:1px solid #d7dee8;border-radius:12px;padding:10px;resize:vertical;background:#fff;color:#0f172a}.readonly-note{border:1px solid #dbe3ee;background:#f8fafc;border-radius:12px;padding:10px 12px;color:#475569}.settings-title-row{display:flex;align-items:center;justify-content:space-between;gap:12px}.settings-actions{display:flex;gap:8px;align-items:center}.settings-error{margin:0 0 10px;color:#b91c1c;background:#fff1f2;border:1px solid #fecaca;border-radius:12px;padding:9px 11px}.call-log-list{display:grid;gap:8px}.call-log-item{display:grid;grid-template-columns:1.1fr 1.2fr 1.3fr 1.3fr;gap:8px;border:1px solid #edf2f7;border-radius:12px;padding:10px}.call-log-item small{grid-column:1/-1;color:#64748b}
@media (max-width: 900px){.ai-chat-shell{height:100dvh;max-height:100dvh;min-height:min(520px,100dvh);grid-template-columns:1fr}.ai-chat-sidebar{display:none}.sidebar-toggle{left:0;top:44%;width:24px;height:48px}.ai-chat-main{grid-column:1}.bubble{max-width:100%}.ai-chat-toolbar{display:grid;grid-template-columns:96px minmax(0,1fr) 48px;align-items:center;gap:8px;padding:10px 12px 10px 34px}.ai-chat-toolbar .title{grid-column:1/-1;width:100%;min-width:0}.ai-chat-toolbar select{max-width:none;width:100%;min-width:0}.ai-chat-toolbar .context-meter{width:46px;height:46px}.ai-chat-toolbar .context-meter strong{width:40px;height:40px;font-size:11px}.ai-chat-toolbar button:not(.context-meter){grid-column:1/-1;justify-self:start}.halo-ai-messages{padding:16px 12px;scroll-padding-bottom:16px}.ai-chat-composer{padding:9px 12px 10px}.ai-chat-composer textarea{height:88px;min-height:88px;max-height:88px;border-radius:16px}.composer-actions{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:8px}.hint{font-size:11px;white-space:normal}.halo-ai-message,.halo-ai-message.user{grid-template-columns:32px minmax(0,1fr)}.halo-ai-message.user .avatar{grid-column:1}.halo-ai-message.user .bubble{grid-column:2}}
@media (max-width: 560px){.ai-chat-toolbar{grid-template-columns:84px minmax(0,1fr);padding-left:32px}.ai-chat-toolbar .context-meter{grid-column:2;justify-self:end}.ai-chat-toolbar button:not(.context-meter){grid-column:1/-1}.context-meter{width:42px;height:42px}.composer-actions{grid-template-columns:1fr auto}.composer-actions .hint{grid-column:1/-1;order:3}.file-button{align-self:center}.ai-chat-composer textarea{font-size:15px}.message-actions button{font-size:11px;padding:4px 8px}}
`;

  window["halo-ai-console"] = definePlugin({
    routes: [
      {
        parentName: "Root",
        route: {
          path: "/halo-ai-console",
          name: "HaloAiConsole",
          component: ChatView,
          meta: {
            title: "AI 聊天",
            searchable: true,
            permissions: ["plugin:halo-ai-console:view"],
            menu: {
              name: "AI 聊天",
              group: "tool",
              icon: markRaw(components.IconMessageCircle || components.IconRobot || components.IconPlug),
              priority: 42,
            },
          },
        },
      },
      {
        parentName: "Root",
        route: {
          path: "/halo-ai-console/settings",
          name: "HaloAiConsoleSettings",
          component: SettingsView,
          meta: {
            title: "AI 聊天设置",
            searchable: true,
            permissions: ["plugin:halo-ai-console:view"],
            menu: {
              name: "AI 聊天设置",
              group: "tool",
              icon: markRaw(components.IconSettings || components.IconPlug),
              priority: 43,
            },
          },
        },
      },
    ],
  });
})();
