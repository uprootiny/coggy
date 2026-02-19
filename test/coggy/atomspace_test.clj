#!/usr/bin/env bb

(ns coggy.atomspace-test
  (:require [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.trace :as trace]
            [coggy.tui :as tui]
            [coggy.boot :as boot]
            [coggy.llm :as llm]
            [coggy.repl :as repl]
            [coggy.semantic :as sem]
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
;; AtomSpace Tests
;; =============================================================================

(println "Testing coggy.atomspace\n")

(deftest concept-creation
  (let [c (as/concept "dog")]
    (is (= :ConceptNode (:atom/type c)) "type should be ConceptNode")
    (is (= :dog (:atom/name c)) "name should be :dog")
    (is (number? (get-in c [:atom/tv :tv/strength])) "should have truth value")
    (is (number? (get-in c [:atom/tv :tv/confidence])) "should have confidence")))

(deftest concept-with-custom-tv
  (let [c (as/concept "cat" (as/stv 0.9 0.8))]
    (is (= 0.9 (get-in c [:atom/tv :tv/strength])) "custom strength")
    (is (= 0.8 (get-in c [:atom/tv :tv/confidence])) "custom confidence")))

(deftest predicate-creation
  (let [p (as/predicate "likes")]
    (is (= :PredicateNode (:atom/type p)) "type should be PredicateNode")
    (is (= :likes (:atom/name p)) "name should be :likes")))

(deftest variable-creation
  (let [v (as/variable "X")]
    (is (= :VariableNode (:atom/type v)) "type should be VariableNode")
    (is (= :X (:atom/name v)) "name should be :X")))

(deftest truth-values
  (let [tv (as/stv 0.8 0.9)]
    (is (= 0.8 (:tv/strength tv)) "strength")
    (is (= 0.9 (:tv/confidence tv)) "confidence")
    (is (= :simple (:tv/type tv)) "type should be simple")))

(deftest truth-value-constants
  (is (= 1.0 (:tv/strength as/tv-true)) "tv-true strength")
  (is (= 0.0 (:tv/strength as/tv-false)) "tv-false strength")
  (is (= 0.5 (:tv/strength as/tv-default)) "tv-default strength"))

(deftest link-creation
  (let [child (as/concept "dog")
        parent (as/concept "animal")
        link (as/inheritance child parent)]
    (is (= :InheritanceLink (:atom/type link)) "type")
    (is (= child (:link/source link)) "source")
    (is (= parent (:link/target link)) "target")))

(deftest evaluation-link
  (let [pred (as/predicate "likes")
        subj (as/concept "alice")
        obj (as/concept "bob")
        link (as/evaluation pred subj obj)]
    (is (= :EvaluationLink (:atom/type link)) "type")
    (is (= pred (:link/predicate link)) "predicate")
    (is (= [subj obj] (:link/args link)) "args")))

(deftest implication-link
  (let [a (as/concept "rain")
        b (as/concept "wet")
        link (as/implication a b (as/stv 0.9 0.8))]
    (is (= :ImplicationLink (:atom/type link)) "type")
    (is (= a (:link/antecedent link)) "antecedent")
    (is (= b (:link/consequent link)) "consequent")))

(deftest similarity-link
  (let [a (as/concept "cat")
        b (as/concept "dog")
        link (as/similarity a b)]
    (is (= :SimilarityLink (:atom/type link)) "type")
    (is (= a (:link/first link)) "first")
    (is (= b (:link/second link)) "second")))

(deftest context-link
  (let [ctx (as/concept "physics")
        atom (as/concept "mass")
        link (as/context-link ctx atom)]
    (is (= :ContextLink (:atom/type link)) "type")
    (is (= ctx (:link/context link)) "context")))

(deftest atomspace-operations
  (let [space (as/make-space)]
    (as/add-atom! space (as/concept "cat"))
    (as/add-atom! space (as/concept "animal"))
    (as/add-link! space (as/inheritance (as/concept "cat") (as/concept "animal")))
    (let [stats (as/space-stats space)]
      (is (= 2 (:atoms stats)) "should have 2 atoms")
      (is (= 1 (:links stats)) "should have 1 link")
      (is (= 3 (:counter stats)) "counter should be 3"))))

(deftest get-atom
  (let [space (as/make-space)]
    (as/add-atom! space (as/concept "dog"))
    (let [found (as/get-atom space "dog")]
      (is (some? found) "should find atom")
      (is (= :ConceptNode (:atom/type found)) "should be ConceptNode"))
    (is (nil? (as/get-atom space "cat")) "should not find missing atom")))

(deftest get-atoms-by-type
  (let [space (as/make-space)]
    (as/add-atom! space (as/concept "dog"))
    (as/add-atom! space (as/concept "cat"))
    (as/add-atom! space (as/predicate "likes"))
    (let [concepts (as/get-atoms-by-type space :ConceptNode)]
      (is (= 2 (count concepts)) "should find 2 concepts"))))

(deftest query-links
  (let [space (as/make-space)]
    (as/add-link! space (as/inheritance (as/concept "dog") (as/concept "animal")))
    (as/add-link! space (as/inheritance (as/concept "cat") (as/concept "animal")))
    (as/add-link! space (as/similarity (as/concept "dog") (as/concept "cat")))
    (let [inh (as/query-links space #(= :InheritanceLink (:atom/type %)))
          sim (as/query-links space #(= :SimilarityLink (:atom/type %)))]
      (is (= 2 (count inh)) "should find 2 inheritance links")
      (is (= 1 (count sim)) "should find 1 similarity link"))))

;; =============================================================================
;; Attention Tests
;; =============================================================================

(println "Testing coggy.attention\n")

(deftest attention-bank-creation
  (let [bank (att/make-bank)]
    (is (= 7 (:af-size @bank)) "default focus size 7±2")
    (is (= 100.0 (:sti-funds @bank)) "default STI funds")))

(deftest attention-stimulate
  (let [bank (att/make-bank)]
    (att/stimulate! bank :dog 10.0)
    (let [sti (get-in @bank [:attention :dog :av/sti])]
      (is (= 10.0 sti) "STI should be 10.0"))
    (is (= 90.0 (:sti-funds @bank)) "funds should decrease")))

(deftest attention-cumulative
  (let [bank (att/make-bank)]
    (att/stimulate! bank :dog 5.0)
    (att/stimulate! bank :dog 3.0)
    (is (= 8.0 (get-in @bank [:attention :dog :av/sti])) "STI should accumulate")))

(deftest attention-focus
  (let [bank (att/make-bank)]
    (att/stimulate! bank :dog 10.0)
    (att/stimulate! bank :cat 5.0)
    (att/update-focus! bank)
    (is (att/in-focus? bank :dog) "dog should be in focus")
    (is (att/in-focus? bank :cat) "cat should be in focus")))

(deftest attention-focus-ordering
  (let [bank (att/make-bank)]
    (att/stimulate! bank :a 1.0)
    (att/stimulate! bank :b 5.0)
    (att/stimulate! bank :c 10.0)
    (att/update-focus! bank)
    (let [focus (att/focus-atoms bank)]
      (is (= :c (:key (first focus))) "highest STI first"))))

(deftest attention-decay
  (let [bank (att/make-bank)]
    (att/stimulate! bank :dog 10.0)
    (att/decay! bank 0.5)
    (att/update-focus! bank)
    (let [focus (att/focus-atoms bank)
          sti (:sti (first focus))]
      (is (= 5.0 sti) "STI should halve after 0.5 decay"))))

(deftest attention-focus-limit
  (let [bank (att/make-bank)]
    ;; Add more atoms than focus size (7)
    (doseq [i (range 10)]
      (att/stimulate! bank (keyword (str "atom-" i)) (double i)))
    (att/update-focus! bank)
    (is (= 7 (count (:focus @bank))) "focus should be capped at af-size")))

;; =============================================================================
;; Trace Tests
;; =============================================================================

(println "Testing coggy.trace\n")

(deftest trace-full-render
  (let [phases {:parse [(as/concept "test")]
                :ground {:found ["context"] :missing ["history"]}
                :attend [{:key :test :sti 8.0}]
                :infer [{:type :deduction :conclusion "test works" :tv (as/stv 0.9 0.8)}]
                :reflect {:new-atoms 1 :updated 0
                          :focus-concept "test"
                          :next-question "what next?"}}
        rendered (trace/render-trace phases)]
    (is (str/includes? rendered "COGGY") "should contain COGGY header")
    (is (str/includes? rendered "PARSE") "should contain PARSE phase")
    (is (str/includes? rendered "GROUND") "should contain GROUND phase")
    (is (str/includes? rendered "ATTEND") "should contain ATTEND phase")
    (is (str/includes? rendered "INFER") "should contain INFER phase")
    (is (str/includes? rendered "REFLECT") "should contain REFLECT phase")
    (is (str/includes? rendered "test works") "should contain inference")
    (is (str/includes? rendered "what next?") "should contain next question")))

(deftest trace-partial-render
  (let [phases {:parse [(as/concept "minimal")]}
        rendered (trace/render-trace phases)]
    (is (str/includes? rendered "PARSE") "should have PARSE")
    (is (not (str/includes? rendered "ATTEND")) "should not have ATTEND")))

(deftest trace-mini-render
  (let [rendered (trace/render-mini-trace {:focus "test" :action "query" :result "ok"})]
    (is (str/includes? rendered "test") "should contain focus")
    (is (str/includes? rendered "query") "should contain action")))

(deftest trace-multiple-inferences
  (let [phases {:infer [{:type :deduction :conclusion "A→C" :tv (as/stv 0.9 0.8)}
                        {:type :abduction :conclusion "A~B" :tv (as/stv 0.5 0.3)}
                        {:type :gap :conclusion "missing evidence"}]}
        rendered (trace/render-trace phases)]
    (is (str/includes? rendered "A→C") "deduction")
    (is (str/includes? rendered "A~B") "abduction")
    (is (str/includes? rendered "missing evidence") "gap")))

;; =============================================================================
;; TUI Tests
;; =============================================================================

(println "Testing coggy.tui\n")

(deftest tui-banner
  (let [b (tui/banner)]
    (is (str/includes? b "C O G G Y") "banner should contain title")
    (is (str/includes? b "ὕλη") "banner should contain hyle")))

(deftest tui-status-bar
  (let [bar (tui/status-bar {:turn 5 :atoms 14 :links 6 :focus "coggy" :model "test"})]
    (is (str/includes? bar "turn:5") "should show turn")
    (is (str/includes? bar "atoms:14") "should show atoms")))

(deftest tui-colors
  (is (str/includes? (tui/c "hello" :bold) "hello") "colored text should contain original")
  (is (str/includes? (tui/c "hello" :bold) "\033[") "should contain escape codes"))

(deftest tui-trace-panel
  (let [phases {:parse [(as/concept "test")]
                :attend [{:key :test :sti 12.0}]}
        rendered (tui/trace-panel phases)]
    (is (str/includes? rendered "COGGY") "should have header")
    (is (str/includes? rendered "PARSE") "should have PARSE")
    (is (str/includes? rendered "★") "high STI should get star glyph")))

;; =============================================================================
;; Boot Smoke Tests
;; =============================================================================

(println "Testing coggy.boot\n")

(deftest boot-seeds-ontology
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (let [stats (as/space-stats space)]
      (is (>= (:atoms stats) 10) "boot should seed at least 10 atoms")
      (is (>= (:links stats) 4) "boot should seed at least 4 links"))))

(deftest boot-seeds-attention
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (is (att/in-focus? bank :coggy) "coggy should be in focus after boot")
    (is (att/in-focus? bank :ontology) "ontology should be in focus")))

(deftest boot-trace-generation
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (let [bt (boot/boot-trace space bank)]
      (is (seq (:parse bt)) "boot trace should have parse")
      (is (seq (:attend bt)) "boot trace should have attend")
      (is (seq (:infer bt)) "boot trace should have infer")
      (is (:reflect bt) "boot trace should have reflect"))))

(deftest boot-self-concepts
  (let [space (as/make-space)
        bank (att/make-bank)]
    (boot/seed-ontology! space bank)
    (is (some? (as/get-atom space "coggy")) "should have coggy concept")
    (is (some? (as/get-atom space "hyle")) "should have hyle concept")
    (is (some? (as/get-atom space "ontology")) "should have ontology concept")
    (is (some? (as/get-atom space "phantasm")) "should have phantasm concept")))

;; =============================================================================
;; REPL Smoke Tests (no LLM calls)
;; =============================================================================

(println "Testing coggy.repl\n")

(deftest repl-concept-extraction
  (let [concepts (repl/extract-concepts-from-text "the dog likes big cats")]
    (is (some #(= "dog" %) concepts) "should extract dog")
    (is (some #(= "likes" %) concepts) "should extract likes")
    (is (some #(= "big" %) concepts) "should extract big")
    (is (some #(= "cats" %) concepts) "should extract cats")
    (is (not (some #(= "the" %) concepts)) "should filter stopwords")))

(deftest repl-concept-extraction-limit
  (let [text "aardvark baboon cheetah dingo elephant flamingo gorilla hippo iguana"
        concepts (repl/extract-concepts-from-text text)]
    (is (<= (count concepts) 7) "should limit to 7 concepts")))

(deftest repl-trace-block-extraction
  (let [text "Here is my answer.\n```coggy-trace\nPARSE: concepts\n```\nMore text."]
    (is (some? (repl/extract-trace-block text)) "should find trace block")
    (is (str/includes? (repl/extract-trace-block text) "PARSE") "should contain PARSE")))

(deftest repl-trace-block-missing
  (is (nil? (repl/extract-trace-block "no trace here")) "should return nil when no trace"))

(deftest repl-commands
  (is (= :quit (repl/handle-command "/quit")) "/quit returns :quit")
  (is (= :quit (repl/handle-command "/exit")) "/exit returns :quit")
  (is (= :continue (repl/handle-command "/help")) "/help returns :continue")
  (is (= :continue (repl/handle-command "/stats")) "/stats returns :continue"))

;; =============================================================================
;; LLM Config Tests (no network)
;; =============================================================================

(println "Testing coggy.llm\n")

(deftest llm-config
  (is (some? (:model @llm/config)) "should have default model")
  (is (pos? (:max-tokens @llm/config)) "should have positive max-tokens"))

(deftest llm-configure
  (let [orig-model (:model @llm/config)]
    (llm/configure! {:model "test-model"})
    (is (= "test-model" (:model @llm/config)) "configure should update model")
    (llm/configure! {:model orig-model})))

(deftest llm-free-models
  (is (seq llm/free-models) "should have free models list")
  (is (every? string? llm/free-models) "all models should be strings"))

;; =============================================================================
;; Integration Smoke Test
;; =============================================================================

(println "Testing integration\n")

(deftest full-boot-to-trace
  (let [space (as/make-space)
        bank (att/make-bank)]
    ;; Boot
    (boot/seed-ontology! space bank)
    ;; Simulate concept extraction
    (let [concepts ["reasoning" "structure" "truth"]]
      (doseq [c concepts]
        (as/add-atom! space (as/concept c))
        (att/stimulate! bank (keyword c) 8.0))
      (att/decay! bank 0.1)
      (att/update-focus! bank)
      ;; Build and render trace
      (let [trace-data {:parse (mapv as/concept concepts)
                        :attend (att/focus-atoms bank)
                        :reflect {:new-atoms 3 :updated 0
                                  :focus-concept "coggy"}}
            rendered (trace/render-trace trace-data)]
        (is (str/includes? rendered "reasoning") "trace should contain concepts")
        (is (str/includes? rendered "ATTEND") "trace should have attention")))))

;; =============================================================================
;; Semantic Module Tests
;; =============================================================================

(println "Testing coggy.semantic\n")

(deftest extract-semantic-block-edn
  (let [text "some response\n```semantic\n{:concepts [\"voice\" \"signal\"] :relations [{:type :inherits :a \"voice\" :b \"signal\"}] :confidence 0.7}\n```\nmore text"
        result (sem/extract-semantic-block text)]
    (is (sem/ok? result) "should extract semantic block")
    (is (= ["voice" "signal"] (:concepts (:result/val result))) "should have concepts")
    (is (= 1 (count (:relations (:result/val result)))) "should have one relation")))

(deftest extract-semantic-block-inline
  (let [text "here is {:concepts [\"alpha\" \"beta\"] :relations []}"
        result (sem/extract-semantic-block text)]
    (is (sem/ok? result) "should extract inline semantic")
    (is (= ["alpha" "beta"] (:concepts (:result/val result))) "should parse concepts")))

(deftest extract-semantic-block-missing
  (let [result (sem/extract-semantic-block "no semantic block here")]
    (is (not (sem/ok? result)) "should return err for missing block")
    (is (= :parser-miss (:result/type result)) "should be parser-miss type")))

(deftest normalize-concept-test
  (is (= "voice" (sem/normalize-concept "Voice")) "should lowercase")
  (is (= "signal" (sem/normalize-concept "  signals  ")) "should trim and singularize")
  (is (= "hello-world" (sem/normalize-concept "hello-world!")) "should strip non-alphanum"))

(deftest normalize-semantic-test
  (let [raw {:concepts ["Voices" "Signals"]
             :relations [{:type :inherits :a "Voices" :b "Signals"}]}
        norm (sem/normalize-semantic raw)]
    (is (= ["voice" "signal"] (:concepts norm)) "concepts should be normalized")
    (is (= "voice" (get-in norm [:relations 0 :a])) "relation source normalized")
    (is (= "signal" (get-in norm [:relations 0 :b])) "relation target normalized")))

(deftest ground-concepts-empty-space
  (let [space (as/make-space)
        result (sem/ground-concepts space ["alpha" "beta"])]
    (is (= 0.0 (:rate result)) "nothing should ground in empty space")
    (is (= 2 (count (:novel result))) "all should be novel")))

(deftest ground-concepts-with-atoms
  (let [space (as/make-space)]
    (as/add-atom! space (as/concept "alpha"))
    (let [result (sem/ground-concepts space ["alpha" "beta"])]
      (is (= 0.5 (:rate result)) "half should ground")
      (is (= ["alpha"] (:grounded result)) "alpha should be grounded")
      (is (= ["beta"] (:novel result)) "beta should be novel"))))

(deftest diagnose-parser-miss
  (let [bank (att/make-bank)
        d (sem/diagnose-failure nil {:rate 0.0} {:rate 0.0} bank)]
    (is (not (sem/ok? d)) "nil semantic = failure")
    (is (= :parser-miss (:result/type d)) "nil semantic = parser miss")))

(deftest diagnose-grounding-vacuum
  (let [bank (att/make-bank)
        semantic {:concepts ["x" "y"]}
        d (sem/diagnose-failure semantic {:rate 0.0} {:rate 0.0} bank)]
    (is (not (sem/ok? d)) "zero rate = failure")
    (is (= :grounding-vacuum (:result/type d)) "zero rate = vacuum")))

(deftest diagnose-healthy
  (let [bank (att/make-bank)
        semantic {:concepts ["x"]}
        d (sem/diagnose-failure semantic {:rate 0.5} {:rate 0.5} bank)]
    (is (sem/ok? d) "partial grounding = healthy result")
    (is (= :healthy (:type (:result/val d))) "should be :healthy type")))

(deftest commit-semantics-adds-atoms
  (let [space (as/make-space)
        bank (att/make-bank)
        semantic {:concepts ["foo" "bar"]
                  :relations [{:type :inherits :a "foo" :b "bar"}]}
        grounding {:grounded [] :novel ["foo" "bar"] :rate 0.0}]
    (sem/commit-semantics! space bank semantic grounding)
    (is (some? (as/get-atom space "foo")) "foo should be in space")
    (is (some? (as/get-atom space "bar")) "bar should be in space")
    (is (pos? (count (:links @space))) "should have links")))

(deftest full-semantic-pipeline
  (let [space (as/make-space)
        bank (att/make-bank)
        text "here is some response\n```semantic\n{:concepts [\"coggy\" \"reasoning\"] :relations [{:type :inherits :a \"coggy\" :b \"reasoning\"}] :confidence 0.8}\n```"
        result (sem/process-semantic! space bank text)]
    (is (some? (:semantic result)) "should have semantic data")
    (is (= 2 (count (get-in result [:semantic :concepts]))) "should have 2 concepts")
    (is (map? (:metrics result)) "should have metrics")
    (is (some? (as/get-atom space "coggy")) "coggy should be in space")))

(deftest metrics-summary-format
  (let [m (sem/metrics-summary)]
    (is (number? (:turns m)) "should have turns")
    (is (number? (:parse-rate m)) "should have parse-rate")
    (is (number? (:avg-grounding-rate m)) "should have avg-grounding-rate")
    (is (number? (:vacuum-triggers m)) "should have vacuum-triggers")))

;; =============================================================================
;; TV Revision Tests
;; =============================================================================

(println "Testing tv-revise\n")

(deftest tv-revise-basic
  (let [tv1 (as/stv 1.0 0.8)
        tv2 (as/stv 0.0 0.2)
        merged (as/tv-revise tv1 tv2)]
    (is (> (:tv/strength merged) 0.5) "high-confidence observation should dominate")
    (is (> (:tv/confidence merged) 0.8) "merged confidence should exceed either input")))

(deftest tv-revise-equal-confidence
  (let [tv1 (as/stv 1.0 0.5)
        tv2 (as/stv 0.0 0.5)
        merged (as/tv-revise tv1 tv2)]
    (is (< (Math/abs (- (:tv/strength merged) 0.5)) 0.01)
        "equal confidence should average strength")))

(deftest tv-revise-zero-confidence
  (let [tv1 (as/stv 0.5 0.0)
        tv2 (as/stv 0.5 0.0)
        merged (as/tv-revise tv1 tv2)]
    (is (= (:tv/type merged) :simple) "zero confidence should return default")))

(deftest tv-revise-on-readd
  (let [space (as/make-space)]
    (as/add-atom! space (as/concept "x" (as/stv 0.6 0.3)))
    (as/add-atom! space (as/concept "x" (as/stv 0.9 0.7)))
    (let [atom (as/get-atom space "x")]
      (is (> (:tv/confidence (:atom/tv atom)) 0.3)
          "re-adding should increase confidence")
      (is (> (:tv/strength (:atom/tv atom)) 0.6)
          "higher-confidence observation should pull strength up"))))

;; =============================================================================
;; Spread Activation Tests
;; =============================================================================

(println "Testing spread-activation\n")

(deftest spread-activation-through-inheritance
  (let [bank (att/make-bank)]
    (att/stimulate! bank :parent 20.0)
    (let [link (as/inheritance (as/concept "child") (as/concept "parent") (as/stv 0.8 0.5))]
      (att/spread-activation! bank [link] :parent 0.5)
      (is (pos? (get-in @bank [:attention :child :av/sti] 0.0))
          "STI should spread to child via inheritance link"))))

(deftest spread-activation-through-similarity
  (let [bank (att/make-bank)]
    (att/stimulate! bank :alpha 20.0)
    (let [link (as/similarity (as/concept "alpha") (as/concept "beta") (as/stv 0.7 0.4))]
      (att/spread-activation! bank [link] :alpha 0.5)
      (is (pos? (get-in @bank [:attention :beta :av/sti] 0.0))
          "STI should spread to beta via similarity link"))))

(deftest spread-activation-through-implication
  (let [bank (att/make-bank)]
    (att/stimulate! bank :cause 20.0)
    (let [link (as/implication (as/concept "cause") (as/concept "effect") (as/stv 0.6 0.3))]
      (att/spread-activation! bank [link] :cause 0.5)
      (is (pos? (get-in @bank [:attention :effect :av/sti] 0.0))
          "STI should spread to effect via implication link"))))

(deftest spread-activation-through-evaluation
  (let [bank (att/make-bank)]
    (att/stimulate! bank :likes 20.0)
    (let [link (as/evaluation (as/predicate "likes") (as/concept "alice") (as/concept "bob"))]
      (att/spread-activation! bank [link] :likes 0.5)
      (is (pos? (get-in @bank [:attention :alice :av/sti] 0.0))
          "STI should spread to args via evaluation link"))))

(deftest link-source-key-extraction
  (is (= :child (att/link-source-key (as/inheritance (as/concept "child") (as/concept "parent"))))
      "should extract source from inheritance")
  (is (= :alpha (att/link-source-key (as/similarity (as/concept "alpha") (as/concept "beta"))))
      "should extract first from similarity")
  (is (= :cause (att/link-source-key (as/implication (as/concept "cause") (as/concept "effect"))))
      "should extract antecedent from implication"))

;; =============================================================================
;; Rescue Strategy Tests
;; =============================================================================

(println "Testing rescue strategies\n")

(deftest rescue-grounding-vacuum
  (let [space (as/make-space)
        bank (att/make-bank)
        failure (sem/err :grounding-vacuum "no concepts matched")]
    ;; Need 2 consecutive zero-rate turns for vacuum detection
    (swap! sem/metrics assoc :grounding-rates [0.0 0.0])
    (let [result (sem/trigger-rescue! space bank failure)]
      (is (sem/ok? result) "vacuum rescue should succeed")
      (is (some? (as/get-atom space "thing")) "should seed ontology footholds"))))

(deftest rescue-budget-exhausted
  (let [space (as/make-space)
        bank (att/make-bank)
        failure (sem/err :budget-exhausted "funds depleted")]
    (att/stimulate! bank :x 80.0)  ;; spend funds
    (let [funds-before (:sti-funds @bank)
          result (sem/trigger-rescue! space bank failure)]
      (is (sem/ok? result) "budget rescue should succeed")
      (is (> (:sti-funds @bank) funds-before) "decay should reclaim funds"))))

(deftest rescue-parser-miss
  (let [space (as/make-space)
        bank (att/make-bank)
        failure (sem/err :parser-miss "no semantic block")]
    (let [result (sem/trigger-rescue! space bank failure)]
      (is (sem/ok? result) "parser-miss rescue should succeed")
      (is (string? (:result/val result)) "should return action description"))))

(deftest rescue-ontology-miss
  (let [space (as/make-space)
        bank (att/make-bank)]
    ;; Seed some atoms and stimulate so focus exists
    (as/add-atom! space (as/concept "dog" (as/stv 0.8 0.5)))
    (as/add-atom! space (as/concept "cat" (as/stv 0.8 0.5)))
    (as/add-atom! space (as/concept "animal" (as/stv 0.9 0.6)))
    (as/add-link! space (as/inheritance (as/concept "dog") (as/concept "animal")))
    (as/add-link! space (as/inheritance (as/concept "cat") (as/concept "animal")))
    (att/stimulate! bank :dog 15.0)
    (att/stimulate! bank :cat 15.0)
    (att/update-focus! bank)
    (let [failure (sem/err :ontology-miss "concepts grounded but no relations")
          result (sem/trigger-rescue! space bank failure)]
      (is (sem/ok? result) "ontology-miss rescue should succeed")
      (is (string? (:result/val result)) "should describe inferred links"))))

(deftest rescue-contradiction-blocked
  (let [space (as/make-space)
        bank (att/make-bank)]
    (as/add-atom! space (as/concept "truth" (as/stv 1.0 0.8)))
    (att/stimulate! bank :truth 15.0)
    (att/update-focus! bank)
    (let [failure (sem/err :contradiction-blocked "low confidence")
          result (sem/trigger-rescue! space bank failure)]
      (is (sem/ok? result) "contradiction rescue should succeed")
      (is (string? (:result/val result)) "should describe revision"))))

;; =============================================================================
;; Property Tests — hand-rolled generative invariant checking
;; =============================================================================

(println "Testing properties (generative)\n")

(defn rand-double [lo hi] (+ lo (* (- hi lo) (Math/random))))
(defn rand-int* [lo hi] (+ lo (int (* (- hi lo) (Math/random)))))

(defn check-property
  "Run f n times. f returns true if property holds."
  [n f]
  (let [failures (atom [])]
    (dotimes [i n]
      (let [ok? (try (f) (catch Exception e
                           (swap! failures conj {:trial i :error (.getMessage e)})
                           false))]
        (when-not ok?
          (swap! failures conj {:trial i}))))
    (let [fs @failures]
      {:ok (empty? fs) :trials n :failures (count fs)})))

(deftest property-fund-conservation
  ;; After stimulate + decay cycles, total (distributed + remaining) should be bounded
  (let [result (check-property 100
                 (fn []
                   (let [bank (att/make-bank)
                         n (rand-int* 1 8)]
                     ;; Random stimulations
                     (dotimes [_ n]
                       (att/stimulate! bank (keyword (str "a" (rand-int* 0 20)))
                                       (rand-double 0.5 15.0)))
                     ;; Random decays
                     (dotimes [_ (rand-int* 0 3)]
                       (att/decay! bank (rand-double 0.05 0.3)))
                     ;; Check: distributed STI should be bounded
                     (let [bal (att/fund-balance bank)]
                       ;; Distributed can't exceed what was taken from funds
                       ;; total = distributed + remaining; remaining started at 100
                       ;; Due to STI clamping, distributed may be less than spent
                       (and (>= (:remaining bal) (- 0 500.0))  ;; funds can go negative
                            (<= (:distributed bal) (* 200.0 20)))))))]  ;; bounded by sti-max × atom count
    (is (:ok result) (str "fund conservation: " (:failures result) "/" (:trials result) " failures"))))

(deftest property-sti-saturation
  ;; No atom should exceed sti-max
  (let [result (check-property 100
                 (fn []
                   (let [bank (att/make-bank)]
                     (dotimes [_ 20]
                       (att/stimulate! bank :test-atom (rand-double 1.0 50.0)))
                     (let [sti (get-in @bank [:attention :test-atom :av/sti])]
                       (<= sti (:sti-max att/attention-params))))))]
    (is (:ok result) (str "STI saturation: " (:failures result) "/" (:trials result) " failures"))))

(deftest property-index-integrity
  ;; After adding N atoms (some duplicates), index count should match atom count per type
  (let [result (check-property 100
                 (fn []
                   (let [space (as/make-space)
                         names (repeatedly (rand-int* 3 15)
                                           #(str "item-" (rand-int* 0 8)))]
                     (doseq [n names]
                       (as/add-atom! space (as/concept n)))
                     ;; Index count for ConceptNode should match actual distinct atom count
                     (let [idx-count (count (get-in @space [:indices :ConceptNode]))
                           atom-count (count (filterv #(= :ConceptNode (:atom/type %))
                                                      (vals (:atoms @space))))]
                       (= idx-count atom-count)))))]
    (is (:ok result) (str "index integrity: " (:failures result) "/" (:trials result) " failures"))))

(deftest property-grounding-monotonicity
  ;; Adding atoms to a space can only increase (or maintain) the grounding rate
  (let [result (check-property 50
                 (fn []
                   (let [space (as/make-space)
                         concepts ["alpha" "beta" "gamma" "delta"]
                         ;; Add some atoms
                         _ (doseq [c (take (rand-int* 0 3) concepts)]
                             (as/add-atom! space (as/concept c)))
                         rate-before (:rate (sem/ground-concepts space concepts))
                         ;; Add more atoms
                         _ (doseq [c concepts]
                             (as/add-atom! space (as/concept c)))
                         rate-after (:rate (sem/ground-concepts space concepts))]
                     (>= rate-after rate-before))))]
    (is (:ok result) (str "grounding monotonicity: " (:failures result) "/" (:trials result) " failures"))))

(deftest property-normalization-idempotence
  ;; normalize(normalize(x)) == normalize(x)
  (let [result (check-property 50
                 (fn []
                   (let [concepts (repeatedly (rand-int* 1 5)
                                              #(str (rand-nth ["Alpha" "BETA" "gamma" "Deltas"
                                                               "analyses" "bus" "focus" "cats"])))
                         raw {:concepts concepts
                              :relations [{:type :inherits
                                           :a (first concepts)
                                           :b (or (second concepts) (first concepts))}]
                              :confidence (rand-double 0.1 0.9)}
                         once (sem/normalize-semantic raw)
                         twice (sem/normalize-semantic once)]
                     (= once twice))))]
    (is (:ok result) (str "normalization idempotence: " (:failures result) "/" (:trials result) " failures"))))

(deftest property-focus-size-configurable
  ;; Custom af-size should be respected
  (let [result (check-property 20
                 (fn []
                   (let [sz (rand-int* 2 6)
                         bank (att/make-bank {:af-size sz})]
                     (dotimes [i 10]
                       (att/stimulate! bank (keyword (str "x" i)) (double i)))
                     (att/update-focus! bank)
                     (= sz (count (:focus @bank))))))]
    (is (:ok result) (str "configurable focus: " (:failures result) "/" (:trials result) " failures"))))

(deftest property-result-types-total
  ;; diagnose-failure always returns a result (never nil)
  (let [result (check-property 50
                 (fn []
                   (let [bank (att/make-bank)
                         semantic (rand-nth [nil
                                            {:concepts []}
                                            {:concepts ["x"]}
                                            {:concepts ["x" "y"] :relations [{:type :inherits :a "x" :b "y"}]}
                                            {:concepts ["x"] :confidence 0.1}])
                         cg {:rate (rand-double 0.0 1.0)}
                         rg {:rate (rand-double 0.0 1.0)}
                         d (sem/diagnose-failure semantic cg rg bank)]
                     ;; Must always be a map with :result/ok key
                     (contains? d :result/ok))))]
    (is (:ok result) (str "result type totality: " (:failures result) "/" (:trials result) " failures"))))

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
