(ns pchat.motion
  "A motion design as an artifact: a Clojure file in the artifacts folder
  that defines what is on screen at a time.

      (def duration 6)                           ; seconds
      (def design {:title \"Hello\" :color \"#b45309\"})  ; what changes per use
      (defn design-view [design t] [:div ...])    ; the frame at t, hiccup

  fnmotion is at hand in the file as `fm`, `tl` and `captions`. The file
  is loaded into a namespace of its own in this process (the sandbox is
  the machine, as for every tool the agent runs), once per version of the
  file, and the pane renders `(design-view design t)` on the server for
  every time the scrubber reaches or playback passes: a frame per tick
  pushed to the page, the way rchat's server-side motion works.

  The db keys this namespace owns:

      :motion/path      the artifact that plays
      :motion/t         the time on screen, seconds
      :motion/playing?  whether the tick loop runs"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rframes.command :as command]
            [rframes.sse :as sse]))

(def tick-ms
  "The pause between two pushed frames, for 60 a second."
  16)

(def aliases
  "What a design file can use without requiring it."
  '[[fnmotion.core :as fm]
    [fnmotion.timeline :as tl]
    [fnmotion.captions :as captions]])

;;; Loading

(defn- ns-name-for
  [key]
  (symbol (str "design." (Math/abs (hash key)))))

(defn load-design
  "Loads `source` into a fresh namespace and returns what the pane needs:
  {:view (fn [design t]) :design map :duration seconds} or {:error text}."
  [source key]
  (let [ns-sym (ns-name-for key)]
    (try
      (when (find-ns ns-sym)
        (remove-ns ns-sym))
      (load-string (str "(ns " ns-sym " (:require " (str/join " " (map pr-str aliases)) "))\n"
                        source))
      (let [resolve-in (fn [sym]
                         (some-> (ns-resolve ns-sym sym) deref))
            view (resolve-in 'design-view)]
        (if (fn? view)
          {:view view
           :design (or (resolve-in 'design) {})
           :duration (double (or (resolve-in 'duration) 10))}
          {:error "The file defines no design-view function."}))
      (catch Throwable e
        {:error (str (ex-message e))}))))

(defonce ^:private loaded*
  (atom {}))

(defn design-of
  "The loaded design of the artifact file, loaded again when the file
  changed."
  [file]
  (let [file (io/file file)
        cache-key [(str file) (.lastModified file)]]
    (or (get @loaded* cache-key)
        (let [design (load-design (slurp file) cache-key)]
          (swap! loaded* (fn [m]
                           (-> (into {} (remove (fn [[[path _] _]] (= (str file) path))) m)
                               (assoc cache-key design))))
          design))))

;;; The frame

(defn- data-only
  "A view may hold a function by mistake; a function would drop the whole
  frame. It becomes nothing."
  [hiccup]
  (walk/postwalk (fn [x] (if (fn? x) nil x)) hiccup))

(defn frame
  [{:keys [view design error]} t]
  (if error
    [:p.error error]
    (try
      (data-only (view design t))
      (catch Throwable e
        [:p.error (str "The frame at " (format "%.1f" (double t)) " s failed: " (ex-message e))]))))

;;; Playback: t in the db, a tick loop that pushes frames

(defn- timestamp
  [seconds]
  (let [seconds (double (or seconds 0))]
    (format "%d:%04.1f" (long (quot seconds 60)) (rem seconds 60.0))))

(defn- tick-loop!
  [db duration]
  (let [started (System/nanoTime)
        t0 (:motion/t @db 0.0)]
    (loop []
      (when (:motion/playing? @db)
        (let [t (+ t0 (/ (- (System/nanoTime) started) 1e9))]
          (if (>= t duration)
            (swap! db assoc :motion/t duration :motion/playing? false)
            (do (swap! db assoc :motion/t t)
                (sse/refresh-all!)
                (Thread/sleep tick-ms)
                (recur))))))))

(defn toggle!
  [db duration]
  (let [playing? (not (:motion/playing? @db))
        t (:motion/t @db 0.0)]
    (swap! db assoc
           :motion/playing? playing?
           :motion/t (if (and playing? (>= t duration)) 0.0 t))
    (when playing?
      (future (tick-loop! db duration)))))

(defn seek!
  "`value` is the scrubber's 0..1000."
  [db duration value]
  (swap! db assoc
         :motion/playing? false
         :motion/t (* duration (/ (double value) 1000.0)))
  ;; Past rframes' coalescing: the scrubber should not wait 100 ms.
  (sse/refresh-all!))

(defn follow!
  "Playback belongs to the artifact on screen: another one starts at 0."
  [db path]
  (when (not= path (:motion/path @db))
    (swap! db assoc :motion/path path :motion/t 0.0 :motion/playing? false)))

(defn- duration-of
  [dir path]
  (:duration (design-of (io/file dir path)) 10.0))

(defn commands
  "The :motion/toggle and :motion/seek commands over `db`, for the design
  under `dir`."
  [db dir]
  [{:command/kind :motion/toggle
    :command/fn (fn [w]
                  (if-let [path (:motion/path @db)]
                    (do (toggle! db (duration-of dir path))
                        (command/accepted w))
                    (command/rejected w "Nothing plays.")))}
   {:command/kind :motion/seek
    :command/fn (fn [w]
                  (if-let [path (:motion/path @db)]
                    (do (seek! db (duration-of dir path)
                               (or (parse-double (str (:value (command/command-data w)))) 0.0))
                        (command/accepted w))
                    (command/rejected w "Nothing plays.")))}])

(defn pane
  "The player: the frame at :motion/t, play or pause, the scrubber, the
  time."
  [{:keys [motion/t motion/playing?] :or {t 0.0}} dir {:keys [path]}]
  (let [{:keys [duration] :as design} (design-of (io/file dir path))
        duration (or duration 10.0)
        t (min (double t) duration)]
    [:div.motion
     [:div.motion-frame (frame design t)]
     [:div.fnmotion-controls
      [:button {:on {:click [[:data/command {:command/kind :motion/toggle}]]}}
       (if playing? "Pause" "Play")]
      [:input {:type "range"
               :min 0
               :max 1000
               :value (long (* 1000 (/ t duration)))
               :on {:input [[:data/command {:command/kind :motion/seek
                                            :command/data {:value :event/target.value}}]]}}]
      [:span.fnmotion-time (str (timestamp t) " / " (timestamp duration))]]]))
