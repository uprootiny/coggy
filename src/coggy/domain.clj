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
     "uncertainty-calibrated inference"]}})

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
