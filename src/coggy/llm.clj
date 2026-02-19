(ns coggy.llm
  "OpenRouter LLM client.

   Hyle's body: the substrate speaks through borrowed voices.
   Coggy uses LLMs not as oracles but as transformation engines —
   natural language ↔ ontological structure."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def api-url "https://openrouter.ai/api/v1/chat/completions")
(def models-url "https://openrouter.ai/api/v1/models")

(def free-models
  ["openrouter/free"
   "nvidia/nemotron-3-nano-30b-a3b:free"
   "nvidia/nemotron-nano-12b-v2-vl:free"
   "qwen/qwen3-next-80b-a3b-instruct:free"
   "openai/gpt-oss-120b:free"])

(defonce config
  (atom {:api-key nil
         :model (first free-models)
         :max-tokens 2048
         :temperature 0.7
         :site-url "https://hyle.hyperstitious.org"
         :site-name "coggy"}))

(defn configure! [opts]
  (swap! config merge opts))

(defn key-source []
  (cond
    (seq (:api-key @config)) {:source :config :key (:api-key @config)}
    (seq (System/getenv "OPENROUTER_API_KEY")) {:source :env :key (System/getenv "OPENROUTER_API_KEY")}
    (seq (System/getProperty "OPENROUTER_API_KEY")) {:source :sysprop :key (System/getProperty "OPENROUTER_API_KEY")}
    :else {:source :missing :key nil}))

(defn api-key []
  (:key (key-source)))

(defn mask-key [k]
  (if (seq k)
    (str (subs k 0 (min 12 (count k))) "...")
    "MISSING"))

(defn parse-json-safe [s]
  (try (json/parse-string (or s "{}") true)
       (catch Exception _ nil)))

(defn error-hint [{:keys [status error]}]
  (cond
    (nil? (api-key)) "Set OPENROUTER_API_KEY in .env or env, then run `coggy doctor`."
    (= 401 status) "API key rejected. Replace OPENROUTER_API_KEY and retry."
    (= 402 status) "Insufficient balance/rate plan. Use a free model or update OpenRouter credits."
    (= 403 status) "Access denied for this model/key. Try another free model."
    (= 404 status) "Model not found. Set a valid model with `/model` or `coggy doctor`."
    (= 429 status) "Rate-limited. Wait and retry, or switch to a different free model."
    (and (string? error) (str/includes? (str/lower-case error) "operation not permitted")) "Network egress blocked by host/sandbox. Allow outbound HTTPS to openrouter.ai."
    :else "Run `coggy doctor --json` for details and fix hints."))

(defn retryable-status?
  "Statuses that should trigger automatic model fallback."
  [status]
  (contains? #{402 429 503} status))

(defonce model-ledger
  (atom {}))

(defn now-ms []
  (System/currentTimeMillis))

(defn cooldown-ms-for-status [status]
  (cond
    (= 429 status) 120000
    (= 402 status) 300000
    (= 503 status) 45000
    :else 0))

(defn cooldown-remaining-ms [entry now]
  (max 0 (- (long (or (:cooldown-until-ms entry) 0)) now)))

(defn update-model-ledger!
  "Track per-model reliability/latency to guide fallback order."
  [model resp latency-ms]
  (let [now (now-ms)
        status (:status resp)
        ok? (:ok resp)
        quota-hit? (= 429 status)
        budget-hit? (= 402 status)
        cool-ms (cooldown-ms-for-status status)
        out (swap! model-ledger
                   (fn [m]
                     (let [prev (get m model {})
                           attempts (inc (long (or (:attempts prev) 0)))
                           succ (if ok? (inc (long (or (:successes prev) 0))) (long (or (:successes prev) 0)))
                           fail (if ok? (long (or (:failures prev) 0)) (inc (long (or (:failures prev) 0))))
                           prev-avg (double (or (:avg-latency-ms prev) latency-ms))
                           avg (/ (+ (* prev-avg (max 0 (dec attempts))) latency-ms) attempts)
                           until (if (pos? cool-ms)
                                   (max (long (or (:cooldown-until-ms prev) 0)) (+ now cool-ms))
                                   (long (or (:cooldown-until-ms prev) 0)))]
                       (assoc m model
                              (merge prev
                                     {:attempts attempts
                                      :successes succ
                                      :failures fail
                                      :quota-hits (if quota-hit? (inc (long (or (:quota-hits prev) 0))) (long (or (:quota-hits prev) 0)))
                                      :budget-hits (if budget-hit? (inc (long (or (:budget-hits prev) 0))) (long (or (:budget-hits prev) 0)))
                                      :last-status status
                                      :last-error (:error resp)
                                      :last-latency-ms latency-ms
                                      :avg-latency-ms avg
                                      :last-at now
                                      :cooldown-until-ms until})))))
        entry (get out model)
        remaining (cooldown-remaining-ms entry now)]
    {:attempts (:attempts entry)
     :avg-latency-ms (double (or (:avg-latency-ms entry) latency-ms))
     :cooldown-ms remaining
     :quota-hits (:quota-hits entry)
     :budget-hits (:budget-hits entry)}))

(def score-weights
  "Model scoring weights. Extracted for legibility and future tuning/search.
   Lower total score = better candidate."
  {:cooldown-per-s 2.2
   :fail-rate      7.5
   :quota-hits     1.4
   :budget-hits    2.0
   :latency-per-s  1.0
   :sticky-bonus  -0.15})

(defn model-score
  "Lower is better; penalize cooldowns, repeated quota/budget hits, failures, latency."
  [model entry now]
  (let [w score-weights
        fail (double (or (:failures entry) 0))
        succ (double (or (:successes entry) 0))
        attempts (max 1.0 (+ fail succ))
        fail-rate (/ fail attempts)
        quota (double (or (:quota-hits entry) 0))
        budget (double (or (:budget-hits entry) 0))
        latency (/ (double (or (:avg-latency-ms entry) 0)) 1000.0)
        cooldown-s (/ (double (cooldown-remaining-ms entry now)) 1000.0)
        sticky (if (= model (:model @config)) (:sticky-bonus w) 0.0)]
    (+ (* cooldown-s (:cooldown-per-s w))
       (* fail-rate (:fail-rate w))
       (* quota (:quota-hits w))
       (* budget (:budget-hits w))
       (* latency (:latency-per-s w))
       sticky)))

(defn model-candidates
  "Requested model + free fallbacks ranked by live model ledger."
  [requested]
  (let [now (now-ms)
        stats @model-ledger
        cands (vec (distinct (cons requested free-models)))
        available (filter #(zero? (cooldown-remaining-ms (get stats % {}) now)) cands)
        cooling (remove (set available) cands)
        rank (fn [xs] (sort-by #(model-score % (get stats % {}) now) xs))]
    (vec (concat (rank available) (rank cooling)))))

(defn model-health-report
  "Inspectable model health snapshot for UI/API."
  []
  (let [now (now-ms)
        configured (:model @config)
        known (vec (distinct (concat [configured] free-models (keys @model-ledger))))
        rows (->> known
                  (map (fn [m]
                         (let [e (get @model-ledger m {})
                               attempts (long (or (:attempts e) 0))
                               succ (long (or (:successes e) 0))
                               fail (long (or (:failures e) 0))]
                           {:model m
                            :attempts attempts
                            :successes succ
                            :failures fail
                            :fail-rate (if (pos? attempts) (double (/ fail attempts)) 0.0)
                            :avg-latency-ms (double (or (:avg-latency-ms e) 0.0))
                            :last-latency-ms (double (or (:last-latency-ms e) 0.0))
                            :last-status (:last-status e)
                            :quota-hits (long (or (:quota-hits e) 0))
                            :budget-hits (long (or (:budget-hits e) 0))
                            :cooldown-ms (cooldown-remaining-ms e now)
                            :score (model-score m e now)})))
                  (sort-by :score)
                  vec)]
    {:at now
     :configured configured
     :ranked (mapv :model rows)
     :models rows}))

(defn ledger-state
  "Raw ledger map for persistence."
  []
  @model-ledger)

(defn restore-ledger!
  "Restore raw ledger map from persisted state."
  [m]
  (reset! model-ledger (or m {})))

(defonce pacing-state
  (atom {:last-call-ms 0}))

(defn throttle-before-call!
  "Simple pacing so retries become staggered short bursts, not hammering."
  [min-gap-ms]
  (let [now (now-ms)
        last-ms (long (or (:last-call-ms @pacing-state) 0))
        wait-ms (max 0 (- min-gap-ms (- now last-ms)))]
    (when (pos? wait-ms)
      (Thread/sleep wait-ms))
    (swap! pacing-state assoc :last-call-ms (now-ms))))

(defn compact-messages
  "Short fallback payload for retry attempts under quota pressure."
  [messages]
  (->> (take-last 4 messages)
       (mapv (fn [m]
               (let [c (str (:content m ""))]
                 (assoc m :content (subs c 0 (min 420 (count c)))))))))

;; =============================================================================
;; API Calls
;; =============================================================================

(defn- chat-once
  [messages {:keys [model system key max-tokens]}]
  (let [t0 (now-ms)
        _ (throttle-before-call! 950)
        body {:model model
              :messages (if system
                          (into [{:role "system" :content system}] messages)
                          (vec messages))
              :max_tokens (or max-tokens (:max-tokens @config))
              :temperature (:temperature @config)}
        base {:model model
              :key-source (:source (key-source))
              :request {:message-count (count messages)
                        :system? (boolean system)}}]
    (try
      (let [resp (http/post api-url
                            {:headers {"Authorization" (str "Bearer " key)
                                       "Content-Type" "application/json"
                                       "HTTP-Referer" (:site-url @config)
                                       "X-Title" (:site-name @config)}
                             :body (json/generate-string body)
                             :timeout 30000
                             :throw false})
            parsed (parse-json-safe (:body resp))
            err (or (get-in parsed [:error :message]) (:body resp))]
        (if (= 200 (:status resp))
          (let [out {:ok true
                     :content (get-in parsed [:choices 0 :message :content])
                     :model model
                     :usage (:usage parsed)
                     :raw parsed
                     :diagnostics base}
                latency (- (now-ms) t0)]
            (assoc out :telemetry (update-model-ledger! model out latency)))
          (let [out {:ok false
                     :status (:status resp)
                     :error err
                     :hint (error-hint {:status (:status resp) :error err})
                     :raw (or parsed {:body (:body resp)})
                     :diagnostics base}
                latency (- (now-ms) t0)]
            (assoc out :telemetry (update-model-ledger! model out latency)))))
      (catch Exception e
        (let [msg (.getMessage e)]
          (let [out {:ok false
                     :status :exception
                     :error msg
                     :hint (error-hint {:error msg})
                     :diagnostics base}
                latency (- (now-ms) t0)]
            (assoc out :telemetry (update-model-ledger! model out latency))))))))

(defn chat
  "Send a chat completion request. Returns the full response map.
   messages: [{:role \"user\" :content \"...\"}]"
  [messages & {:keys [model system allow-fallback? max-attempts] :as _opts}]
  (let [key (api-key)
        requested (or model (:model @config))
        candidates (if (false? allow-fallback?)
                     [requested]
                     (take (or max-attempts 4) (model-candidates requested)))]
    (if-not key
      {:ok false
       :status :missing-key
       :error "No API key"
       :hint (error-hint {})
       :diagnostics {:model requested
                     :key-source (:source (key-source))
                     :request {:message-count (count messages)
                               :system? (boolean system)}}}
      (loop [[m & more] candidates
             attempts []
             short-mode? false]
        (let [payload (if short-mode? (compact-messages messages) messages)
              resp (chat-once payload {:model m
                                       :system system
                                       :key key
                                       :max-tokens (if short-mode?
                                                     (min 640 (:max-tokens @config))
                                                     (:max-tokens @config))})
              attempt {:model m
                       :ok (:ok resp)
                       :status (:status resp)
                       :error (:error resp)
                       :short-mode short-mode?
                       :latency-ms (get-in resp [:telemetry :avg-latency-ms])}
              attempts* (conj attempts attempt)]
          (if (:ok resp)
            (do
              ;; Sticky model update when fallback succeeded and caller did not force model.
              (when (and (nil? model) (not= m requested))
                (swap! config assoc :model m))
              (assoc resp :attempts attempts*))
            (if (and (seq more) (retryable-status? (:status resp)))
              (do
                (Thread/sleep 700)
                (recur more attempts* true))
              (assoc resp
                     :attempts attempts*
                     :hint (if (> (count attempts*) 1)
                             (str (:hint resp)
                                  " Tried: "
                                  (str/join ", " (map :model attempts*)))
                             (:hint resp))))))))))

(defn ask
  "Simple question → answer. Returns content string or throws."
  [question & {:keys [system model]}]
  (let [resp (chat [{:role "user" :content question}]
                   :system system
                   :model model)]
    (if (:ok resp)
      (:content resp)
      (throw (ex-info (str "LLM error: " (:error resp)) resp)))))

(defn converse
  "Multi-turn conversation. history is vec of {:role :content} maps."
  [history & {:keys [system model]}]
  (let [resp (chat history :system system :model model)]
    (if (:ok resp)
      resp
      (throw (ex-info "LLM error" resp)))))

;; =============================================================================
;; Model Discovery
;; =============================================================================

(defn list-models
  "Fetch available models from OpenRouter."
  []
  (when-let [k (api-key)]
    (let [resp (http/get models-url
                         {:headers {"Authorization" (str "Bearer " k)}
                          :timeout 10000
                          :throw false})
          parsed (parse-json-safe (:body resp))]
      (when (= 200 (:status resp))
        (->> (:data parsed)
             (mapv (fn [m] {:id (:id m)
                            :name (:name m)
                            :pricing (:pricing m)})))))))

;; =============================================================================
;; Doctor
;; =============================================================================

(defn doctor
  "Check connectivity and key validity. Use :json? true for inspectable JSON output."
  [& {:keys [json? silent?] :or {json? false silent? false}}]
  (let [ks (key-source)
        conn (try
               (let [resp (http/get models-url {:timeout 5000 :throw false})]
                 {:ok (= 200 (:status resp)) :status (:status resp)})
               (catch Exception e
                 {:ok false :status :exception :error (.getMessage e)}))
        probe (chat [{:role "user" :content "Say ok"}] :model (or (:model @config) (first free-models)))
        report {:provider :openrouter
                :configured-model (:model @config)
                :key {:present (boolean (:key ks))
                      :source (:source ks)
                      :masked (mask-key (:key ks))}
                :connectivity conn
                :auth {:ok (:ok probe)
                       :status (:status probe)
                       :error (:error probe)
                       :hint (if (:ok probe) "Authenticated." (or (:hint probe) (error-hint probe)))}
                :fixes [(error-hint probe)
                        "Use `/model <id>` to switch to another free model."
                        "Inspect status via GET /api/openrouter/status or `coggy doctor --json`." ]}]
    (if json?
      (when-not silent?
        (println (json/generate-string report {:pretty true})))
      (when-not silent?
        (println "coggy doctor")
        (println "────────────")
        (println (str "  provider: openrouter"))
        (println (str "  API key: " (get-in report [:key :masked]) " (" (name (get-in report [:key :source])) ")"))
        (println (str "  model: " (:configured-model report)))
        (println (str "  connectivity: " (if (get-in report [:connectivity :ok]) "OK" (str "FAIL — " (or (get-in report [:connectivity :error]) (get-in report [:connectivity :status]))))))
        (println (str "  auth: " (if (get-in report [:auth :ok]) "OK" (str "FAIL — " (get-in report [:auth :error])))))
        (println (str "  hint: " (get-in report [:auth :hint])))))
    report))
