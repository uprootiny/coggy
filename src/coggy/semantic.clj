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
;; Result Types — totality over partiality
;; =============================================================================

(defn ok [val] {:result/ok true :result/val val})
(defn err [type reason] {:result/ok false :result/type type :result/reason reason})
(defn ok? [r] (true? (:result/ok r)))

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
   Returns (ok semantic-map) or (err :parser-miss reason)."
  [text]
  (let [try-parse (fn [s] (try (read-string s) (catch Exception _ nil)))
        ;; Strategy 1: ```semantic ... ``` block
        s1 (when-let [match (re-find #"(?s)```semantic\s*\n(.*?)```" text)]
             (try-parse (second match)))
        ;; Strategy 2: inline semantic fence
        s2 (when-not s1
             (when-let [match (re-find #"(?s)```semantic\s*(\{.*?\})\s*```" text)]
               (try-parse (second match))))
        ;; Strategy 3: json block with semantic keys
        s3 (when-not (or s1 s2)
             (when-let [match (re-find #"(?s)```json\n(\{.*?\"concepts\".*?\})```" text)]
               (try-parse (str/replace (second match) #"\"(\w+)\":" ":$1 "))))
        ;; Strategy 4: inline {:concepts [...]}
        s4 (when-not (or s1 s2 s3)
             (when-let [match (re-find #"(?s)\{:concepts\s+\[.*?\].*?\}" text)]
               (try-parse match)))
        result (or s1 s2 s3 s4)]
    (if result
      (ok result)
      (err :parser-miss "no semantic block found after 4 extraction strategies"))))

(def fallback-stopwords
  #{"the" "and" "for" "with" "this" "that" "have" "from"
    "were" "been" "into" "your" "about" "what" "when" "where"
    "which" "then" "will" "would" "could" "should" "must"
    "also" "just" "like" "very" "more" "some" "each" "only"
    "parse" "ground" "attend" "infer" "reflect"
    "coggy-trace" "semantic" "stv" "concepts" "relations"})

(defn fallback-semantic-from-text
  "Heuristic fallback when no semantic block is emitted.
   Extracts lightweight concepts and creates star-topology relations
   from the most salient token to the rest."
  [text]
  (let [clean (-> (str/lower-case (or text ""))
                  (str/replace #"(?s)```.*?```" " ")
                  (str/replace #"`" " "))
        tokens (->> (str/split clean #"\s+")
                    (map #(str/replace % #"[^a-z0-9-]" ""))
                    (remove #(or (< (count %) 3)
                                 (contains? fallback-stopwords %)))
                    distinct
                    (take 8)
                    vec)]
    (when (seq tokens)
      (let [hub (first tokens)
            spokes (rest tokens)]
        {:concepts tokens
         :relations (into []
                          (take 4
                                (map (fn [spoke]
                                       {:type :resembles :a hub :b spoke})
                                     spokes)))
         :intent {:type :fallback :target "parser-recovery"}
         :confidence 0.35}))))

;; =============================================================================
;; Normalization
;; =============================================================================

(def no-strip-s
  "Words where trailing 's' is not a plural marker."
  #{"bus" "analysis" "glass" "basis" "process" "focus" "status"
    "consensus" "atlas" "alias" "bias" "chaos" "cosmos" "ethos"
    "logos" "pathos" "thesis" "crisis" "diagnosis" "hypothesis"
    "emphasis" "synthesis" "corpus" "apparatus" "nexus"})

(defn normalize-concept
  "Canonicalize a concept string: lowercase, strip non-alphanum, naive singular."
  [s]
  (let [cleaned (-> (str s)
                    str/trim
                    str/lower-case
                    (str/replace #"[^a-z0-9-]" ""))]
    (if (contains? no-strip-s cleaned)
      cleaned
      (str/replace cleaned #"s$" ""))))

(def concept-aliases
  {"inference" "reasoning"
   "infer" "reasoning"
   "oracle" "llm"
   "logician" "reasoning"
   "atomspace" "ontology"
   "representation" "ontology"
   "mode-router" "attention"
   "select-mode" "attention"
   "control-locu" "attention"
   "temporal-stance" "trace"
   "emulation" "phantasm"
   "emulate" "phantasm"
   "simulator" "phantasm"
   "actor" "human"
   "partner" "human"
   "organism" "hyle"
   "instrument" "form"
   "prompt-chain" "trace"
   "harnes" "harness"})

(defn canonical-concept
  "Apply typo repair and ontology aliases to concept text."
  [s]
  (let [n (normalize-concept s)
        repaired (-> n
                     (str/replace #"-locu$" "-locus")
                     (str/replace #"harnes$" "harness"))]
    (get concept-aliases repaired repaired)))

(defn normalize-semantic
  "Normalize a semantic block: canonicalize concept names."
  [semantic]
  (when semantic
    (-> semantic
        (update :concepts (fn [cs]
                            (->> (or cs [])
                                 (map canonical-concept)
                                 (remove #(or (< (count %) 3)
                                              (#{"coggy-trace" "parse" "ground" "attend" "infer" "reflect"} %)))
                                 distinct
                                 (take 7)
                                 vec)))
        (update :relations (fn [rs]
                             (->> (or rs [])
                                  (map (fn [r]
                                         (-> r
                                             (update :a canonical-concept)
                                             (update :b canonical-concept))))
                                  (remove (fn [r] (= (:a r) (:b r))))
                                  distinct
                                  (take 5)
                                  vec))))))

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
  (let [novel-concepts (:novel grounding)
        funds (:sti-funds @bank)
        stim-scale (cond
                     (> funds 40.0) 1.0
                     (> funds 15.0) 0.65
                     (> funds 0.0) 0.4
                     (> funds -40.0) 0.2
                     :else 0.08)
        decay-rate (cond
                     (< funds -80.0) 0.45
                     (< funds -40.0) 0.32
                     (< funds 0.0) 0.22
                     :else 0.1)]
    ;; Add novel concepts
    (doseq [c novel-concepts]
      (as/add-atom! space (as/concept c (as/stv 0.6 0.3))))

    ;; Stimulate all mentioned concepts
    (doseq [c (:concepts semantic)]
      (let [k (keyword (canonical-concept c))]
        (att/stimulate! bank k
                        (* stim-scale
                           (if (some #{c} (:grounded grounding))
                             8.0   ;; grounded = familiar = moderate boost
                             12.0))))) ;; novel = needs attention

    ;; Add relations as links
    (doseq [r (:relations semantic)]
      (let [source (as/concept (canonical-concept (:a r)))
            target (as/concept (canonical-concept (:b r)))
            link (case (keyword (or (:type r) "relates"))
                   :inherits   (as/inheritance source target (as/stv 0.7 0.4))
                   :causes     (as/implication source target (as/stv 0.6 0.3))
                   :resembles  (as/similarity source target (as/stv 0.7 0.4))
                   :is-a       (as/inheritance source target (as/stv 0.8 0.5))
                   (as/evaluation (as/predicate (name (or (:type r) "relates")))
                                  source target))]
        (as/add-link! space link)))

    ;; Spread activation through committed links
    (doseq [r (:relations semantic)]
      (let [src-key (keyword (canonical-concept (:a r)))]
        (let [related-links (as/query-links space
                              (fn [l] (= (:atom/name (:link/source l)) src-key)))]
          (when (seq related-links)
            (att/spread-activation! bank related-links src-key 0.3)))))

    ;; Decay and update focus
    (att/decay! bank decay-rate)
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
         :budget-exhaustions 0
         :rescue-successes 0
         :last-failure nil}))

(defn metrics-state
  "Raw semantic metrics for persistence."
  []
  @metrics)

(defn restore-metrics!
  "Restore semantic metrics from persisted snapshot."
  [m]
  (reset! metrics
          (merge {:turns 0
                  :parser-hits 0
                  :parser-misses 0
                  :grounding-rates []
                  :relation-rates []
                  :vacuum-triggers 0
                  :budget-exhaustions 0
                  :rescue-successes 0
                  :last-failure nil}
                 (or m {}))))

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
     :budget-exhaustions (:budget-exhaustions m)
     :last-failure (:last-failure m)}))

;; =============================================================================
;; Grounding Vacuum Reflex
;; =============================================================================

(defn diagnose-failure
  "Diagnose grounding result. Returns (ok {:type :healthy}) or (err :type reason).
   Total: always returns a result, never nil."
  [semantic concept-grounding relation-grounding bank]
  (cond
    (nil? semantic)
    (err :parser-miss "LLM did not produce SEMANTIC block")

    (empty? (:concepts semantic))
    (err :parser-miss "SEMANTIC block had no concepts")

    (zero? (:rate concept-grounding))
    (err :grounding-vacuum "no concepts matched existing atoms")

    (and (seq (:relations semantic))
         (zero? (:rate relation-grounding)))
    (err :ontology-miss "concepts grounded but no relations matched")

    (< (:sti-funds @bank) -120.0)
    (err :budget-exhausted "ECAN funds depleted")

    (and (> (:rate concept-grounding) 0.5)
         (some? (:confidence semantic))
         (< (:confidence semantic) 0.3))
    (err :contradiction-blocked "low semantic confidence despite grounded concepts")

    :else
    (ok {:type :healthy
         :grounding-rate (:rate concept-grounding)
         :relation-rate (:rate relation-grounding)})))

(defn vacuum-detected?
  "Check if we're in a grounding vacuum (N consecutive failures)."
  [n]
  (let [rates (take n (:grounding-rates @metrics))]
    (and (>= (count rates) n)
         (every? zero? rates))))

(defn trigger-rescue!
  "Execute the grounding vacuum rescue reflex.
   Returns (ok action-description) or (err :not-implemented reason)."
  [space bank failure]
  (swap! metrics update :vacuum-triggers inc)

  (let [failure-type (:result/type failure)]
    (case failure-type
      :grounding-vacuum
      (do
        (doseq [c ["thing" "idea" "action" "state" "relation"
                    "cause" "effect" "agent" "object" "property"]]
          (as/add-atom! space (as/concept c (as/stv 0.5 0.2)))
          (att/stimulate! bank (keyword c) 3.0))
        (att/update-focus! bank)
        (ok "seeded broad ontology footholds"))

      :budget-exhausted
      (do
        (att/decay! bank 0.9)
        (att/update-focus! bank)
        (ok "reset attention bank"))

      :parser-miss
      (ok "will add semantic prompt suffix next turn")

      :ontology-miss
      (let [;; Find grounded concepts in focus
            focused (att/focus-atoms bank)
            focus-keys (set (map :key focused))
            ;; Query existing links involving focused atoms
            existing (as/query-links space
                       (fn [l] (or (focus-keys (:atom/name (:link/source l)))
                                   (focus-keys (:atom/name (:link/target l))))))
            ;; Extract parent types from inheritance links
            parents (->> existing
                         (filter #(= :InheritanceLink (:atom/type %)))
                         (map #(:atom/name (:link/target %)))
                         (remove nil?)
                         set)
            ;; Find siblings: concepts sharing a parent
            siblings (when (seq parents)
                       (->> (as/query-links space
                              (fn [l] (and (= :InheritanceLink (:atom/type l))
                                           (parents (:atom/name (:link/target l))))))
                            (map (fn [l] (:atom/name (:link/source l))))
                            (remove nil?)
                            distinct))
            ;; Create similarity links between focused atoms and their siblings
            pairs (for [f focus-keys
                        s siblings
                        :when (and s (not= f s))]
                    [f s])]
        (if (seq pairs)
          (do
            (doseq [[a b] (take 4 pairs)]
              (as/add-link! space
                (as/similarity (as/concept (name a)) (as/concept (name b))
                               (as/stv 0.5 0.3)))
              (att/stimulate! bank b 4.0))
            (att/update-focus! bank)
            (ok (str "inferred " (min 4 (count pairs)) " similarity links from shared parents")))
          ;; Fallback: link focused concepts to each other
          (let [fkeys (take 3 (map :key focused))]
            (doseq [[a b] (partition 2 1 fkeys)]
              (as/add-link! space
                (as/similarity (as/concept (name a)) (as/concept (name b))
                               (as/stv 0.4 0.2))))
            (ok (str "linked " (count fkeys) " focused concepts by proximity")))))

      :contradiction-blocked
      (let [;; Re-add all grounded concepts with the semantic's confidence
            ;; This triggers tv-revise in add-atom!, merging toward coherence
            conf (or (:confidence (second (find failure :result/reason))) 0.3)
            focused (att/focus-atoms bank)
            focus-keys (map :key focused)
            revised (doall
                      (for [k focus-keys
                            :let [existing (as/get-atom space (name k))]
                            :when (and existing (:atom/tv existing))]
                        (do
                          (as/add-atom! space
                            (assoc existing :atom/tv (as/stv conf 0.4)))
                          k)))]
        (att/decay! bank 0.15)
        (att/update-focus! bank)
        (ok (str "revised " (count revised) " atoms toward confidence " (format "%.1f" (double conf)))))

      (err :not-implemented (str "unknown recovery for " failure-type)))))

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
Contract rules:
- keep concepts canonical and reusable across turns
- include at least one relation, even if weak/conflicted
- prefer architecture-internal concepts (ontology, attention, reasoning, trace, human, llm, hyle, form, phantasm) when relevant
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
  (let [extract-result (extract-semantic-block llm-response)
        raw (if (ok? extract-result)
              (:result/val extract-result)
              (fallback-semantic-from-text llm-response))
        semantic (normalize-semantic raw)
        concept-ground (ground-concepts space (or (:concepts semantic) []))
        relation-ground (ground-relations space (or (:relations semantic) []))
        diagnosis (diagnose-failure semantic concept-ground relation-ground bank)]

    ;; Record metrics
    (record-metrics! semantic concept-ground relation-ground)
    (when (= :budget-exhausted (:result/type diagnosis))
      (swap! metrics update :budget-exhaustions (fnil inc 0)))

    ;; Commit if we have anything
    (when (and semantic (seq (:concepts semantic)))
      (commit-semantics! space bank semantic concept-ground))

    ;; Rescue if needed (only on failure, not on healthy)
    (let [rescue (when (and (not (ok? diagnosis)) (vacuum-detected? 2))
                   (trigger-rescue! space bank diagnosis))]

      {:semantic semantic
       :grounding {:concepts concept-ground
                   :relations relation-ground}
       :diagnosis diagnosis
       :rescue rescue
       :metrics (metrics-summary)})))
