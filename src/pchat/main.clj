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
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [pchat.artifacts :as artifacts]
            [pchat.chat :as chat]
            [pchat.looks :as looks]
            [pchat.pi :as pi]
            [pchat.projects :as projects]
            [pchat.session :as session]
            [pchat.view :as view]
            [rchat.auth :as auth]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(def workspace
  (io/file (or (System/getenv "PCHAT_WORKSPACE") "./workspace")))

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

(def template-files
  "The motion project template and the scripts, copied into the
  workspace at start: .pchat/template/motion/ and bin/."
  ["template/motion/bb.edn"
   "template/motion/design.edn"
   "template/motion/README.md"
   "template/motion/src/artifact/main.clj"
   "bin/new"
   "bin/eval"])

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
  (let [summaries (map session/summary (session/files sessions-dir))]
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
  first time."
  [id]
  (swap! db (fn [db]
              (let [{:keys [loaded? file]} (get-in db [:chats id])]
                (cond-> (assoc db :current id)
                  (and (not loaded?) file)
                  (update-in [:chats id] merge (session/load-chat (io/file file) intern-message)))))))

(defn- on-event
  [id event]
  (when (= "exited" (:type event))
    (swap! pis dissoc id))
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
                               :on-event #(on-event id %)})]
        (swap! pis assoc id handle)
        handle)))

(defn- send-command
  [w]
  (let [{:keys [text images]} (command/command-data w)
        text (str/trim (str text))
        images (looks/blocks looks-dir (when (sequential? images)
                                         (keep :name images)))
        id (:current @db)]
    (if (and (str/blank? text) (empty? images))
      (command/rejected w "Nothing to send.")
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
    (swap! db #(-> % (assoc-in [:chats id] (new-chat id)) (assoc :current id)))
    (command/accepted w)))

(defn- open-command
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (if (get-in @db [:chats id])
      (do (open! id)
          (command/accepted w))
      (command/rejected w "No such chat."))))

(defn- open-artifact-command
  [w]
  (let [{:keys [path]} (command/command-data w)]
    (if (some #(= path (:path %)) (:artifacts/list @db))
      (do (artifacts/pick! artifacts path)
          (command/accepted w))
      (command/rejected w "No such artifact."))))

(defn page-view
  [_w]
  (let [value @db]
    (view/page value {:key-missing? key-missing?
                      :project (fn [{:keys [path]}]
                                 (get-in value [:projects path]))})))

(def head
  (str "<title>pchat</title>"
       "<link rel=\"icon\" href=\"data:,\">"
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
   {:command/kind :artifacts/open
    :command/fn #'open-artifact-command}])

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
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(projects/stop-all! projects)))
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
                  ", the workspace " (.getCanonicalPath workspace))))
  @(promise))
