(ns coggy.atomspace
  "AtomSpace — hypergraph knowledge store.

   Atoms are the substrate. Links are the relations.
   Truth values are the epistemological stance.

   Hyle: prime matter receives form through naming.
   An atom exists the moment it is asserted.")

;; =============================================================================
;; Truth Values
;; =============================================================================

(defn stv
  "Simple truth value: strength × confidence.
   Strength: how true (0.0–1.0).
   Confidence: how well-grounded (0.0–1.0)."
  [strength confidence]
  {:tv/type :simple
   :tv/strength (double strength)
   :tv/confidence (double confidence)})

(def tv-true (stv 1.0 0.9))
(def tv-default (stv 0.5 0.1))
(def tv-false (stv 0.0 0.9))

;; =============================================================================
;; Atom Types
;; =============================================================================

(defn concept
  "ConceptNode — a thing, idea, entity."
  ([name] (concept name tv-default))
  ([name tv]
   {:atom/type :ConceptNode
    :atom/name (keyword name)
    :atom/tv tv}))

(defn predicate
  "PredicateNode — a relation, property, verb."
  ([name] (predicate name tv-default))
  ([name tv]
   {:atom/type :PredicateNode
    :atom/name (keyword name)
    :atom/tv tv}))

(defn variable
  "VariableNode — unbound slot for pattern matching."
  [name]
  {:atom/type :VariableNode
   :atom/name (keyword name)})

;; =============================================================================
;; Link Types
;; =============================================================================

(defn inheritance
  "InheritanceLink — X is-a Y (taxonomy)."
  ([child parent] (inheritance child parent tv-default))
  ([child parent tv]
   {:atom/type :InheritanceLink
    :link/source child
    :link/target parent
    :atom/tv tv}))

(defn evaluation
  "EvaluationLink — predicate applied to arguments."
  ([pred & args]
   {:atom/type :EvaluationLink
    :link/predicate pred
    :link/args (vec args)
    :atom/tv tv-default}))

(defn implication
  "ImplicationLink — if X then Y."
  ([antecedent consequent] (implication antecedent consequent tv-default))
  ([antecedent consequent tv]
   {:atom/type :ImplicationLink
    :link/antecedent antecedent
    :link/consequent consequent
    :atom/tv tv}))

(defn similarity
  "SimilarityLink — X resembles Y."
  ([a b] (similarity a b tv-default))
  ([a b tv]
   {:atom/type :SimilarityLink
    :link/first a
    :link/second b
    :atom/tv tv}))

(defn context-link
  "ContextLink — scoped assertion (Cyc-style microtheory)."
  [context atom]
  {:atom/type :ContextLink
   :link/context context
   :link/atom atom})

;; =============================================================================
;; AtomSpace — the substrate
;; =============================================================================

(defn make-space
  "Create an empty AtomSpace."
  []
  (atom {:atoms {}      ;; name → atom
         :links []      ;; ordered list of links
         :indices {}    ;; type → [atoms]
         :counter 0}))

(defn atom-key [a]
  (or (:atom/name a)
      (hash a)))

(defn add-atom!
  "Assert an atom into the space. Returns the atom."
  [space a]
  (let [k (atom-key a)]
    (swap! space
           (fn [s]
             (let [existing? (contains? (:atoms s) k)]
               (-> s
                   (assoc-in [:atoms k] a)
                   (cond-> (not existing?)
                     (update-in [:indices (:atom/type a)] (fnil conj []) k))
                   (update :counter inc)))))
    a))

(defn add-link!
  "Assert a link into the space. Returns the link."
  [space link]
  (swap! space
         (fn [s]
           (-> s
               (update :links conj link)
               (update-in [:indices (:atom/type link)] (fnil conj []) (hash link))
               (update :counter inc))))
  link)

(defn get-atom [space name]
  (get-in @space [:atoms (keyword name)]))

(defn get-atoms-by-type [space type]
  (let [keys (get-in @space [:indices type])]
    (mapv #(get-in @space [:atoms %]) keys)))

(defn query-links
  "Find links matching a predicate."
  [space pred]
  (filterv pred (:links @space)))

(defn space-stats [space]
  (let [s @space]
    {:atoms (count (:atoms s))
     :links (count (:links s))
     :types (into {} (map (fn [[k v]] [k (count v)]) (:indices s)))
     :counter (:counter s)}))
