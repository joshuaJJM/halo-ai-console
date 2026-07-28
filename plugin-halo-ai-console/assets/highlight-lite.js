(function(){
  "use strict";
  function escapeHtml(value){
    return String(value || "")
      .replace(/&/g,"&amp;")
      .replace(/</g,"&lt;")
      .replace(/>/g,"&gt;");
  }
  function highlightElement(node){
    if(!node || node.dataset.highlighted) return;
    var html = escapeHtml(node.textContent || "");
    html = html
      .replace(/(\/\/[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/)/g,'<span class="hljs-comment">$1</span>')
      .replace(/("[^"\n]*"|'[^'\n]*'|`[^`\n]*`)/g,'<span class="hljs-string">$1</span>')
      .replace(/\b(\d+(?:\.\d+)?)\b/g,'<span class="hljs-number">$1</span>')
      .replace(/\b(function|class|const|let|var|return|if|else|for|while|try|catch|finally|await|async|import|from|export|def|lambda|in|not|and|or|public|private|static|new|void|true|false|null|None|True|False)\b/g,'<span class="hljs-keyword">$1</span>')
      .replace(/\b(console|print|Math|JSON|String|Number|Array|Object|Map|Set)\b/g,'<span class="hljs-built_in">$1</span>');
    node.innerHTML = html;
    node.dataset.highlighted = "yes";
  }
  window.haloAiChatHighlight = { highlightElement: highlightElement };
})();
