(ns coggy.tmux-dash
  "Live tmux pane dashboard — Clojure fullstack.

   Captures pane content via tmux capture-pane, serves as JSON.
   Frontend polls with content hashes for efficient diff-only updates.
   Panes are semantically styled by role/session."
  (:require [org.httpkit.server :as srv]
            [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.process :as proc]))

;; =============================================================================
;; Tmux Capture
;; =============================================================================

(defn sh [& args]
  (let [r (apply proc/shell {:out :string :err :string :continue true} args)]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

(defn list-sessions []
  (when-let [out (sh "tmux" "list-sessions" "-F" "#{session_name}")]
    (str/split-lines out)))

(defn list-windows [session]
  (when-let [out (sh "tmux" "list-windows" "-t" session
                     "-F" "#{window_index}|#{window_name}|#{window_active}")]
    (mapv (fn [line]
            (let [[idx nm active] (str/split line #"\|")]
              {:index (parse-long idx) :name nm :active (= "1" active)}))
          (str/split-lines out))))

(defn list-panes [session window-idx]
  (when-let [out (sh "tmux" "list-panes" "-t" (str session ":" window-idx)
                     "-F" "#{pane_index}|#{pane_title}|#{pane_width}|#{pane_height}|#{pane_active}|#{pane_current_command}")]
    (mapv (fn [line]
            (let [[idx title w h active cmd] (str/split line #"\|")]
              {:index (parse-long idx)
               :title (or title "")
               :width (parse-long w)
               :height (parse-long h)
               :active (= "1" active)
               :command (or cmd "")}))
          (str/split-lines out))))

(defn capture-pane
  "Capture pane content. Returns string."
  [session window-idx pane-idx]
  (sh "tmux" "capture-pane" "-t" (str session ":" window-idx "." pane-idx)
      "-p" "-e"))  ;; -e preserves escape sequences

(defn capture-pane-plain
  "Capture pane content without escape sequences."
  [session window-idx pane-idx]
  (sh "tmux" "capture-pane" "-t" (str session ":" window-idx "." pane-idx) "-p"))

;; =============================================================================
;; Pane Manifest — which panes to show
;; =============================================================================

(def tracked-sessions
  "Sessions and windows to track. nil for :windows means all."
  [{:session "jacobson"   :windows nil}
   {:session "traceboard" :windows nil}
   {:session "coggy"      :windows nil}])

(defn role-style
  "Map pane name/title to a semantic style class."
  [session window-name pane-title]
  (let [n (str/lower-case (or window-name ""))
        t (str/lower-case (or pane-title ""))]
    (cond
      ;; Jacobson roles
      (str/includes? n "oracle")     "role-oracle"
      (str/includes? n "librarian")  "role-librarian"
      (str/includes? n "actor")      "role-actor"
      (str/includes? n "simulator")  "role-simulator"
      (str/includes? n "instrument") "role-instrument"
      (str/includes? n "partner")    "role-partner"
      (str/includes? n "organism")   "role-organism"
      (str/includes? n "logician")   "role-logician"
      (str/includes? n "router")     "role-router"
      (str/includes? n "monitor")    "role-monitor"
      ;; Traceboard
      (str/includes? t "hyle-pulse")     "stream-pulse"
      (str/includes? t "hyle-git")       "stream-git"
      (str/includes? t "coggy-inference") "stream-inference"
      ;; Coggy
      (str/includes? n "claude")   "coggy-claude"
      (str/includes? n "node")     "coggy-node"
      (str/includes? n "bash")     "coggy-bash"
      ;; Fallback
      (= session "jacobson")   "role-default"
      (= session "traceboard") "stream-default"
      :else "pane-default")))

;; =============================================================================
;; Snapshot — capture all tracked panes with hashes
;; =============================================================================

(defonce pane-cache (atom {}))  ;; {pane-id -> {:content :hash}}

(defn pane-id [session widx pidx]
  (str session ":" widx "." pidx))

(defn content-hash [^String s]
  (when s
    (str (hash s))))

(defn capture-all!
  "Capture all tracked panes. Returns manifest with content + hashes."
  []
  (let [results (atom [])]
    (doseq [{:keys [session windows]} tracked-sessions]
      (when-let [wins (list-windows session)]
        (doseq [{win-idx :index win-name :name win-active :active} wins
                :when (or (nil? windows) (contains? (set windows) win-idx))]
          (when-let [panes (list-panes session win-idx)]
            (doseq [{pane-idx :index :keys [title width height active command]} panes]
              (let [pid (pane-id session win-idx pane-idx)
                    content (capture-pane-plain session win-idx pane-idx)
                    h (content-hash content)]
                (swap! pane-cache assoc pid {:content content :hash h})
                (swap! results conj
                       {:id pid
                        :session session
                        :window win-idx
                        :window-name win-name
                        :pane pane-idx
                        :title title
                        :width (or width 80)
                        :height (or height 24)
                        :active active
                        :command (or command "")
                        :style (role-style session win-name title)
                        :hash h
                        :content content})))))))
    @results))

(defn capture-diff!
  "Capture all panes but only include content for changed ones.
   Client sends known hashes, we only return content for mismatches."
  [known-hashes]
  (let [all (capture-all!)]
    (mapv (fn [pane]
            (let [known (get known-hashes (:id pane))]
              (if (= known (:hash pane))
                (dissoc pane :content)  ;; unchanged — skip content
                pane)))                 ;; changed — include content
          all)))

;; =============================================================================
;; HTML Page
;; =============================================================================

(def dashboard-html
  "<!DOCTYPE html>
<html lang='en'>
<head>
<meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>coggy tmux</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{
  --void:#06080c;--slab:#0e1219;--well:#141b24;--edge:#1e2a38;
  --text:#9eaab8;--bright:#d0dae6;--dim:#556270;
  --oracle:#5b9cf5;--librarian:#2bcfb5;--actor:#e8a33a;--simulator:#9b7be8;
  --instrument:#36c76a;--partner:#e878a8;--organism:#e07838;--logician:#38bde8;
  --router:#8899aa;--monitor:#7888aa;
  --pulse:#36c76a;--git:#e8a33a;--inference:#9b7be8;
  --claude:#e8c840;--node:#5b9cf5;--bash:#8899aa;
}
body{
  background:var(--void);color:var(--text);
  font:11px/1.4 'JetBrains Mono','Fira Code','Cascadia Code',monospace;
  height:100vh;overflow:hidden;
}
.shell{display:grid;grid-template-rows:36px 1fr;height:100vh}

/* Ribbon */
.ribbon{
  background:linear-gradient(180deg,#1a2230,#121822);
  border-bottom:1px solid var(--edge);
  display:flex;align-items:center;padding:0 14px;gap:12px;font-size:11px;
}
.ribbon .mark{color:var(--claude);font-weight:800;letter-spacing:2px;font-size:13px}
.ribbon .stat{color:var(--dim)}
.ribbon .stat b{color:var(--text)}
.ribbon .spacer{flex:1}
.ribbon .rate{
  padding:2px 8px;border-radius:3px;font-size:10px;
  background:var(--well);border:1px solid var(--edge);
}
.ribbon .rate.fast{color:var(--instrument)}
.ribbon .rate.slow{color:var(--actor)}

/* Session groups */
.grid{
  overflow-y:auto;padding:8px;
  display:flex;flex-direction:column;gap:12px;
}
.session-group{
  border:1px solid var(--edge);border-radius:6px;overflow:hidden;
  background:var(--slab);
}
.session-head{
  padding:6px 12px;font-size:10px;letter-spacing:2px;text-transform:uppercase;
  font-weight:700;border-bottom:1px solid var(--edge);
  display:flex;align-items:center;gap:8px;
}
.session-head .dot{width:6px;height:6px;border-radius:50%;display:inline-block}
.session-head.jacobson{color:var(--oracle)}
.session-head.jacobson .dot{background:var(--oracle)}
.session-head.traceboard{color:var(--pulse)}
.session-head.traceboard .dot{background:var(--pulse)}
.session-head.coggy{color:var(--claude)}
.session-head.coggy .dot{background:var(--claude)}

.pane-grid{
  display:grid;gap:1px;background:var(--edge);
}
.pane-grid.cols-2{grid-template-columns:1fr 1fr}
.pane-grid.cols-3{grid-template-columns:1fr 1fr 1fr}
.pane-grid.cols-4{grid-template-columns:1fr 1fr 1fr 1fr}
.pane-grid.cols-5{grid-template-columns:1fr 1fr 1fr 1fr 1fr}
.pane-grid.cols-10{grid-template-columns:repeat(5,1fr)}

/* Individual pane */
.pane{
  background:var(--slab);overflow:hidden;position:relative;
  min-height:120px;max-height:280px;
}
.pane-head{
  padding:3px 8px;font-size:9px;letter-spacing:1px;text-transform:uppercase;
  font-weight:700;border-bottom:1px solid rgba(30,42,56,.6);
  display:flex;align-items:center;justify-content:space-between;
  position:sticky;top:0;z-index:1;background:inherit;
}
.pane-head .label{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.pane-head .cmd{color:var(--dim);font-weight:400;font-size:8px}
.pane-body{
  padding:4px 6px;overflow:auto;height:calc(100% - 22px);
  white-space:pre;font-size:10px;line-height:1.35;color:var(--text);
  scrollbar-width:thin;scrollbar-color:var(--edge) transparent;
}
.pane-body::-webkit-scrollbar{width:3px}
.pane-body::-webkit-scrollbar-thumb{background:var(--edge);border-radius:2px}

/* Semantic role colors — border-left accent */
.pane.role-oracle .pane-head{color:var(--oracle);border-left:3px solid var(--oracle)}
.pane.role-librarian .pane-head{color:var(--librarian);border-left:3px solid var(--librarian)}
.pane.role-actor .pane-head{color:var(--actor);border-left:3px solid var(--actor)}
.pane.role-simulator .pane-head{color:var(--simulator);border-left:3px solid var(--simulator)}
.pane.role-instrument .pane-head{color:var(--instrument);border-left:3px solid var(--instrument)}
.pane.role-partner .pane-head{color:var(--partner);border-left:3px solid var(--partner)}
.pane.role-organism .pane-head{color:var(--organism);border-left:3px solid var(--organism)}
.pane.role-logician .pane-head{color:var(--logician);border-left:3px solid var(--logician)}
.pane.role-router .pane-head{color:var(--router);border-left:3px solid var(--router)}
.pane.role-monitor .pane-head{color:var(--monitor);border-left:3px solid var(--monitor)}
.pane.stream-pulse .pane-head{color:var(--pulse);border-left:3px solid var(--pulse)}
.pane.stream-git .pane-head{color:var(--git);border-left:3px solid var(--git)}
.pane.stream-inference .pane-head{color:var(--inference);border-left:3px solid var(--inference)}
.pane.coggy-claude .pane-head{color:var(--claude);border-left:3px solid var(--claude)}
.pane.coggy-node .pane-head{color:var(--node);border-left:3px solid var(--node)}
.pane.coggy-bash .pane-head{color:var(--bash);border-left:3px solid var(--bash)}
.pane.role-default .pane-head{color:var(--dim);border-left:3px solid var(--dim)}

/* Flash on update */
.pane.updated{animation:flash .4s ease}
@keyframes flash{
  0%{box-shadow:inset 0 0 0 1px rgba(54,199,106,.3)}
  100%{box-shadow:inset 0 0 0 1px transparent}
}

/* Active pane glow */
.pane.is-active .pane-head::after{
  content:'';display:inline-block;width:5px;height:5px;
  border-radius:50%;background:var(--pulse);margin-left:6px;
}
</style>
</head>
<body>
<div class='shell'>
  <div class='ribbon'>
    <span class='mark'>COGGY TMUX</span>
    <span class='stat'>panes: <b id='r-panes'>0</b></span>
    <span class='stat'>sessions: <b id='r-sessions'>0</b></span>
    <span class='spacer'></span>
    <span class='rate' id='r-rate'>--ms</span>
    <span class='stat' id='r-ts'>--</span>
  </div>
  <div class='grid' id='grid'></div>
</div>

<script>
const $ = id => document.getElementById(id);
const grid = $('grid');
let hashes = {};
let paneEls = {};
let pollMs = 1500;

function esc(s) {
  return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function tailLines(s, n) {
  if (!s) return '';
  const lines = s.split('\\n');
  // Show last N non-empty lines
  const trimmed = lines.slice(-n).join('\\n');
  return trimmed;
}

function groupBy(arr, key) {
  const m = {};
  arr.forEach(p => {
    const k = p[key];
    if (!m[k]) m[k] = [];
    m[k].push(p);
  });
  return m;
}

function colsClass(n) {
  if (n <= 2) return 'cols-2';
  if (n <= 3) return 'cols-3';
  if (n <= 5) return 'cols-5';
  return 'cols-10';
}

function renderPanes(panes) {
  const groups = groupBy(panes, 'session');
  const order = ['jacobson', 'traceboard', 'coggy'];
  const sessions = new Set(panes.map(p => p.session));

  // Create/update session groups
  order.forEach(sess => {
    if (!groups[sess]) return;
    let groupEl = document.querySelector(`[data-session='${sess}']`);
    if (!groupEl) {
      groupEl = document.createElement('div');
      groupEl.className = 'session-group';
      groupEl.dataset.session = sess;
      groupEl.innerHTML = `<div class='session-head ${sess}'><span class='dot'></span> ${sess}</div><div class='pane-grid ${colsClass(groups[sess].length)}'></div>`;
      grid.appendChild(groupEl);
    }
    const paneGrid = groupEl.querySelector('.pane-grid');
    paneGrid.className = 'pane-grid ' + colsClass(groups[sess].length);

    groups[sess].forEach(p => {
      let el = paneEls[p.id];
      if (!el) {
        el = document.createElement('div');
        el.className = 'pane ' + (p.style || '');
        if (p.active) el.classList.add('is-active');
        el.innerHTML = `<div class='pane-head'><span class='label'>${esc(p.title || p['window-name'] || p.id)}</span><span class='cmd'>${esc(p.command||'')}</span></div><div class='pane-body'></div>`;
        paneGrid.appendChild(el);
        paneEls[p.id] = el;
      }

      // Update content only if changed (content field present = changed)
      if (p.content !== undefined) {
        const body = el.querySelector('.pane-body');
        body.textContent = tailLines(p.content, 40);
        body.scrollTop = body.scrollHeight;
        // Flash
        el.classList.remove('updated');
        void el.offsetWidth;
        el.classList.add('updated');
        hashes[p.id] = p.hash;
      }
    });
  });

  $('r-panes').textContent = panes.length;
  $('r-sessions').textContent = sessions.size;
}

async function poll() {
  const t0 = performance.now();
  try {
    const resp = await fetch('/tmux/api/panes', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({hashes})
    });
    const panes = await resp.json();
    const dt = Math.round(performance.now() - t0);
    renderPanes(panes);
    const rateEl = $('r-rate');
    rateEl.textContent = dt + 'ms';
    rateEl.className = 'rate ' + (dt < 200 ? 'fast' : 'slow');
    $('r-ts').textContent = new Date().toLocaleTimeString();
  } catch(e) {
    $('r-rate').textContent = 'err';
    $('r-rate').className = 'rate slow';
  }
  setTimeout(poll, pollMs);
}

poll();
</script>
</body>
</html>")

;; =============================================================================
;; HTTP Handlers
;; =============================================================================

(defn json-resp [data & {:keys [status] :or {status 200}}]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body (json/generate-string data)})

(defn handle-panes [body]
  (let [known (get body :hashes {})
        panes (capture-diff! known)]
    (json-resp panes)))

(defn handle-sessions []
  (json-resp {:sessions (list-sessions)}))

(defn dash-handler [{:keys [uri request-method body]}]
  (try
    (case [request-method uri]
      [:get "/tmux"]          {:status 200
                               :headers {"Content-Type" "text/html"}
                               :body dashboard-html}
      [:post "/tmux/api/panes"] (let [b (json/parse-string (slurp body) true)]
                                  (handle-panes b))
      [:get "/tmux/api/sessions"] (handle-sessions)
      nil)
    (catch Exception e
      (json-resp {:error (.getMessage e)} :status 500))))

;; =============================================================================
;; Standalone Server
;; =============================================================================

(defn start! [port]
  (println (str "coggy tmux dashboard on http://localhost:" port "/tmux"))
  (srv/run-server dash-handler {:port port})
  @(promise))
