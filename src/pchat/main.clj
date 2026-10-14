(ns pchat.main
  "The server: many chats, one pi process each, the page over rframes.

      PORT             default 8080
      PCHAT_WORKSPACE  the folder the agent works in, default ./workspace
      PCHAT_PROVIDER   default openrouter (OPENROUTER_API_KEY in the environment)
      PCHAT_MODEL      default openai/gpt-6.1-sol
      PCHAT_TOKEN      a secret: the page is behind the link that sets the cookie
      PCHAT_AGENTITOS  an agentitos checkout: its skills are the tasks a chat can be for
      BB               the babashka executable for the artifact projects, default bb

  The app value: {:chats {id chat} :current id :skills [skill]}. A chat
  is the value of `pchat.chat` plus :id, :name, :task (the name of its
  skill, :free, or nil while the person has not picked one), :created,
  :updated, :file and :loaded?;
  the list comes from pi's session files, the history of a chat is read
  from its file when it is opened."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [pchat.artifacts :as artifacts]
            [pchat.chat :as chat]
            [pchat.hooks :as hooks]
            [pchat.i18n :as i18n]
            [pchat.looks :as looks]
            [pchat.mcp :as mcp]
            [pchat.pi :as pi]
            [pchat.projects :as projects]
            [pchat.render :as render]
            [pchat.session :as session]
            [pchat.setup :as setup]
            [pchat.update :as update]
            [pchat.share :as share]
            [pchat.skills :as skills]
            [pchat.tools :as tools]
            [pchat.uploads :as uploads]
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

(def uploads-dir
  "The clips the person attaches, where the agent finds them:
  `uploads/<name>` in its working directory."
  (io/file workspace "uploads"))

(def projects
  (projects/handle {:db db
                    :dir (io/file workspace "artifacts")
                    :bb (or (System/getenv "BB") "bb")}))

(def renders
  (render/handle {:db db
                  :workspace workspace
                  :bb (or (System/getenv "BB") "bb")
                  :env (tools/env)
                  ;; The finished video is news now, the poll alone would
                  ;; not notice: the file stopped changing before.
                  :on-finish (fn [_name] (artifacts/refollow! artifacts))}))

(defn- idle?
  "No chat works and no video renders: the moment the process may end."
  []
  (and (every? #(= :idle (:status %)) (vals (:chats @db)))
       (not (render/any-running? renders))))

(def updater
  (update/handle {:db db
                  :idle? idle?}))

(def setup
  (setup/handle {:db db
                 :extra (fn [] {:version (:current updater) :idle (idle?)})
                 :env (tools/env)}))

(def hooks
  (hooks/handle {:db db
                 :workspace workspace
                 :bb (or (System/getenv "BB") "bb")
                 :env (tools/env)
                 :artifacts (io/file workspace "artifacts")}))

(def shares
  (share/handle {:db db
                 :dir (io/file workspace "artifacts")
                 :file (io/file workspace ".pchat" "shares.edn")}))

(declare restart-pis!)

(def connections
  (mcp/handle {:db db
               :workspace workspace
               :on-change (fn [] (restart-pis!))}))

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
   "bin/music"
   "bin/image"
   "bin/voice"
   "bin/ask"
   "bin/mcp"])

(def lib-files
  "Namespaces of pchat the scripts load from .pchat/lib: `bin/mcp` edits
  the connections with the same code as the page."
  ["mcp.clj"])

(def retired-files
  "Scripts earlier builds installed that are gone: the kinetic engine is a
  skill now."
  ["bin/kinetic"])

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
        (.setExecutable target true false))))
  (doseq [path lib-files]
    (let [target (io/file workspace ".pchat" "lib" "pchat" path)]
      (io/make-parents target)
      (spit target (slurp (io/resource (str "pchat/" path))))))
  (doseq [path retired-files]
    (io/delete-file (io/file workspace path) true)))

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

(def tasks-file
  "The task each chat was started for, {id skill-name-or-:free}."
  (io/file workspace ".pchat" "tasks.edn"))

(defn- read-tasks
  []
  (if (.isFile tasks-file)
    (edn/read-string (slurp tasks-file))
    {}))

(defn- write-tasks!
  [tasks]
  (io/make-parents tasks-file)
  (spit tasks-file (pr-str tasks)))

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
        tasks (read-tasks)
        summaries (map (fn [{:keys [id] :as summary}]
                         (cond-> summary
                           (get names id) (assoc :name (get names id))
                           (get tasks id) (assoc :task (get tasks id))))
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
    ;; The handle of the process that ended, not one spawned since.
    (swap! pis (fn [pis] (if (some-> (get pis id) pi/alive?) pis (dissoc pis id))))
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
    (artifacts/refresh! artifacts))
  ;; The connections changed while the agent worked: its process ends
  ;; now that it is done, the next message starts one that has them.
  (when (and (= "agent_settled" (:type event))
             (get-in @pis [id :restart?]))
    (when-let [handle (get @pis id)]
      (pi/kill! handle)
      (swap! pis dissoc id))))

(defn- restart-pis!
  "The chats' processes read .pi/mcp.json when they start, so after a
  change the idle ones end here and the working ones when they settle;
  the next message starts each again on its session."
  []
  (doseq [[id handle] @pis]
    (if (= :working (get-in @db [:chats id :status]))
      (swap! pis assoc-in [id :restart?] true)
      (do (pi/kill! handle)
          (swap! pis dissoc id)))))

(defn- watch-connections!
  "Once a second: the file the agent's script edits is read again and
  the processes restarted, as after a change on the page."
  []
  (future
    (loop []
      (Thread/sleep 1000)
      (try
        (when (mcp/changed? connections)
          (mcp/load! connections)
          (restart-pis!))
        (catch Exception e
          (binding [*out* *err*]
            (println "[connections]" (str e)))))
      (recur))))

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

(defn- system-prompt
  "system.md, the skills paragraph (the chat's task in full, see
  `pchat.skills/prompt`), the integrations paragraph when a connection
  with Composio's connection tools exists (a Storrito agent's own), and
  the chat's webhook paragraph."
  [id]
  (str (slurp (io/resource "pchat/prompts/system.md"))
       (some->> (skills/prompt (:skills @db) (get-in @db [:chats id :task]))
                (str "\n\n"))
       (when-let [names (seq (mcp/composio-connections connections))]
         (str "\n\n" (str/replace (slurp (io/resource "pchat/prompts/integrations.md"))
                                  "<connection>" (str/join ", " names))))
       "\n\n" (hooks/prompt id)))

(defn- ensure-pi!
  [id]
  (or (get @pis id)
      (let [{:keys [skills]} @db
            handle (pi/spawn! {:dir workspace
                               :session-dir sessions-dir
                               :session-id id
                               :provider provider
                               :model model
                               :system-prompt (system-prompt id)
                               :skills (skills/paths skills)
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

(defn- send!
  "The text and the images to the current chat's agent."
  [text images]
  (let [id (:current @db)
        p (ensure-pi! id)
        streaming? (= :working (get-in @db [:chats id :status]))]
    (pi/prompt! p text streaming? images)))

(defn- keep!
  "The message of a chat whose agent is not there yet: kept in the
  chat with the answer that says so, nothing is sent. Its clips show as
  in a sent message."
  [text image-urls message]
  (let [id (:current @db)
        [user note] (setup/refusal-items text image-urls message)
        user (assoc user
                    :text (uploads/without-lines text)
                    :media (uploads/attachments text))]
    (swap! db (fn [db]
                (-> db
                    (update-in [:chats id :items] into [user note])
                    (assoc-in [:chats id :updated] (now)))))))

(defn- clip-lines
  "A line for each attached clip that is in the uploads folder, naming
  its path in the workspace, in the language of the page:
  \"(Attached video: uploads/ab12.mp4)\"."
  [w names]
  (for [name names
        :let [kind (uploads/kind name)]
        :when (and kind
                   (re-matches #"[A-Za-z0-9]+\.[a-z0-9]+" (str name))
                   (.isFile (io/file uploads-dir (str name))))]
    (let [path (str "uploads/" name)]
      (say w (if (= :audio kind)
               [:i18n :upload/attached-audio {:path path}]
               [:i18n :upload/attached-video {:path path}])))))

(defn- send-command
  [w]
  (let [{:keys [text images]} (command/command-data w)
        names (when (sequential? images)
                (keep :name images))
        text (str/trim (str text))
        clips (clip-lines w names)
        images (looks/blocks looks-dir (remove uploads/kind names))
        message (str/trim (cond
                            (seq clips) (str/join "\n" (cons text clips))
                            (str/blank? text) "Look at this."
                            :else text))]
    (cond
      (and (str/blank? text) (empty? images) (empty? clips))
      (command/rejected w (say w [:i18n :command/nothing-to-send]))

      ;; pi is still being installed on a fresh machine: the message
      ;; waits in the chat with the reason, see `pchat.setup`.
      (not (setup/ready? (setup/refresh! setup)))
      (do (keep! message
                 (map #(str "/looks/" %) (remove uploads/kind names))
                 (setup/not-ready-message (:pi (:setup @db))))
          (command/accepted w))

      :else
      (do (send! message images)
          (command/accepted w)))))

(defn- abort-command
  [w]
  (when-let [p (get @pis (:current @db))]
    (pi/abort! p))
  (command/accepted w))

(defn- new!
  "A fresh chat, the current one, the page back on the chat."
  []
  (let [id (str (random-uuid))]
    (swap! db #(-> % (assoc-in [:chats id] (new-chat id)) (assoc :current id) (dissoc :deleting :hook-open :settings-open)))
    id))

(defn- new-command
  [w]
  (new!)
  (command/accepted w))

(defn- known-task
  "The task a name stands for: the name of a skill there is, :free for
  \"free\", else nil."
  [task]
  (let [task (some-> task str not-empty)]
    (cond
      (= "free" task) :free
      (skills/by-name (:skills @db) task) task)))

(defn- unstarted?
  "Whether nothing has happened in the chat yet: no message, no session
  file, no pi. Only then can its task still be chosen."
  [id]
  (let [chat (get-in @db [:chats id])]
    (and chat
         (empty? (:items chat))
         (not (:file chat))
         (not (get @pis id)))))

(defn- set-task!
  "The task of the chat, none again with nil."
  [id task]
  (if task
    (do (write-tasks! (assoc (read-tasks) id task))
        (swap! db assoc-in [:chats id :task] task))
    (do (write-tasks! (dissoc (read-tasks) id))
        (swap! db update-in [:chats id] dissoc :task))))

(defn- task-command
  "The task of a new chat: a skill by its name, or :free. Only before
  the first message, while the chat's pi has not started."
  [w]
  (let [{:keys [id task]} (command/command-data w)
        task (known-task task)
        chat (get-in @db [:chats id])]
    (cond
      (not chat) (command/rejected w (say w [:i18n :command/no-such-chat]))
      (not (unstarted? id)) (command/rejected w (say w [:i18n :command/task-fixed]))
      (not task) (command/rejected w (say w [:i18n :command/no-such-task]))
      :else (do (set-task! id task)
                (command/accepted w)))))

(defn- task-link
  "GET /task/<skill> (or /task/free): the way in from elsewhere, e.g. a
  button on Storrito, to a chat for that task without the picker. The
  path survives the redirect of a token link (`/task/<skill>?token=…`),
  a query would not. The current chat is taken while nothing has
  happened in it, so that opening the link twice gives one chat; else a
  fresh one. An unknown task leaves the choice to the picker. Then on
  to the page."
  [w]
  (let [task (known-task (get-in w [:ring/route-params :*]))
        current (:current @db)
        id (if (and current (unstarted? current))
             (do (swap! db dissoc :deleting :hook-open :settings-open)
                 current)
             (new!))]
    (set-task! id task)
    (assoc w :ring/response {:status 302
                             :headers {"Location" "/"
                                       "Cache-Control" "no-store"}
                             :body ""})))

(defn- open-command
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (if (get-in @db [:chats id])
      (do (open! id)
          (swap! db dissoc :deleting :hook-open :settings-open)
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
  (write-tasks! (dissoc (read-tasks) id))
  (hooks/forget! hooks id)
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

(defn- hook-toggle-command
  "The webhook panel of the chat, shown or hidden."
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (swap! db (fn [db] (if (= id (:hook-open db)) (dissoc db :hook-open) (assoc db :hook-open id))))
    (command/accepted w)))

(defn- hook-create-command
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (if (get-in @db [:chats id])
      (do (hooks/create! hooks id)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-chat])))))

(defn- hook-remove-command
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (hooks/remove! hooks id)
    (command/accepted w)))

(defn- share-command
  [w]
  (let [{:keys [path]} (command/command-data w)]
    (if (some #(and (= path (:path %)) (not= :project (:kind %))) (:artifacts/list @db))
      (do (share/share! shares path)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-artifact])))))

(defn- unshare-command
  [w]
  (let [{:keys [path]} (command/command-data w)]
    (share/unshare! shares path)
    (command/accepted w)))

(defn- render-stop-command
  [w]
  (let [{:keys [path]} (command/command-data w)]
    (render/stop! renders path)
    (command/accepted w)))

(defn- update-start-command
  "The person takes the newer pchat: see `pchat.update`."
  [w]
  (update/update! updater)
  (command/accepted w))

(defn- update-check-command
  "The person asks for a lookup of the newest pchat right now
  (Settings, Updates); the daily one keeps its rhythm."
  [w]
  (future (update/check-now! updater))
  (command/accepted w))

(defn- settings-open-command
  "The settings in place of the chat; the chat stays current."
  [w]
  (swap! db #(-> % (assoc :settings-open true) (dissoc :deleting)))
  (command/accepted w))

(defn- settings-close-command
  [w]
  (swap! db dissoc :settings-open)
  (command/accepted w))

(defn- settings-tab-command
  "`:tab`, the id of one of `pchat.view/tabs`; another name is ignored."
  [w]
  (let [{:keys [tab]} (command/command-data w)
        tab (keyword (str tab))]
    (when (some #(= tab (:id %)) view/tabs)
      (swap! db assoc :settings-tab tab))
    (command/accepted w)))

(defn- parse-secret
  "The access line of the form: a header, `Authorization: Bearer …`, for
  an address, `NAME=value` for a program's environment; {} when empty,
  nil when it is neither."
  [line {:keys [url]}]
  (let [line (str/trim (str line))]
    (cond
      (str/blank? line) {}
      (and url (mcp/parse-header line)) {:headers (into {} [(mcp/parse-header line)])}
      (and (not url) (re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" line)) {:env (into {} [(str/split line #"=" 2)])}
      :else nil)))

(defn- connections-problem
  "The sentence of the page for what `pchat.mcp/check` found; the keys
  are literal so that the dictionaries are checked against them."
  [error]
  (case error
    :connections/bad-name [:i18n :connections/bad-name]
    :connections/bad-secret [:i18n :connections/bad-secret]
    :connections/exists [:i18n :connections/exists]
    [:i18n :connections/bad-address]))

(defn- connections-add-command
  "A connection from the form; tested right away, so that the panel
  shows its tools."
  [w]
  (let [{:keys [name address secret exposure]} (command/command-data w)
        name (str/trim (str name))
        address (mcp/parse-address address)
        secret (parse-secret secret address)
        spec (merge address secret {:exposure (not-empty (str exposure))})
        result (if secret
                 (mcp/add! connections name spec)
                 {:error :connections/bad-secret})]
    (if-let [error (:error result)]
      (command/rejected w (say w (connections-problem error)))
      (do (future (mcp/test! connections name))
          (command/accepted w)))))

(defn- connections-test-command
  [w]
  (let [{:keys [name]} (command/command-data w)]
    (future (mcp/test! connections name))
    (command/accepted w)))

(defn- connections-enable-command
  [w]
  (let [{:keys [name enabled?]} (command/command-data w)]
    (mcp/set-enabled! connections name (boolean enabled?))
    (command/accepted w)))

(defn- connections-remove-command
  [w]
  (let [{:keys [name]} (command/command-data w)]
    (mcp/remove! connections name)
    (command/accepted w)))

(defn- connections-agent-command
  "The person names a service, the agent finds out how it is reached
  and sets it up: a message to the current chat, a fresh one when there
  is none, in the person's language; the settings close so that the
  person sees the agent at work."
  [w]
  (let [{:keys [service]} (command/command-data w)
        service (str/trim (str service))]
    (when-not (get-in @db [:chats (:current @db)])
      (new!))
    (send! (say w (if (str/blank? service)
                    [:i18n :connections/agent-message-blank]
                    [:i18n :connections/agent-message {:service service}]))
           nil)
    (swap! db dissoc :settings-open)
    (command/accepted w)))

(defn page-view
  "The page in the language of the connection."
  [w]
  (let [value @db
        locale (i18n/locale (:ring/request w))
        base-url (share/base-url (:ring/request w))]
    (-> (view/page value {:key-missing? key-missing?
                          :locale locale
                          :project (fn [{:keys [path]}]
                                     (assoc (get-in value [:projects path])
                                            :render (get-in value [:renders path])))
                          :share (fn [path]
                                   (some->> (get-in value [:shares path])
                                            (share/link base-url)))
                          :hook-url (fn [{:keys [id hook]}]
                                      (when hook
                                        (hooks/url base-url id (:secret hook))))})
        (i18n/translate locale))))

(def head
  (str "<title>pchat</title>"
       "<link rel=\"icon\" href=\"data:,\">"
       ;; The scheme of the page before the first paint: the person's
       ;; choice from localStorage (`:ui/theme` in pchat.client writes
       ;; it), light without one. The class on <html> is outside
       ;; Replicant's root, so a frame never undoes it.
       "<script>(function () {"
       " var t; try { t = localStorage.getItem('pchat-theme'); } catch (e) {}"
       " if (t !== 'dark' && t !== 'system') t = 'light';"
       " document.documentElement.classList.add('theme-' + t);"
       "})();</script>"
       ;; The shim's <html lang> is static; the page carries the language
       ;; of the connection on .pchat, and this moves it up.
       "<script>new MutationObserver(function () {"
       " var p = document.querySelector('.pchat[lang]');"
       " if (p && document.documentElement.lang !== p.lang) document.documentElement.lang = p.lang;"
       "}).observe(document.documentElement, {subtree: true, childList: true, attributes: true, attributeFilter: ['lang']});</script>"
       ;; The version in the links, so that a browser fetches the
       ;; stylesheets and the script of a new release instead of
       ;; keeping the ones it cached.
       "<link rel=\"stylesheet\" href=\"/rchat.css?v=" (update/current-version) "\">"
       "<link rel=\"stylesheet\" href=\"/pchat.css?v=" (update/current-version) "\">"
       "<script defer src=\"/js/main.js?v=" (update/current-version) "\"></script>"))

(def register
  [(http/resources "/rchat.css" "pchat/chat.css")
   (http/resources "/pchat.css" "pchat/pchat.css")
   (http/resources "/js/*" "public/js")
   {:replicant/shim "/"
    :head head}
   {:replicant/render "/"
    :render/fn #'page-view}
   {:ring/route [:get "/task/*"]
    :ring/handler #'task-link}
   {:command/kind :chat/send
    :command/fn #'send-command}
   {:command/kind :update/start
    :command/fn #'update-start-command}
   {:command/kind :update/check
    :command/fn #'update-check-command}
   {:command/kind :chat/abort
    :command/fn #'abort-command}
   {:command/kind :chat/new
    :command/fn #'new-command}
   {:command/kind :chat/task
    :command/fn #'task-command}
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
    :command/fn #'render-command}
   {:command/kind :artifacts/render-stop
    :command/fn #'render-stop-command}
   {:command/kind :hook/toggle
    :command/fn #'hook-toggle-command}
   {:command/kind :hook/create
    :command/fn #'hook-create-command}
   {:command/kind :hook/remove
    :command/fn #'hook-remove-command}
   {:command/kind :artifacts/share
    :command/fn #'share-command}
   {:command/kind :artifacts/unshare
    :command/fn #'unshare-command}
   {:command/kind :settings/open
    :command/fn #'settings-open-command}
   {:command/kind :settings/close
    :command/fn #'settings-close-command}
   {:command/kind :settings/tab
    :command/fn #'settings-tab-command}
   {:command/kind :connections/add
    :command/fn #'connections-add-command}
   {:command/kind :connections/test
    :command/fn #'connections-test-command}
   {:command/kind :connections/enable
    :command/fn #'connections-enable-command}
   {:command/kind :connections/remove
    :command/fn #'connections-remove-command}
   {:command/kind :connections/agent
    :command/fn #'connections-agent-command}])

(defn get-register
  []
  ;; The project proxy before the files: it answers only for a running
  ;; project, the files route takes the rest.
  (replicant/expand (concat register
                            command/register
                            (setup/register setup)
                            (projects/register projects)
                            (artifacts/register artifacts)
                            (looks/register {:db db
                                             :looks-dir looks-dir})
                            (uploads/register {:dir uploads-dir
                                               :looks-dir looks-dir}))))

(def open-register
  "The routes outside the token: a share link and a webhook address are
  their own keys."
  (concat (share/register shares)
          (hooks/register hooks)))

(defn handler
  "The ring handler: the open routes as they are, everything else
  behind the token."
  [token]
  (let [open (http/ring-handler (fn [] open-register))
        secured (auth/wrap (http/ring-handler get-register) {:token token})]
    (fn [request]
      (if (some (fn [{:keys [ring/route]}] (http/match route request)) open-register)
        (open request)
        (secured request)))))

(defn -main
  [& args]
  ;; `bb pchat.jar --version`: the version built in, nothing else; the
  ;; check-run of an update relies on it.
  (when (= "--version" (first args))
    (println (update/current-version))
    (System/exit 0))
  (.mkdirs sessions-dir)
  (.mkdirs (:dir artifacts))
  (.mkdirs looks-dir)
  (.mkdirs uploads-dir)
  (install-workspace!)
  (swap! db assoc :skills (skills/load-all (skills/checkout)))
  (share/load! shares)
  (load-chats!)
  (hooks/load! hooks)
  (mcp/load! connections)
  (open! (:current @db))
  (artifacts/refresh! artifacts)
  (poll-artifacts!)
  (sync-projects!)
  (reap-idle-pis!)
  (watch-connections!)
  (setup/watch! setup)
  (update/watch! updater)
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(do (projects/stop-all! projects)
                                                       (render/stop-all! renders)
                                                       (hooks/stop-all! hooks))))
  (sse/watch! db)
  (let [port (parse-long (or (System/getenv "PORT") "8080"))
        token (System/getenv "PCHAT_TOKEN")]
    ;; http-kit holds a request body in memory: the browser sends a clip
    ;; in pieces of 8 MB (`pchat.uploads`), this leaves room for a clip
    ;; of a few dozen MB in one request.
    (server/run-server (handler token) {:port port
                                        :max-body (* 64 1024 1024)})
    ;; Under a supervisor (`PCHAT_JAR` set, the output goes to a log
    ;; that is kept) the line names the address without the token;
    ;; a hand start prints the link to open.
    (println (str "pchat on "
                  (cond
                    (and token (System/getenv "PCHAT_JAR")) (str "http://localhost:" port " (the token is in the environment)")
                    token (auth/link (str "http://localhost:" port) token)
                    :else (str "http://localhost:" port))
                  ", " (count (:chats @db)) " chats, the model is " provider "/" model
                  ", the workspace " (.getCanonicalPath workspace)
                  ", " (tools/report)
                  ", skills: " (if-let [skills (seq (:skills @db))]
                                 (str (str/join " " (map :name skills)) " from " (skills/checkout))
                                 "none (PCHAT_AGENTITOS)"))))
  @(promise))
