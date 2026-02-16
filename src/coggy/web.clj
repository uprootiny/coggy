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
            [coggy.repl :as repl]
            [coggy.trace :as trace]
            [coggy.semantic :as sem]))

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
<html lang='en'>
<head>
<meta charset='utf-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>coggy</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{
  --bg:#0a0f14;--panel:#1b222b;--panel-hi:#252f3a;--well:#0d141d;
  --edge:#3b4757;--edge-soft:#2b3542;--text:#c5d2e0;--muted:#7f8ea1;
  --phosphor:#71f6a6;--amber:#f2b84c;--teal:#38d0c8;--cyan:#73b9ff;
  --rose:#c87ca2;--red:#d16464;--steel:#9aa8b8;--shadow:rgba(0,0,0,.45);
  --geom-hue:182;--geom-hue-2:34;
}
body{
  background:
    radial-gradient(1200px 600px at 5% 0%, #1a2533 0%, transparent 55%),
    radial-gradient(1000px 700px at 100% 100%, #182326 0%, transparent 60%),
    linear-gradient(180deg,#0c1218 0%,#090d13 100%);
  color:var(--text);font:13px/1.55 'JetBrains Mono','Fira Code',monospace;
  height:100vh;overflow:hidden;letter-spacing:.1px;
}
body::before{
  content:'';position:fixed;inset:0;pointer-events:none;z-index:2;
  background:repeating-linear-gradient(
    0deg,
    rgba(255,255,255,.02) 0px,
    rgba(255,255,255,.02) 1px,
    transparent 2px,
    transparent 4px
  );
  opacity:.18;
}
body::after{
  content:'';position:fixed;inset:0;pointer-events:none;z-index:2;
  box-shadow:inset 0 0 120px rgba(0,0,0,.7);
}

/* ── Chassis layout ── */
.shell{
  display:grid;grid-template-rows:48px 1fr 62px;height:calc(100vh - 20px);
  margin:10px;border-radius:12px;overflow:hidden;position:relative;
  border:1px solid var(--edge);
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,.08),
    inset 0 -1px 0 rgba(0,0,0,.45),
    0 20px 40px var(--shadow);
  background:linear-gradient(180deg,#1a232e 0%,#141b24 100%);
}
.geo-field{
  position:absolute;inset:-8%;pointer-events:none;z-index:1;overflow:hidden;
  filter:saturate(1.05);
}
.geo-shape{
  position:absolute;left:var(--x);top:var(--y);width:var(--s);height:var(--s);
  transform:translate(-50%,-50%) rotate(var(--r));
  opacity:var(--o);
  border:1px solid hsla(var(--h), 65%, 72%, .24);
  background:
    linear-gradient(135deg, hsla(var(--h), 80%, 66%, .14), transparent 65%),
    linear-gradient(315deg, hsla(var(--h), 65%, 52%, .08), transparent 60%);
  box-shadow:inset 0 0 14px hsla(var(--h), 80%, 60%, .09), 0 0 20px hsla(var(--h), 70%, 52%, .05);
  animation:geoDrift var(--d) ease-in-out infinite alternate;
}
.geo-shape.g0{clip-path:polygon(50% 0%, 100% 50%, 50% 100%, 0% 50%)}
.geo-shape.g1{clip-path:polygon(50% 0%, 95% 28%, 95% 72%, 50% 100%, 5% 72%, 5% 28%)}
.geo-shape.g2{clip-path:polygon(50% 0%, 100% 100%, 0% 100%)}
@keyframes geoDrift{
  0%{transform:translate(-50%,-50%) rotate(var(--r)) translateY(0)}
  100%{transform:translate(-50%,-50%) rotate(calc(var(--r) + 14deg)) translateY(8px)}
}

/* Ribbon / top metal plate */
.ribbon{
  background:
    linear-gradient(180deg,#3a4655 0%,#2c3643 52%,#222b35 100%);
  border-bottom:1px solid #11161d;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.18),inset 0 -1px 0 rgba(0,0,0,.45);
  display:flex;align-items:center;padding:0 14px;gap:10px;font-size:11px;
  position:relative;z-index:3;
}
.ribbon .mark{
  font-weight:800;letter-spacing:3px;font-size:14px;color:#f5ce78;
  text-shadow:0 0 8px rgba(242,184,76,.22);
  padding:0 8px;
}
.ribbon .lamps{display:flex;gap:5px;margin-right:4px}
.ribbon .lamp{
  width:10px;height:10px;border-radius:50%;
  border:1px solid rgba(0,0,0,.5);
  box-shadow:inset 0 1px 1px rgba(255,255,255,.3),0 0 8px rgba(113,246,166,.18);
}
.ribbon .lamp.ok{background:#49d985}
.ribbon .lamp.warn{background:#d3b15a}
.ribbon .lamp.hot{background:#d47f5e}
.ribbon .pill{
  background:linear-gradient(180deg,#1b2430,#121b26);
  border:1px solid var(--edge-soft);
  border-radius:999px;padding:3px 10px;color:var(--muted);
  box-shadow:inset 0 1px 0 rgba(255,255,255,.08);
}
.ribbon .pill b{color:var(--text)}
.ribbon .pill.ok b{color:var(--phosphor)}
.ribbon .pill.warn b{color:var(--amber)}
.ribbon .pill.bad b{color:var(--red)}
.ribbon .spacer{flex:1}

/* Hemispheres */
.hemi{
  display:grid;grid-template-columns:minmax(420px,1fr) 10px minmax(300px,380px);gap:0;
  background:#10161f;overflow:hidden;
  position:relative;z-index:3;
}

/* Left: conversation */
.conv{
  background:
    radial-gradient(120% 100% at 100% 0%, rgba(56,208,200,.07), transparent 65%),
    linear-gradient(180deg,#0f1720 0%,#0b1119 100%);
  display:flex;flex-direction:column;overflow:hidden;
  border-right:1px solid rgba(255,255,255,.04);
}
.splitter{
  position:relative;cursor:col-resize;
  background:linear-gradient(180deg,#223041,#1a2635);
  border-left:1px solid #101824;border-right:1px solid #101824;
}
.splitter::before{
  content:'';position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);
  width:3px;height:66px;border-radius:2px;
  background:linear-gradient(180deg,#88a3c2,#5e7896);
  box-shadow:0 0 8px rgba(129,170,214,.33);
}
.splitter.dragging{background:linear-gradient(180deg,#2a3c52,#1f2f43)}
.conv-head{
  min-height:40px;display:flex;align-items:center;justify-content:space-between;padding:6px 10px;
  color:var(--steel);font-size:10px;letter-spacing:1.3px;
  background:linear-gradient(180deg,#1d2732,#141d27);
  border-bottom:1px solid #0e141b;
}
.conv-head .title{
  text-transform:uppercase;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding-right:8px;
}
.trace-controls{display:flex;align-items:center;gap:6px;flex-wrap:wrap;justify-content:flex-end}
.trace-controls .ctl{
  border:1px solid #3a485a;background:linear-gradient(180deg,#202b37,#161f2a);
  color:#9fb0c5;border-radius:999px;padding:2px 8px;font-size:9px;letter-spacing:.8px;
  text-transform:uppercase;cursor:pointer;user-select:none;
  transition:transform .06s ease,filter .18s ease,box-shadow .18s ease;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.08),0 1px 0 rgba(0,0,0,.35);
}
.trace-controls .ctl:hover{filter:brightness(1.08)}
.trace-controls .ctl:active{transform:translateY(1px)}
.trace-controls .ctl.active{
  color:#0d1218;background:linear-gradient(180deg,#8df4be,#52cf90);border-color:#56bf89;font-weight:700;
}
.trace-controls .ctl.warn.active{
  background:linear-gradient(180deg,#f2c371,#d69440);border-color:#b57a30;color:#201307;
}
.conv-scroll{flex:1;overflow-y:auto;padding:16px 20px;scroll-behavior:smooth}
.conv-scroll::-webkit-scrollbar{width:5px}
.conv-scroll::-webkit-scrollbar-thumb{background:#2d3a49;border-radius:3px}
.thought-canvas{
  height:200px;border-bottom:1px solid #102030;position:relative;overflow:hidden;
  background:
    radial-gradient(120% 140% at 10% 0%, rgba(80,214,201,.12), transparent 56%),
    radial-gradient(90% 120% at 90% 100%, rgba(240,191,99,.11), transparent 58%),
    linear-gradient(180deg,#0e1b28 0%,#0a1520 100%);
}
.thought-canvas::before{
  content:'';position:absolute;inset:0;pointer-events:none;
  background:repeating-linear-gradient(90deg,rgba(170,198,230,.03) 0px,rgba(170,198,230,.03) 1px,transparent 2px,transparent 34px);
}
.thought-canvas .label{
  position:absolute;left:10px;top:8px;z-index:2;font-size:10px;letter-spacing:1px;
  color:#a2bad6;text-transform:uppercase;
}
.thought-canvas .legend{
  position:absolute;right:10px;top:8px;z-index:2;font-size:10px;color:#8ca7c4;
}
.froth{
  width:100%;height:100%;display:block;position:relative;z-index:1;
}
.froth .cell{
  stroke:rgba(194,224,255,.22);stroke-width:1.2;
  filter:drop-shadow(0 0 5px rgba(122,193,247,.16));
}
.froth .core{
  stroke:rgba(255,255,255,.25);stroke-width:1.1;
  filter:drop-shadow(0 0 10px rgba(133,236,207,.24));
}
.froth .label{
  font:700 13px 'JetBrains Mono','Fira Code',monospace;letter-spacing:.2px;
  fill:#ecf7ff;paint-order:stroke;stroke:#09131d;stroke-width:3;
}
.froth .sti{
  font:10px 'JetBrains Mono','Fira Code',monospace;fill:#b9d0ea;
}
.froth .lane{
  stroke:rgba(115,185,255,.22);stroke-width:1;stroke-dasharray:4 7;
}
.froth .time-dot{
  fill:#8ef3bf;opacity:.9;filter:drop-shadow(0 0 5px rgba(142,243,191,.45));
}
.froth .node{cursor:grab}
.froth .node.dragging{cursor:grabbing}
.froth .node .core{transition:filter .18s ease}
.froth .node:hover .core{filter:drop-shadow(0 0 13px rgba(133,236,207,.36))}
.ops-strip{
  min-height:28px;padding:4px 10px;display:flex;align-items:center;gap:6px;flex-wrap:wrap;
  border-bottom:1px solid #0f1620;background:linear-gradient(180deg,#151f2a,#101822);
}
.ops-chip{
  border:1px solid #2d3c4e;border-radius:999px;padding:2px 8px;font-size:9px;
  color:#9fb0c5;background:linear-gradient(180deg,#192330,#131c27);letter-spacing:.5px;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.06),0 1px 0 rgba(0,0,0,.32);
}
.ops-chip kbd{
  background:#0f1620;border:1px solid #2b3a4d;border-bottom-color:#1a2736;
  border-radius:3px;padding:0 4px;font:9px/1.5 inherit;color:#cfe3fb;margin-left:4px;
}
.action-dock{
  min-height:34px;padding:5px 10px;display:flex;align-items:center;gap:6px;flex-wrap:wrap;
  border-bottom:1px solid #0e1723;background:linear-gradient(180deg,#172333,#121c2a);
}
.act-btn{
  border:1px solid #3a4f66;border-radius:7px;padding:4px 8px;font-size:10px;letter-spacing:.4px;
  color:#c4d7eb;background:linear-gradient(180deg,#25364a,#1a2838);cursor:pointer;user-select:none;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.08),0 1px 0 rgba(0,0,0,.34);
  transition:transform .07s ease,filter .15s ease;
}
.act-btn:hover{filter:brightness(1.08)}
.act-btn:active{transform:translateY(1px)}
.act-btn.warn{border-color:#8c6b3b;color:#ffe6c1;background:linear-gradient(180deg,#5a4123,#3d2d1a)}
.act-btn.good{border-color:#3e7d58;color:#d4ffe2;background:linear-gradient(180deg,#244c35,#183527)}
.help-modal{
  position:fixed;inset:0;background:rgba(7,10,15,.68);z-index:12;
  display:none;align-items:center;justify-content:center;padding:14px;
}
.help-modal.show{display:flex}
.help-card{
  width:min(760px,96vw);max-height:80vh;overflow:auto;border-radius:10px;
  border:1px solid #3c4a5d;background:linear-gradient(180deg,#1b2531,#131b25);
  box-shadow:0 18px 40px rgba(0,0,0,.55), inset 0 1px 0 rgba(255,255,255,.08);
  padding:14px 16px;
}
.help-title{font-size:12px;letter-spacing:1.2px;text-transform:uppercase;color:#f0c97c;margin-bottom:8px}
.help-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.help-row{font-size:11px;color:#b9c9dc;padding:6px 8px;border:1px solid #2a3849;border-radius:6px;background:rgba(11,18,27,.45)}
.help-row b{color:#dff2ff}
@media(max-width:700px){.help-grid{grid-template-columns:1fr}}

.msg{margin-bottom:20px;animation:rise .25s ease}
@keyframes rise{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
.msg-label{font-size:10px;letter-spacing:1.5px;text-transform:uppercase;margin-bottom:3px}
.msg-label.human{color:var(--cyan)}
.msg-label.coggy{color:var(--amber)}
.msg-body{
  padding:11px 14px;border-radius:8px;white-space:pre-wrap;word-wrap:break-word;
  line-height:1.6;
}
.msg.human .msg-body{
  background:linear-gradient(180deg,rgba(115,185,255,.12),rgba(115,185,255,.05));
  border:1px solid rgba(115,185,255,.24);
  box-shadow:inset 0 1px 0 rgba(255,255,255,.05);
}
.msg.coggy .msg-body{
  background:linear-gradient(180deg,rgba(242,184,76,.12),rgba(242,184,76,.05));
  border:1px solid rgba(242,184,76,.24);
  box-shadow:inset 0 1px 0 rgba(255,255,255,.05);
}

/* Trace block inside conversation */
.trace{
  margin-top:10px;padding:10px 14px;border-radius:6px;font-size:11px;
  background:linear-gradient(180deg,rgba(113,246,166,.08),rgba(113,246,166,.03));
  border:1px solid rgba(113,246,166,.2);
  line-height:1.7;position:relative;overflow:hidden;
  transition:opacity 1.2s ease,filter 1.2s ease,border-color 1.2s ease;
}
.trace-head{
  display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:6px;
}
.trace-badges{display:flex;gap:6px;flex-wrap:wrap}
.badge{
  border:1px solid #3a4a5f;border-radius:999px;padding:1px 8px;font-size:9px;letter-spacing:.6px;
  text-transform:uppercase;background:linear-gradient(180deg,#1b2634,#141d28);color:#9fb5ce;
}
.badge.fail-parse{
  color:#ffd5cf;border-color:#a45f5f;background:linear-gradient(180deg,#4a2222,#311818);
}
.badge.fail-vacuum{
  color:#ffe7bf;border-color:#a07c46;background:linear-gradient(180deg,#473318,#302213);
}
.badge.ctx{
  color:#d7e7ff;border-color:#47648a;background:linear-gradient(180deg,#1b2f4b,#162539);
}
.badge.delta-up{
  color:#c8ffd9;border-color:#3f8a63;background:linear-gradient(180deg,#1e3d2d,#182f24);
}
.badge.delta-down{
  color:#ffd0d0;border-color:#9a5a5a;background:linear-gradient(180deg,#462424,#2e1717);
}
.trace::before{
  content:'';position:absolute;inset:0;pointer-events:none;
  background:radial-gradient(140% 120% at 0% 0%,rgba(113,246,166,.12),transparent 60%);
}
.trace .ph{color:var(--amber);font-weight:700;font-size:10px;letter-spacing:1px}
.trace .g{color:var(--teal)}
.trace .tp{color:var(--phosphor);font-size:10px;text-transform:uppercase;letter-spacing:.5px}
.trace .nm{color:#eff8ff}
.trace .tv{color:#b798f0}
.trace .foc{color:var(--cyan);font-weight:700}
.trace .gap{color:#8f9fb5;font-style:italic}
.trace .dx{color:var(--rose);font-weight:700}
.trace[data-age='new']{
  opacity:1;border-color:rgba(113,246,166,.35);
  box-shadow:0 0 18px rgba(113,246,166,.14), inset 0 1px 0 rgba(255,255,255,.06);
}
.trace[data-age='warm']{opacity:.86;filter:saturate(.92)}
.trace[data-age='cool']{opacity:.72;filter:saturate(.8)}
.trace[data-age='ghost']{opacity:.58;filter:saturate(.7)}

.trace-layer{
  margin:6px 0;padding:5px 8px;border-radius:5px;border-left:2px solid transparent;
  background:linear-gradient(180deg,rgba(12,20,31,.34),rgba(12,20,31,.18));
}
.trace.hidden-layer{display:none}
.trace-layer.depth-1{margin-left:0;border-left-color:#4ac6bf}
.trace-layer.depth-2{margin-left:14px;border-left-color:#f0bf63}
.trace-layer.depth-3{margin-left:28px;border-left-color:#bf97ea}
.trace-layer .lh{
  font-size:9px;letter-spacing:1px;text-transform:uppercase;color:#97a8be;margin-bottom:2px;
  display:flex;align-items:center;gap:6px;cursor:pointer;user-select:none;
}
.trace-layer .lh .car{
  width:12px;display:inline-block;text-align:center;color:#c0d0e5;
}
.trace-layer.conf-high{box-shadow:inset 0 0 0 1px rgba(120,241,181,.18)}
.trace-layer.conf-mid{box-shadow:inset 0 0 0 1px rgba(242,184,76,.2)}
.trace-layer.conf-low{
  box-shadow:inset 0 0 0 1px rgba(209,100,100,.22);
  filter:saturate(.82);
}
.trace-line{display:block}
.trace-layer.collapsed .trace-line{display:none}
.trace-line.semantic-parse{color:#a4ebcf}
.trace-line.semantic-ground{color:#d8e6f8}
.trace-line.semantic-attend{color:#8fd6ff}
.trace-line.semantic-infer{color:#dec3ff}
.trace-line.semantic-reflect{color:#f5cfda}
.trace-line.muted-semantic{opacity:.33}
.trace[data-hidden-by-age='1']{display:none}
.ctx-frame{
  border-left:3px solid var(--ctx-color,#5c7da5);
  padding-left:8px;
}

/* Grounding bar — the vital sign */
.ground-bar{
  margin-top:6px;display:flex;align-items:center;gap:8px;font-size:10px;color:var(--muted);
}
.ground-track{
  flex:1;height:6px;background:var(--well);border-radius:3px;overflow:hidden;max-width:180px;
  border:1px solid rgba(255,255,255,.06);
}
.ground-fill{height:100%;border-radius:3px;transition:width .4s ease,background .4s ease}

/* Right: atomspace + metrics */
.aside{
  background:
    radial-gradient(140% 120% at 100% 0%, rgba(200,124,162,.14), transparent 72%),
    linear-gradient(180deg,#111923 0%,#0d141e 100%);
  display:flex;flex-direction:column;overflow:hidden;
}
.aside-head{
  padding:10px 14px;font-size:10px;letter-spacing:2px;text-transform:uppercase;
  color:var(--amber);font-weight:700;border-bottom:1px solid #1f2a38;
  display:flex;justify-content:space-between;align-items:center;
  background:linear-gradient(180deg,#1d2732,#141d27);
}
.aside-head .count{color:var(--steel);font-weight:400;letter-spacing:0}

/* Metrics strip */
.metrics{
  padding:8px 14px;border-bottom:1px solid #1f2a38;
  display:grid;grid-template-columns:1fr 1fr;gap:4px 12px;font-size:10px;
}
.metric{display:flex;justify-content:space-between}
.metric .k{color:var(--muted)}
.metric .v{color:var(--text);font-weight:600}
.metric .v.good{color:var(--phosphor)}
.metric .v.mid{color:var(--amber)}
.metric .v.bad{color:var(--red)}

.lineage{
  margin:8px 14px 10px;padding:10px 10px;border-radius:6px;
  background:linear-gradient(180deg,rgba(200,124,162,.13),rgba(200,124,162,.05));
  border:1px solid rgba(200,124,162,.24);
  box-shadow:inset 0 1px 0 rgba(255,255,255,.06);
}
.lineage .lh{
  font-size:10px;letter-spacing:1.1px;color:#ebb0cc;text-transform:uppercase;
  margin-bottom:4px;font-weight:700;
}
.lineage .lb{
  font-size:10px;color:#c2cbda;line-height:1.55;
}
.lineage .lb b{color:#f2d2e2}

.or-panel{
  margin:6px 14px 10px;padding:8px 10px;border-radius:6px;
  border:1px solid #2d3b4c;background:linear-gradient(180deg,rgba(11,18,27,.65),rgba(11,18,27,.4));
}
.or-head{
  display:flex;align-items:center;justify-content:space-between;gap:8px;
  font-size:10px;text-transform:uppercase;letter-spacing:1px;color:#aebed2;margin-bottom:4px;
}
.or-dot{
  width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:6px;
  border:1px solid rgba(0,0,0,.4);vertical-align:middle;
}
.or-dot.ok{background:#56d68d;box-shadow:0 0 8px rgba(86,214,141,.35)}
.or-dot.warn{background:#e3b05c;box-shadow:0 0 8px rgba(227,176,92,.3)}
.or-dot.bad{background:#d06a6a;box-shadow:0 0 8px rgba(208,106,106,.3)}
.or-body{font-size:10px;line-height:1.45;color:#c5d1df}
.or-body .hint{color:#9eb0c6}
.or-body .mono{font-family:'JetBrains Mono','Fira Code',monospace;color:#e8f0fb}

/* Focus set */
.focus-set{padding:8px 14px;border-bottom:1px solid #1f2a38}
.focus-set .fh{font-size:10px;color:var(--muted);letter-spacing:1px;margin-bottom:4px}
.focus-item{
  display:flex;align-items:center;gap:6px;padding:2px 0;font-size:11px;
}
.focus-item .sigil{color:var(--amber)}
.focus-item .fname{color:#e7effa;flex:1}
.focus-item .sti-track{
  width:60px;height:5px;background:var(--well);border-radius:2px;overflow:hidden;
  border:1px solid rgba(255,255,255,.06);
}
.focus-item .sti-fill{
  height:100%;
  background:linear-gradient(90deg,#1fbf96,#7ef4bc);
  border-radius:2px;transition:width .3s ease
}
.focus-item .sti-val{color:var(--muted);font-size:10px;width:32px;text-align:right}

/* Atom list */
.atom-list{flex:1;overflow-y:auto;padding:6px 14px}
.atom-list::-webkit-scrollbar{width:4px}
.atom-list::-webkit-scrollbar-thumb{background:#2d3a49;border-radius:2px}
.atom{
  padding:4px 0;border-bottom:1px solid rgba(58,72,90,.45);
  display:flex;align-items:baseline;gap:6px;font-size:11px;
}
.atom .atype{color:var(--phosphor);font-size:9px;text-transform:uppercase;letter-spacing:.5px;width:70px;flex-shrink:0}
.atom .aname{color:#eef5ff;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.atom .atv{color:#ba9ced;font-size:10px;white-space:nowrap}

/* Input bar */
.bar{
  background:linear-gradient(180deg,#27313e,#1b2430);
  border-top:1px solid #10161d;
  display:flex;align-items:center;padding:0 16px;gap:8px;position:relative;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.13);
  z-index:3;
}
.bar .prompt{color:var(--phosphor);font-weight:700;white-space:nowrap}
.bar .turn{color:var(--muted);font-size:11px}
.bar input{
  flex:1;background:#0c1219;border:1px solid #334154;border-radius:6px;
  color:#eff6ff;font:13px/1.4 inherit;padding:10px 12px;outline:none;
  transition:border-color .2s,box-shadow .2s;
  box-shadow:inset 0 2px 6px rgba(0,0,0,.45);
}
.bar input:focus{border-color:var(--cyan);box-shadow:0 0 0 2px rgba(115,185,255,.18),inset 0 2px 6px rgba(0,0,0,.45)}
.bar input::placeholder{color:var(--muted)}
.bar button{
  background:linear-gradient(180deg,#f5c96e,#d99838);
  color:#1a1306;border:1px solid #a87326;border-radius:8px;
  padding:9px 16px;font:800 12px inherit;cursor:pointer;
  transition:transform .06s ease,filter .2s;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.35),0 3px 0 #845b1f;
}
.bar button:hover{filter:brightness(1.03)}
.bar button:active{transform:translateY(2px);box-shadow:inset 0 1px 0 rgba(255,255,255,.35),0 1px 0 #845b1f}
.bar button:disabled{opacity:.35;cursor:default;transform:none}

/* Responsive */
@media(max-width:1100px){
  .hemi{grid-template-columns:1fr}
  .splitter{display:none}
  .aside{max-height:42vh}
}
@media(max-width:700px){
  .shell{margin:0;border-radius:0;height:100vh}
  .ribbon{gap:6px;padding:0 8px}
  .ribbon .pill{padding:2px 6px;font-size:10px}
  .ribbon .mark{font-size:12px;padding:0 4px}
  .thought-canvas{height:150px}
  .conv-scroll{padding:12px}
  .bar{padding:0 10px}
}
</style>
</head>
<body>
<div class='shell'>
  <div class='geo-field' id='geo-field'></div>
  <div class='ribbon'>
    <span class='lamps'>
      <span class='lamp ok'></span>
      <span class='lamp warn'></span>
      <span class='lamp hot'></span>
    </span>
    <span class='mark'>COGGY</span>
    <span class='pill'>mode: <b>atomspace console</b></span>
    <span class='pill' id='r-model'>model: <b>...</b></span>
    <span class='pill' id='r-atoms'>atoms: <b>0</b></span>
    <span class='pill' id='r-ground'>ground: <b>—</b></span>
    <span class='pill' id='r-vacuum'>vacuum: <b>0</b></span>
    <span class='pill' id='r-hyle'>hyle: <b>—</b></span>
    <span class='pill' id='r-delta'>delta: <b>—</b></span>
    <span class='spacer'></span>
    <span class='pill' id='r-parse'>parse: <b>—</b></span>
    <span class='pill' id='r-turn'>turn: <b>0</b></span>
  </div>

  <div class='hemi'>
    <div class='conv'>
      <div class='conv-head'>
        <div class='title'>RETRO COGNITIVE TERMINAL :: natural-language to hypergraph bridge</div>
        <div class='trace-controls'>
          <span class='ctl active' id='ctl-depth-1'>L1</span>
          <span class='ctl active' id='ctl-depth-2'>L2</span>
          <span class='ctl active' id='ctl-depth-3'>L3</span>
          <span class='ctl active warn' id='ctl-ghost'>ghosts</span>
          <span class='ctl active' id='ctl-semantic'>semantic</span>
        </div>
      </div>
      <div class='ops-strip'>
        <span class='ops-chip' id='op-legend'>layers: 3 on</span>
        <span class='ops-chip' id='op-traces'>visible traces: 0</span>
        <span class='ops-chip'>depth <kbd>1</kbd><kbd>2</kbd><kbd>3</kbd></span>
        <span class='ops-chip'>ghosts <kbd>g</kbd></span>
        <span class='ops-chip'>semantic <kbd>s</kbd></span>
        <span class='ops-chip'>help <kbd>?</kbd></span>
      </div>
      <div class='action-dock'>
        <span class='act-btn warn' id='act-focus-fail'>focus failures</span>
        <span class='act-btn' id='act-collapse-noninfer'>infer only</span>
        <span class='act-btn' id='act-expand-all'>expand all</span>
        <span class='act-btn good' id='act-reset-layout'>reset layout</span>
      </div>
      <div class='thought-canvas' id='thought-canvas'>
        <span class='label'>common attention froth</span>
        <span class='legend' id='froth-legend'>turn 0 · 0 active thoughts</span>
        <svg class='froth' id='froth' viewBox='0 0 1000 260' preserveAspectRatio='none'></svg>
      </div>
      <div class='conv-scroll' id='conv'></div>
    </div>
    <div class='splitter' id='splitter' title='drag to resize'></div>
    <div class='aside'>
      <div class='aside-head'>
        <span>atomspace</span>
        <span class='count' id='aside-count'>0 atoms · 0 links</span>
      </div>
      <div class='metrics' id='metrics'></div>
      <div class='lineage'>
        <div class='lh'>NEANDERTHALEAN BRANCH</div>
        <div class='lb'><b>Cyc</b> structure, <b>OpenCog</b> composability, <b>NARS</b> scarcity logic. Built as a living, inspectable reasoning substrate.</div>
      </div>
      <div class='or-panel' id='or-panel'>
        <div class='or-head'>
          <span><span class='or-dot warn' id='or-dot'></span>OpenRouter</span>
          <span id='or-when'>checking…</span>
        </div>
        <div class='or-body' id='or-body'>status pending</div>
      </div>
      <div class='focus-set' id='focus-set'>
        <div class='fh'>ATTENTIONAL FOCUS</div>
      </div>
      <div class='atom-list' id='atom-list'></div>
    </div>
  </div>

  <div class='bar'>
    <span class='prompt'>coggy</span>
    <span class='turn' id='bar-turn'>[0]</span>
    <span class='prompt'>❯</span>
    <input id='inp' placeholder='speak to the atomspace...' autofocus>
    <button id='go'>↵</button>
  </div>
</div>
<div class='help-modal' id='help-modal'>
  <div class='help-card'>
    <div class='help-title'>Coggy Operator Shortcuts</div>
    <div class='help-grid'>
      <div class='help-row'><b>1 / 2 / 3</b> trace depth layers</div>
      <div class='help-row'><b>g</b> toggle ghost traces</div>
      <div class='help-row'><b>s</b> toggle semantic coloring</div>
      <div class='help-row'><b>Enter</b> send current prompt</div>
      <div class='help-row'><b>Esc</b> close help panel</div>
      <div class='help-row'><b>?</b> open/close this panel</div>
    </div>
  </div>
</div>

<script>
const $ = id => document.getElementById(id);
const conv = $('conv'), inp = $('inp'), go = $('go');
let turn = 0, lastGround = null, traceSeq = 0;
let traceDepth = 3, showGhost = true, showSemantic = true;
let prevMetrics = null, prevAtoms = null;
let prevTraceStats = null;
let layerCollapse = {parse:false, ground:false, attend:false, infer:false, reflect:false};
let orCache = {at: 0, data: null};
let frothState = {nodes: [], map: {}, dragging: null, raf: null};
let leftColPx = null;

// ── Render helpers ──

function groundColor(rate) {
  if (rate >= 0.5) return 'var(--teal)';
  if (rate >= 0.2) return 'var(--gold)';
  return 'var(--red)';
}
function groundClass(rate) {
  if (rate >= 0.5) return 'good';
  if (rate >= 0.2) return 'mid';
  return 'bad';
}
function rnd(seed, i) {
  const x = Math.sin(seed * 12.9898 + i * 78.233) * 43758.5453;
  return x - Math.floor(x);
}
function renderGeometry(stateR, metricsR) {
  const field = $('geo-field');
  if (!field) return;
  const atoms = Object.keys((stateR && stateR.atoms) || {}).length;
  const gr = (metricsR && metricsR['avg-grounding-rate']) || 0;
  const pr = (metricsR && metricsR['parse-rate']) || 0;
  const seed = (turn || 0) * 17 + atoms * 13 + Math.round((gr + pr) * 100);
  const count = Math.max(10, Math.min(36, 10 + Math.floor(atoms / 2)));
  const hueA = Math.round(150 + gr * 90);
  const hueB = Math.round(20 + pr * 90);
  document.documentElement.style.setProperty('--geom-hue', String(hueA));
  document.documentElement.style.setProperty('--geom-hue-2', String(hueB));
  let h = '';
  for (let i = 0; i < count; i++) {
    const x = (rnd(seed, i) * 100).toFixed(2) + '%';
    const y = (rnd(seed, i + 91) * 100).toFixed(2) + '%';
    const s = Math.round(26 + rnd(seed, i + 131) * 120) + 'px';
    const r = Math.round(rnd(seed, i + 171) * 360) + 'deg';
    const o = (0.08 + rnd(seed, i + 211) * 0.22).toFixed(3);
    const d = (9 + rnd(seed, i + 251) * 21).toFixed(2) + 's';
    const hu = Math.round((i % 2 === 0 ? hueA : hueB) + rnd(seed, i + 291) * 20);
    const g = 'g' + (i % 3);
    h += `<div class=\"geo-shape ${g}\" style=\"--x:${x};--y:${y};--s:${s};--r:${r};--o:${o};--d:${d};--h:${hu}\"></div>`;
  }
  field.innerHTML = h;
}
function blobPath(cx, cy, r, seed) {
  const n = 8;
  let pts = [];
  for (let i = 0; i < n; i++) {
    const a = (Math.PI * 2 * i) / n;
    const m = 0.75 + rnd(seed, i + 1) * 0.55;
    const rr = r * m;
    const x = cx + Math.cos(a) * rr;
    const y = cy + Math.sin(a) * rr;
    pts.push([x, y]);
  }
  return pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ') + ' Z';
}
function renderThoughtCanvas(stateR) {
  const el = $('froth');
  if (!el) return;
  const focus = (stateR && stateR.focus) || [];
  const attention = (stateR && stateR.attention) || {};
  const active = focus.slice(0, 16).map((k, idx) => {
    const av = attention[k] || {};
    const sti = Number(av['av/sti'] || av.av_sti || 0);
    return {k: String(k), sti, idx};
  });
  $('froth-legend').textContent = `turn ${turn} · ${active.length} active thoughts`;

  const W = 1000, H = 260;
  const lanes = 6;
  if (!active.length) {
    el.innerHTML = `<text x='40' y='132' class='label'>no active thoughts yet</text>`;
    frothState.nodes = [];
    frothState.map = {};
    return;
  }

  const base = Math.max(1, active.length - 1);
  const nextNodes = [];
  const nextMap = {};
  active.forEach((a, i) => {
    const seed = turn * 37 + i * 11 + Math.round(a.sti * 10);
    const lane = i % lanes;
    const x = 70 + ((W - 140) * (i / base)) + (rnd(seed, 5) - 0.5) * 70;
    const y = 30 + (H - 60) * ((lane + rnd(seed, 7) * 0.85) / lanes);
    const r = Math.max(22, Math.min(88, 20 + a.sti * 3.2));
    const hu = Math.round((hashColor(a.k).match(/hsl\\((\\d+)/) || [0, 180])[1]);
    const fillCell = `hsla(${hu}, 78%, 58%, 0.16)`;
    const fillCore = `hsla(${hu}, 88%, 64%, 0.24)`;
    const t = ((turn * 19 + i * 31) % 1000) / 1000;
    const prev = frothState.map[a.k];
    const node = prev
      ? {...prev, baseX: x, baseY: y, r, sti: a.sti, hu, fillCell, fillCore, seed, t}
      : {key: a.k, x, y, vx: 0, vy: 0, baseX: x, baseY: y, r, sti: a.sti, hu, fillCell, fillCore, seed, t};
    nextNodes.push(node);
    nextMap[a.k] = node;
  });
  frothState.nodes = nextNodes;
  frothState.map = nextMap;
  drawFroth();
}
function drawFroth() {
  const el = $('froth');
  if (!el) return;
  const W = 1000, H = 260;
  let h = '';
  const lanes = 6;
  for (let i = 1; i < lanes; i++) {
    const y = (H / lanes) * i;
    h += `<line class='lane' x1='0' y1='${y.toFixed(1)}' x2='${W}' y2='${y.toFixed(1)}'/>`;
  }
  frothState.nodes.forEach((n, idx) => {
    const pathOuter = blobPath(n.x, n.y, n.r * 1.55, n.seed + 99);
    const pathCore = blobPath(n.x, n.y, n.r, n.seed + 177);
    const tx = (n.x + (n.t * 90)).toFixed(1);
    const ty = (n.y - n.r - 9).toFixed(1);
    const draggingCls = (frothState.dragging && frothState.dragging.node === n) ? ' dragging' : '';
    h += `<g class='node${draggingCls}' data-idx='${idx}'>`;
    h += `<path class='cell' d='${pathOuter}' fill='${n.fillCell}'/>`;
    h += `<path class='core' d='${pathCore}' fill='${n.fillCore}'/>`;
    h += `<circle class='time-dot' cx='${tx}' cy='${ty}' r='2.4'/>`;
    h += `<text class='label' x='${(n.x - n.r * 0.62).toFixed(1)}' y='${(n.y + 2).toFixed(1)}'>${escTrace(n.key).slice(0, 16)}</text>`;
    h += `<text class='sti' x='${(n.x - n.r * 0.62).toFixed(1)}' y='${(n.y + 16).toFixed(1)}'>STI ${n.sti.toFixed(1)}</text>`;
    h += `</g>`;
  });
  el.innerHTML = h;
}
function toSvgPoint(evt) {
  const svg = $('froth');
  const rect = svg.getBoundingClientRect();
  const x = ((evt.clientX - rect.left) / rect.width) * 1000;
  const y = ((evt.clientY - rect.top) / rect.height) * 260;
  return {x, y};
}
function animateFroth() {
  const d = frothState.dragging;
  let moving = false;
  frothState.nodes.forEach(n => {
    if (d && d.node === n) return;
    const ax = (n.baseX - n.x) * 0.015;
    const ay = (n.baseY - n.y) * 0.015;
    n.vx = (n.vx + ax) * 0.9;
    n.vy = (n.vy + ay) * 0.9;
    n.x += n.vx;
    n.y += n.vy;
    if (Math.abs(n.vx) > 0.02 || Math.abs(n.vy) > 0.02 || Math.abs(n.baseX - n.x) > 0.6 || Math.abs(n.baseY - n.y) > 0.6) moving = true;
  });
  for (let i = 0; i < frothState.nodes.length; i++) {
    for (let j = i + 1; j < frothState.nodes.length; j++) {
      const a = frothState.nodes[i], b = frothState.nodes[j];
      const dx = b.x - a.x, dy = b.y - a.y;
      const dist = Math.hypot(dx, dy) || 0.001;
      const minDist = (a.r + b.r) * 0.72;
      if (dist < minDist) {
        const overlap = (minDist - dist) * 0.5;
        const nx = dx / dist, ny = dy / dist;
        a.x -= nx * overlap; a.y -= ny * overlap;
        b.x += nx * overlap; b.y += ny * overlap;
        a.vx -= nx * 0.04; a.vy -= ny * 0.04;
        b.vx += nx * 0.04; b.vy += ny * 0.04;
        moving = true;
      }
    }
  }
  frothState.nodes.forEach(n => {
    n.x = Math.max(30, Math.min(970, n.x));
    n.y = Math.max(24, Math.min(236, n.y));
  });
  drawFroth();
  if (moving || frothState.dragging) {
    frothState.raf = requestAnimationFrame(animateFroth);
  } else {
    frothState.raf = null;
  }
}
function kickFroth() {
  if (!frothState.raf) frothState.raf = requestAnimationFrame(animateFroth);
}
function applySplit() {
  if (!hemi) return;
  if (window.innerWidth <= 1100) {
    hemi.style.gridTemplateColumns = '1fr';
    return;
  }
  const w = hemi.getBoundingClientRect().width;
  const left = Math.max(420, Math.min(w - 330, leftColPx || Math.round(w * 0.65)));
  hemi.style.gridTemplateColumns = `${left}px 10px minmax(300px,1fr)`;
}
function resetLayout() {
  leftColPx = null;
  traceDepth = 3; showGhost = true; showSemantic = true;
  setCtl('ctl-depth-1', true); setCtl('ctl-depth-2', true); setCtl('ctl-depth-3', true);
  setCtl('ctl-ghost', true); setCtl('ctl-semantic', true);
  layerCollapse = {parse:false, ground:false, attend:false, infer:false, reflect:false};
  saveLayerCollapse(); saveUiPrefs();
  applySplit(); applyTraceFilters(); updateTraceAges();
}
function focusFailures() {
  showGhost = false;
  setCtl('ctl-ghost', false);
  applyTraceFilters();
  conv.querySelectorAll('.trace').forEach(t => {
    const hasFail = t.querySelector('.badge.fail-parse, .badge.fail-vacuum');
    t.style.display = hasFail ? '' : 'none';
  });
  saveUiPrefs();
}
function inferOnly() {
  layerCollapse = {parse:true, ground:true, attend:true, infer:false, reflect:false};
  saveLayerCollapse();
  conv.querySelectorAll('.trace-layer[data-layer-key]').forEach(el => {
    const key = el.getAttribute('data-layer-key');
    const on = isLayerCollapsed(key);
    el.classList.toggle('collapsed', on);
    const car = el.querySelector('.car');
    if (car) car.textContent = on ? '▸' : '▾';
  });
}
function expandAll() {
  layerCollapse = {parse:false, ground:false, attend:false, infer:false, reflect:false};
  saveLayerCollapse();
  conv.querySelectorAll('.trace-layer[data-layer-key]').forEach(el => {
    el.classList.remove('collapsed');
    const car = el.querySelector('.car');
    if (car) car.textContent = '▾';
  });
}
function orLevel(data) {
  if (!data) return 'warn';
  const ok = data.auth && data.auth.ok;
  if (ok) return 'ok';
  const s = data.auth ? data.auth.status : null;
  if (s === 429 || s === 402) return 'warn';
  return 'bad';
}
function renderOpenRouterStatus(data) {
  const dot = $('or-dot');
  const body = $('or-body');
  const when = $('or-when');
  const lv = orLevel(data);
  dot.className = 'or-dot ' + lv;
  const key = data && data.key ? `${data.key.masked || 'MISSING'} (${data.key.source || 'unknown'})` : 'unknown';
  const model = data && data['configured-model'] ? data['configured-model'] : '?';
  const auth = data && data.auth ? (data.auth.ok ? 'ok' : `fail${data.auth.status ? ' ' + data.auth.status : ''}`) : 'unknown';
  const hint = data && data.auth ? (data.auth.hint || 'no hint') : 'status unavailable';
  body.innerHTML = `<div>key: <span class='mono'>${escTrace(key)}</span></div>
    <div>model: <span class='mono'>${escTrace(model)}</span> · auth: <span class='mono'>${escTrace(auth)}</span></div>
    <div class='hint'>${escTrace(hint)}</div>`;
  when.textContent = new Date().toLocaleTimeString();
}
async function refreshOpenRouterStatus(force=false) {
  const now = Date.now();
  if (!force && orCache.data && (now - orCache.at) < 60000) {
    renderOpenRouterStatus(orCache.data);
    return;
  }
  try {
    const data = await fetch('/api/openrouter/status').then(r => r.json());
    orCache = {at: now, data};
    renderOpenRouterStatus(data);
  } catch (_) {
    renderOpenRouterStatus({auth: {ok:false, status:'unreachable', hint:'cannot reach /api/openrouter/status'}});
  }
}

function escTrace(s) {
  return escHtml(String(s ?? ''));
}
function loadLayerCollapse() {
  try {
    const raw = localStorage.getItem('coggy.trace.layerCollapse');
    if (!raw) return;
    const d = JSON.parse(raw);
    if (d && typeof d === 'object') {
      layerCollapse = {...layerCollapse, ...d};
    }
  } catch (_) {}
}
function saveLayerCollapse() {
  try { localStorage.setItem('coggy.trace.layerCollapse', JSON.stringify(layerCollapse)); } catch (_) {}
}
function loadUiPrefs() {
  try {
    const raw = localStorage.getItem('coggy.ui.prefs');
    if (!raw) return;
    const p = JSON.parse(raw);
    if (typeof p.traceDepth === 'number') traceDepth = Math.max(1, Math.min(3, p.traceDepth));
    if (typeof p.showGhost === 'boolean') showGhost = p.showGhost;
    if (typeof p.showSemantic === 'boolean') showSemantic = p.showSemantic;
    if (typeof p.leftColPx === 'number') leftColPx = p.leftColPx;
  } catch (_) {}
}
function saveUiPrefs() {
  try {
    localStorage.setItem('coggy.ui.prefs', JSON.stringify({traceDepth, showGhost, showSemantic, leftColPx}));
  } catch (_) {}
}
function isLayerCollapsed(key) {
  return !!layerCollapse[key];
}
function confidenceClass(score) {
  if (score >= 0.75) return 'conf-high';
  if (score >= 0.4) return 'conf-mid';
  return 'conf-low';
}
function hashColor(s) {
  const str = String(s || 'global');
  let h = 0;
  for (let i = 0; i < str.length; i++) h = ((h << 5) - h + str.charCodeAt(i)) | 0;
  const hue = Math.abs(h % 360);
  return `hsl(${hue} 55% 60%)`;
}
function traceLayer(title, depth, bodyHtml, semanticClass, confScore, layerKey) {
  if (!bodyHtml) return '';
  const collapsed = isLayerCollapsed(layerKey);
  const car = collapsed ? '▸' : '▾';
  return `<div class=\"trace-layer depth-${depth} ${confidenceClass(confScore ?? 0.6)} ${collapsed ? 'collapsed' : ''}\" data-layer-key=\"${layerKey}\">
    <div class=\"lh\"><span class=\"car\">${car}</span>${title}</div>
    <div class=\"trace-line ${semanticClass || ''}\">${bodyHtml}</div>
  </div>`;
}
function traceStats(t) {
  const parseN = (t && t.parse && t.parse.length) || 0;
  const inferN = (t && t.infer && t.infer.length) || 0;
  const attendN = (t && t.attend && t.attend.length) || 0;
  const groundR = (t && t.ground && typeof t.ground.rate === 'number') ? t.ground.rate : 0;
  return {parseN, inferN, attendN, groundR};
}
function traceDeltaBadge(s, prev) {
  if (!prev) return '<span class=\"badge\">baseline</span>';
  const di = s.inferN - prev.inferN;
  const dg = Math.round((s.groundR - prev.groundR) * 100);
  const cls = (di > 0 || dg > 0) ? 'delta-up' : (di < 0 || dg < 0 ? 'delta-down' : '');
  const signI = di > 0 ? '+' : '';
  const signG = dg > 0 ? '+' : '';
  return `<span class=\"badge ${cls}\">Δ infer ${signI}${di} · Δ ground ${signG}${dg}%</span>`;
}
function renderTrace(t) {
  if (!t) return '';
  const id = ++traceSeq;
  const ctx = (t.reflect && t.reflect['focus-concept']) || 'global';
  const ctxColor = hashColor(ctx);
  const diagnosis = t.reflect && t.reflect.diagnosis ? String(t.reflect.diagnosis) : '';
  const s = traceStats(t);
  let h = `<div class=\"trace ctx-frame\" style=\"--ctx-color:${ctxColor}\" data-trace-id=\"${id}\" data-born=\"${Date.now()}\" data-age=\"new\">`;
  h += `<div class=\"trace-head\"><div class=\"ph\">┌ COGGY TRACE</div><div class=\"trace-badges\"><span class=\"badge ctx\">ctx:${escTrace(ctx)}</span>${traceDeltaBadge(s, prevTraceStats)}`;
  if (diagnosis.toLowerCase().includes('parser')) h += '<span class=\"badge fail-parse\">parser miss</span>';
  if (diagnosis.toLowerCase().includes('vacuum')) h += '<span class=\"badge fail-vacuum\">grounding vacuum</span>';
  h += '</div></div>';
  prevTraceStats = s;

  // PARSE
  if (t.parse && t.parse.length) {
    let rows = '';
    t.parse.forEach(a => {
      const tp = a['atom/type'] || '?';
      const nm = typeof a === 'string' ? a : (a['atom/name'] || '?');
      rows += `<div>│  <span class=\"g\">⊕</span> <span class=\"tp\">${escTrace(tp)}</span> <span class=\"nm\">\"${escTrace(nm)}\"</span></div>`;
    });
    h += traceLayer('L1 PARSE', 1, rows, 'semantic-parse', Math.min(1, 0.35 + (s.parseN / 12)), 'parse');
  }

  // GROUND
  if (t.ground) {
    const g = t.ground;
    let rows = '';
    if (g.found && g.found.length)
      rows += `<div>│  <span class=\"g\">⊕</span> found — ${g.found.map(escTrace).join(', ')}</div>`;
    if (g.missing && g.missing.length)
      rows += `<div>│  <span class=\"gap\">○</span> novel — ${g.missing.map(escTrace).join(', ')}</div>`;
    if (g.rate !== undefined && g.rate !== null) {
      const pct = (g.rate * 100).toFixed(0);
      rows += `<div class=\"ground-bar\">│  rate: <div class=\"ground-track\"><div class=\"ground-fill\" style=\"width:${pct}%;background:${groundColor(g.rate)}\"></div></div> <b style=\"color:${groundColor(g.rate)}\">${pct}%</b></div>`;
    }
    h += traceLayer('L2 GROUND', 2, rows, 'semantic-ground', g.rate ?? 0, 'ground');
  }

  // ATTEND
  if (t.attend && t.attend.length) {
    let rows = '';
    t.attend.forEach(a => {
      const s = a.sti > 5 ? '★' : '·';
      rows += `<div>│  <span class=\"g\">${s}</span> <span class=\"foc\">${escTrace(a.key || '?')}</span> STI ${(a.sti||0).toFixed(1)}</div>`;
    });
    h += traceLayer('L2 ATTEND', 2, rows, 'semantic-attend', Math.min(1, 0.25 + (s.attendN / 10)), 'attend');
  }

  // INFER
  if (t.infer && t.infer.length) {
    let rows = '';
    let confAcc = 0, confN = 0;
    t.infer.forEach(i => {
      const tvs = i.tv ? ` <span class=\"tv\">(stv ${i.tv['tv/strength'].toFixed(1)} ${i.tv['tv/confidence'].toFixed(1)})</span>` : '';
      if (i.tv && typeof i.tv['tv/confidence'] === 'number') { confAcc += i.tv['tv/confidence']; confN += 1; }
      const glyph = i.type === 'gap' ? '?' : '⊢';
      const cls = i.type === 'gap' ? 'gap' : '';
      rows += `<div class=\"${cls}\">│  <span class=\"g\">${glyph}</span> ${escTrace(i.conclusion||'')}${tvs}</div>`;
    });
    h += traceLayer('L3 INFER', 3, rows, 'semantic-infer', confN ? (confAcc / confN) : 0.45, 'infer');
  }

  // REFLECT
  if (t.reflect) {
    const r = t.reflect;
    let parts = [];
    if (r['new-atoms'] !== undefined) parts.push(`new:${r['new-atoms']}`);
    if (r.updated !== undefined) parts.push(`upd:${r.updated}`);
    if (r['grounding-rate'] !== undefined) parts.push(`ground:${(r['grounding-rate']*100).toFixed(0)}%`);
    if (r['focus-concept']) parts.push(`focus:<span class=\"foc\">${r['focus-concept']}</span>`);
    let rows = `<div>│  ${parts.join('  ')}</div>`;
    if (r.diagnosis) rows += `<div>│  <span class=\"dx\">⚠ ${escTrace(r.diagnosis)}</span>${r.rescue ? ' → ' + escTrace(r.rescue) : ''}</div>`;
    h += traceLayer('L3 REFLECT', 3, rows, 'semantic-reflect', r['grounding-rate'] ?? s.groundR, 'reflect');
  }

  h += '<div class=\"ph\">└───────────────────────</div></div>';
  return h;
}

function applyTraceFilters() {
  const traces = conv.querySelectorAll('.trace');
  let visible = 0;
  traces.forEach(t => {
    const age = t.getAttribute('data-age');
    const hideGhost = !showGhost && age === 'ghost';
    t.setAttribute('data-hidden-by-age', hideGhost ? '1' : '0');
    if (!hideGhost) visible += 1;

    const layers = t.querySelectorAll('.trace-layer');
    layers.forEach(layer => {
      const d = layer.classList.contains('depth-3') ? 3 :
                layer.classList.contains('depth-2') ? 2 : 1;
      layer.classList.toggle('hidden-layer', d > traceDepth);

      const line = layer.querySelector('.trace-line');
      if (line) line.classList.toggle('muted-semantic', !showSemantic);
    });
  });
  $('op-traces').textContent = `visible traces: ${visible}`;
  $('op-legend').textContent = `layers: L1..L${traceDepth} ${showGhost ? 'with ghosts' : 'fresh only'}`;
}

function updateTraceAges() {
  const traces = conv.querySelectorAll('.trace[data-born]');
  const now = Date.now();
  traces.forEach(t => {
    const born = parseInt(t.getAttribute('data-born') || '0', 10);
    const ageS = (now - born) / 1000;
    let age = 'new';
    if (ageS > 180) age = 'ghost';
    else if (ageS > 90) age = 'cool';
    else if (ageS > 35) age = 'warm';
    t.setAttribute('data-age', age);
  });
  applyTraceFilters();
}

function addMsg(role, content, trace) {
  const d = document.createElement('div');
  d.className = 'msg ' + role;
  d.innerHTML = `<div class=\"msg-label ${role}\">${role === 'human' ? 'HUMAN' : 'COGGY'}</div>
    <div class=\"msg-body\">${escHtml(content)}</div>
    ${trace ? renderTrace(trace) : ''}`;
  conv.appendChild(d);
  updateTraceAges();
  conv.scrollTop = conv.scrollHeight;
}

function escHtml(s) {
  return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// ── Aside renderers ──

function renderMetrics(m) {
  if (!m) return;
  const el = $('metrics');
  const kv = (k, v, cls) => `<div class=\"metric\"><span class=\"k\">${k}</span><span class=\"v ${cls||''}\">${v}</span></div>`;
  el.innerHTML =
    kv('parse rate', m['parse-rate'] !== undefined ? (m['parse-rate']*100).toFixed(0)+'%' : '—',
       m['parse-rate'] >= 0.7 ? 'good' : m['parse-rate'] >= 0.3 ? 'mid' : 'bad') +
    kv('avg ground', m['avg-grounding-rate'] !== undefined ? (m['avg-grounding-rate']*100).toFixed(0)+'%' : '—',
       groundClass(m['avg-grounding-rate'] || 0)) +
    kv('avg relations', m['avg-relation-rate'] !== undefined ? (m['avg-relation-rate']*100).toFixed(0)+'%' : '—',
       groundClass(m['avg-relation-rate'] || 0)) +
    kv('vacuum triggers', m['vacuum-triggers'] || 0,
       (m['vacuum-triggers'] || 0) > 0 ? 'bad' : '') +
    kv('turns', m.turns || 0, '') +
    (m['last-failure'] ? kv('last fail', m['last-failure'].type || '—', 'bad') : '');
}

function renderFocus(focus, attention) {
  const el = $('focus-set');
  let h = '<div class=\"fh\">ATTENTIONAL FOCUS</div>';
  if (!focus || !focus.length) {
    h += '<div style=\"font-size:10px;color:var(--muted);padding:2px 0\">no atoms in focus</div>';
    el.innerHTML = h;
    return;
  }
  focus.forEach(k => {
    const av = attention && attention[k] ? attention[k] : {};
    const sti = av['av/sti'] || 0;
    const w = Math.min(100, Math.max(2, sti * 5));
    h += `<div class=\"focus-item\">
      <span class=\"sigil\">${sti > 5 ? '★' : '·'}</span>
      <span class=\"fname\">${k}</span>
      <span class=\"sti-track\"><span class=\"sti-fill\" style=\"width:${w}%\"></span></span>
      <span class=\"sti-val\">${sti.toFixed(1)}</span>
    </div>`;
  });
  el.innerHTML = h;
}

function renderAtoms(atoms) {
  const el = $('atom-list');
  if (!atoms || !Object.keys(atoms).length) {
    el.innerHTML = '<div style=\"font-size:11px;color:var(--muted);padding:8px 0\">empty atomspace</div>';
    return;
  }
  let h = '';
  const sorted = Object.entries(atoms).sort((a,b) => a[0].localeCompare(b[0]));
  sorted.forEach(([k, a]) => {
    const tv = a['atom/tv'] || {};
    h += `<div class=\"atom\">
      <span class=\"atype\">${a['atom/type'] || '?'}</span>
      <span class=\"aname\">${k}</span>
      <span class=\"atv\">(stv ${(tv['tv/strength']||0).toFixed(1)} ${(tv['tv/confidence']||0).toFixed(1)})</span>
    </div>`;
  });
  el.innerHTML = h;
}

// ── Refresh state ──

async function refresh() {
  try {
    const [stateR, metricsR] = await Promise.all([
      fetch('/api/state').then(r => r.json()),
      fetch('/api/metrics').then(r => r.json())
    ]);
    const atoms = stateR.atoms || {};
    const nAtoms = Object.keys(atoms).length;
    const nLinks = stateR.links || 0;
    $('r-model').innerHTML = 'model: <b>' + (stateR.model || '?') + '</b>';
    $('r-atoms').innerHTML = 'atoms: <b>' + nAtoms + '</b>';
    $('aside-count').textContent = nAtoms + ' atoms · ' + nLinks + ' links';
    renderFocus(stateR.focus, stateR.attention);
    renderAtoms(atoms);
    renderMetrics(metricsR);
    renderGeometry(stateR, metricsR);
    renderThoughtCanvas(stateR);
    refreshOpenRouterStatus(false);
    if (stateR.hyle) {
      const up = stateR.hyle.status === 'up';
      $('r-hyle').innerHTML = `hyle: <b>${up ? 'up' : 'down'}</b>`;
      $('r-hyle').className = 'pill ' + (up ? 'ok' : 'warn');
    }
    // ribbon metrics
    if (metricsR) {
      const gr = metricsR['avg-grounding-rate'] || 0;
      const gc = groundClass(gr);
      $('r-ground').innerHTML = `ground: <b>${(gr*100).toFixed(0)}%</b>`;
      $('r-ground').className = 'pill ' + gc;
      $('r-vacuum').innerHTML = `vacuum: <b>${metricsR['vacuum-triggers']||0}</b>`;
      $('r-vacuum').className = 'pill ' + ((metricsR['vacuum-triggers']||0) > 0 ? 'warn' : '');
      const pr = metricsR['parse-rate'] || 0;
      $('r-parse').innerHTML = `parse: <b>${(pr*100).toFixed(0)}%</b>`;
      $('r-parse').className = 'pill ' + (pr >= 0.7 ? 'ok' : pr >= 0.3 ? 'warn' : 'bad');

      const dAtoms = prevAtoms === null ? 0 : (nAtoms - prevAtoms);
      const prevGround = prevMetrics ? (prevMetrics['avg-grounding-rate'] || 0) : gr;
      const dGround = gr - prevGround;
      const dSign = dGround > 0 ? '+' : '';
      const dAtomsSign = dAtoms > 0 ? '+' : '';
      $('r-delta').innerHTML = `delta: <b>${dAtomsSign}${dAtoms}a ${dSign}${(dGround*100).toFixed(0)}g</b>`;
      $('r-delta').className = 'pill ' + (dGround > 0 ? 'ok' : dGround < 0 ? 'bad' : 'warn');

      prevMetrics = metricsR;
      prevAtoms = nAtoms;
    }
  } catch(e) {}
}

// ── Send ──

async function send() {
  const msg = inp.value.trim();
  if (!msg) return;
  inp.value = '';
  go.disabled = true;
  addMsg('human', msg);

  try {
    const resp = await fetch('/api/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({message: msg})
    });
    const data = await resp.json();
    turn = data.turn || turn + 1;
    $('bar-turn').textContent = '[' + turn + ']';
    $('r-turn').innerHTML = 'turn: <b>' + turn + '</b>';
    addMsg('coggy', data.content || data.error || '?', data.trace);
    refresh();
  } catch(e) {
    addMsg('coggy', '⚠ ' + e.message);
  }
  go.disabled = false;
  inp.focus();
}

inp.addEventListener('keydown', e => { if (e.key === 'Enter') send() });
go.addEventListener('click', send);

function setCtl(id, active) {
  const el = $(id);
  if (!el) return;
  el.classList.toggle('active', !!active);
}

$('ctl-depth-1').addEventListener('click', () => { traceDepth = 1; setCtl('ctl-depth-1', true); setCtl('ctl-depth-2', false); setCtl('ctl-depth-3', false); applyTraceFilters(); saveUiPrefs(); });
$('ctl-depth-2').addEventListener('click', () => { traceDepth = 2; setCtl('ctl-depth-1', true); setCtl('ctl-depth-2', true); setCtl('ctl-depth-3', false); applyTraceFilters(); saveUiPrefs(); });
$('ctl-depth-3').addEventListener('click', () => { traceDepth = 3; setCtl('ctl-depth-1', true); setCtl('ctl-depth-2', true); setCtl('ctl-depth-3', true); applyTraceFilters(); saveUiPrefs(); });
$('ctl-ghost').addEventListener('click', () => { showGhost = !showGhost; setCtl('ctl-ghost', showGhost); applyTraceFilters(); saveUiPrefs(); });
$('ctl-semantic').addEventListener('click', () => { showSemantic = !showSemantic; setCtl('ctl-semantic', showSemantic); applyTraceFilters(); saveUiPrefs(); });
document.addEventListener('keydown', e => {
  const modal = $('help-modal');
  if (e.key === 'Escape' && modal.classList.contains('show')) { modal.classList.remove('show'); return; }
  if (e.key === '?') { modal.classList.toggle('show'); e.preventDefault(); return; }
  if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) return;
  if (e.key === '1') $('ctl-depth-1').click();
  else if (e.key === '2') $('ctl-depth-2').click();
  else if (e.key === '3') $('ctl-depth-3').click();
  else if (e.key.toLowerCase() === 'g') $('ctl-ghost').click();
  else if (e.key.toLowerCase() === 's') $('ctl-semantic').click();
});
$('help-modal').addEventListener('click', e => { if (e.target.id === 'help-modal') e.currentTarget.classList.remove('show'); });
$('froth').addEventListener('pointerdown', e => {
  const g = e.target.closest('.node');
  if (!g) return;
  const idx = parseInt(g.getAttribute('data-idx') || '-1', 10);
  const node = frothState.nodes[idx];
  if (!node) return;
  const p = toSvgPoint(e);
  frothState.dragging = {node, offX: p.x - node.x, offY: p.y - node.y, lx: p.x, ly: p.y};
  kickFroth();
});
window.addEventListener('pointermove', e => {
  if (!frothState.dragging) return;
  const p = toSvgPoint(e);
  const d = frothState.dragging;
  d.node.x = p.x - d.offX;
  d.node.y = p.y - d.offY;
  d.node.vx = (p.x - d.lx) * 0.55;
  d.node.vy = (p.y - d.ly) * 0.55;
  d.lx = p.x;
  d.ly = p.y;
  drawFroth();
});
window.addEventListener('pointerup', () => {
  if (!frothState.dragging) return;
  frothState.dragging = null;
  kickFroth();
});
conv.addEventListener('click', e => {
  const h = e.target.closest('.trace-layer .lh');
  if (!h) return;
  const layer = h.closest('.trace-layer');
  if (!layer) return;
  const key = layer.getAttribute('data-layer-key');
  if (!key) return;
  layerCollapse[key] = !isLayerCollapsed(key);
  saveLayerCollapse();
  applyTraceFilters();
  conv.querySelectorAll(`.trace-layer[data-layer-key=\"${key}\"]`).forEach(el => {
    el.classList.toggle('collapsed', isLayerCollapsed(key));
    const car = el.querySelector('.car');
    if (car) car.textContent = isLayerCollapsed(key) ? '▸' : '▾';
  });
});

// ── Boot ──
loadLayerCollapse();
loadUiPrefs();
setCtl('ctl-depth-1', traceDepth >= 1);
setCtl('ctl-depth-2', traceDepth >= 2);
setCtl('ctl-depth-3', traceDepth >= 3);
setCtl('ctl-ghost', showGhost);
setCtl('ctl-semantic', showSemantic);
fetch('/api/boot', {method:'POST'})
  .then(r => r.json())
  .then(d => {
    addMsg('coggy', `boot: ${d.atoms} atoms, ${d.links} links seeded. atomspace ready.`);
    refresh();
    refreshOpenRouterStatus(true);
  })
  .catch(e => addMsg('coggy', '⚠ boot failed: ' + e.message));
refresh();
refreshOpenRouterStatus(true);
setInterval(updateTraceAges, 4000);
setInterval(() => refreshOpenRouterStatus(false), 60000);
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
                    :model (:model @llm/config)
                    :hyle {:port hyle-port
                           :status (if hyle-ok? "up" "down")}})))

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
      [:get "/"]          {:status 200
                           :headers {"Content-Type" "text/html"}
                           :body index-html}
      [:get "/health"]    (json-response {:status "ok"})
      [:get "/api/state"] (handle-state)
      [:get "/api/logs"]  (json-response (:logs @server-state))
      [:get "/api/metrics"] (json-response (sem/metrics-summary))
      [:get "/api/openrouter/status"] (json-response (llm/doctor :json? false :silent? true))

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
  (srv/run-server handler {:ip "0.0.0.0" :port port})
  @(promise))
