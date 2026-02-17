(ns coggy.bench
  "Benchmarks, diagnostics, and challenge drills.

   Three layers:
   1. Benchmarks — measure system behavior quantitatively
   2. Diagnostics — classify blockers and health signals
   3. Drills — inject faults and verify recovery

   Two control models:
   - LLM-first duplex: prompt drift, turn economics, repetition loops
   - Architectured agent: contract integrity, state transitions, invariant checks"
  (:require [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.semantic :as sem]
            [coggy.llm :as llm]
            [clojure.string :as str]))

;; =============================================================================
;; Trace Quality Metrics
;; =============================================================================

(defn trace-quality
  "Score a trace data map for completeness and depth.
   Returns {:score 0.0-1.0 :phases-present [...] :gaps [...]}"
  [trace-data]
  (let [phases [:parse :ground :attend :infer :reflect]
        present (filterv #(seq (get trace-data %)) phases)
        missing (filterv #(nil? (seq (get trace-data %))) phases)
        reflect (:reflect trace-data)
        has-diagnosis (some? (:diagnosis reflect))
        has-next (some? (or (:next-question reflect) (:next reflect)))
        infers (:infer trace-data)
        has-tv (some #(some? (:tv %)) (or infers []))
        raw-score (/ (count present) (count phases))
        bonus (+ (if has-diagnosis 0.05 0.0)
                 (if has-next 0.05 0.0)
                 (if has-tv 0.05 0.0))]
    {:score (min 1.0 (+ (double raw-score) bonus))
     :phases-present (mapv name present)
     :gaps (mapv name missing)
     :has-diagnosis has-diagnosis
     :has-next-question has-next
     :has-truth-values has-tv}))

;; =============================================================================
;; Blocker Classifier
;; =============================================================================

(defn classify-blocker
  "Classify an error or failure into a typed blocker.
   Returns {:type :severity :recovery}"
  [error-map]
  (let [status (:status error-map)
        error (str (or (:error error-map) ""))
        error-lower (str/lower-case error)]
    (cond
      (= :missing-key status)
      {:type :auth-gate
       :severity :critical
       :recovery "Set OPENROUTER_API_KEY in .env"
       :category :llm-first}

      (= 401 status)
      {:type :auth-gate
       :severity :critical
       :recovery "Replace API key"
       :category :llm-first}

      (= 429 status)
      {:type :rate-limit
       :severity :transient
       :recovery "Wait or switch model"
       :category :llm-first}

      (= 402 status)
      {:type :budget-gate
       :severity :degraded
       :recovery "Switch to free model"
       :category :llm-first}

      (str/includes? error-lower "parser")
      {:type :parser-miss
       :severity :degraded
       :recovery "Add semantic suffix"
       :category :architectured}

      (str/includes? error-lower "vacuum")
      {:type :grounding-vacuum
       :severity :degraded
       :recovery "Seed broader ontology"
       :category :architectured}

      (str/includes? error-lower "timeout")
      {:type :timeout
       :severity :transient
       :recovery "Retry with smaller payload"
       :category :llm-first}

      (str/includes? error-lower "permission")
      {:type :sandbox-gate
       :severity :critical
       :recovery "Allow outbound HTTPS"
       :category :architectured}

      :else
      {:type :unknown
       :severity :unknown
       :recovery "Inspect error details"
       :category :unknown})))

;; =============================================================================
;; Smoke Loop Benchmark
;; =============================================================================

(defn smoke-check
  "Run a battery of quick checks. Returns [{:check :ok :detail}]."
  [space bank]
  (let [checks (atom [])]
    ;; AtomSpace health
    (let [stats (as/space-stats space)]
      (swap! checks conj {:check :atomspace-populated
                          :ok (pos? (:atoms stats))
                          :detail stats}))

    ;; Attention bank health
    (let [b @bank
          funds (:sti-funds b)
          focus-count (count (:focus b))]
      (swap! checks conj {:check :attention-funded
                          :ok (> funds -200.0)
                          :detail {:sti-funds funds :focus-count focus-count}})
      (swap! checks conj {:check :focus-populated
                          :ok (pos? focus-count)
                          :detail {:focus-count focus-count}}))

    ;; Semantic pipeline health
    (let [m (sem/metrics-summary)]
      (swap! checks conj {:check :parse-rate-above-50pct
                          :ok (or (zero? (:turns m))
                                  (> (:parse-rate m) 0.5))
                          :detail m})
      (swap! checks conj {:check :no-vacuum-spiral
                          :ok (< (:vacuum-triggers m) 5)
                          :detail {:vacuum-triggers (:vacuum-triggers m)}}))

    ;; API key present
    (swap! checks conj {:check :api-key-present
                        :ok (some? (llm/api-key))
                        :detail {:source (:source (llm/key-source))}})

    @checks))

(defn smoke-summary
  "Summarize smoke check results."
  [checks]
  (let [total (count checks)
        passed (count (filter :ok checks))
        failed (remove :ok checks)]
    {:total total
     :passed passed
     :failed (count failed)
     :blockers (mapv (fn [c] {:check (:check c) :detail (:detail c)}) failed)
     :score (if (pos? total) (double (/ passed total)) 0.0)}))

;; =============================================================================
;; Free Model Reliability Probe
;; =============================================================================

(defn probe-model
  "Probe a single model for liveness. Returns {:model :ok :latency-ms :error}."
  [model]
  (let [t0 (System/currentTimeMillis)
        resp (try
               (llm/chat [{:role "user" :content "Say ok"}]
                          :model model
                          :allow-fallback? false
                          :max-attempts 1)
               (catch Exception e
                 {:ok false :error (.getMessage e)}))]
    {:model model
     :ok (:ok resp)
     :latency-ms (- (System/currentTimeMillis) t0)
     :error (when-not (:ok resp) (:error resp))
     :status (:status resp)}))

;; =============================================================================
;; Evidence Log
;; =============================================================================

(defonce evidence-log (atom []))

(defn log-evidence!
  "Record an observation → decision → action → outcome chain."
  [observation decision action outcome]
  (swap! evidence-log
         (fn [log]
           (take-last 100
                      (conj log {:at (System/currentTimeMillis)
                                 :observation observation
                                 :decision decision
                                 :action action
                                 :outcome outcome})))))

(defn recent-evidence
  "Return last N evidence entries."
  [n]
  (take-last n @evidence-log))

;; =============================================================================
;; Haywire Detection
;; =============================================================================

(defn detect-haywire
  "Detect repeated no-op cycles in evidence log.
   Returns {:haywire? bool :reason str :repeated-actions N}."
  []
  (let [recent (take-last 10 @evidence-log)
        actions (mapv :action recent)
        unique (count (distinct actions))
        total (count actions)]
    (cond
      (and (>= total 5) (< unique 2))
      {:haywire? true
       :reason "repeated identical actions"
       :repeated-actions total
       :action (first actions)}

      (and (>= total 5)
           (every? #(str/includes? (str %) "status") actions))
      {:haywire? true
       :reason "status-command spam"
       :repeated-actions total}

      :else
      {:haywire? false})))
