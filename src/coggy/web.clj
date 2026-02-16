#!/usr/bin/env bb

(ns coggy.web
  "HTTP server — serves the Coggy web UI and API.

   Endpoints:
   /           — web UI (three-column layout)
   /api/chat   — POST {message: str} → {response, trace}
   /api/state  — GET → current atomspace + attention
   /api/trace  — GET → last trace
   /api/boot   — POST → re-run boot ritual
   /health     — health check"
  (:require [org.httpkit.server :as srv]
            [cheshire.core :as json]
            [clojure.string :as str]
            [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.llm :as llm]
            [coggy.boot :as boot]
            [coggy.repl :as repl]
            [coggy.trace :as trace]
            [coggy.semantic :as sem]))

;; =============================================================================
;; State
;; =============================================================================

(defonce server-state (atom {:logs []
                             :files-written []
                             :last-trace nil}))

(defn log! [msg]
  (let [ts (java.time.LocalTime/now)]
    (swap! server-state update :logs
           (fn [logs] (take-last 200 (conj logs {:ts (str ts) :msg msg}))))))

;; =============================================================================
;; Web UI — Three Column Layout
;; =============================================================================

(def index-html
  "<!DOCTYPE html>
<html lang='en'>
<head>
<meta charset='utf-8'>
<meta name='viewport' content='width=device-width, initial-scale=1'>
<title>COGGY — ὕλη becomes νοῦς</title>
<style>
  :root {
    --bg: #0d1117;
    --bg-panel: #161b22;
    --bg-input: #0d1117;
    --border: #30363d;
    --border-focus: #58a6ff;
    --text: #c9d1d9;
    --text-dim: #8b949e;
    --text-bright: #f0f6fc;
    --gold: #e3b341;
    --teal: #39d353;
    --cyan: #58a6ff;
    --magenta: #bc8cff;
    --ember: #f78166;
    --sage: #7ee787;
    --ghost: #484f58;
    --lavender: #d2a8ff;
    --red: #f85149;
    --scrollbar: #30363d;
    --scrollbar-thumb: #484f58;
  }

  * { margin: 0; padding: 0; box-sizing: border-box; }

  body {
    background: var(--bg);
    color: var(--text);
    font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;
    font-size: 13px;
    line-height: 1.5;
    height: 100vh;
    overflow: hidden;
  }

  /* Three Column Layout */
  .layout {
    display: grid;
    grid-template-columns: 1fr 2fr 1fr;
    grid-template-rows: auto 1fr auto;
    height: 100vh;
    gap: 1px;
    background: var(--border);
  }

  .header {
    grid-column: 1 / -1;
    background: var(--bg-panel);
    padding: 8px 16px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 1px solid var(--border);
  }

  .header-title {
    color: var(--gold);
    font-weight: bold;
    font-size: 14px;
    letter-spacing: 2px;
  }

  .header-subtitle {
    color: var(--text-dim);
    font-style: italic;
    font-size: 11px;
  }

  .header-status {
    display: flex;
    gap: 16px;
    font-size: 11px;
    color: var(--text-dim);
  }

  .status-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    margin-right: 4px;
    vertical-align: middle;
  }

  .status-dot.up { background: var(--teal); }
  .status-dot.down { background: var(--red); }

  /* Panels */
  .panel {
    background: var(--bg-panel);
    overflow-y: auto;
    padding: 12px;
    scrollbar-width: thin;
    scrollbar-color: var(--scrollbar-thumb) var(--scrollbar);
  }

  .panel::-webkit-scrollbar { width: 6px; }
  .panel::-webkit-scrollbar-track { background: var(--scrollbar); }
  .panel::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 3px; }

  .panel-header {
    color: var(--gold);
    font-weight: bold;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 2px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--border);
    margin-bottom: 8px;
    position: sticky;
    top: 0;
    background: var(--bg-panel);
    z-index: 1;
  }

  /* Left Column — Hyle / AtomSpace */
  .left-panel .atom {
    padding: 4px 0;
    border-bottom: 1px solid rgba(48, 54, 61, 0.5);
    font-size: 12px;
  }

  .atom-type {
    color: var(--sage);
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 1px;
  }

  .atom-name {
    color: var(--text-bright);
    font-weight: bold;
  }

  .atom-tv {
    color: var(--lavender);
    font-size: 11px;
  }

  .atom-sti {
    display: inline-block;
    background: var(--bg);
    border-radius: 2px;
    padding: 0 4px;
    font-size: 10px;
  }

  .sti-bar {
    display: inline-block;
    height: 8px;
    background: var(--teal);
    border-radius: 1px;
    transition: width 0.3s ease;
    vertical-align: middle;
  }

  /* Center Column — Conversation + Traces */
  .center-panel { padding: 0; display: flex; flex-direction: column; }

  .messages {
    flex: 1;
    overflow-y: auto;
    padding: 12px;
    scrollbar-width: thin;
    scrollbar-color: var(--scrollbar-thumb) var(--scrollbar);
  }

  .message {
    margin-bottom: 16px;
    animation: fadeIn 0.3s ease;
  }

  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(4px); }
    to { opacity: 1; transform: translateY(0); }
  }

  .message-role {
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 1px;
    margin-bottom: 4px;
  }

  .message-role.human { color: var(--cyan); }
  .message-role.coggy { color: var(--gold); }

  .message-content {
    padding: 8px 12px;
    border-radius: 6px;
    white-space: pre-wrap;
    word-wrap: break-word;
  }

  .message.human .message-content {
    background: rgba(88, 166, 255, 0.08);
    border-left: 2px solid var(--cyan);
  }

  .message.coggy .message-content {
    background: rgba(227, 179, 65, 0.06);
    border-left: 2px solid var(--gold);
  }

  /* Trace Block */
  .trace-block {
    background: rgba(57, 211, 83, 0.04);
    border: 1px solid rgba(57, 211, 83, 0.15);
    border-radius: 4px;
    padding: 8px 12px;
    margin-top: 8px;
    font-size: 11px;
    line-height: 1.6;
  }

  .trace-phase {
    color: var(--gold);
    font-weight: bold;
    font-size: 10px;
    letter-spacing: 1px;
  }

  .trace-line { color: var(--text-dim); }
  .trace-line .glyph { color: var(--ember); }
  .trace-line .type { color: var(--sage); }
  .trace-line .name { color: var(--text-bright); }
  .trace-line .tv { color: var(--lavender); }
  .trace-line .found { color: var(--teal); }
  .trace-line .gap { color: var(--ghost); }
  .trace-line .focus { color: var(--cyan); font-weight: bold; }

  /* Input */
  .input-area {
    padding: 8px 12px;
    border-top: 1px solid var(--border);
    background: var(--bg-panel);
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .prompt-label {
    color: var(--teal);
    font-weight: bold;
    white-space: nowrap;
  }

  .turn-num {
    color: var(--ghost);
    font-size: 11px;
  }

  #input {
    flex: 1;
    background: var(--bg-input);
    border: 1px solid var(--border);
    border-radius: 4px;
    color: var(--text-bright);
    font-family: inherit;
    font-size: 13px;
    padding: 8px 12px;
    outline: none;
    transition: border-color 0.2s;
  }

  #input:focus { border-color: var(--border-focus); }

  #send-btn {
    background: var(--gold);
    color: var(--bg);
    border: none;
    border-radius: 4px;
    padding: 8px 16px;
    font-family: inherit;
    font-weight: bold;
    cursor: pointer;
    transition: opacity 0.2s;
  }

  #send-btn:hover { opacity: 0.8; }
  #send-btn:disabled { opacity: 0.4; cursor: default; }

  /* Right Column — Logs */
  .right-panel .log-entry {
    padding: 2px 0;
    font-size: 11px;
    border-bottom: 1px solid rgba(48, 54, 61, 0.3);
  }

  .log-ts {
    color: var(--ghost);
    font-size: 10px;
    margin-right: 8px;
  }

  .log-msg { color: var(--text-dim); }
  .log-msg.file { color: var(--sage); }
  .log-msg.error { color: var(--red); }
  .log-msg.ontology { color: var(--lavender); }

  /* Footer */
  .footer {
    grid-column: 1 / -1;
    background: var(--bg);
    padding: 4px 16px;
    display: flex;
    justify-content: space-between;
    font-size: 10px;
    color: var(--ghost);
    border-top: 1px solid var(--border);
  }
</style>
</head>
<body>
<div class='layout'>
  <!-- Header -->
  <div class='header'>
    <div>
      <span class='header-title'>C O G G Y</span>
      <span class='header-subtitle'> — ὕλη becomes νοῦς</span>
    </div>
    <div class='header-status'>
      <span><span class='status-dot up' id='api-dot'></span> openrouter</span>
      <span id='model-name'>model: loading...</span>
      <span id='atom-count'>atoms: 0</span>
      <span id='turn-count'>turn: 0</span>
    </div>
  </div>

  <!-- Left: AtomSpace / Hyle -->
  <div class='panel left-panel'>
    <div class='panel-header'>⬡ HYLE — AtomSpace</div>
    <div id='atomspace'></div>
  </div>

  <!-- Center: Conversation -->
  <div class='panel center-panel'>
    <div class='panel-header' style='padding: 8px 12px;'>◉ TRACE — Conversation</div>
    <div class='messages' id='messages'></div>
    <div class='input-area'>
      <span class='prompt-label'>coggy</span>
      <span class='turn-num' id='prompt-turn'>[0]</span>
      <span class='prompt-label'>❯</span>
      <input id='input' type='text' placeholder='speak...' autofocus>
      <button id='send-btn'>↵</button>
    </div>
  </div>

  <!-- Right: Logs -->
  <div class='panel right-panel'>
    <div class='panel-header'>◈ LOGS — System Trace</div>
    <div id='logs'></div>
  </div>

  <!-- Footer -->
  <div class='footer'>
    <span>coggy v0.1 — ontological reasoning harness</span>
    <span id='footer-stats'></span>
  </div>
</div>

<script>
const messagesEl = document.getElementById('messages');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('send-btn');
const atomspaceEl = document.getElementById('atomspace');
const logsEl = document.getElementById('logs');
let turn = 0;

function addLog(msg, cls) {
  const ts = new Date().toLocaleTimeString();
  const el = document.createElement('div');
  el.className = 'log-entry';
  el.innerHTML = `<span class=\"log-ts\">${ts}</span><span class=\"log-msg ${cls||''}\">${msg}</span>`;
  logsEl.prepend(el);
}

function renderAtom(atom) {
  const tv = atom['atom/tv'] || {};
  const sti = atom.sti || 0;
  const barW = Math.min(60, Math.max(2, sti * 3));
  return `<div class=\"atom\">
    <span class=\"atom-type\">${atom['atom/type']||'?'}</span>
    <span class=\"atom-name\"> ${atom['atom/name']||'?'}</span>
    <span class=\"atom-tv\"> (stv ${(tv['tv/strength']||0).toFixed(1)} ${(tv['tv/confidence']||0).toFixed(1)})</span>
    <div class=\"atom-sti\"><span class=\"sti-bar\" style=\"width:${barW}px\"></span> ${sti.toFixed(1)}</div>
  </div>`;
}

function renderTrace(trace) {
  if (!trace) return '';
  let html = '<div class=\"trace-block\">';
  html += '<div class=\"trace-phase\">┌ COGGY TRACE</div>';

  if (trace.parse) {
    html += '<div class=\"trace-phase\">│ PARSE</div>';
    trace.parse.forEach(a => {
      html += `<div class=\"trace-line\">│  <span class=\"glyph\">⊕</span> <span class=\"type\">${a['atom/type']||'?'}</span> <span class=\"name\">\"${a['atom/name']||'?'}\"</span></div>`;
    });
  }
  if (trace.attend) {
    html += '<div class=\"trace-phase\">│ ATTEND</div>';
    trace.attend.forEach(a => {
      const g = a.sti > 5 ? '★' : '↘';
      html += `<div class=\"trace-line\">│  <span class=\"glyph\">${g}</span> <span class=\"focus\">${a.key}</span> STI ${a.sti.toFixed(1)}</div>`;
    });
  }
  if (trace.infer) {
    html += '<div class=\"trace-phase\">│ INFER</div>';
    trace.infer.forEach(i => {
      const tv = i.tv ? ` <span class=\"tv\">(stv ${i.tv['tv/strength'].toFixed(1)} ${i.tv['tv/confidence'].toFixed(1)})</span>` : '';
      html += `<div class=\"trace-line\">│  <span class=\"glyph\">${i.type==='gap'?'?':'⊢'}</span> ${i.conclusion}${tv}</div>`;
    });
  }
  if (trace.reflect) {
    html += '<div class=\"trace-phase\">│ REFLECT</div>';
    const r = trace.reflect;
    html += `<div class=\"trace-line\">│  new:${r['new-atoms']||0} upd:${r.updated||0} focus:<span class=\"focus\">${r['focus-concept']||'—'}</span></div>`;
    if (r['next-question']) html += `<div class=\"trace-line\">│  <span class=\"glyph\">↦</span> \"${r['next-question']}\"</div>`;
  }
  html += '<div class=\"trace-phase\">└───────────────────</div></div>';
  return html;
}

function addMessage(role, content, trace) {
  const el = document.createElement('div');
  el.className = `message ${role}`;
  const roleLabel = role === 'human' ? 'HUMAN' : 'COGGY';
  el.innerHTML = `<div class=\"message-role ${role}\">${roleLabel}</div>
    <div class=\"message-content\">${content}</div>
    ${trace ? renderTrace(trace) : ''}`;
  messagesEl.appendChild(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

async function send() {
  const msg = inputEl.value.trim();
  if (!msg) return;
  inputEl.value = '';
  sendBtn.disabled = true;
  addMessage('human', msg);
  addLog('→ ' + msg.substring(0, 50));

  try {
    const resp = await fetch('/api/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({message: msg})
    });
    const data = await resp.json();
    turn = data.turn || turn + 1;
    document.getElementById('prompt-turn').textContent = `[${turn}]`;
    document.getElementById('turn-count').textContent = `turn: ${turn}`;

    addMessage('coggy', data.content || data.error || '?', data.trace);
    addLog('← response (' + (data.usage?.total_tokens || '?') + ' tokens)');

    if (data.stats) {
      document.getElementById('atom-count').textContent = `atoms: ${data.stats.atoms}`;
    }

    refreshAtomSpace();
  } catch(e) {
    addMessage('coggy', '⚠ Error: ' + e.message);
    addLog('ERROR: ' + e.message, 'error');
  }
  sendBtn.disabled = false;
  inputEl.focus();
}

async function refreshAtomSpace() {
  try {
    const resp = await fetch('/api/state');
    const data = await resp.json();
    let html = '';
    if (data.atoms) {
      Object.entries(data.atoms).forEach(([k, v]) => {
        v.sti = (data.attention && data.attention[k]) ? data.attention[k]['av/sti'] : 0;
        html += renderAtom(v);
      });
    }
    atomspaceEl.innerHTML = html;
    document.getElementById('atom-count').textContent = `atoms: ${Object.keys(data.atoms||{}).length}`;
    document.getElementById('model-name').textContent = `model: ${data.model || '?'}`;
  } catch(e) {}
}

inputEl.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
sendBtn.addEventListener('click', send);

// Boot
addLog('coggy boot sequence initiated', 'ontology');
refreshAtomSpace();
fetch('/api/boot', {method:'POST'}).then(r => r.json()).then(data => {
  addLog('boot complete: ' + data.atoms + ' atoms, ' + data.links + ' links', 'ontology');
  refreshAtomSpace();
}).catch(e => addLog('boot error: ' + e.message, 'error'));
</script>
</body>
</html>")

;; =============================================================================
;; API Handlers
;; =============================================================================

(defn json-response [data & {:keys [status] :or {status 200}}]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"
             "Access-Control-Allow-Headers" "Content-Type"}
   :body (json/generate-string data)})

(defn handle-chat [body]
  (let [msg (:message body)]
    (log! (str "← " (subs msg 0 (min 80 (count msg)))))
    (let [result (repl/process-turn! msg)]
      (log! (str "→ turn " (:turn result) " | atoms " (get-in result [:stats :atoms])))
      (json-response result))))

(defn handle-state []
  (let [space @(repl/space)
        bank @(repl/bank)]
    (json-response {:atoms (:atoms space)
                    :links (count (:links space))
                    :attention (:attention bank)
                    :focus (:focus bank)
                    :model (:model @llm/config)})))

(defn handle-boot []
  (let [space (repl/space)
        bank (repl/bank)]
    (boot/seed-ontology! space bank)
    (log! "boot ritual complete")
    (let [stats (as/space-stats space)]
      (json-response stats))))

(defn handler [{:keys [uri request-method body]}]
  (try
    (case [request-method uri]
      [:get "/"]         {:status 200
                          :headers {"Content-Type" "text/html"}
                          :body index-html}
      [:get "/health"]   (json-response {:status "ok"})
      [:get "/api/state"] (handle-state)
      [:get "/api/logs"]  (json-response (:logs @server-state))
      [:get "/api/metrics"] (json-response (sem/metrics-summary))

      [:post "/api/chat"] (let [body (json/parse-string (slurp body) true)]
                            (handle-chat body))
      [:post "/api/boot"] (handle-boot)

      [:options "/api/chat"] {:status 200
                              :headers {"Access-Control-Allow-Origin" "*"
                                        "Access-Control-Allow-Methods" "POST"
                                        "Access-Control-Allow-Headers" "Content-Type"}}

      {:status 404 :body "not found"})
    (catch Exception e
      (log! (str "ERROR: " (.getMessage e)))
      (json-response {:error (.getMessage e)} :status 500))))

;; =============================================================================
;; Server
;; =============================================================================

(defn start! [port]
  (println (str "coggy web UI on http://localhost:" port))
  (log! (str "server starting on port " port))
  (srv/run-server handler {:port port})
  @(promise))
