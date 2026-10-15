(ns pchat.projects
  "An artifact with a process: a directory under a chat's artifacts/
  with a bb.edn. pchat starts it (`bb -m artifact.main`, PORT and
  NREPL_PORT in its environment) when it is on screen or changed
  lately, stops it when no one wanted it for a while, shows its page in
  the iframe through a proxy under /chat/<id>/artifacts/<name>/,
  reloads a source file into it over nREPL when the file changes, and
  writes its output to .log in the directory. The template under
  resources/pchat/template is the motion design project: rframes,
  fnmotion and its server-side player.

  The key this namespace owns in a chat of the db:

      :projects {name {:port :nrepl-port :ready-at :exit :reload}}"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [pchat.chats :as chats]
            [pchat.nrepl :as nrepl]))

(defn handle
  "{:db the app's atom, :chats-dir the folder of the chats, :bb the
  babashka executable (\"bb\"), :shown? a function of a chat id, whether
  someone looks at the chat right now (everyone, by default)}. The
  processes are kept by [chat-id name]."
  [{:keys [chats-dir bb shown?] :as opts}]
  (assoc opts
         :chats-dir (io/file chats-dir)
         :bb (or bb "bb")
         :shown? (or shown? (constantly true))
         :procs (atom {})))

(defn- project-dir
  [{:keys [chats-dir]} id name]
  (io/file (chats/artifacts-dir chats-dir id) (str name)))

(defn names
  "The artifacts that are projects: subdirectories with a bb.edn."
  [dir]
  (->> (or (.listFiles (io/file dir)) [])
       (filter #(and (.isDirectory %)
                     (not (str/starts-with? (.getName %) "."))
                     (.isFile (io/file % "bb.edn"))))
       (map #(.getName %))
       (sort)
       (vec)))

(defn- free-port
  []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(def idle-ms
  "A project no one looks at for this long is stopped; it starts again
  when it is wanted."
  (* 15 60 1000))

(def recent-ms
  "A project with a change this recent is wanted: the agent works on it."
  (* 10 60 1000))

(defn- newest-change
  [project-dir]
  (->> (file-seq (io/file project-dir))
       (filter #(.isFile %))
       (remove #(str/starts-with? (.getName %) "."))
       (map #(.lastModified %))
       (reduce max 0)))

(defn- wanted?
  "The project on screen in a chat someone looks at, or one that
  changed lately."
  [{:keys [db shown?] :as handle} id name now]
  (or (and (shown? id)
           (= name (get-in @db [:chats id :artifacts/current])))
      (< (- now (newest-change (project-dir handle id name))) recent-ms)))

(defn- src-mtimes
  [project-dir]
  (into {}
        (for [file (file-seq (io/file project-dir "src"))
              :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
          [(str file) (.lastModified file)])))

(defn start!
  [{:keys [chats-dir bb procs db] :as handle} id name]
  (let [project-dir (project-dir handle id name)
        port (free-port)
        nrepl-port (free-port)
        log (io/file project-dir ".log")
        p (process/process [bb "-m" "artifact.main"]
                           {:dir (str project-dir)
                            :out log
                            :err log
                            :extra-env {"PORT" (str port)
                                        "NREPL_PORT" (str nrepl-port)
                                        "TMPDIR" (chats/tmp-path chats-dir id)
                                        "PCHAT_BASE" (str (chats/url id) "/artifacts/" name)
                                        ;; The project exits by itself when
                                        ;; pchat is gone, hook or no hook.
                                        "PCHAT_PID" (str (.pid (java.lang.ProcessHandle/current)))}})]
    (swap! procs assoc [id name] {:process p
                                  :port port
                                  :nrepl-port nrepl-port
                                  :started (System/currentTimeMillis)
                                  :last-wanted (System/currentTimeMillis)
                                  :mtimes (src-mtimes project-dir)})
    (swap! db assoc-in [:chats id :projects name] {:port port
                                                   :nrepl-port nrepl-port
                                                   :ready-at nil})
    name))

(defn stop!
  [{:keys [procs db]} id name]
  (when-let [{:keys [process]} (get @procs [id name])]
    (process/destroy-tree process))
  (swap! procs dissoc [id name])
  (swap! db (fn [db]
              (if (get-in db [:chats id])
                (update-in db [:chats id :projects] dissoc name)
                db))))

(defn stop-chat!
  "Ends the projects of a chat, for its deletion."
  [{:keys [procs] :as handle} id]
  (doseq [[chat-id name] (keys @procs)
          :when (= id chat-id)]
    (stop! handle id name)))

(defn stop-all!
  [{:keys [procs] :as handle}]
  (doseq [[id name] (keys @procs)]
    (stop! handle id name)))

(defn- answers?
  [port]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 200)
      true)
    (catch Exception _
      false)))

(defn- check-ready!
  [{:keys [db procs]} id name]
  (let [{:keys [port process]} (get @procs [id name])]
    (when (and port (nil? (get-in @db [:chats id :projects name :ready-at])))
      (cond
        (not (.isAlive (:proc process)))
        (swap! db assoc-in [:chats id :projects name :exit] (:exit @process))

        (answers? port)
        (swap! db assoc-in [:chats id :projects name :ready-at] (System/currentTimeMillis))))))

(defn- reload-changed!
  "A source file the agent saved is loaded into the running process."
  [{:keys [db procs] :as handle} id name]
  (let [{:keys [nrepl-port mtimes]} (get @procs [id name])
        now-mtimes (src-mtimes (project-dir handle id name))]
    (when (and (get-in @db [:chats id :projects name :ready-at])
               (not= mtimes now-mtimes))
      (swap! procs assoc-in [[id name] :mtimes] now-mtimes)
      (doseq [[file mtime] now-mtimes
              :when (not= mtime (get mtimes file))]
        (future
          (let [{:keys [ex err]} (try
                                   (let [result (nrepl/eval! nrepl-port (str "(load-file " (pr-str file) ")"))]
                                     ;; The new definitions on screen now,
                                     ;; not at the next interaction; a
                                     ;; project without rframes has no
                                     ;; connections to refresh.
                                     (nrepl/eval! nrepl-port "(when-let [refresh (resolve 'rframes.sse/refresh-all!)] (refresh))")
                                     result)
                                   (catch Exception e
                                     {:ex (ex-message e)}))]
            (swap! db assoc-in [:chats id :projects name :reload]
                   {:file (str (fs/relativize (project-dir handle id name) file))
                    :at (System/currentTimeMillis)
                    :error (or ex (when (seq err) err))})))))))

(defn- restart-exited!
  "A project that died comes back, not more often than every ten seconds."
  [{:keys [procs] :as handle} id name]
  (let [{:keys [process started]} (get @procs [id name])]
    (when (and process
               (not (.isAlive (:proc process)))
               (< 10000 (- (System/currentTimeMillis) started)))
      (stop! handle id name)
      (start! handle id name))))

(defn- stop-idle!
  "A running project no one wanted for `idle-ms` is stopped: a process
  and its memory for nothing."
  [{:keys [procs] :as handle} id name now wanted?]
  (when-let [{:keys [last-wanted]} (get @procs [id name])]
    (if wanted?
      (swap! procs assoc-in [[id name] :last-wanted] now)
      (when (< idle-ms (- now last-wanted))
        (stop! handle id name)))))

(defn sync!
  "Over the chats of the db: starts the projects that are wanted, the
  one on screen and the ones with recent changes, stops the ones that
  went or idled, notes which are ready, reloads changed sources,
  restarts the dead."
  [{:keys [db chats-dir procs] :as handle}]
  (let [current (set (for [id (keys (:chats @db))
                           name (names (chats/artifacts-dir chats-dir id))]
                       [id name]))
        now (System/currentTimeMillis)]
    (doseq [[id name] (keys @procs)
            :when (not (contains? current [id name]))]
      (stop! handle id name))
    (doseq [[id name] current
            :let [wanted? (wanted? handle id name now)]]
      (if (contains? @procs [id name])
        (do (check-ready! handle id name)
            (reload-changed! handle id name)
            (restart-exited! handle id name)
            (stop-idle! handle id name now wanted?))
        (when wanted?
          (start! handle id name))))))

(defn eval!
  "Evaluates `code` in the project's process."
  [{:keys [procs]} id name code]
  (if-let [{:keys [nrepl-port]} (get @procs [id name])]
    (nrepl/eval! nrepl-port code)
    {:ex (str "The project " name " is not running.")}))

;;; The proxy

(def ^:private skipped-request-headers
  #{"host" "content-length" "connection" "transfer-encoding" "expect" "upgrade"})

(def ^:private skipped-response-headers
  #{"transfer-encoding" "content-length" "connection"})

(defn- upstream-request
  [port rest {:keys [request-method query-string headers body]}]
  (let [url (str "http://127.0.0.1:" port "/" rest
                 (when (seq query-string) (str "?" query-string)))
        builder (java.net.http.HttpRequest/newBuilder (java.net.URI. url))
        builder (if (= :post request-method)
                  (.POST builder (java.net.http.HttpRequest$BodyPublishers/ofByteArray
                                  (if body (.readAllBytes body) (byte-array 0))))
                  (.GET builder))]
    (doseq [[k v] headers
            :when (not (skipped-request-headers (str/lower-case (str k))))]
      (.header builder (str k) (str v)))
    (.build builder)))

(defn- chunk
  [^bytes buffer n]
  (let [bytes (byte-array n)]
    (System/arraycopy buffer 0 bytes 0 n)
    (java.io.ByteArrayInputStream. bytes)))

(defn- pump!
  "Streams the upstream response into the channel: headers with the
  first bytes, every later chunk as it arrives, closed at the end."
  [response channel]
  (let [status (.statusCode response)
        headers (into {}
                      (for [[k vs] (.map (.headers response))
                            :when (not (skipped-response-headers (str/lower-case k)))]
                        [k (str/join ", " vs)]))
        in (.body response)
        buffer (byte-array 8192)]
    (try
      (loop [first? true]
        (let [n (.read in buffer)]
          (cond
            (and (neg? n) first?)
            (server/send! channel {:status status :headers headers :body ""} true)

            (neg? n)
            (server/close channel)

            first?
            (do (server/send! channel {:status status :headers headers :body (chunk buffer n)} false)
                (recur false))

            :else
            (do (server/send! channel (chunk buffer n) false)
                (recur false)))))
      (catch Exception _
        (server/close channel))
      (finally
        (.close in)))))

(defonce ^:private client
  (delay (java.net.http.HttpClient/newHttpClient)))

(defn- proxy!
  [port rest w]
  (let [request (:ring/request w)
        upstream (atom nil)]
    (assoc w :ring/response
           (server/as-channel request
                              {:on-open (fn [channel]
                                          (future
                                            (try
                                              (let [response (.send @client
                                                                    (upstream-request port rest request)
                                                                    (java.net.http.HttpResponse$BodyHandlers/ofInputStream))]
                                                (reset! upstream (.body response))
                                                (pump! response channel))
                                              (catch Exception e
                                                (server/send! channel {:status 502
                                                                       :headers {"Content-Type" "text/plain"}
                                                                       :body (str "The artifact did not answer: " (ex-message e))}
                                                              true)))))
                               :on-close (fn [_ _]
                                           (some-> @upstream .close))}))))

(defn- split-path
  "\"name/rest/of/it\" of the wildcard into [name \"rest/of/it\"]."
  [subpath]
  (let [[name & rest] (str/split (str subpath) #"/" 2)]
    [name (or (first rest) "")]))

(defn handler
  "The handler of /chat/<id>/artifacts/*, the rest of the path under
  `:*`: a running project's page and stream go to its process; anything
  else is left to the caller, without a response."
  [{:keys [procs db]} id w]
  (let [[name rest] (split-path (get-in w [:ring/route-params :*]))
        {:keys [port]} (get @procs [id name])]
    (if (and port (get-in @db [:chats id :projects name :ready-at]))
      (proxy! port rest w)
      w)))
