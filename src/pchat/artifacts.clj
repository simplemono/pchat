(ns pchat.artifacts
  "The things the agent makes, beside the chat: the files under the
  chat's `artifacts/` folder (`pchat.chats`), shown by kind. A page in an
  iframe that reloads when its file changes (rchat's canvas alias), an
  image, a video, an audio file as themselves, anything else as a
  download. The newest change the agent makes becomes the one on
  screen, unless the user picked another one since. Every chat has its
  own folder and its own pane: what another chat made is not here.

  A directory with a bb.edn is a project, an artifact with a process of
  its own whose page is shown through pchat.projects.

  The keys this namespace owns in a chat of the db:

      :artifacts/list       [{:path :kind :title :mtime :size} ...], newest first
      :artifacts/rev        the newest modification time
      :artifacts/current    the path on screen
      :artifacts/picked-at  when the user picked it, to know what is newer

  It reads `:renders` of the chat (`pchat.render`): a video a render
  still writes is shown as is, with its progress, and does not take the
  screen. The files are served under `/chat/<id>/artifacts/`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chats :as chats]
            [rframes.http :as http]))

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
  "{:db the app's atom, :chats-dir the folder of the chats}"
  [{:keys [chats-dir] :as opts}]
  (assoc opts :chats-dir (io/file chats-dir)))

(defn dir
  "The artifacts folder of the chat."
  [{:keys [chats-dir]} id]
  (chats/artifacts-dir chats-dir id))

(defn url-prefix
  "Where the files of the chat are served."
  [id]
  (str (chats/url id) "/artifacts"))

(defn facts
  [handle id]
  (let [files (list-files (dir handle id))]
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
  "The artifact on screen after the folder changed: the newest finished
  file when it is newer than the user's pick, what they picked while it
  exists, the newest otherwise. A video a render still writes changes
  with every frame and does not take the screen by itself; it is news
  once the render is done (`refollow!`)."
  [{:keys [artifacts/current artifacts/picked-at] :as chat} {files :artifacts/list}]
  (let [finished (remove (partial writing chat) files)
        newest (or (first finished) (first files))
        current (some #(when (= current (:path %)) %) files)]
    (cond
      (nil? newest) nil
      (nil? current) (:path newest)
      (> (:mtime newest) (or picked-at 0)) (:path newest)
      :else (:path current))))

(defn- update-chat!
  "`f` over the chat in the db, when the chat is there."
  [db id f]
  (swap! db (fn [db]
              (if (get-in db [:chats id])
                (update-in db [:chats id] f)
                db))))

(defn refresh!
  "Puts the facts of the chat's folder into its chat when they changed,
  and moves the screen to the newest change."
  [{:keys [db] :as handle} id]
  (let [new-facts (facts handle id)]
    (when (not= (:artifacts/list new-facts) (get-in @db [:chats id :artifacts/list]))
      (update-chat! db id (fn [chat]
                            (let [chat (merge chat new-facts)]
                              (assoc chat :artifacts/current (follow chat new-facts))))))
    new-facts))

(defn refollow!
  "Moves the screen as after a change of the folder, for news that is no
  change of a file: a render that is done makes its video the newest
  finished file."
  [{:keys [db]} id]
  (update-chat! db id (fn [chat] (assoc chat :artifacts/current (follow chat chat)))))

(defn pick!
  [{:keys [db]} id path]
  (update-chat! db id #(assoc %
                              :artifacts/current path
                              :artifacts/picked-at (System/currentTimeMillis))))

(defn file-handler
  "GET /chat/<id>/artifacts/<path>: the file, never a hidden one."
  [handle id w]
  (let [subpath (str (get-in w [:ring/route-params :*]))
        file (when-not (hidden? subpath)
               (http/resolve-file (dir handle id) subpath))]
    (assoc w :ring/response
           (if file
             (http/file-response (:ring/request w) file)
             http/not-found))))

(defn- src
  [id {:keys [path mtime]}]
  (str (url-prefix id) "/" path "?rev=" mtime))

(defn- content
  [id {:keys [path kind mtime] :as artifact} {:keys [project render]}]
  (case kind
    :page [:ui/canvas {:ui/src (str (url-prefix id) "/" path)
                       :ui/rev mtime
                       :ui/title [:i18n :artifacts/creation]}]
    :image [:div.artifact-media
            [:img {:replicant/key path
                   :src (src id artifact)
                   :alt (title path)}]]
    ;; The element stays while a render writes the file (one key, one
    ;; src): a reload per frame would show nothing but a spinner.
    :video [:div.artifact-media
            [:video {:replicant/key path
                     :src (if render
                            (str (url-prefix id) "/" path "?rev=" (:started render))
                            (src id artifact))
                     :controls true}]
            (when render
              [:p.rendering
               [:i18n :artifacts/rendering {:progress (str/replace (or (:progress render) "") #"^frame " "")}]])]
    :audio [:div.artifact-media
            [:audio {:replicant/key (str path mtime)
                     :src (src id artifact)
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
                           [:ui/canvas {:ui/src (str (url-prefix id) "/" path "/")
                                        :ui/rev ready-at
                                        :ui/title [:i18n :artifacts/creation]}])
                 :else [:div.canvas-placeholder [:i18n :artifacts/starting]]))
    [:div.canvas-placeholder [:i18n :artifacts/no-preview]]))

(defn- share-controls
  "Share makes a link anyone can open; the link is shown in a field to
  copy from, with the way to end it."
  [id {:keys [path]} {:keys [share]}]
  (when share
    (if-let [url (share path)]
      (list
       [:input.share-link {:replicant/key url
                           :value url
                           :readonly true
                           :title [:i18n :artifacts/share-title]}]
       [:button.link {:on {:click [[:data/command {:command/kind :artifacts/unshare
                                                   :command/data {:id id :path path}}]]}}
        [:i18n :artifacts/unshare]])
      [:button.link {:on {:click [[:data/command {:command/kind :artifacts/share
                                                  :command/data {:id id :path path}}]]}}
       [:i18n :artifacts/share]])))

(defn- option
  [{:keys [path title kind]} selected]
  ;; The option carries the choice: a value on the select alone is set
  ;; before the options exist on a fresh page.
  [:option {:value path
            :selected (= path selected)}
   title
   ;; The video of a design shares its name with the design.
   (when (= :video kind) [:i18n :artifacts/video])])

(defn view
  "The pane of the chat: a chooser over its artifacts, open and download
  links, the current one shown by its kind. `:project` in `opts` is the
  function of a project artifact to its state, see `pchat.projects`,
  with the state of its render under `:render`, see `pchat.render`;
  `:share` the function of an artifact path to its public link, nil
  when there is none, see `pchat.share`."
  ([chat]
   (view chat {}))
  ([{:keys [id] files :artifacts/list current :artifacts/current :as chat} opts]
   (let [artifact (some #(when (= current (:path %)) %) files)
         opts (cond-> opts
                artifact (assoc :render (writing chat artifact)))]
     [:div.canvas-pane
      [:div.canvas-bar
       [:span.label [:i18n :artifacts/label]]
       (when (seq files)
         [:select.artifacts {:value (:path artifact)
                             :on {:change [[:data/command {:command/kind :artifacts/open
                                                           :command/data {:id id
                                                                          :path :event/target.value}}]]}}
          (for [file files]
            (option file (:path artifact)))])
       (when artifact
         (if (= :project (:kind artifact))
           (let [{:keys [render]} (when (:project opts) ((:project opts) artifact))]
             (list
              [:a.link {:href (str (url-prefix id) "/" (:path artifact) "/")
                        :target "_blank"
                        :rel "noopener"}
               [:i18n :artifacts/open]]
              ;; The video of the design, made here on a click.
              (if (and (:started render) (nil? (:exit render)))
                [:span.rendering
                 [:i18n :artifacts/rendering {:progress (str/replace (or (:progress render) "") #"^frame " "")}]
                 " · "
                 [:button.link {:on {:click [[:data/command {:command/kind :artifacts/render-stop
                                                             :command/data {:id id
                                                                            :path (:path artifact)}}]]}}
                  [:i18n :artifacts/render-stop]]]
                [:button.link {:on {:click [[:data/command {:command/kind :artifacts/render
                                                            :command/data {:id id
                                                                           :path (:path artifact)}}]]}}
                 [:i18n :artifacts/render]])))
           (list
            [:a.link {:href (src id artifact)
                      :target "_blank"
                      :rel "noopener"}
             [:i18n :artifacts/open]]
            [:a.link {:href (src id artifact)
                      :download (last (str/split (:path artifact) #"/"))}
             [:i18n :artifacts/download]]
            (share-controls id artifact opts))))]
      (if artifact
        (content id artifact opts)
        [:div.canvas-placeholder [:i18n :artifacts/empty]])])))
