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
          <option value=\"plasmid\">plasmid</option>
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
  const ltiFunds   = m['lti-funds'] != null ? m['lti-funds'] : null;

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

  if (stiFunds != null) html += metricCard('STI funds', fmt(Math.round(stiFunds)), '', null);
  if (ltiFunds != null) html += metricCard('LTI funds', fmt(Math.round(ltiFunds)), '', null);

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
                    :links (count (:links space))
                    :attention (:attention bank)
                    :focus (:focus bank)
                    :budget {:sti-funds (:sti-funds bank)
                             :lti-funds (:lti-funds bank)}
                    :model (:model @llm/config)
                    :hyle {:port hyle-port
                           :status (if hyle-ok? "up" "down")}})))

(defn handle-metrics []
  (let [m (sem/metrics-summary)
        bank @(repl/bank)]
    (json-response (assoc m
                          :sti-funds (:sti-funds bank)
                          :lti-funds (:lti-funds bank)))))

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

(defn handler [{:keys [uri request-method body]}]
  (try
    (case [request-method uri]
      [:get "/"]          {:status 200
                           :headers {"Content-Type" "text/html"}
                           :body index-html}
      [:get "/health"]    (json-response {:status "ok"})
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
  (srv/run-server handler {:ip "0.0.0.0" :port port})
  @(promise))
