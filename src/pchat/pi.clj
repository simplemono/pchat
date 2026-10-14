(ns pchat.pi
  "One pi process per chat: `pi --mode rpc`, commands as JSON lines on its
  stdin, events as JSON lines on its stdout. Every event is handed to the
  `on-event` function of the chat as a map with keyword keys, the type a
  string as pi names it (\"message_update\", \"agent_settled\", ...).
  Lines on stderr arrive as {:type \"stderr\" :line ...}, the end of the
  process as {:type \"exited\" :exit n}."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- args
  [{:keys [session-dir session-id provider model system-prompt skills]}]
  (cond-> ["pi" "--mode" "rpc"
           ;; rpc has no trust prompt; without --approve pi silently drops
           ;; project-local files. Extensions, skills and context files
           ;; are a developer's, not this chat's: the chat's skills come
           ;; as --skill, which loads even with --no-skills.
           "--approve" "--no-extensions" "--no-skills" "--no-prompt-templates"
           "--no-themes" "--no-context-files"
           ;; --no-extensions drops pi's built-in extensions too; the MCP
           ;; servers of the workspace (.pi/mcp.json, see pchat.mcp) and
           ;; tool_search, which loads their tools for the model, come back.
           "-e" "builtin:mcp" "-e" "builtin:tool-search"
           ;; Absolute: pi resolves it against its own working directory.
           "--session-dir" (.getAbsolutePath (io/file (str session-dir)))
           "--session-id" (str session-id)]
    provider (into ["--provider" provider])
    model (into ["--model" model])
    system-prompt (into ["--system-prompt" system-prompt])
    (seq skills) (into (mapcat (fn [dir] ["--skill" (str dir)]) skills))))

(defn- read-events!
  [stream on-event]
  (with-open [reader (io/reader stream)]
    (doseq [line (line-seq reader)
            :when (not (str/blank? line))]
      (on-event (try
                  (json/parse-string line true)
                  (catch Exception _
                    {:type "raw"
                     :line line}))))))

(defn spawn!
  "Starts pi for one chat in `dir` and returns the handle. Options: `:dir`,
  `:session-dir`, `:session-id` (a chat id; pi creates or resumes the
  session file), `:provider`, `:model`, `:system-prompt`, `:skills` (skill
  folders), `:env` (extra environment, e.g. the API key), `:on-event`."
  [{:keys [dir env on-event] :as opts}]
  (let [p (process/process (args opts)
                           {:dir (str dir)
                            :in :stream
                            :out :stream
                            :err :stream
                            :extra-env (or env {})})
        in (java.io.OutputStreamWriter. (:in p) "UTF-8")]
    (future
      (try
        (read-events! (:out p) on-event)
        (finally
          (on-event {:type "exited"
                     :exit (:exit @p)}))))
    (future
      (read-events! (:err p) (fn [{:keys [line] :as event}]
                               (on-event (if line
                                           event
                                           {:type "stderr"
                                            :line (json/generate-string event)})))))
    {:process p
     :in in
     :lock (Object.)}))

(defn send!
  "Writes one command to pi."
  [{:keys [in lock]} command]
  (locking lock
    (.write in (str (json/generate-string command) "\n"))
    (.flush in)))

(defn prompt!
  "Sends the user's message, with `images` as pi's image blocks
  `[{:type \"image\" :data base64 :mimeType ...}]`. While pi is streaming
  it is queued as a steering message, delivered before the next model
  call."
  ([pi text streaming?]
   (prompt! pi text streaming? nil))
  ([pi text streaming? images]
   (send! pi (cond-> {:type "prompt"
                      :message text}
               streaming? (assoc :streamingBehavior "steer")
               (seq images) (assoc :images (vec images))))))

(defn abort!
  [pi]
  (send! pi {:type "abort"}))

(defn alive?
  [{:keys [process]}]
  (.isAlive (:proc process)))

(defn kill!
  [{:keys [process]}]
  (process/destroy-tree process))

(defn wait!
  "Waits for the process to end, at most `ms`; for a deletion that
  removes its folder next."
  [{:keys [process]} ms]
  (.waitFor (:proc process) (long ms) java.util.concurrent.TimeUnit/MILLISECONDS))
