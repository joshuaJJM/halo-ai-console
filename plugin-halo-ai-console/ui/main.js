(function () {
  "use strict";

  const { definePlugin } = window.HaloUiShared;
  const { h, ref, computed, onMounted, onActivated, onBeforeUnmount, watch, markRaw, nextTick } = window.Vue;
  const components = window.HaloComponents || {};
  const axios = window.HaloApiClient && window.HaloApiClient.axiosInstance;
  const sessionTargetApi = window.HaloAiSessionTarget;
  const sessionVersionApi = window.HaloAiSessionVersion;
  const jobStatusApi = window.HaloAiJobStatus;
  const ownerCache = window.HaloAiOwnerCache?.createOwnerCache(localStorage);

  if (!sessionTargetApi || !sessionVersionApi || !jobStatusApi || !ownerCache) {
    throw new Error("Halo AI Console 前端状态模块未正确打包。");
  }

  const API = "/apis/console.api.aifoundation.halo.run/v1alpha1";
  const CHAT_API = "/apis/console.api.halo-ai-console.halo.run/v1alpha1";
  const STORE_KEY = "sessions";
  const SELECTED_KEY = "selected";
  const SETTINGS_KEY = "settings";
  const LOG_KEY = "call-logs";
  const PENDING_SESSION_KEY = "pending-session-sync";
  const SIDEBAR_KEY = "sidebar-collapsed";
  const MIGRATION_DISMISSED_KEY = "legacy-migration-dismissed";
  const EMPTY_TITLE = "新的聊天";
  const SUPPORTED_IMAGE_ACCEPT = "image/png,image/jpeg,.png,.jpg,.jpeg";
  const MAX_UPLOAD_IMAGES = 8;
  let cacheOwner = "";
  let cacheCapabilities = { canViewAllLogs: false };

  function readCache(key) {
    return ownerCache.read(key);
  }

  function writeCache(key, value) {
    ownerCache.write(key, value);
  }

  function removeCache(key) {
    ownerCache.remove(key);
  }

  function clearOwnerCache() {
    [STORE_KEY, SELECTED_KEY, SETTINGS_KEY, LOG_KEY, PENDING_SESSION_KEY, SIDEBAR_KEY, MIGRATION_DISMISSED_KEY]
      .forEach(removeCache);
  }

  async function activateOwnerCache() {
    const { data } = await axios.get(`${CHAT_API}/me/identity`);
    const owner = String(data?.owner || "").trim();
    if (!owner) throw new Error("无法确认当前登录用户，已停止读取本地聊天缓存。");
    const activated = ownerCache.setOwner(owner);
    cacheOwner = activated.owner;
    cacheCapabilities = { canViewAllLogs: Boolean(data?.canViewAllLogs) };
    return { owner, changed: activated.changed, ...cacheCapabilities };
  }

  function isAuthenticationFailure(cause) {
    return Number(cause?.response?.status) === 401;
  }

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

  function safeRenderedImageSource(value) {
    const source = String(value || "").trim();
    if (/^data:image\/(?:png|jpe?g|gif|webp);base64,/i.test(source)) return source;
    try {
      const url = new URL(source, window.location.origin);
      if (!/^https?:$/i.test(url.protocol) || url.origin !== window.location.origin) return "";
      return url.href;
    } catch (_) {
      return "";
    }
  }

  function removeUnsafeRenderedImages(html) {
    const template = document.createElement("template");
    template.innerHTML = String(html || "");
    template.content.querySelectorAll("img").forEach((image) => {
      const source = safeRenderedImageSource(image.getAttribute("src"));
      if (source) image.setAttribute("src", source);
      else image.replaceWith(document.createTextNode(image.getAttribute("alt") || "[图片已阻止加载]"));
    });
    return template.innerHTML;
  }

  function sanitizeRenderedHtml(html) {
    if (window.DOMPurify?.sanitize) {
      return removeUnsafeRenderedImages(window.DOMPurify.sanitize(String(html || ""), {
        USE_PROFILES: { html: true },
        ADD_TAGS: ["div", "span", "sup", "sub"],
        ADD_ATTR: ["target", "rel", "class", "data-source", "role", "aria-label"],
        ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
      }));
    }
    if (window.Sanitizer) {
      try {
        const sanitizer = new window.Sanitizer();
        const fragment = sanitizer.sanitizeFor("div", String(html || ""));
        return removeUnsafeRenderedImages(fragment?.innerHTML || "");
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
    return removeUnsafeRenderedImages(template.innerHTML);
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
        const source = node.textContent || "";
        const tokens = /(\/\/[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/)|("[^"\n]*"|'[^'\n]*'|`[^`\n]*`)|\b(\d+(?:\.\d+)?)\b|\b(function|class|const|let|var|return|if|else|for|while|try|catch|finally|await|async|import|from|export|def|lambda|in|not|and|or|public|private|static|new|void|true|false|null|None|True|False)\b|\b(console|print|Math|JSON|String|Number|Array|Object|Map|Set)\b/g;
        const fragment = document.createDocumentFragment();
        let offset = 0;
        for (const match of source.matchAll(tokens)) {
          fragment.append(document.createTextNode(source.slice(offset, match.index)));
          const span = document.createElement("span");
          span.className = match[1] ? "hljs-comment"
            : match[2] ? "hljs-string"
              : match[3] ? "hljs-number"
                : match[4] ? "hljs-keyword" : "hljs-built_in";
          span.textContent = match[0];
          fragment.append(span);
          offset = match.index + match[0].length;
        }
        fragment.append(document.createTextNode(source.slice(offset)));
        node.replaceChildren(fragment);
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
      const parsed = JSON.parse(readCache(STORE_KEY) || "[]");
      if (!Array.isArray(parsed)) return [];
      return parsed.map((session) => ({
        ...session,
        messages: Array.isArray(session.messages)
          ? session.messages.filter((message) => {
            const hasText = typeof message.content === "string" && message.content.trim().length > 0;
            const hasReasoning = typeof message.reasoning === "string" && message.reasoning.trim().length > 0;
            const hasFiles = Array.isArray(message.files) && message.files.length > 0;
            const hasImages = Array.isArray(message.images) && message.images.length > 0;
            return hasText || hasReasoning || hasFiles || hasImages;
          })
          : [],
      }));
    } catch (_) {
      return [];
    }
  }

  function saveSessions(sessions) {
    const recent = [...sessions]
      .sort((left, right) => Number(right?.updatedAt || 0) - Number(left?.updatedAt || 0))
      .slice(0, 50);
    writeCache(STORE_KEY, JSON.stringify(recent));
  }

  function loadSettings() {
    try {
      return { ...defaultPersonalSettings(), ...JSON.parse(readCache(SETTINGS_KEY) || "{}") };
    } catch (_) {
      return defaultPersonalSettings();
    }
  }

  function defaultPersonalSettings() {
    return { lazyBatchSize: 60, olderBatchSize: 40, imageMaxSizeMb: 8, autoCompressPercent: 85, memoryText: "" };
  }

  function saveSettings(settings) {
    writeCache(SETTINGS_KEY, JSON.stringify(settings));
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

  function loadCallLogs() {
    try {
      const parsed = JSON.parse(readCache(LOG_KEY) || "[]");
      return Array.isArray(parsed) ? parsed : [];
    } catch (_) {
      return [];
    }
  }

  function saveCallLogs(logs) {
    writeCache(LOG_KEY, JSON.stringify(logs.slice(0, 300)));
  }

  function newSyncToken() {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID();
    const bytes = new Uint32Array(4);
    if (window.crypto?.getRandomValues) window.crypto.getRandomValues(bytes);
    return `sync-${Date.now().toString(36)}-${Array.from(bytes, (item) => item.toString(36)).join("-")}-${Math.random().toString(36).slice(2)}`;
  }

  function loadPendingSessionSyncs() {
    try {
      const parsed = JSON.parse(readCache(PENDING_SESSION_KEY) || "{}");
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
      return Object.fromEntries(Object.entries(parsed)
        .filter(([id, value]) => id && value && (typeof value.token === "string" || Number.isFinite(Number(value.revision))))
        .map(([id, value]) => [id, {
          token: typeof value.token === "string" && value.token ? value.token : `legacy-${id}-${Number(value.revision) || 0}`,
          owner: typeof value.owner === "string" && value.owner ? value.owner : cacheOwner,
          updatedAt: Math.max(0, Number(value.updatedAt) || 0),
          base: value.base && typeof value.base === "object" && !Array.isArray(value.base) ? value.base : null,
          draft: value.draft && typeof value.draft === "object" && !Array.isArray(value.draft) ? value.draft : null,
          details: Array.isArray(value.details) ? value.details.map(String).slice(0, 20) : [],
          conflicted: value.conflicted === true,
        }]));
    } catch (_) {
      return {};
    }
  }

  function savePendingSessionSyncs(pending) {
    if (Object.keys(pending).length) writeCache(PENDING_SESSION_KEY, JSON.stringify(pending));
    else removeCache(PENDING_SESSION_KEY);
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
      _version: Math.max(0, Number(session._version) || 0),
      title: session.title || EMPTY_TITLE,
      memory: session.memory || "",
      tags: Array.isArray(session.tags) ? session.tags : [],
      contextClearedAt: session.contextClearedAt || 0,
      createdAt: session.createdAt || Date.now(),
      updatedAt: session.updatedAt || session.createdAt || Date.now(),
      messages: Array.isArray(session.messages) ? session.messages : [],
    };
  }

  function cloneSessionSnapshot(session) {
    return session ? JSON.parse(JSON.stringify(session)) : null;
  }

  function initialSessionBaseline(session) {
    const normalized = normalizeSession(session || {});
    return {
      id: normalized.id,
      _version: 0,
      title: EMPTY_TITLE,
      memory: "",
      tags: [],
      contextClearedAt: 0,
      createdAt: normalized.createdAt,
      updatedAt: normalized.createdAt,
      messages: [],
    };
  }

  function sameSnapshotValue(left, right) {
    return JSON.stringify(left ?? null) === JSON.stringify(right ?? null);
  }

  function mergeConflictingSession(baseSession, localSession, serverSession) {
    const local = normalizeSession(localSession || {});
    const server = normalizeSession(serverSession || {});
    if (!baseSession) {
      const localComparable = { ...local };
      const serverComparable = { ...server };
      delete localComparable._version;
      delete serverComparable._version;
      if (sameSnapshotValue(localComparable, serverComparable)) {
        return { session: server, conflicts: [] };
      }
      return {
        session: server,
        conflicts: ["缺少本地编辑的基线快照，无法安全合并。请重新应用该修改。"],
      };
    }
    const base = normalizeSession(baseSession);
    const merged = { ...server, messages: [] };
    const conflicts = [];
    for (const field of ["title", "memory", "tags", "contextClearedAt"]) {
      const localChanged = !sameSnapshotValue(local[field], base[field]);
      const serverChanged = !sameSnapshotValue(server[field], base[field]);
      if (localChanged && serverChanged && !sameSnapshotValue(local[field], server[field])) {
        conflicts.push(`会话字段“${field}”`);
      } else if (localChanged) {
        merged[field] = local[field];
      }
    }
    const baseMessages = new Map(base.messages.map((message) => [message.id, message]));
    const localMessages = new Map(local.messages.map((message) => [message.id, message]));
    const serverMessages = new Map(server.messages.map((message) => [message.id, message]));
    const messageIds = new Set([...baseMessages.keys(), ...localMessages.keys(), ...serverMessages.keys()]);
    for (const id of messageIds) {
      const baseMessage = baseMessages.get(id);
      const localMessage = localMessages.get(id);
      const serverMessage = serverMessages.get(id);
      const localChanged = !sameSnapshotValue(localMessage, baseMessage);
      const serverChanged = !sameSnapshotValue(serverMessage, baseMessage);
      if (!baseMessage) {
        if (!serverMessage || (localMessage && sameSnapshotValue(localMessage, serverMessage))) {
          if (localMessage) merged.messages.push({ ...localMessage });
        } else if (!localMessage) {
          merged.messages.push({ ...serverMessage });
        } else {
          conflicts.push(`消息“${id}”`);
          merged.messages.push({ ...serverMessage });
        }
      } else if (!localMessage) {
        if (!serverChanged || !serverMessage) {
          continue;
        }
        conflicts.push(`消息“${id}”的删除`);
        merged.messages.push({ ...serverMessage });
      } else if (!serverMessage) {
        if (!localChanged) {
          continue;
        }
        conflicts.push(`消息“${id}”`);
      } else if (!localChanged) {
        merged.messages.push({ ...serverMessage });
      } else if (!serverChanged || sameSnapshotValue(localMessage, serverMessage)) {
        merged.messages.push({ ...localMessage });
      } else {
        conflicts.push(`消息“${id}”`);
        merged.messages.push({ ...serverMessage });
      }
    }
    merged.messages.sort((left, right) => Number(left.createdAt || 0) - Number(right.createdAt || 0));
    merged.updatedAt = Math.max(Number(local.updatedAt) || 0, Number(server.updatedAt) || 0);
    merged._version = Math.max(0, Number(server._version) || 0);
    return { session: merged, conflicts };
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

  function modelTypeLabel(type) {
    const value = String(type || "").toLowerCase().replace(/[-_\s]/g, "");
    const labels = {
      language: "语言",
      chat: "对话",
      multimodal: "多模态",
      vision: "视觉",
      image: "图像生成",
      "image-generation": "图像生成",
      imagegeneration: "图像生成",
      texttoimage: "文生图",
    };
    return labels[value] || "模型";
  }

  function operationLabel(operation) {
    const labels = {
      chat: "对话",
      image: "图像生成",
      summary: "生成摘要",
      compress: "压缩上下文",
      title: "生成标题",
      upload: "上传文件",
      migration: "迁移数据",
    };
    return labels[String(operation || "").toLowerCase()] || operation || "未知操作";
  }

  function statusLabel(status) {
    const labels = {
      pending: "等待中",
      running: "生成中",
      success: "成功",
      completed: "已完成",
      error: "失败",
      failed: "失败",
      cancelled: "已取消",
      canceled: "已取消",
      interrupted: "已中断",
      empty: "结果为空",
    };
    return labels[String(status || "").toLowerCase()] || status || "未知状态";
  }

  function tagLabel(tag) {
    const labels = {
      image: "图像",
      code: "代码",
      error: "错误",
      config: "配置",
      security: "安全",
      summary: "摘要",
    };
    return labels[String(tag || "").toLowerCase()] || tag;
  }

  function resourceLabel(resource) {
    const labels = {
      sessions: "会话",
      jobs: "Job",
      usage: "用量记录",
      auditLogs: "审计日志",
      personalStore: "个人设置与缓存",
    };
    return labels[resource] || resource || "未知资源";
  }

  function clientLabel(value) {
    return String(value || "") === "Other" ? "其他" : (value || "-");
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

  async function generateText(model, messages, maxOutputTokens, operation = "utility") {
    const { data } = await axios.post(
      `${CHAT_API}/models/${encodeURIComponent(model)}/generate-text`,
      { messages, maxOutputTokens, operation }
    );
    return data || {};
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
      const sessions = ref([]);
      const selectedId = ref("");
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
      const settings = ref(defaultPersonalSettings());
      const globalSettings = ref(defaultGlobalSettings());
      const visibleMessageCount = ref(Number(settings.value.lazyBatchSize) || 60);
      const callLogs = ref([]);
      const abortController = ref(null);
      const activeJobId = ref("");
      const activeAssistantId = ref("");
      const activeSessionId = ref("");
      const cancellationRequestedSessions = new Set();
      const pendingJobSessions = new Set();
      const editingMessageId = ref("");
      const editingMessageValue = ref("");
      const dragActive = ref(false);
      const dbReady = ref(false);
      const sidebarCollapsed = ref(false);
      const contextCompressing = ref(false);
      const sessionQuery = ref("");
      const showFavoritesOnly = ref(false);
      const persistTimers = new Map();
      const scheduledPersists = new Map();
      const recoveryJobControllers = new Map();
      const activeJobsBySession = ref({});
      const deletedSessionIds = new Set();
      const serverSessionSnapshots = new Map();
      const syncConflicts = ref({});
      let lastAutoCompressionAt = 0;

      const current = computed(() => sessions.value.find((item) => item.id === selectedId.value) || sessions.value[0]);
      const currentActiveJobs = computed(() => activeJobsBySession.value[current.value?.id] || []);
      const currentSyncConflict = computed(() => current.value ? syncConflicts.value[current.value.id] : null);
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
        const recentMessages = (current.value?.messages || []).slice(-8);
        return recentMessages.reduce((sum, message) => {
          const files = (message.files || []).reduce((fileSum, file) => fileSum + estimateTokens(file.name || file.title || ""), 0);
          return sum + estimateTokens(`${message.content || ""}\n${message.reasoning || ""}`) + files;
        }, estimateTokens(`${settings.value.memoryText || ""}\n${current.value?.memory || ""}`));
      });
      const contextRemaining = computed(() => Math.max(0, contextLimit.value - contextUsed.value));
      const contextUsedPercent = computed(() => Math.min(100, Math.round((contextUsed.value / Math.max(1, contextLimit.value)) * 100)));
      const selectableModels = computed(() => [
        { label: "默认", value: "default" },
        ...allowedModels.value.map((item) => ({ label: `${item.label}（${modelTypeLabel(item.modelType)}）`, value: item.name })),
      ]);

      function allowSessionWrite(sessionId, notify = true) {
        if (sessionId && syncConflicts.value[sessionId]) {
          if (notify) error.value = "该会话存在未处理的同步冲突。请先另存或放弃本地草稿。";
          return false;
        }
        return true;
      }

      function sessionTarget(session = current.value) {
        return sessionTargetApi.createSessionTarget(session, cacheOwner);
      }

      function resolveSessionTarget(target, notify = true) {
        const blockedSessionIds = new Set(Object.keys(syncConflicts.value));
        const result = sessionTargetApi.resolveSessionTarget(target, {
          owner: cacheOwner,
          sessions: sessions.value,
          deletedSessionIds,
          blockedSessionIds,
        });
        if (result.session) return result.session;
        if (notify) {
          error.value = result.reason === "session-conflict"
            ? "该会话存在未处理的同步冲突。请先另存或放弃本地草稿。"
            : "会话已删除、不可用或登录用户已变化，已停止写入本次异步结果。";
        }
        return null;
      }

      function scrollTargetIfVisible(target) {
        if (target?.sessionId === current.value?.id) scheduleScrollBottom();
      }

      function persistSession(session = current.value) {
        if (!session || !allowSessionWrite(session.id)) return false;
        saveSessions(sessions.value);
        writeCache(SELECTED_KEY, selectedId.value);
        queuePersistDb(session);
        return true;
      }

      function persist() {
        return persistSession();
      }

      function persistLocalOnly() {
        if (current.value && !allowSessionWrite(current.value.id, false)) return false;
        saveSessions(sessions.value);
        writeCache(SELECTED_KEY, selectedId.value);
        return true;
      }

      function persistStreaming(session = current.value) {
        if (!session || !allowSessionWrite(session.id, false)) return false;
        saveSessions(sessions.value);
        writeCache(SELECTED_KEY, selectedId.value);
        return true;
      }

      function flushStreamingPersist(session = current.value) {
        return persistSession(session);
      }

      function toggleSidebar() {
        sidebarCollapsed.value = !sidebarCollapsed.value;
        writeCache(SIDEBAR_KEY, sidebarCollapsed.value ? "true" : "false");
      }

      function auxiliaryModelFor(session) {
        const usableModels = new Set([...languageModels.value, ...multimodalModels.value]
          .map((item) => item.name));
        return [...(session?.messages || [])].reverse()
          .map((message) => message?.generation?.model)
          .find((model) => usableModels.has(model)) || "";
      }

      function compressionChunks(messages, maxCharacters = 24_000, maxChunks = 8) {
        const chunks = [];
        let chunk = "";
        for (const message of messages) {
          const prefix = `${message.role === "user" ? "用户" : "AI"}：`;
          const content = String(message.content || "（无文本内容）");
          const entryLimit = Math.max(1, maxCharacters - prefix.length);
          for (let offset = 0; offset < content.length; offset += entryLimit) {
            const entry = `${prefix}${content.slice(offset, offset + entryLimit)}`;
            if (chunk && chunk.length + entry.length + 2 > maxCharacters) {
              chunks.push(chunk);
              chunk = "";
            }
            chunk += `${chunk ? "\n\n" : ""}${entry}`;
          }
        }
        if (chunk) chunks.push(chunk);
        if (chunks.length > maxChunks) {
          throw new Error("对话过长，无法在安全的请求上限内自动压缩。请先手动分段摘要或清除部分上下文。");
        }
        return chunks;
      }

      async function summarizeCompressionChunks(model, messages, operation, outputTokens) {
        const summaries = [];
        const chunks = compressionChunks(messages);
        const summaryInstruction = operation === "conversation-summary"
          ? "请给下面这段对话生成结构化摘要，包含：目标、已确认事实、关键结论、待办/未解决问题。不要编造，也不要包含思考过程。"
          : "请压缩以下对话，保留目标、已确认事实、关键约束和未完成事项。不要包含思考过程，不要编造。";
        for (let index = 0; index < chunks.length; index += 1) {
          const result = await generateText(model, [{
            id: uid("compress-user"),
            role: "user",
            parts: [{
              type: "text",
              id: uid("compress-text"),
              text: `${summaryInstruction}\n\n第 ${index + 1}/${chunks.length} 段：\n\n${chunks[index]}`,
            }],
          }], Math.min(outputTokens, 900), operation);
          const summary = String(result.text || "").trim();
          if (!summary) throw new Error("压缩模型没有返回摘要。");
          summaries.push(summary);
        }
        if (summaries.length === 1) return summaries[0];
        const result = await generateText(model, [{
          id: uid("compress-merge-user"),
          role: "user",
          parts: [{
            type: "text",
            id: uid("compress-merge-text"),
            text: `${operation === "conversation-summary" ? "请合并以下分段对话摘要，输出结构化的目标、已确认事实、关键结论、待办/未解决问题。不要编造。" : "请合并以下分段摘要，保留目标、事实、约束和未完成事项。不要编造。"}\n\n${summaries.join("\n\n")}`,
          }],
        }], outputTokens, operation);
        const merged = String(result.text || "").trim();
        if (!merged) throw new Error("压缩模型没有返回合并摘要。");
        return merged;
      }

      async function compressContext() {
        if (!current.value || contextCompressing.value) return;
        if (!allowSessionWrite(current.value.id)) return;
        const target = sessionTarget(current.value);
        const session = resolveSessionTarget(target);
        if (!session) return;
        const messages = session.messages || [];
        const recent = messages.slice(-8);
        const older = messages.slice(0, Math.max(0, messages.length - recent.length));
        if (!older.length) {
          window.alert("当前对话还没有可压缩的更早上下文。");
          return;
        }
        if (!window.confirm("将更早的对话压缩为摘要，并保留最近消息。继续吗？")) return;
        const model = auxiliaryModelFor(session);
        if (!model) {
          error.value = "没有可复用当前对话模型的语言或多模态模型，未发送压缩请求。";
          return;
        }
        contextCompressing.value = true;
        error.value = "";
        try {
          const summary = await summarizeCompressionChunks(model, older, "context-compression", 1200);
          if (!summary) throw new Error("压缩模型没有返回摘要。");
          const targetSession = resolveSessionTarget(target);
          if (!targetSession) return;
          const now = Date.now();
          targetSession.memory = [targetSession.memory, summary].filter(Boolean).join("\n\n").slice(-12000);
          targetSession.updatedAt = now;
          persistSession(targetSession);
          scrollTargetIfVisible(target);
        } catch (err) {
          error.value = err?.message || "上下文压缩失败。";
        } finally {
          contextCompressing.value = false;
        }
      }

      async function autoCompressContext() {
        // Automatic compaction only refreshes saved memory. Original messages remain intact.
        if (!current.value || contextCompressing.value || loading.value) return;
        const target = sessionTarget(current.value);
        const session = resolveSessionTarget(target, false);
        const messages = session?.messages || [];
        if (messages.length <= 12) return;
        const older = messages.slice(0, Math.max(0, messages.length - 8));
        if (!older.length) return;
        const model = auxiliaryModelFor(session);
        if (!model) return;
        contextCompressing.value = true;
        try {
          const summary = await summarizeCompressionChunks(model, older, "context-compression", 900);
          const targetSession = resolveSessionTarget(target, false);
          if (summary && targetSession) {
            targetSession.memory = summary.slice(0, 20000);
            targetSession.updatedAt = Date.now();
            persistSession(targetSession);
          }
        } catch (cause) {
          error.value = cause?.message || "自动上下文压缩失败，原始消息仍已保留。";
        } finally {
          contextCompressing.value = false;
        }
      }

      function setSyncConflict(sessionId, pending, draft, details) {
        pending.conflicted = true;
        pending.draft = pending.draft || cloneSessionSnapshot(draft);
        pending.details = Array.isArray(details) ? details.map(String).slice(0, 20) : ["同步冲突"];
        const title = String(pending.draft?.title || draft?.title || EMPTY_TITLE).slice(0, 60);
        syncConflicts.value = {
          ...syncConflicts.value,
          [sessionId]: { sessionId, title, details: pending.details },
        };
        error.value = `会话同步冲突：${details.join("、")}。已保留服务器内容；可重新应用修改，或另存本地草稿。`;
      }

      function clearSyncConflict(sessionId) {
        if (!syncConflicts.value[sessionId]) return;
        const next = { ...syncConflicts.value };
        delete next[sessionId];
        syncConflicts.value = next;
      }

      async function loadDbSessions() {
        try {
          const { data } = await axios.get(`${CHAT_API}/sessions-with-messages`);
          if (Array.isArray(data)) {
            const pendingSyncs = loadPendingSessionSyncs();
            const restoredConflicts = Object.fromEntries(Object.entries(pendingSyncs)
              .filter(([, pending]) => pending.conflicted && pending.draft)
              .map(([sessionId, pending]) => [sessionId, {
                sessionId,
                title: String(pending.draft?.title || EMPTY_TITLE).slice(0, 60),
                details: pending.details.length ? pending.details : ["未处理的本地冲突草稿"],
              }]));
            if (Object.keys(restoredConflicts).length) {
              syncConflicts.value = { ...syncConflicts.value, ...restoredConflicts };
              if (!error.value) error.value = "存在未处理的会话同步冲突。请选择另存或放弃相应本地草稿。";
            }
            const pendingIds = new Set(Object.keys(pendingSyncs));
            const localSessions = sessions.value.map(normalizeSession);
            const cachedSessionsById = new Map(localSessions.map((session) => [session.id, session]));
            const localPendingSessions = Object.entries(pendingSyncs)
              .filter(([sessionId]) => pendingIds.has(sessionId) && !deletedSessionIds.has(sessionId))
              .map(([sessionId, pending]) => pending.conflicted && pending.draft
                ? normalizeSession(pending.draft)
                : cachedSessionsById.get(sessionId))
              .filter(Boolean);
            const serverSessions = data.map(normalizeSession);
            serverSessionSnapshots.clear();
            serverSessions.forEach((session) => serverSessionSnapshots.set(session.id, cloneSessionSnapshot(session)));
            if (!data.length) {
              sessions.value = [];
              selectedId.value = "";
            } else {
              sessions.value = serverSessions;
              const localById = new Map(localPendingSessions.map((session) => [session.id, session]));
              const serverIds = new Set(sessions.value.map((session) => session.id));
              sessions.value = sessions.value.map((session) => {
                const local = localById.get(session.id);
                if (!local) return session;
                const pending = pendingSyncs[session.id];
                if (pending?.conflicted) {
                  setSyncConflict(session.id, pending, pending.draft || local,
                    pending.details?.length ? pending.details : ["未处理的本地冲突草稿"]);
                  return session;
                }
                if (!pending?.base) {
                  if (sessionVersionApi.samePersistedContent(local, session)) {
                    delete pendingSyncs[session.id];
                    clearSyncConflict(session.id);
                    return session;
                  }
                  setSyncConflict(session.id, pending, local,
                    ["缺少本地编辑的基线快照，无法安全合并。请重新应用该修改。"]);
                  return session;
                }
                const result = mergeConflictingSession(pending?.base, local, session);
                if (result.conflicts.length) {
                  setSyncConflict(session.id, pending, local, result.conflicts);
                } else {
                  pending.base = cloneSessionSnapshot(session);
                  pending.draft = null;
                  pending.details = [];
                  pending.conflicted = false;
                  clearSyncConflict(session.id);
                }
                return result.session;
              });
              for (const local of localPendingSessions) {
                if (serverIds.has(local.id)) continue;
                const pending = pendingSyncs[local.id];
                if (pending?.base) {
                  setSyncConflict(local.id, pending, local, ["远端会话已删除"]);
                  continue;
                }
                sessions.value.push(local);
              }
            }
            if (!data.length) {
              for (const local of localPendingSessions) {
                const pending = pendingSyncs[local.id];
                if (pending?.base) {
                  setSyncConflict(local.id, pending, local, ["远端会话已删除"]);
                } else {
                  sessions.value.push(local);
                }
              }
            }
            if (!sessions.value.length) {
              const id = uid("chat");
              sessions.value.push({ id, title: EMPTY_TITLE, memory: "", createdAt: Date.now(), updatedAt: Date.now(), messages: [] });
              selectedId.value = id;
            }
            if (!sessions.value.some((item) => item.id === selectedId.value)) {
              selectedId.value = sessions.value[0].id;
            }
            saveSessions(sessions.value);
            savePendingSessionSyncs(pendingSyncs);
            for (const [sessionId, pending] of Object.entries(pendingSyncs)) {
              const local = sessions.value.find((session) => session.id === sessionId);
              if (local && !pending.conflicted && !deletedSessionIds.has(sessionId)) {
                void saveSessionToDb(JSON.parse(JSON.stringify(local)), pending.token, pending.owner);
              }
            }
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
        } catch (cause) {
          error.value = cause?.response?.data?.detail || cause?.message || "无法读取个人设置。";
        }
      }

      async function loadDbGlobalSettings() {
        globalSettings.value = { ...defaultGlobalSettings(), ...(settings.value.modelPolicy || {}) };
        settings.value = { ...settings.value, imageMaxSizeMb: globalSettings.value.imageMaxSizeMb || settings.value.imageMaxSizeMb };
        if (modelName.value !== "default" && !allowedModels.value.some((item) => item.name === modelName.value)) {
          modelName.value = "default";
        }
      }

      async function migrateLegacyStorageIfNeeded() {
        if (readCache(MIGRATION_DISMISSED_KEY) === "true") return;
        try {
          const { data } = await axios.get(`${CHAT_API}/migration/legacy/status`);
          const total = Number(data?.total || 0);
          if (!total) return;
          const ok = window.confirm(`检测到 ${total} 条旧版 AI 聊天数据。是否迁移到新版按用户拆分的存储？迁移完成后会删除旧版对象，避免脏数据残留。`);
          if (!ok) {
            writeCache(MIGRATION_DISMISSED_KEY, "true");
            return;
          }
          const migrated = await axios.post(`${CHAT_API}/migration/legacy`);
          writeCache(MIGRATION_DISMISSED_KEY, "true");
          const warningCount = Array.isArray(migrated.data?.deleteWarnings) ? migrated.data.deleteWarnings.length : 0;
          error.value = `旧版数据迁移完成：会话 ${migrated.data?.sessions || 0}，日志 ${migrated.data?.callLogs || 0}，图片缓存 ${migrated.data?.imageCaches || 0}${warningCount ? `；有 ${warningCount} 项旧对象因 Halo 索引缺失未能删除` : ""}`;
          await loadDbSessions();
          scheduleScrollBottom();
        } catch (cause) {
          error.value = cause?.response?.data?.detail
            || cause?.message
            || "旧数据迁移当前不可用。请在旧版插件环境中导出后再迁移。";
        }
      }

      function queuePersistDb(session = current.value) {
        if (!session || !allowSessionWrite(session.id, false)) return;
        const snapshot = JSON.parse(JSON.stringify(session));
        if (deletedSessionIds.has(snapshot.id)) return;
        const owner = cacheOwner;
        if (!owner) return;
        const previousTimer = persistTimers.get(snapshot.id);
        if (previousTimer) window.clearTimeout(previousTimer);
        const pendingSyncs = loadPendingSessionSyncs();
        const previousPending = pendingSyncs[snapshot.id];
        if (previousPending?.conflicted) {
          error.value = "该会话存在未处理的同步冲突。请先另存或放弃本地草稿。";
          return;
        }
        const token = newSyncToken();
        pendingSyncs[snapshot.id] = {
          token,
          owner,
          updatedAt: Number(snapshot.updatedAt) || Date.now(),
          base: previousPending?.base
            || cloneSessionSnapshot(serverSessionSnapshots.get(snapshot.id))
            || initialSessionBaseline(snapshot),
          draft: null,
          details: [],
          conflicted: false,
        };
        clearSyncConflict(snapshot.id);
        savePendingSessionSyncs(pendingSyncs);
        scheduledPersists.set(snapshot.id, { snapshot, token, owner });
        const timer = window.setTimeout(() => {
          persistTimers.delete(snapshot.id);
          const pending = scheduledPersists.get(snapshot.id);
          scheduledPersists.delete(snapshot.id);
          if (pending && !deletedSessionIds.has(snapshot.id)) {
            void saveSessionToDb(pending.snapshot, pending.token, pending.owner);
          }
        }, 260);
        persistTimers.set(snapshot.id, timer);
      }

      async function persistSessionNow(session = current.value) {
        if (!session || !allowSessionWrite(session.id)) return false;
        const snapshot = cloneSessionSnapshot(session);
        const owner = cacheOwner;
        if (!snapshot || !owner || deletedSessionIds.has(snapshot.id)) return false;
        const timer = persistTimers.get(snapshot.id);
        if (timer) window.clearTimeout(timer);
        persistTimers.delete(snapshot.id);
        scheduledPersists.delete(snapshot.id);
        const pendingSyncs = loadPendingSessionSyncs();
        const previous = pendingSyncs[snapshot.id];
        if (previous?.conflicted) {
          error.value = "该会话存在未处理的同步冲突。请先另存或放弃本地草稿。";
          return false;
        }
        const token = newSyncToken();
        pendingSyncs[snapshot.id] = {
          token,
          owner,
          updatedAt: Number(snapshot.updatedAt) || Date.now(),
          base: previous?.base
            || cloneSessionSnapshot(serverSessionSnapshots.get(snapshot.id))
            || initialSessionBaseline(snapshot),
          draft: null,
          details: [],
          conflicted: false,
        };
        savePendingSessionSyncs(pendingSyncs);
        return saveSessionToDb(snapshot, token, owner);
      }

      function flushPendingPersist() {
        persistTimers.forEach((timer) => window.clearTimeout(timer));
        persistTimers.clear();
        const scheduled = new Map(scheduledPersists);
        scheduledPersists.clear();
        const pendingSyncs = loadPendingSessionSyncs();
        for (const [sessionId, pending] of Object.entries(pendingSyncs)) {
          const item = scheduled.get(sessionId);
          const local = item?.snapshot || sessions.value.find((session) => session.id === sessionId);
          if (local && !pending.conflicted && !deletedSessionIds.has(sessionId)) {
            void saveSessionToDb(JSON.parse(JSON.stringify(local)), pending.token, pending.owner);
          }
        }
      }

      async function saveSessionToDb(session, token, expectedOwner) {
        if (!session?.id || !token || !expectedOwner || expectedOwner !== cacheOwner || deletedSessionIds.has(session.id)) return false;
        try {
          const liveSession = sessions.value.find((item) => item.id === session.id);
          const baseVersion = sessionVersionApi.baseVersion(liveSession, session);
          const { data } = await axios.put(`${CHAT_API}/sessions/${encodeURIComponent(session.id)}/snapshot`, {
            ...session,
            _expectedOwner: expectedOwner,
            _baseVersion: baseVersion,
          });
          if (expectedOwner !== cacheOwner || deletedSessionIds.has(session.id)) return;
          const savedVersion = Math.max(0, Number(data?._version) || 0);
          if (liveSession && savedVersion > Number(liveSession._version || 0)) {
            liveSession._version = savedVersion;
          }
          if (data?.id) {
            serverSessionSnapshots.set(data.id, cloneSessionSnapshot(normalizeSession(data)));
          }
          const pendingSyncs = loadPendingSessionSyncs();
          if (pendingSyncs[session.id]
            && pendingSyncs[session.id].owner === expectedOwner
            && pendingSyncs[session.id].token === token) {
            delete pendingSyncs[session.id];
            savePendingSessionSyncs(pendingSyncs);
          }
          dbReady.value = true;
          return true;
        } catch (err) {
          dbReady.value = false;
          if (err?.response?.status === 409 && !deletedSessionIds.has(session.id)) {
            void loadDbSessions();
          }
          return false;
        }
      }

      function discardPendingPersist(id) {
        const timer = persistTimers.get(id);
        if (timer) window.clearTimeout(timer);
        persistTimers.delete(id);
        scheduledPersists.delete(id);
        const pendingSyncs = loadPendingSessionSyncs();
        if (pendingSyncs[id]) {
          delete pendingSyncs[id];
          savePendingSessionSyncs(pendingSyncs);
        }
      }

      function saveConflictDraftAsNewSession(sessionId) {
        const conflict = syncConflicts.value[sessionId];
        const pending = conflict ? loadPendingSessionSyncs()[sessionId] : null;
        const draft = pending?.draft;
        if (!draft) {
          error.value = "本地冲突草稿已不可用，请重新应用修改。";
          clearSyncConflict(sessionId);
          return;
        }
        const now = Date.now();
        const copy = normalizeSession(cloneSessionSnapshot(draft));
        copy.id = uid("chat");
        copy._version = 0;
        copy.title = `${copy.title || EMPTY_TITLE}（冲突草稿）`.slice(0, 60);
        copy.createdAt = now;
        copy.updatedAt = now;
        sessions.value.unshift(copy);
        selectedId.value = copy.id;
        discardPendingPersist(sessionId);
        clearSyncConflict(sessionId);
        error.value = "已将本地冲突草稿另存为新会话。";
        persist();
      }

      function discardConflictDraft(sessionId) {
        discardPendingPersist(sessionId);
        clearSyncConflict(sessionId);
        error.value = "已放弃本地冲突草稿，服务器会话保持不变。";
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
        persistLocalOnly();
      }

      function appendCallLog(entry) {
        // Authoritative audit records are written by the server. Keep this function as a no-op
        // while an already-open view finishes a job from an older plugin instance.
      }

      function removeSession(id) {
        if (!allowSessionWrite(id)) return;
        deletedSessionIds.add(id);
        discardPendingPersist(id);
        clearSyncConflict(id);
        sessions.value = sessions.value.filter((item) => item.id !== id);
        axios.delete(`${CHAT_API}/sessions/${encodeURIComponent(id)}`).catch(() => {
          deletedSessionIds.delete(id);
          void loadDbSessions();
        });
        if (!sessions.value.length) newSession();
        if (selectedId.value === id) selectedId.value = sessions.value[0].id;
        persistLocalOnly();
      }

      function startRename(session, event) {
        event.stopPropagation();
        if (!allowSessionWrite(session.id)) return;
        renamingId.value = session.id;
        renameValue.value = session.title || "";
      }

      function finishRename(session) {
        if (!allowSessionWrite(session.id)) {
          renamingId.value = "";
          renameValue.value = "";
          return;
        }
        const nextTitle = renameValue.value.trim();
        if (nextTitle) {
          session.title = nextTitle.slice(0, 60);
          session.updatedAt = Date.now();
          persistSession(session);
        }
        renamingId.value = "";
        renameValue.value = "";
      }

      function cancelRename(event) {
        event.stopPropagation();
        renamingId.value = "";
        renameValue.value = "";
      }

      function cleanCurrentSession(session = current.value) {
        if (!session || !allowSessionWrite(session.id)) return false;
        session.messages = (session.messages || []).filter((message) =>
          !(message?.role === "assistant" && message?.status === "error" && !message?.content && !message?.reasoning));
        return true;
      }

      function clearContext() {
        if (!current.value) return;
        if (!allowSessionWrite(current.value.id)) return;
        current.value.contextClearedAt = Date.now();
        current.value.updatedAt = Date.now();
        persist();
      }

      async function addPickedFiles(fileList) {
        if (!current.value || !allowSessionWrite(current.value.id)) return;
        const allFiles = Array.from(fileList || []);
        const supported = allFiles.filter((file) => isTextLikeFile(file) || isSupportedImageFile(file));
        const unsupported = allFiles.filter((file) => !supported.includes(file));
        if (unsupported.length) {
          error.value = `不支持 ${unsupported.map((file) => file.name).slice(0, 3).join("、")}。图片仅支持 PNG 或 JPEG；文本支持 txt、md、json、csv、log、xml、yaml 等格式。`;
        }
        const maxTextBytes = 256 * 1024;
        const maxTextTotalBytes = 512 * 1024;
        let remainingBytes = maxTextTotalBytes;
        const snippets = [];
        for (const file of supported.filter(isTextLikeFile).slice(0, 6)) {
          if (remainingBytes <= 0) break;
          const bytes = Math.min(maxTextBytes, remainingBytes, Math.max(0, Number(file.size) || 0));
          if (!bytes) continue;
          const text = await file.slice(0, bytes).text();
          const suffix = file.size > bytes ? "\n[文件内容已截断]" : "";
          snippets.push(`\n\n--- FILE: ${file.name} ---\n${text}${suffix}`);
          remainingBytes -= bytes;
        }
        if (snippets.length) input.value = `${input.value || ""}${snippets.join("\n")}`.slice(0, 90000);
        const picked = allFiles.filter(isSupportedImageFile);
        if (!picked.length) return;
        const remainingSlots = Math.max(0, MAX_UPLOAD_IMAGES - files.value.length);
        if (!remainingSlots || picked.length > remainingSlots) {
          error.value = `单条消息最多添加 ${MAX_UPLOAD_IMAGES} 张图片。`;
          return;
        }
        const maxBytes = Math.max(1, Number(settings.value.imageMaxSizeMb) || 8) * 1024 * 1024;
        const oversized = picked.find((file) => file.size > maxBytes);
        if (oversized) {
          error.value = `Image ${oversized.name} exceeds ${Math.round(maxBytes / 1024 / 1024)} MB.`;
          return;
        }
        const next = [];
        for (const image of picked) next.push(await uploadImageToAttachment(image));
        files.value = [...files.value, ...next];
      }

      async function summarizeConversation() {
        if (!current.value || contextCompressing.value) return;
        if (!allowSessionWrite(current.value.id)) return;
        const target = sessionTarget(current.value);
        const session = resolveSessionTarget(target);
        if (!session) return;
        const messages = (session.messages || []).filter((message) => message.role === "user" || message.role === "assistant");
        if (!messages.length) {
          window.alert("当前对话还没有可摘要的内容。");
          return;
        }
        const model = auxiliaryModelFor(session);
        if (!model) {
          error.value = "没有可复用当前对话模型的语言或多模态模型，未发送摘要请求。";
          return;
        }
        contextCompressing.value = true;
        error.value = "";
        try {
          const summary = await summarizeCompressionChunks(model, messages.slice(-40), "conversation-summary", 1000);
          if (!summary) throw new Error("摘要模型没有返回内容。");
          const targetSession = resolveSessionTarget(target);
          if (!targetSession) return;
          const message = { id: uid("summary"), role: "assistant", content: `## 对话摘要\n\n${summary}`, createdAt: Date.now(), tags: ["summary"] };
          targetSession.messages.push(message);
          targetSession.tags = Array.from(new Set([...(targetSession.tags || []), "summary"])).slice(0, 12);
          targetSession.updatedAt = Date.now();
          persistSession(targetSession);
          scrollTargetIfVisible(target);
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

      function isSupportedImageFile(file) {
        const type = String(file?.type || "").toLowerCase();
        const name = String(file?.name || "").toLowerCase();
        return type === "image/png" || type === "image/jpeg" || /\.(png|jpe?g)$/i.test(name);
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
            throw new Error("附件上传成功，但没有返回可用的访问地址。");
          }
        }
        return {
          name: file.name,
          mediaType: spec.mediaType || data?.mediaType || "image/png",
          url,
          size: file.size,
          attachmentName: data?.metadata?.name || data?.attachment?.metadata?.name,
        };
      }

      async function chooseFiles(event) {
        try {
          await addPickedFiles(event.target.files);
        } catch (err) {
          error.value = err?.response?.data?.detail || err?.message || "图片上传失败，请检查文件后重试。";
        }
        event.target.value = "";
      }

      function handleDragOver(event) {
        event.preventDefault();
        if (!current.value || !allowSessionWrite(current.value.id, false)) return;
        dragActive.value = true;
      }

      function handleDragLeave(event) {
        if (!event.currentTarget.contains(event.relatedTarget)) dragActive.value = false;
      }

      async function handleDrop(event) {
        event.preventDefault();
        dragActive.value = false;
        if (!current.value || !allowSessionWrite(current.value.id)) return;
        try {
          await addPickedFiles(event.dataTransfer?.files);
        } catch (err) {
          error.value = err?.response?.data?.detail || err?.message || "图片上传失败，请检查文件后重试。";
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

      function toUiMessages(messages, contextClearedAt, session = current.value) {
        const valid = messages.filter((item) => {
          if (item.role !== "user" && item.role !== "assistant") return false;
          if (contextClearedAt && (item.createdAt || 0) < contextClearedAt) return false;
          if (item.role === "assistant" && ["error", "cancelled", "interrupted"].includes(String(item.status || ""))) return false;
          const text = String(item.content || "").trim();
          const hasFiles = Array.isArray(item.files) && item.files.length > 0;
          return text || hasFiles;
        });
        const latestUserIndex = valid.map((item) => item.role).lastIndexOf("user");
        const scoped = latestUserIndex >= 0 ? valid.slice(Math.max(0, latestUserIndex - 6)) : valid.slice(-8);
        const mapped = scoped.map((item) => {
          const content = typeof item.content === "string" ? item.content.trim() : "";
          const parts = [
            ...(content ? [{ type: "text", id: uid("text"), text: item.content }] : []),
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
                attachmentName: file.attachmentName,
              };
            })),
            ...((item.images || [])
              .filter((image) => String(image || "").startsWith("data:image/"))
              .slice(0, 4)
              .map((image) => ({
                type: "file",
                fileId: uid("generated-image"),
                mediaType: /^data:(image\/[^;,]+)/i.exec(String(image))?.[1] || "image/png",
                data: image,
                title: "生成图片",
              }))),
          ];
          return { id: item.id, role: item.role, parts };
        }).filter((item) => item.parts.length > 0);
        const memory = [settings.value.memoryText, session?.memory].filter(Boolean).join("\n\n").trim();
        if (memory) {
          mapped.unshift({
            id: uid("memory"),
            role: "user",
            parts: [{
              type: "text",
              id: uid("memory-text"),
              text: `以下是长期记忆和知识引用。请在相关时使用这些事实，但除非确有帮助，否则不要主动提及它们：\n\n${memory}`,
            }],
          });
        }
        return mapped;
      }

      async function generateSessionTitle(prompt, target, expectedTitle, primaryModel) {
        const session = resolveSessionTarget(target, false);
        if (!session || session.title !== expectedTitle) return;
        const selectedLanguageModel = [...languageModels.value, ...multimodalModels.value]
          .find((item) => item.name === primaryModel);
        const model = selectedLanguageModel?.name;
        if (!model) return;
        try {
          const result = await generateText(model, [{
                id: uid("title-user"),
                role: "user",
                parts: [{ type: "text", id: uid("title-text"), text: `请为下面这段对话生成一个不超过 12 个汉字的标题，只输出标题：\n${prompt}` }],
              }], 64, "title-generation");
          const title = String(result.text || "").replace(/["“”'。.\n\r]/g, "").trim();
          const targetSession = resolveSessionTarget(target, false);
          if (title && targetSession?.title === expectedTitle) {
            targetSession.title = title.slice(0, 24);
            targetSession.updatedAt = Date.now();
            persistSession(targetSession);
          }
        } catch (_) {}
      }

      function activeJobsForSession(sessionId) {
        return activeJobsBySession.value[sessionId] || [];
      }

      function sessionIsBusy(sessionId) {
        return pendingJobSessions.has(sessionId) || activeJobsForSession(sessionId).length > 0;
      }

      function observerDetachedError() {
        return new DOMException("聊天页面已离开，已停止本地任务观察。", "ObserverDetachedError");
      }

      function isRecoverableJobTransportError(cause) {
        const status = Number(cause?.response?.status || 0);
        return !status || status >= 500 || [408, 429].includes(status);
      }

      function waitForJobRetry(milliseconds) {
        return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
      }

      function syncSelectedJobState() {
        const jobs = activeJobsForSession(selectedId.value);
        const active = jobs[jobs.length - 1];
        loading.value = !!active || pendingJobSessions.has(selectedId.value);
        activeJobId.value = active?.id || "";
        activeAssistantId.value = active?.assistantId || "";
        activeSessionId.value = active?.sessionId || "";
        abortController.value = active?.controller || null;
      }

      function trackActiveJob(job, controller) {
        if (!job?.id || !job?.sessionId || !job?.assistantId) return;
        const existing = activeJobsForSession(job.sessionId).filter((item) => item.id !== job.id);
        activeJobsBySession.value = {
          ...activeJobsBySession.value,
          [job.sessionId]: [...existing, {
            id: job.id,
            sessionId: job.sessionId,
            assistantId: job.assistantId,
            controller,
          }],
        };
        syncSelectedJobState();
      }

      function releaseActiveJob(sessionId, jobId) {
        const remaining = activeJobsForSession(sessionId).filter((item) => item.id !== jobId);
        const next = { ...activeJobsBySession.value };
        if (remaining.length) next[sessionId] = remaining;
        else delete next[sessionId];
        activeJobsBySession.value = next;
        syncSelectedJobState();
      }

      function controllerForJob(jobId) {
        for (const jobs of Object.values(activeJobsBySession.value)) {
          const matching = jobs.find((job) => job.id === jobId);
          if (matching) return matching.controller;
        }
        return recoveryJobControllers.get(jobId) || null;
      }

      function applyJobUpdate(job, target, assistantId, persistMode) {
        const session = resolveSessionTarget(target);
        if (!session) return false;
        const assistant = (session.messages || []).find((message) => message.id === assistantId);
        if (!assistant) return false;
        if (job.content !== undefined && job.content !== null) assistant.content = job.content;
        if (job.reasoning !== undefined && job.reasoning !== null) assistant.reasoning = job.reasoning;
        assistant.reasoningOpen = !!job.reasoningOpen;
        if (Array.isArray(job.images)) assistant.images = job.images;
        assistant.promptTokens = job.promptTokens || assistant.promptTokens || 0;
        assistant.completionTokens = job.completionTokens || 0;
        assistant.totalTokens = job.totalTokens || ((assistant.promptTokens || 0) + (assistant.completionTokens || 0));
        assistant.status = job.status || assistant.status;
        if (job.type && job.model) {
          assistant.generation = { type: String(job.type), model: String(job.model) };
        }
        assistant.streaming = jobStatusApi.kind(job.status) === "active";
        assistant.updatedAt = job.updatedAt || assistant.updatedAt || Date.now();
        if (sessionVersionApi.applyServerVersion(session, job.sessionVersion)) {
          serverSessionSnapshots.set(session.id, cloneSessionSnapshot(normalizeSession(session)));
        }
        session.updatedAt = Date.now();
        if (jobStatusApi.kind(job.status) === "success") {
          applyAutoTags(assistant, session);
          applySessionTags(session);
        }
        if (persistMode === "stream") {
          persistStreaming(session);
        } else if (persistMode === "local") {
          persistLocalOnly();
        } else {
          flushStreamingPersist(session);
        }
        scrollTargetIfVisible(target);
        return true;
      }

      function terminalJobError(job) {
        if (jobStatusApi.normalize(job?.status) === "interrupted") {
          return "任务因 Halo 重启或插件重载中断，请重新生成。";
        }
        return job?.error || "AI 生成失败，请稍后重试。";
      }

      function markAssistantTerminal(target, assistantId, status, content) {
        const session = resolveSessionTarget(target, false);
        const assistant = session?.messages?.find((message) => message.id === assistantId);
        if (!assistant) return;
        assistant.status = status;
        assistant.streaming = false;
        assistant.reasoningOpen = false;
        if (content) assistant.content = content;
        assistant.updatedAt = Date.now();
        session.updatedAt = Date.now();
        persistSession(session);
      }

      async function pollChatJobLegacy(jobId, target, assistantId, controller = abortController.value) {
        let retryDelay = 800;
        for (;;) {
          if (controller?.signal?.aborted) {
            throw controller.signal.reason || new DOMException("Aborted", "AbortError");
          }
          let job;
          try {
            ({ data: job } = await axios.get(`${CHAT_API}/jobs/${encodeURIComponent(jobId)}`));
            retryDelay = 800;
          } catch (cause) {
            if (!isRecoverableJobTransportError(cause)) throw cause;
            await waitForJobRetry(retryDelay);
            retryDelay = Math.min(retryDelay * 2, 10_000);
            continue;
          }
          applyJobUpdate(job, target, assistantId, jobStatusApi.kind(job.status) === "active" ? "stream" : "final");
          const kind = jobStatusApi.kind(job.status);
          if (kind === "success") {
            return job;
          }
          if (kind === "failure" || kind === "unknown") {
            throw new Error(terminalJobError(job));
          }
          if (kind === "cancelled") {
            throw new DOMException("Aborted", "AbortError");
          }
          await waitForJobRetry(800);
        }
      }

      async function pollChatJob(jobId, target, assistantId, controller = abortController.value) {
        if (!window.EventSource) return pollChatJobLegacy(jobId, target, assistantId, controller);
        return new Promise((resolve, reject) => {
          let settled = false;
          let received = false;
          const source = new EventSource(`${CHAT_API}/jobs/${encodeURIComponent(jobId)}/events`);
          const cleanup = () => {
            source.close();
            controller?.signal?.removeEventListener("abort", abortHandler);
          };
          const finish = (callback, value) => {
            if (settled) return;
            settled = true;
            cleanup();
            callback(value);
          };
          const abortHandler = () => finish(reject,
            controller?.signal?.reason || new DOMException("Aborted", "AbortError"));
          const handleJob = (event) => {
            received = true;
            let job;
            try {
              job = JSON.parse(event.data || "{}");
            } catch (err) {
              finish(reject, err);
              return;
            }
            applyJobUpdate(job, target, assistantId, jobStatusApi.kind(job.status) === "active" ? "stream" : "final");
            const kind = jobStatusApi.kind(job.status);
            if (kind === "success") {
              finish(resolve, job);
            } else if (kind === "failure" || kind === "unknown") {
              finish(reject, new Error(terminalJobError(job)));
            } else if (kind === "cancelled") {
              finish(reject, new DOMException("Aborted", "AbortError"));
            }
          };
          source.addEventListener("job", handleJob);
          source.onerror = () => {
            if (settled) return;
            cleanup();
            if (received) {
              pollChatJobLegacy(jobId, target, assistantId, controller).then(resolve, reject);
            } else {
              pollChatJobLegacy(jobId, target, assistantId, controller).then(resolve, reject);
            }
          };
          controller?.signal?.addEventListener("abort", abortHandler, { once: true });
        });
      }

      async function confirmCancellation(jobId, target, assistantId) {
        const applyAuthoritativeState = (job) => {
          if (!job) return "unknown";
          applyJobUpdate(job, target, assistantId, jobStatusApi.kind(job.status) === "active" ? "stream" : "final");
          return jobStatusApi.kind(job.status);
        };
        try {
          const { data } = await axios.post(`${CHAT_API}/jobs/${encodeURIComponent(jobId)}/cancel`);
          const disposition = jobStatusApi.cancellationDisposition(data?.status);
          applyAuthoritativeState(data);
          if (disposition === "confirmed") {
            controllerForJob(jobId)?.abort();
            return true;
          }
        } catch (cause) {
          if (cause?.response?.status === 409) {
            try {
              const { data } = await axios.get(`${CHAT_API}/jobs/${encodeURIComponent(jobId)}`);
              const disposition = jobStatusApi.cancellationDisposition(data?.status);
              applyAuthoritativeState(data);
              if (disposition === "confirmed") {
                controllerForJob(jobId)?.abort();
                return true;
              }
              if (disposition === "terminal") return false;
            } catch (refreshCause) {
              error.value = refreshCause?.response?.data?.detail || "无法确认任务终态，仍会继续等待服务器结果。";
              return false;
            }
          } else {
            error.value = cause?.response?.data?.detail || "取消请求未得到服务器确认，仍会继续等待服务器结果。";
            return false;
          }
        }
        error.value = "服务器未确认任务已取消，仍会继续等待服务器结果。";
        return false;
      }

      async function recoverPersistedJobs() {
        try {
          const { data: jobs } = await axios.get(`${CHAT_API}/jobs/recoverable`);
          if (!Array.isArray(jobs)) return;
          for (const job of jobs) {
            const target = sessionTarget(sessions.value.find((session) => session.id === job?.sessionId));
            if (!target || !job?.assistantId) continue;
            applyJobUpdate(job, target, job.assistantId,
              jobStatusApi.kind(job.status) === "active" ? "stream" : "local");
          }
          for (const job of jobStatusApi.activeJobs(jobs)) {
            if (recoveryJobControllers.has(job.id)) continue;
            const target = sessionTarget(sessions.value.find((session) => session.id === job.sessionId));
            if (!target || !job.assistantId) continue;
            const controller = new AbortController();
            recoveryJobControllers.set(job.id, controller);
            trackActiveJob(job, controller);
            void pollChatJob(job.id, target, job.assistantId, controller)
              .catch((cause) => {
                if (cause?.name !== "AbortError") {
                  error.value = cause?.message || "任务状态恢复失败，正在等待下次连接后重试。";
                }
              })
              .finally(() => {
                recoveryJobControllers.delete(job.id);
                releaseActiveJob(job.sessionId, job.id);
                cancellationRequestedSessions.delete(job.sessionId);
              });
          }
        } catch (cause) {
          error.value = cause?.response?.data?.detail || cause?.message || "无法恢复后台任务状态。";
        }
      }

      async function sendChat(target = sessionTarget(current.value), requestedModel = "", lifecycle = {}) {
        const session = resolveSessionTarget(target);
        if (!session) return;
        const startedAt = Date.now();
        cleanCurrentSession(session);
        const requestMessages = toUiMessages(session.messages, session.contextClearedAt || 0, session);
        if (!requestMessages.length) throw new Error("没有可发送的有效消息，请重新输入内容后再试。");
        const hasImageInput = requestMessages.some((message) => message.parts?.some((part) =>
          part.type === "file" && String(part.mediaType || "").startsWith("image/")));
        const model = requestedModel || resolveModel("chat", hasImageInput);
        const assistant = {
          id: uid("assistant"), role: "assistant", content: "", reasoning: "", reasoningOpen: true,
          generation: { type: "chat", model }, createdAt: Date.now(),
        };
        session.messages.push(assistant);
        if (target.sessionId === selectedId.value) activeAssistantId.value = assistant.id;
        const controller = new AbortController();
        pendingJobSessions.add(target.sessionId);
        syncSelectedJobState();
        lifecycle.assistantId = assistant.id;
        lifecycle.controller = controller;
        if (target.sessionId === selectedId.value) abortController.value = controller;
        const promptTokens = requestMessages.reduce((sum, item) => sum + item.parts.reduce((partSum, part) => partSum + estimateTokens(part.text || part.title || ""), 0), 0);
        assistant.promptTokens = promptTokens;
        assistant.streaming = true;
        if (!await persistSessionNow(session)) {
          pendingJobSessions.delete(target.sessionId);
          syncSelectedJobState();
          throw new Error("会话保存失败，请刷新页面后重试。");
        }
        let job;
        try {
          ({ data: job } = await axios.post(`${CHAT_API}/jobs/chat`, {
            id: uid("job"),
            model,
            requestMessages,
            assistant,
            promptTokens,
            session,
          }));
        } catch (cause) {
          pendingJobSessions.delete(target.sessionId);
          syncSelectedJobState();
          throw cause;
        }
        pendingJobSessions.delete(target.sessionId);
        lifecycle.jobId = job.id;
        lifecycle.jobCreated = true;
        trackActiveJob({ ...job, sessionId: target.sessionId, assistantId: assistant.id }, controller);
        try {
          if (cancellationRequestedSessions.has(target.sessionId)) {
            if (await confirmCancellation(job.id, target, assistant.id)) {
              throw new DOMException("Aborted", "AbortError");
            }
          }
          await pollChatJob(job.id, target, assistant.id, controller);
        } finally {
          releaseActiveJob(target.sessionId, job.id);
        }
        const targetSession = resolveSessionTarget(target, false);
        const completedAssistant = targetSession?.messages?.find((message) => message.id === assistant.id);
        if (completedAssistant) {
          completedAssistant.streaming = false;
          completedAssistant.completionTokens = estimateTokens(`${completedAssistant.reasoning}\n${completedAssistant.content}`);
          completedAssistant.totalTokens = (completedAssistant.promptTokens || 0) + (completedAssistant.completionTokens || 0);
          appendCallLog({ type: "chat", model, status: "success", durationMs: Date.now() - startedAt, promptTokens: completedAssistant.promptTokens, completionTokens: completedAssistant.completionTokens, totalTokens: completedAssistant.totalTokens });
        }
        return { type: "chat", model };
      }

      async function sendImage(prompt, attachedFiles, target = sessionTarget(current.value), requestedModel = "", lifecycle = {}) {
        const session = resolveSessionTarget(target);
        if (!session) return;
        const model = requestedModel || resolveModel("image");
        const startedAt = Date.now();
        const payload = {
          prompt,
          inputImages: attachedFiles.map((file) => ({
            data: file.data || (String(file.url || "").startsWith("data:") ? file.url : undefined),
            url: (file.data || String(file.url || "").startsWith("data:")) ? undefined : file.url,
            mediaType: file.mediaType,
            filename: file.name,
            attachmentName: file.attachmentName,
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
          generation: { type: "image", model },
        };
        session.messages.push(assistant);
        if (target.sessionId === selectedId.value) activeAssistantId.value = assistant.id;
        const controller = new AbortController();
        pendingJobSessions.add(target.sessionId);
        syncSelectedJobState();
        lifecycle.assistantId = assistant.id;
        lifecycle.controller = controller;
        if (target.sessionId === selectedId.value) abortController.value = controller;
        if (!await persistSessionNow(session)) {
          pendingJobSessions.delete(target.sessionId);
          syncSelectedJobState();
          throw new Error("会话保存失败，请刷新页面后重试。");
        }
        let job;
        try {
          ({ data: job } = await axios.post(`${CHAT_API}/jobs/image`, {
            id: uid("job"),
            model,
            prompt,
            payload,
            assistant,
            promptTokens: assistant.promptTokens,
            session,
          }));
        } catch (cause) {
          pendingJobSessions.delete(target.sessionId);
          syncSelectedJobState();
          throw cause;
        }
        pendingJobSessions.delete(target.sessionId);
        lifecycle.jobId = job.id;
        lifecycle.jobCreated = true;
        trackActiveJob({ ...job, sessionId: target.sessionId, assistantId: assistant.id }, controller);
        try {
          if (cancellationRequestedSessions.has(target.sessionId)) {
            if (await confirmCancellation(job.id, target, assistant.id)) {
              throw new DOMException("Aborted", "AbortError");
            }
          }
          await pollChatJob(job.id, target, assistant.id, controller);
        } finally {
          releaseActiveJob(target.sessionId, job.id);
        }
        const targetSession = resolveSessionTarget(target, false);
        const completedAssistant = targetSession?.messages?.find((message) => message.id === assistant.id);
        if (targetSession && completedAssistant) {
          completedAssistant.streaming = false;
          targetSession.updatedAt = Date.now();
          persistSession(targetSession);
          appendCallLog({ type: "image", model, status: completedAssistant.images?.length ? "success" : "empty", durationMs: Date.now() - startedAt, promptTokens: completedAssistant.promptTokens, completionTokens: 0, totalTokens: completedAssistant.totalTokens });
        }
        return { type: "image", model };
      }

      async function send() {
        const prompt = input.value.trim();
        if (!prompt || loading.value) return;
        if (!current.value || !allowSessionWrite(current.value.id)) return;
        const target = sessionTarget(current.value);
        const session = resolveSessionTarget(target);
        if (!session) return;
        error.value = "";
        loading.value = true;
        activeSessionId.value = target.sessionId;
        const attachedFiles = files.value.slice();
        input.value = "";
        files.value = [];
        session.messages.push({ id: uid("user"), role: "user", content: prompt, files: attachedFiles, createdAt: Date.now() });
        const shouldGenerateTitle = session.title === EMPTY_TITLE;
        const provisionalTitle = prompt.slice(0, 24);
        if (shouldGenerateTitle) session.title = provisionalTitle;
        session.updatedAt = Date.now();
        scrollTargetIfVisible(target);
        const lifecycle = {};
        try {
          const wantsImage = mode.value === "image" || /^\/image\b/i.test(prompt);
          const generation = wantsImage
            ? await sendImage(prompt.replace(/^\/image\b/i, "").trim() || prompt, attachedFiles, target, "", lifecycle)
            : await sendChat(target, "", lifecycle);
          if (shouldGenerateTitle && generation?.type !== "image") {
            void generateSessionTitle(prompt, target, provisionalTitle, generation?.model);
          }
        } catch (err) {
          if (err?.name === "ObserverDetachedError") {
            lifecycle.observerDetached = true;
            return;
          }
          if (err?.name === "AbortError") {
            markAssistantTerminal(target, lifecycle.assistantId, "cancelled", "已停止生成。");
            return;
          }
          const message = err?.response?.data?.detail || err?.response?.data?.message || err.message || "AI 调用失败";
          error.value = message;
          markAssistantTerminal(target, lifecycle.assistantId, "error", `调用失败：${message}`);
        } finally {
          cancellationRequestedSessions.delete(target.sessionId);
          if (!lifecycle.observerDetached) {
            const targetSession = resolveSessionTarget(target, false);
            if (targetSession) {
              targetSession.updatedAt = Date.now();
              persistSession(targetSession);
            }
            scrollTargetIfVisible(target);
          }
          syncSelectedJobState();
        }
      }

      async function stopGeneration() {
        if (current.value?.id) cancellationRequestedSessions.add(current.value.id);
        const jobs = currentActiveJobs.value.slice();
        if (jobs.length) {
          const target = sessionTarget(current.value);
          if (target) await Promise.all(jobs.map((job) => confirmCancellation(job.id, target, job.assistantId)));
        } else {
          error.value = "正在等待服务器创建任务；创建完成后会请求取消。";
        }
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
        const name = `${current.value?.title || "AI回复"}-${message.id || Date.now()}.md`.replace(/[\\/:*?"<>|]/g, "-");
        const text = [message.reasoning ? `## 思考过程\n\n${message.reasoning}` : "", `## 回复\n\n${message.content || ""}`].filter(Boolean).join("\n\n");
        downloadText(name, text);
      }

      function toggleFavorite(ownerSession, sourceMessage) {
        const session = sourceMessage ? ownerSession : current.value;
        const message = sourceMessage || ownerSession;
        if (!session || !message || !allowSessionWrite(session.id)) return;
        const target = message._sourceMessage || message;
        const previousFavorite = !!target.favorite;
        target.favorite = !previousFavorite;
        target.updatedAt = Date.now();
        session.updatedAt = Date.now();
        void persistSessionNow(session).then((saved) => {
          if (saved) return;
          target.favorite = previousFavorite;
          target.updatedAt = Date.now();
          session.updatedAt = Date.now();
          error.value = "收藏状态保存失败，已恢复原状态。";
        });
      }

      function applyAutoTags(message, session) {
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
        if (session) session.tags = Array.from(new Set([...(session.tags || []), ...tags])).slice(0, 12);
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

      async function retryMessage(ownerSession, sourceMessage) {
        const session = ownerSession || current.value;
        const message = sourceMessage || ownerSession;
        if (!session || !message || !allowSessionWrite(session.id)) return;
        const messages = session.messages || [];
        const index = messages.findIndex((item) => item.id === message.id);
        if (index < 0) return;
        const user = messages.slice(0, index).reverse().find((item) => item.role === "user");
        if (!user || sessionIsBusy(session.id)) return;
        await regenerateFromUserMessage(user, session, message.generation);
      }

      async function regenerateFromUserMessage(message, ownerSession = current.value, originalGeneration) {
        if (!ownerSession || sessionIsBusy(ownerSession.id)) return;
        if (!allowSessionWrite(ownerSession.id)) return;
        const target = sessionTarget(ownerSession);
        const session = resolveSessionTarget(target);
        if (!session) return;
        const messages = session.messages || [];
        const index = messages.indexOf(message);
        if (index < 0) return;
        const generation = originalGeneration?.type && originalGeneration?.model
          ? originalGeneration
          : null;
        if (!generation && !window.confirm("这条历史回复没有保存模型信息。将使用当前模式和模型重新生成，是否继续？")) {
          return;
        }
        const originalMessages = messages.slice();
        session.messages = messages.slice(0, index + 1);
        session.updatedAt = Date.now();
        persistSession(session);
        scrollTargetIfVisible(target);
        loading.value = true;
        activeSessionId.value = target.sessionId;
        error.value = "";
        const prompt = message.content || "";
        const attachedFiles = Array.isArray(message.files) ? message.files : [];
        const lifecycle = {};
        try {
          const wantsImage = generation?.type === "image"
            || (!generation && (mode.value === "image" || /^\/image\b/i.test(prompt)));
          if (wantsImage) {
            await sendImage(prompt.replace(/^\/image\b/i, "").trim() || prompt, attachedFiles, target,
              generation?.model, lifecycle);
          } else {
            await sendChat(target, generation?.model, lifecycle);
          }
        } catch (err) {
          if (!lifecycle.jobCreated) {
            session.messages = originalMessages;
            session.updatedAt = Date.now();
            persistSession(session);
          }
          if (err?.name === "ObserverDetachedError") {
            lifecycle.observerDetached = true;
            return;
          }
          if (err?.name === "AbortError") {
            markAssistantTerminal(target, lifecycle.assistantId, "cancelled", "已停止生成。");
            return;
          }
          const messageText = err?.response?.data?.detail || err?.response?.data?.message || err.message || "AI 调用失败";
          error.value = messageText;
          markAssistantTerminal(target, lifecycle.assistantId, "error", `调用失败：${messageText}`);
        } finally {
          cancellationRequestedSessions.delete(target.sessionId);
          if (!lifecycle.observerDetached) {
            const targetSession = resolveSessionTarget(target, false);
            if (targetSession) {
              targetSession.updatedAt = Date.now();
              persistSession(targetSession);
            }
            scrollTargetIfVisible(target);
          }
          syncSelectedJobState();
        }
      }

      function startEditMessage(ownerSession, sourceMessage) {
        const session = sourceMessage ? ownerSession : current.value;
        const message = sourceMessage || ownerSession;
        if (!session || !message || !allowSessionWrite(session.id)) return;
        editingMessageId.value = message.id;
        editingMessageValue.value = message.content || "";
      }

      async function saveEditMessage(ownerSession, sourceMessage, regenerate) {
        const session = sourceMessage ? ownerSession : current.value;
        const message = sourceMessage || ownerSession;
        if (!session || !message || !allowSessionWrite(session.id)) return;
        const next = editingMessageValue.value.trim();
        if (!next) return;
        message.content = next;
        message.updatedAt = Date.now();
        editingMessageId.value = "";
        editingMessageValue.value = "";
        session.updatedAt = Date.now();
        persistSession(session);
        if (regenerate) {
          const index = (session.messages || []).findIndex((item) => item.id === message.id);
          const originalGeneration = index < 0 ? null
            : session.messages.slice(index + 1).find((item) => item.role === "assistant")?.generation;
          await regenerateFromUserMessage(message, session, originalGeneration);
        }
      }

      let resizeObserver;
      let loadingOlderMessages = false;
      let identityCheck = null;
      const clearSensitiveChatView = () => {
        sessions.value = [];
        selectedId.value = "";
        callLogs.value = [];
        settings.value = defaultPersonalSettings();
        globalSettings.value = defaultGlobalSettings();
        sidebarCollapsed.value = false;
        input.value = "";
        files.value = [];
        dbReady.value = false;
        syncConflicts.value = {};
        serverSessionSnapshots.clear();
      };
      const hydrateCurrentOwner = async ({ resetTransient = false } = {}) => {
        sessions.value = loadSessions();
        selectedId.value = readCache(SELECTED_KEY) || "";
        settings.value = loadSettings();
        callLogs.value = loadCallLogs();
        sidebarCollapsed.value = readCache(SIDEBAR_KEY) === "true";
        if (resetTransient) {
          input.value = "";
          files.value = [];
        }
        error.value = "";
        if (!sessions.value.length) {
          const id = uid("chat");
          sessions.value = [{ id, title: EMPTY_TITLE, memory: "", createdAt: Date.now(), updatedAt: Date.now(), messages: [] }];
          selectedId.value = id;
        }
        await loadDbSettings();
        await loadDbGlobalSettings();
        await loadDbSessions();
        await recoverPersistedJobs();
        if (cacheCapabilities.canViewAllLogs) void migrateLegacyStorageIfNeeded();
      };
      const checkCurrentOwner = async () => {
        if (identityCheck) return identityCheck;
        identityCheck = activateOwnerCache()
          .then(({ changed }) => {
            if (changed) clearSensitiveChatView();
            return hydrateCurrentOwner({ resetTransient: changed });
          })
          .catch((cause) => {
            if (isAuthenticationFailure(cause)) {
              // Login loss must hide the old owner's data without deleting recoverable owner-scoped cache.
              clearSensitiveChatView();
              error.value = "登录已失效，已隐藏本地聊天数据。请重新登录。";
              return;
            }
            // A transient identity failure must not erase offline drafts or pending recovery data.
            error.value = cause?.response?.data?.detail || cause?.message || "无法确认当前登录用户。";
          })
          .finally(() => { identityCheck = null; });
        return identityCheck;
      };
      const handleReturnToView = () => {
        if (!document.hidden) {
          checkCurrentOwner();
          scheduleScrollBottom();
        }
      };
      const clearCacheOnPageHide = () => flushPendingPersist();

      onMounted(() => {
        checkCurrentOwner()
          .then(() => refreshModels())
          .then(scheduleScrollBottom)
          .catch((cause) => {
            error.value = cause?.response?.data?.detail || cause?.message || "无法读取当前用户的聊天数据。";
            dbReady.value = false;
          });
        scheduleScrollBottom();
        window.addEventListener("focus", handleReturnToView);
        window.addEventListener("pageshow", handleReturnToView);
        window.addEventListener("pagehide", clearCacheOnPageHide);
        document.addEventListener("visibilitychange", handleReturnToView);
        if (window.ResizeObserver) {
          resizeObserver = new ResizeObserver(scheduleSoftScrollBottom);
          nextTick(() => {
            if (chatEl.value) resizeObserver.observe(chatEl.value);
          });
        }
      });
      if (onActivated) onActivated(() => checkCurrentOwner().then(scheduleScrollBottom));
      if (onBeforeUnmount) onBeforeUnmount(() => {
        flushPendingPersist();
        for (const controller of recoveryJobControllers.values()) controller.abort(observerDetachedError());
        for (const jobs of Object.values(activeJobsBySession.value)) {
          for (const job of jobs) job.controller?.abort(observerDetachedError());
        }
        recoveryJobControllers.clear();
        activeJobsBySession.value = {};
        pendingJobSessions.clear();
        cancellationRequestedSessions.clear();
        window.removeEventListener("focus", handleReturnToView);
        window.removeEventListener("pageshow", handleReturnToView);
        window.removeEventListener("pagehide", clearCacheOnPageHide);
        document.removeEventListener("visibilitychange", handleReturnToView);
        resizeObserver?.disconnect();
      });
      watch(selectedId, () => {
        syncSelectedJobState();
        settings.value = loadSettings();
        loadDbSettings();
        visibleMessageCount.value = Number(settings.value.lazyBatchSize) || 60;
        persistLocalOnly();
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
        const messageSessionId = message._sessionId || current.value?.id;
        const messageWriteBlocked = !!(messageSessionId && syncConflicts.value[messageSessionId]);
        const ownerSession = sessions.value.find((session) => session.id === messageSessionId) || current.value;
        const sourceMessage = message._sourceMessage || message;
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
                    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) saveEditMessage(ownerSession, sourceMessage);
                    if (event.key === "Escape") editingMessageId.value = "";
                  },
                }),
                h("div", { class: "message-actions" }, [
                  h("button", { disabled: messageWriteBlocked, onClick: () => saveEditMessage(ownerSession, sourceMessage) }, "保存"),
                  h("button", { disabled: sessionIsBusy(ownerSession.id) || messageWriteBlocked, onClick: () => saveEditMessage(ownerSession, sourceMessage, true) }, "保存并重新生成"),
                  h("button", { onClick: () => { editingMessageId.value = ""; editingMessageValue.value = ""; } }, "取消"),
                ]),
              ])
              : null,
            message.reasoning ? h("details", {
              class: "reasoning",
              open: !!message.reasoningOpen,
              onToggle: (event) => {
                if (messageWriteBlocked || !ownerSession) return;
                sourceMessage.reasoningOpen = event.target.open;
                persistSession(ownerSession);
              },
            }, [
              h("summary", "思考过程"),
              h(MarkdownContent, { content: message.reasoning, onRendered: scheduleSoftScrollBottom }),
            ]) : null,
            editingMessageId.value === message.id
              ? null
              : h(MarkdownContent, { content: message.content, onRendered: scheduleSoftScrollBottom }),
            message.files?.length ? h("div", { class: "attachments" }, message.files.map((file) => h("span", file.name))) : null,
            message.images?.length ? h("div", { class: "images" }, message.images
              .map(safeRenderedImageSource)
              .filter(Boolean)
              .map((src) => h("img", { src }))) : null,
            message.role === "assistant" ? h("div", { class: "message-actions" }, [
              h("button", { onClick: () => copyMarkdown(message) }, "复制 MD"),
              h("button", { onClick: () => copyPlainText(message) }, "复制纯文本"),
              h("button", { onClick: () => exportMessage(message) }, "📄 导出"),
              h("button", { disabled: sessionIsBusy(ownerSession.id) || messageWriteBlocked, onClick: () => retryMessage(ownerSession, sourceMessage) }, "🔄 重试"),
              h("button", { disabled: messageWriteBlocked, onClick: () => toggleFavorite(ownerSession, sourceMessage) }, sourceMessage.favorite ? "⭐ 取消收藏" : "⭐ 收藏"),
            ]) : null,
            message.role === "user" && editingMessageId.value !== message.id ? h("div", { class: "message-actions" }, [
              h("button", { disabled: messageWriteBlocked, onClick: () => startEditMessage(ownerSession, sourceMessage) }, "编辑"),
            ]) : null,
            message.totalTokens ? h("div", { class: "token-stats" }, `Token 约 ${message.totalTokens}（输入 ${message.promptTokens || 0} / 输出 ${message.completionTokens || 0}）`) : null,
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
          h("div", { class: "brand" }, [h("strong", "Halo AI"), h("span", "控制台助手")]),
          h("div", { class: "sidebar-actions" }, [
            h(Button, { type: "primary", size: "sm", onClick: newSession }, () => "新建聊天"),
            h(Button, { size: "sm", disabled: !!currentSyncConflict.value, onClick: clearContext }, () => "清除上下文"),
            h(Button, { size: "sm", disabled: !!currentSyncConflict.value, onClick: cleanCurrentSession }, () => "清理错误"),
            h(Button, { size: "sm", onClick: () => { showFavoritesOnly.value = !showFavoritesOnly.value; } }, () => showFavoritesOnly.value ? "全部消息" : "收藏消息"),
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
                h("b", {
                  class: syncConflicts.value[session.id] && "is-disabled",
                  "aria-disabled": syncConflicts.value[session.id] ? "true" : "false",
                  onClick: (event) => startRename(session, event),
                }, "重命名"),
                h("b", {
                  class: ["danger", syncConflicts.value[session.id] && "is-disabled"],
                  "aria-disabled": syncConflicts.value[session.id] ? "true" : "false",
                  onClick: (event) => { event.stopPropagation(); removeSession(session.id); },
                }, "删除"),
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
              title: `当前上下文约使用 ${contextUsed.value} Token，剩余约 ${contextRemaining.value} / ${contextLimit.value} Token。点击压缩上下文。`,
              disabled: contextCompressing.value || !!currentSyncConflict.value,
              onClick: compressContext,
            }, [
              h("strong", compactTokenCount(contextRemaining.value)),
              h("span", "剩余"),
            ]),
            h(Button, { size: "sm", disabled: contextCompressing.value || !!currentSyncConflict.value, onClick: summarizeConversation }, () => "摘要对话"),
            h(Button, { size: "sm", onClick: refreshModels }, () => "刷新模型"),
          ]),
          conversationTags.value.length ? h("div", { class: "conversation-tags" }, conversationTags.value.map((tag) =>
            h("button", { type: "button", onClick: () => { sessionQuery.value = tag; } }, `#${tagLabel(tag)}`)
          )) : null,
          (error.value || Object.keys(syncConflicts.value).length) ? h("div", { class: "ai-chat-notices" }, [
            error.value ? h("div", { class: "ai-chat-error" }, error.value) : null,
            Object.keys(syncConflicts.value).length ? h("div", { class: "ai-chat-conflicts" }, [
              h("strong", "以下会话存在未处理的同步冲突："),
              ...Object.values(syncConflicts.value).map((conflict) => {
              const shortId = conflict.sessionId.length > 18 ? `${conflict.sessionId.slice(0, 18)}…` : conflict.sessionId;
              const label = `${conflict.title || EMPTY_TITLE}（${shortId}）`;
              return h("span", { class: "sync-conflict-item" }, [
                h("strong", label),
                h("small", (conflict.details || []).join("、")),
                h("span", { class: "sync-conflict-actions" }, [
                  h("button", {
                    type: "button",
                    "aria-label": `另存“${label}”的本地冲突草稿`,
                    onClick: () => saveConflictDraftAsNewSession(conflict.sessionId),
                  }, "另存本地草稿"),
                  h("button", {
                    type: "button",
                    "aria-label": `放弃“${label}”的本地冲突草稿`,
                    onClick: () => discardConflictDraft(conflict.sessionId),
                  }, "放弃草稿"),
                ]),
              ]);
              }),
            ]) : null,
          ]) : null,
          h("section", { ref: chatEl, class: "halo-ai-messages", onScroll: handleMessagesScroll }, (showFavoritesOnly.value ? favoriteMessages.value.length : (current.value?.messages || []).length)
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
              disabled: !!currentSyncConflict.value,
              placeholder: currentSyncConflict.value
                ? "该会话存在同步冲突，请先另存或放弃本地草稿。"
                : "输入消息，支持 Markdown，LaTeX 将以源码显示。使用 /image 开头可调用图像生成模型。",
              onInput: (event) => { input.value = event.target.value; },
              onKeydown: (event) => {
                if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) send();
              },
            }),
            h("div", { class: "composer-actions" }, [
              h("label", { class: ["file-button", currentSyncConflict.value && "is-disabled"] }, ["添加文件", h("input", { type: "file", disabled: !!currentSyncConflict.value, accept: `${SUPPORTED_IMAGE_ACCEPT},.txt,.md,.markdown,.json,.csv,.tsv,.log,.xml,.yaml,.yml,text/*`, multiple: true, onChange: chooseFiles })]),
              h("span", { class: "hint" }, "Ctrl / Cmd + Enter 发送"),
              loading.value
                ? h(Button, { type: "secondary", onClick: stopGeneration }, () => `停止生成${currentActiveJobs.value.length > 1 ? `（${currentActiveJobs.value.length}）` : ""}`)
                : h(Button, { type: "primary", disabled: !input.value.trim() || !!currentSyncConflict.value, onClick: send }, () => "发送"),
            ]),
          ]),
        ]),
      ]);
    },
  };

  const SettingsView = {
    name: "HaloAiConsoleSettings",
    setup() {
      const settings = ref(defaultPersonalSettings());
      const logs = ref([]);
      const logScope = ref("own");
      const canViewAllLogs = ref(false);
      const logError = ref("");
      const dataMessage = ref("");
      const Button = components.VButton || "button";
      let identityCheck = null;
      let settingsHydrated = false;
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
      const load = async () => {
        try {
          settings.value = await loadSettingsFromDb();
          settingsHydrated = true;
        } catch (cause) {
          logError.value = `设置加载失败：${cause?.response?.data?.detail || cause?.message || "请求失败"}`;
        }
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
      const exportOwnData = async () => {
        dataMessage.value = "正在准备导出。日志和 Job 各最多导出 10,000 条，附件仅导出引用信息。";
        try {
          const { data } = await axios.get(`${CHAT_API}/me/export`);
          downloadText(`halo-ai-console-data-${Date.now()}.json`, JSON.stringify(data, null, 2));
          dataMessage.value = "导出完成。日志和 Job 各最多包含 10,000 条，附件文件本身不会包含在导出文件中。";
        } catch (cause) {
          dataMessage.value = `个人数据导出失败：${cause?.response?.data?.detail || cause?.message || "请求失败"}`;
        }
      };
      const deleteOwnData = async () => {
        if (!window.confirm("这会删除你的会话、Job、图片缓存、用量记录和个人设置；是否继续？审计日志是否删除由管理员策略决定。该操作不会删除 Halo 附件库中的上传文件，请前往附件管理单独删除。")) return;
        try {
          const { data } = await axios.delete(`${CHAT_API}/me/data`);
          const failures = Array.isArray(data?.failures) ? data.failures : [];
          if (failures.length) {
          const details = failures.map((item) => `${resourceLabel(item.resource)}：${item.error || "删除失败"}`).join("；");
            dataMessage.value = `个人数据已部分删除，仍有 ${failures.length} 项失败：${details}。请修复后重试。Halo 附件库中的上传文件不会由此操作删除，请前往附件管理单独删除。`;
            return;
          }
          clearOwnerCache();
          window.alert("个人数据删除完成。该操作不会删除 Halo 附件库中的上传文件，请前往附件管理单独删除。");
          window.location.reload();
        } catch (cause) {
          logError.value = `个人数据删除失败：${cause?.response?.data?.detail || cause?.message || "请求失败"}`;
        }
      };
      const clearSensitiveSettingsView = () => {
        settings.value = defaultPersonalSettings();
        logs.value = [];
        canViewAllLogs.value = false;
        logScope.value = "own";
        dataMessage.value = "";
      };
      const checkSettingsOwner = () => {
        if (identityCheck) return identityCheck;
        identityCheck = activateOwnerCache()
          .then(({ canViewAllLogs: allowed, changed }) => {
            if (changed) clearSensitiveSettingsView();
            canViewAllLogs.value = allowed;
            if (!allowed) logScope.value = "own";
            return Promise.all([changed || !settingsHydrated ? load() : Promise.resolve(), refreshLogs()]);
          })
          .catch((cause) => {
            if (isAuthenticationFailure(cause)) {
              clearSensitiveSettingsView();
              logError.value = "登录已失效，已隐藏本地设置和审计数据。请重新登录。";
              return;
            }
            logError.value = cause?.response?.data?.detail || cause?.message || "无法确认当前登录用户。";
          })
          .finally(() => { identityCheck = null; });
        return identityCheck;
      };
      onMounted(() => {
        checkSettingsOwner();
        window.addEventListener("focus", checkSettingsOwner);
      });
      if (onActivated) onActivated(checkSettingsOwner);
      if (onBeforeUnmount) onBeforeUnmount(() => window.removeEventListener("focus", checkSettingsOwner));
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
          h("p", { class: "readonly-note" }, [
            "消息和附件可能会发送给管理员配置的第三方 AI 服务。当前版本的隐私与数据处理说明：",
            h("a", {
              href: "https://github.com/joshuaJJM/halo-ai-console/blob/main/PRIVACY.md",
              target: "_blank",
              rel: "noreferrer",
            }, "查看隐私与数据处理说明"),
          ]),
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
          dataMessage.value ? h("p", { class: "readonly-note" }, dataMessage.value) : null,
          h("div", { class: "settings-data-actions" }, [
            h(Button, { size: "sm", onClick: exportOwnData }, () => "导出我的数据"),
            h(Button, { size: "sm", type: "danger", onClick: deleteOwnData }, () => "删除我的数据"),
          ]),
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
              h("strong", `${log.owner || "当前用户"} · ${operationLabel(log.operation || log.type)}`),
              h("span", `${log.model || "-"} · Token 约 ${log.totalTokens || 0}`),
              h("span", `${new Date(log.time).toLocaleString()} · ${log.durationMs || 0} 毫秒`),
              h("span", `${log.ipAddress || "-"} · ${clientLabel(log.browser)} / ${clientLabel(log.operatingSystem)}`),
              h("small", `${statusLabel(log.status)} · ${log.sessionTitle || log.sessionId || ""}`),
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
.halo-ai-session small{font-size:11px;color:#64748b}.session-actions{display:flex;gap:10px;align-items:center}.halo-ai-session b{font-size:11px;color:#2563eb;font-weight:500;cursor:pointer}.halo-ai-session b.danger{color:#ef4444}.halo-ai-session b.is-disabled{opacity:.42;cursor:not-allowed}.session-rename{width:100%;border:1px solid #bfdbfe;border-radius:10px;padding:6px 8px;font-size:13px;outline:none;background:#fff}
.session-search{width:100%;height:36px;border:1px solid #d7dee8;border-radius:999px;padding:0 12px;background:#fff;outline:none;font-size:13px}.session-search:focus{border-color:#93c5fd;box-shadow:0 0 0 3px rgba(59,130,246,.12)}.search-hint{margin-top:-8px;color:#94a3b8;font-size:11px;line-height:1.4}
.ai-chat-main{grid-column:2;min-width:0;min-height:0;overflow:hidden;display:grid;grid-template-rows:auto auto auto minmax(0,1fr) auto}
.ai-chat-toolbar{grid-row:1;min-height:66px;border-bottom:1px solid #e6eaf0;background:rgba(255,255,255,.82);backdrop-filter:blur(10px);display:flex;align-items:center;gap:12px;padding:12px 18px}
.ai-chat-toolbar .title{display:grid;margin-right:auto}.ai-chat-toolbar strong{font-size:18px}.ai-chat-toolbar span{font-size:12px;color:#64748b}
.ai-chat-toolbar select{height:38px;border:1px solid #d7dee8;border-radius:999px;background:#fff;padding:0 12px;max-width:280px;outline:none}
.context-meter{width:54px;height:54px;border:0;border-radius:999px;background:conic-gradient(#2563eb var(--context-used),#e2e8f0 0);padding:3px;cursor:pointer;display:grid;place-items:center;box-shadow:0 10px 24px rgba(37,99,235,.12)}
.context-meter:disabled{opacity:.58;cursor:wait}.context-meter strong,.context-meter span{grid-area:1/1}.context-meter strong{width:48px;height:48px;border-radius:999px;background:#fff;display:flex;align-items:center;justify-content:center;font-size:12px;color:#0f172a;padding-top:0}.context-meter span{align-self:end;justify-self:center;margin-bottom:8px;font-size:9px;color:#64748b;transform:translateY(10px)}
.ai-chat-notices{grid-row:3;margin:14px 20px 0;display:grid;gap:8px;min-width:0;max-height:min(36vh,320px);overflow:auto}.ai-chat-error,.ai-chat-conflicts{padding:12px 14px;border:1px solid #fecaca;background:#fff1f2;color:#b91c1c;border-radius:16px}.ai-chat-conflicts{display:grid;gap:8px}.sync-conflict-item{display:grid;grid-template-columns:minmax(140px,1fr) auto;align-items:center;gap:3px 10px;padding:7px 9px;border:1px solid #fecaca;border-radius:8px;background:#fff;max-width:100%}.sync-conflict-item small{grid-column:1;color:#9f1239;overflow-wrap:anywhere}.sync-conflict-actions{grid-column:2;grid-row:1 / span 2;display:flex;gap:6px}.sync-conflict-actions button{flex:0 0 auto;border:1px solid #fca5a5;background:#fff;color:#b91c1c;border-radius:8px;padding:5px 9px;cursor:pointer}
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
.ai-chat-composer textarea:focus{border-color:#93c5fd;box-shadow:0 0 0 4px rgba(59,130,246,.12)}.ai-chat-composer textarea:disabled{background:#f8fafc;color:#64748b;cursor:not-allowed}.file-button.is-disabled{color:#94a3b8;cursor:not-allowed}
.composer-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.file-button{font-size:13px;color:#2563eb;cursor:pointer}.file-button input{display:none}.hint{font-size:12px;color:#94a3b8;margin-right:auto}
.message-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.message-actions button{border:1px solid #d7dee8;background:#fff;border-radius:999px;padding:4px 9px;font-size:12px;color:#475569;cursor:pointer}.message-actions button:disabled{opacity:.45;cursor:not-allowed}.token-stats{margin-top:8px;font-size:11px;color:#94a3b8}.message-edit textarea{width:100%;min-height:92px;border:1px solid #d7dee8;border-radius:14px;padding:10px;font:inherit;resize:vertical;background:#fff;color:#0f172a}
.drop-mask{position:fixed;inset:0;z-index:10000;background:rgba(37,99,235,.12);border:3px dashed #60a5fa;display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:700;color:#1d4ed8;pointer-events:none}.ai-chat-settings{min-height:calc(100vh - 64px);background:#f1f5f9;padding:24px;display:grid;gap:18px;align-content:start}.settings-panel{max-width:1080px;background:#fff;border:1px solid #e2e8f0;border-radius:18px;padding:20px;box-shadow:0 12px 28px rgba(15,23,42,.06)}.settings-panel h1,.settings-panel h2{margin:0 0 16px}.settings-panel label{display:grid;gap:6px;margin:12px 0}.settings-panel input{height:38px;border:1px solid #d7dee8;border-radius:10px;padding:0 10px}.settings-panel textarea{min-height:120px;border:1px solid #d7dee8;border-radius:12px;padding:10px;resize:vertical;background:#fff;color:#0f172a}.readonly-note{border:1px solid #dbe3ee;background:#f8fafc;border-radius:12px;padding:10px 12px;color:#475569}.settings-title-row{display:flex;align-items:center;justify-content:space-between;gap:12px}.settings-actions{display:flex;gap:8px;align-items:center}.settings-error{margin:0 0 10px;color:#b91c1c;background:#fff1f2;border:1px solid #fecaca;border-radius:12px;padding:9px 11px}.call-log-list{display:grid;gap:8px}.call-log-item{display:grid;grid-template-columns:1.1fr 1.2fr 1.3fr 1.3fr;gap:8px;border:1px solid #edf2f7;border-radius:12px;padding:10px}.call-log-item small{grid-column:1/-1;color:#64748b}
@media (max-width: 900px){.ai-chat-shell{height:100dvh;max-height:100dvh;min-height:min(520px,100dvh);grid-template-columns:1fr;position:relative}.ai-chat-sidebar{display:flex;position:absolute;z-index:8;inset:0 auto 0 0;width:min(88vw,320px);max-width:calc(100vw - 28px);box-shadow:12px 0 28px rgba(15,23,42,.18);transition:transform .2s ease,opacity .2s ease}.ai-chat-shell.is-sidebar-collapsed .ai-chat-sidebar{transform:translateX(calc(-100% - 2px));opacity:0;pointer-events:none}.sidebar-toggle{left:0;top:44%;width:24px;height:48px;z-index:9}.ai-chat-shell:not(.is-sidebar-collapsed) .sidebar-toggle{left:min(88vw,320px)}.ai-chat-main{grid-column:1}.bubble{max-width:100%}.ai-chat-toolbar{display:grid;grid-template-columns:96px minmax(0,1fr) 48px;align-items:center;gap:8px;padding:10px 12px 10px 34px}.ai-chat-toolbar .title{grid-column:1/-1;width:100%;min-width:0}.ai-chat-toolbar select{max-width:none;width:100%;min-width:0}.ai-chat-toolbar .context-meter{width:46px;height:46px}.ai-chat-toolbar .context-meter strong{width:40px;height:40px;font-size:11px}.ai-chat-toolbar button:not(.context-meter){grid-column:1/-1;justify-self:start}.halo-ai-messages{padding:16px 12px;scroll-padding-bottom:16px}.ai-chat-composer{padding:9px 12px 10px}.ai-chat-composer textarea{height:88px;min-height:88px;max-height:88px;border-radius:16px}.composer-actions{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:8px}.hint{font-size:11px;white-space:normal}.halo-ai-message,.halo-ai-message.user{grid-template-columns:32px minmax(0,1fr)}.halo-ai-message.user .avatar{grid-column:1}.halo-ai-message.user .bubble{grid-column:2}}
@media (max-width: 560px){.ai-chat-toolbar{grid-template-columns:84px minmax(0,1fr);padding-left:32px}.ai-chat-toolbar .context-meter{grid-column:2;justify-self:end}.ai-chat-toolbar button:not(.context-meter){grid-column:1/-1}.context-meter{width:42px;height:42px}.composer-actions{grid-template-columns:1fr auto}.composer-actions .hint{grid-column:1/-1;order:3}.file-button{align-self:center}.ai-chat-composer textarea{font-size:15px}.message-actions button{font-size:11px;padding:4px 8px}.sync-conflict-item{grid-template-columns:minmax(0,1fr);width:100%}.sync-conflict-item small,.sync-conflict-actions{grid-column:1;grid-row:auto}.sync-conflict-actions{flex-wrap:wrap}}
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
