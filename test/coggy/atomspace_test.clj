#!/usr/bin/env bb

(ns coggy.atomspace-test
  (:require [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.trace :as trace]))

;; =============================================================================
;; Minimal test runner
;; =============================================================================

(def ^:dynamic *tests* (atom {:pass 0 :fail 0 :total 0}))

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
  (when-not pred
    (throw (ex-info (str "Assertion failed: " msg) {}))))

;; =============================================================================
;; AtomSpace Tests
;; =============================================================================

(println "Testing coggy.atomspace\n")

(deftest concept-creation
  (let [c (as/concept "dog")]
    (is (= :ConceptNode (:atom/type c)) "type should be ConceptNode")
    (is (= :dog (:atom/name c)) "name should be :dog")
    (is (number? (get-in c [:atom/tv :tv/strength])) "should have truth value")))

(deftest predicate-creation
  (let [p (as/predicate "likes")]
    (is (= :PredicateNode (:atom/type p)) "type should be PredicateNode")
    (is (= :likes (:atom/name p)) "name should be :likes")))

(deftest truth-values
  (let [tv (as/stv 0.8 0.9)]
    (is (= 0.8 (:tv/strength tv)) "strength")
    (is (= 0.9 (:tv/confidence tv)) "confidence")))

(deftest atomspace-operations
  (let [space (as/make-space)]
    (as/add-atom! space (as/concept "cat"))
    (as/add-atom! space (as/concept "animal"))
    (as/add-link! space (as/inheritance (as/concept "cat") (as/concept "animal")))
    (let [stats (as/space-stats space)]
      (is (= 2 (:atoms stats)) "should have 2 atoms")
      (is (= 1 (:links stats)) "should have 1 link"))))

(deftest query-links
  (let [space (as/make-space)]
    (as/add-link! space (as/inheritance (as/concept "dog") (as/concept "animal")))
    (as/add-link! space (as/inheritance (as/concept "cat") (as/concept "animal")))
    (let [results (as/query-links space #(= :InheritanceLink (:atom/type %)))]
      (is (= 2 (count results)) "should find 2 inheritance links"))))

;; =============================================================================
;; Attention Tests
;; =============================================================================

(println "Testing coggy.attention\n")

(deftest attention-bank
  (let [bank (att/make-bank)]
    (att/stimulate! bank :dog 10.0)
    (att/stimulate! bank :cat 5.0)
    (att/update-focus! bank)
    (is (att/in-focus? bank :dog) "dog should be in focus")
    (is (att/in-focus? bank :cat) "cat should be in focus")))

(deftest attention-decay
  (let [bank (att/make-bank)]
    (att/stimulate! bank :dog 10.0)
    (att/decay! bank 0.5)
    (att/update-focus! bank)
    (let [focus (att/focus-atoms bank)]
      (is (< (:sti (first focus)) 10.0) "STI should have decayed"))))

;; =============================================================================
;; Trace Tests
;; =============================================================================

(println "Testing coggy.trace\n")

(deftest trace-rendering
  (let [phases {:parse [(as/concept "test")]
                :ground {:found ["context"] :missing ["history"]}
                :attend [{:key :test :sti 8.0}]
                :infer [{:type :deduction :conclusion "test works" :tv (as/stv 0.9 0.8)}]
                :reflect {:new-atoms 1 :updated 0
                          :focus-concept "test"
                          :next-question "what next?"}}
        rendered (trace/render-trace phases)]
    (is (clojure.string/includes? rendered "COGGY") "should contain COGGY header")
    (is (clojure.string/includes? rendered "PARSE") "should contain PARSE phase")
    (is (clojure.string/includes? rendered "REFLECT") "should contain REFLECT phase")))

;; =============================================================================
;; Results
;; =============================================================================

(println "")
(let [{:keys [pass fail total]} @*tests*]
  (println (str total " tests, " pass " passed, " fail " failed."))
  (when (pos? fail)
    (System/exit 1)))
