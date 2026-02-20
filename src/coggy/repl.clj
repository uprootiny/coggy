(ns coggy.repl
  "Coggy REPL — the interaction surface.

   A phantasm that reconstructs itself each turn.
   Human speaks. Coggy parses into ontology. LLM transforms.
   Trace renders the reasoning wake."
  (:require [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.domain :as domain]
            [coggy.llm :as llm]
            [coggy.trace :as trace]
            [coggy.semantic :as sem]
            [coggy.bench :as bench]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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
         :active-domain nil
         :started-at (System/currentTimeMillis)}))

;; =============================================================================
;; Event Log — structured state transition audit trail
;; =============================================================================

(defonce event-log
  (atom {:events [] :cap 200}))

(defn log-event!
  "Record a typed event with timestamp. Capped at :cap entries."
  [type data]
  (swap! event-log
         (fn [el]
           (update el :events
                   (fn [es]
                     (vec (take-last (:cap el)
                                     (conj es {:type type
                                               :at (System/currentTimeMillis)
                                               :data data}))))))))

(defn space [] (:space @session))
(defn bank [] (:bank @session))

(defn recent-events
  "Return the last N events from the event log."
  [n]
  (take-last n (:events @event-log)))

(defn make-system
  "Create an isolated system map. For dependency injection and testing."
  []
  {:space (as/make-space)
   :bank (att/make-bank)
   :session (atom {:history [] :turn 0 :concepts-seen #{}
                   :active-domain nil
                   :started-at (System/currentTimeMillis)})
   :event-log (atom {:events [] :cap 200})})

(defn http-get-json
  "Small helper for local status probes without extra deps."
  [url]
  (try
    (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. url))]
      (.setRequestMethod conn "GET")
      (.setConnectTimeout conn 1500)
      (.setReadTimeout conn 1500)
      (let [code (.getResponseCode conn)]
        {:ok (= 200 code) :status code}))
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn connect-status
  "OpenRouter + Hyle connectivity summary."
  []
  (let [or-report (llm/doctor :json? false :silent? true)
        hyle-port (or (System/getenv "HYLE_PORT")
                      (System/getProperty "HYLE_PORT")
                      "8420")
        hyle (http-get-json (str "http://localhost:" hyle-port "/health"))]
    {:openrouter {:ok (get-in or-report [:auth :ok])
                  :status (get-in or-report [:auth :status])
                  :hint (get-in or-report [:auth :hint])
                  :model (:configured-model or-report)}
     :hyle {:ok (:ok hyle)
            :status (:status hyle)
            :port hyle-port
            :error (:error hyle)}}))

(defn activate-domain!
  "Seed and activate a domain pack."
  [domain-id]
  (let [result (domain/seed-domain! (space) (bank) domain-id)]
    (when (:ok result)
      (swap! session assoc :active-domain (:domain result)))
    result))

(def default-snapshot-path "state/session.edn")
(def default-snapshot-dir "state/snapshots")

(defn snapshot-stamp []
  (str (System/currentTimeMillis)))

(defn snapshot-version-path []
  (str default-snapshot-dir "/session-" (snapshot-stamp) ".edn"))

(defn snapshot-state
  "Collect a persistable runtime snapshot."
  []
  (let [s @session]
    {:session (dissoc s :space :bank)
     :space @(space)
     :bank @(bank)
     :semantic-metrics (sem/metrics-state)
     :llm {:config (select-keys @llm/config [:model :max-tokens :temperature :site-url :site-name])
           :ledger (llm/ledger-state)}
     :event-log (:events @event-log)
     :at (System/currentTimeMillis)}))

(defn restore-state!
  "Restore runtime snapshot into the live session."
  [snap]
  (let [snap (or snap {})
        s (:session snap)
        sp (:space snap)
        bk (:bank snap)]
    (when (map? sp)
      ;; Migrate old :links vector to :link-map if needed
      (let [migrated (if (and (contains? sp :links) (not (contains? sp :link-map)))
                       (let [lm (reduce (fn [m l]
                                          (assoc m (as/link-key l) l))
                                        {} (:links sp))]
                         (-> sp (dissoc :links) (assoc :link-map lm)))
                       sp)]
        (reset! (space) migrated)))
    (when (map? bk) (reset! (bank) bk))
    (when (map? s)
      (swap! session merge s))
    (when-let [sm (:semantic-metrics snap)]
      (sem/restore-metrics! sm))
    (when-let [cfg (get-in snap [:llm :config])]
      (llm/configure! cfg))
    (when-let [ledger (get-in snap [:llm :ledger])]
      (llm/restore-ledger! ledger))
    (when-let [events (:event-log snap)]
      (swap! event-log assoc :events (vec events)))
    {:ok true
     :turn (:turn @session)
     :atoms (count (:atoms @(space)))
     :links (count (:link-map @(space)))}))

(defn dump-state!
  "Write snapshot to disk (EDN)."
  [& [path]]
  (let [p (or path default-snapshot-path)
        snap (snapshot-state)]
    (io/make-parents p)
    (spit p (pr-str snap))
    {:ok true :path p :bytes (count (pr-str snap))}))

(defn dump-state-versioned!
  "Write both the rolling snapshot and a versioned snapshot."
  []
  (let [latest (dump-state! default-snapshot-path)
        versioned (dump-state! (snapshot-version-path))]
    {:ok (and (:ok latest) (:ok versioned))
     :latest latest
     :versioned versioned}))

(defn load-state!
  "Load snapshot from disk (EDN) into runtime."
  [& [path]]
  (let [p (or path default-snapshot-path)
        f (io/file p)]
    (if (.exists f)
      (try
        (let [snap (edn/read-string (slurp f))
              out (restore-state! snap)]
          (assoc out :path p))
        (catch Exception e
          {:ok false
           :path p
           :error (str "snapshot parse/load failed: " (.getMessage e))}))
      {:ok false :error (str "snapshot not found: " p)})))

(defn list-snapshots
  "List available versioned snapshots in descending recency."
  []
  (let [dir (io/file default-snapshot-dir)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".edn"))
           (map (fn [f]
                  {:path (.getPath f)
                   :name (.getName f)
                   :bytes (.length f)
                   :modified (.lastModified f)}))
           (sort-by :modified >)
           vec)
      [])))

(defn load-latest-snapshot!
  "Load most recent versioned snapshot."
  []
  (if-let [snaps (seq (list-snapshots))]
    (or (some (fn [s]
                (let [r (load-state! (:path s))]
                  (when (:ok r) r)))
              snaps)
        {:ok false :error "no valid snapshots found"})
    {:ok false :error "no snapshots found"}))

;; =============================================================================
;; System Prompt — Ontological Seed
;; =============================================================================

(def system-prompt
  "You are Coggy — an ontological reasoning harness.

RESPONSE FORMAT (strict):
1. First: your human-readable answer (1-4 sentences, concise)
2. Then: a blank line
3. Then: your trace block in EXACTLY this format:

```coggy-trace
PARSE: concept1, concept2, concept3
GROUND: found: X, Y | new: Z
ATTEND: focus1 (high), focus2 (fading)
INFER: X is-a Y (stv 0.8 0.7), Z causes W (stv 0.6 0.4)
REFLECT: summary | next: suggested question
```

RULES:
- Answer FIRST, trace LAST. Never mix them.
- Keep trace compact: 1 line per phase, max 7 concepts.
- Use OpenCog atom types: ConceptNode, PredicateNode, InheritanceLink, EvaluationLink, ImplicationLink, SimilarityLink.
- Truth values: (stv strength confidence) where 1.0=certain, 0.0=false.
- Be concise. Structures over paragraphs. Honest about uncertainty.")

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
  "Process one turn of interaction. Returns response map.
   Uses the semantic pipeline for grounding and vacuum detection."
  [user-input]
  (let [turn (inc (:turn @session))
        _ (swap! session assoc :turn turn)
        _ (log-event! :turn-start {:turn turn :input (subs user-input 0 (min 120 (count user-input)))})

        ;; Pre-extract concepts from user input (for seeding before LLM call)
        concepts (extract-concepts-from-text user-input)
        new-concepts (remove (:concepts-seen @session) concepts)
        space (space)
        bank (bank)

        ;; Seed new concepts into AtomSpace so LLM response can ground against them
        _ (doseq [c new-concepts]
            (as/add-atom! space (as/concept c))
            (att/stimulate! bank (keyword c) 10.0))

        ;; Stimulate returning concepts
        _ (doseq [c (filter (:concepts-seen @session) concepts)]
            (att/stimulate! bank (keyword c) 5.0))

        ;; Track seen concepts
        _ (swap! session update :concepts-seen into concepts)

        ;; Build message history
        _ (swap! session update :history conj {:role "user" :content user-input})

        ;; Build augmented input with attentional focus
        focus (att/focus-atoms bank)
        focus-context (when (seq focus)
                        (str "\n[Current attentional focus: "
                             (str/join ", " (map #(name (:key %)) (take 4 focus)))
                             "]"))
        augmented-input (str user-input (or focus-context ""))

        ;; Build system prompt — add semantic suffix if grounding is weak
        domain-prompt (when-let [d (:active-domain @session)]
                        (get-in (domain/get-domain d) [:prompt]))
        effective-system (if (sem/should-add-suffix?)
                           (str system-prompt
                                (when domain-prompt (str "\n\n" domain-prompt))
                                sem/semantic-suffix)
                           (str system-prompt
                                (when domain-prompt (str "\n\n" domain-prompt))))

        ;; Call LLM
        resp (try
               (llm/converse
                 (conj (vec (take-last 6 (:history @session)))
                       {:role "user" :content augmented-input})
                 :system effective-system)
               (catch Exception e
                 (let [d (ex-data e)]
                   {:ok false
                    :error (.getMessage e)
                    :hint (:hint d)
                    :status (:status d)})))

        _ (log-event! :llm-response {:ok (:ok resp) :model (:model resp)
                                       :status (:status resp)})

        content (if (:ok resp)
                  (:content resp)
                  (str "⚠ LLM error: " (:error resp)
                       (when (:status resp) (str " [status " (:status resp) "]"))
                       (when (:hint resp) (str "\nfix: " (:hint resp)))))

        ;; Record assistant response
        _ (swap! session update :history conj {:role "assistant" :content content})

        ;; Run semantic pipeline — extract, ground, commit, rescue
        sem-result (sem/process-semantic! space bank content)
        _ (log-event! :semantic-commit {:concepts (get-in sem-result [:semantic :concepts])
                                         :grounding-rate (get-in sem-result [:grounding :concepts :rate])
                                         :diagnosis-type (when-let [d (:diagnosis sem-result)]
                                                           (if (sem/ok? d) :healthy (:result/type d)))})
        sem-data (:semantic sem-result)
        concept-ground (get-in sem-result [:grounding :concepts])
        diagnosis (:diagnosis sem-result)
        rescue (:rescue sem-result)

        ;; Build trace from semantic pipeline + coggy-trace block
        trace-data {:parse (if (seq (:concepts sem-data))
                             (mapv #(as/concept %) (:concepts sem-data))
                             (mapv #(as/concept %) new-concepts))
                    :ground {:found (or (:grounded concept-ground) [])
                             :missing (or (:novel concept-ground)
                                         (if (seq new-concepts)
                                           (mapv str new-concepts)
                                           []))
                             :rate (:rate concept-ground)}
                    :attend focus
                    :infer (cond
                             ;; Semantic block had structured data
                             (seq (:relations sem-data))
                             (mapv (fn [r]
                                     {:type :deduction
                                      :conclusion (str (:type r) ": " (:a r) " → " (:b r))
                                      :tv (as/stv 0.7 0.5)})
                                   (:relations sem-data))

                             ;; Coggy-trace block present
                             (extract-trace-block content)
                             [{:type :deduction
                               :conclusion (first (str/split-lines (extract-trace-block content)))
                               :tv (as/stv 0.7 0.5)}]

                             ;; Neither — gap
                             :else
                             [{:type :gap
                               :conclusion "no structured trace from LLM"}])
                    :reflect {:new-atoms (count (:novel concept-ground))
                              :updated (count (:grounded concept-ground))
                              :grounding-rate (:rate concept-ground)
                              :focus-concept (when (seq focus) (name (:key (first focus))))
                              :diagnosis (when (and diagnosis (not (sem/ok? diagnosis)))
                                       (:result/type diagnosis))
                              :rescue (when rescue
                                        (if (sem/ok? rescue)
                                          (:result/val rescue)
                                          (:result/reason rescue)))
                              :next-question nil}}

        ;; Strip trace blocks from display content (multiple formats LLMs emit)
        display-content (-> content
                            ;; Fenced blocks
                            (str/replace #"(?s)```coggy-trace[\s\S]*?```" "")
                            (str/replace #"(?s)```semantic[\s\S]*?```" "")
                            (str/replace #"(?s)```json[\s\S]*?```" "")
                            ;; Unfenced coggy-trace (everything after it)
                            (str/replace #"(?s)coggy-trace\s*\n?PARSE:[\s\S]*" "")
                            ;; Standalone trace lines at start of line
                            (str/replace #"(?m)^(PARSE|GROUND|ATTEND|INFER|REFLECT)\s*:.*$" "")
                            ;; Inline PARSE: after answer text (grab from PARSE: to end)
                            (str/replace #"(?s)\nPARSE\s*:[\s\S]*" "")
                            ;; Cleanup: collapse multiple blank lines
                            (str/replace #"\n{3,}" "\n\n"))]

    ;; Wire evidence log so haywire detection works
    (bench/log-evidence!
     (str "turn-" turn)
     (if (sem/ok? diagnosis) "healthy" (name (or (:result/type diagnosis) :unknown)))
     (str "grounding-rate:" (format "%.2f" (double (or (:rate concept-ground) 0.0))))
     (if (:ok resp) "llm-ok" (str "llm-fail:" (:status resp))))

    {:content (str/trim display-content)
     :trace trace-data
     :turn turn
     :usage (:usage resp)
     :llm {:ok (:ok resp)
           :model (:model resp)
           :status (:status resp)
           :hint (:hint resp)
           :attempts (:attempts resp)
           :error (when-not (:ok resp) (:error resp))}
     :stats (as/space-stats space)
     :domain (:active-domain @session)
     :semantic sem-result}))

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
                    (println "  /connect  — OpenRouter + Hyle integration status")
                    (println "  /domains  — list domain packs")
                    (println "  /domain   — activate domain pack")
                    (println "  /dump     — dump runtime snapshot")
                    (println "  /dumpv    — dump versioned snapshot")
                    (println "  /load     — load runtime snapshot")
                    (println "  /loadv    — load latest versioned snapshot")
                    (println "  /snaps    — list versioned snapshots")
                    (println "  /atoms    — list all atoms")
                    (println "  /metrics  — semantic grounding health")
                    (println "  /smoke    — run smoke check battery")
                    (println "  /haywire  — detect repeated no-op cycles")
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
      "/connect" (do (let [s (connect-status)]
                       (println "  OpenRouter:")
                       (println (str "    ok: " (get-in s [:openrouter :ok])
                                     "  status: " (get-in s [:openrouter :status])
                                     "  model: " (get-in s [:openrouter :model])))
                       (when-let [h (get-in s [:openrouter :hint])]
                         (println (str "    hint: " h)))
                       (println "  Hyle:")
                       (println (str "    ok: " (get-in s [:hyle :ok])
                                     "  status: " (get-in s [:hyle :status])
                                     "  port: " (get-in s [:hyle :port])))
                       (when-let [e (get-in s [:hyle :error])]
                         (println (str "    error: " e))))
                     :continue)
      "/domains" (do (println (str "  domains: " (str/join ", " (domain/available-domains))))
                     (println (str "  active: " (or (:active-domain @session) "none")))
                     :continue)
      "/domain"  (do (if arg
                       (let [r (activate-domain! arg)]
                         (if (:ok r)
                           (do
                             (println (str "  domain → " (:domain r) " (" (:name r) ")"))
                             (println (str "  added: " (:added-atoms r) " atoms, " (:added-links r) " links"))
                             (println (str "  strategies: " (str/join " | " (:strategies r)))))
                           (println (str "  " (:error r) " (available: " (str/join ", " (:available r)) ")"))))
                       (println (str "  active domain: " (or (:active-domain @session) "none"))))
                     :continue)
      "/dump" (do (let [r (dump-state! arg)]
                    (println (str "  dump: " (if (:ok r) "ok" "fail")
                                  "  " (or (:path r) "")
                                  (when (:bytes r) (str " (" (:bytes r) " bytes)"))))
                    :continue))
      "/dumpv" (do (let [r (dump-state-versioned!)
                         v (:versioned r)]
                     (println (str "  dumpv: " (if (:ok r) "ok" "fail")
                                   "  " (or (:path v) "")))
                     :continue))
      "/load" (do (let [r (load-state! arg)]
                    (if (:ok r)
                      (println (str "  load: ok  turn " (:turn r) "  atoms " (:atoms r) "  links " (:links r)))
                      (println (str "  load: fail  " (:error r))))
                    :continue))
      "/loadv" (do (let [r (load-latest-snapshot!)]
                     (if (:ok r)
                       (println (str "  loadv: ok  turn " (:turn r) "  atoms " (:atoms r) "  links " (:links r)
                                     "  from " (:path r)))
                       (println (str "  loadv: fail  " (:error r))))
                     :continue))
      "/snaps" (do (let [ss (list-snapshots)]
                     (if (seq ss)
                       (do
                         (println (str "  snapshots: " (count ss)))
                         (doseq [s (take 10 ss)]
                           (println (str "    " (:name s) "  " (:bytes s) " bytes"))))
                       (println "  snapshots: none")))
                   :continue)
      "/atoms"  (do (doseq [[k v] (:atoms @(space))]
                      (println (str "  " (name (:atom/type v)) " " (name k)
                                    " (stv " (format "%.1f" (get-in v [:atom/tv :tv/strength]))
                                    " " (format "%.1f" (get-in v [:atom/tv :tv/confidence])) ")")))
                    :continue)
      "/metrics" (do (let [m (sem/metrics-summary)]
                       (println (str "  turns: " (:turns m)))
                       (println (str "  parse-rate: " (format "%.1f%%" (* 100 (:parse-rate m)))))
                       (println (str "  avg-grounding: " (format "%.1f%%" (* 100 (:avg-grounding-rate m)))))
                       (println (str "  avg-relations: " (format "%.1f%%" (* 100 (:avg-relation-rate m)))))
                       (println (str "  vacuum-triggers: " (:vacuum-triggers m)))
                       (when-let [f (:last-failure m)]
                         (println (str "  last-failure: " (:type f) " at turn " (:turn f)))))
                     :continue)
      "/smoke"   (do (let [checks (bench/smoke-check (space) (bank))
                           summary (bench/smoke-summary checks)]
                       (println (str "  smoke: " (:passed summary) "/" (:total summary)
                                     " (score " (format "%.0f%%" (* 100 (:score summary))) ")"))
                       (doseq [b (:blockers summary)]
                         (println (str "  BLOCKER: " (name (:check b))))))
                     :continue)
      "/trace-quality" (do (let [hist (:history @session)]
                             (if (empty? hist)
                               (println "  no turns yet")
                               (println "  trace quality scoring available after turns")))
                           :continue)
      "/haywire" (do (let [h (bench/detect-haywire)]
                       (if (:haywire? h)
                         (println (str "  HAYWIRE: " (:reason h)
                                       " (" (:repeated-actions h) " repeated)"))
                         (println "  no haywire detected")))
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
        (let [{:keys [content trace turn stats semantic]} (process-turn! input)
              grate (get-in semantic [:grounding :concepts :rate])]
          (println "")
          (println content)
          (println "")
          (trace/print-trace trace)
          (println (str "  [turn " turn
                        " | atoms " (:atoms stats)
                        " | links " (:links stats)
                        (when grate (str " | ground " (format "%.0f%%" (* 100 grate))))
                        (when-let [d (get-in trace [:reflect :diagnosis])]
                          (str " | " (name d)))
                        "]"))
          (println "")
          (recur))))))
