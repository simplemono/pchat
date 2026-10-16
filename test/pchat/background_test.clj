(ns pchat.background-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.background :as background]))

(def id "0199aaaa-bbbb-7ccc-8ddd-eeeeeeeeeeee")

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-background"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- dead-pid
  "The pid of a process that has ended."
  []
  (let [p (.start (ProcessBuilder. ["true"]))]
    (.waitFor p)
    (.pid p)))

(defn- task!
  [chats-dir n files]
  (let [dir (io/file chats-dir id ".background" (str n))]
    (.mkdirs dir)
    (doseq [[name text] files]
      (spit (io/file dir name) (str text)))))

(deftest background-test
  (let [chats-dir (temp-dir)
        now (System/currentTimeMillis)
        told (atom [])]
    (task! chats-dir 1 {"command" "node render.mjs 01" "pid" (dead-pid) "started" (- now 600000)
                       "log" "frame 1\nframe 2\nwork/out/01.mp4: 14 MB." "exit" "0"})
    (task! chats-dir 2 {"command" "sleep 600" "pid" (.pid (java.lang.ProcessHandle/current)) "started" now})
    (task! chats-dir 3 {"command" "node render.mjs 03" "pid" (dead-pid) "started" (- now 30000)})
    (testing "a task whose process lives runs: the machine stays awake"
      (is (background/running? chats-dir [id]))
      (is (= [false true false] (map (complement :ended?) (background/tasks chats-dir id)))))
    (testing "each ended task is told once, with its exit, its time and its output"
      (background/report-ended! chats-dir [id] (fn [id text] (swap! told conj [id text])) now)
      (is (= 2 (count @told)))
      (let [[[_ one] [_ three]] @told]
        (is (str/includes? one "Background task 1 (bin/background) has ended with exit 0 after 10 min: node render.mjs 01"))
        (is (str/includes? one "work/out/01.mp4: 14 MB."))
        (is (str/includes? three "without an exit: it was stopped, or the machine restarted after 30 s"))
        (is (str/includes? three "It printed nothing.")))
      (background/report-ended! chats-dir [id] (fn [id text] (swap! told conj [id text])) now)
      (is (= 2 (count @told)) "not twice"))
    (testing "a chat without tasks has none"
      (is (empty? (background/tasks chats-dir "0199aaaa-bbbb-7ccc-8ddd-ffffffffffff")))
      (is (not (background/running? chats-dir ["0199aaaa-bbbb-7ccc-8ddd-ffffffffffff"]))))))
