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
  (is (= :motion (artifacts/kind "review-story.clj")))
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
    (testing "nothing yet"
      (is (some #{:div.canvas-placeholder} (tags (artifacts/view {})))))))
