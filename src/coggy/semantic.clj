(ns coggy.semantic
  "Semantic contract enforcement — the grounding vacuum killer.

   Every LLM response MUST produce a machine-checkable SEMANTIC block.
   The middleware validates, normalizes, grounds against the AtomSpace,
   and records metrics. If grounding fails repeatedly, the vacuum reflex
   triggers automatic rescue routing.

   Failure modes are typed, not mysterious:
   - :grounding-vacuum   — nothing latches to existing atoms
   - :parser-miss        — SEMANTIC block absent or malformed
   - :ontology-miss      — concepts exist but no relations ground
   - :budget-exhausted   — ECAN funds depleted
   - :contradiction-blocked — conflicting truth values")

(require '[coggy.atomspace :as as])
(require '[coggy.attention :as att])
(require '[clojure.string :as str])

;; =============================================================================
;; Semantic Block Schema
;; =============================================================================
;;
;; Every role output must include:
;; ```semantic
;; {:concepts ["voice" "signal" "agent"]
;;  :relations [{:type :inherits :a "voice" :b "signal"}
;;              {:type :causes :a "noise" :b "inference-failure"}]
;;  :intent {:type :debug :target "grounding"}
;;  :confidence 0.62}
;; ```

(defn extract-semantic-block
  "Extract the semantic block from LLM response text.
   Tries EDN first, then JSON-ish, then brute-force concept extraction."
  [text]
  (or
    ;; Try ```semantic ... ``` block
    (when-let [match (re-find #"(?s)```semantic\n(.*?)```" text)]
      (try
        (read-string (second match))
        (catch Exception _ nil)))

    ;; Try ```json ... ``` with semantic keys
    (when-let [match (re-find #"(?s)```json\n(\{.*?\"concepts\".*?\})```" text)]
      (try
        (let [parsed (read-string (str/replace (second match) #"\"(\w+)\":" ":$1 "))]
          parsed)
        (catch Exception _ nil)))

    ;; Try inline {:concepts [...]}
    (when-let [match (re-find #"(?s)\{:concepts\s+\[.*?\].*?\}" text)]
      (try
        (read-string match)
        (catch Exception _ nil)))

    ;; Fallback: nil (parser-miss)
    nil))

;; =============================================================================
;; Normalization
;; =============================================================================

(defn normalize-concept
  "Canonicalize a concept string: lowercase, singular, trimmed."
  [s]
  (-> (str s)
      str/trim
      str/lower-case
      (str/replace #"[^a-z0-9-]" "")
      (str/replace #"s$" "")))  ;; naive singular — good enough

(defn normalize-semantic
  "Normalize a semantic block: canonicalize concept names."
  [semantic]
  (when semantic
    (-> semantic
        (update :concepts (fn [cs] (mapv normalize-concept (or cs []))))
        (update :relations (fn [rs]
                             (mapv (fn [r]
                                     (-> r
                                         (update :a normalize-concept)
                                         (update :b normalize-concept)))
                                   (or rs [])))))))

;; =============================================================================
;; Grounding — match against AtomSpace
;; =============================================================================

(defn ground-concepts
  "Attempt to ground concepts against the AtomSpace.
   Returns {:grounded [...] :novel [...] :rate float}."
  [space concepts]
  (let [results (mapv (fn [c]
                        (let [found (as/get-atom space c)]
                          {:concept c
                           :grounded? (some? found)
                           :atom found}))
                      concepts)
        grounded (filterv :grounded? results)
        novel (filterv (complement :grounded?) results)]
    {:grounded (mapv :concept grounded)
     :novel (mapv :concept novel)
     :rate (if (seq concepts)
             (double (/ (count grounded) (count concepts)))
             0.0)
     :details results}))

(defn ground-relations
  "Attempt to ground relations against existing links."
  [space relations]
  (let [results (mapv (fn [r]
                        (let [source-atom (as/get-atom space (:a r))
                              target-atom (as/get-atom space (:b r))
                              both-exist? (and source-atom target-atom)]
                          {:relation r
                           :grounded? both-exist?}))
                      relations)]
    {:grounded (filterv :grounded? results)
     :novel (filterv (complement :grounded?) results)
     :rate (if (seq relations)
             (double (/ (count (filter :grounded? results)) (count relations)))
             0.0)}))

;; =============================================================================
;; Commit — write grounded semantics into AtomSpace
;; =============================================================================

(defn commit-semantics!
  "Commit validated semantics to AtomSpace + attention bank."
  [space bank semantic grounding]
  (let [novel-concepts (:novel grounding)]
    ;; Add novel concepts
    (doseq [c novel-concepts]
      (as/add-atom! space (as/concept c (as/stv 0.6 0.3))))

    ;; Stimulate all mentioned concepts
    (doseq [c (:concepts semantic)]
      (let [k (keyword (normalize-concept c))]
        (att/stimulate! bank k
                        (if (some #{c} (:grounded grounding))
                          8.0   ;; grounded = familiar = moderate boost
                          12.0)))) ;; novel = needs attention

    ;; Add relations as links
    (doseq [r (:relations semantic)]
      (let [source (as/concept (normalize-concept (:a r)))
            target (as/concept (normalize-concept (:b r)))
            link (case (keyword (or (:type r) "relates"))
                   :inherits   (as/inheritance source target (as/stv 0.7 0.4))
                   :causes     (as/implication source target (as/stv 0.6 0.3))
                   :resembles  (as/similarity source target (as/stv 0.7 0.4))
                   :is-a       (as/inheritance source target (as/stv 0.8 0.5))
                   (as/evaluation (as/predicate (name (or (:type r) "relates")))
                                  source target))]
        (as/add-link! space link)))

    ;; Decay and update focus
    (att/decay! bank 0.1)
    (att/update-focus! bank)))

;; =============================================================================
;; Metrics — track semantic extraction health
;; =============================================================================

(defonce metrics
  (atom {:turns 0
         :parser-hits 0
         :parser-misses 0
         :grounding-rates []     ;; last N rates
         :relation-rates []
         :vacuum-triggers 0
         :rescue-successes 0
         :last-failure nil}))

(defn record-metrics!
  "Record metrics for this turn."
  [semantic concept-grounding relation-grounding]
  (swap! metrics
         (fn [m]
           (let [m (update m :turns inc)]
             (if semantic
               (-> m
                   (update :parser-hits inc)
                   (update :grounding-rates
                           (fn [rs] (take 20 (cons (:rate concept-grounding) rs))))
                   (update :relation-rates
                           (fn [rs] (take 20 (cons (:rate relation-grounding) rs)))))
               (-> m
                   (update :parser-misses inc)
                   (assoc :last-failure {:type :parser-miss
                                         :turn (:turns m)})))))))

(defn avg-rate [rates]
  (if (seq rates)
    (/ (reduce + rates) (count rates))
    0.0))

(defn metrics-summary []
  (let [m @metrics]
    {:turns (:turns m)
     :parse-rate (if (pos? (:turns m))
                   (double (/ (:parser-hits m) (:turns m)))
                   0.0)
     :avg-grounding-rate (avg-rate (:grounding-rates m))
     :avg-relation-rate (avg-rate (:relation-rates m))
     :vacuum-triggers (:vacuum-triggers m)
     :last-failure (:last-failure m)}))

;; =============================================================================
;; Grounding Vacuum Reflex
;; =============================================================================

(defn diagnose-failure
  "Diagnose why grounding failed. Returns typed failure."
  [semantic concept-grounding relation-grounding bank]
  (cond
    (nil? semantic)
    {:type :parser-miss
     :reason "LLM did not produce SEMANTIC block"
     :recovery :add-semantic-suffix}

    (empty? (:concepts semantic))
    {:type :parser-miss
     :reason "SEMANTIC block had no concepts"
     :recovery :add-semantic-suffix}

    (zero? (:rate concept-grounding))
    {:type :grounding-vacuum
     :reason "no concepts matched existing atoms"
     :recovery :seed-ontology}

    (and (seq (:relations semantic))
         (zero? (:rate relation-grounding)))
    {:type :ontology-miss
     :reason "concepts grounded but no relations matched"
     :recovery :extend-relations}

    (< (:sti-funds @bank) 5.0)
    {:type :budget-exhausted
     :reason "ECAN funds depleted"
     :recovery :reset-attention}

    :else nil))

(defn vacuum-detected?
  "Check if we're in a grounding vacuum (N consecutive failures)."
  [n]
  (let [rates (take n (:grounding-rates @metrics))]
    (and (>= (count rates) n)
         (every? zero? rates))))

(defn trigger-rescue!
  "Execute the grounding vacuum rescue reflex."
  [space bank failure]
  (swap! metrics update :vacuum-triggers inc)

  (case (:recovery failure)
    :seed-ontology
    (do
      ;; Seed some broad concepts to create footholds
      (doseq [c ["thing" "idea" "action" "state" "relation"
                  "cause" "effect" "agent" "object" "property"]]
        (as/add-atom! space (as/concept c (as/stv 0.5 0.2)))
        (att/stimulate! bank (keyword c) 3.0))
      (att/update-focus! bank)
      {:rescued true :action "seeded broad ontology footholds"})

    :reset-attention
    (do
      (att/decay! bank 0.9)  ;; massive decay to reclaim funds
      (att/update-focus! bank)
      {:rescued true :action "reset attention bank"})

    :extend-relations
    {:rescued false :action "needs librarian role (not yet implemented)"}

    :add-semantic-suffix
    {:rescued false :action "will add semantic prompt suffix next turn"}

    {:rescued false :action "unknown recovery path"}))

;; =============================================================================
;; Semantic Prompt Suffix — enforce the contract
;; =============================================================================

(def semantic-suffix
  "\n\nIMPORTANT: Your response MUST end with a semantic block:
```semantic
{:concepts [\"concept1\" \"concept2\"]
 :relations [{:type :inherits :a \"concept1\" :b \"concept2\"}]
 :intent {:type :question :target \"topic\"}
 :confidence 0.7}
```
Even if uncertain, always emit concepts and relations with low confidence.
Never emit an empty structure. Guess if you must.")

(defn should-add-suffix?
  "Should we add the semantic enforcement suffix?
   Yes if: parser-miss rate > 50% or vacuum detected."
  []
  (let [m @metrics]
    (or (< (:turns m) 3)  ;; always for first 3 turns
        (> (:parser-misses m) (/ (:turns m) 2))
        (vacuum-detected? 2))))

;; =============================================================================
;; Full Pipeline
;; =============================================================================

(defn process-semantic!
  "Full semantic processing pipeline for an LLM response.
   Returns {:semantic :grounding :diagnosis :rescue :metrics}."
  [space bank llm-response]
  (let [raw (extract-semantic-block llm-response)
        semantic (normalize-semantic raw)
        concept-ground (ground-concepts space (or (:concepts semantic) []))
        relation-ground (ground-relations space (or (:relations semantic) []))
        diagnosis (diagnose-failure semantic concept-ground relation-ground bank)]

    ;; Record metrics
    (record-metrics! semantic concept-ground relation-ground)

    ;; Commit if we have anything
    (when (and semantic (seq (:concepts semantic)))
      (commit-semantics! space bank semantic concept-ground))

    ;; Rescue if needed
    (let [rescue (when (and diagnosis (vacuum-detected? 2))
                   (trigger-rescue! space bank diagnosis))]

      {:semantic semantic
       :grounding {:concepts concept-ground
                   :relations relation-ground}
       :diagnosis diagnosis
       :rescue rescue
       :metrics (metrics-summary)})))
