#!/usr/bin/env bb

(ns coggy.main
  "Entry point — Coggy wakes, boots, enters the loop."
  (:require [coggy.atomspace :as as]
            [coggy.attention :as att]
            [cheshire.core :as json]
            [coggy.llm :as llm]
            [coggy.trace :as trace]
            [coggy.tui :as tui]
            [coggy.boot :as boot]
            [coggy.repl :as repl]
            [coggy.web :as web]))

;; =============================================================================
;; Configuration
;; =============================================================================

(defn load-env! []
  (let [env-file ".env"]
    (when (-> (java.io.File. env-file) .exists)
      (doseq [line (clojure.string/split-lines (slurp env-file))]
        (when-let [[_ k v] (re-matches #"([A-Z_]+)=(.*)" line)]
          (System/setProperty k v)))))
  ;; Configure from env
  (when-let [key (or (System/getenv "OPENROUTER_API_KEY")
                     (System/getProperty "OPENROUTER_API_KEY"))]
    (llm/configure! {:api-key key})))

;; =============================================================================
;; Main
;; =============================================================================

(defn -main [& args]
  (load-env!)

  (case (first args)
    "boot"   (do (println (tui/banner))
                 (boot/run-boot! (as/make-space) (att/make-bank)))

    "doctor" (llm/doctor :json? (= "--json" (second args)))
    "connect" (println (json/generate-string (repl/connect-status) {:pretty true}))

    "serve"  (let [port (parse-long (or (second args) "8421"))]
               (println (tui/banner))
               (let [space (as/make-space)
                     bank (att/make-bank)]
                 (boot/run-boot! space bank)
                 (reset! repl/session
                         {:space space :bank bank :history []
                          :turn 0 :concepts-seen #{}
                          :active-domain nil
                          :started-at (System/currentTimeMillis)})
                 (when (not= "0" (or (System/getenv "COGGY_AUTOLOAD_SNAPSHOT")
                                     (System/getProperty "COGGY_AUTOLOAD_SNAPSHOT")
                                     "1"))
                   (let [r (let [rolling (repl/load-state!)]
                             (if (:ok rolling) rolling (repl/load-latest-snapshot!)))]
                     (when (:ok r)
                       (println (str "snapshot loaded: " (:path r)
                                     " (turn " (:turn r) ", atoms " (:atoms r) ")")))))
                 (web/start! port)))

    ;; Default: full REPL with boot
    (do
      (println (tui/banner))

      ;; Boot ritual
      (let [space (as/make-space)
            bank (att/make-bank)]
        (boot/run-boot! space bank)

        ;; Install into session
        (reset! repl/session
                {:space space
                 :bank bank
                 :history []
                 :turn 0
                 :concepts-seen #{}
                 :active-domain nil
                 :started-at (System/currentTimeMillis)})
        (when (not= "0" (or (System/getenv "COGGY_AUTOLOAD_SNAPSHOT")
                            (System/getProperty "COGGY_AUTOLOAD_SNAPSHOT")
                            "1"))
          (let [r (let [rolling (repl/load-state!)]
                    (if (:ok rolling) rolling (repl/load-latest-snapshot!)))]
            (when (:ok r)
              (println (str "snapshot loaded: " (:path r)
                            " (turn " (:turn r) ", atoms " (:atoms r) ")")))))

        ;; Enter the loop
        (repl/repl-loop)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
