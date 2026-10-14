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

(def id
  "The chat of the tests."
  "0199dd7e-1f1a-7a2b-8b3c-4d5e6f708192")

(deftest list-and-follow-test
  (let [chats (temp-dir)
        dir (io/file chats id "artifacts")
        db (atom {:chats {id {:id id}}})
        handle (artifacts/handle {:db db :chats-dir chats})
        chat (fn [] (get-in @db [:chats id]))]
    (testing "an empty or missing folder is an empty list"
      (is (= [] (artifacts/list-files dir)))
      (is (= [] (artifacts/list-files (io/file dir "missing")))))
    (touch! dir "first.html" 1000)
    (touch! dir ".hidden/secret.html" 3000)
    (touch! dir "pictures/pool.png" 2000)
    (testing "newest first, hidden left out, the newest on screen, all in the chat"
      (artifacts/refresh! handle id)
      (is (= ["pictures/pool.png" "first.html"] (map :path (:artifacts/list (chat)))))
      (is (= 2000 (:artifacts/rev (chat))))
      (is (= "pictures/pool.png" (:artifacts/current (chat))))
      (is (nil? (:artifacts/list @db)) "nothing of a chat outside it"))
    (testing "the user's pick holds until the agent writes something newer"
      (artifacts/pick! handle id "first.html")
      (artifacts/refresh! handle id)
      (is (= "first.html" (:artifacts/current (chat))))
      (touch! dir "story.mp4" (+ (System/currentTimeMillis) 5000))
      (artifacts/refresh! handle id)
      (is (= "story.mp4" (:artifacts/current (chat)))))
    (testing "a directory with a bb.edn is one artifact, a project, timed by its newest file"
      (touch! dir "clip/bb.edn" 100)
      (touch! dir "clip/src/artifact/main.clj" 7000)
      (touch! dir "clip/.log" 9000)
      (artifacts/refresh! handle id)
      (let [files (:artifacts/list (chat))
            clip (some #(when (= "clip" (:path %)) %) files)]
        (is (= {:path "clip" :kind :project :title "Clip" :mtime 7000} (dissoc clip :size)))
        (is (not-any? #(clojure.string/starts-with? (:path %) "clip/") files))
        (is (= "story.mp4" (:path (first files))) "the file from the future stays newest")))
    (testing "a pick that disappeared falls back to the newest"
      (artifacts/pick! handle id "first.html")
      (.delete (io/file dir "first.html"))
      (artifacts/refresh! handle id)
      (is (= "story.mp4" (:artifacts/current (chat)))))
    (testing "another chat's folder is another chat's list"
      (let [other "0199dd7e-1f1a-7a2b-8b3c-4d5e6f708193"]
        (swap! db assoc-in [:chats other] {:id other})
        (touch! (io/file chats other "artifacts") "mine.html" 5000)
        (artifacts/refresh! handle other)
        (is (= ["mine.html"] (map :path (get-in @db [:chats other :artifacts/list]))))
        (is (= "story.mp4" (:artifacts/current (chat))))))
    (testing "a chat that is gone gets nothing"
      (artifacts/refresh! handle "0199dd7e-1f1a-7a2b-8b3c-4d5e6f708194")
      (is (= #{id "0199dd7e-1f1a-7a2b-8b3c-4d5e6f708193"} (set (keys (:chats @db))))))))

(defn- tags
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter #(and (vector? %) (keyword? (first %))))
       (map first)))

(defn- nodes
  "Every hiccup node of the tree, depth first."
  [tree]
  (tree-seq (fn [node] (or (vector? node) (seq? node)))
            (fn [node] (if (vector? node) (rest node) node))
            tree))

(defn- find-tag
  [tree tag]
  (some #(when (and (vector? %) (= tag (first %))) %) (nodes tree)))

(deftest view-test
  (let [list [{:path "card.html" :kind :page :title "Card" :mtime 3}
              {:path "pool.png" :kind :image :title "Pool" :mtime 2}
              {:path "story.mp4" :kind :video :title "Story" :mtime 1}]]
    (testing "a page is the live iframe, under the chat's own address"
      (let [view (artifacts/view {:id id :artifacts/list list :artifacts/current "card.html"})]
        (is (some #{:ui/canvas} (tags view)))
        (is (some #{:select.artifacts} (tags view)))
        (is (= 3 (count (filter #{:option} (tags view)))))
        (is (= (str "/chat/" id "/artifacts/card.html")
               (:ui/src (second (find-tag view :ui/canvas)))))))
    (testing "an image and a video are shown as themselves"
      (is (some #{:img} (tags (artifacts/view {:id id :artifacts/list list :artifacts/current "pool.png"}))))
      (is (some #{:video} (tags (artifacts/view {:id id :artifacts/list list :artifacts/current "story.mp4"})))))
    (testing "a project is the iframe once its process answers, a notice before"
      (let [project {:path "clip" :kind :project :title "Clip" :mtime 9}
            view (fn [state] (artifacts/view {:id id :artifacts/list [project] :artifacts/current "clip"}
                                             {:project (constantly state)}))]
        (is (some #{:div.canvas-placeholder} (tags (view {:ready-at nil}))))
        (is (some #{:ui/canvas} (tags (view {:ready-at 5}))))
        (is (some #{:p.error} (tags (view {:ready-at 5 :reload {:file "x.clj" :error "boom"}}))))
        (is (some #{:div.canvas-placeholder} (tags (view {:exit 1}))))))
    (testing "nothing yet"
      (is (some #{:div.canvas-placeholder} (tags (artifacts/view {:id id})))))))

(deftest render-in-progress-test
  (let [chats (temp-dir)
        dir (io/file chats id "artifacts")
        db (atom {:chats {id {:id id}}})
        handle (artifacts/handle {:db db :chats-dir chats})
        chat (fn [] (get-in @db [:chats id]))
        now (System/currentTimeMillis)
        prefix (str "/chat/" id "/artifacts/")
        video-of (fn [] (find-tag (artifacts/view (chat)) :video))]
    (touch! dir "card/bb.edn" 100)
    (touch! dir "card/index.html" (- now 60000))
    (touch! dir "notes.html" (- now 50000))
    (artifacts/refresh! handle id)
    (artifacts/pick! handle id "notes.html")
    (testing "the video a render writes does not take the screen, tick after tick"
      (swap! db assoc-in [:chats id :renders "card"] {:started now :progress "frame 3/90"})
      (touch! dir "card.mp4" (+ now 1000))
      (artifacts/refresh! handle id)
      (is (= "notes.html" (:artifacts/current (chat))))
      (touch! dir "card.mp4" (+ now 2000))
      (artifacts/refresh! handle id)
      (is (= "notes.html" (:artifacts/current (chat))))
      (is (= "notes.html" (:value (second (find-tag (artifacts/view (chat)) :select.artifacts))))))
    (testing "shown while it is written: one element, one src, the progress beside it"
      (artifacts/pick! handle id "card.mp4")
      (let [[_ {:keys [replicant/key src]}] (video-of)]
        (is (= "card.mp4" key))
        (is (= (str prefix "card.mp4?rev=" now) src)))
      (swap! db assoc-in [:chats id :renders "card" :progress] "frame 4/90")
      (touch! dir "card.mp4" (+ now 3000))
      (artifacts/refresh! handle id)
      (is (= "card.mp4" (:artifacts/current (chat))))
      (is (= [:video {:replicant/key "card.mp4" :src (str prefix "card.mp4?rev=" now) :controls true}] (video-of)))
      (is (= [:i18n :artifacts/rendering {:progress "4/90"}] (second (find-tag (artifacts/view (chat)) :p.rendering)))))
    (testing "the finished video is news: it takes the screen from the pick made during the render"
      (artifacts/pick! handle id "notes.html")
      (touch! dir "card.mp4" (+ now 100000))
      (artifacts/refresh! handle id)
      (is (= "notes.html" (:artifacts/current (chat))) "still writing")
      (swap! db update-in [:chats id :renders "card"] #(-> % (dissoc :progress) (assoc :exit 0)))
      (artifacts/refollow! handle id)
      (is (= "card.mp4" (:artifacts/current (chat))))
      (is (= (str prefix "card.mp4?rev=" (+ now 100000)) (:src (second (video-of)))) "the final file, loaded once")
      (is (nil? (find-tag (artifacts/view (chat)) :p.rendering))))
    (testing "a pick made after the render is done holds"
      (touch! dir "card.mp4" (- (System/currentTimeMillis) 1))
      (artifacts/refresh! handle id)
      (artifacts/pick! handle id "notes.html")
      (artifacts/refollow! handle id)
      (is (= "notes.html" (:artifacts/current (chat)))))))
