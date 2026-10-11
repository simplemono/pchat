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
      :artifacts/picked-at  when the user picked it, to know what is newer"
  (:require [clojure.java.io :as io]
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
  "{:db the app's atom, :dir the artifacts directory}"
  [{:keys [dir] :as opts}]
  (assoc opts :dir (io/file dir)))

(defn facts
  [{:keys [dir]}]
  (let [files (list-files dir)]
    {:artifacts/list files
     :artifacts/rev (reduce max 0 (map :mtime files))}))

(defn follow
  "The artifact on screen after the directory changed: the newest file
  when it is newer than the user's pick, what they picked while it exists,
  the newest otherwise."
  [{:keys [artifacts/current artifacts/picked-at]} {files :artifacts/list}]
  (let [newest (first files)
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
      (swap! db (fn [db]
                  (let [db (merge db new-facts)]
                    (assoc db :artifacts/current (follow db new-facts))))))
    new-facts))

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
  [{:keys [path kind mtime] :as artifact} {:keys [project]}]
  (case kind
    :page [:ui/canvas {:ui/src (str path-prefix "/" path)
                       :ui/rev mtime
                       :ui/title [:i18n :artifacts/creation]}]
    :image [:div.artifact-media
            [:img {:replicant/key path
                   :src (src artifact)
                   :alt (title path)}]]
    :video [:div.artifact-media
            [:video {:replicant/key (str path mtime)
                     :src (src artifact)
                     :controls true}]]
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

(defn view
  "The pane: a chooser over the artifacts, open and download links, the
  current one shown by its kind. `:project` in `opts` is the function of a
  project artifact to its state, see `pchat.projects`, with the state of
  its render under `:render`, see `pchat.render`; `:share` the function
  of an artifact path to its public link, nil when there is none, see
  `pchat.share`."
  ([db]
   (view db {}))
  ([{files :artifacts/list current :artifacts/current} opts]
  (let [artifact (some #(when (= current (:path %)) %) files)]
    [:div.canvas-pane
     [:div.canvas-bar
      [:span.label [:i18n :artifacts/label]]
      (when (seq files)
        [:select.artifacts {:value (:path artifact)
                            :on {:change [[:data/command {:command/kind :artifacts/open
                                                          :command/data {:path :event/target.value}}]]}}
         ;; The option carries the choice: a value on the select alone
         ;; is set before the options exist on a fresh page.
         (for [{:keys [path title kind]} files]
           [:option {:value path
                     :selected (= path (:path artifact))}
            title
            ;; The video of a design shares its name with the design.
            (when (= :video kind) [:i18n :artifacts/video])])])
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
