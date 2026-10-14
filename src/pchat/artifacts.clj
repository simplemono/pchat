(ns pchat.artifacts
  "The things the agent makes, beside the chat: the files under the
  workspace's `artifacts/` folder, shown by kind. A page in an iframe that
  reloads when its file changes (rchat's canvas alias), an image, a video,
  an audio file as themselves, anything else as a download. The newest
  change the agent makes becomes the one on screen, unless the user picked
  another one since.

  A directory with a bb.edn is a project, an artifact with a process of
  its own whose page is shown through pchat.projects.

  The db keys this namespace owns:

      :artifacts/list       [{:path :kind :title :mtime :size} ...], newest first
      :artifacts/rev        the newest modification time
      :artifacts/current    the path on screen
      :artifacts/picked-at  when the user picked it, to know what is newer
      :artifacts/owners     {path {:chat id :at ms}}, which chat made what
      :artifacts/hints      {name {:chat id :at ms}}, what a chat's tool is
                            about to make, from its tool calls

  Which chat made an artifact: a new file is stamped with the chat whose
  tool named it (`hint!`, from the tool calls pi reports: `bin/new
  story`, a write under `artifacts/card/`), else with the one chat that
  works at that moment; a file nobody was making (dropped in by hand)
  stays unstamped. The stamps live in `.pchat/artifact-owners.edn` of
  the workspace. The pane lists the current chat's own artifacts first,
  the other chats' under their names, the unstamped under Other.

  It reads `:renders` (`pchat.render`): a video a render still writes
  is shown as is, with its progress, and does not take the screen; and
  `:chats` and `:current` for the owners."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rchat.canvas :as canvas]))

(def path-prefix
  "/artifacts")

(def kinds
  {"html" :page "htm" :page
   "png" :image "jpg" :image "jpeg" :image "gif" :image "webp" :image "svg" :image
   "mp4" :video "webm" :video "mov" :video
   "mp3" :audio "wav" :audio "m4a" :audio "ogg" :audio})

(defn kind
  [path]
  (let [name (last (str/split (str path) #"/"))
        extension (when (str/includes? name ".")
                    (str/lower-case (last (str/split name #"\."))))]
    (get kinds extension :file)))

(defn title
  "What a person calls the file: `guest-review_card.html` is `Guest review card`."
  [path]
  (let [base (-> (last (str/split (str path) #"/"))
                 (str/replace #"\.[^.]+$" "")
                 (str/replace #"[-_]+" " ")
                 (str/trim))]
    (if (seq base)
      (str (str/upper-case (subs base 0 1)) (subs base 1))
      (str path))))

(defn- hidden?
  [relative]
  (boolean (some #(str/starts-with? % ".") (str/split relative #"/"))))

(defn- project?
  "A directory with a bb.edn is an artifact with a process of its own,
  see pchat.projects."
  [dir]
  (.isFile (io/file dir "bb.edn")))

(defn- newest-mtime
  "The newest file of the project; the directory's own time only when it
  has none."
  [dir]
  (let [times (->> (file-seq (io/file dir))
                   (filter #(.isFile %))
                   (remove #(str/starts-with? (.getName %) "."))
                   (map #(.lastModified %)))]
    (if (seq times)
      (reduce max times)
      (.lastModified (io/file dir)))))

(defn list-files
  "The artifacts of the directory, newest first: the files, and a project
  directory as one artifact whose time is its newest file's."
  [dir]
  (let [dir (io/file dir)]
    (if (.isDirectory dir)
      (let [projects (->> (.listFiles dir)
                          (filter #(and (.isDirectory %) (project? %)))
                          (remove #(str/starts-with? (.getName %) "."))
                          (map (fn [project]
                                 {:path (.getName project)
                                  :kind :project
                                  :title (title (.getName project))
                                  :mtime (newest-mtime project)
                                  :size 0})))
            project-names (set (map :path projects))
            files (->> (file-seq dir)
                       (filter #(.isFile %))
                       (map (fn [file]
                              (let [relative (str (.relativize (.toPath dir) (.toPath file)))]
                                {:path relative
                                 :kind (kind relative)
                                 :title (title relative)
                                 :mtime (.lastModified file)
                                 :size (.length file)})))
                       (remove #(hidden? (:path %)))
                       (remove #(project-names (first (str/split (:path %) #"/")))))]
        (->> (concat projects files)
             (sort-by :mtime >)
             (vec)))
      [])))

(defn handle
  "{:db the app's atom, :dir the artifacts directory, :owners-file where
  the stamps are kept (none: they live in the db only, for tests)}"
  [{:keys [dir] :as opts}]
  (assoc opts :dir (io/file dir)))

(defn load-owners!
  "The stamps from the file into the db, at the start."
  [{:keys [db owners-file]}]
  (when (and owners-file (.isFile (io/file owners-file)))
    (swap! db assoc :artifacts/owners
           (try
             (edn/read-string (slurp owners-file))
             (catch Exception _
               {})))))

(defn- save-owners!
  [{:keys [owners-file]} owners]
  (when owners-file
    (io/make-parents (io/file owners-file))
    (spit owners-file (pr-str owners))))

(defn top-name
  "The artifact an item of the tree belongs to: `card/src/main.clj` is
  `card`'s, `story.mp4` is `story`'s (the render of that project),
  `pool.png` its own."
  [path]
  (let [first-segment (first (str/split (str path) #"/"))]
    (if (and (= first-segment (str path)) (str/ends-with? (str path) ".mp4"))
      (subs (str path) 0 (- (count (str path)) 4))
      first-segment)))

(def ^:private names-in-command
  "The artifact a command is about: `bin/new story`, `bin/render story`,
  `bin/music story ...`, `bin/image story ...`, `bin/voice story ...`,
  `bin/eval story ...`, or any path under `artifacts/`."
  [#"bin/(?:new|render|music|image|voice|eval)\s+([A-Za-z0-9._-]+)"
   #"artifacts/([A-Za-z0-9._-]+)"])

(defn hints-of
  "The artifact names a tool call of pi is about: the command of a
  `bash` call, the path of a `write` or `edit`."
  [{:keys [name args]}]
  (let [text (case (str name)
               "bash" (str (:command args))
               ("write" "edit") (str (:path args))
               "")]
    (into #{}
          (comp (mapcat #(re-seq % text))
                (map second))
          names-in-command)))

(def hint-ttl-ms
  "How long a hint counts: a tool call names an artifact that appears
  within minutes, or not at all."
  (* 10 60 1000))

(defn hint!
  "A chat's tool call just started: what it names is the chat's once
  it appears (`refresh!`)."
  [{:keys [db]} chat-id tool-call]
  (let [names (hints-of tool-call)]
    (when (seq names)
      (let [now (System/currentTimeMillis)]
        (swap! db update :artifacts/hints
               (fn [hints]
                 (into (or hints {})
                       (map (fn [name] [name {:chat chat-id :at now}]))
                       names)))))))

(defn- working-chat
  "The one chat that works right now, nil when none or several do."
  [{:keys [chats]}]
  (let [working (filter #(= :working (:status %)) (vals chats))]
    (when (= 1 (count working))
      (:id (first working)))))

(defn owner-for
  "The chat a new artifact belongs to: the one whose tool named it, the
  one that made the project a video is the render of, else the one
  chat that works; nil otherwise."
  [{:keys [artifacts/hints artifacts/owners] :as db} path now]
  (let [name (top-name path)
        hint (get hints name)]
    (or (when (and hint (< (- now (:at hint)) hint-ttl-ms))
          (:chat hint))
        (when (not= name (str path))
          (get-in owners [name :chat]))
        (working-chat db))))

(defn- stamp-new!
  "Stamps the artifacts that appeared since the last list, keeps the
  stamps of what is still there."
  [{:keys [db] :as artifacts} old-files new-files]
  (let [old-paths (set (map :path old-files))
        new-paths (set (map :path new-files))
        now (System/currentTimeMillis)
        before (:artifacts/owners @db)
        owners (reduce (fn [owners path]
                         (if (or (contains? owners path) (contains? old-paths path))
                           owners
                           (if-let [chat (owner-for (assoc @db :artifacts/owners owners) path now)]
                             (assoc owners path {:chat chat :at now})
                             owners)))
                       (select-keys (or before {}) new-paths)
                       (sort-by (fn [path] (count (str path))) new-paths))]
    (when (not= owners before)
      (swap! db assoc :artifacts/owners owners)
      (save-owners! artifacts owners))
    owners))

(defn forget-chat!
  "The chat is gone: its stamps go, its files stay as Other."
  [{:keys [db] :as artifacts} chat-id]
  (let [owners (into {} (remove (fn [[_ {:keys [chat]}]] (= chat chat-id))) (:artifacts/owners @db))]
    (swap! db assoc :artifacts/owners owners)
    (save-owners! artifacts owners)))

(defn owner
  "The chat that made the artifact, or nil."
  [db path]
  (get-in db [:artifacts/owners path :chat]))

(defn own?
  "Whether the artifact is the current chat's."
  [{:keys [current] :as db} {:keys [path]}]
  (and current (= current (owner db path))))

(defn facts
  [{:keys [dir]}]
  (let [files (list-files dir)]
    {:artifacts/list files
     :artifacts/rev (reduce max 0 (map :mtime files))}))

(defn- render-name
  "The project whose render writes the video: `story.mp4` is `story`'s
  (see `pchat.render/output-file`)."
  [path]
  (when (str/ends-with? (str path) ".mp4")
    (subs (str path) 0 (- (count (str path)) 4))))

(defn writing
  "The render that is still writing the artifact (`pchat.render`: the
  video of a project while bin/render runs), or nil. Such a file is no
  news, it changes with every frame."
  [{:keys [renders]} {:keys [path]}]
  (when-let [name (render-name path)]
    (let [render (get renders name)]
      (when (and (:started render) (nil? (:exit render)))
        render))))

(defn follow
  "The artifact on screen after the directory changed: the newest
  finished file when it is newer than the user's pick, what they picked
  while it exists, the newest otherwise. The current chat's own
  artifacts come first: another chat's new file does not take the
  screen while this chat has something of its own. A video a render
  still writes changes with every frame and does not take the screen by
  itself; it is news once the render is done (`refollow!`)."
  [{:keys [artifacts/current artifacts/picked-at] :as db} {files :artifacts/list}]
  (let [finished (remove (partial writing db) files)
        own (filter (partial own? db) finished)
        newest (or (first own) (first finished) (first files))
        current (some #(when (= current (:path %)) %) files)]
    (cond
      (nil? newest) nil
      (nil? current) (:path newest)
      (> (:mtime newest) (or picked-at 0)) (:path newest)
      :else (:path current))))

(defn refresh!
  "Puts the facts of the directory into the db when they changed, and
  moves the screen to the newest change."
  [{:keys [db] :as artifacts}]
  (let [new-facts (facts artifacts)]
    (when (not= (:artifacts/list new-facts) (:artifacts/list @db))
      (stamp-new! artifacts (:artifacts/list @db) (:artifacts/list new-facts))
      (swap! db (fn [db]
                  (let [db (merge db new-facts)]
                    (assoc db :artifacts/current (follow db new-facts))))))
    new-facts))

(defn refollow!
  "Moves the screen as after a change of the directory, for news that
  is no change of a file: a render that is done makes its video the
  newest finished file."
  [{:keys [db]}]
  (swap! db (fn [db] (assoc db :artifacts/current (follow db db)))))

(defn pick!
  [{:keys [db]} path]
  (swap! db assoc
         :artifacts/current path
         :artifacts/picked-at (System/currentTimeMillis)))

(defn register
  "The route that serves the files."
  [{:keys [db dir]}]
  (canvas/register (canvas/canvas {:db db
                                   :dir dir
                                   :path path-prefix})))

(defn- src
  [{:keys [path mtime]}]
  (str path-prefix "/" path "?rev=" mtime))

(defn- content
  [{:keys [path kind mtime] :as artifact} {:keys [project render]}]
  (case kind
    :page [:ui/canvas {:ui/src (str path-prefix "/" path)
                       :ui/rev mtime
                       :ui/title [:i18n :artifacts/creation]}]
    :image [:div.artifact-media
            [:img {:replicant/key path
                   :src (src artifact)
                   :alt (title path)}]]
    ;; The element stays while a render writes the file (one key, one
    ;; src): a reload per frame would show nothing but a spinner.
    :video [:div.artifact-media
            [:video {:replicant/key path
                     :src (if render
                            (str path-prefix "/" path "?rev=" (:started render))
                            (src artifact))
                     :controls true}]
            (when render
              [:p.rendering
               [:i18n :artifacts/rendering {:progress (str/replace (or (:progress render) "") #"^frame " "")}]])]
    :audio [:div.artifact-media
            [:audio {:replicant/key (str path mtime)
                     :src (src artifact)
                     :controls true}]]
    :project (let [{:keys [ready-at exit reload render]} (when project (project artifact))]
               (cond
                 exit [:div.canvas-placeholder [:i18n :artifacts/stopped {:exit exit}]]
                 ready-at (list
                           (when (:error reload)
                             [:p.error [:i18n :artifacts/reload-failed {:file (:file reload)
                                                                        :error (:error reload)}]])
                           (when (:error render)
                             [:p.error [:i18n :artifacts/render-failed {:error (:error render)}]])
                           [:ui/canvas {:ui/src (str path-prefix "/" path "/")
                                        :ui/rev ready-at
                                        :ui/title [:i18n :artifacts/creation]}])
                 :else [:div.canvas-placeholder [:i18n :artifacts/starting]]))
    [:div.canvas-placeholder [:i18n :artifacts/no-preview]]))

(defn- share-controls
  "Share makes a link anyone can open; the link is shown in a field to
  copy from, with the way to end it."
  [{:keys [path]} {:keys [share]}]
  (when share
    (if-let [url (share path)]
      (list
       [:input.share-link {:replicant/key url
                           :value url
                           :readonly true
                           :title [:i18n :artifacts/share-title]}]
       [:button.link {:on {:click [[:data/command {:command/kind :artifacts/unshare
                                                   :command/data {:path path}}]]}}
        [:i18n :artifacts/unshare]])
      [:button.link {:on {:click [[:data/command {:command/kind :artifacts/share
                                                  :command/data {:path path}}]]}}
       [:i18n :artifacts/share]])))

(defn- option
  [{:keys [path title kind]} selected by]
  ;; The option carries the choice: a value on the select alone is set
  ;; before the options exist on a fresh page.
  [:option {:value path
            :selected (= path selected)}
   title
   ;; The video of a design shares its name with the design.
   (when (= :video kind) [:i18n :artifacts/video])
   (when by (list " · " by))])

(defn options
  "The chooser's options: the current chat's own artifacts first, the
  other chats' in a group named after them, the unstamped in a group
  Other; one flat list while nothing is stamped. `:chat-name` in `opts`
  names a chat, the chat's own name otherwise."
  [{:keys [chats] :as db} files artifact {:keys [chat-name]}]
  (let [selected (:path artifact)
        name-of (fn [chat-id]
                  (let [chat (get chats chat-id)]
                    (or (when chat-name (chat-name chat))
                        (:name chat)
                        chat-id)))
        own (filter (partial own? db) files)
        others (filter #(let [by (owner db (:path %))] (and by (not (own? db %)))) files)
        unowned (remove #(owner db (:path %)) files)]
    (if (and (empty? own) (empty? others))
      (for [file files]
        (option file selected nil))
      (list
       (for [file own]
         (option file selected nil))
       (when (seq others)
         [:optgroup {:label [:i18n :artifacts/others]}
          (for [file others]
            (option file selected (name-of (owner db (:path file)))))])
       (when (seq unowned)
         [:optgroup {:label [:i18n :artifacts/unowned]}
          (for [file unowned]
            (option file selected nil))])))))

(defn view
  "The pane: a chooser over the artifacts, open and download links, the
  current one shown by its kind. `:project` in `opts` is the function of a
  project artifact to its state, see `pchat.projects`, with the state of
  its render under `:render`, see `pchat.render`; `:share` the function
  of an artifact path to its public link, nil when there is none, see
  `pchat.share`; `:chat-name` the function of a chat to its name, for
  the other chats' artifacts."
  ([db]
   (view db {}))
  ([{files :artifacts/list current :artifacts/current :as db} opts]
  (let [artifact (some #(when (= current (:path %)) %) files)
        opts (cond-> opts
               artifact (assoc :render (writing db artifact)))]
    [:div.canvas-pane
     [:div.canvas-bar
      [:span.label [:i18n :artifacts/label]]
      (when (seq files)
        [:select.artifacts {:value (:path artifact)
                            :on {:change [[:data/command {:command/kind :artifacts/open
                                                          :command/data {:path :event/target.value}}]]}}
         (options db files artifact opts)])
      (when artifact
        (if (= :project (:kind artifact))
          (let [{:keys [render]} (when (:project opts) ((:project opts) artifact))]
            (list
             [:a.link {:href (str path-prefix "/" (:path artifact) "/")
                       :target "_blank"
                       :rel "noopener"}
              [:i18n :artifacts/open]]
             ;; The video of the design, made here on a click.
             (if (and (:started render) (nil? (:exit render)))
               [:span.rendering
                [:i18n :artifacts/rendering {:progress (str/replace (or (:progress render) "") #"^frame " "")}]
                " · "
                [:button.link {:on {:click [[:data/command {:command/kind :artifacts/render-stop
                                                            :command/data {:path (:path artifact)}}]]}}
                 [:i18n :artifacts/render-stop]]]
               [:button.link {:on {:click [[:data/command {:command/kind :artifacts/render
                                                           :command/data {:path (:path artifact)}}]]}}
                [:i18n :artifacts/render]])))
          (list
           [:a.link {:href (src artifact)
                     :target "_blank"
                     :rel "noopener"}
            [:i18n :artifacts/open]]
           [:a.link {:href (src artifact)
                     :download (last (str/split (:path artifact) #"/"))}
            [:i18n :artifacts/download]]
           (share-controls artifact opts))))]
     (if artifact
       (content artifact opts)
       [:div.canvas-placeholder [:i18n :artifacts/empty]])])))
