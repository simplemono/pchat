(ns pchat.main
  "The server: many chats, one pi process each, the page over rframes.

      PORT             default 8080
      PCHAT_WORKSPACE  the folder of the chats, default ./workspace
      PCHAT_PROVIDER   default openrouter (OPENROUTER_API_KEY in the environment)
      PCHAT_MODEL      default openai/gpt-6.1-sol
      PCHAT_TOKEN      a secret: the page is behind the link that sets the cookie
      PCHAT_AGENTITOS  an agentitos checkout: its skills are the tasks a chat can be for
      BB               the babashka executable for the artifact projects, default bb

  The app value: {:chats {id chat} :tabs {tabid id} :skills [skill]}. A
  chat is the value of `pchat.chat` plus :id, :name, :task (the name of
  its skill, :free, or nil while the person has not picked one),
  :created, :updated, :file and :loaded?, and what the other namespaces
  keep in a chat: its artifacts, projects, renders, shares, hook and
  runs. Every chat works in its own folder (`pchat.chats`); the list
  comes from the folders, the history of a chat from its pi session
  file when it is first shown. Which chat a browser tab shows is the
  tab's: its URL, `/chat/<id>`, until a click in the sidebar names
  another (`:tabs`)."
  (:require [babashka.nrepl.server :as nrepl-server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [pchat.artifacts :as artifacts]
            [pchat.chat :as chat]
            [pchat.chats :as chats]
            [pchat.hooks :as hooks]
            [pchat.i18n :as i18n]
            [pchat.looks :as looks]
            [pchat.mcp :as mcp]
            [pchat.migrate :as migrate]
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

(def chats-dir
  (io/file workspace "chats"))

(def bb
  (or (System/getenv "BB") "bb"))

(def provider
  (or (System/getenv "PCHAT_PROVIDER") "openrouter"))

(def model
  (or (System/getenv "PCHAT_MODEL") "openai/gpt-6.1-sol"))

(def key-missing?
  (and (= "openrouter" provider)
       (str/blank? (System/getenv "OPENROUTER_API_KEY"))))

(defonce db
  (atom {:chats {}
         :tabs {}}))

;; The pi process handles are not values, so they live beside the db,
;; one per chat id.
(defonce pis
  (atom {}))

;; Which chat each browser tab showed last and when: the view notes it
;; at every frame. Beside the db, so that a frame changes nothing that
;; would make another frame.
(defonce tab-views
  (atom {}))

(defn- now
  []
  (System/currentTimeMillis))

(def shown-ms
  "A tab that got a frame this recently looks at its chat: its
  artifacts are watched and its project on screen runs."
  (* 2 60 1000))

(defn- shown-chats
  "The chats someone looks at right now."
  []
  (let [since (- (now) shown-ms)]
    (into #{}
          (keep (fn [[_ {:keys [chat seen]}]]
                  (when (< since seen)
                    chat)))
          @tab-views)))

(def artifacts
  (artifacts/handle {:db db
                     :chats-dir chats-dir}))

(def projects
  (projects/handle {:db db
                    :chats-dir chats-dir
                    :bb bb
                    :shown? (fn [id] (contains? (shown-chats) id))}))

(def renders
  (render/handle {:db db
                  :chats-dir chats-dir
                  :bb bb
                  :env (tools/env)
                  ;; The finished video is news now, the poll alone would
                  ;; not notice: the file stopped changing before.
                  :on-finish (fn [id _name] (artifacts/refollow! artifacts id))}))

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
                 :chats-dir chats-dir
                 :bb bb
                 :env (tools/env)}))

(def shares
  (share/handle {:db db
                 :chats-dir chats-dir}))

(declare restart-pis!)

(def connections
  (mcp/handle {:db db
               :workspace workspace
               :on-change (fn [] (restart-pis!))}))

(declare load-chats!)

(def migration
  (migrate/handle {:db db
                   :workspace workspace
                   :bb bb
                   :on-done (fn [] (load-chats!))}))

(def template-files
  "The project templates and the scripts, copied into the workspace at
  start: .pchat/template/motion/, .pchat/template/hyperframes/ and bin/.
  Every chat's folder links to them."
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
   "bin/mcp"
   "bin/migrate-chats"])

(def lib-files
  "Namespaces of pchat the scripts load from .pchat/lib: `bin/mcp` edits
  the connections with the same code as the page, `bin/migrate-chats`
  makes chat folders the way the page does."
  ["mcp.clj"
   "chats.clj"])

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
  "A pi message of the chat with its pictures as files in the chat's
  uploads folder."
  [id message]
  (looks/intern-message (chats/uploads-dir chats-dir id)
                        (str (chats/url id) "/uploads")
                        message))

(defn new-chat
  [id]
  (merge chat/empty-chat
         {:id id
          :created (now)
          :updated (now)
          :loaded? true}))

(defn- chat-summary
  "What the sidebar needs of a chat, from its folder: its session file
  when it has one, the name and the task the person gave it."
  [id]
  (let [{:keys [name task]} (chats/meta chats-dir id)
        file (first (session/files (chats/session-dir chats-dir id)))
        made (.lastModified (chats/dir chats-dir id))]
    (-> (merge chat/empty-chat
               {:created made
                :updated made}
               (when file
                 (session/summary file))
               (when name {:name name})
               (when task {:task task}))
        (assoc :id id))))

(defn- new!
  "A fresh chat with its folder; the id."
  []
  (let [id (chats/new-id)]
    (chats/create! workspace id)
    (swap! db (fn [db]
                (-> db
                    (assoc-in [:chats id] (new-chat id))
                    (dissoc :deleting :hook-open :settings-open))))
    id))

(defn- load-chats!
  "The chats of the folders into the db, without their items; the ones
  already loaded stay as they are, the ones whose folder went go. A
  fresh chat when there is none."
  []
  (let [ids (chats/ids chats-dir)]
    (swap! db update :chats
           (fn [chats]
             (into (select-keys chats ids)
                   (for [id ids
                         :when (not (get-in chats [id :loaded?]))]
                     [id (chat-summary id)]))))
    (hooks/load! hooks)
    (share/load! shares)
    (when (empty? (:chats @db))
      (new!))))

(defn- ensure-loaded!
  "The history of the chat from its session file, the first time it is
  shown; the name the person gave it stays."
  [id]
  (let [{:keys [loaded? file]} (get-in @db [:chats id])]
    (when (and (not loaded?) file)
      (let [loaded (session/load-chat (io/file file) #(intern-message id %))]
        (swap! db update-in [:chats id] merge (dissoc loaded :name) {:loaded? true})))))

(defn- on-event
  [id event]
  (if (= "exited" (:type event))
    ;; The handle of the process that ended, not one spawned since.
    (swap! pis (fn [pis] (if (some-> (get pis id) pi/alive?) pis (dissoc pis id))))
    (swap! pis update id #(some-> % (assoc :last-event (now)))))
  (let [event (cond-> event
                (:message event) (update :message #(intern-message id %)))]
    (swap! db (fn [db]
                (if (get-in db [:chats id])
                  (-> db
                      (update-in [:chats id] chat/event event)
                      (assoc-in [:chats id :updated] (now)))
                  db))))
  ;; A tool just ran: what it wrote is on screen right away, the poll
  ;; catches the rest.
  (when (#{"tool_execution_end" "agent_settled"} (:type event))
    (artifacts/refresh! artifacts id))
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

(defn- rendering-chats
  "The chats with a video on its way."
  []
  (for [[id {:keys [renders]}] (:chats @db)
        :when (some (fn [[_ render]] (and (:started render) (nil? (:exit render)))) renders)]
    id))

(defn- watched-chats
  "The chats whose artifacts folders are looked at: shown in a tab,
  working, or rendering."
  []
  (into (shown-chats)
        (concat (for [[id {:keys [status]}] (:chats @db)
                      :when (= :working status)]
                  id)
                (rendering-chats))))

(defn- prune-tabs!
  "The tabs the db remembers a chat for, less the ones gone for an
  hour."
  []
  (let [since (- (now) (* 60 60 1000))
        gone (for [[tab _] (:tabs @db)
                   :when (< (get-in @tab-views [tab :seen] 0) since)]
               tab)]
    (when (seq gone)
      (swap! db update :tabs #(apply dissoc % gone)))))

(defn- poll-artifacts!
  "Looks at the artifacts folders of the chats someone watches twice a
  second: the agent writes with tools, but a command can write files
  too. Once a minute a tick in the db makes a frame for every tab, so
  that a quiet tab stays known as one that looks."
  []
  (future
    (loop [n 0]
      (Thread/sleep 500)
      (try
        (doseq [id (watched-chats)]
          (artifacts/refresh! artifacts id))
        (when (zero? (mod n 120))
          (prune-tabs!)
          (swap! db assoc :tick (quot (now) 60000)))
        (catch Exception e
          (binding [*out* *err*]
            (println "[artifacts]" (str e)))))
      (recur (inc n)))))

(defn- system-prompt
  "system.md, the skills paragraph (the chat's task in full, see
  `pchat.skills/prompt`), the integrations paragraph when a connection
  with Composio's connection tools exists (a Storrito agent's own), and
  the webhook paragraph."
  [id]
  (str (slurp (io/resource "pchat/prompts/system.md"))
       (some->> (skills/prompt (:skills @db) (get-in @db [:chats id :task]))
                (str "\n\n"))
       (when-let [names (seq (mcp/composio-connections connections))]
         (str "\n\n" (str/replace (slurp (io/resource "pchat/prompts/integrations.md"))
                                  "<connection>" (str/join ", " names))))
       "\n\n" (hooks/prompt)))

(defn- chat-env
  "The environment of a chat's processes: the tools, the shared scripts
  on the PATH, the chat's own folder for temporary files."
  [id]
  (assoc (tools/env)
         "PATH" (str (.getPath (io/file workspace "bin")) ":" (System/getenv "PATH"))
         "TMPDIR" (str (chats/tmp-dir chats-dir id))))

(defn- ensure-pi!
  [id]
  (or (get @pis id)
      (let [{:keys [skills]} @db
            handle (pi/spawn! {:dir (chats/dir chats-dir id)
                               :session-dir (chats/session-dir chats-dir id)
                               :session-id id
                               :provider provider
                               :model model
                               :system-prompt (system-prompt id)
                               :skills (skills/paths skills)
                               :env (chat-env id)
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
  "The text and the images to the chat's agent."
  [id text images]
  (let [p (ensure-pi! id)
        streaming? (= :working (get-in @db [:chats id :status]))]
    (pi/prompt! p text streaming? images)))

(defn- keep!
  "The message of a chat whose agent is not there yet: kept in the
  chat with the answer that says so, nothing is sent. Its clips show as
  in a sent message."
  [id text image-urls message]
  (let [[user note] (setup/refusal-items text image-urls message)
        user (assoc user
                    :text (uploads/without-lines text)
                    :media (uploads/attachments text))]
    (swap! db (fn [db]
                (-> db
                    (update-in [:chats id :items] into [user note])
                    (assoc-in [:chats id :updated] (now)))))))

(defn- attachment-lines
  "A line for each attached file that is in the chat's uploads folder,
  naming its path there, in the language of the page: \"(Attached
  video: uploads/ab12.mp4)\". A picture goes to the model as an image
  as well; the line tells the agent where the file is."
  [w id names]
  (for [name names
        :let [kind (or (uploads/kind name)
                       (when (re-find #"\.(png|jpe?g|webp|gif)$" (str name)) :image))]
        :when (and kind
                   (re-matches #"[A-Za-z0-9]+\.[a-z0-9]+" (str name))
                   (.isFile (io/file (chats/uploads-dir chats-dir id) (str name))))]
    (let [path (str "uploads/" name)]
      (say w (case kind
               :audio [:i18n :upload/attached-audio {:path path}]
               :video [:i18n :upload/attached-video {:path path}]
               [:i18n :upload/attached-image {:path path}])))))

(defn- send-command
  [w]
  (let [{:keys [id text images]} (command/command-data w)
        names (when (sequential? images)
                (keep :name images))
        text (str/trim (str text))
        lines (attachment-lines w id names)
        images (looks/blocks (chats/uploads-dir chats-dir id) (remove uploads/kind names))
        message (str/trim (cond
                            (seq lines) (str/join "\n" (cons text lines))
                            (str/blank? text) "Look at this."
                            :else text))]
    (cond
      (not (get-in @db [:chats id]))
      (command/rejected w (say w [:i18n :command/no-such-chat]))

      (and (str/blank? text) (empty? images) (empty? lines))
      (command/rejected w (say w [:i18n :command/nothing-to-send]))

      ;; pi is still being installed on a fresh machine: the message
      ;; waits in the chat with the reason, see `pchat.setup`.
      (not (setup/ready? (setup/refresh! setup)))
      (do (keep! id
                 message
                 (map #(str (chats/url id) "/uploads/" %) (remove uploads/kind names))
                 (setup/not-ready-message (:pi (:setup @db))))
          (command/accepted w))

      :else
      (do (send! id message images)
          (command/accepted w)))))

(defn- abort-command
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (when-let [p (get @pis id)]
      (pi/abort! p))
    (command/accepted w)))

(defn- show-command
  "The tab shows the chat from now on: a click in the sidebar, the
  back button. Without an id, or with one that is gone, the tab
  follows its URL again."
  [w]
  (let [{:keys [id tab]} (command/command-data w)
        tab (str tab)]
    (if (str/blank? tab)
      (command/rejected w (say w [:i18n :command/no-such-tab]))
      (do (if (get-in @db [:chats id])
            (swap! db #(-> % (assoc-in [:tabs tab] id) (dissoc :deleting :hook-open :settings-open)))
            (swap! db update :tabs dissoc tab))
          (command/accepted w)))))

(defn- new-command
  "A fresh chat, shown in the tab that asked for it."
  [w]
  (let [{:keys [tab]} (command/command-data w)
        tab (str tab)]
    (if (str/blank? tab)
      (command/rejected w (say w [:i18n :command/no-such-tab]))
      (let [id (new!)]
        (swap! db assoc-in [:tabs tab] id)
        (command/accepted w)))))

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
  (chats/update-meta! chats-dir id #(assoc % :task task))
  (if task
    (swap! db assoc-in [:chats id :task] task)
    (swap! db update-in [:chats id] dissoc :task)))

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

(defn- newest-chat-id
  [db]
  (:id (first (sort-by #(or (:updated %) 0) > (vals (:chats db))))))

(defn- task-link
  "GET /task/<skill> (or /task/free): the way in from elsewhere, e.g. a
  button on Storrito, to a chat for that task without the picker. The
  path survives the redirect of a token link (`/task/<skill>?token=…`),
  a query would not. The newest chat is taken while nothing has
  happened in it, so that opening the link twice gives one chat; else a
  fresh one. An unknown task leaves the choice to the picker. Then on
  to the chat's page."
  [w]
  (let [task (known-task (get-in w [:ring/route-params :*]))
        newest (newest-chat-id @db)
        id (if (and newest (unstarted? newest))
             (do (swap! db dissoc :deleting :hook-open :settings-open)
                 newest)
             (new!))]
    (set-task! id task)
    (assoc w :ring/response {:status 302
                             :headers {"Location" (chats/url id)
                                       "Cache-Control" "no-store"}
                             :body ""})))

(defn- rename-command
  [w]
  (let [{:keys [id name]} (command/command-data w)
        name (str/trim (str name))]
    (if (get-in @db [:chats id])
      (do (chats/update-meta! chats-dir id #(assoc % :name (not-empty name)))
          (if (str/blank? name)
            (swap! db update-in [:chats id] dissoc :name)
            (swap! db assoc-in [:chats id :name] name))
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-chat])))))

(defn- delete!
  "The chat is gone: its pi ended, its projects and renders stopped,
  its folder deleted with everything in it; the tabs that showed it
  follow their URL, which lands on the newest chat; a fresh one when
  there is none."
  [id]
  (when-let [handle (get @pis id)]
    (pi/kill! handle)
    (pi/wait! handle 2000)
    (swap! pis dissoc id))
  (projects/stop-chat! projects id)
  (render/stop-chat! renders id)
  (chats/delete! chats-dir id)
  (swap! db (fn [db]
              (-> db
                  (update :chats dissoc id)
                  (update :tabs #(into {} (remove (fn [[_ chat]] (= chat id))) %))
                  (dissoc :deleting))))
  (when (empty? (:chats @db))
    (new!)))

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

(defn- artifact-of
  "The artifact of the chat at the path, or nil."
  [id path]
  (some #(when (= path (:path %)) %) (get-in @db [:chats id :artifacts/list])))

(defn- open-artifact-command
  [w]
  (let [{:keys [id path]} (command/command-data w)]
    (if (artifact-of id path)
      (do (artifacts/pick! artifacts id path)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-artifact])))))

(defn- render-command
  [w]
  (let [{:keys [id path]} (command/command-data w)]
    (if (= :project (:kind (artifact-of id path)))
      (do (render/start! renders id path)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-artifact])))))

(defn- render-stop-command
  [w]
  (let [{:keys [id path]} (command/command-data w)]
    (render/stop! renders id path)
    (command/accepted w)))

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
    (when (get-in @db [:chats id])
      (hooks/remove! hooks id))
    (command/accepted w)))

(defn- share-command
  [w]
  (let [{:keys [id path]} (command/command-data w)
        artifact (artifact-of id path)]
    (if (and artifact (not= :project (:kind artifact)))
      (do (share/share! shares id path)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-artifact])))))

(defn- unshare-command
  [w]
  (let [{:keys [id path]} (command/command-data w)]
    (when (get-in @db [:chats id])
      (share/unshare! shares id path))
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

(defn- migrate-start-command
  "The person brings the chats from before 0.2.0 back: see
  `pchat.migrate`."
  [w]
  (migrate/start! migration)
  (command/accepted w))

(defn- migrate-dismiss-command
  [w]
  (migrate/dismiss! migration)
  (command/accepted w))

(defn- settings-open-command
  "The settings in place of the chat."
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
  and sets it up: a message to the chat the tab shows, in the person's
  language; the settings close so that the person sees the agent at
  work."
  [w]
  (let [{:keys [id service]} (command/command-data w)
        service (str/trim (str service))]
    (if (get-in @db [:chats id])
      (do (send! id
                 (say w (if (str/blank? service)
                          [:i18n :connections/agent-message-blank]
                          [:i18n :connections/agent-message {:service service}]))
                 nil)
          (swap! db dissoc :settings-open)
          (command/accepted w))
      (command/rejected w (say w [:i18n :command/no-such-chat])))))

(defn- url-chat-id
  "The chat id in the path of the page, `/chat/<id>`, or nil."
  [w]
  (let [id (first (str/split (str (get-in w [:ring/route-params :*])) #"/"))]
    (when (chats/valid-id? id)
      id)))

(defn- shown-chat
  "The chat a connection shows: the one its tab picked since
  (`:chat/show`), else the one of its URL; the newest when that one is
  not there, with the id that was asked for under `:missing`."
  [db w]
  (let [wanted (or (get-in db [:tabs (:tabid w)]) (url-chat-id w))]
    (if (get-in db [:chats wanted])
      {:id wanted}
      {:id (newest-chat-id db)
       :missing wanted})))

(defn page-view
  "The page in the language of the connection, with the chat of the tab."
  [w]
  (let [{:keys [id missing]} (shown-chat @db w)]
    (ensure-loaded! id)
    (when-not (contains? (get-in @db [:chats id]) :artifacts/list)
      (artifacts/refresh! artifacts id))
    (when-let [tab (:tabid w)]
      (swap! tab-views assoc tab {:chat id :seen (now)}))
    (let [value @db
          chat (get-in value [:chats id])
          locale (i18n/locale (:ring/request w))
          base-url (share/base-url (:ring/request w))]
      (-> (view/page value {:id id
                            :note (when missing [:i18n :chat/missing])
                            :key-missing? key-missing?
                            :locale locale
                            :project (fn [{:keys [path]}]
                                       (assoc (get-in chat [:projects path])
                                              :render (get-in chat [:renders path])))
                            :share (fn [path]
                                     (some->> (get-in chat [:shares path])
                                              (share/link base-url)))
                            :hook-url (fn [{:keys [id hook]}]
                                        (when hook
                                          (hooks/url base-url id (:secret hook))))})
          (i18n/translate locale)))))

(def head
  (str "<title>pchat</title>"
       ;; Read by the client: where this page's commands go (see
       ;; `pchat.client/init!`).
       "<meta name=\"pchat-command\" content=\"/command\">"
       "<link rel=\"icon\" href=\"data:,\">"
       ;; Relative links in an answer, `artifacts/card.html`, resolve
       ;; against the chat's page: the `:ui/url` alias of the page
       ;; (`pchat.client`) points the base at `/chat/<id>/`.
       "<base href=\"/\">"
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

(def chat-stream
  "The view stream of a chat's page, POST /chat/<id>: the chat id of
  the path is in the route params of the connection."
  (:ring/handler (replicant/expand-entry {:replicant/render "/chat/*"
                                          :render/fn #'page-view})))

(defn- split-chat-path
  "`<id>/artifacts/x` of the wildcard into [id \"artifacts/x\"]."
  [w]
  (let [[id & rest] (str/split (str (get-in w [:ring/route-params :*])) #"/" 2)]
    [id (str (first rest))]))

(defn- under
  "The world with the rest of the path as its wildcard."
  [w rest prefix]
  (assoc w :ring/route-params {:* (subs rest (count prefix))}))

(defn- not-found
  [w]
  (assoc w :ring/response http/not-found))

(defn- chat-get
  "GET /chat/<id>: the page; /chat/<id>/artifacts/<path>: a running
  project's page, else the file; /chat/<id>/uploads/<name>: the file."
  [w]
  (let [[id rest] (split-chat-path w)]
    (cond
      (not (chats/valid-id? id))
      (not-found w)

      (str/blank? rest)
      (assoc w :ring/response {:status 200
                               :headers {"Content-Type" "text/html; charset=utf-8"
                                         "Cache-Control" "no-cache"}
                               :body (replicant/shim-html head)})

      (str/starts-with? rest "artifacts/")
      (let [w (under w rest "artifacts/")
            w (projects/handler projects id w)]
        (if (:ring/response w)
          w
          (artifacts/file-handler artifacts id w)))

      (str/starts-with? rest "uploads/")
      (uploads/file-handler (chats/uploads-dir chats-dir id) (under w rest "uploads/"))

      :else
      (not-found w))))

(defn- chat-post
  "POST /chat/<id>: the view stream; /chat/<id>/upload: a file into
  the chat's folder; /chat/<id>/artifacts/<name>/...: a running
  project's commands."
  [w]
  (let [[id rest] (split-chat-path w)]
    (cond
      (not (chats/valid-id? id))
      (not-found w)

      (str/blank? rest)
      (chat-stream w)

      (and (= "upload" rest) (get-in @db [:chats id]))
      (uploads/handler {:dir (chats/uploads-dir chats-dir id)
                        :url-prefix (str (chats/url id) "/uploads")}
                       w)

      (str/starts-with? rest "artifacts/")
      (let [w (projects/handler projects id (under w rest "artifacts/"))]
        (if (:ring/response w)
          w
          (not-found w)))

      :else
      (not-found w))))

(def register
  [(http/resources "/rchat.css" "pchat/chat.css")
   (http/resources "/pchat.css" "pchat/pchat.css")
   (http/resources "/js/*" "public/js")
   ;; The page at / shows the newest chat; the browser puts the chat's
   ;; own address in the bar.
   {:replicant/shim "/"
    :head head}
   {:replicant/render "/"
    :render/fn #'page-view}
   {:ring/route [:get "/chat/*"]
    :ring/handler #'chat-get}
   {:ring/route [:post "/chat/*"]
    :ring/handler #'chat-post}
   {:ring/route [:get "/task/*"]
    :ring/handler #'task-link}
   {:command/kind :chat/send
    :command/fn #'send-command}
   {:command/kind :update/start
    :command/fn #'update-start-command}
   {:command/kind :update/check
    :command/fn #'update-check-command}
   {:command/kind :migrate/start
    :command/fn #'migrate-start-command}
   {:command/kind :migrate/dismiss
    :command/fn #'migrate-dismiss-command}
   {:command/kind :chat/abort
    :command/fn #'abort-command}
   {:command/kind :chat/show
    :command/fn #'show-command}
   {:command/kind :chat/new
    :command/fn #'new-command}
   {:command/kind :chat/task
    :command/fn #'task-command}
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
  (replicant/expand (concat register
                            command/register
                            (setup/register setup))))

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

(defn start-nrepl!
  "An nREPL server for live development, on by default: bound to
  127.0.0.1 only, so on a sprite nothing outside reaches it (the sprite
  exposes the page's port alone, `sprite proxy <port>` tunnels through
  the authenticated API), and on a laptop it is what `bb nrepl-server`
  gives. The port is `PCHAT_NREPL_PORT` or a free one, written to
  `.nrepl-port` in the workspace for editors; `PCHAT_NREPL=off` leaves
  it out. Answers the port, nil when off."
  [workspace]
  (when-not (= "off" (System/getenv "PCHAT_NREPL"))
    (let [server (nrepl-server/start-server! {:host "127.0.0.1"
                                              :port (parse-long (or (System/getenv "PCHAT_NREPL_PORT") "0"))
                                              :quiet true})
          port (.getLocalPort ^java.net.ServerSocket (:socket server))]
      (spit (io/file workspace ".nrepl-port") (str port))
      port)))

(defn -main
  [& args]
  ;; `bb pchat.jar --version`: the version built in, nothing else; the
  ;; check-run of an update relies on it.
  (when (= "--version" (first args))
    (println (update/current-version))
    (System/exit 0))
  (.mkdirs chats-dir)
  ;; pi's connections, linked from every chat's folder.
  (.mkdirs (io/file workspace ".pi"))
  (install-workspace!)
  (swap! db assoc :skills (skills/load-all (skills/checkout)))
  (load-chats!)
  (mcp/load! connections)
  (migrate/check! migration)
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
        token (System/getenv "PCHAT_TOKEN")
        nrepl-port (start-nrepl! workspace)]
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
                  (if nrepl-port (str ", nREPL on 127.0.0.1:" nrepl-port) ", no nREPL")
                  ", " (tools/report)
                  ", skills: " (if-let [skills (seq (:skills @db))]
                                 (str (str/join " " (map :name skills)) " from " (skills/checkout))
                                 "none (PCHAT_AGENTITOS)")
                  (when (migrate/needed? migration)
                    ", chats from before 0.2.0 found: bin/migrate-chats or the banner brings them back"))))
  @(promise))
