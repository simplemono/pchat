(ns artifact.main
  "A motion design: what is on screen at a time, as a small app of its
  own. The data of the design lives in design.edn and in the atom `db`
  under :design; `design-view` is the frame at time `t` in seconds, a pure
  function of the design and the time; `controls` are what the person
  plays with, each one writing into the design. Change a function here
  and save, or evaluate it into the running process, and the page beside
  the chat shows it at once."
  (:require [babashka.nrepl.server :as nrepl]
            [fnmotion.captions :as captions]
            [fnmotion.core :as fm]
            [fnmotion.rframes :as player]
            [fnmotion.timeline :as tl]
            [org.httpkit.server :as server]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(def duration
  "Seconds."
  6)

(defonce db
  (player/watch-design! (atom {:design {:title "Hello" :color "#b45309" :size 56}
                               :motion/t 0.0})
                        "design.edn"))

(defn design-view
  "The frame at `t`."
  [{:keys [title color size]} t]
  (let [enter (fm/spring (fm/progress t 0 1.2))]
    [:div {:style {:position "relative" :height "360px" :display "flex"
                   :align-items "center" :justify-content "center"
                   :background "#fff7ed" :border-radius "12px" :overflow "hidden"}}
     [:h1 {:style {:margin 0
                   :font-size (fm/px size)
                   :color color
                   :opacity (fm/progress t 0 0.4)
                   :transform (str "translateY(" (fm/px (fm/lerp 60 0 enter)) ")")}}
      title]
     [:div {:style {:position "absolute" :left 0 :bottom 0 :height "6px"
                    :width (fm/pct (/ t duration)) :background color}}]]))

(defn controls
  "What the person plays with; every change is visible at once."
  [{:keys [title color size]}]
  [:div.controls
   [:label "Title " [:input {:type "text" :value title :on (player/setter [:title])}]]
   [:label "Size " [:input {:type "range" :min 16 :max 120 :value size :on (player/setter [:size])}]]
   [:label "Color " [:input {:type "color" :value color :on (player/setter [:color])}]]])

(defn page-view
  [_w]
  (let [{:keys [design] :as value} @db]
    [:div.artifact
     (player/player value {:frame #'design-view
                           :duration (fn [] duration)})
     (controls design)]))

(def head
  (str "<title>artifact</title>"
       "<style>"
       "body { margin: 0; padding: 16px; font: 15px/1.5 system-ui, sans-serif; color: #111; background: #fff; }"
       ".artifact { display: flex; flex-direction: column; gap: 16px; }"
       ".controls { display: flex; flex-wrap: wrap; gap: 12px 20px; }"
       ".controls label { display: flex; align-items: center; gap: 8px; }"
       player/css
       "</style>"
       ;; pchat serves the browser runtime; this page lives under its origin.
       "<script defer src=\"/js/main.js\"></script>"))

(defn get-register
  []
  (replicant/expand
   (concat [{:replicant/shim "/"
             :head head}
            {:replicant/render "/"
             :render/fn #'page-view}]
           (player/commands db {:duration (fn [] duration)})
           command/register)))

(defn -main
  [& _args]
  (let [port (parse-long (or (System/getenv "PORT") "0"))
        nrepl-port (parse-long (or (System/getenv "NREPL_PORT") "0"))
        nrepl (nrepl/start-server! {:host "127.0.0.1" :port nrepl-port :quiet true})
        nrepl-port (.getLocalPort (:socket nrepl))]
    (spit ".nrepl-port" (str nrepl-port))
    (sse/watch! db)
    (server/run-server (http/ring-handler get-register) {:port port})
    (println (str "artifact on http://localhost:" port ", nREPL on " nrepl-port))
    @(promise)))
