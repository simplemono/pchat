(ns pchat.setup
  "Whether the machine is ready for the chat. On a fresh machine pi, the
  agent's program, may still be installing when the page opens: an
  operator installs it in the background and leaves marker files, the
  same convention as the video tools of `pchat.render`. Meanwhile the
  page shows a banner, a message sent gets an answer that says so (the
  message stays in the chat), and `GET /api/setup` tells scripts.

  The markers in `markers-dir` (`~/.local` by default):

      pi-installing   pi is being installed; removed when it is there
      pi-failed       the install failed, the file holds its last lines

  The db key this namespace owns:

      :setup {:pi {:state :ready | :installing | :failed | :missing
                   :lines [...]}
              :video-tools {:state ...}}"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.render :as render]))

(defn handle
  "`{:db atom, :env the agent's environment, :markers-dir where an
  install leaves its marker files (`~/.local`), :extra a function of
  no arguments answering more fields for `GET /api/setup`, such as the
  version and whether the process idles}`."
  [opts]
  (update opts :markers-dir #(or % (io/file (System/getProperty "user.home") ".local"))))

(defn pi-state
  "`:ready` when pi is on the PATH, else what the markers say."
  [env markers-dir]
  (if (render/on-path? env "pi")
    {:state :ready}
    (let [installing (io/file markers-dir "pi-installing")
          failed (io/file markers-dir "pi-failed")]
      (cond
        (.isFile installing) {:state :installing}
        (.isFile failed) {:state :failed
                          :lines (vec (remove str/blank? (str/split-lines (slurp failed))))}
        :else {:state :missing}))))

(defn video-tools-state
  "`:ready` when every video tool is there, else what the markers say."
  [env markers-dir]
  (if (empty? (render/missing-video-tools env))
    {:state :ready}
    (let [{:keys [state] :as found} (render/video-tools-state markers-dir)]
      (if (= :absent state)
        (assoc found :state :missing)
        found))))

(defn state
  [{:keys [env markers-dir]}]
  {:pi (pi-state env markers-dir)
   :video-tools (video-tools-state env markers-dir)})

(defn ready?
  "Whether the chat can answer: pi is there."
  [{:keys [pi]}]
  (= :ready (:state pi)))

(defn refresh!
  "Reads the machine and writes `:setup` into the db; answers the state."
  [{:keys [db] :as handle}]
  (let [found (state handle)]
    (swap! db assoc :setup found)
    found))

(defn not-ready-message
  "The answer to a message sent while pi is not there, nil when it is:
  the words for the person, no stack trace."
  [{:keys [state lines]}]
  (case state
    :ready nil
    :installing [:i18n :setup/pi-installing]
    :failed [:i18n :setup/pi-failed {:lines (str/join " / " lines)}]
    [:i18n :setup/pi-missing]))

(defn refusal-items
  "The person's message, kept in the chat, and the answer under it."
  [text images message]
  [{:kind :user :text text :images (vec images)}
   {:kind :note :text message}])

(def poll-ms
  10000)

(defn watch!
  "Every ten seconds until pi is there: the state into the db, so that
  the banner goes when the install is done. Nothing is polled once
  everything is ready."
  [handle]
  (future
    (loop []
      (let [found (try
                    (refresh! handle)
                    (catch Exception e
                      (binding [*out* *err*]
                        (println "[setup]" (str e)))
                      nil))]
        (when-not (and found (ready? found) (= :ready (get-in found [:video-tools :state])))
          (Thread/sleep poll-ms)
          (recur))))))

(defn api-handler
  "GET /api/setup: `{\"pi\": \"ready\", \"video-tools\": \"installing\",
  \"version\": \"0.1.4\", \"idle\": true}`, read from the machine on
  every call; the fields after the tools come from `:extra`."
  [{:keys [extra] :as handle} w]
  (let [found (refresh! handle)]
    (assoc w :ring/response
           {:status 200
            :headers {"Content-Type" "application/json; charset=utf-8"}
            :body (json/generate-string (merge {:pi (name (get-in found [:pi :state]))
                                                :video-tools (name (get-in found [:video-tools :state]))}
                                               (when extra (extra))))})))

(defn register
  [handle]
  [{:ring/route [:get "/api/setup"]
    :ring/handler (fn [w] (api-handler handle w))}])
