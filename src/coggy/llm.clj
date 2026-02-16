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
  ["qwen/qwen3-next-80b-a3b-instruct:free"
   "nvidia/nemotron-nano-12b-v2-vl:free"
   "nvidia/nemotron-3-nano-30b-a3b:free"
   "openai/gpt-oss-120b:free"
   "openrouter/free"])

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

;; =============================================================================
;; API Calls
;; =============================================================================

(defn chat
  "Send a chat completion request. Returns the full response map.
   messages: [{:role \"user\" :content \"...\"}]"
  [messages & {:keys [model system] :as opts}]
  (let [key (api-key)
        model (or model (:model @config))
        body (cond-> {:model model
                      :messages (if system
                                  (into [{:role "system" :content system}] messages)
                                  (vec messages))
                      :max_tokens (:max-tokens @config)
                      :temperature (:temperature @config)}
               true identity)
        base {:model model
              :key-source (:source (key-source))
              :request {:message-count (count messages)
                        :system? (boolean system)}}]
    (if-not key
      {:ok false
       :status :missing-key
       :error "No API key"
       :hint (error-hint {})
       :diagnostics base}
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
            {:ok true
             :content (get-in parsed [:choices 0 :message :content])
             :model model
             :usage (:usage parsed)
             :raw parsed
             :diagnostics base}
            {:ok false
             :status (:status resp)
             :error err
             :hint (error-hint {:status (:status resp) :error err})
             :raw (or parsed {:body (:body resp)})
             :diagnostics base}))
        (catch Exception e
          (let [msg (.getMessage e)]
            {:ok false
             :status :exception
             :error msg
             :hint (error-hint {:error msg})
             :diagnostics base}))))))

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

(defn list-free-models
  "Filter to models with zero prompt pricing."
  []
  (->> (list-models)
       (filter (fn [m]
                 (and (get-in m [:pricing :prompt])
                      (= "0" (get-in m [:pricing :prompt])))))
       (mapv :id)))

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
                       :hint (or (:hint probe) (error-hint probe))}
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
