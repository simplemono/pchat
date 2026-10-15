(ns pchat.jobs
  "Tasks that outlast the agent's turn: `bin/background <command>` starts
  one detached from the agent, in `.jobs/<n>/` of the chat's folder
  (`command`, `pid`, `started`, `log`, and `exit` once it has ended). A
  job that runs is work, like a chat that works: the machine stays awake
  (`pchat.awake`) and no update ends the process. A job that has ended
  is told to the chat's agent once, with its exit, how long it took and
  the end of its output, so that it goes on with what it started the job
  for; `reported` in its folder marks it told. A job whose process is
  gone without an exit (stopped, the machine restarted) is told as such."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chats :as chats]))

(defn jobs-dir
  [chats-dir id]
  (io/file (chats/dir chats-dir id) ".jobs"))

(defn- read-file
  [dir name]
  (let [f (io/file dir name)]
    (when (.isFile f)
      (str/trim (slurp f)))))

(defn- alive?
  [pid]
  (boolean (some-> pid parse-long (java.lang.ProcessHandle/of) (.orElse nil) (.isAlive))))

(defn- job
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

(defn jobs
  "The jobs of a chat, oldest first."
  [chats-dir id]
  (->> (.listFiles (jobs-dir chats-dir id))
       (filter #(and (.isDirectory %) (re-matches #"\d+" (.getName %))
                     ;; a job just being written has no pid yet
                     (.isFile (io/file % "pid"))))
       (map job)
       (sort-by :n)))

(defn running?
  "Whether any chat of `ids` has a job that runs."
  [chats-dir ids]
  (boolean (some (fn [id] (some (complement :ended?) (jobs chats-dir id))) ids)))

(defn- tail
  "The last lines of the job's output, at most `n` lines and 3000
  characters."
  [dir n]
  (let [f (io/file dir "log")]
    (if (.isFile f)
      (let [lines (take-last n (str/split-lines (slurp f)))
            text (str/join "\n" lines)]
        (if (> (count text) 3000) (str "…" (subs text (- (count text) 3000))) text))
      "")))

(defn report
  "What the agent is told about an ended job."
  [{:keys [n dir command started exit]} now]
  (let [minutes (when started (max 1 (Math/round (/ (- now started) 60000.0))))
        output (tail dir 30)]
    (str "Background job " n " (bin/background) has ended"
         (if (some? exit)
           (str " with exit " exit)
           " without an exit: it was stopped, or the machine restarted")
         (when minutes (str " after " minutes " min"))
         ": " command "\n"
         (if (str/blank? output)
           "It printed nothing."
           (str "The end of its output (all of it in .jobs/" n "/log):\n```\n" output "\n```"))
         "\nGo on with what you started it for.")))

(defn report-ended!
  "Tells `tell!` (id text) about each ended job of the chats of `ids`
  that it was not told about, and marks it told first: a failing
  `tell!` does not tell twice."
  [chats-dir ids tell! now]
  (doseq [id ids
          {:keys [dir ended? reported?] :as j} (jobs chats-dir id)
          :when (and ended? (not reported?))]
    (spit (io/file dir "reported") (str now))
    (tell! id (report j now))))
