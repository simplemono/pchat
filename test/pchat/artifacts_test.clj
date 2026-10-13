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
