(ns coggy.ontology
  "Ad-hoc ontology persistence: capture grounded subsets, store, and reload."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coggy.atomspace :as as]
            [coggy.attention :as att]))

(def default-dir "state/ontologies")

(defn sanitize-id [s]
  (let [base (or (some-> s str str/trim not-empty) "ontology")]
    (-> base
        str/lower-case
        (str/replace #"[^a-z0-9._-]+" "-")
        (str/replace #"(^-|-$)" ""))))

(defn ontology-path [id]
  (str default-dir "/" (sanitize-id id) ".edn"))

(defn now-ms [] (System/currentTimeMillis))

(defn- top-attention-keys
  [bank max-concepts]
  (->> (:attention @bank)
       (sort-by (comp :av/sti val) >)
       (take max-concepts)
       (map first)))

(defn- select-concept-keys
  [space bank {:keys [concepts include-focus? max-concepts min-sti min-confidence]
               :or {include-focus? true
                    max-concepts 24
                    min-sti 2.0
                    min-confidence 0.45}}]
  (let [atoms (:atoms @space)
        explicit (->> (or concepts []) (map keyword))
        focus (if include-focus?
                (map :key (att/focus-atoms bank))
                [])
        top (top-attention-keys bank max-concepts)
        strict (->> (concat explicit focus top)
                    distinct
                    (filter #(contains? atoms %))
                    (filter (fn [k]
                              (let [a (get atoms k)
                                    conf (double (get-in a [:atom/tv :tv/confidence] 0.1))
                                    sti (double (get-in @bank [:attention k :av/sti] 0.0))]
                                (and (>= conf min-confidence)
                                     (>= sti min-sti)))))
                    (take max-concepts)
                    vec)]
    (if (seq strict)
      strict
      ;; Fallback: if strict grounding is empty, keep a minimally useful slice.
      (let [fallback (->> (keys atoms)
                          (sort-by #(get-in @bank [:attention % :av/sti] 0.0) >)
                          (filter (fn [k]
                                    (>= (double (get-in atoms [k :atom/tv :tv/confidence] 0.1))
                                        (max 0.25 (- min-confidence 0.2)))))
                          (take (min max-concepts 12))
                          vec)]
        fallback))))

(defn capture
  "Capture a reusable, grounded ontology subset from runtime state."
  [space bank {:keys [id title]
               :as opts}]
  (let [keys* (select-concept-keys space bank opts)
        atoms-map (:atoms @space)
        atom-set (set keys*)
        links (->> (vals (:link-map @space))
                   (filter (fn [l]
                             (let [ks (set (att/link-atom-keys l))
                                   overlap (count (filter atom-set ks))]
                               ;; Keep links that materially connect selected concepts.
                               (or (>= overlap 2)
                                   (and (= 1 overlap)
                                        (contains? #{:InheritanceLink :SimilarityLink :ImplicationLink}
                                                   (:atom/type l)))))))
                   vec)]
    {:id (sanitize-id (or id title "ontology"))
     :title (or title "Ad hoc ontology")
     :created-at (now-ms)
     :grounding {:min-sti (:min-sti opts 2.0)
                 :min-confidence (:min-confidence opts 0.45)}
     :concept-count (count keys*)
     :link-count (count links)
     :concept-keys (mapv name keys*)
     :atoms (mapv #(get atoms-map %) keys*)
     :links links}))

(defn save!
  "Save captured ontology to disk."
  [space bank opts]
  (let [ont (capture space bank opts)
        p (ontology-path (:id ont))]
    (io/make-parents p)
    (spit p (pr-str ont))
    {:ok true
     :id (:id ont)
     :path p
     :concept-count (:concept-count ont)
     :link-count (:link-count ont)}))

(defn list-all
  "List saved ontologies with quick metadata."
  []
  (let [dir (io/file default-dir)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".edn"))
           (map (fn [f]
                  (try
                    (let [d (edn/read-string (slurp f))]
                      {:id (:id d)
                       :title (:title d)
                       :path (.getPath f)
                       :modified (.lastModified f)
                       :concept-count (or (:concept-count d) (count (:atoms d)))
                       :link-count (or (:link-count d) (count (:links d)))})
                    (catch Exception e
                      {:id (str/replace (.getName f) #"\.edn$" "")
                       :title "unreadable"
                       :path (.getPath f)
                       :modified (.lastModified f)
                       :error (.getMessage e)}))))
           (sort-by :modified >)
           vec)
      [])))

(defn load!
  "Load a saved ontology by id or explicit path into runtime state."
  [space bank id-or-path]
  (let [candidate (str (or id-or-path ""))
        p (if (and (seq candidate) (.exists (io/file candidate)))
            candidate
            (ontology-path candidate))
        f (io/file p)]
    (if-not (.exists f)
      {:ok false :error (str "ontology not found: " p)}
      (try
        (let [d (edn/read-string (slurp f))
              atoms (or (:atoms d) [])
              links (or (:links d) [])
              before (as/space-stats space)]
          (doseq [a atoms]
            (as/add-atom! space a)
            (when-let [k (:atom/name a)]
              (att/stimulate! bank k 4.0)))
          (doseq [l links] (as/add-link! space l))
          (att/update-focus! bank)
          (let [after (as/space-stats space)]
            {:ok true
             :id (:id d)
             :title (:title d)
             :path p
             :concept-count (count atoms)
             :link-count (count links)
             :added-atoms (max 0 (- (:atoms after) (:atoms before)))
             :added-links (max 0 (- (:links after) (:links before)))}))
        (catch Exception e
          {:ok false :error (.getMessage e) :path p})))))
