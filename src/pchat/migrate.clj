(ns pchat.migrate
  "The chats of a workspace from before 0.2.0, when every chat worked in
  the one shared folder: their session files under `.pchat/sessions/`,
  the artifacts of all of them under `artifacts/`, the pictures under
  `.pchat/looks/`. `bin/migrate-chats` (a script in the workspace, see
  its header) brings them into the folders of `pchat.chats`; this
  namespace shows the banner that offers it and runs the script.

  The db key this namespace owns:

      :migration {:needed? :state (:running :done :failed) :progress
                  :count :error}"
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn handle
  "{:db the app's atom, :workspace the folder the script runs in, :bb
  the babashka executable, :on-done a function of no arguments, called
  after the script succeeded: the chats are read again}"
  [{:keys [workspace] :as opts}]
  (assoc opts :workspace (io/file workspace)))

(defn- visible-entries
  [dir]
  (->> (or (.listFiles (io/file dir)) [])
       (remove #(str/starts-with? (.getName %) "."))
       (seq)))

(defn needed?
  "Whether there is something to bring back: session files or
  artifacts in the old places, and no `.pchat/migrated-at` yet."
  [{:keys [workspace]}]
  (boolean
   (and (not (.isFile (io/file workspace ".pchat" "migrated-at")))
        (or (some #(str/ends-with? (.getName %) ".jsonl")
                  (or (.listFiles (io/file workspace ".pchat" "sessions")) []))
            (visible-entries (io/file workspace "artifacts"))))))

(defn check!
  "The banner follows what the workspace holds."
  [{:keys [db] :as handle}]
  (swap! db assoc-in [:migration :needed?] (needed? handle)))

(defn- read-lines!
  [stream on-line]
  (with-open [reader (io/reader stream)]
    (doseq [line (line-seq reader)
            :when (not (str/blank? line))]
      (on-line line))))

(defn- count-of
  "What the script printed last: `3 chats brought back`."
  [lines]
  (some->> lines
           (keep #(re-find #"^(\d+) chats? brought back" %))
           (last)
           (second)
           (parse-long)))

(defn start!
  "Runs the script in the workspace, its last line as the progress of
  the banner; done with the number of chats, or failed with the last
  line."
  [{:keys [db workspace bb on-done]}]
  (when-not (= :running (get-in @db [:migration :state]))
    (swap! db update :migration merge {:state :running
                                       :progress nil
                                       :error nil})
    (future
      (let [lines (atom [])
            note! (fn [line]
                    (swap! lines conj line)
                    (swap! db assoc-in [:migration :progress] line))
            exit (try
                   (let [p (process/process [(or bb "bb") "bin/migrate-chats"]
                                            {:dir (str workspace)
                                             :out :stream
                                             :err :stream})]
                     (future (read-lines! (:err p) note!))
                     (read-lines! (:out p) note!)
                     (:exit @p))
                   (catch Exception e
                     (note! (ex-message e))
                     -1))]
        (if (zero? exit)
          (do (swap! db update :migration merge {:state :done
                                                 :needed? false
                                                 :count (or (count-of @lines) 0)})
              (when on-done
                (on-done)))
          (swap! db update :migration merge {:state :failed
                                             :error (or (last @lines) (str "exit " exit))}))))))

(defn dismiss!
  [{:keys [db]}]
  (swap! db update :migration dissoc :state :count :error :progress))
