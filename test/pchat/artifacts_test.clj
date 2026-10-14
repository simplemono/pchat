(ns pchat.artifacts-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.artifacts :as artifacts]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-artifacts"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- touch!
  [dir path mtime]
  (let [file (io/file dir path)]
    (io/make-parents file)
    (spit file (str "content of " path))
    (.setLastModified file mtime)
    file))

(deftest kind-and-title-test
  (is (= :page (artifacts/kind "guest-review_card.html")))
  (is (= :image (artifacts/kind "photos/Pool.JPG")))
  (is (= :video (artifacts/kind "story.mp4")))
  (is (= :file (artifacts/kind "review-story.clj")))
  (is (= :file (artifacts/kind "notes")))
  (is (= "Guest review card" (artifacts/title "guest-review_card.html")))
  (is (= "Pool" (artifacts/title "photos/Pool.JPG"))))

(deftest list-and-follow-test
  (let [dir (temp-dir)
        db (atom {})
        handle (artifacts/handle {:db db :dir dir})]
    (testing "an empty or missing folder is an empty list"
      (is (= [] (artifacts/list-files dir)))
      (is (= [] (artifacts/list-files (io/file dir "missing")))))
    (touch! dir "first.html" 1000)
    (touch! dir ".hidden/secret.html" 3000)
    (touch! dir "pictures/pool.png" 2000)
    (testing "newest first, hidden left out, the newest on screen"
      (artifacts/refresh! handle)
      (is (= ["pictures/pool.png" "first.html"] (map :path (:artifacts/list @db))))
      (is (= 2000 (:artifacts/rev @db)))
      (is (= "pictures/pool.png" (:artifacts/current @db))))
    (testing "the user's pick holds until the agent writes something newer"
      (artifacts/pick! handle "first.html")
      (artifacts/refresh! handle)
      (is (= "first.html" (:artifacts/current @db)))
      (touch! dir "story.mp4" (+ (System/currentTimeMillis) 5000))
      (artifacts/refresh! handle)
      (is (= "story.mp4" (:artifacts/current @db))))
    (testing "a directory with a bb.edn is one artifact, a project, timed by its newest file"
      (touch! dir "clip/bb.edn" 100)
      (touch! dir "clip/src/artifact/main.clj" 7000)
      (touch! dir "clip/.log" 9000)
      (artifacts/refresh! handle)
      (let [files (:artifacts/list @db)
            clip (some #(when (= "clip" (:path %)) %) files)]
        (is (= {:path "clip" :kind :project :title "Clip" :mtime 7000} (dissoc clip :size)))
        (is (not-any? #(clojure.string/starts-with? (:path %) "clip/") files))
        (is (= "story.mp4" (:path (first files))) "the file from the future stays newest")))
    (testing "a pick that disappeared falls back to the newest"
      (artifacts/pick! handle "first.html")
      (.delete (io/file dir "first.html"))
      (artifacts/refresh! handle)
      (is (= "story.mp4" (:artifacts/current @db))))))

(defn- tags
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter #(and (vector? %) (keyword? (first %))))
       (map first)))

(deftest view-test
  (let [list [{:path "card.html" :kind :page :title "Card" :mtime 3}
              {:path "pool.png" :kind :image :title "Pool" :mtime 2}
              {:path "story.mp4" :kind :video :title "Story" :mtime 1}]]
    (testing "a page is the live iframe"
      (let [view (artifacts/view {:artifacts/list list :artifacts/current "card.html"})]
        (is (some #{:ui/canvas} (tags view)))
        (is (some #{:select.artifacts} (tags view)))
        (is (= 3 (count (filter #{:option} (tags view)))))))
    (testing "an image and a video are shown as themselves"
      (is (some #{:img} (tags (artifacts/view {:artifacts/list list :artifacts/current "pool.png"}))))
      (is (some #{:video} (tags (artifacts/view {:artifacts/list list :artifacts/current "story.mp4"})))))
    (testing "a project is the iframe once its process answers, a notice before"
      (let [project {:path "clip" :kind :project :title "Clip" :mtime 9}
            view (fn [state] (artifacts/view {:artifacts/list [project] :artifacts/current "clip"}
                                             {:project (constantly state)}))]
        (is (some #{:div.canvas-placeholder} (tags (view {:ready-at nil}))))
        (is (some #{:ui/canvas} (tags (view {:ready-at 5}))))
        (is (some #{:p.error} (tags (view {:ready-at 5 :reload {:file "x.clj" :error "boom"}}))))
        (is (some #{:div.canvas-placeholder} (tags (view {:exit 1}))))))
    (testing "nothing yet"
      (is (some #{:div.canvas-placeholder} (tags (artifacts/view {})))))))

(defn- nodes
  "Every hiccup node of the tree, depth first."
  [tree]
  (tree-seq (fn [node] (or (vector? node) (seq? node)))
            (fn [node] (if (vector? node) (rest node) node))
            tree))

(defn- find-tag
  [tree tag]
  (some #(when (and (vector? %) (= tag (first %))) %) (nodes tree)))

(deftest render-in-progress-test
  (let [dir (temp-dir)
        db (atom {})
        handle (artifacts/handle {:db db :dir dir})
        now (System/currentTimeMillis)
        video-of (fn [] (find-tag (artifacts/view @db) :video))]
    (touch! dir "card/bb.edn" 100)
    (touch! dir "card/index.html" (- now 60000))
    (touch! dir "notes.html" (- now 50000))
    (artifacts/refresh! handle)
    (artifacts/pick! handle "notes.html")
    (testing "the video a render writes does not take the screen, tick after tick"
      (swap! db assoc-in [:renders "card"] {:started now :progress "frame 3/90"})
      (touch! dir "card.mp4" (+ now 1000))
      (artifacts/refresh! handle)
      (is (= "notes.html" (:artifacts/current @db)))
      (touch! dir "card.mp4" (+ now 2000))
      (artifacts/refresh! handle)
      (is (= "notes.html" (:artifacts/current @db)))
      (is (= "notes.html" (:value (second (find-tag (artifacts/view @db) :select.artifacts))))))
    (testing "shown while it is written: one element, one src, the progress beside it"
      (artifacts/pick! handle "card.mp4")
      (let [[_ {:keys [replicant/key src]}] (video-of)]
        (is (= "card.mp4" key))
        (is (= (str "/artifacts/card.mp4?rev=" now) src)))
      (swap! db assoc-in [:renders "card" :progress] "frame 4/90")
      (touch! dir "card.mp4" (+ now 3000))
      (artifacts/refresh! handle)
      (is (= "card.mp4" (:artifacts/current @db)))
      (is (= [:video {:replicant/key "card.mp4" :src (str "/artifacts/card.mp4?rev=" now) :controls true}] (video-of)))
      (is (= [:i18n :artifacts/rendering {:progress "4/90"}] (second (find-tag (artifacts/view @db) :p.rendering)))))
    (testing "the finished video is news: it takes the screen from the pick made during the render"
      (artifacts/pick! handle "notes.html")
      (touch! dir "card.mp4" (+ now 100000))
      (artifacts/refresh! handle)
      (is (= "notes.html" (:artifacts/current @db)) "still writing")
      (swap! db update-in [:renders "card"] #(-> % (dissoc :progress) (assoc :exit 0)))
      (artifacts/refollow! handle)
      (is (= "card.mp4" (:artifacts/current @db)))
      (is (= (str "/artifacts/card.mp4?rev=" (+ now 100000)) (:src (second (video-of)))) "the final file, loaded once")
      (is (nil? (find-tag (artifacts/view @db) :p.rendering))))
    (testing "a pick made after the render is done holds"
      (touch! dir "card.mp4" (- (System/currentTimeMillis) 1))
      (artifacts/refresh! handle)
      (artifacts/pick! handle "notes.html")
      (artifacts/refollow! handle)
      (is (= "notes.html" (:artifacts/current @db))))))

(deftest hints-test
  (testing "the artifact a tool call is about"
    (is (= #{"story"} (artifacts/hints-of {:name "bash" :args {:command "bin/new story"}})))
    (is (= #{"story"} (artifacts/hints-of {:name "bash" :args {:command "cd /w && bin/render story 2>&1 | tail -3"}})))
    (is (= #{"card"} (artifacts/hints-of {:name "write" :args {:path "artifacts/card/src/artifact/main.clj"}})))
    (is (= #{"card"} (artifacts/hints-of {:name "edit" :args {:path "/home/sprite/pchat/workspace/artifacts/card/design.edn"}})))
    (is (= #{} (artifacts/hints-of {:name "read" :args {:path "artifacts/card/design.edn"}})))
    (is (= #{} (artifacts/hints-of {:name "bash" :args {:command "ls"}}))))
  (testing "what an item of the tree belongs to"
    (is (= "card" (artifacts/top-name "card/src/main.clj")))
    (is (= "story" (artifacts/top-name "story.mp4")) "the video of a project")
    (is (= "pool.png" (artifacts/top-name "pool.png")))))

(deftest owners-test
  (let [dir (temp-dir)
        owners-file (io/file (temp-dir) ".pchat" "artifact-owners.edn")
        db (atom {:chats {"a" {:id "a" :status :working :name "Alpha"}
                          "b" {:id "b" :status :idle :name "Beta"}}
                  :current "a"})
        handle (artifacts/handle {:db db :dir dir :owners-file owners-file})]
    (testing "a new file is the one working chat's"
      (touch! dir "pool.png" 1000)
      (artifacts/refresh! handle)
      (is (= "a" (artifacts/owner @db "pool.png")))
      (is (= "a" (get-in (read-string (slurp owners-file)) ["pool.png" :chat])) "kept in the file"))
    (testing "a tool call names the artifact: that chat's, whoever works"
      (artifacts/hint! handle "b" {:name "bash" :args {:command "bin/new clip"}})
      (touch! dir "clip/bb.edn" 2000)
      (touch! dir "clip/src/artifact/main.clj" 2100)
      (artifacts/refresh! handle)
      (is (= "b" (artifacts/owner @db "clip"))))
    (testing "the video of a project is its chat's"
      (touch! dir "clip.mp4" 3000)
      (artifacts/refresh! handle)
      (is (= "b" (artifacts/owner @db "clip.mp4"))))
    (testing "nothing works and no tool named it: unstamped"
      (swap! db assoc-in [:chats "a" :status] :idle)
      (touch! dir "dropped.pdf" 4000)
      (artifacts/refresh! handle)
      (is (nil? (artifacts/owner @db "dropped.pdf"))))
    (testing "the current chat's own comes first on screen, another chat's new file does not take it"
      (is (= "a" (:current @db)))
      (is (= "pool.png" (:artifacts/current @db)) "the newest own, although clip.mp4 and dropped.pdf are newer"))
    (testing "a chat without anything of its own sees the newest overall"
      (swap! db assoc :current "c")
      (artifacts/refollow! handle)
      (is (= "dropped.pdf" (:artifacts/current @db))))
    (testing "a deleted chat's stamps go, its files stay"
      (artifacts/forget-chat! handle "b")
      (is (nil? (artifacts/owner @db "clip")))
      (is (= "a" (artifacts/owner @db "pool.png")))
      (is (.isFile (io/file dir "clip.mp4"))))
    (testing "the stamps come back from the file at the start"
      (let [db2 (atom {})]
        (artifacts/load-owners! (artifacts/handle {:db db2 :dir dir :owners-file owners-file}))
        (is (= "a" (artifacts/owner @db2 "pool.png")))))))

(deftest options-test
  (let [files [{:path "card.html" :kind :page :title "Card" :mtime 3}
               {:path "clip" :kind :project :title "Clip" :mtime 2}
               {:path "pool.png" :kind :image :title "Pool" :mtime 1}]
        db {:chats {"a" {:id "a" :name "Alpha"} "b" {:id "b" :name "Beta"}}
            :current "a"
            :artifacts/owners {"card.html" {:chat "a" :at 1} "clip" {:chat "b" :at 1}}
            :artifacts/list files
            :artifacts/current "card.html"}
        strings (fn [hiccup] (filter string? (tree-seq coll? seq hiccup)))
        view (artifacts/view db {:chat-name :name})
        groups (filter #(and (vector? %) (= :optgroup (first %))) (tree-seq coll? seq view))]
    (testing "own first, the other chat's under its name, the unstamped under Other"
      (is (= 2 (count groups)))
      (is (= [[:i18n :artifacts/others] [:i18n :artifacts/unowned]] (map #(get-in % [1 :label]) groups)))
      (is (some #{"Beta"} (strings (first groups))))
      (is (= 3 (count (filter #{:option} (tags view))))))
    (testing "nothing stamped: one flat list"
      (is (empty? (filter #(and (vector? %) (= :optgroup (first %)))
                          (tree-seq coll? seq (artifacts/view (dissoc db :artifacts/owners) {}))))))))
