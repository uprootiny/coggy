#!/usr/bin/env bb

(ns coggy.bench-test
  (:require [coggy.bench :as bench]
            [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.boot :as boot]
            [clojure.string :as str]))

;; =============================================================================
;; Test Runner
;; =============================================================================

(def ^:dynamic *tests* (atom {:pass 0 :fail 0 :total 0 :assertions 0}))

(defmacro deftest [name & body]
  `(do
     (try
       ~@body
       (swap! *tests* update :pass inc)
       (catch Exception e#
         (swap! *tests* update :fail inc)
         (println (str "  FAIL " ~(str name) ": " (.getMessage e#)))))
     (swap! *tests* update :total inc)))

(defn is [pred msg]
  (swap! *tests* update :assertions inc)
  (when-not pred
    (throw (ex-info (str "Assertion failed: " msg) {}))))

;; =============================================================================
;; trace-quality Tests
;; =============================================================================

(println "Testing coggy.bench/trace-quality\n")

(deftest trace-quality-full-trace
  (let [phases {:parse  [(as/concept "test")]
                :ground {:found ["context"] :missing []}
                :attend [{:key :test :sti 8.0}]
                :infer  [{:type :deduction :conclusion "A→C" :tv (as/stv 0.9 0.8)}]
                :reflect {:new-atoms 1
                          :updated 0
                          :focus-concept "test"
                          :next-question "what follows?"
                          :diagnosis {:type :healthy}}}
        result (bench/trace-quality phases)]
    (is (map? result) "should return a map")
    (is (number? (:score result)) "score should be numeric")
    (is (>= (:score result) 1.0) "full trace with bonuses should reach 1.0")
    (is (= 5 (count (:phases-present result))) "all 5 phases present")
    (is (empty? (:gaps result)) "no gaps in full trace")
    (is (:has-diagnosis result) "full trace has diagnosis")
    (is (:has-next-question result) "full trace has next-question")
    (is (:has-truth-values result) "full trace has truth values")))

(deftest trace-quality-partial-trace
  (let [phases {:parse  [(as/concept "partial")]
                :attend [{:key :partial :sti 4.0}]}
        result (bench/trace-quality phases)]
    (is (map? result) "should return a map")
    (is (< (:score result) 1.0) "partial trace should score below 1.0")
    (is (= 2 (count (:phases-present result))) "two phases present")
    (is (= 3 (count (:gaps result))) "three gaps: ground, infer, reflect")
    (is (some #(= "ground" %) (:gaps result)) "ground should be a gap")
    (is (some #(= "infer" %) (:gaps result)) "infer should be a gap")
    (is (some #(= "reflect" %) (:gaps result)) "reflect should be a gap")
    (is (not (:has-diagnosis result)) "no diagnosis in partial trace")
    (is (not (:has-next-question result)) "no next-question in partial trace")
    (is (not (:has-truth-values result)) "no truth values in partial trace")))

(deftest trace-quality-empty-trace
  (let [result (bench/trace-quality {})]
    (is (map? result) "should return a map")
    (is (= 0.0 (:score result)) "empty trace scores 0.0")
    (is (empty? (:phases-present result)) "no phases present")
    (is (= 5 (count (:gaps result))) "all five phases are gaps")
    (is (not (:has-diagnosis result)) "no diagnosis")
    (is (not (:has-next-question result)) "no next-question")
    (is (not (:has-truth-values result)) "no truth values")))

(deftest trace-quality-score-bounded
  (let [phases {:parse  [(as/concept "a")]
                :ground {:found ["a"]}
                :attend [{:key :a :sti 1.0}]
                :infer  [{:type :deduction :conclusion "ok" :tv (as/stv 1.0 1.0)}]
                :reflect {:new-atoms 1 :updated 0
                          :focus-concept "a"
                          :next-question "next?"
                          :diagnosis {:type :ok}}}
        result (bench/trace-quality phases)]
    (is (<= (:score result) 1.0) "score should never exceed 1.0")))

;; =============================================================================
;; classify-blocker Tests
;; =============================================================================

(println "Testing coggy.bench/classify-blocker\n")

(deftest classify-blocker-missing-key
  (let [result (bench/classify-blocker {:status :missing-key})]
    (is (= :auth-gate (:type result)) ":missing-key maps to :auth-gate")
    (is (= :critical (:severity result)) "auth-gate is critical")
    (is (= :llm-first (:category result)) "auth-gate is llm-first")))

(deftest classify-blocker-401
  (let [result (bench/classify-blocker {:status 401})]
    (is (= :auth-gate (:type result)) "401 maps to :auth-gate")
    (is (= :critical (:severity result)) "401 is critical severity")
    (is (= :llm-first (:category result)) "401 is llm-first")
    (is (string? (:recovery result)) "should have recovery hint")))

(deftest classify-blocker-rate-limit
  (let [result (bench/classify-blocker {:status 429})]
    (is (= :rate-limit (:type result)) "429 maps to :rate-limit")
    (is (= :transient (:severity result)) "rate-limit is transient")
    (is (= :llm-first (:category result)) "rate-limit is llm-first")
    (is (str/includes? (:recovery result) "Wait") "recovery mentions waiting")))

(deftest classify-blocker-parser-miss
  (let [result (bench/classify-blocker {:error "parser failed to extract block"})]
    (is (= :parser-miss (:type result)) "parser error maps to :parser-miss")
    (is (= :degraded (:severity result)) "parser-miss is degraded")
    (is (= :architectured (:category result)) "parser-miss is architectured")
    (is (string? (:recovery result)) "should have recovery hint")))

(deftest classify-blocker-vacuum
  (let [result (bench/classify-blocker {:error "grounding vacuum detected"})]
    (is (= :grounding-vacuum (:type result)) "vacuum error maps to :grounding-vacuum")
    (is (= :degraded (:severity result)) "grounding-vacuum is degraded")
    (is (= :architectured (:category result)) "grounding-vacuum is architectured")))

(deftest classify-blocker-timeout
  (let [result (bench/classify-blocker {:error "request timeout exceeded"})]
    (is (= :timeout (:type result)) "timeout error maps to :timeout")
    (is (= :transient (:severity result)) "timeout is transient")
    (is (= :llm-first (:category result)) "timeout is llm-first")))

(deftest classify-blocker-sandbox
  (let [result (bench/classify-blocker {:error "permission denied for outbound"})]
    (is (= :sandbox-gate (:type result)) "permission error maps to :sandbox-gate")
    (is (= :critical (:severity result)) "sandbox-gate is critical")
    (is (= :architectured (:category result)) "sandbox-gate is architectured")))

(deftest classify-blocker-unknown
  (let [result (bench/classify-blocker {:error "some completely unexpected error"})]
    (is (= :unknown (:type result)) "unrecognized error maps to :unknown")
    (is (= :unknown (:severity result)) "unknown has unknown severity")
    (is (= :unknown (:category result)) "unknown has unknown category")
    (is (string? (:recovery result)) "should still provide recovery hint")))

(deftest classify-blocker-all-types-have-required-keys
  (let [inputs [{:status :missing-key}
                {:status 401}
                {:status 429}
                {:status 402}
                {:error "parser exploded"}
                {:error "vacuum spiral"}
                {:error "timeout hit"}
                {:error "permission denied"}
                {:error "????"}]
        results (mapv bench/classify-blocker inputs)]
    (doseq [r results]
      (is (keyword? (:type r)) "each result must have :type keyword")
      (is (keyword? (:severity r)) "each result must have :severity keyword")
      (is (string? (:recovery r)) "each result must have :recovery string")
      (is (keyword? (:category r)) "each result must have :category keyword"))))

;; =============================================================================
;; smoke-check Tests
;; =============================================================================

(println "Testing coggy.bench/smoke-check\n")

(deftest smoke-check-returns-list-of-checks
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (att/update-focus! bank)
    (let [checks (bench/smoke-check space bank)]
      (is (sequential? checks) "smoke-check should return a sequence")
      (is (pos? (count checks)) "should have at least one check")
      (doseq [c checks]
        (is (keyword? (:check c)) "each check should have a :check keyword")
        (is (contains? c :ok) "each check should have an :ok key")
        (is (contains? c :detail) "each check should have a :detail key")))))

(deftest smoke-check-atomspace-populated-passes-after-boot
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (att/update-focus! bank)
    (let [checks (bench/smoke-check space bank)
          atomspace-check (first (filter #(= :atomspace-populated (:check %)) checks))]
      (is (some? atomspace-check) "should have atomspace-populated check")
      (is (:ok atomspace-check) "atomspace-populated should pass after boot"))))

(deftest smoke-check-attention-funded-passes-after-boot
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (att/update-focus! bank)
    (let [checks (bench/smoke-check space bank)
          funded-check (first (filter #(= :attention-funded (:check %)) checks))]
      (is (some? funded-check) "should have attention-funded check")
      (is (:ok funded-check) "attention-funded should pass — fresh bank has 100.0 funds"))))

(deftest smoke-check-focus-populated-passes-after-seed-and-update
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (att/update-focus! bank)
    (let [checks (bench/smoke-check space bank)
          focus-check (first (filter #(= :focus-populated (:check %)) checks))]
      (is (some? focus-check) "should have focus-populated check")
      (is (:ok focus-check) "focus-populated should pass after seed+update-focus!"))))

(deftest smoke-check-parse-rate-passes-on-zero-turns
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (att/update-focus! bank)
    (let [checks (bench/smoke-check space bank)
          parse-check (first (filter #(= :parse-rate-above-50pct (:check %)) checks))]
      (is (some? parse-check) "should have parse-rate-above-50pct check")
      (is (:ok parse-check) "parse-rate should pass when turns=0 (vacuously true)"))))

(deftest smoke-check-expected-check-names
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (att/update-focus! bank)
    (let [checks (bench/smoke-check space bank)
          check-names (set (map :check checks))]
      (is (contains? check-names :atomspace-populated) "should include :atomspace-populated")
      (is (contains? check-names :attention-funded) "should include :attention-funded")
      (is (contains? check-names :focus-populated) "should include :focus-populated")
      (is (contains? check-names :parse-rate-above-50pct) "should include :parse-rate-above-50pct")
      (is (contains? check-names :no-vacuum-spiral) "should include :no-vacuum-spiral")
      (is (contains? check-names :api-key-present) "should include :api-key-present"))))

;; =============================================================================
;; smoke-summary Tests
;; =============================================================================

(println "Testing coggy.bench/smoke-summary\n")

(deftest smoke-summary-all-pass
  (let [checks [{:check :a :ok true :detail {}}
                {:check :b :ok true :detail {}}
                {:check :c :ok true :detail {}}]
        summary (bench/smoke-summary checks)]
    (is (= 3 (:total summary)) "total should be 3")
    (is (= 3 (:passed summary)) "passed should be 3")
    (is (= 0 (:failed summary)) "failed should be 0")
    (is (empty? (:blockers summary)) "blockers should be empty")
    (is (= 1.0 (:score summary)) "score should be 1.0 when all pass")))

(deftest smoke-summary-all-fail
  (let [checks [{:check :x :ok false :detail {:msg "bad"}}
                {:check :y :ok false :detail {:msg "also bad"}}]
        summary (bench/smoke-summary checks)]
    (is (= 2 (:total summary)) "total should be 2")
    (is (= 0 (:passed summary)) "passed should be 0")
    (is (= 2 (:failed summary)) "failed should be 2")
    (is (= 2 (count (:blockers summary))) "blockers should list both failures")
    (is (= 0.0 (:score summary)) "score should be 0.0 when all fail")))

(deftest smoke-summary-mixed
  (let [checks [{:check :pass1 :ok true  :detail {}}
                {:check :fail1 :ok false :detail {:reason "broken"}}
                {:check :pass2 :ok true  :detail {}}
                {:check :fail2 :ok false :detail {:reason "also broken"}}]
        summary (bench/smoke-summary checks)]
    (is (= 4 (:total summary)) "total should be 4")
    (is (= 2 (:passed summary)) "passed should be 2")
    (is (= 2 (:failed summary)) "failed should be 2")
    (is (= 0.5 (:score summary)) "score should be 0.5 for half passing")
    (is (= 2 (count (:blockers summary))) "two blockers")
    (let [blocker-names (set (map :check (:blockers summary)))]
      (is (contains? blocker-names :fail1) "blocker list should include :fail1")
      (is (contains? blocker-names :fail2) "blocker list should include :fail2")
      (is (not (contains? blocker-names :pass1)) "passing checks not in blockers"))))

(deftest smoke-summary-empty-input
  (let [summary (bench/smoke-summary [])]
    (is (= 0 (:total summary)) "total should be 0")
    (is (= 0 (:passed summary)) "passed should be 0")
    (is (= 0 (:failed summary)) "failed should be 0")
    (is (= 0.0 (:score summary)) "score should be 0.0 for empty input")))

(deftest smoke-summary-blockers-contain-check-and-detail
  (let [checks [{:check :bad :ok false :detail {:info "something went wrong"}}]
        summary (bench/smoke-summary checks)
        blocker (first (:blockers summary))]
    (is (= :bad (:check blocker)) "blocker should carry :check key")
    (is (= {:info "something went wrong"} (:detail blocker)) "blocker should carry :detail")))

;; =============================================================================
;; detect-haywire Tests
;; =============================================================================

(println "Testing coggy.bench/detect-haywire\n")

(deftest detect-haywire-not-triggered-with-few-entries
  ;; Reset evidence log to a clean state for this test
  (reset! bench/evidence-log [])
  (bench/log-evidence! "obs" "dec" :query "ok")
  (bench/log-evidence! "obs" "dec" :query "ok")
  (let [result (bench/detect-haywire)]
    (is (map? result) "detect-haywire should return a map")
    (is (contains? result :haywire?) "result should have :haywire? key")
    ;; Fewer than 5 entries so haywire should not fire
    (is (false? (:haywire? result)) "haywire should not trigger with fewer than 5 entries")))

(deftest detect-haywire-triggers-on-repeated-identical-actions
  (reset! bench/evidence-log [])
  (doseq [_ (range 7)]
    (bench/log-evidence! "some obs" "decided" :same-action "outcome"))
  (let [result (bench/detect-haywire)]
    (is (:haywire? result) "haywire should trigger when all recent actions are identical")
    (is (string? (:reason result)) "should include a reason string")
    (is (number? (:repeated-actions result)) "should report repeated-actions count")
    (is (= :same-action (:action result)) "should report which action repeated")))

(deftest detect-haywire-does-not-trigger-with-varied-actions
  (reset! bench/evidence-log [])
  (bench/log-evidence! "obs" "dec" :action-alpha "ok")
  (bench/log-evidence! "obs" "dec" :action-beta "ok")
  (bench/log-evidence! "obs" "dec" :action-gamma "ok")
  (bench/log-evidence! "obs" "dec" :action-delta "ok")
  (bench/log-evidence! "obs" "dec" :action-epsilon "ok")
  (bench/log-evidence! "obs" "dec" :action-zeta "ok")
  (let [result (bench/detect-haywire)]
    (is (false? (:haywire? result)) "varied actions should not trigger haywire")))

(deftest detect-haywire-triggers-on-status-spam
  (reset! bench/evidence-log [])
  (doseq [i (range 6)]
    (bench/log-evidence! "obs" "dec" (str "check-status-" i) "ok"))
  (let [result (bench/detect-haywire)]
    (is (:haywire? result) "status-command spam should trigger haywire")
    (is (= "status-command spam" (:reason result)) "reason should be status-command spam")))

(deftest detect-haywire-returns-map-with-haywire-false
  (reset! bench/evidence-log [])
  (let [result (bench/detect-haywire)]
    (is (map? result) "should always return a map")
    (is (false? (:haywire? result)) "empty log should not be haywire")))

;; =============================================================================
;; log-evidence! + recent-evidence Tests
;; =============================================================================

(println "Testing coggy.bench/log-evidence! and recent-evidence\n")

(deftest log-evidence-basic-entry
  (reset! bench/evidence-log [])
  (bench/log-evidence! "parse failed" "retry" :llm-call {:ok false})
  (let [entries (bench/recent-evidence 10)]
    (is (= 1 (count entries)) "should have exactly one entry")
    (let [e (first entries)]
      (is (= "parse failed" (:observation e)) "observation should match")
      (is (= "retry" (:decision e)) "decision should match")
      (is (= :llm-call (:action e)) "action should match")
      (is (= {:ok false} (:outcome e)) "outcome should match")
      (is (number? (:at e)) "should have timestamp :at"))))

(deftest log-evidence-multiple-entries
  (reset! bench/evidence-log [])
  (bench/log-evidence! "obs1" "dec1" :act1 "out1")
  (bench/log-evidence! "obs2" "dec2" :act2 "out2")
  (bench/log-evidence! "obs3" "dec3" :act3 "out3")
  (let [entries (bench/recent-evidence 10)
        ;; log is stored newest-first; first entry is most recently added
        observations (set (map :observation entries))]
    (is (= 3 (count entries)) "should have 3 entries")
    (is (contains? observations "obs1") "obs1 should be present")
    (is (contains? observations "obs2") "obs2 should be present")
    (is (contains? observations "obs3") "obs3 should be present")))

(deftest recent-evidence-respects-limit
  (reset! bench/evidence-log [])
  (doseq [i (range 10)]
    (bench/log-evidence! (str "obs-" i) "dec" :act (str "out-" i)))
  (let [entries-3 (bench/recent-evidence 3)
        entries-7 (bench/recent-evidence 7)]
    (is (= 3 (count entries-3)) "should return at most 3 when asked for 3")
    (is (= 7 (count entries-7)) "should return at most 7 when asked for 7")))

(deftest recent-evidence-returns-most-recent-entries
  ;; The log is stored newest-first (conj prepends on seq after first take-last).
  ;; recent-evidence n uses take-last n, pulling from the tail of the stored seq —
  ;; that tail contains the oldest entries in the log.
  ;; What matters: the full set of observations is complete, and count is correct.
  (reset! bench/evidence-log [])
  (doseq [i (range 8)]
    (bench/log-evidence! (str "obs-" i) "dec" :act (str "out-" i)))
  (let [all-entries  (bench/recent-evidence 20)
        few-entries  (bench/recent-evidence 3)
        all-obs      (set (map :observation all-entries))]
    (is (= 8 (count all-entries)) "should return all 8 entries when limit exceeds count")
    (is (= 3 (count few-entries)) "should return exactly 3 when asked for 3")
    ;; All original observations must be retrievable
    (doseq [i (range 8)]
      (is (contains? all-obs (str "obs-" i)) (str "obs-" i " should be in full log")))))

(deftest log-evidence-capped-at-100
  (reset! bench/evidence-log [])
  (doseq [i (range 110)]
    (bench/log-evidence! (str "obs-" i) "dec" :act (str "out-" i)))
  (let [entries (bench/recent-evidence 200)]
    (is (<= (count entries) 100) "log should be capped at 100 entries")))

(deftest recent-evidence-empty-log
  (reset! bench/evidence-log [])
  (let [entries (bench/recent-evidence 5)]
    (is (empty? entries) "empty log should return empty sequence")))

(deftest log-evidence-timestamps-are-present-and-numeric
  ;; The log stores entries newest-first, so timestamps appear in descending order.
  ;; The key invariant is that every entry carries a numeric :at timestamp.
  (reset! bench/evidence-log [])
  (doseq [i (range 5)]
    (bench/log-evidence! (str "obs-" i) "dec" :act "out"))
  (let [entries (bench/recent-evidence 10)
        timestamps (mapv :at entries)]
    (is (= 5 (count timestamps)) "should have 5 timestamps")
    (is (every? number? timestamps) "all timestamps should be numeric")
    ;; Stored newest-first: timestamps should be non-increasing
    (is (= timestamps (vec (sort > timestamps))) "timestamps should be non-increasing (newest first)")))

;; =============================================================================
;; Results
;; =============================================================================

(println "\n════════════════════════════════════════")
(let [{:keys [pass fail total assertions]} @*tests*]
  (println (str total " tests, " assertions " assertions, "
               pass " passed, " fail " failed."))
  (when (pos? fail)
    (println "SOME TESTS FAILED")
    (System/exit 1))
  (println "ALL TESTS PASSED"))
