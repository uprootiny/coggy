(ns coggy.attention
  "ECAN-lite — Economic Attention Allocation.

   Each atom has STI (Short-Term Importance) and LTI (Long-Term Importance).
   Attention flows toward what's relevant, fades from what's not.

   This is how Coggy decides what to think about.")

;; =============================================================================
;; Attention Values
;; =============================================================================

(defn av
  "Attention value: STI (short-term) and LTI (long-term)."
  [sti lti]
  {:av/sti (double sti)
   :av/lti (double lti)})

(def av-default (av 0.0 0.0))

;; =============================================================================
;; Attention Bank
;; =============================================================================

(defn make-bank
  "Create attention bank tracking STI/LTI for atoms."
  []
  (atom {:attention {}   ;; atom-key → av
         :focus []       ;; ordered by STI descending
         :sti-funds 100.0
         :lti-funds 50.0
         :af-size 7}))  ;; attentional focus size (7±2)

(defn stimulate!
  "Increase STI of an atom. Costs funds."
  [bank atom-key amount]
  (swap! bank
         (fn [b]
           (let [current (get-in b [:attention atom-key] av-default)
                 new-sti (+ (:av/sti current) amount)]
             (-> b
                 (assoc-in [:attention atom-key]
                           (av new-sti (:av/lti current)))
                 (update :sti-funds - amount))))))

(defn decay!
  "Decay all STI by a fraction. Reclaims funds."
  [bank rate]
  (swap! bank
         (fn [b]
           (let [decayed (reduce-kv
                           (fn [m k v]
                             (let [lost (* (:av/sti v) rate)]
                               (assoc m k (av (- (:av/sti v) lost)
                                              (:av/lti v)))))
                           {}
                           (:attention b))
                 reclaimed (reduce + 0 (map #(* (:av/sti %) rate)
                                            (vals (:attention b))))]
             (-> b
                 (assoc :attention decayed)
                 (update :sti-funds + reclaimed))))))

(defn update-focus!
  "Recompute attentional focus — top-N by STI."
  [bank]
  (swap! bank
         (fn [b]
           (let [sorted (->> (:attention b)
                             (sort-by (comp :av/sti val) >)
                             (take (:af-size b))
                             (mapv first))]
             (assoc b :focus sorted)))))

(defn in-focus? [bank atom-key]
  (some #{atom-key} (:focus @bank)))

(defn focus-atoms
  "Return the current attentional focus as atom keys with STI."
  [bank]
  (let [b @bank]
    (mapv (fn [k]
            {:key k
             :sti (get-in b [:attention k :av/sti] 0.0)})
          (:focus b))))

(defn spread-activation!
  "Spread STI from source to connected atoms via links."
  [bank links source-key fraction]
  (let [b @bank
        source-sti (get-in b [:attention source-key :av/sti] 0.0)
        amount (* source-sti fraction)
        targets (count links)]
    (when (pos? targets)
      (let [per-target (/ amount targets)]
        (doseq [link links]
          (let [target (or (:link/target link)
                          (first (:link/args link)))]
            (when-let [tk (:atom/name target)]
              (stimulate! bank tk per-target))))))))
