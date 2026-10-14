(ns pchat.hooks
  "An automation: a chat's webhook runs the script the agent wrote. The
  address `/hook/<chat-id>/<secret>` is outside the token; a call runs
  `hook.clj` in the chat's folder (`pchat.chats`) with the body of the
  request on its standard input, and what it printed, its exit and the
  artifacts it made are a run, shown in the chat and logged for the
  agent in `hook.log` beside the script. One run at a time per chat,
  the others wait.

  The secret of a chat lives in `.hook.edn` of its folder, `{:secret
  :created}`, its runs in `.runs.edn`, the last fifty.

  The keys this namespace owns in a chat of the db, and in the db:

      :hook  {:secret \"...\" :created ms}
      :runs  [{:n :at :method :bytes :exit :seconds :output :made} ...]
      :hook-open  the id of the chat whose webhook panel is open"
  (:require [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [pchat.chats :as chats]
            [rframes.http :as http]))

(def path-prefix
  "/hook")

(def run-timeout-ms
  "A script that runs longer is ended."
  (* 10 60 1000))

(def answer-wait-ms
  "How long a call waits for its run before it is told that the run
  goes on."
  25000)

(def kept-runs
  50)

(def max-body-bytes
  (* 1024 1024))

(def max-output-chars
  4000)

(defn handle
  "{:db the app's atom, :chats-dir the folder of the chats, :bb the
  babashka executable, :env extra environment for a run}"
  [{:keys [chats-dir] :as opts}]
  (assoc opts
         :chats-dir (io/file chats-dir)
         :procs (atom {})
         :locks (atom {})))

(defn chat-dir
  [{:keys [chats-dir]} id]
  (chats/dir chats-dir id))

(defn script-file
  [handle id]
  (io/file (chat-dir handle id) "hook.clj"))

(defn log-file
  [handle id]
  (io/file (chat-dir handle id) "hook.log"))

(defn- hook-file
  [handle id]
  (io/file (chat-dir handle id) ".hook.edn"))

(defn- runs-file
  [handle id]
  (io/file (chat-dir handle id) ".runs.edn"))

(defn- read-edn
  [file default]
  (if (.isFile file)
    (edn/read-string (slurp file))
    default))

(defn- write-edn!
  [file value]
  (io/make-parents file)
  (spit file (pr-str value)))

(defn load!
  "The hook and the runs of every chat in the db, from the chats'
  files."
  [{:keys [db] :as handle}]
  (doseq [id (keys (:chats @db))]
    (let [hook (read-edn (hook-file handle id) nil)
          runs (read-edn (runs-file handle id) [])]
      (swap! db update-in [:chats id]
             (fn [chat]
               (cond-> (assoc chat :runs runs)
                 hook (assoc :hook hook)
                 (not hook) (dissoc :hook)))))))

(defn- new-secret
  []
  (let [bytes (byte-array 16)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(def stub
  "The script of a fresh webhook: shows what arrived, for the agent to
  build on."
  "#!/usr/bin/env bb
;; The webhook of this chat runs this script: the body of the request is
;; on standard input, PCHAT_HOOK_METHOD, PCHAT_HOOK_QUERY and
;; PCHAT_HOOK_CONTENT_TYPE are in the environment. What it prints is the
;; output of the run, shown in the chat.
(require '[cheshire.core :as json])

(let [body (slurp *in*)
      data (try (json/parse-string body true) (catch Exception _ nil))]
  (println (str \"Received \" (count body) \" bytes\" (when data \" of JSON\")))
  (when data
    (println (pr-str data))))
")

(defn create!
  "The webhook of the chat, with a new secret; the script gets its stub
  when there is none yet."
  [{:keys [db] :as handle} id]
  (let [hook {:secret (new-secret)
              :created (System/currentTimeMillis)}
        script (script-file handle id)]
    (write-edn! (hook-file handle id) hook)
    (when-not (.isFile script)
      (io/make-parents script)
      (spit script stub)
      (.setExecutable script true false))
    (swap! db assoc-in [:chats id :hook] hook)
    hook))

(defn remove!
  "The address stops working; the script and the log stay. Everything
  of the webhook goes with the chat's folder when the chat is deleted."
  [{:keys [db] :as handle} id]
  (io/delete-file (hook-file handle id) true)
  (swap! db update-in [:chats id] dissoc :hook)
  nil)

(defn url
  [base-url id secret]
  (str base-url path-prefix "/" id "/" secret))

(defn prompt
  "What the agent is told about the webhook of its chat."
  []
  (str "This chat has a webhook, an address another service calls when something happened there, for example a new review, a form sent, a time of day: the person turns it on with the Webhook button and pastes the address into the service. A call runs the script `hook.clj` in this folder with the body of the request on standard input (JSON as a rule) and `PCHAT_HOOK_METHOD`, `PCHAT_HOOK_QUERY` and `PCHAT_HOOK_CONTENT_TYPE` in the environment; what it prints and the artifacts it makes are shown in the chat as a run. "
       "When the person wants something to happen by itself, write that script: plain babashka, deterministic, the same tools as you (`bin/render`, `bin/music`, `bin/image`, `bin/voice`), `bin/ask` for a small judgment of a model, data the person gives you in a plain file under `data/`. Try it yourself: `echo '{...}' | bb hook.clj`. "
       "The runs are in `hook.log`, the newest at the end, with the body each call brought: read it to see what the service sends, or why a run failed."))

(defn- lock-of
  [{:keys [locks]} id]
  (or (get @locks id)
      (get (swap! locks assoc id (Object.)) id)))

(defn- cut
  [text n]
  (let [text (str text)]
    (if (> (count text) n)
      (str (subs text 0 n) " …")
      text)))

(defn- made-since
  "The artifacts of the chat written since `since`."
  [{:keys [chats-dir]} id since]
  (let [dir (chats/artifacts-dir chats-dir id)]
    (->> (file-seq dir)
         (filter #(and (.isFile %) (<= since (.lastModified %))))
         (map #(str (.relativize (.toPath dir) (.toPath %))))
         (remove #(some (fn [part] (str/starts-with? part ".")) (str/split % #"/")))
         (sort)
         (vec))))

(defn- log!
  [handle id {:keys [n at method bytes exit seconds output]} body]
  (let [file (log-file handle id)]
    (io/make-parents file)
    (spit file
          (str "=== run " n ", " (str (java.time.Instant/ofEpochMilli at))
               ", " method ", " bytes " bytes\n"
               (cut body 20000) (when (seq body) "\n")
               "--- output\n" (cut output 20000) (when (seq output) "\n")
               "--- exit " exit " in " seconds " s\n\n")
          :append true)))

(defn- record!
  [{:keys [db] :as handle} id run]
  (let [runs (-> (get-in @db [:chats id :runs] [])
                 (conj run)
                 (->> (take-last kept-runs))
                 (vec))]
    (write-edn! (runs-file handle id) runs)
    (swap! db assoc-in [:chats id :runs] runs)
    run))

(defn- execute!
  "The script in the chat's folder with the body on its standard input;
  what came out, in at most ten minutes."
  [{:keys [chats-dir bb env procs] :as handle} id ^bytes body request]
  (let [p (process/process [(or bb "bb") "hook.clj"]
                           {:dir (str (chat-dir handle id))
                            :in (java.io.ByteArrayInputStream. body)
                            :out :string
                            :err :string
                            :extra-env (merge (or env {})
                                              {"TMPDIR" (str (chats/tmp-dir chats-dir id))
                                               "PCHAT_HOOK_METHOD" (str/upper-case (name (or (:request-method request) :post)))
                                               "PCHAT_HOOK_QUERY" (str (:query-string request))
                                               "PCHAT_HOOK_CONTENT_TYPE" (str (get-in request [:headers "content-type"]))})})]
    (swap! procs assoc id p)
    (try
      (if (.waitFor (:proc p) run-timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (let [{:keys [exit out err]} @p]
          {:exit exit
           :output (str out (when (seq err) (str (when (seq out) "\n") err)))})
        (do (process/destroy-tree p)
            {:exit -1
             :output (str (:out @p) "\nThe run took longer than ten minutes and was ended.")}))
      (finally
        (swap! procs dissoc id)))))

(defn run!
  "One run of the chat's script for a request, recorded and logged; the
  next call of the same chat waits for this one."
  [{:keys [db] :as handle} id ^bytes body request]
  (locking (lock-of handle id)
    (let [at (System/currentTimeMillis)
          n (inc (reduce max 0 (map :n (get-in @db [:chats id :runs]))))
          {:keys [exit output]} (if (.isFile (script-file handle id))
                                  (execute! handle id body request)
                                  {:exit -1 :output "There is no script for this webhook yet."})
          run {:n n
               :at at
               :method (str/upper-case (name (or (:request-method request) :post)))
               :bytes (count body)
               :exit exit
               :seconds (/ (Math/round (/ (- (System/currentTimeMillis) at) 100.0)) 10.0)
               :output (cut output max-output-chars)
               :made (made-since handle id at)}]
      (log! handle id run (String. body "UTF-8"))
      (record! handle id run))))

(defn- timing-safe=
  [a b]
  (java.security.MessageDigest/isEqual (.getBytes (str a) "UTF-8")
                                       (.getBytes (str b) "UTF-8")))

(defn- json-response
  [status value]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/generate-string value)})

(defn- read-body
  [request]
  (if-let [body (:body request)]
    (let [bytes (.readAllBytes body)]
      (if (> (count bytes) max-body-bytes)
        nil
        bytes))
    (byte-array 0)))

(defn handler
  "The call of /hook/<chat-id>/<secret>: the run's output when it is
  done within a moment, else word that it goes on."
  [{:keys [db] :as handle} w]
  (let [request (:ring/request w)
        [id secret] (str/split (str (get-in w [:ring/route-params :*])) #"/" 2)
        hook (get-in @db [:chats id :hook])
        body (when (and hook (timing-safe= (:secret hook) (str secret)))
               (read-body request))]
    (assoc w :ring/response
           (cond
             (not hook) http/not-found
             (not (timing-safe= (:secret hook) (str secret))) http/not-found
             (nil? body) (json-response 413 {:error "The body is larger than a megabyte."})
             :else (let [run (future (run! handle id body request))
                         result (deref run answer-wait-ms nil)]
                     (if result
                       (json-response (if (zero? (:exit result)) 200 500)
                                      (select-keys result [:n :exit :output :made]))
                       (json-response 202 {:status "running"})))))))

(defn register
  "The routes, outside the token: the secret in the address is the key."
  [handle]
  [{:ring/route [:post (str path-prefix "/*")]
    :ring/handler (fn [w] (handler handle w))}
   {:ring/route [:get (str path-prefix "/*")]
    :ring/handler (fn [w] (handler handle w))}])

(defn stop-all!
  [{:keys [procs]}]
  (doseq [[_ p] @procs]
    (process/destroy-tree p))
  (reset! procs {}))
