(ns coggy.integrations.ibid
  "IBID legal corpus ingestion scaffold.

   Purpose: make legal corpora ingestion explicit, inspectable, and low-friction
   without forcing one provider. v0 supports EDN case bundles."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [coggy.atomspace :as as]
            [coggy.attention :as att]))

(def default-corpus-path "resources/ibid/legal-corpus.edn")

(defonce ingest-state
  (atom {:runs 0
         :last nil
         :last-error nil
         :last-path nil
         :loaded-cases 0}))

(defn status []
  @ingest-state)

(defn- safe-token [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-|-$)" "")))

(defn- read-corpus [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info "corpus file not found" {:path path})))
    (let [raw (edn/read-string (slurp f))]
      (cond
        (vector? raw) raw
        (and (map? raw) (vector? (:cases raw))) (:cases raw)
        :else (throw (ex-info "unsupported corpus shape; expected vector or {:cases [...]}"
                              {:path path :type (type raw)}))))))

(defn- seed-case! [space bank c]
  (let [id (or (:id c) (:case-id c) (str "case-" (safe-token (or (:title c) (hash c)))))
        case-k (str "legal-case/" (safe-token id))
        issue-k (some-> (:issue c) safe-token)
        jur-k (some-> (:jurisdiction c) safe-token)]
    (as/add-atom! space (as/concept case-k (as/stv 0.92 0.75)))
    (as/add-atom! space (as/concept "legal-case" (as/stv 0.9 0.7)))
    (as/add-link! space (as/inheritance (as/concept case-k) (as/concept "legal-case") (as/stv 0.85 0.7)))
    (when (seq jur-k)
      (let [j (str "jurisdiction/" jur-k)]
        (as/add-atom! space (as/concept j (as/stv 0.86 0.7)))
        (as/add-link! space (as/evaluation (as/predicate "in-jurisdiction") (as/concept case-k) (as/concept j)))))
    (when (seq issue-k)
      (let [i (str "issue/" issue-k)]
        (as/add-atom! space (as/concept i (as/stv 0.8 0.6)))
        (as/add-link! space (as/evaluation (as/predicate "addresses-issue") (as/concept case-k) (as/concept i)))))
    (when-let [citations (seq (:citations c))]
      (doseq [cit (take 8 citations)]
        (let [ck (str "citation/" (safe-token cit))]
          (as/add-atom! space (as/concept ck (as/stv 0.76 0.62)))
          (as/add-link! space (as/evaluation (as/predicate "cites") (as/concept case-k) (as/concept ck))))))
    (att/stimulate! bank (keyword case-k) 7.0)
    (when jur-k (att/stimulate! bank (keyword (str "jurisdiction/" jur-k)) 4.0))
    (when issue-k (att/stimulate! bank (keyword (str "issue/" issue-k)) 4.0))
    case-k))

(defn ingest-corpus!
  "Ingest EDN legal corpus into AtomSpace.
   Returns {:ok ... :cases ... :path ... :added-atoms ... :added-links ...}."
  [space bank & [path]]
  (let [p (or path default-corpus-path)
        before (as/space-stats space)]
    (try
      (let [cases (read-corpus p)
            n (count cases)]
        (doseq [c cases]
          (seed-case! space bank c))
        (att/update-focus! bank)
        (let [after (as/space-stats space)
              out {:ok true
                   :path p
                   :cases n
                   :added-atoms (max 0 (- (:atoms after) (:atoms before)))
                   :added-links (max 0 (- (:links after) (:links before)))}]
          (swap! ingest-state
                 (fn [s]
                   (-> s
                       (update :runs inc)
                       (assoc :last out
                              :last-error nil
                              :last-path p
                              :loaded-cases n))))
          out))
      (catch Exception e
        (let [out {:ok false
                   :path p
                   :error (.getMessage e)
                   :data (ex-data e)}]
          (swap! ingest-state
                 (fn [s]
                   (-> s
                       (update :runs inc)
                       (assoc :last out
                              :last-error (:error out)
                              :last-path p))))
          out)))))
