(ns pchat.main
  "The server: many chats, one pi process each, the page over rframes.

      PORT             default 8080
      PCHAT_WORKSPACE  the folder the agent works in, default ./workspace
      PCHAT_PROVIDER   default openrouter (OPENROUTER_API_KEY in the environment)
      PCHAT_MODEL      default openai/gpt-6.1-sol
      PCHAT_TOKEN      a secret: the page is behind the link that sets the cookie
      BB               the babashka executable for the artifact projects, default bb

  The app value: {:chats {id chat} :current id}. A chat is the value of
  `pchat.chat` plus :id, :name, :created, :updated, :file and :loaded?;
  the list comes from pi's session files, the history of a chat is read
  from its file when it is opened."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [pchat.artifacts :as artifacts]
            [pchat.chat :as chat]
            [pchat.i18n :as i18n]
            [pchat.looks :as looks]
            [pchat.pi :as pi]
            [pchat.projects :as projects]
            [pchat.render :as render]
            [pchat.session :as session]
            [pchat.tools :as tools]
            [pchat.view :as view]
            [rchat.auth :as auth]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(def workspace
  ;; Absolute: pi and the artifact projects run inside it and resolve
  ;; relative paths against their own directory.
  (.getAbsoluteFile (io/file (or (System/getenv "PCHAT_WORKSPACE") "./workspace"))))

(def sessions-dir
  (io/file workspace ".pchat" "sessions"))

(def provider
  (or (System/getenv "PCHAT_PROVIDER") "openrouter"))

(def model
  (or (System/getenv "PCHAT_MODEL") "openai/gpt-6.1-sol"))

(def key-missing?
  (and (= "openrouter" provider)
       (str/blank? (System/getenv "OPENROUTER_API_KEY"))))

(defonce db
  (atom {:chats {}
         :current nil}))

;; The pi process handles are not values, so they live beside the db,
;; one per chat id.
(defonce pis
  (atom {}))

(def artifacts
  (artifacts/handle {:db db
                     :dir (io/file workspace "artifacts")}))

(def looks-dir
  (io/file workspace ".pchat" "looks"))

(def projects
  (projects/handle {:db db
                    :dir (io/file workspace "artifacts")
                    :bb (or (System/getenv "BB") "bb")}))

(def renders
  (render/handle {:db db
                  :workspace workspace
                  :bb (or (System/getenv "BB") "bb")
                  :env (tools/env)}))

(def template-files
  "The project templates and the scripts, copied into the workspace at
  start: .pchat/template/motion/, .pchat/template/hyperframes/ and bin/."
  ["template/motion/bb.edn"
   "template/motion/design.edn"
   "template/motion/README.md"
   "template/motion/src/artifact/main.clj"
   "template/hyperframes/bb.edn"
   "template/hyperframes/README.md"
   "template/hyperframes/src/artifact/main.clj"
   "template/hyperframes/composition/index.html"
   "bin/new"
   "bin/eval"
   "bin/render"
   "bin/music"])

(defn- install-workspace!
  "The template and the scripts the agent uses, always the ones of this
  build."
  []
  (doseq [path template-files]
    (let [target (if (str/starts-with? path "bin/")
                   (io/file workspace path)
                   (io/file workspace ".pchat" path))]
      (io/make-parents target)
      (spit target (slurp (io/resource (str "pchat/" path))))
      (when (str/starts-with? path "bin/")
        (.setExecutable target true false)))))

(defn- sync-projects!
  "Starts, watches and reloads the artifact projects, once a second."
  []
  (future
    (loop []
      (try
        (projects/sync! projects)
        (catch Exception e
          (binding [*out* *err*]
            (println "[projects]" (str e)))))
      (Thread/sleep 1000)
      (recur))))

(defn- intern-message
  [message]
  (looks/intern-message looks-dir message))

(defn- now
  []
  (System/currentTimeMillis))

(def names-file
  "The names people gave their chats, {id name}; a chat without one is
  named after its first message."
  (io/file workspace ".pchat" "names.edn"))

(defn- read-names
  []
  (if (.isFile names-file)
    (edn/read-string (slurp names-file))
    {}))

(defn- write-names!
  [names]
  (io/make-parents names-file)
  (spit names-file (pr-str names)))

(defn new-chat
  [id]
  (merge chat/empty-chat
         {:id id
          :created (now)
          :updated (now)
          :loaded? true}))

(defn- load-chats!
  "The chats of the session files into the db, without their items, and
  a fresh chat as the current one when there is none."
  []
  (let [names (read-names)
        summaries (map (fn [{:keys [id] :as summary}]
                         (cond-> summary
                           (get names id) (assoc :name (get names id))))
                       (map session/summary (session/files sessions-dir)))]
    (swap! db (fn [db]
                (let [chats (into (:chats db)
                                  (for [{:keys [id] :as summary} summaries
                                        :when (not (get-in db [:chats id :loaded?]))]
                                    [id (merge chat/empty-chat summary)]))
                      current (or (:current db)
                                  (:id (first summaries)))]
                  (assoc db :chats chats :current current))))
    (when-not (:current @db)
      (let [id (str (random-uuid))]
        (swap! db #(-> % (assoc-in [:chats id] (new-chat id)) (assoc :current id)))))))

(defn- open!
  "Makes the chat current, its history read from the session file the
  first time; the name the person gave it stays."
  [id]
  (swap! db (fn [db]
              (let [{:keys [loaded? file name]} (get-in db [:chats id])]
                (cond-> (assoc db :current id)
                  (and (not loaded?) file)
                  (update-in [:chats id] merge (session/load-chat (io/file file) intern-message)
                             (when (get (read-names) id) {:name name})))))))

(defn- on-event
  [id event]
  (if (= "exited" (:type event))
    (swap! pis dissoc id)
    (swap! pis update id #(some-> % (assoc :last-event (now)))))
  (let [event (cond-> event
                (:message event) (update :message intern-message))]
    (swap! db (fn [db]
                (-> db
                    (update-in [:chats id] chat/event event)
                    (assoc-in [:chats id :updated] (now))))))
  ;; A tool just ran: what it wrote is on screen right away, the poll
  ;; catches the rest.
  (when (#{"tool_execution_end" "agent_settled"} (:type event))
    (artifacts/refresh! artifacts)))

(defn- poll-artifacts!
  "Looks at the artifacts folder twice a second: the agent writes with
  tools, but a command can write files too."
  []
  (future
    (loop []
      (Thread/sleep 500)
      (try
        (artifacts/refresh! artifacts)
        (catch Exception e
          (binding [*out* *err*]
            (println "[artifacts]" (str e)))))
      (recur))))

(defn- ensure-pi!
  [id]
  (or (get @pis id)
      (let [handle (pi/spawn! {:dir workspace
                               :session-dir sessions-dir
                               :session-id id
                               :provider provider
                               :model model
                               :system-prompt (slurp (io/resource "pchat/prompts/system.md"))
                               :env (tools/env)
                               :on-event #(on-event id %)})]
        (swap! pis assoc id (assoc handle :last-event (now)))
        handle)))

(def pi-idle-ms
  "A pi that did nothing for this long is stopped; the next message
  starts it again on the same session."
  (* 15 60 1000))

(defn- reap-idle-pis!
  "Once a minute: the pi processes of chats that idle are ended."
  []
  (future
    (loop []
      (Thread/sleep 60000)
      (try
        (doseq [[id {:keys [last-event] :as handle}] @pis
                :when (and (= :idle (get-in @db [:chats id :status]))
                           (< pi-idle-ms (- (now) (or last-event 0))))]
          (pi/kill! handle))
        (catch Exception e
          (binding [*out* *err*]
            (println "[pis]" (str e)))))
      (recur))))

(defn- say
  "A text of the page in the language of the request."
  [w text]
  (i18n/translate text (i18n/locale (:ring/request w))))

(defn- send-command
  [w]
  (let [{:keys [text images]} (command/command-data w)
        text (str/trim (str text))
        images (looks/blocks looks-dir (when (sequential? images)
                                         (keep :name images)))
        id (:current @db)]
    (if (and (str/blank? text) (empty? images))
      (command/rejected w (say w [:i18n :command/nothing-to-send]))
      (let [p (ensure-pi! id)
            streaming? (= :working (get-in @db [:chats id :status]))]
        (pi/prompt! p (if (str/blank? text) "Look at this." text) streaming? images)
        (command/accepted w)))))

(defn- abort-command
  [w]
  (when-let [p (get @pis (:current @db))]
    (pi/abort! p))
  (command/accepted w))

(defn- new-command
  [w]
  (let [id (str (random-uuid))]
    (swap! db #(-> % (assoc-in [:chats id] (new-chat id)) (assoc :current id) (dissoc :deleting)))
    (command/accepted w)))

(defn- open-command
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (if (get-in @db [:chats id])
      (do (open! id)
          (swap! db dissoc :deleting)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-chat])))))

(defn- rename-command
  [w]
  (let [{:keys [id name]} (command/command-data w)
        name (str/trim (str name))]
    (if (get-in @db [:chats id])
      (do (if (str/blank? name)
            (do (write-names! (dissoc (read-names) id))
                (swap! db update-in [:chats id] dissoc :name))
            (do (write-names! (assoc (read-names) id name))
                (swap! db assoc-in [:chats id :name] name)))
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-chat])))))

(defn- delete!
  "The chat is gone: its pi ended, its session file and name deleted,
  the newest other chat current, a fresh one when there is none."
  [id]
  (when-let [handle (get @pis id)]
    (pi/kill! handle)
    (swap! pis dissoc id))
  (when-let [file (get-in @db [:chats id :file])]
    (io/delete-file (io/file file) true))
  (write-names! (dissoc (read-names) id))
  (swap! db (fn [db]
              (let [chats (dissoc (:chats db) id)
                    current (if (= id (:current db))
                              (:id (first (sort-by #(or (:updated %) 0) > (vals chats))))
                              (:current db))]
                (-> db
                    (assoc :chats chats :current current)
                    (dissoc :deleting)))))
  (when-not (:current @db)
    (let [id (str (random-uuid))]
      (swap! db #(-> % (assoc-in [:chats id] (new-chat id)) (assoc :current id)))))
  (open! (:current @db)))

(defn- delete-command
  "The first call arms the deletion, the second deletes."
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (cond
      (not (get-in @db [:chats id])) (command/rejected w (say w [:i18n :command/no-such-chat]))
      (= id (:deleting @db)) (do (delete! id) (command/accepted w))
      :else (do (swap! db assoc :deleting id) (command/accepted w)))))

(defn- keep-command
  [w]
  (swap! db dissoc :deleting)
  (command/accepted w))

(defn- open-artifact-command
  [w]
  (let [{:keys [path]} (command/command-data w)]
    (if (some #(= path (:path %)) (:artifacts/list @db))
      (do (artifacts/pick! artifacts path)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-artifact])))))

(defn- render-command
  [w]
  (let [{:keys [path]} (command/command-data w)]
    (if (some #(and (= path (:path %)) (= :project (:kind %))) (:artifacts/list @db))
      (do (render/start! renders path)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-artifact])))))

(defn page-view
  "The page in the language of the connection."
  [w]
  (let [value @db
        locale (i18n/locale (:ring/request w))]
    (-> (view/page value {:key-missing? key-missing?
                          :locale locale
                          :project (fn [{:keys [path]}]
                                     (assoc (get-in value [:projects path])
                                            :render (get-in value [:renders path])))})
        (i18n/translate locale))))

(def head
  (str "<title>pchat</title>"
       "<link rel=\"icon\" href=\"data:,\">"
       ;; The shim's <html lang> is static; the page carries the language
       ;; of the connection on .pchat, and this moves it up.
       "<script>new MutationObserver(function () {"
       " var p = document.querySelector('.pchat[lang]');"
       " if (p && document.documentElement.lang !== p.lang) document.documentElement.lang = p.lang;"
       "}).observe(document.documentElement, {subtree: true, childList: true, attributes: true, attributeFilter: ['lang']});</script>"
       "<link rel=\"stylesheet\" href=\"/rchat.css\">"
       "<link rel=\"stylesheet\" href=\"/pchat.css\">"
       "<script defer src=\"/js/main.js\"></script>"))

(def register
  [(http/resources "/rchat.css" "pchat/chat.css")
   (http/resources "/pchat.css" "pchat/pchat.css")
   (http/resources "/js/*" "public/js")
   {:replicant/shim "/"
    :head head}
   {:replicant/render "/"
    :render/fn #'page-view}
   {:command/kind :chat/send
    :command/fn #'send-command}
   {:command/kind :chat/abort
    :command/fn #'abort-command}
   {:command/kind :chat/new
    :command/fn #'new-command}
   {:command/kind :chat/open
    :command/fn #'open-command}
   {:command/kind :chat/rename
    :command/fn #'rename-command}
   {:command/kind :chat/delete
    :command/fn #'delete-command}
   {:command/kind :chat/keep
    :command/fn #'keep-command}
   {:command/kind :artifacts/open
    :command/fn #'open-artifact-command}
   {:command/kind :artifacts/render
    :command/fn #'render-command}])

(defn get-register
  []
  ;; The project proxy before the files: it answers only for a running
  ;; project, the files route takes the rest.
  (replicant/expand (concat register
                            command/register
                            (projects/register projects)
                            (artifacts/register artifacts)
                            (looks/register {:db db
                                             :looks-dir looks-dir}))))

(defn -main
  [& _args]
  (.mkdirs sessions-dir)
  (.mkdirs (:dir artifacts))
  (.mkdirs looks-dir)
  (install-workspace!)
  (load-chats!)
  (open! (:current @db))
  (artifacts/refresh! artifacts)
  (poll-artifacts!)
  (sync-projects!)
  (reap-idle-pis!)
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(do (projects/stop-all! projects)
                                                       (render/stop-all! renders))))
  (sse/watch! db)
  (let [port (parse-long (or (System/getenv "PORT") "8080"))
        token (System/getenv "PCHAT_TOKEN")]
    (server/run-server (auth/wrap (http/ring-handler get-register) {:token token})
                       {:port port})
    (println (str "pchat on "
                  (if token
                    (auth/link (str "http://localhost:" port) token)
                    (str "http://localhost:" port))
                  ", " (count (:chats @db)) " chats, the model is " provider "/" model
                  ", the workspace " (.getCanonicalPath workspace)
                  ", " (tools/report))))
  @(promise))
