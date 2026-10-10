(ns pchat.artifacts
  "The things the agent makes, beside the chat: the files under the
  workspace's `artifacts/` folder, shown by kind. A page in an iframe that
  reloads when its file changes (rchat's canvas alias), an image, a video,
  an audio file as themselves, anything else as a download. The newest
  change the agent makes becomes the one on screen, unless the user picked
  another one since.

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
   "mp3" :audio "wav" :audio "m4a" :audio "ogg" :audio
   "clj" :motion "cljc" :motion})

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

(defn list-files
  "The artifacts of the directory, newest first."
  [dir]
  (let [dir (io/file dir)]
    (if (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (map (fn [file]
                  (let [relative (str (.relativize (.toPath dir) (.toPath file)))]
                    {:path relative
                     :kind (kind relative)
                     :title (title relative)
                     :mtime (.lastModified file)
                     :size (.length file)})))
           (remove #(hidden? (:path %)))
           (sort-by :mtime >)
           (vec))
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
  [{:keys [path kind mtime] :as artifact} {:keys [motion]}]
  (case kind
    :page [:ui/canvas {:ui/src (str path-prefix "/" path)
                       :ui/rev mtime}]
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
    :motion (if motion
              (motion artifact)
              [:div.canvas-placeholder "Motion designs do not play here."])
    [:div.canvas-placeholder "There is no preview for this file. Download it from above."]))

(defn view
  "The pane: a chooser over the artifacts, open and download links, the
  current one shown by its kind. `:motion` in `opts` is the function of a
  motion artifact to its player, see `pchat.motion/pane`."
  ([db]
   (view db {}))
  ([{files :artifacts/list current :artifacts/current} opts]
  (let [artifact (some #(when (= current (:path %)) %) files)]
    [:div.canvas-pane
     [:div.canvas-bar
      [:span.label "What we made"]
      (when (seq files)
        [:select.artifacts {:value (:path artifact)
                            :on {:change [[:data/command {:command/kind :artifacts/open
                                                          :command/data {:path :event/target.value}}]]}}
         (for [{:keys [path title]} files]
           [:option {:value path} title])])
      (when artifact
        (list
         [:a.link {:href (src artifact)
                   :target "_blank"
                   :rel "noopener"}
          "Open"]
         [:a.link {:href (src artifact)
                   :download (last (str/split (:path artifact) #"/"))}
          "Download"]))]
     (if artifact
       (content artifact opts)
       [:div.canvas-placeholder "What the agent makes for you appears here."])])))
