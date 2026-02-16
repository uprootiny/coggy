(ns coggy.boot
  "Boot ritual — Coggy reconstructs itself ab nihilo.

   This is not initialization. It is a ritual of self-assembly.
   The substrate (hyle) receives form (morphe) through the act
   of naming what it is.")

(require '[coggy.atomspace :as as])
(require '[coggy.attention :as att])
(require '[coggy.trace :as trace])

(defn seed-ontology!
  "Seed the AtomSpace with Coggy's self-knowledge."
  [space bank]
  ;; Self-concept
  (as/add-atom! space (as/concept "coggy" (as/stv 1.0 0.9)))
  (as/add-atom! space (as/concept "phantasm" (as/stv 0.8 0.7)))
  (as/add-atom! space (as/concept "ontology" (as/stv 1.0 0.8)))
  (as/add-atom! space (as/concept "reasoning" (as/stv 1.0 0.8)))
  (as/add-atom! space (as/concept "hyle" (as/stv 0.9 0.6)))
  (as/add-atom! space (as/concept "form" (as/stv 0.9 0.6)))
  (as/add-atom! space (as/concept "trace" (as/stv 1.0 0.9)))
  (as/add-atom! space (as/concept "attention" (as/stv 0.9 0.7)))
  (as/add-atom! space (as/concept "human" (as/stv 1.0 0.9)))
  (as/add-atom! space (as/concept "llm" (as/stv 1.0 0.9)))

  ;; Predicates
  (as/add-atom! space (as/predicate "is-a" as/tv-true))
  (as/add-atom! space (as/predicate "has-quality" as/tv-true))
  (as/add-atom! space (as/predicate "transforms" as/tv-true))
  (as/add-atom! space (as/predicate "attends-to" as/tv-true))

  ;; Taxonomic links
  (as/add-link! space (as/inheritance (as/concept "coggy") (as/concept "phantasm") (as/stv 0.9 0.8)))
  (as/add-link! space (as/inheritance (as/concept "coggy") (as/concept "reasoning") (as/stv 1.0 0.9)))
  (as/add-link! space (as/inheritance (as/concept "trace") (as/concept "ontology") (as/stv 0.8 0.7)))
  (as/add-link! space (as/inheritance (as/concept "hyle") (as/concept "form") (as/stv 0.7 0.5)))

  ;; Relational links
  (as/add-link! space (as/evaluation (as/predicate "transforms")
                                     (as/concept "hyle") (as/concept "form")))
  (as/add-link! space (as/evaluation (as/predicate "attends-to")
                                     (as/concept "coggy") (as/concept "human")))

  ;; Seed attention
  (att/stimulate! bank :coggy 20.0)
  (att/stimulate! bank :ontology 15.0)
  (att/stimulate! bank :reasoning 12.0)
  (att/stimulate! bank :human 10.0)
  (att/stimulate! bank :trace 8.0)
  (att/update-focus! bank))

(defn boot-trace
  "Generate the boot trace."
  [space bank]
  {:parse [(as/concept "coggy") (as/concept "hyle") (as/concept "ontology")]
   :ground {:found ["self-concept" "substrate-concept" "reasoning-framework"]
            :missing ["prior-session" "user-context"]}
   :attend (att/focus-atoms bank)
   :infer [{:type :deduction
            :conclusion "coggy exists as reasoning-about-reasoning"
            :tv (as/stv 0.9 0.7)}
           {:type :gap
            :conclusion "no continuity from prior sessions — reconstructed ab nihilo"}]
   :reflect {:new-atoms (count (:atoms @space))
             :updated 0
             :focus-concept "coggy"
             :next-question "what does the human need?"}})

(defn run-boot!
  "Execute the boot ritual. Returns the seeded space and bank."
  [space bank]
  (println "")
  (println "  ┌─ BOOT ────────────────────────────────────┐")
  (println "  │ Coggy reconstructs itself ab nihilo.       │")
  (println "  │ The substrate receives form through naming. │")
  (println "  └────────────────────────────────────────────┘")
  (println "")

  (seed-ontology! space bank)

  (let [bt (boot-trace space bank)]
    (trace/print-trace bt)
    (println "")
    (println (str "  seeded: " (count (:atoms @space)) " atoms, "
                  (count (:links @space)) " links"))
    (println ""))
  {:space space :bank bank})
