(ns pchat.background
  "Tasks that outlast the agent's turn: `bin/background <command>` starts
  one detached from the agent, in `.background/<n>/` of the chat's folder
  (`command`, `pid`, `started`, `log`, and `exit` once it has ended). A
  task that runs is work, like a chat that works: the machine stays awake
  (`pchat.awake`) and no update ends the process. A task that has ended
  is told to the chat's agent once, with its exit, how long it took and
  the end of its output, so that it goes on with what it started the task
  for; `reported` in its folder marks it told. A task whose process is
  gone without an exit (stopped, the machine restarted) is told as such."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chats :as chats]))

(defn tasks-dir
  [chats-dir id]
  (io/file (chats/dir chats-dir id) ".background"))

(defn- read-file
  [dir name]
  (let [f (io/file dir name)]
    (when (.isFile f)
      (str/trim (slurp f)))))

(defn- alive?
  [pid]
  (boolean (some-> pid parse-long (java.lang.ProcessHandle/of) (.orElse nil) (.isAlive))))

(defn- task
  [dir]
  (let [exit (read-file dir "exit")
        pid (read-file dir "pid")]
    {:n (parse-long (.getName dir))
     :dir dir
     :command (read-file dir "command")
     :started (some-> (read-file dir "started") parse-long)
     :exit (some-> exit parse-long)
     :ended? (or (some? exit) (not (alive? pid)))
     :reported? (.isFile (io/file dir "reported"))}))

(defn tasks
  "The tasks of a chat, oldest first."
  [chats-dir id]
  (->> (.listFiles (tasks-dir chats-dir id))
       (filter #(and (.isDirectory %) (re-matches #"\d+" (.getName %))
                     ;; a task just being written has no pid yet
                     (.isFile (io/file % "pid"))))
       (map task)
       (sort-by :n)))

(defn running?
  "Whether any chat of `ids` has a task that runs."
  [chats-dir ids]
  (boolean (some (fn [id] (some (complement :ended?) (tasks chats-dir id))) ids)))

(defn- tail
  "The last lines of the task's output, at most `n` lines and 3000
  characters."
  [dir n]
  (let [f (io/file dir "log")]
    (if (.isFile f)
      (let [lines (take-last n (str/split-lines (slurp f)))
            text (str/join "\n" lines)]
        (if (> (count text) 3000) (str "…" (subs text (- (count text) 3000))) text))
      "")))

(defn report
  "What the agent is told about an ended task."
  [{:keys [n dir command started exit]} now]
  (let [seconds (when started (Math/round (/ (- now started) 1000.0)))
        output (tail dir 30)]
    (str "Background task " n " (bin/background) has ended"
         (if (some? exit)
           (str " with exit " exit)
           " without an exit: it was stopped, or the machine restarted")
         (when seconds
           (str " after " (if (< seconds 60) (str seconds " s") (str (Math/round (/ seconds 60.0)) " min"))))
         ": " command "\n"
         (if (str/blank? output)
           "It printed nothing."
           (str "The end of its output (all of it in .background/" n "/log):\n```\n" output "\n```"))
         "\nGo on with what you started it for.")))

(defn report-ended!
  "Tells `tell!` (id text) about each ended task of the chats of `ids`
  that it was not told about, and marks it told first: a failing
  `tell!` does not tell twice."
  [chats-dir ids tell! now]
  (doseq [id ids
          {:keys [dir ended? reported?] :as j} (tasks chats-dir id)
          :when (and ended? (not reported?))]
    (spit (io/file dir "reported") (str now))
    (tell! id (report j now))))
