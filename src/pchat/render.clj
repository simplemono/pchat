(ns pchat.render
  "A video from an artifact project on a click: `bin/render <name>` as a
  process of its own, its progress in the db while it runs. The file it
  writes shows up as an artifact by itself. Stop ends the process and
  removes the half-written file.

  The db key this namespace owns:

      :renders {name {:started ms :progress \"frame 30/180\" :exit n :error \"...\"}}"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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
  "{:db the app's atom, :workspace the directory bin/render runs in, :bb
  the babashka executable, :env extra environment for the process,
  :markers-dir where an install leaves its marker files (`~/.local`)}"
  [opts]
  (-> opts
      (update :markers-dir #(or % (io/file (System/getProperty "user.home") ".local")))
      (assoc :procs (atom {}))))

(defn running?
  [{:keys [procs]} name]
  (when-let [{:keys [process]} (get @procs name)]
    (.isAlive (:proc process))))

(defn- read-lines!
  "Every line of the stream into `on-line`."
  [stream on-line]
  (with-open [reader (io/reader stream)]
    (doseq [line (line-seq reader)
            :when (not (str/blank? line))]
      (on-line line))))

(defn output-file
  "Where bin/render writes the video of the project."
  [{:keys [workspace]} name]
  (io/file workspace "artifacts" (str name ".mp4")))

(defn- finish!
  "The exit of the process into the db: an error with the last line of
  the output, nothing of it after a stop."
  [{:keys [db procs]} name exit lines]
  (let [stopped? (get-in @procs [name :stopped?])]
    (swap! procs dissoc name)
    (swap! db update :renders
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
                                                 (str "exit " exit))))))))))))

(defn- refuse!
  "No process: the render is over before it started, with the message
  as its error, where the person reads it."
  [{:keys [db]} name message]
  (swap! db assoc-in [:renders name] {:started (System/currentTimeMillis)
                                      :exit 1
                                      :error message})
  name)

(defn start!
  "Starts the render of the project `name`, unless it runs already or
  the video tools are not there yet (then the reason is the render's
  error, see `not-ready-message`)."
  [{:keys [db workspace bb env procs markers-dir] :as handle} name]
  (when-not (running? handle name)
    (if-let [message (not-ready-message (missing-video-tools env) (video-tools-state markers-dir))]
      (refuse! handle name message)
      (let [p (process/process [(or bb "bb") "bin/render" (str name)]
                             {:dir (str workspace)
                              :out :stream
                              :err :stream
                              :extra-env (or env {})})
          lines (atom [])
          note! (fn [line]
                  (swap! lines #(vec (take-last 20 (conj % line))))
                  (when (re-find #"^frame \d+/\d+" line)
                    (swap! db assoc-in [:renders name :progress] line)))]
      (swap! procs assoc name {:process p})
      (swap! db assoc-in [:renders name] {:started (System/currentTimeMillis)})
      (future (read-lines! (:out p) note!))
      (future (read-lines! (:err p) note!))
      (future
        (let [{:keys [exit]} @p]
          ;; The readers are done with their streams by now, or nearly.
          (Thread/sleep 100)
          (finish! handle name exit @lines)))
      name))))

(defn stop!
  "Ends a running render; the file it was writing goes with it."
  [{:keys [db procs] :as handle} name]
  (when-let [{:keys [process]} (get @procs name)]
    (swap! procs assoc-in [name :stopped?] true)
    (process/destroy-tree process)
    (let [started (get-in @db [:renders name :started] 0)
          file (output-file handle name)]
      ;; ffmpeg wrote into it since the start: a torso.
      (when (and (.isFile file) (<= started (.lastModified file)))
        (io/delete-file file true)))
    (swap! db update :renders dissoc name)
    name))

(defn stop-all!
  [{:keys [procs]}]
  (doseq [[_ {:keys [process]}] @procs]
    (process/destroy-tree process))
  (reset! procs {}))
