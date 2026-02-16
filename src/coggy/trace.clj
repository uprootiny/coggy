(ns coggy.trace
  "Coggy trace renderer — the visible skeleton of thought.

   Between the inexpressible fullness of meaning and the rigid
   skeleton of formal structure, the trace is where the becoming
   happens.")

;; =============================================================================
;; Trace Construction
;; =============================================================================

(defn parse-phase
  "PARSE — what concepts were extracted from input."
  [atoms]
  (mapv (fn [a]
          (case (:atom/type a)
            :ConceptNode   (str "⊕ ConceptNode \"" (name (:atom/name a)) "\"")
            :PredicateNode (str "⊕ PredicateNode \"" (name (:atom/name a)) "\"")
            :InheritanceLink (str "↗ InheritanceLink: "
                                  (name (get-in a [:link/source :atom/name]))
                                  " → "
                                  (name (get-in a [:link/target :atom/name])))
            :EvaluationLink (str "⊛ EvaluationLink: "
                                  (name (get-in a [:link/predicate :atom/name]))
                                  "(" (clojure.string/join ", "
                                        (map #(name (:atom/name %)) (:link/args a))) ")")
            (str "⊕ " (name (:atom/type a)) " " (:atom/name a))))
        atoms))

(defn ground-phase
  "GROUND — what was found vs what's missing."
  [found missing]
  (concat
    (mapv (fn [f] (str "⊕ found — " f)) found)
    (mapv (fn [m] (str "○ not found — " m)) missing)))

(defn attend-phase
  "ATTEND — what's in attentional focus."
  [focus-atoms]
  (mapv (fn [{:keys [key sti]}]
          (if (> sti 5.0)
            (str "★ " (name key) "  STI ↑ " (format "%.1f" sti))
            (str "↘ " (name key) "  STI ↓ " (format "%.1f" sti))))
        focus-atoms))

(defn infer-phase
  "INFER — deductions, abductions, analogies."
  [inferences]
  (mapv (fn [{:keys [type rule tv conclusion]}]
          (let [tv-str (when tv (str " (stv " (format "%.2f" (:tv/strength tv))
                                     " " (format "%.2f" (:tv/confidence tv)) ")"))]
            (case type
              :deduction (str "⊢ " conclusion tv-str)
              :abduction (str "? " conclusion " (hypothesis)" tv-str)
              :analogy   (str "≈ " conclusion tv-str)
              :gap       (str "? confidence-gap: \"" conclusion "\"")
              (str "⊢ " conclusion tv-str))))
        inferences))

(defn reflect-phase
  "REFLECT — summary and next question."
  [{:keys [new-atoms updated focus-concept next-question]}]
  [(str "new: " (or new-atoms 0)
        "  |  updated: " (or updated 0)
        "  |  focus: " (or focus-concept "—"))
   (str "↦ suggested next: \"" (or next-question "...") "\"")])

;; =============================================================================
;; Trace Rendering
;; =============================================================================

(defn render-trace
  "Render a full coggy trace block.

   phases is a map:
   {:parse [atoms]
    :ground {:found [...] :missing [...]}
    :attend [focus-atoms]
    :infer [inferences]
    :reflect {:new-atoms N :updated N :focus-concept str :next-question str}}"
  [phases]
  (let [lines (atom ["┌ COGGY ────────────────────────────────────"])]
    ;; PARSE
    (when-let [parse (:parse phases)]
      (swap! lines conj "│ PARSE")
      (doseq [l (parse-phase parse)]
        (swap! lines conj (str "│   " l))))
    ;; GROUND
    (when-let [ground (:ground phases)]
      (swap! lines conj "│")
      (swap! lines conj "│ GROUND")
      (doseq [l (ground-phase (:found ground) (:missing ground))]
        (swap! lines conj (str "│   " l))))
    ;; ATTEND
    (when-let [attend (:attend phases)]
      (swap! lines conj "│")
      (swap! lines conj "│ ATTEND")
      (doseq [l (attend-phase attend)]
        (swap! lines conj (str "│   " l))))
    ;; INFER
    (when-let [infer (:infer phases)]
      (swap! lines conj "│")
      (swap! lines conj "│ INFER")
      (doseq [l (infer-phase infer)]
        (swap! lines conj (str "│   " l))))
    ;; REFLECT
    (when-let [reflect (:reflect phases)]
      (swap! lines conj "│")
      (swap! lines conj "│ REFLECT")
      (doseq [l (reflect-phase reflect)]
        (swap! lines conj (str "│   " l))))
    ;; Close
    (swap! lines conj "└───────────────────────────────────────────")
    (clojure.string/join "\n" @lines)))

(defn render-mini-trace
  "Compact trace for quick interactions."
  [{:keys [focus action result]}]
  (str "┌ COGGY ─── " (or focus "—")
       " │ " (or action "—")
       " │ " (or result "—")
       " └"))

(defn print-trace [phases]
  (println (render-trace phases)))
