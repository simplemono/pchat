(ns artifact.main
  "A motion design: what is on screen at a time, as a small app of its
  own. The data of the design lives in design.edn and in the atom `db`
  under :design; `design-view` is the frame at time `t` in seconds, a pure
  function of the design and the time; `controls` are what the person
  plays with, each one writing into the design. Change a function here
  and save, or evaluate it into the running process, and the page beside
  the chat shows it at once. `render!` makes the video file."
  (:require [babashka.nrepl.server :as nrepl]
            [clojure.string :as str]
            [fnmotion.captions :as captions]
            [fnmotion.core :as fm]
            [fnmotion.render :as render]
            [fnmotion.rframes :as player]
            [fnmotion.timeline :as tl]
            [org.httpkit.server :as server]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(def size
  "Width and height of the video in pixels: a story, upright."
  [1080 1920])

(def duration
  "Seconds."
  6)

(defonce db
  (player/watch-design! (atom {:design {:title "Hello"
                                        :subtitle "What we will make together"
                                        :color "#b45309"
                                        :size 120
                                        ;; Music or a voice: a file in this
                                        ;; directory, {:src "music.mp3"
                                        ;; :volume 0.6 :fade-out 1}
                                        :audio []}
                               :motion/t 0.0})
                        "design.edn"))

(defn design-view
  "The frame at `t`: fills the stage of `size`."
  [{:keys [title subtitle color size]} t]
  (let [enter (fm/spring (fm/progress t 0 1.2))]
    [:div {:style {:position "absolute" :inset 0
                   :display "flex" :flex-direction "column"
                   :align-items "center" :justify-content "center"
                   :gap "32px" :padding "0 96px" :text-align "center"
                   :background "#fff7ed" :font-family "system-ui, sans-serif"}}
     [:h1 {:style {:margin 0
                   :font-size (fm/px size)
                   :line-height 1.1
                   :color color
                   :opacity (fm/progress t 0 0.4)
                   :transform (str "translateY(" (fm/px (fm/lerp 160 0 enter)) ")")}}
      title]
     [:p {:style {:margin 0
                  :font-size (fm/px (* 0.4 size))
                  :color "#57534e"
                  :opacity (fm/interpolate t [0.8 1.4] [0 1])}}
      subtitle]
     [:div {:style {:position "absolute" :left 0 :bottom 0 :height "20px"
                    :width (fm/pct (/ t duration)) :background color}}]]))

(defn controls
  "What the person plays with; every change is visible at once."
  [{:keys [title subtitle color size]}]
  [:div.controls
   [:label "Title " [:input {:type "text" :value title :on (player/setter [:title])}]]
   [:label "Subtitle " [:input {:type "text" :value subtitle :on (player/setter [:subtitle])}]]
   [:label "Size " [:input {:type "range" :min 48 :max 240 :value size :on (player/setter [:size])}]]
   [:label "Color " [:input {:type "color" :value color :on (player/setter [:color])}]]])

(defn page-view
  [_w]
  (let [{:keys [design] :as value} @db]
    [:div.artifact
     (player/player value {:frame #'design-view
                           :duration (fn [] duration)
                           :size size})
     (controls design)]))

(def styles
  "The rules of the page and of the frames: the page has them, the video
  too."
  (str "<style>"
       "body { margin: 0; padding: 16px; font: 15px/1.5 system-ui, sans-serif; color: #111; background: #fff; }"
       ".artifact { display: flex; flex-direction: column; gap: 16px; }"
       ".controls { display: flex; flex-wrap: wrap; gap: 12px 20px; }"
       ".controls label { display: flex; align-items: center; gap: 8px; }"
       player/css
       "</style>"))

(def head
  (str "<title>artifact</title>"
       styles
       "<script>" player/script "</script>"
       ;; pchat serves the browser runtime; this page lives under its origin.
       "<script defer src=\"/js/main.js\"></script>"))

(def media
  "What a frame may refer to by its name: pictures, sounds, videos, fonts."
  #{"png" "jpg" "jpeg" "gif" "webp" "svg" "avif" "mp3" "wav" "m4a" "ogg" "aac"
    "mp4" "webm" "mov" "woff" "woff2" "ttf" "otf" "css"})

(defn- file-handler
  "A media file of this directory a frame refers to by its name; nothing
  else, nothing hidden."
  [w]
  (let [subpath (str (get-in w [:ring/route-params :*]))
        extension (str/lower-case (or (second (re-find #"\.([^./]+)$" subpath)) ""))
        file (when (and (contains? media extension)
                        (not (re-find #"(^|/)\." subpath)))
               (http/resolve-file "." subpath))]
    (assoc w :ring/response (if file
                              (http/file-response (:ring/request w) file)
                              http/not-found))))

(defn get-register
  []
  (replicant/expand
   (concat [{:replicant/shim "/"
             :head head}
            {:replicant/render "/"
             :render/fn #'page-view}]
           (player/commands db {:duration (fn [] duration)})
           command/register
           ;; Last: the files of this directory.
           [{:ring/route [:get "/*"]
             :ring/handler #'file-handler}])))

(defn render!
  "The design as the video `out`, a path relative to this directory: every
  frame screenshot by a headless Chrome, ffmpeg encodes them at thirty a
  second with the :audio tracks of the design mixed in. Returns
  {:out :frames :seconds}; `bb render <file>` calls it."
  [out]
  (let [design (:design @db)]
    (render/render! {:frame design-view
                     :design design
                     :duration duration
                     :fps 30
                     :size size
                     :head styles
                     :dir "."
                     :audio (:audio design)
                     :out out
                     :on-progress (fn [done all]
                                    (when (or (= done all) (zero? (mod done 30)))
                                      (println (str "frame " done "/" all))))})))

(defn- exit-with-parent!
  "pchat started this process; when pchat is gone, so is this."
  []
  (when-let [pid (some-> (System/getenv "PCHAT_PID") parse-long)]
    (future
      (loop []
        (Thread/sleep 2000)
        (if (some-> (.orElse (java.lang.ProcessHandle/of pid) nil) .isAlive)
          (recur)
          (System/exit 0))))))

(defn -main
  [& _args]
  (exit-with-parent!)
  (let [port (parse-long (or (System/getenv "PORT") "0"))
        nrepl-port (parse-long (or (System/getenv "NREPL_PORT") "0"))
        nrepl (nrepl/start-server! {:host "127.0.0.1" :port nrepl-port :quiet true})
        nrepl-port (.getLocalPort (:socket nrepl))]
    (spit ".nrepl-port" (str nrepl-port))
    (sse/watch! db)
    (server/run-server (http/ring-handler get-register) {:port port})
    (println (str "artifact on http://localhost:" port ", nREPL on " nrepl-port))
    @(promise)))
