(ns coggy.tui
  "Terminal UI — physicality in text.

   Every element has weight, texture, state.
   Borders breathe. Focus glows. History scrolls with heft.
   The screen is a surface with regions, not a stream of lines."
  (:require [clojure.string :as str]))

;; =============================================================================
;; ANSI Escape Codes
;; =============================================================================

(def esc "\033[")

(def colors
  {:reset     (str esc "0m")
   :bold      (str esc "1m")
   :dim       (str esc "2m")
   :italic    (str esc "3m")
   :underline (str esc "4m")
   :blink     (str esc "5m")
   :reverse   (str esc "7m")
   ;; Foreground
   :fg-black   (str esc "30m")
   :fg-red     (str esc "31m")
   :fg-green   (str esc "32m")
   :fg-yellow  (str esc "33m")
   :fg-blue    (str esc "34m")
   :fg-magenta (str esc "35m")
   :fg-cyan    (str esc "36m")
   :fg-white   (str esc "37m")
   ;; Bright foreground
   :fg-bright-black  (str esc "90m")
   :fg-bright-red    (str esc "91m")
   :fg-bright-green  (str esc "92m")
   :fg-bright-yellow (str esc "93m")
   :fg-bright-blue   (str esc "94m")
   :fg-bright-magenta (str esc "95m")
   :fg-bright-cyan   (str esc "96m")
   :fg-bright-white  (str esc "97m")
   ;; Background
   :bg-black   (str esc "40m")
   :bg-red     (str esc "41m")
   :bg-blue    (str esc "44m")
   :bg-cyan    (str esc "46m")
   ;; 256-color
   :bg-dark    (str esc "48;5;235m")
   :bg-darker  (str esc "48;5;233m")
   :bg-trace   (str esc "48;5;236m")
   :fg-gold    (str esc "38;5;220m")
   :fg-teal    (str esc "38;5;37m")
   :fg-lavender (str esc "38;5;183m")
   :fg-ember   (str esc "38;5;208m")
   :fg-sage    (str esc "38;5;108m")
   :fg-slate   (str esc "38;5;245m")
   :fg-ghost   (str esc "38;5;240m")
   :fg-ice     (str esc "38;5;117m")})

(defn c
  "Apply color/style codes to text."
  [text & styles]
  (str (str/join "" (map colors styles))
       text
       (:reset colors)))

(defn clear-screen []
  (print (str esc "2J" esc "H"))
  (flush))

(defn move-cursor [row col]
  (print (str esc row ";" col "H"))
  (flush))

(defn term-width []
  (try
    (let [result (-> (ProcessBuilder. ["tput" "cols"])
                     (.inheritIO)
                     (.redirectOutput ProcessBuilder$Redirect/PIPE)
                     (.start))]
      (parse-long (str/trim (slurp (.getInputStream result)))))
    (catch Exception _ 80)))

(defn term-height []
  (try
    (let [result (-> (ProcessBuilder. ["tput" "lines"])
                     (.inheritIO)
                     (.redirectOutput ProcessBuilder$Redirect/PIPE)
                     (.start))]
      (parse-long (str/trim (slurp (.getInputStream result)))))
    (catch Exception _ 24)))

;; =============================================================================
;; Box Drawing — surfaces with weight
;; =============================================================================

(def box-chars
  {:heavy  {:tl "┏" :tr "┓" :bl "┗" :br "┛" :h "━" :v "┃"
            :lt "┣" :rt "┫" :tt "┳" :bt "┻" :cross "╋"}
   :light  {:tl "┌" :tr "┐" :bl "└" :br "┘" :h "─" :v "│"
            :lt "├" :rt "┤" :tt "┬" :bt "┴" :cross "┼"}
   :double {:tl "╔" :tr "╗" :bl "╚" :br "╝" :h "═" :v "║"
            :lt "╠" :rt "╣" :tt "╦" :bt "╩" :cross "╬"}
   :round  {:tl "╭" :tr "╮" :bl "╰" :br "╯" :h "─" :v "│"
            :lt "├" :rt "┤" :tt "┬" :bt "┴" :cross "┼"}})

(defn box
  "Draw a box with title and content."
  [title content & {:keys [style width fg bg title-fg]
                    :or {style :round width 60 fg :fg-white bg nil title-fg :fg-gold}}]
  (let [bc (get box-chars style)
        inner (- width 4)
        title-str (if title (str " " title " ") "")
        title-len (count title-str)
        top-line (str (:tl bc)
                      (apply str (repeat (min title-len inner) (str (:h bc))))
                      "╴"
                      (c title-str :bold title-fg)
                      "╶"
                      (apply str (repeat (max 0 (- inner title-len 2)) (str (:h bc))))
                      (:tr bc))
        bot-line (str (:bl bc)
                      (apply str (repeat (- width 2) (str (:h bc))))
                      (:br bc))
        lines (if (string? content)
                (str/split-lines content)
                content)]
    (str (c top-line fg) "\n"
         (str/join "\n"
                   (map (fn [l]
                          (let [pad (- inner (min inner (count l)))]
                            (str (c (:v bc) fg) " "
                                 (if bg (c l fg bg) (c l fg))
                                 (apply str (repeat pad " "))
                                 "   " (c (:v bc) fg))))
                        lines))
         "\n" (c bot-line fg))))

;; =============================================================================
;; Status Bar — always-visible ground truth
;; =============================================================================

(defn status-bar
  "Render a full-width status bar."
  [{:keys [turn atoms links focus model]}]
  (let [w (term-width)
        left (str " ● turn:" (or turn 0)
                  "  atoms:" (or atoms 0)
                  "  links:" (or links 0)
                  "  focus:" (or focus "—"))
        right (str "model:" (or model "?") " ")
        pad (max 0 (- w (count left) (count right)))]
    (c (str left (apply str (repeat pad " ")) right)
       :fg-black :bg-dark :bold)))

;; =============================================================================
;; Trace Panel — the reasoning wake, rendered with texture
;; =============================================================================

(defn trace-panel
  "Render trace as a visual panel with phase separators."
  [phases & {:keys [width] :or {width 56}}]
  (let [lines (atom [])]
    ;; Header
    (swap! lines conj (c "┌ COGGY ────────────────────────────" :fg-teal :bold))

    ;; PARSE
    (when-let [parse (:parse phases)]
      (swap! lines conj (c "│ PARSE" :fg-gold :bold))
      (doseq [a parse]
        (let [atype (name (:atom/type a))
              aname (name (:atom/name a))]
          (swap! lines conj
                 (str (c "│" :fg-teal)
                      "   "
                      (c (case (:atom/type a)
                           :ConceptNode "⊕"
                           :PredicateNode "⊛"
                           :InheritanceLink "↗"
                           "·") :fg-ember)
                      " "
                      (c atype :fg-sage)
                      " "
                      (c (str "\"" aname "\"") :fg-bright-white :bold))))))

    ;; GROUND
    (when-let [ground (:ground phases)]
      (swap! lines conj (c "│" :fg-teal))
      (swap! lines conj (c "│ GROUND" :fg-gold :bold))
      (doseq [f (:found ground)]
        (swap! lines conj (str (c "│" :fg-teal) "   " (c "⊕" :fg-green) " found — " (c (str f) :fg-sage))))
      (doseq [m (:missing ground)]
        (swap! lines conj (str (c "│" :fg-teal) "   " (c "○" :fg-ghost) " gap — " (c (str m) :dim)))))

    ;; ATTEND
    (when-let [attend (:attend phases)]
      (swap! lines conj (c "│" :fg-teal))
      (swap! lines conj (c "│ ATTEND" :fg-gold :bold))
      (doseq [{:keys [key sti]} attend]
        (let [glyph (if (> sti 5.0) (c "★" :fg-bright-yellow) (c "↘" :fg-ghost))
              sti-str (format "%.1f" sti)
              bar-len (min 12 (int (/ sti 2)))
              bar (apply str (repeat bar-len "▓"))
              empty-bar (apply str (repeat (- 12 bar-len) "░"))]
          (swap! lines conj
                 (str (c "│" :fg-teal)
                      "   " glyph " "
                      (c (name key) :fg-bright-white) "  "
                      (c bar :fg-teal) (c empty-bar :fg-ghost) " "
                      (c sti-str :fg-slate))))))

    ;; INFER
    (when-let [infer (:infer phases)]
      (swap! lines conj (c "│" :fg-teal))
      (swap! lines conj (c "│ INFER" :fg-gold :bold))
      (doseq [{:keys [type conclusion tv]} infer]
        (let [glyph (case type :deduction "⊢" :abduction "?" :analogy "≈" :gap "?" "·")
              tv-str (when tv (str " " (c (str "(stv " (format "%.1f" (:tv/strength tv))
                                                " " (format "%.1f" (:tv/confidence tv)) ")")
                                         :fg-lavender)))]
          (swap! lines conj
                 (str (c "│" :fg-teal)
                      "   " (c glyph :fg-ember) " "
                      (c conclusion :fg-white :italic)
                      (or tv-str ""))))))

    ;; REFLECT
    (when-let [reflect (:reflect phases)]
      (swap! lines conj (c "│" :fg-teal))
      (swap! lines conj (c "│ REFLECT" :fg-gold :bold))
      (swap! lines conj
             (str (c "│" :fg-teal)
                  "   new:" (c (str (:new-atoms reflect)) :fg-bright-green)
                  "  upd:" (c (str (:updated reflect)) :fg-bright-yellow)
                  "  focus:" (c (str (or (:focus-concept reflect) "—")) :fg-bright-cyan :bold)))
      (when-let [q (:next-question reflect)]
        (swap! lines conj
               (str (c "│" :fg-teal)
                    "   " (c "↦" :fg-ember) " "
                    (c (str "\"" q "\"") :dim :italic)))))

    ;; Footer
    (swap! lines conj (c "└───────────────────────────────────" :fg-teal))
    (str/join "\n" @lines)))

;; =============================================================================
;; Response Panel
;; =============================================================================

(defn response-panel
  "Render LLM response with visual framing."
  [content & {:keys [width] :or {width 72}}]
  (let [wrapped-lines (->> (str/split-lines content)
                           (mapcat (fn [line]
                                     (if (<= (count line) width)
                                       [line]
                                       ;; simple word wrap
                                       (loop [remaining line, acc []]
                                         (if (<= (count remaining) width)
                                           (conj acc remaining)
                                           (let [break (or (str/last-index-of remaining " " width) width)]
                                             (recur (subs remaining (min (inc break) (count remaining)))
                                                    (conj acc (subs remaining 0 break))))))))))]
    (str/join "\n"
              (map (fn [l] (str "  " (c l :fg-bright-white)))
                   wrapped-lines))))

;; =============================================================================
;; Prompt
;; =============================================================================

(defn prompt-string [turn]
  (str (c "coggy" :fg-teal :bold)
       (c (str "[" turn "]") :fg-slate)
       (c "❯ " :fg-gold)))

;; =============================================================================
;; Sparkline — for attention history
;; =============================================================================

(defn sparkline
  "Render a sparkline from a sequence of numbers."
  [values & {:keys [width] :or {width 20}}]
  (let [bars "▁▂▃▄▅▆▇█"
        sampled (if (> (count values) width)
                  (let [step (/ (count values) width)]
                    (mapv #(nth values (int (* % step))) (range width)))
                  values)
        mn (apply min sampled)
        mx (apply max sampled)
        range (max 0.01 (- mx mn))]
    (apply str (map (fn [v]
                      (nth bars (min 7 (int (* 7 (/ (- v mn) range))))))
                    sampled))))

;; =============================================================================
;; Full Screen Render
;; =============================================================================

(defn render-turn
  "Render a complete turn: response + trace + status."
  [{:keys [content trace turn stats model]}]
  (println "")
  (println (response-panel content))
  (println "")
  (println (trace-panel trace))
  (println "")
  (println (status-bar {:turn turn
                        :atoms (:atoms stats)
                        :links (:links stats)
                        :focus (when-let [f (first (:attend trace))]
                                 (name (:key f)))
                        :model model}))
  (println ""))

;; =============================================================================
;; Banner
;; =============================================================================

(defn banner []
  (str/join "\n"
            [(c "" :reset)
             ""
             (c "        ╔═══════════════════════════════════════════╗" :fg-teal)
             (c "        ║" :fg-teal)
             (str (c "        ║" :fg-teal)
                  "    " (c "C O G G Y" :fg-gold :bold)
                  "  —  " (c "ὕλη becomes νοῦς" :fg-lavender :italic)
                  "    " (c "║" :fg-teal))
             (c "        ║" :fg-teal)
             (str (c "        ║" :fg-teal)
                  "  " (c "ontological reasoning harness v0.1" :fg-sage)
                  "    " (c "║" :fg-teal))
             (str (c "        ║" :fg-teal)
                  "  " (c "prime matter → form → thought → trace" :fg-ghost :italic)
                  " " (c "║" :fg-teal))
             (c "        ║" :fg-teal)
             (c "        ╚═══════════════════════════════════════════╝" :fg-teal)
             ""]))
