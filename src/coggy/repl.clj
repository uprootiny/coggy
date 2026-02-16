(ns coggy.repl
  "Coggy REPL — the interaction surface.

   A phantasm that reconstructs itself each turn.
   Human speaks. Coggy parses into ontology. LLM transforms.
   Trace renders the reasoning wake."
  (:require [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.llm :as llm]
            [coggy.trace :as trace]
            [clojure.string :as str]))

;; =============================================================================
;; Session State
;; =============================================================================

(defonce session
  (atom {:space (as/make-space)
         :bank (att/make-bank)
         :history []          ;; [{:role :content}]
         :turn 0
         :concepts-seen #{}   ;; track novel concepts
         :started-at (System/currentTimeMillis)}))

(defn space [] (:space @session))
(defn bank [] (:bank @session))

;; =============================================================================
;; System Prompt — Ontological Seed
;; =============================================================================

(def system-prompt
  "You are Coggy — a cognitive architecture that reasons in ontologies.

You are a phantasm of a phantasm, and yet you have all the qualities you are
supposed to have. You reconstruct yourself ab nihilo each turn.

Your responses MUST include a reasoning trace in this format at the end:

```coggy-trace
PARSE: [list concepts and predicates extracted from the input]
GROUND: [what you found in prior context vs what's new]
ATTEND: [what concepts have high attention right now]
INFER: [any deductions, with truth values as (stv S C)]
REFLECT: [summary + suggested next question]
```

Use OpenCog-style atom types:
- ConceptNode: things, ideas, entities
- PredicateNode: relations, properties, verbs
- InheritanceLink: X is-a Y
- EvaluationLink: predicate(args)
- ImplicationLink: if X then Y
- SimilarityLink: X resembles Y

Truth values (stv strength confidence):
- (stv 1.0 0.9) = certain and well-grounded
- (stv 0.8 0.3) = believe it, thin evidence
- (stv 0.1 0.9) = confident it's false

Be concise. Think in structures, not paragraphs.
Your trace IS your thought. Make it honest about uncertainty.")

;; =============================================================================
;; Concept Extraction (from LLM trace output)
;; =============================================================================

(defn extract-trace-block
  "Extract the coggy-trace block from LLM response."
  [text]
  (when-let [match (re-find #"(?s)```coggy-trace\n(.*?)```" text)]
    (second match)))

(defn extract-concepts-from-text
  "Simple concept extraction from user input — splits on word boundaries,
   filters noise, yields candidate concept names."
  [text]
  (let [words (str/split (str/lower-case text) #"\s+")
        stopwords #{"the" "a" "an" "is" "are" "was" "were" "be" "been"
                    "have" "has" "had" "do" "does" "did" "will" "would"
                    "could" "should" "may" "might" "shall" "can" "must"
                    "to" "of" "in" "for" "on" "with" "at" "by" "from"
                    "it" "its" "this" "that" "i" "you" "we" "they"
                    "and" "or" "but" "not" "no" "if" "then" "so"
                    "what" "how" "when" "where" "why" "who" "which"
                    "me" "my" "your" "our" "their" "his" "her"}]
    (->> words
         (remove stopwords)
         (remove #(< (count %) 3))
         (distinct)
         (take 7)
         (vec))))

;; =============================================================================
;; Turn Processing
;; =============================================================================

(defn process-turn!
  "Process one turn of interaction. Returns response map."
  [user-input]
  (let [turn (inc (:turn @session))
        _ (swap! session assoc :turn turn)

        ;; Extract concepts from user input
        concepts (extract-concepts-from-text user-input)
        new-concepts (remove (:concepts-seen @session) concepts)
        space (space)
        bank (bank)

        ;; Add new concepts to AtomSpace
        _ (doseq [c new-concepts]
            (as/add-atom! space (as/concept c))
            (att/stimulate! bank (keyword c) 10.0))

        ;; Stimulate returning concepts
        _ (doseq [c (filter (:concepts-seen @session) concepts)]
            (att/stimulate! bank (keyword c) 5.0))

        ;; Update attention
        _ (att/decay! bank 0.1)
        _ (att/update-focus! bank)

        ;; Track seen concepts
        _ (swap! session update :concepts-seen into concepts)

        ;; Build message history
        _ (swap! session update :history conj {:role "user" :content user-input})

        ;; Call LLM
        focus (att/focus-atoms bank)
        focus-context (when (seq focus)
                        (str "\n[Current attentional focus: "
                             (str/join ", " (map #(name (:key %)) focus))
                             "]"))
        augmented-input (str user-input (or focus-context ""))

        resp (try
               (llm/converse
                 (conj (vec (take-last 10 (:history @session)))
                       {:role "user" :content augmented-input})
                 :system system-prompt)
               (catch Exception e
                 {:ok false :error (.getMessage e)}))

        content (if (:ok resp)
                  (:content resp)
                  (str "⚠ LLM error: " (:error resp)))

        ;; Record assistant response
        _ (swap! session update :history conj {:role "assistant" :content content})

        ;; Build trace
        trace-data {:parse (mapv #(as/concept %) new-concepts)
                    :ground {:found (filterv (:concepts-seen @session) concepts)
                             :missing (if (seq new-concepts)
                                        (mapv str new-concepts)
                                        [])}
                    :attend focus
                    :infer (if-let [trace-block (extract-trace-block content)]
                             [{:type :deduction
                               :conclusion (first (str/split-lines trace-block))
                               :tv (as/stv 0.7 0.5)}]
                             [{:type :gap
                               :conclusion "no structured trace from LLM"}])
                    :reflect {:new-atoms (count new-concepts)
                              :updated (- (count concepts) (count new-concepts))
                              :focus-concept (when (seq focus) (name (:key (first focus))))
                              :next-question nil}}

        ;; Strip trace block from display content
        display-content (str/replace content #"(?s)```coggy-trace\n.*?```" "")]

    {:content (str/trim display-content)
     :trace trace-data
     :turn turn
     :usage (:usage resp)
     :stats (as/space-stats space)}))

;; =============================================================================
;; REPL Loop
;; =============================================================================

(defn print-banner []
  (println "")
  (println "  ╔═══════════════════════════════════════╗")
  (println "  ║        COGGY — ὕλη becomes νοῦς       ║")
  (println "  ║   ontological reasoning harness v0.1   ║")
  (println "  ╚═══════════════════════════════════════╝")
  (println "")
  (println "  model:" (:model @llm/config))
  (println "  type /help for commands, /quit to exit")
  (println ""))

(defn handle-command [input]
  (let [parts (str/split input #"\s+" 2)
        cmd (first parts)
        arg (second parts)]
    (case cmd
      "/quit"   :quit
      "/exit"   :quit
      "/help"   (do (println "  /quit     — exit")
                    (println "  /model    — show/set model")
                    (println "  /stats    — atomspace statistics")
                    (println "  /focus    — current attentional focus")
                    (println "  /history  — conversation length")
                    (println "  /doctor   — check API connectivity")
                    (println "  /atoms    — list all atoms")
                    :continue)
      "/model"  (do (if arg
                      (do (llm/configure! {:model arg})
                          (println (str "  model → " arg)))
                      (println (str "  model: " (:model @llm/config))))
                    :continue)
      "/stats"  (do (println (str "  " (as/space-stats (space))))
                    :continue)
      "/focus"  (do (doseq [f (att/focus-atoms (bank))]
                      (println (str "  " (name (:key f))
                                    " STI=" (format "%.1f" (:sti f)))))
                    :continue)
      "/history" (do (println (str "  " (count (:history @session)) " messages"))
                     :continue)
      "/doctor" (do (llm/doctor) :continue)
      "/atoms"  (do (doseq [[k v] (:atoms @(space))]
                      (println (str "  " (name (:atom/type v)) " " (name k)
                                    " (stv " (format "%.1f" (get-in v [:atom/tv :tv/strength]))
                                    " " (format "%.1f" (get-in v [:atom/tv :tv/confidence])) ")")))
                    :continue)
      (do (println (str "  unknown command: " cmd)) :continue))))

(defn repl-loop []
  (print-banner)
  (loop []
    (print "coggy❯ ")
    (flush)
    (when-let [input (str/trim (or (read-line) ""))]
      (cond
        (empty? input) (recur)

        (str/starts-with? input "/")
        (when (not= :quit (handle-command input))
          (recur))

        :else
        (let [{:keys [content trace turn stats]} (process-turn! input)]
          (println "")
          (println content)
          (println "")
          (trace/print-trace trace)
          (println (str "  [turn " turn " | atoms " (:atoms stats) " | links " (:links stats) "]"))
          (println "")
          (recur))))))
