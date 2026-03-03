(ns coggy.assess
  "State assessment unroll: write multi-page ontological traces + mindmap artifacts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [coggy.atomspace :as as]
            [coggy.attention :as att]
            [coggy.semantic :as sem]
            [coggy.bench :as bench]))

(def default-root "docs/assessments")

(defn now-ms [] (System/currentTimeMillis))

(defn- stamp [] (str (now-ms)))

(defn- safe-tag [s]
  (-> (or (some-> s str str/trim not-empty) "run")
      str/lower-case
      (str/replace #"[^a-z0-9._-]+" "-")
      (str/replace #"(^-|-$)" "")))

(defn- write-file! [path body]
  (io/make-parents path)
  (spit path body)
  path)

(defn- top-atoms [space bank n]
  (->> (:atoms @space)
       (map (fn [[k a]]
              {:key k
               :type (:atom/type a)
               :strength (double (get-in a [:atom/tv :tv/strength] 0.5))
               :confidence (double (get-in a [:atom/tv :tv/confidence] 0.1))
               :sti (double (get-in @bank [:attention k :av/sti] 0.0))}))
       (sort-by :sti >)
       (take n)))

(defn- overview-md [space bank]
  (let [stats (as/space-stats space)
        m (sem/metrics-summary)
        smoke (bench/smoke-summary (bench/smoke-check space bank))]
    (str "# State Assessment Overview\n\n"
         "- Generated: " (java.time.Instant/ofEpochMilli (now-ms)) "\n"
         "- Atoms: " (:atoms stats) "\n"
         "- Links: " (:links stats) "\n"
         "- Parse rate: " (format "%.1f%%" (* 100 (:parse-rate m))) "\n"
         "- Avg grounding: " (format "%.1f%%" (* 100 (:avg-grounding-rate m))) "\n"
         "- Vacuum triggers: " (:vacuum-triggers m) "\n"
         "- Smoke score: " (format "%.1f%%" (* 100 (:score smoke))) "\n\n"
         "## System Ladder\n\n"
         "1. Scaffold system (CLI/API/WebUI controls)\n"
         "2. Study system (trace + grounding instrumentation)\n"
         "3. Validate system (smoke/haywire/readiness)\n"
         "4. Harness system (domain/ingest adapters)\n"
         "5. Resilience system (snapshots/fallbacks/retries)\n"
         "6. Quiet support system (background health loops)\n"
         "7. Fruition system (reusable ontology packs)\n")))

(defn- traces-md [space bank]
  (let [atoms (top-atoms space bank 120)]
    (str "# Ontological Traces\n\n"
         "| concept | type | sti | stv |\n"
         "|---|---:|---:|---:|\n"
         (apply str
                (for [{:keys [key type sti strength confidence]} atoms]
                  (str "| `" (name key) "` | " (name type) " | "
                       (format "%.2f" sti) " | "
                       (format "%.2f/%.2f" strength confidence) " |\n"))))))

(defn- mindmap-mmd [space bank]
  (let [focus (att/focus-atoms bank)
        top (take 10 focus)]
    (str "mindmap\n"
         "  root((coggy assessment))\n"
         "    runtime\n"
         "      atomspace\n"
         "      attention\n"
         "      semantic-pipeline\n"
         "    focus\n"
         (apply str (for [f top]
                      (str "      " (name (:key f)) " (" (format "%.1f" (:sti f)) ")\n")))
         "    operations\n"
         "      snapshots\n"
         "      fallback-models\n"
         "      ibid-ingest\n")))

(defn- pregraph-edn [space bank]
  (let [focus (set (map :key (att/focus-atoms bank)))
        edges (->> (vals (:link-map @space))
                   (map (fn [l]
                          {:type (:atom/type l)
                           :keys (mapv name (att/link-atom-keys l))}))
                   (filter (fn [e] (some #(contains? focus (keyword %)) (:keys e))))
                   (take 120))]
    {:nodes (mapv name focus)
     :edges edges
     :metrics (sem/metrics-summary)}))

(defn unroll!
  "Write a multi-file assessment bundle."
  [space bank & [{:keys [root tag] :or {root default-root}}]]
  (let [run-id (str (stamp) "-" (safe-tag tag))
        dir (str root "/" run-id)
        p0 (write-file! (str dir "/00-overview.md") (overview-md space bank))
        p1 (write-file! (str dir "/01-ontological-traces.md") (traces-md space bank))
        p2 (write-file! (str dir "/02-mindmap.mmd") (mindmap-mmd space bank))
        p3 (write-file! (str dir "/03-pregraph.edn") (pr-str (pregraph-edn space bank)))]
    {:ok true
     :run-id run-id
     :dir dir
     :files [p0 p1 p2 p3]}))
