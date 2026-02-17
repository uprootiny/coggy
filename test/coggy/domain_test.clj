#!/usr/bin/env bb

(ns coggy.domain-test
  (:require [coggy.domain :as domain]
            [coggy.atomspace :as as]
            [coggy.attention :as att]
            [clojure.string :as str]))

;; Test runner (same pattern as atomspace_test)
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

(println "Testing coggy.domain\n")

(deftest available-domains-not-empty
  (let [ds (domain/available-domains)]
    (is (seq ds) "should have domains")
    (is (every? string? ds) "all domain ids should be strings")))

(deftest available-domains-contains-known
  (let [ds (set (domain/available-domains))]
    (is (contains? ds "bio") "should have bio")
    (is (contains? ds "forecast") "should have forecast")
    (is (contains? ds "legal") "should have legal")
    (is (contains? ds "ibid-legal") "should have ibid-legal")))

(deftest get-domain-valid
  (let [d (domain/get-domain "legal")]
    (is (some? d) "should find legal domain")
    (is (= "Legal Reasoning" (:name d)) "should have correct name")
    (is (seq (:concepts d)) "should have concepts")
    (is (seq (:relations d)) "should have relations")
    (is (seq (:strategies d)) "should have strategies")
    (is (string? (:prompt d)) "should have prompt")))

(deftest get-domain-case-insensitive
  (is (some? (domain/get-domain "LEGAL")) "should find LEGAL")
  (is (some? (domain/get-domain "Legal")) "should find Legal"))

(deftest get-domain-invalid
  (is (nil? (domain/get-domain "nonexistent")) "should return nil for unknown"))

(deftest domain-brief
  (let [b (domain/domain-brief "forecast")]
    (is (some? b) "should have brief")
    (is (= "forecast" (:id b)) "should have id")
    (is (string? (:name b)) "should have name")
    (is (seq (:strategies b)) "should have strategies")))

(deftest seed-domain-legal
  (let [space (as/make-space)
        bank (att/make-bank)
        result (domain/seed-domain! space bank "legal")]
    (is (:ok result) "should succeed")
    (is (= "legal" (:domain result)) "domain id")
    (is (pos? (:added-atoms result)) "should add atoms")
    (is (pos? (:added-links result)) "should add links")
    (is (seq (:strategies result)) "should return strategies")))

(deftest seed-domain-unknown
  (let [space (as/make-space)
        bank (att/make-bank)
        result (domain/seed-domain! space bank "nosuchdomain")]
    (is (not (:ok result)) "should fail")
    (is (string? (:error result)) "should have error message")
    (is (seq (:available result)) "should list available domains")))

(deftest seed-domain-populates-atomspace
  (let [space (as/make-space)
        bank (att/make-bank)]
    (domain/seed-domain! space bank "bio")
    (is (some? (as/get-atom space "plasmid-design")) "should have seeded concept")
    (is (some? (as/get-atom space "domain/bio")) "should have domain tag")))

(deftest seed-domain-stimulates-attention
  (let [space (as/make-space)
        bank (att/make-bank)]
    (domain/seed-domain! space bank "forecast")
    (att/update-focus! bank)
    (let [focus (att/focus-atoms bank)]
      (is (seq focus) "should have focus atoms after seeding"))))

;; =============================================================================

(println "\n════════════════════════════════════════")
(let [{:keys [pass fail total assertions]} @*tests*]
  (println (str total " tests, " assertions " assertions, "
               pass " passed, " fail " failed."))
  (when (pos? fail)
    (println "SOME TESTS FAILED")
    (System/exit 1))
  (println "ALL TESTS PASSED"))
