(ns pchat.actions
  "A skill's actions: the commands its artifact pages run without the
  agent. A skill declares them in `actions.json` beside its SKILL.md:

      {\"apply\": {\"run\": [\"node\", \"{skill}/tools/apply.mjs\"],
                 \"label\": \"Apply the settings\",
                 \"timeout\": 300}}

  A page asks for one with the command `:artifacts/action`, `{:id chat
  :path \"short-1\" :action \"apply\" :input {...}}`. pchat runs the
  command in the chat's folder, `{skill}` replaced by the skill's
  folder, and writes the request to its standard input as JSON:
  `{\"artifact\": \"short-1\", \"input\": {...}}`. A line `progress
  <text>` on its standard output is the progress, the last line that is
  a JSON object its result (`{\"ok\": true, \"note\": \"...\"}`). One
  action per artifact at a time; `GET /chat/<id>/actions/<path>` answers
  its state. The command is fixed by the operator's skill, the input is
  not trusted: it reaches the command only as data, the skill checks it.

  When an action ends, `:on-finish` is called; pchat tells the chat and,
  with the person's next message, the agent: a line of `note-line`,
  which the chat does not show in the message.

  The key this namespace owns in a chat of the db:

      :actions {path {:action :label :started :progress :ended :exit :result :error}}"
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chats :as chats]))

(def default-timeout-s
  600)

(def max-input-bytes
  (* 64 1024))

(defn read-actions
  "The actions of the skill in `dir`, from its actions.json: `{name
  {:run [...] :label :timeout}}`; {} without the file, and without the
  entries that have no `run` of strings."
  [dir]
  (let [file (io/file dir "actions.json")]
    (if-not (.isFile file)
      {}
      (into {}
            (for [[name {:keys [run label timeout]}] (json/parse-string (slurp file) true)
                  :when (and (sequential? run) (seq run) (every? string? run))]
              [(clojure.core/name name)
               {:run (vec run)
                :label (when (string? label) label)
                :timeout (if (and (number? timeout) (pos? timeout)) timeout default-timeout-s)}])))))

(defn command-line
  "The command of an action, `{skill}` replaced by the skill's folder."
  [{:keys [run]} skill-dir]
  (mapv #(str/replace % "{skill}" (str skill-dir)) run))

(defn artifact?
  "Whether `path` names a file or folder under the chat's artifacts
  folder `dir`: relative, no `..`, nothing hidden."
  [dir path]
  (let [parts (str/split (str path) #"/")]
    (boolean (and (seq parts)
                  (not-any? #(or (str/blank? %) (str/starts-with? % ".")) parts)
                  (.exists (io/file dir (str path)))))))

(defn handle
  "{:db the app's atom, :chats-dir the folder of the chats' folders,
  :env a function of the chat id giving the environment of its
  processes, :on-finish a function of the chat id, the path and the
  action's entry, called once it ended}"
  [{:keys [chats-dir] :as opts}]
  (assoc opts
         :chats-dir (io/file chats-dir)
         :procs (atom {})))

(defn running?
  [{:keys [procs]} id path]
  (when-let [{:keys [process]} (get @procs [id path])]
    (.isAlive (:proc process))))

(defn any-running?
  [{:keys [procs]}]
  (boolean (some (fn [{:keys [process]}] (.isAlive (:proc process))) (vals @procs))))

(defn- read-lines!
  [stream on-line]
  (with-open [reader (io/reader stream)]
    (doseq [line (line-seq reader)
            :when (not (str/blank? line))]
      (on-line line))))

(defn- json-object
  [line]
  (when (str/starts-with? (str/trim line) "{")
    (try
      (let [value (json/parse-string line true)]
        (when (map? value) value))
      (catch Exception _ nil))))

(defn- finish!
  [{:keys [db procs on-finish]} id path exit out err timed-out?]
  (let [result (some json-object (reverse out))
        failed? (or timed-out? (not (zero? exit)) (false? (:ok result)))
        error (when failed?
                (or (when timed-out? "It took too long and was stopped.")
                    (some-> (:error result) str)
                    (last err)
                    (str "exit " exit)))]
    (swap! procs dissoc [id path])
    (swap! db update-in [:chats id :actions path]
           (fn [entry]
             (cond-> (-> entry (dissoc :progress) (assoc :exit exit :result result :ended (System/currentTimeMillis)))
               error (assoc :error error))))
    (when on-finish
      (on-finish id path (get-in @db [:chats id :actions path])))))

(defn start!
  "Runs `action` (one of the skill's, see `read-actions`) for the
  artifact `path` of the chat `id` with `input`, in the chat's folder;
  nil when one runs for the artifact already."
  [{:keys [db chats-dir env procs] :as handle} id {:keys [skill-dir action-name action path input]}]
  (when-not (running? handle id path)
    (let [p (process/process (command-line action skill-dir)
                             {:dir (str (chats/dir chats-dir id))
                              :in (json/generate-string {:artifact path :input input})
                              :out :stream
                              :err :stream
                              :extra-env (if env (env id) {})})
          out (atom [])
          err (atom [])
          timed-out? (atom false)
          keep-last (fn [lines line] (vec (take-last 50 (conj lines line))))]
      (swap! procs assoc [id path] {:process p})
      (swap! db assoc-in [:chats id :actions path] {:action action-name
                                                    :label (:label action)
                                                    :started (System/currentTimeMillis)})
      (future (read-lines! (:out p) (fn [line]
                                      (swap! out keep-last line)
                                      (when-let [[_ progress] (re-find #"^progress (.+)$" line)]
                                        (swap! db assoc-in [:chats id :actions path :progress] progress)))))
      (future (read-lines! (:err p) #(swap! err keep-last %)))
      (future
        (Thread/sleep (long (* 1000 (:timeout action))))
        (when (.isAlive (:proc p))
          (reset! timed-out? true)
          (process/destroy-tree p)))
      (future
        (let [{:keys [exit]} @p]
          (Thread/sleep 100)
          (finish! handle id path exit @out @err @timed-out?)))
      path)))

(defn stop-chat!
  "Ends the actions of a chat, for its deletion."
  [{:keys [procs]} id]
  (doseq [[[chat-id path] {:keys [process]}] @procs
          :when (= id chat-id)]
    (process/destroy-tree process)
    (swap! procs dissoc [chat-id path])))

(defn stop-all!
  [{:keys [procs]}]
  (doseq [[_ {:keys [process]}] @procs]
    (process/destroy-tree process))
  (reset! procs {}))

;;; What the agent reads

(def note-line-pattern
  "The line pchat puts above the person's next message for each action
  that ran since: what the page did without the agent."
  #"(?m)^\(Action on the page: [^\n]*\)\n?")

(defn note-line
  "`(Action on the page: apply on short-1, done: Built, 0 errors.)`"
  [path {:keys [action error result]}]
  (str "(Action on the page: " action " on " path
       (if error
         (str ", failed: " error)
         (str ", done" (when-let [note (:note result)] (str ": " note))))
       ")"))

(defn without-notes
  "The text of a message without the lines of `note-line`."
  [text]
  (str/trim (str/replace (str text) note-line-pattern "")))

;;; The page asks

(defn state-handler
  "GET /chat/<id>/actions/<path>, the path under `:*`: the state of the
  artifact's last action, `{\"action\": \"apply\", \"running\": false,
  \"exit\": 0, \"result\": {...}}`, `{}` before the first."
  [{:keys [db] :as handle} id w]
  (let [path (str (get-in w [:ring/route-params :*]))
        entry (get-in @db [:chats id :actions path])]
    (assoc w :ring/response
           {:status 200
            :headers {"Content-Type" "application/json; charset=utf-8"
                      "Cache-Control" "no-store"}
            :body (json/generate-string
                   (if entry
                     (assoc (select-keys entry [:action :label :started :progress :ended :exit :result :error])
                            :running (boolean (running? handle id path)))
                     {}))})))
