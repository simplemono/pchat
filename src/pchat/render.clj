(ns pchat.render
  "A video from an artifact project on a click: `bin/render <name>` in
  the chat's folder as a process of its own, its progress in the db
  while it runs. The file it writes shows up as an artifact by itself.
  Stop ends the process and removes the half-written file.

  The key this namespace owns in a chat of the db:

      :renders {name {:started ms :progress \"frame 30/180\" :exit n :error \"...\"}}"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chats :as chats]))

(def video-tools
  "What a video needs on the machine: ffmpeg, a Chrome, the HyperFrames
  CLI. Any of the Chrome names bin/render accepts counts."
  [["ffmpeg" ["ffmpeg"]]
   ["google-chrome" ["google-chrome" "google-chrome-stable" "chromium" "chromium-browser" "chrome"]]
   ["hyperframes" ["hyperframes"]]])

(defn on-path?
  "Whether an executable of the name is on the PATH of `env` (the
  render's environment) or of this process."
  [env name]
  (let [path (or (get env "PATH") (System/getenv "PATH"))]
    (boolean (some #(fs/executable? (io/file % name)) (str/split (str path) #":")))))

(defn missing-video-tools
  "The names of the video tools that are not there."
  [env]
  (for [[name executables] video-tools
        :when (not (some (partial on-path? env) executables))]
    name))

(defn video-tools-state
  "What the operator's install left in `markers-dir` (`~/.local` by
  default): `:installing` while the video tools are being installed,
  `:failed` with the install's last lines when it failed, `:absent`
  without a marker."
  [markers-dir]
  (let [installing (io/file markers-dir "video-tools-installing")
        failed (io/file markers-dir "video-tools-failed")]
    (cond
      (.isFile installing) {:state :installing}
      (.isFile failed) {:state :failed :lines (remove str/blank? (str/split-lines (slurp failed)))}
      :else {:state :absent})))

(defn not-ready-message
  "Why a render cannot start, or nil when the video tools are there:
  the words for the person, no stack trace."
  [missing {:keys [state lines]}]
  (when (seq missing)
    (case state
      :installing "The video tools are still being installed, try again in a few minutes."
      :failed (str "The video tools could not be installed: " (str/join " / " lines))
      (str "The video tools are not installed on this machine (" (str/join ", " missing)
           "), so no video: the operator installs them."))))

(defn handle
  "{:db the app's atom, :chats-dir the folder of the chats, :bb the
  babashka executable, :env extra environment for the process,
  :markers-dir where an install leaves its marker files (`~/.local`),
  :on-finish a function of the chat id and the name, called once a
  render ended by itself (not after a stop), after its exit is in the
  db}. The processes are kept by [chat-id name]."
  [{:keys [chats-dir] :as opts}]
  (-> opts
      (assoc :chats-dir (io/file chats-dir))
      (update :markers-dir #(or % (io/file (System/getProperty "user.home") ".local")))
      (assoc :procs (atom {}))))

(defn running?
  [{:keys [procs]} id name]
  (when-let [{:keys [process]} (get @procs [id name])]
    (.isAlive (:proc process))))

(defn any-running?
  "Whether a render is on its way."
  [{:keys [procs]}]
  (boolean (some (fn [{:keys [process]}] (.isAlive (:proc process))) (vals @procs))))

(defn- read-lines!
  "Every line of the stream into `on-line`."
  [stream on-line]
  (with-open [reader (io/reader stream)]
    (doseq [line (line-seq reader)
            :when (not (str/blank? line))]
      (on-line line))))

(defn output-file
  "Where bin/render writes the video of the project."
  [{:keys [chats-dir]} id name]
  (io/file (chats/artifacts-dir chats-dir id) (str name ".mp4")))

(defn- finish!
  "The exit of the process into the db: an error with the last line of
  the output, nothing of it after a stop."
  [{:keys [db procs on-finish]} id name exit lines]
  (let [stopped? (get-in @procs [[id name] :stopped?])]
    (swap! procs dissoc [id name])
    (swap! db update-in [:chats id :renders]
           (fn [renders]
             (if stopped?
               (dissoc renders name)
               (update renders name
                       (fn [render]
                         (-> render
                             (dissoc :progress)
                             (assoc :exit exit)
                             (cond-> (not (zero? exit))
                               (assoc :error (or (last (remove #(str/starts-with? % "The render failed") lines))
                                                 (str "exit " exit))))))))))
    (when (and on-finish (not stopped?))
      (on-finish id name))))

(defn- refuse!
  "No process: the render is over before it started, with the message
  as its error, where the person reads it."
  [{:keys [db]} id name message]
  (swap! db assoc-in [:chats id :renders name] {:started (System/currentTimeMillis)
                                                :exit 1
                                                :error message})
  name)

(defn start!
  "Starts the render of the project `name` of the chat, unless it runs
  already or the video tools are not there yet (then the reason is the
  render's error, see `not-ready-message`)."
  [{:keys [db chats-dir bb env procs markers-dir] :as handle} id name]
  (when-not (running? handle id name)
    (if-let [message (not-ready-message (missing-video-tools env) (video-tools-state markers-dir))]
      (refuse! handle id name message)
      (let [p (process/process [(or bb "bb") "bin/render" (str name)]
                               {:dir (str (chats/dir chats-dir id))
                                :out :stream
                                :err :stream
                                :extra-env (assoc (or env {})
                                                  "TMPDIR" (chats/tmp-path chats-dir id))})
            lines (atom [])
            note! (fn [line]
                    (swap! lines #(vec (take-last 20 (conj % line))))
                    (when (re-find #"^frame \d+/\d+" line)
                      (swap! db assoc-in [:chats id :renders name :progress] line)))]
        (swap! procs assoc [id name] {:process p})
        (swap! db assoc-in [:chats id :renders name] {:started (System/currentTimeMillis)})
        (future (read-lines! (:out p) note!))
        (future (read-lines! (:err p) note!))
        (future
          (let [{:keys [exit]} @p]
            ;; The readers are done with their streams by now, or nearly.
            (Thread/sleep 100)
            (finish! handle id name exit @lines)))
        name))))

(defn stop!
  "Ends a running render; the file it was writing goes with it."
  [{:keys [db procs] :as handle} id name]
  (when-let [{:keys [process]} (get @procs [id name])]
    (swap! procs assoc-in [[id name] :stopped?] true)
    (process/destroy-tree process)
    (let [started (get-in @db [:chats id :renders name :started] 0)
          file (output-file handle id name)]
      ;; ffmpeg wrote into it since the start: a torso.
      (when (and (.isFile file) (<= started (.lastModified file)))
        (io/delete-file file true)))
    (swap! db update-in [:chats id :renders] dissoc name)
    name))

(defn stop-chat!
  "Ends the renders of a chat, for its deletion."
  [{:keys [procs] :as handle} id]
  (doseq [[chat-id name] (keys @procs)
          :when (= id chat-id)]
    (stop! handle id name)))

(defn stop-all!
  [{:keys [procs]}]
  (doseq [[_ {:keys [process]}] @procs]
    (process/destroy-tree process))
  (reset! procs {}))
