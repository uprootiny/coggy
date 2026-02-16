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

(defn api-key []
  (or (:api-key @config)
      (System/getenv "OPENROUTER_API_KEY")))

;; =============================================================================
;; API Calls
;; =============================================================================

(defn chat
  "Send a chat completion request. Returns the full response map.
   messages: [{:role \"user\" :content \"...\"}]"
  [messages & {:keys [model system] :as opts}]
  (let [key (api-key)
        _ (when-not key (throw (ex-info "No API key" {:hint "Set OPENROUTER_API_KEY or call configure!"})))
        model (or model (:model @config))
        body (cond-> {:model model
                      :messages (if system
                                  (into [{:role "system" :content system}] messages)
                                  (vec messages))
                      :max_tokens (:max-tokens @config)
                      :temperature (:temperature @config)}
               true identity)
        resp (http/post api-url
                        {:headers {"Authorization" (str "Bearer " key)
                                   "Content-Type" "application/json"
                                   "HTTP-Referer" (:site-url @config)
                                   "X-Title" (:site-name @config)}
                         :body (json/generate-string body)
                         :timeout 30000
                         :throw false})]
    (let [parsed (json/parse-string (:body resp) true)]
      (if (= 200 (:status resp))
        {:ok true
         :content (get-in parsed [:choices 0 :message :content])
         :model model
         :usage (:usage parsed)
         :raw parsed}
        {:ok false
         :status (:status resp)
         :error (or (get-in parsed [:error :message])
                    (:body resp))
         :raw parsed}))))

(defn ask
  "Simple question → answer. Returns content string or throws."
  [question & {:keys [system model]}]
  (let [resp (chat [{:role "user" :content question}]
                   :system system
                   :model model)]
    (if (:ok resp)
      (:content resp)
      (throw (ex-info "LLM error" resp)))))

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
  (let [resp (http/get models-url
                       {:headers {"Authorization" (str "Bearer " (api-key))}
                        :timeout 10000
                        :throw false})
        parsed (json/parse-string (:body resp) true)]
    (when (= 200 (:status resp))
      (->> (:data parsed)
           (mapv (fn [m] {:id (:id m)
                          :name (:name m)
                          :pricing (:pricing m)}))))))

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
  "Check connectivity and key validity."
  []
  (println "coggy doctor")
  (println "────────────")
  (print "  API key: ")
  (if (api-key)
    (println (str (subs (api-key) 0 12) "..."))
    (println "MISSING"))
  (print "  Model: ")
  (println (:model @config))
  (print "  Connectivity: ")
  (try
    (let [resp (http/get "https://openrouter.ai/api/v1/models"
                         {:timeout 5000 :throw false})]
      (if (= 200 (:status resp))
        (println "OK")
        (println (str "HTTP " (:status resp)))))
    (catch Exception e
      (println (str "FAIL — " (.getMessage e)))))
  (print "  Auth: ")
  (try
    (let [resp (ask "Say 'ok'" :model (first free-models))]
      (println (str "OK — " (subs (str resp) 0 (min 30 (count (str resp)))))))
    (catch Exception e
      (println (str "FAIL — " (.getMessage e))))))
