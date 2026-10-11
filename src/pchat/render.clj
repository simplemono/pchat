(ns pchat.render
  "A video from an artifact project on a click: `bin/render <name>` as a
  process of its own, its progress in the db while it runs. The file it
  writes shows up as an artifact by itself. Stop ends the process and
  removes the half-written file.

  The db key this namespace owns:

      :renders {name {:started ms :progress \"frame 30/180\" :exit n :error \"...\"}}"
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn handle
  "{:db the app's atom, :workspace the directory bin/render runs in, :bb
  the babashka executable, :env extra environment for the process}"
  [opts]
  (assoc opts :procs (atom {})))

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

(defn start!
  "Starts the render of the project `name`, unless it runs already."
  [{:keys [db workspace bb env procs] :as handle} name]
  (when-not (running? handle name)
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
      name)))

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
