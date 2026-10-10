(ns pchat.motion-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.motion :as motion]))

(def source
  "(def duration 4)
   (def design {:title \"Hi\" :color \"#123\"})
   (defn design-view [design t]
     [:div {:style {:opacity (fm/progress t 0 1) :color (:color design)}}
      (:title design)
      (tl/at (tl/still 2 [:p \"still\"]) t)])")

(deftest load-and-frame-test
  (let [{:keys [view design duration error]} (motion/load-design source "a")]
    (is (nil? error))
    (is (= 4.0 duration))
    (is (= {:title "Hi" :color "#123"} design))
    (is (= [:div {:style {:opacity 0.5 :color "#123"}} "Hi" [:p "still"]]
           (view design 0.5)))
    (testing "the frame drops a function and reports an exception"
      (is (= [:div nil] (motion/frame {:view (fn [_ _] [:div (fn [] 1)]) :design {}} 0)))
      (let [[tag text] (motion/frame {:view (fn [_ _] (throw (ex-info "boom" {}))) :design {}} 1.5)]
        (is (= :p.error tag))
        (is (re-find #"1[.,]5 s failed: boom" text))))))

(deftest load-errors-test
  (is (re-find #"no design-view" (:error (motion/load-design "(def duration 2)" "b"))))
  (is (string? (:error (motion/load-design "(defn design-view [d t] (undefined-thing))" "c"))))
  (is (string? (:error (motion/load-design "(def x" "d")))))

(deftest design-of-reloads-test
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "pchat-motion"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file dir "clip.clj")]
    (spit file "(def duration 1) (defn design-view [d t] [:p \"one\"])")
    (.setLastModified file 1000)
    (is (= [:p "one"] (motion/frame (motion/design-of file) 0)))
    (spit file "(def duration 2) (defn design-view [d t] [:p \"two\"])")
    (.setLastModified file 2000)
    (is (= [:p "two"] (motion/frame (motion/design-of file) 0)))
    (is (= 2.0 (:duration (motion/design-of file))))
    (testing "the pane has the frame, the scrubber at the time and the clock"
      (let [pane (motion/pane {:motion/t 1.0 :motion/playing? false} dir {:path "clip.clj"})
            strings (filter string? (tree-seq coll? seq pane))
            range (first (filter #(and (vector? %) (= :input (first %))) (tree-seq coll? seq pane)))]
        (is (some #{"two"} strings))
        (is (some #{"Play"} strings))
        (is (= 500 (:value (second range))))
        (is (some #{"0:01.0 / 0:02.0"} strings))))))

(deftest playback-test
  (let [db (atom {})]
    (motion/follow! db "clip.clj")
    (is (= {:motion/path "clip.clj" :motion/t 0.0 :motion/playing? false} @db))
    (motion/seek! db 4.0 250)
    (is (= 1.0 (:motion/t @db)))
    (is (false? (:motion/playing? @db)))
    (motion/toggle! db 4.0)
    (is (true? (:motion/playing? @db)))
    (Thread/sleep 120)
    (is (< 1.05 (:motion/t @db) 4.0))
    (motion/toggle! db 4.0)
    (is (false? (:motion/playing? @db)))
    (testing "another artifact starts over"
      (motion/follow! db "other.clj")
      (is (= 0.0 (:motion/t @db))))))
