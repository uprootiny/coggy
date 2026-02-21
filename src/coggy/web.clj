#!/usr/bin/env bb

(ns coggy.web
  "HTTP server — the coggy web surface.

   Two-panel layout: the conversation is the left brain,
   the atomspace is the right brain. Grounding rate is the
   vital sign that connects them."
  (:require [org.httpkit.server :as srv]
            [cheshire.core :as json]
            [clojure.string :as str]
            [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.llm :as llm]
            [coggy.boot :as boot]
            [coggy.integrations.ibid :as ibid]
            [coggy.repl :as repl]
            [coggy.trace :as trace]
            [coggy.semantic :as sem]
            [coggy.bench :as bench]))

;; =============================================================================
;; State
;; =============================================================================

(defonce server-state (atom {:logs []}))

(defn log! [msg]
  (let [ts (java.time.LocalTime/now)]
    (swap! server-state update :logs
           (fn [logs] (take-last 200 (conj logs {:ts (str ts) :msg msg}))))))

;; =============================================================================
;; Web UI
;; =============================================================================


(def index-html
  "<!DOCTYPE html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
<title>coggy</title>
<style>
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

:root {
  --bg:         #0e1117;
  --surface:    #161b22;
  --surface-2:  #1c2330;
  --border:     #2d3748;
  --border-lo:  #1e2a38;
  --text:       #c9d1d9;
  --text-dim:   #6e7d8f;
  --text-faint: #3d4f63;
  --accent:     #58a6ff;
  --green:      #3fb950;
  --amber:      #d29922;
  --red:        #f85149;
  --teal:       #2ea043;
  --tag-bg:     #1a2332;
  --user-bg:    #1c2a1c;
  --coggy-bg:   #0e1a2e;
  --scroll-w:   6px;
  --font:       'JetBrains Mono', 'Fira Code', 'Cascadia Code', ui-monospace, monospace;
  --radius:     4px;
}

html, body {
  height: 100%;
  background: var(--bg);
  color: var(--text);
  font-family: var(--font);
  font-size: 13px;
  line-height: 1.6;
  overflow: hidden;
}

::-webkit-scrollbar { width: var(--scroll-w); height: var(--scroll-w); }
::-webkit-scrollbar-track { background: var(--bg); }
::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
::-webkit-scrollbar-thumb:hover { background: #4a5568; }

/* ── Top ribbon ── */
#ribbon {
  display: grid;
  grid-template-columns: auto 1fr repeat(5, auto);
  align-items: center;
  gap: 0;
  height: 38px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  padding: 0 12px;
  flex-shrink: 0;
}

.ribbon-logo {
  font-size: 14px;
  font-weight: 700;
  color: var(--accent);
  letter-spacing: 0.05em;
  padding-right: 20px;
  border-right: 1px solid var(--border-lo);
  margin-right: 16px;
}

.ribbon-spacer { flex: 1; }

.ribbon-stat {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  border-left: 1px solid var(--border-lo);
  height: 38px;
  white-space: nowrap;
}

.ribbon-stat .label {
  color: var(--text-dim);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.ribbon-stat .value {
  color: var(--text);
  font-size: 12px;
  font-weight: 600;
}

.ribbon-stat .value.good  { color: var(--green); }
.ribbon-stat .value.warn  { color: var(--amber); }
.ribbon-stat .value.bad   { color: var(--red); }

/* ── Main body ── */
#main {
  display: grid;
  grid-template-columns: 1fr 340px;
  height: calc(100vh - 38px);
  overflow: hidden;
}

/* ── Left: conversation ── */
#left {
  display: grid;
  grid-template-rows: 1fr auto;
  border-right: 1px solid var(--border);
  overflow: hidden;
}

#messages {
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.msg {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--radius);
  max-width: 100%;
}

.msg.user {
  background: var(--user-bg);
  border: 1px solid #2a3e2a;
  align-self: flex-end;
  max-width: 85%;
}

.msg.coggy {
  background: var(--coggy-bg);
  border: 1px solid #1a2e45;
  align-self: flex-start;
  max-width: 92%;
}

.msg.system {
  background: transparent;
  border: 1px solid var(--border-lo);
  align-self: center;
  max-width: 100%;
  color: var(--text-dim);
  font-size: 11px;
  text-align: center;
  padding: 4px 10px;
}

.msg-who {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  padding-top: 1px;
  white-space: nowrap;
  text-transform: uppercase;
}

.msg.user .msg-who   { color: var(--green); }
.msg.coggy .msg-who  { color: var(--accent); }

.msg-text {
  color: var(--text);
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg.coggy .msg-text { color: #b8c4d0; }

/* ── Input bar ── */
#input-bar {
  display: grid;
  grid-template-rows: auto auto;
  padding: 10px 16px 12px;
  background: var(--surface);
  border-top: 1px solid var(--border);
  gap: 8px;
}

.input-controls {
  display: flex;
  gap: 8px;
  align-items: center;
}

#model-select, #domain-select {
  background: var(--surface-2);
  border: 1px solid var(--border);
  color: var(--text-dim);
  font-family: var(--font);
  font-size: 11px;
  padding: 4px 8px;
  border-radius: var(--radius);
  cursor: pointer;
  outline: none;
  max-width: 200px;
}

#model-select:focus, #domain-select:focus {
  border-color: var(--accent);
  color: var(--text);
}

option { background: var(--surface-2); }

.ctrl-btn {
  background: var(--surface-2);
  border: 1px solid var(--border);
  color: var(--text-dim);
  font-family: var(--font);
  font-size: 11px;
  padding: 4px 10px;
  border-radius: var(--radius);
  cursor: pointer;
  white-space: nowrap;
}

.ctrl-btn:hover { border-color: var(--accent); color: var(--text); }
.ctrl-btn.danger:hover { border-color: var(--red); color: var(--red); }

.input-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
}

#chat-input {
  background: var(--surface-2);
  border: 1px solid var(--border);
  color: var(--text);
  font-family: var(--font);
  font-size: 13px;
  padding: 8px 12px;
  border-radius: var(--radius);
  resize: none;
  outline: none;
  line-height: 1.5;
  min-height: 38px;
  max-height: 120px;
}

#chat-input:focus { border-color: var(--accent); }
#chat-input::placeholder { color: var(--text-faint); }

#send-btn {
  background: var(--accent);
  border: none;
  color: #0d1117;
  font-family: var(--font);
  font-size: 12px;
  font-weight: 700;
  padding: 8px 18px;
  border-radius: var(--radius);
  cursor: pointer;
  white-space: nowrap;
  align-self: end;
}

#send-btn:hover { background: #79b8ff; }
#send-btn:disabled { background: var(--border); color: var(--text-faint); cursor: default; }

/* ── Right panel ── */
#right {
  display: grid;
  grid-template-rows: 1fr 1fr;
  overflow: hidden;
}

/* ── Panel shells ── */
.panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-bottom: 1px solid var(--border);
}

.panel:last-child { border-bottom: none; }

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.panel-title {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--text-dim);
}

.panel-action {
  background: none;
  border: 1px solid var(--border-lo);
  color: var(--text-faint);
  font-family: var(--font);
  font-size: 10px;
  padding: 2px 7px;
  border-radius: var(--radius);
  cursor: pointer;
}

.panel-action:hover { border-color: var(--accent); color: var(--accent); }

.panel-body {
  overflow-y: auto;
  padding: 10px 12px;
  flex: 1;
  font-size: 11px;
}

/* ── AtomSpace inspector ── */
.atom-section {
  margin-bottom: 12px;
}

.atom-section-title {
  font-size: 10px;
  color: var(--text-dim);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 4px;
  padding-bottom: 3px;
  border-bottom: 1px solid var(--border-lo);
}

.atom-type-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 2px 4px;
  border-radius: 2px;
}

.atom-type-row:hover { background: var(--surface-2); }

.atom-type-name { color: var(--text-dim); }
.atom-type-count {
  background: var(--tag-bg);
  color: var(--accent);
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 10px;
  font-weight: 600;
}

.focus-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.focus-item {
  display: grid;
  grid-template-columns: 1fr auto auto;
  align-items: center;
  gap: 8px;
  padding: 3px 6px;
  background: var(--surface-2);
  border-radius: 3px;
  border: 1px solid var(--border-lo);
}

.focus-name { color: var(--text); font-size: 11px; }
.focus-sti {
  color: var(--amber);
  font-size: 10px;
  font-weight: 600;
}

.sti-bar {
  width: 40px;
  height: 3px;
  background: var(--border);
  border-radius: 2px;
  overflow: hidden;
}

.sti-bar-fill {
  height: 100%;
  background: var(--amber);
  border-radius: 2px;
}

.link-row {
  display: grid;
  grid-template-columns: auto 1fr auto 1fr;
  gap: 4px;
  align-items: center;
  padding: 2px 4px;
  font-size: 11px;
  border-radius: 2px;
  color: var(--text-dim);
}

.link-row:hover { background: var(--surface-2); }
.link-type-badge {
  font-size: 10px;
  color: var(--teal);
  background: #0d2020;
  padding: 0 5px;
  border-radius: 3px;
  white-space: nowrap;
}

/* ── Metrics panel ── */
.metric-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin-bottom: 12px;
}

.metric-card {
  background: var(--surface-2);
  border: 1px solid var(--border-lo);
  border-radius: var(--radius);
  padding: 8px 10px;
}

.metric-card .m-label {
  font-size: 10px;
  color: var(--text-dim);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin-bottom: 3px;
}

.metric-card .m-value {
  font-size: 16px;
  font-weight: 700;
  color: var(--text);
}

.metric-card .m-value.good  { color: var(--green); }
.metric-card .m-value.warn  { color: var(--amber); }
.metric-card .m-value.bad   { color: var(--red); }

.rate-bar {
  width: 100%;
  height: 4px;
  background: var(--border);
  border-radius: 2px;
  margin-top: 4px;
  overflow: hidden;
}

.rate-bar-fill {
  height: 100%;
  border-radius: 2px;
  background: var(--green);
  transition: width 0.4s ease;
}

.model-health-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 10px;
}

.model-health-table th {
  color: var(--text-faint);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  text-align: left;
  padding: 2px 4px;
  border-bottom: 1px solid var(--border-lo);
  font-weight: 400;
}

.model-health-table td {
  color: var(--text-dim);
  padding: 3px 4px;
  border-bottom: 1px solid var(--border-lo);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-health-table tr.active-model td { color: var(--text); }
.model-health-table tr.active-model td:first-child { color: var(--accent); }
.model-health-table td.ok   { color: var(--green); }
.model-health-table td.fail { color: var(--red); }
.model-health-table td.cool { color: var(--amber); }

/* ── Loading shimmer ── */
.shimmer {
  background: linear-gradient(90deg, var(--surface-2) 25%, var(--border-lo) 50%, var(--surface-2) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
  border-radius: 3px;
  height: 12px;
  width: 80%;
  margin: 4px 0;
}

@keyframes shimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

/* ── Typing indicator ── */
.typing-dots {
  display: inline-flex;
  gap: 3px;
  padding: 2px 0;
}

.typing-dots span {
  width: 5px; height: 5px;
  background: var(--accent);
  border-radius: 50%;
  animation: dot-bounce 1.2s infinite;
}

.typing-dots span:nth-child(2) { animation-delay: 0.2s; }
.typing-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes dot-bounce {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.4; }
  40%           { transform: translateY(-4px); opacity: 1; }
}

/* ── Empty state ── */
.empty-state {
  color: var(--text-faint);
  font-size: 11px;
  text-align: center;
  padding: 20px 0;
}

/* ── Status badge ── */
.status-dot {
  display: inline-block;
  width: 6px; height: 6px;
  border-radius: 50%;
  margin-right: 4px;
}

.status-dot.up   { background: var(--green); }
.status-dot.down { background: var(--red); }

.hyle-status {
  font-size: 10px;
  color: var(--text-dim);
  display: flex;
  align-items: center;
}
</style>
</head>
<body>

<!-- Top ribbon -->
<div id=\"ribbon\">
  <span class=\"ribbon-logo\">coggy</span>
  <span></span><!-- spacer -->
  <div class=\"ribbon-stat\">
    <span class=\"label\">model</span>
    <span class=\"value\" id=\"r-model\">—</span>
  </div>
  <div class=\"ribbon-stat\">
    <span class=\"label\">turns</span>
    <span class=\"value\" id=\"r-turns\">0</span>
  </div>
  <div class=\"ribbon-stat\">
    <span class=\"label\">ground</span>
    <span class=\"value\" id=\"r-ground\">—</span>
  </div>
  <div class=\"ribbon-stat\">
    <span class=\"label\">atoms</span>
    <span class=\"value\" id=\"r-atoms\">—</span>
  </div>
  <div class=\"ribbon-stat\">
    <span class=\"label\">links</span>
    <span class=\"value\" id=\"r-links\">—</span>
  </div>
</div>

<!-- Main layout -->
<div id=\"main\">

  <!-- Left: conversation -->
  <div id=\"left\">
    <div id=\"messages\">
      <div class=\"msg system\">
        <div class=\"msg-text\">coggy ready — type a message to begin</div>
      </div>
    </div>

    <div id=\"input-bar\">
      <div class=\"input-controls\">
        <select id=\"model-select\" title=\"Select model\">
          <option value=\"\">loading models…</option>
        </select>
        <select id=\"domain-select\" title=\"Select domain pack\">
          <option value=\"\">no domain</option>
          <option value=\"legal\">legal</option>
          <option value=\"ibid-legal\">ibid-legal</option>
          <option value=\"forecast\">forecast</option>
          <option value=\"bio\">bio</option>
          <option value=\"unix\">unix</option>
          <option value=\"research\">research</option>
          <option value=\"balance\">balance</option>
          <option value=\"study\">study</option>
          <option value=\"accountability\">accountability</option>
        </select>
        <button class=\"ctrl-btn\" onclick=\"doRefresh()\" title=\"Refresh state\">refresh</button>
        <button class=\"ctrl-btn\" onclick=\"doBoot()\" title=\"Seed ontology\">boot</button>
        <button class=\"ctrl-btn\" onclick=\"doDump()\" title=\"Dump state snapshot\">dump</button>
      </div>
      <div class=\"input-row\">
        <textarea id=\"chat-input\" rows=\"1\" placeholder=\"enter message — shift+enter for newline\"></textarea>
        <button id=\"send-btn\" onclick=\"doSend()\">send</button>
      </div>
    </div>
  </div>

  <!-- Right: AtomSpace + metrics -->
  <div id=\"right\">

    <!-- AtomSpace inspector -->
    <div class=\"panel\">
      <div class=\"panel-header\">
        <span class=\"panel-title\">atomspace</span>
        <button class=\"panel-action\" onclick=\"doRefresh()\">refresh</button>
      </div>
      <div class=\"panel-body\" id=\"atomspace-body\">
        <div class=\"empty-state\">loading…</div>
      </div>
    </div>

    <!-- Metrics -->
    <div class=\"panel\">
      <div class=\"panel-header\">
        <span class=\"panel-title\">metrics</span>
        <div class=\"hyle-status\" id=\"hyle-status\">
          <span class=\"status-dot down\" id=\"hyle-dot\"></span>hyle
        </div>
      </div>
      <div class=\"panel-body\" id=\"metrics-body\">
        <div class=\"empty-state\">loading…</div>
      </div>
    </div>

  </div>
</div>

<script>
'use strict';

// ── Utilities ──────────────────────────────────────────────────────────────

function $(id) { return document.getElementById(id); }

function pct(n) {
  if (n == null || isNaN(n)) return '—';
  return (n * 100).toFixed(1) + '%';
}

function fmt(n) {
  if (n == null || isNaN(n)) return '—';
  if (typeof n === 'number') return n.toLocaleString();
  return String(n);
}

function rateClass(r) {
  if (r == null) return '';
  if (r >= 0.7) return 'good';
  if (r >= 0.4) return 'warn';
  return 'bad';
}

function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\"/g, '&quot;');
}

// ── State ──────────────────────────────────────────────────────────────────

let currentModel = '';
let currentDomain = '';

// ── Messages ───────────────────────────────────────────────────────────────

function addMsg(role, text) {
  const el = $('messages');
  const div = document.createElement('div');
  div.className = 'msg ' + role;

  const who = document.createElement('div');
  who.className = 'msg-who';
  who.textContent = role === 'user' ? 'you' : role;

  const body = document.createElement('div');
  body.className = 'msg-text';
  body.textContent = text;

  div.appendChild(who);
  div.appendChild(body);
  el.appendChild(div);
  el.scrollTop = el.scrollHeight;
  return div;
}

function addTyping() {
  const el = $('messages');
  const div = document.createElement('div');
  div.className = 'msg coggy';
  div.id = 'typing-indicator';

  const who = document.createElement('div');
  who.className = 'msg-who';
  who.textContent = 'coggy';

  const body = document.createElement('div');
  body.className = 'msg-text';
  body.innerHTML = '<div class=\"typing-dots\"><span></span><span></span><span></span></div>';

  div.appendChild(who);
  div.appendChild(body);
  el.appendChild(div);
  el.scrollTop = el.scrollHeight;
  return div;
}

function removeTyping() {
  const t = $('typing-indicator');
  if (t) t.remove();
}

// ── API calls ──────────────────────────────────────────────────────────────

async function doSend() {
  const inp = $('chat-input');
  const btn = $('send-btn');
  const msg = inp.value.trim();
  if (!msg) return;

  inp.value = '';
  inp.style.height = 'auto';
  btn.disabled = true;

  addMsg('user', msg);
  const indicator = addTyping();

  try {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: msg })
    });
    const data = await res.json();
    removeTyping();

    if (data.error) {
      addMsg('coggy', '⚠ ' + data.error);
    } else {
      addMsg('coggy', data.content || '(empty response)');
    }

    // Update ribbon from response stats
    if (data.turn != null)  $('r-turns').textContent = data.turn;
    if (data.stats) {
      $('r-atoms').textContent = fmt(data.stats.atoms);
      $('r-links').textContent = fmt(data.stats.links);
    }
    if (data.llm && data.llm.model) {
      currentModel = data.llm.model;
      $('r-model').textContent = shortModelName(currentModel);
    }
    const gr = data.semantic && data.semantic.grounding && data.semantic.grounding.concepts && data.semantic.grounding.concepts.rate;
    if (gr != null) {
      const el = $('r-ground');
      el.textContent = pct(gr);
      el.className = 'value ' + rateClass(gr);
    }

    // Refresh panels after chat
    await refreshPanels();

  } catch (err) {
    removeTyping();
    addMsg('coggy', '⚠ request failed: ' + err.message);
  } finally {
    btn.disabled = false;
    inp.focus();
  }
}

async function doRefresh() {
  await refreshPanels();
}

async function doBoot() {
  try {
    const res = await fetch('/api/boot', { method: 'POST' });
    const data = await res.json();
    addMsg('system', 'boot complete — atoms: ' + (data.atoms || '?'));
    await refreshPanels();
  } catch (err) {
    addMsg('system', 'boot failed: ' + err.message);
  }
}

async function doDump() {
  try {
    const res = await fetch('/api/state/dump', { method: 'POST', headers: {'Content-Type':'application/json'}, body: '{}' });
    const data = await res.json();
    addMsg('system', 'state dumped' + (data.path ? ': ' + data.path : ''));
  } catch (err) {
    addMsg('system', 'dump failed: ' + err.message);
  }
}

// ── Panel refresh ──────────────────────────────────────────────────────────

async function refreshPanels() {
  try {
    const [stateData, metricsData, modelsData] = await Promise.all([
      fetch('/api/state').then(r => r.json()),
      fetch('/api/metrics').then(r => r.json()),
      fetch('/api/openrouter/models').then(r => r.json()).catch(() => null)
    ]);

    renderRibbonFromState(stateData);
    renderAtomspace(stateData);
    renderMetrics(metricsData, modelsData);
    if (modelsData) renderModelSelector(modelsData);
  } catch (err) {
    console.error('refresh failed', err);
  }
}

// ── Ribbon ─────────────────────────────────────────────────────────────────

function shortModelName(m) {
  if (!m) return '—';
  // e.g. \"google/gemma-3-27b-it:free\" → \"gemma-3-27b\"
  const parts = m.split('/');
  const tail = parts[parts.length - 1];
  return tail.replace(/:.*$/, '').substring(0, 22);
}

function renderRibbonFromState(s) {
  if (!s) return;
  if (s.model) {
    currentModel = s.model;
    $('r-model').textContent = shortModelName(s.model);
  }
  if (s.atoms != null) {
    const atomCount = typeof s.atoms === 'object' ? Object.keys(s.atoms).length : s.atoms;
    $('r-atoms').textContent = fmt(atomCount);
  }
  if (s.links != null) $('r-links').textContent = fmt(s.links);

  // hyle status
  if (s.hyle) {
    const dot = $('hyle-dot');
    const stat = $('hyle-status');
    const up = s.hyle.status === 'up';
    dot.className = 'status-dot ' + (up ? 'up' : 'down');
    stat.title = 'hyle on port ' + s.hyle.port;
  }
}

// ── AtomSpace panel ────────────────────────────────────────────────────────

function renderAtomspace(s) {
  const el = $('atomspace-body');
  if (!s) { el.innerHTML = '<div class=\"empty-state\">no data</div>'; return; }

  let html = '';

  // Atom type breakdown
  if (s.atoms != null) {
    html += '<div class=\"atom-section\">';
    html += '<div class=\"atom-section-title\">atoms by type</div>';

    // atoms is a map of keyword→atom-data, so group by :atom/type
    const typeCounts = {};
    let totalAtoms = 0;

    if (typeof s.atoms === 'object' && !Array.isArray(s.atoms)) {
      const entries = Object.values(s.atoms);
      totalAtoms = entries.length;
      for (const a of entries) {
        const t = a['atom/type'] || a.type || 'concept';
        typeCounts[t] = (typeCounts[t] || 0) + 1;
      }
    }

    // Also include stats.types if available (from space-stats)
    if (s.stats && s.stats.types) {
      for (const [k, v] of Object.entries(s.stats.types)) {
        html += `<div class=\"atom-type-row\">
          <span class=\"atom-type-name\">${esc(String(k))}</span>
          <span class=\"atom-type-count\">${v}</span>
        </div>`;
      }
    } else if (Object.keys(typeCounts).length > 0) {
      for (const [k, v] of Object.entries(typeCounts).sort((a,b) => b[1]-a[1])) {
        html += `<div class=\"atom-type-row\">
          <span class=\"atom-type-name\">${esc(String(k))}</span>
          <span class=\"atom-type-count\">${v}</span>
        </div>`;
      }
    } else {
      // Flat count
      const n = typeof s.atoms === 'number' ? s.atoms : totalAtoms;
      html += `<div class=\"atom-type-row\">
        <span class=\"atom-type-name\">total</span>
        <span class=\"atom-type-count\">${n}</span>
      </div>`;
    }

    html += '</div>';
  }

  // Attentional focus
  const focus = s.focus || (s.attention && s.attention.focus);
  if (focus && focus.length > 0) {
    html += '<div class=\"atom-section\">';
    html += '<div class=\"atom-section-title\">attentional focus</div>';
    html += '<div class=\"focus-list\">';
    const maxSti = focus[0] ? (focus[0].sti || 1) : 1;
    for (const f of focus.slice(0, 10)) {
      const name = f.key ? String(f.key).replace(/^:/, '') : (f.name || '?');
      const sti = f.sti || 0;
      const barW = Math.min(100, Math.round((sti / Math.max(maxSti, 1)) * 100));
      html += `<div class=\"focus-item\">
        <span class=\"focus-name\">${esc(name)}</span>
        <div class=\"sti-bar\"><div class=\"sti-bar-fill\" style=\"width:${barW}%\"></div></div>
        <span class=\"focus-sti\">${sti.toFixed ? sti.toFixed(1) : sti}</span>
      </div>`;
    }
    html += '</div></div>';
  }

  // Links (first N)
  if (s.links != null && typeof s.links === 'object' && Array.isArray(s.links)) {
    const links = s.links.slice(0, 12);
    if (links.length > 0) {
      html += '<div class=\"atom-section\">';
      html += '<div class=\"atom-section-title\">links</div>';
      for (const lk of links) {
        const t = lk['atom/type'] || lk.type || '—';
        const a = lk.a || lk.outgoing && lk.outgoing[0] || '—';
        const b = lk.b || lk.outgoing && lk.outgoing[1] || '—';
        html += `<div class=\"link-row\">
          <span class=\"link-type-badge\">${esc(String(t))}</span>
          <span>${esc(String(a))}</span>
          <span style=\"color:var(--text-faint)\">→</span>
          <span>${esc(String(b))}</span>
        </div>`;
      }
      if (s.links.length > 12) {
        html += `<div style=\"color:var(--text-faint);font-size:10px;padding:4px\">… ${s.links.length - 12} more</div>`;
      }
      html += '</div>';
    }
  } else if (s.links != null && typeof s.links === 'number') {
    html += `<div class=\"atom-section\">
      <div class=\"atom-section-title\">links</div>
      <div class=\"atom-type-row\">
        <span class=\"atom-type-name\">total</span>
        <span class=\"atom-type-count\">${s.links}</span>
      </div>
    </div>`;
  }

  if (!html) html = '<div class=\"empty-state\">no atoms yet</div>';
  el.innerHTML = html;
}

// ── Metrics panel ──────────────────────────────────────────────────────────

function renderMetrics(m, models) {
  const el = $('metrics-body');
  if (!m) { el.innerHTML = '<div class=\"empty-state\">no data</div>'; return; }

  const parseRate = m['parse-rate'] != null ? m['parse-rate'] : null;
  const groundRate = m['avg-grounding-rate'] != null ? m['avg-grounding-rate'] : null;
  const relRate    = m['avg-relation-rate'] != null ? m['avg-relation-rate'] : null;
  const vacuums    = m['vacuum-triggers'] != null ? m['vacuum-triggers'] : 0;
  const exhaustions = m['budget-exhaustions'] != null ? m['budget-exhaustions'] : 0;
  const turns      = m['turns'] != null ? m['turns'] : 0;
  const stiFunds   = m['sti-funds'] != null ? m['sti-funds'] : null;
  const stiMax     = m['sti-max'] != null ? m['sti-max'] : null;
  const fundBal    = m['fund-balance'] || null;

  // Update ribbon grounding rate
  if (groundRate != null) {
    const el2 = $('r-ground');
    el2.textContent = pct(groundRate);
    el2.className = 'value ' + rateClass(groundRate);
  }
  if (turns > 0) $('r-turns').textContent = turns;

  let html = '<div class=\"metric-grid\">';

  html += metricCard('parse rate', pct(parseRate), rateClass(parseRate), parseRate);
  html += metricCard('ground rate', pct(groundRate), rateClass(groundRate), groundRate);
  html += metricCard('relation rate', pct(relRate), rateClass(relRate), relRate);
  html += metricCard('turns', fmt(turns), '', null);
  html += metricCard('vacuums', fmt(vacuums), vacuums > 0 ? 'warn' : '', null);
  html += metricCard('exhaustions', fmt(exhaustions), exhaustions > 0 ? 'bad' : '', null);

  if (stiFunds != null) html += metricCard('STI funds', fmt(Math.round(stiFunds)), stiFunds < -80 ? 'bad' : stiFunds < 0 ? 'warn' : '', null);
  if (fundBal) html += metricCard('fund total', fmt(Math.round(fundBal.total)), '', null);

  html += '</div>';

  // Last failure
  if (m['last-failure'] && m['last-failure'].type) {
    const f = m['last-failure'];
    html += `<div class=\"atom-section\">
      <div class=\"atom-section-title\">last failure</div>
      <div style=\"color:var(--red);font-size:11px;padding:3px 4px\">
        ${esc(String(f.type))} at turn ${esc(String(f.turn || '?'))}
      </div>
    </div>`;
  }

  // Model health table
  if (models && models.models && models.models.length > 0) {
    html += '<div class=\"atom-section\">';
    html += '<div class=\"atom-section-title\">model health</div>';
    html += '<table class=\"model-health-table\"><thead><tr>';
    html += '<th>model</th><th>ok</th><th>fail%</th><th>ms</th>';
    html += '</tr></thead><tbody>';
    for (const row of models.models.slice(0, 8)) {
      const isActive = row.model === (models.configured || currentModel);
      const cooldown = row['cooldown-ms'] > 0;
      const failPct = row['fail-rate'] ? (row['fail-rate'] * 100).toFixed(0) + '%' : '0%';
      const latency = row['avg-latency-ms'] ? Math.round(row['avg-latency-ms']) : '—';
      const statusClass = cooldown ? 'cool' : (row.failures > 0 ? 'fail' : 'ok');
      html += `<tr class=\"${isActive ? 'active-model' : ''}\">
        <td title=\"${esc(row.model)}\">${esc(shortModelName(row.model))}</td>
        <td class=\"${statusClass}\">${cooldown ? 'cool' : (row.successes > 0 ? 'ok' : '—')}</td>
        <td>${failPct}</td>
        <td>${latency}</td>
      </tr>`;
    }
    html += '</tbody></table></div>';
  }

  el.innerHTML = html;
}

function metricCard(label, value, cls, rate) {
  let barHtml = '';
  if (rate != null) {
    const w = Math.round(rate * 100);
    const color = cls === 'good' ? 'var(--green)' : cls === 'warn' ? 'var(--amber)' : cls === 'bad' ? 'var(--red)' : 'var(--accent)';
    barHtml = `<div class=\"rate-bar\"><div class=\"rate-bar-fill\" style=\"width:${w}%;background:${color}\"></div></div>`;
  }
  return `<div class=\"metric-card\">
    <div class=\"m-label\">${esc(label)}</div>
    <div class=\"m-value ${cls}\">${esc(String(value))}</div>
    ${barHtml}
  </div>`;
}

// ── Model selector ─────────────────────────────────────────────────────────

function renderModelSelector(data) {
  const sel = $('model-select');
  const configured = data.configured || currentModel;
  const ranked = data.ranked || (data.models ? data.models.map(m => m.model) : []);

  if (!ranked.length) return;

  // Rebuild options only if the model list changed
  const existing = Array.from(sel.options).map(o => o.value);
  const incoming = ranked;
  const same = existing.length === incoming.length && existing.every((v,i) => v === incoming[i]);
  if (same) {
    sel.value = configured;
    return;
  }

  sel.innerHTML = '';
  for (const m of ranked) {
    const opt = document.createElement('option');
    opt.value = m;
    opt.textContent = shortModelName(m);
    opt.title = m;
    sel.appendChild(opt);
  }
  if (configured) sel.value = configured;
}

$('model-select').addEventListener('change', async function() {
  const m = this.value;
  if (!m) return;
  try {
    const res = await fetch('/api/model', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: m })
    });
    const data = await res.json();
    if (data.ok) {
      currentModel = data.model;
      $('r-model').textContent = shortModelName(data.model);
      addMsg('system', 'model → ' + shortModelName(data.model));
    }
  } catch (err) {
    addMsg('system', 'model switch failed: ' + err.message);
  }
});

$('domain-select').addEventListener('change', async function() {
  const d = this.value;
  if (!d) return;
  try {
    const res = await fetch('/api/domain', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ domain: d })
    });
    const data = await res.json();
    if (data.ok) {
      addMsg('system', 'domain → ' + (data.domain || d));
      await refreshPanels();
    } else {
      addMsg('system', 'domain activation failed: ' + (data.error || '?'));
    }
  } catch (err) {
    addMsg('system', 'domain switch failed: ' + err.message);
  }
  // Reset selector (domain is active, not sticky selection)
  this.value = '';
});

// ── Input handling ─────────────────────────────────────────────────────────

$('chat-input').addEventListener('keydown', function(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    doSend();
  }
});

$('chat-input').addEventListener('input', function() {
  this.style.height = 'auto';
  this.style.height = Math.min(this.scrollHeight, 120) + 'px';
});

// ── Init ───────────────────────────────────────────────────────────────────

async function init() {
  await refreshPanels();
  // Poll every 15s
  setInterval(refreshPanels, 15000);
}

init();
</script>
</body>
</html>
")
;; =============================================================================
;; Canvas — Voronoi Attention Space
;; =============================================================================

(def canvas-html
  "<!DOCTYPE html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
<title>coggy canvas — attention space</title>
<style>
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

:root {
  --bg: #06080c;
  --surface: #0c1018;
  --border: #1a2235;
  --text: #c9d1d9;
  --dim: #4a5568;
  --faint: #2a3444;
  --accent: #58a6ff;
  --green: #3fb950;
  --amber: #d29922;
  --red: #f85149;
  --teal: #2dd4bf;
  --violet: #a78bfa;
  --rose: #fb7185;
  --font: 'JetBrains Mono', 'Fira Code', ui-monospace, monospace;
}

html, body { height: 100%; background: var(--bg); color: var(--text); font-family: var(--font); overflow: hidden; }

#app { display: grid; grid-template-rows: 32px 1fr 180px; height: 100vh; }

/* Ribbon */
#ribbon {
  display: flex; align-items: center; gap: 16px;
  padding: 0 12px; background: var(--surface);
  border-bottom: 1px solid var(--border); font-size: 11px;
}
.r-logo { color: var(--accent); font-weight: 700; font-size: 13px; letter-spacing: 0.06em; }
.r-stat { color: var(--dim); }
.r-stat b { color: var(--text); font-weight: 600; }
.r-spacer { flex: 1; }
.r-btn {
  background: none; border: 1px solid var(--border); color: var(--dim);
  font: inherit; font-size: 10px; padding: 2px 8px; border-radius: 3px; cursor: pointer;
}
.r-btn:hover { border-color: var(--accent); color: var(--accent); }

/* Canvas */
#canvas-wrap { position: relative; overflow: hidden; }
canvas { display: block; width: 100%; height: 100%; }

/* Tooltip */
#tooltip {
  position: fixed; pointer-events: none; z-index: 100;
  background: #0d1520; border: 1px solid var(--border);
  padding: 8px 12px; border-radius: 4px; font-size: 11px;
  max-width: 280px; display: none; line-height: 1.5;
}
#tooltip .tt-name { color: var(--accent); font-weight: 700; font-size: 12px; }
#tooltip .tt-type { color: var(--dim); font-size: 10px; text-transform: uppercase; letter-spacing: 0.06em; }
#tooltip .tt-row { display: flex; justify-content: space-between; gap: 16px; }
#tooltip .tt-label { color: var(--dim); }
#tooltip .tt-val { color: var(--text); font-weight: 600; }
#tooltip .tt-val.hi { color: var(--green); }
#tooltip .tt-val.lo { color: var(--red); }

/* Bottom panel — wisdom stream */
#stream {
  display: grid; grid-template-columns: 260px 1fr;
  border-top: 1px solid var(--border); overflow: hidden;
}

#focus-list {
  background: var(--surface); border-right: 1px solid var(--border);
  overflow-y: auto; padding: 8px;
}
.fl-title { font-size: 10px; color: var(--dim); text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 6px; }
.fl-item {
  display: grid; grid-template-columns: 1fr auto;
  padding: 4px 6px; border-radius: 3px; font-size: 11px;
  cursor: pointer; margin-bottom: 2px;
}
.fl-item:hover { background: #141c28; }
.fl-item .name { color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fl-item .sti { color: var(--amber); font-weight: 600; font-size: 10px; }

#wisdom {
  overflow-y: auto; padding: 10px 14px; font-size: 11px; line-height: 1.65;
}
.w-entry { margin-bottom: 8px; padding: 6px 10px; background: var(--surface); border-radius: 4px; border-left: 2px solid var(--border); }
.w-entry.active { border-left-color: var(--accent); }
.w-source { font-size: 10px; color: var(--dim); text-transform: uppercase; letter-spacing: 0.04em; }
.w-text { color: var(--text); }
</style>
</head>
<body>

<div id=\"app\">

<div id=\"ribbon\">
  <span class=\"r-logo\">coggy canvas</span>
  <span class=\"r-stat\">nodes: <b id=\"r-nodes\">0</b></span>
  <span class=\"r-stat\">focus: <b id=\"r-focus\">0</b></span>
  <span class=\"r-stat\">STI fund: <b id=\"r-sti\">—</b></span>
  <span class=\"r-spacer\"></span>
  <button class=\"r-btn\" onclick=\"toggleSource('atomspace')\">atoms</button>
  <button class=\"r-btn\" onclick=\"toggleSource('deskfloor')\">projects</button>
  <button class=\"r-btn\" onclick=\"toggleSource('tmux')\">sessions</button>
  <button class=\"r-btn\" onclick=\"refresh()\">refresh</button>
  <a class=\"r-btn\" href=\"/\">chat</a>
</div>

<div id=\"canvas-wrap\">
  <canvas id=\"c\"></canvas>
</div>

<div id=\"stream\">
  <div id=\"focus-list\">
    <div class=\"fl-title\">attentional focus</div>
    <div id=\"fl-items\"></div>
  </div>
  <div id=\"wisdom\">
    <div class=\"w-entry active\">
      <div class=\"w-source\">canvas</div>
      <div class=\"w-text\">Hover or click bubbles to explore the attention space. Size = STI. Color = source type. Proximity = relational closeness.</div>
    </div>
  </div>
</div>

</div>

<div id=\"tooltip\"></div>

<script>
'use strict';

const C = document.getElementById('c');
const ctx = C.getContext('2d');
const tooltip = document.getElementById('tooltip');
let W, H, nodes = [], links = [], focusSet = new Set();
let hiddenSources = new Set();
let hoveredNode = null, selectedNode = null;
let mouse = { x: -1, y: -1 };

const COLORS = {
  ConceptNode:    { fill: '#1a3a5c', stroke: '#58a6ff', text: '#8cc4ff' },
  PredicateNode:  { fill: '#2a1a3c', stroke: '#a78bfa', text: '#c4a6ff' },
  InheritanceLink:{ fill: '#1a2a2a', stroke: '#2dd4bf', text: '#6ee7cc' },
  deskfloor:      { fill: '#1c2a1c', stroke: '#3fb950', text: '#6ee77a' },
  tmux:           { fill: '#2a2210', stroke: '#d29922', text: '#f0c050' },
  default:        { fill: '#1a1e28', stroke: '#4a5568', text: '#8899aa' }
};

function colorFor(n) {
  if (n.source === 'deskfloor') return COLORS.deskfloor;
  if (n.source === 'tmux') return COLORS.tmux;
  return COLORS[n.type] || COLORS.default;
}

function resize() {
  const wrap = document.getElementById('canvas-wrap');
  W = wrap.clientWidth; H = wrap.clientHeight;
  C.width = W * devicePixelRatio;
  C.height = H * devicePixelRatio;
  C.style.width = W + 'px';
  C.style.height = H + 'px';
  ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
}

function radiusFor(n) {
  const base = Math.max(8, Math.min(60, (n.sti || 1) * 3.5 + 10));
  if (n === selectedNode) return base * 1.15;
  if (n === hoveredNode) return base * 1.08;
  return base;
}

function initPositions() {
  for (const n of nodes) {
    if (n.x == null) {
      n.x = W * 0.1 + Math.random() * W * 0.8;
      n.y = H * 0.1 + Math.random() * H * 0.8;
    }
    n.vx = 0; n.vy = 0;
  }
}

function simulate() {
  const visible = nodes.filter(n => !hiddenSources.has(n.source));
  const dt = 0.3, friction = 0.88, gravity = 0.02;

  // Gravity toward center
  for (const n of visible) {
    n.vx += (W / 2 - n.x) * gravity * dt;
    n.vy += (H / 2 - n.y) * gravity * dt;
  }

  // Repulsion between nodes
  for (let i = 0; i < visible.length; i++) {
    for (let j = i + 1; j < visible.length; j++) {
      const a = visible[i], b = visible[j];
      let dx = b.x - a.x, dy = b.y - a.y;
      let d = Math.sqrt(dx * dx + dy * dy) || 1;
      const minDist = radiusFor(a) + radiusFor(b) + 4;
      if (d < minDist) {
        const force = (minDist - d) / d * 0.5;
        const fx = dx * force, fy = dy * force;
        a.vx -= fx; a.vy -= fy;
        b.vx += fx; b.vy += fy;
      }
    }
  }

  // Apply velocity
  for (const n of visible) {
    n.vx *= friction; n.vy *= friction;
    n.x += n.vx; n.y += n.vy;
    // Boundary
    const r = radiusFor(n);
    n.x = Math.max(r, Math.min(W - r, n.x));
    n.y = Math.max(r, Math.min(H - r, n.y));
  }
}

function draw() {
  ctx.clearRect(0, 0, W, H);

  // Background grid
  ctx.strokeStyle = '#0a0f18';
  ctx.lineWidth = 0.5;
  for (let x = 0; x < W; x += 40) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, H); ctx.stroke(); }
  for (let y = 0; y < H; y += 40) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke(); }

  const visible = nodes.filter(n => !hiddenSources.has(n.source));

  // Draw connections for focus atoms
  ctx.lineWidth = 0.5;
  for (const n of visible) {
    if (!focusSet.has(n.id)) continue;
    for (const m of visible) {
      if (m === n || !focusSet.has(m.id)) continue;
      ctx.strokeStyle = 'rgba(88,166,255,0.08)';
      ctx.beginPath(); ctx.moveTo(n.x, n.y); ctx.lineTo(m.x, m.y); ctx.stroke();
    }
  }

  // Draw bubbles
  for (const n of visible) {
    const r = radiusFor(n);
    const c = colorFor(n);
    const inFocus = focusSet.has(n.id);
    const isHovered = n === hoveredNode;
    const isSelected = n === selectedNode;

    // Glow for focused atoms
    if (inFocus || isSelected) {
      ctx.save();
      ctx.shadowColor = c.stroke;
      ctx.shadowBlur = isSelected ? 20 : 12;
      ctx.beginPath(); ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.fillStyle = c.fill;
      ctx.fill();
      ctx.restore();
    }

    // Bubble body
    ctx.beginPath(); ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
    ctx.fillStyle = c.fill;
    ctx.fill();

    // Border
    ctx.lineWidth = isHovered ? 2 : (inFocus ? 1.5 : 0.8);
    ctx.strokeStyle = isHovered ? '#fff' : c.stroke;
    ctx.globalAlpha = inFocus ? 1.0 : 0.6;
    ctx.stroke();
    ctx.globalAlpha = 1.0;

    // Confidence ring (partial arc)
    if (n.confidence > 0) {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 2, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * n.confidence);
      ctx.strokeStyle = c.stroke;
      ctx.lineWidth = 1.5;
      ctx.globalAlpha = 0.4;
      ctx.stroke();
      ctx.globalAlpha = 1.0;
    }

    // Label
    if (r > 14) {
      const label = n.name || n.id;
      const maxChars = Math.floor(r / 3.5);
      const display = label.length > maxChars ? label.slice(0, maxChars) : label;
      ctx.font = `${Math.max(8, Math.min(12, r * 0.28))}px ${getComputedStyle(document.body).fontFamily}`;
      ctx.fillStyle = c.text;
      ctx.globalAlpha = inFocus ? 1.0 : 0.7;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(display, n.x, n.y);
      ctx.globalAlpha = 1.0;
    }
  }
}

function frame() {
  simulate();
  draw();
  requestAnimationFrame(frame);
}

// Interaction
C.addEventListener('mousemove', e => {
  const rect = C.getBoundingClientRect();
  mouse.x = e.clientX - rect.left;
  mouse.y = e.clientY - rect.top;

  const visible = nodes.filter(n => !hiddenSources.has(n.source));
  hoveredNode = null;
  for (const n of visible) {
    const dx = mouse.x - n.x, dy = mouse.y - n.y;
    if (dx * dx + dy * dy < radiusFor(n) ** 2) {
      hoveredNode = n;
      break;
    }
  }

  if (hoveredNode) {
    const n = hoveredNode;
    const c = colorFor(n);
    tooltip.style.display = 'block';
    tooltip.style.left = (e.clientX + 14) + 'px';
    tooltip.style.top = (e.clientY - 10) + 'px';
    tooltip.innerHTML = `
      <div class=\\\"tt-name\\\">${esc(n.name || n.id)}</div>
      <div class=\\\"tt-type\\\">${esc(n.type)} &middot; ${esc(n.source)}</div>
      <div class=\\\"tt-row\\\"><span class=\\\"tt-label\\\">STI</span><span class=\\\"tt-val ${n.sti > 5 ? 'hi' : ''}\\\">${n.sti.toFixed(1)}</span></div>
      <div class=\\\"tt-row\\\"><span class=\\\"tt-label\\\">strength</span><span class=\\\"tt-val\\\">${(n.strength || 0).toFixed(2)}</span></div>
      <div class=\\\"tt-row\\\"><span class=\\\"tt-label\\\">confidence</span><span class=\\\"tt-val ${n.confidence < 0.3 ? 'lo' : ''}\\\">${(n.confidence || 0).toFixed(2)}</span></div>
      ${n.branch ? '<div class=\\\"tt-row\\\"><span class=\\\"tt-label\\\">branch</span><span class=\\\"tt-val\\\">' + esc(n.branch) + '</span></div>' : ''}
      ${n.dirty ? '<div class=\\\"tt-row\\\"><span class=\\\"tt-label\\\">dirty</span><span class=\\\"tt-val lo\\\">yes</span></div>' : ''}
    `;
    C.style.cursor = 'pointer';
  } else {
    tooltip.style.display = 'none';
    C.style.cursor = 'default';
  }
});

C.addEventListener('click', e => {
  if (hoveredNode) {
    selectedNode = selectedNode === hoveredNode ? null : hoveredNode;
    if (selectedNode) showWisdom(selectedNode);
  }
});

C.addEventListener('mouseleave', () => {
  hoveredNode = null;
  tooltip.style.display = 'none';
});

function esc(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function showWisdom(n) {
  const el = document.getElementById('wisdom');
  const c = colorFor(n);
  let html = `<div class=\\\"w-entry active\\\" style=\\\"border-left-color:${c.stroke}\\\">
    <div class=\\\"w-source\\\">${esc(n.type)} &middot; ${esc(n.source)}</div>
    <div class=\\\"w-text\\\"><b>${esc(n.name || n.id)}</b></div>
  </div>`;

  if (n.source === 'deskfloor' && n.path) {
    html += `<div class=\\\"w-entry\\\"><div class=\\\"w-source\\\">path</div><div class=\\\"w-text\\\">${esc(n.path)}</div></div>`;
  }

  // Show related atoms from links
  const related = nodes.filter(m => m !== n && m.source === n.source).slice(0, 5);
  if (related.length) {
    html += '<div class=\\\"w-entry\\\"><div class=\\\"w-source\\\">nearby</div><div class=\\\"w-text\\\">' +
      related.map(r => esc(r.name || r.id)).join(' &middot; ') + '</div></div>';
  }

  el.innerHTML = html;
}

function renderFocusList() {
  const el = document.getElementById('fl-items');
  const sorted = [...nodes].sort((a, b) => (b.sti || 0) - (a.sti || 0)).slice(0, 15);
  el.innerHTML = sorted.map(n =>
    `<div class=\\\"fl-item\\\" onclick=\\\"selectById('${esc(n.id)}')\\\">
      <span class=\\\"name\\\">${esc(n.name || n.id)}</span>
      <span class=\\\"sti\\\">${(n.sti || 0).toFixed(1)}</span>
    </div>`
  ).join('');
}

function selectById(id) {
  selectedNode = nodes.find(n => n.id === id) || null;
  if (selectedNode) showWisdom(selectedNode);
}

function toggleSource(src) {
  if (hiddenSources.has(src)) hiddenSources.delete(src);
  else hiddenSources.add(src);
}

async function refresh() {
  try {
    const res = await fetch('/api/fleet');
    const data = await res.json();

    // Merge new data preserving positions
    const oldMap = {};
    for (const n of nodes) oldMap[n.id] = n;

    nodes = (data.nodes || []).map(n => {
      const old = oldMap[n.id];
      return { ...n, x: old ? old.x : null, y: old ? old.y : null, vx: 0, vy: 0 };
    });

    focusSet = new Set((data.focus || []).map(k => typeof k === 'string' ? k.replace(/^:/, '') : k));
    links = data.links || [];

    initPositions();
    renderFocusList();

    document.getElementById('r-nodes').textContent = nodes.length;
    document.getElementById('r-focus').textContent = focusSet.size;
    if (data.budget) document.getElementById('r-sti').textContent = (data.budget['sti-funds'] || 0).toFixed(0);
  } catch (err) {
    console.error('fleet refresh failed', err);
  }
}

window.addEventListener('resize', resize);
resize();
refresh();
setInterval(refresh, 10000);
frame();
</script>
</body>
</html>
")

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
      (try
        (repl/dump-state!)
        (when (zero? (mod (max 1 (:turn result)) 3))
          (repl/dump-state-versioned!))
        (catch Exception e
          (log! (str "snapshot dump failed: " (.getMessage e)))))
      (log! (str "→ turn " (:turn result)
                 " | atoms " (get-in result [:stats :atoms])
                 " | ground " (get-in result [:semantic :grounding :concepts :rate])))
      (json-response result))))

(defn handle-state []
  (let [space @(repl/space)
        bank @(repl/bank)
        hyle-port (or (System/getenv "HYLE_PORT")
                      (System/getProperty "HYLE_PORT")
                      "8420")
        hyle-ok? (try
                   (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. (str "http://localhost:" hyle-port "/health")))]
                     (.setRequestMethod conn "GET")
                     (.setConnectTimeout conn 1200)
                     (.setReadTimeout conn 1200)
                     (= 200 (.getResponseCode conn)))
                   (catch Exception _ false))]
    (json-response {:atoms (:atoms space)
                    :links (count (:link-map space))
                    :attention (:attention bank)
                    :focus (:focus bank)
                    :budget {:sti-funds (:sti-funds bank)
                             :sti-max (:sti-max bank)}
                    :fund-balance (att/fund-balance (repl/bank))
                    :model (:model @llm/config)
                    :hyle {:port hyle-port
                           :status (if hyle-ok? "up" "down")}})))

(defn handle-metrics []
  (let [m (sem/metrics-summary)
        bank @(repl/bank)]
    (json-response (assoc m
                          :sti-funds (:sti-funds bank)
                          :sti-max (:sti-max bank)
                          :fund-balance (att/fund-balance (repl/bank))))))

(defn handle-boot []
  (let [space (repl/space)
        bank (repl/bank)]
    (boot/seed-ontology! space bank)
    (log! "boot ritual complete")
    (let [stats (as/space-stats space)]
      (json-response stats))))

(defn handle-dump-state []
  (json-response (repl/dump-state-versioned!)))

(defn handle-load-state []
  (json-response (repl/load-state!)))

(defn handle-load-latest-state []
  (json-response (repl/load-latest-snapshot!)))

(defn handle-list-snapshots []
  (json-response {:snapshots (repl/list-snapshots)}))

(defn handle-model [body]
  (if-let [m (:model body)]
    (do
      (llm/configure! {:model (str m)})
      (json-response {:ok true :model (:model @llm/config)}))
    (json-response {:ok false :error "missing model"} :status 400)))

(defn handle-domain [body]
  (if-let [d (:domain body)]
    (json-response (repl/activate-domain! d))
    (json-response {:ok false :error "missing domain"} :status 400)))

(defn handle-ibid-status []
  (json-response (ibid/status)))

(defn handle-ibid-ingest [body]
  (let [p (:path body)
        out (ibid/ingest-corpus! (repl/space) (repl/bank) p)]
    (json-response out :status (if (:ok out) 200 400))))

(defn handle-fleet []
  "Aggregate cross-project data from deskfloor + local atomspace.
   Produces nodes for the voronoi attention canvas."
  (let [space @(repl/space)
        bank @(repl/bank)
        ;; Local atoms as attention nodes
        atom-nodes (->> (:atoms space)
                        (map (fn [[k a]]
                               (let [sti (get-in bank [:attention k :av/sti] 0.0)
                                     lti (get-in bank [:attention k :av/lti] 0.0)]
                                 {:id (name k)
                                  :type (name (:atom/type a))
                                  :strength (get-in a [:atom/tv :tv/strength] 0.5)
                                  :confidence (get-in a [:atom/tv :tv/confidence] 0.1)
                                  :sti sti
                                  :lti lti
                                  :source "atomspace"})))
                        (sort-by :sti >)
                        (take 40))
        ;; Try to pull project data from deskfloor
        projects (try
                   (let [conn ^java.net.HttpURLConnection
                         (.openConnection (java.net.URL. "http://localhost:9900/projects"))]
                     (.setConnectTimeout conn 2000)
                     (.setReadTimeout conn 2000)
                     (let [body (slurp (.getInputStream conn))]
                       (->> (json/parse-string body true)
                            (map (fn [[k v]]
                                   {:id (name k)
                                    :type (or (:type v) "unknown")
                                    :path (:path v)
                                    :dirty (get-in v [:git :dirty] false)
                                    :branch (get-in v [:git :branch] "?")
                                    :sti (if (:dirty (or (:git v) {})) 8.0 2.0)
                                    :strength 0.6
                                    :confidence 0.4
                                    :source "deskfloor"})))))
                   (catch Exception _ []))
        ;; Try to pull tmux sessions
        sessions (try
                   (let [out (-> (Runtime/getRuntime)
                                 (.exec (into-array String ["tmux" "list-sessions" "-F" "#{session_name}:#{session_windows}"]))
                                 (.getInputStream)
                                 slurp
                                 str/trim)]
                     (->> (str/split-lines out)
                          (map (fn [line]
                                 (let [[n w] (str/split line #":")]
                                   {:id (str "session:" n)
                                    :type "tmux-session"
                                    :name n
                                    :windows (parse-long (or w "1"))
                                    :sti 1.0
                                    :strength 0.5
                                    :confidence 0.7
                                    :source "tmux"})))))
                   (catch Exception _ []))]
    (json-response {:nodes (concat atom-nodes projects sessions)
                    :links (vals (:link-map space))
                    :focus (:focus bank)
                    :budget {:sti-funds (:sti-funds bank)
                             :sti-max (:sti-max bank)}})))

(defn handle-integration-catalog []
  (json-response
   {:projects
    [{:id "metaculus-middleware"
      :kind "predictions"
      :status "stubbed"
      :hook "/api/domain forecast"
      :note "time-bounded claims + calibration traces"}
     {:id "ibid-legal"
      :kind "legal reasoning"
      :status "seeded"
      :hook "/api/ibid/ingest"
      :note "IRAC + citation-chain + adversarial counterarguments"}
     {:id "authority-feeds"
      :kind "feeds/corpora"
      :status "planned"
      :hook "resources/ibid/legal-corpus.edn"
      :note "court and regulator record mirrors"}
     {:id "hott-knowledge-layers"
      :kind "formal methods"
      :status "planned"
      :hook "/api/domain formal"
      :note "type-theoretic fragments as context partitions"}
     {:id "ops-dashboard"
      :kind "dashboards"
      :status "active"
      :hook "/api/openrouter/models + /api/metrics"
      :note "latency/quota/budget and grounding health"}]}))

;; =============================================================================
;; Agent API Handlers — direct atomspace interaction for external agents
;; =============================================================================

(defn handle-observe [body]
  (let [source (or (:source body) "unknown-agent")
        observation (dissoc body :source)]
    (repl/log-event! :agent-observe {:source source
                                      :concepts (:concepts observation)})
    (log! (str "◀ observe from " source ": " (count (:concepts observation)) " concepts"))
    (let [result (sem/commit-observation! (repl/space) (repl/bank) observation)]
      (log! (str "▶ grounded " (get-in result [:grounding :concepts :rate]) " of concepts"))
      (json-response (assoc result
                            :source source
                            :stats (as/space-stats (repl/space)))))))

(defn handle-query [body]
  (let [concepts (or (:concepts body) [])
        opts {:include-links? (not (false? (:include_links body)))
              :include-attention? (not (false? (:include_attention body)))}]
    (json-response {:results (sem/query-atoms (repl/space) (repl/bank) concepts opts)
                    :focus (att/focus-atoms (repl/bank))
                    :stats (as/space-stats (repl/space))})))

(defn handle-stimulate [body]
  (let [atoms-map (or (:atoms body) {})]
    (doseq [[k amount] atoms-map]
      (att/stimulate! (repl/bank) (keyword (name k)) (double amount)))
    (att/update-focus! (repl/bank))
    (repl/log-event! :agent-stimulate {:atoms (keys atoms-map)})
    (json-response {:focus (att/focus-atoms (repl/bank))
                    :fund-balance (att/fund-balance (repl/bank))})))

(defn handle-focus []
  (json-response {:focus (att/focus-atoms (repl/bank))
                  :fund-balance (att/fund-balance (repl/bank))}))

(defn handle-atom-lookup [name]
  (let [atom (as/get-atom (repl/space) name)
        k (keyword name)
        bank (repl/bank)]
    (if atom
      (json-response {:name name
                      :atom atom
                      :links (as/query-links (repl/space)
                               (fn [l] (or (= k (att/link-source-key l))
                                           (some #{k} (att/link-atom-keys l)))))
                      :sti (get-in @bank [:attention k :av/sti] 0.0)
                      :in-focus? (boolean (att/in-focus? bank k))})
      (json-response {:name name :found? false} :status 404))))

(defn handler [{:keys [uri request-method body]}]
  (try
    (case [request-method uri]
      [:get "/"]          {:status 200
                           :headers {"Content-Type" "text/html"}
                           :body index-html}
      [:get "/health"]    (json-response {:status "ok"})
      [:get "/canvas"]    {:status 200
                           :headers {"Content-Type" "text/html"}
                           :body canvas-html}
      [:get "/api/fleet"] (handle-fleet)
      [:get "/api/state"] (handle-state)
      [:get "/api/state/dump"] (handle-dump-state)
      [:get "/api/state/load"] (handle-load-state)
      [:get "/api/state/snapshots"] (handle-list-snapshots)
      [:get "/api/logs"]  (json-response (:logs @server-state))
      [:get "/api/metrics"] (handle-metrics)
      [:get "/api/openrouter/status"] (json-response (llm/doctor :json? false :silent? true))
      [:get "/api/openrouter/models"] (json-response (llm/model-health-report))
      [:get "/api/ibid/status"] (handle-ibid-status)
      [:get "/api/integrations/catalog"] (handle-integration-catalog)
      [:get "/api/smoke"]     (let [checks (bench/smoke-check (repl/space) (repl/bank))]
                                (json-response (bench/smoke-summary checks)))
      [:get "/api/haywire"]   (json-response (bench/detect-haywire))
      [:get "/api/evidence"]  (json-response (bench/recent-evidence 20))
      [:get "/api/events"]    (json-response {:events (repl/recent-events 50)})

      [:post "/api/chat"] (let [body (json/parse-string (slurp body) true)]
                            (handle-chat body))
      [:post "/api/boot"] (handle-boot)
      [:post "/api/state/dump"] (let [body (json/parse-string (slurp body) true)
                                      mode (str/lower-case (str (or (:mode body) "versioned")))]
                                  (json-response (if (= mode "rolling")
                                                   (repl/dump-state!)
                                                   (repl/dump-state-versioned!))))
      [:post "/api/state/load"] (let [body (json/parse-string (slurp body) true)
                                      p (:path body)
                                      latest? (true? (:latest body))]
                                  (json-response (cond
                                                   (seq (str p)) (repl/load-state! p)
                                                   latest? (repl/load-latest-snapshot!)
                                                   :else (repl/load-state!))))
      [:post "/api/model"] (let [body (json/parse-string (slurp body) true)]
                             (handle-model body))
      [:post "/api/domain"] (let [body (json/parse-string (slurp body) true)]
                              (handle-domain body))
      [:post "/api/ibid/ingest"] (let [body (json/parse-string (slurp body) true)]
                                   (handle-ibid-ingest body))

      ;; Agent API
      [:get "/api/focus"]    (handle-focus)
      [:post "/api/observe"] (let [body (json/parse-string (slurp body) true)]
                               (handle-observe body))
      [:post "/api/query"]   (let [body (json/parse-string (slurp body) true)]
                               (handle-query body))
      [:post "/api/stimulate"] (let [body (json/parse-string (slurp body) true)]
                                 (handle-stimulate body))

      [:options "/api/chat"] {:status 200
                              :headers {"Access-Control-Allow-Origin" "*"
                                        "Access-Control-Allow-Methods" "POST"
                                        "Access-Control-Allow-Headers" "Content-Type"}}
      [:options "/api/observe"] {:status 200
                                 :headers {"Access-Control-Allow-Origin" "*"
                                           "Access-Control-Allow-Methods" "POST"
                                           "Access-Control-Allow-Headers" "Content-Type"}}
      [:options "/api/query"] {:status 200
                                :headers {"Access-Control-Allow-Origin" "*"
                                          "Access-Control-Allow-Methods" "POST"
                                          "Access-Control-Allow-Headers" "Content-Type"}}
      [:options "/api/stimulate"] {:status 200
                                   :headers {"Access-Control-Allow-Origin" "*"
                                             "Access-Control-Allow-Methods" "POST"
                                             "Access-Control-Allow-Headers" "Content-Type"}}

      ;; Dynamic routes (prefix matching)
      (cond
        (and (= :get request-method)
             (str/starts-with? uri "/api/atoms/"))
        (let [name (subs uri (count "/api/atoms/"))]
          (handle-atom-lookup (java.net.URLDecoder/decode name "UTF-8")))

        :else
        {:status 404 :body "not found"}))
    (catch Exception e
      (log! (str "ERROR: " (.getMessage e)))
      (json-response {:error (.getMessage e)} :status 500))))

;; =============================================================================
;; Server
;; =============================================================================

(defn start! [port]
  (println (str "coggy web UI on http://localhost:" port))
  (log! (str "server starting on port " port))
  (srv/run-server handler {:ip "0.0.0.0" :port port})
  @(promise))
