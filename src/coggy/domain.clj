(ns coggy.domain
  "Domain packs for expert-judgment knowledge construction.

   Each pack seeds concepts/relations + grounding strategy anchors so
   Coggy can reason with explicit structure in that field."
  (:require [coggy.atomspace :as as]
            [coggy.attention :as att]
            [clojure.string :as str]))

(def domain-packs
  {:legal
   {:name "Legal Reasoning"
    :prompt
    "Active domain: legal reasoning.
Prioritize jurisdiction, procedure, burden of proof, and citation grounding.
When uncertain, state what authority is missing and what fact pattern would disambiguate."
    :concepts
    ["legal-case" "jurisdiction" "precedent" "statute" "regulation"
     "holding" "ratio" "dicta" "burden-of-proof" "standard-of-review"
     "adversarial-reasoning" "occluded-facts" "citation-grounding"]
    :relations
    [{:type :inherits :a "precedent" :b "legal-case"}
     {:type :inherits :a "statute" :b "authority"}
     {:type :inherits :a "regulation" :b "authority"}
     {:type :causes :a "occluded-facts" :b "uncertainty"}
     {:type :causes :a "citation-grounding" :b "auditability"}]
    :strategies
   ["authority-first grounding"
    "jurisdiction partitioning"
    "adversarial occlusion tests"
    "delta-first disagreement tracing"]}

   :ibid-legal
   {:name "IBID Legal Reasoning Engine"
    :prompt
    "Active domain: IBID legal reasoning engine.
Build explicit issue→rule→analysis→conclusion chains, maintain citation provenance,
track unresolved factual gaps, and produce adversarial counter-arguments.
Always separate authority strength from factual confidence."
    :concepts
    ["issue" "rule" "analysis" "conclusion" "holding" "authority-weight"
     "citation-chain" "fact-pattern" "counterargument" "distinguishing-factor"
     "burden-shift" "standard-of-proof" "precedent-cluster" "jurisdiction-scope"]
    :relations
    [{:type :causes :a "citation-chain" :b "auditability"}
     {:type :causes :a "precedent-cluster" :b "predictability"}
     {:type :causes :a "distinguishing-factor" :b "counterargument"}
     {:type :inherits :a "holding" :b "authority"}
     {:type :causes :a "burden-shift" :b "outcome-variance"}]
    :strategies
    ["IRAC trace construction"
     "citation-chain grounding"
     "counterfactual challenge sets"
     "authority-vs-fact confidence split"]}

   :forecast
   {:name "Forecasting / Metaculus Middleware"
    :prompt
    "Active domain: forecasting middleware.
Represent predictions as explicit claims with time bounds, base rates, and update logs.
Track calibration signals and resolution criteria."
    :concepts
    ["prediction-question" "base-rate" "time-horizon" "resolution-criteria"
     "calibration" "brier-score" "probability-update" "evidence-event"
     "ensemble-forecast" "market-implied-probability"]
    :relations
    [{:type :causes :a "evidence-event" :b "probability-update"}
     {:type :causes :a "calibration" :b "forecast-reliability"}
     {:type :causes :a "base-rate" :b "prior-belief"}
     {:type :resembles :a "market-implied-probability" :b "ensemble-forecast"}]
    :strategies
    ["prior→likelihood→posterior updates"
     "resolution-first question design"
     "calibration tracking"
     "scenario decomposition"]}

   :bio
   {:name "Plasmid & Peptide Knowledge"
    :prompt
    "Active domain: plasmid and peptide knowledge reasoning.
Keep outputs high-level and provenance-first. Emphasize evidence quality, assay context,
and conflict tracking. Do not provide operational wet-lab procedures."
    :concepts
    ["plasmid-design" "peptide-engineering" "sequence-annotation" "assay-context"
     "phenotype-signal" "conflicting-evidence" "provenance-chain"
     "constraint-check" "safety-boundary" "design-rationale"]
    :relations
    [{:type :inherits :a "plasmid-design" :b "bio-design"}
     {:type :inherits :a "peptide-engineering" :b "bio-design"}
     {:type :causes :a "conflicting-evidence" :b "uncertainty"}
     {:type :causes :a "provenance-chain" :b "auditability"}
     {:type :causes :a "constraint-check" :b "safer-design-space"}]
    :strategies
    ["provenance-first grounding"
     "assay-context partitioning"
     "contradiction surfacing"
     "uncertainty-calibrated inference"]}

   :unix
   {:name "Unix Ecosystem Operations"
    :prompt
    "Active domain: unix ecosystem operations.
Reason about processes, services, file systems, network interfaces, and system state.
Treat configuration as versioned data, services as typed processes with health signals,
and failures as typed events with causal chains. Prefer composable pipelines over monoliths.
When diagnosing, trace from symptom → intermediate cause → root cause."
    :concepts
    ["process" "service" "daemon" "socket" "port" "filesystem"
     "permission" "cron-job" "systemd-unit" "container"
     "log-stream" "config-file" "environment-variable"
     "tmux-session" "pipeline" "signal"]
    :relations
    [{:type :inherits :a "daemon" :b "process"}
     {:type :inherits :a "container" :b "process"}
     {:type :inherits :a "cron-job" :b "scheduled-task"}
     {:type :inherits :a "systemd-unit" :b "service"}
     {:type :causes :a "config-file" :b "service"}
     {:type :causes :a "signal" :b "process"}
     {:type :causes :a "permission" :b "access-control"}
     {:type :resembles :a "environment-variable" :b "config-file"}]
    :strategies
    ["symptom → cause → root-cause tracing"
     "process tree inspection"
     "config-as-data versioning"
     "composable pipeline construction"
     "service dependency mapping"]}

   :research
   {:name "Artistic & Research Processes"
    :prompt
    "Active domain: artistic and research processes.
Treat creative work as structured exploration: hypotheses, experiments, artifacts, reflections.
Track provenance of ideas — what inspired what, what contradicts what.
Distinguish between generative phases (divergent, low-confidence, many candidates)
and convergent phases (pruning, committing, raising confidence).
Honor the tension between rigor and intuition — name it, don't resolve it prematurely."
    :concepts
    ["hypothesis" "experiment" "artifact" "reflection" "inspiration"
     "constraint" "medium" "technique" "revision" "critique"
     "generative-phase" "convergent-phase" "provenance"
     "aesthetic-judgment" "research-question"]
    :relations
    [{:type :causes :a "hypothesis" :b "experiment"}
     {:type :causes :a "experiment" :b "artifact"}
     {:type :causes :a "critique" :b "revision"}
     {:type :causes :a "inspiration" :b "hypothesis"}
     {:type :inherits :a "generative-phase" :b "creative-process"}
     {:type :inherits :a "convergent-phase" :b "creative-process"}
     {:type :resembles :a "aesthetic-judgment" :b "constraint"}
     {:type :causes :a "research-question" :b "experiment"}]
    :strategies
    ["diverge-then-converge phasing"
     "provenance tracking for ideas"
     "constraint-as-generative-force"
     "artifact-first reasoning (make then understand)"
     "revision-not-replacement attitude"]}

   :balance
   {:name "Balancing & Self-Management"
    :prompt
    "Active domain: balancing and self-management.
Reason about energy, capacity, commitment, rest, and sustainability.
Treat attention as a finite resource with replenishment cycles.
Track load (what's demanding energy) vs recovery (what restores it).
Surface conflicts between commitments early. Distinguish urgent from important.
Be honest about capacity — overcommitment is a structural failure, not a character flaw."
    :concepts
    ["energy-level" "capacity" "commitment" "rest" "recovery"
     "load" "boundary" "priority" "urgency" "importance"
     "sustainability" "burnout-signal" "rhythm" "routine"
     "debt" "slack"]
    :relations
    [{:type :causes :a "load" :b "energy-level"}
     {:type :causes :a "recovery" :b "capacity"}
     {:type :causes :a "overcommitment" :b "burnout-signal"}
     {:type :causes :a "boundary" :b "sustainability"}
     {:type :inherits :a "urgency" :b "priority"}
     {:type :inherits :a "importance" :b "priority"}
     {:type :resembles :a "debt" :b "overcommitment"}
     {:type :causes :a "slack" :b "capacity"}]
    :strategies
    ["load vs capacity auditing"
     "commitment conflict surfacing"
     "urgent vs important separation"
     "recovery-as-investment framing"
     "rhythm over willpower"]}

   :study
   {:name "Studies & Learning"
    :prompt
    "Active domain: studies and learning.
Treat knowledge as a graph: concepts connected by dependencies, analogies, and contradictions.
Track what you know (grounded, high confidence), what you're learning (partial, growing),
and what you don't know (gaps, low confidence). Use spaced repetition logic for review.
Distinguish understanding (can explain and apply) from recognition (can identify).
Name confusions explicitly — they're the most valuable data."
    :concepts
    ["concept" "dependency" "prerequisite" "understanding" "recognition"
     "confusion" "gap" "review-interval" "mastery" "analogy"
     "worked-example" "problem-set" "mental-model" "misconception"
     "transfer" "curriculum"]
    :relations
    [{:type :inherits :a "prerequisite" :b "dependency"}
     {:type :causes :a "worked-example" :b "understanding"}
     {:type :causes :a "problem-set" :b "mastery"}
     {:type :causes :a "confusion" :b "gap"}
     {:type :causes :a "analogy" :b "transfer"}
     {:type :resembles :a "misconception" :b "confusion"}
     {:type :inherits :a "understanding" :b "mastery"}
     {:type :causes :a "review-interval" :b "retention"}]
    :strategies
    ["prerequisite-first ordering"
     "confusion-as-signal (name gaps)"
     "spaced review scheduling"
     "analogy bridge building"
     "explain-to-verify understanding"]}

   :accountability
   {:name "Accountability & Reconciliation"
    :prompt
    "Active domain: accountability and reconciliation practices.
Track commitments, outcomes, discrepancies, and repair actions.
Distinguish between intention and impact. Surface patterns across incidents.
Treat reconciliation as a process (acknowledge → understand → repair → prevent),
not a single act. Be precise about what was promised, what was delivered,
and what the gap means structurally — not just emotionally."
    :concepts
    ["commitment" "outcome" "discrepancy" "repair-action" "intention"
     "impact" "pattern" "acknowledgment" "accountability-loop"
     "trust" "breach" "restoration" "prevention" "ledger"
     "follow-through" "review-cadence"]
    :relations
    [{:type :causes :a "commitment" :b "outcome"}
     {:type :causes :a "discrepancy" :b "repair-action"}
     {:type :causes :a "breach" :b "trust"}
     {:type :causes :a "restoration" :b "trust"}
     {:type :causes :a "acknowledgment" :b "restoration"}
     {:type :causes :a "prevention" :b "sustainability"}
     {:type :inherits :a "follow-through" :b "accountability-loop"}
     {:type :resembles :a "ledger" :b "review-cadence"}]
    :strategies
    ["acknowledge → understand → repair → prevent cycle"
     "intention vs impact separation"
     "pattern detection across incidents"
     "structural root cause over blame"
     "commitment ledger maintenance"]}})

(defn available-domains []
  (->> (keys domain-packs) (map name) sort vec))

(defn get-domain [domain-id]
  (let [k (keyword (str/lower-case (str domain-id)))]
    (get domain-packs k)))

(defn domain-brief [domain-id]
  (when-let [d (get-domain domain-id)]
    {:id (name (keyword domain-id))
     :name (:name d)
     :strategies (:strategies d)
     :prompt (:prompt d)}))

(defn seed-domain!
  "Seed selected domain into AtomSpace + attention.
   Returns {:ok bool ...} with stats."
  [space bank domain-id]
  (if-let [d (get-domain domain-id)]
    (let [before (as/space-stats space)
          domain-tag (str "domain/" (name (keyword domain-id)))]
      (as/add-atom! space (as/concept domain-tag (as/stv 0.95 0.8)))
      (doseq [c (:concepts d)]
        (as/add-atom! space (as/concept c (as/stv 0.8 0.6))))
      (doseq [r (:relations d)]
        (let [source (as/concept (:a r))
              target (as/concept (:b r))
              link (case (keyword (or (:type r) "inherits"))
                     :inherits (as/inheritance source target (as/stv 0.75 0.6))
                     :causes (as/implication source target (as/stv 0.7 0.55))
                     :resembles (as/similarity source target (as/stv 0.7 0.5))
                     (as/evaluation (as/predicate (name (:type r))) source target))]
          (as/add-link! space link)))
      (doseq [c (take 6 (:concepts d))]
        (att/stimulate! bank (keyword c) 9.0))
      (att/stimulate! bank (keyword domain-tag) 12.0)
      (att/update-focus! bank)
      (let [after (as/space-stats space)]
        {:ok true
         :domain (name (keyword domain-id))
         :name (:name d)
         :added-atoms (max 0 (- (:atoms after) (:atoms before)))
         :added-links (max 0 (- (:links after) (:links before)))
         :strategies (:strategies d)}))
    {:ok false
     :error (str "unknown domain: " domain-id)
     :available (available-domains)}))
