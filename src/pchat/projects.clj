(ns pchat.projects
  "An artifact with a process: a directory under artifacts/ with a bb.edn.
  pchat starts it (`bb -m artifact.main`, PORT and NREPL_PORT in its
  environment), shows its page in the iframe through a proxy under
  /artifacts/<name>/, reloads a source file into it over nREPL when the
  file changes, and writes its output to .log in the directory. The
  template under resources/pchat/template is the motion design project:
  rframes, fnmotion and its server-side player.

  The db key this namespace owns:

      :projects {name {:port :nrepl-port :ready-at :exit :reload}}"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [pchat.nrepl :as nrepl]))

(defn handle
  "{:db the app's atom, :dir the artifacts directory, :bb the babashka
  executable (\"bb\")}"
  [{:keys [dir bb] :as opts}]
  (assoc opts
         :dir (io/file dir)
         :bb (or bb "bb")
         :procs (atom {})))

(defn names
  "The artifacts that are projects: subdirectories with a bb.edn."
  [dir]
  (->> (.listFiles (io/file dir))
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

(defn- src-mtimes
  [project-dir]
  (into {}
        (for [file (file-seq (io/file project-dir "src"))
              :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
          [(str file) (.lastModified file)])))

(defn start!
  [{:keys [dir bb procs db]} name]
  (let [project-dir (io/file dir name)
        port (free-port)
        nrepl-port (free-port)
        log (io/file project-dir ".log")
        p (process/process [bb "-m" "artifact.main"]
                           {:dir (str project-dir)
                            :out log
                            :err log
                            :extra-env {"PORT" (str port)
                                        "NREPL_PORT" (str nrepl-port)
                                        "PCHAT_BASE" (str "/artifacts/" name)
                                        ;; The project exits by itself when
                                        ;; pchat is gone, hook or no hook.
                                        "PCHAT_PID" (str (.pid (java.lang.ProcessHandle/current)))}})]
    (swap! procs assoc name {:process p
                             :port port
                             :nrepl-port nrepl-port
                             :started (System/currentTimeMillis)
                             :mtimes (src-mtimes project-dir)})
    (swap! db assoc-in [:projects name] {:port port
                                         :nrepl-port nrepl-port
                                         :ready-at nil})
    name))

(defn stop!
  [{:keys [procs db]} name]
  (when-let [{:keys [process]} (get @procs name)]
    (process/destroy-tree process))
  (swap! procs dissoc name)
  (swap! db update :projects dissoc name))

(defn stop-all!
  [{:keys [procs] :as handle}]
  (doseq [name (keys @procs)]
    (stop! handle name)))

(defn- answers?
  [port]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (int port)) 200)
      true)
    (catch Exception _
      false)))

(defn- check-ready!
  [{:keys [db procs]} name]
  (let [{:keys [port process]} (get @procs name)]
    (when (and port (nil? (get-in @db [:projects name :ready-at])))
      (cond
        (not (.isAlive (:proc process)))
        (swap! db assoc-in [:projects name :exit] (:exit @process))

        (answers? port)
        (swap! db assoc-in [:projects name :ready-at] (System/currentTimeMillis))))))

(defn- reload-changed!
  "A source file the agent saved is loaded into the running process."
  [{:keys [dir db procs]} name]
  (let [{:keys [nrepl-port mtimes]} (get @procs name)
        now-mtimes (src-mtimes (io/file dir name))]
    (when (and (get-in @db [:projects name :ready-at])
               (not= mtimes now-mtimes))
      (swap! procs assoc-in [name :mtimes] now-mtimes)
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
            (swap! db assoc-in [:projects name :reload]
                   {:file (str (fs/relativize (io/file dir name) file))
                    :at (System/currentTimeMillis)
                    :error (or ex (when (seq err) err))})))))))

(defn- restart-exited!
  "A project that died comes back, not more often than every ten seconds."
  [{:keys [procs db] :as handle} name]
  (let [{:keys [process started]} (get @procs name)]
    (when (and process
               (not (.isAlive (:proc process)))
               (< 10000 (- (System/currentTimeMillis) started)))
      (stop! handle name)
      (start! handle name))))

(defn sync!
  "Starts the projects that appeared, stops the ones that went, notes
  which are ready, reloads changed sources, restarts the dead."
  [{:keys [dir procs] :as handle}]
  (let [current (set (names dir))]
    (doseq [name current
            :when (not (contains? @procs name))]
      (start! handle name))
    (doseq [name (keys @procs)
            :when (not (contains? current name))]
      (stop! handle name))
    (doseq [name current]
      (check-ready! handle name)
      (reload-changed! handle name)
      (restart-exited! handle name))))

(defn eval!
  "Evaluates `code` in the project's process."
  [{:keys [procs]} name code]
  (if-let [{:keys [nrepl-port]} (get @procs name)]
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
  "The handler of /artifacts/*: a running project's page and stream go
  to its process; anything else is left to the next route."
  [{:keys [procs db]} w]
  (let [[name rest] (split-path (get-in w [:ring/route-params :*]))
        {:keys [port]} (get @procs name)]
    (if (and port (get-in @db [:projects name :ready-at]))
      (proxy! port rest w)
      w)))

(defn register
  [handle]
  [{:ring/route [:get "/artifacts/*"]
    :ring/handler (fn [w] (handler handle w))}
   {:ring/route [:post "/artifacts/*"]
    :ring/handler (fn [w] (handler handle w))}])
