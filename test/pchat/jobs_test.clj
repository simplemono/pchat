(ns pchat.jobs-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.jobs :as jobs]))

(def id "0199aaaa-bbbb-7ccc-8ddd-eeeeeeeeeeee")

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-jobs"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- dead-pid
  "The pid of a process that has ended."
  []
  (let [p (.start (ProcessBuilder. ["true"]))]
    (.waitFor p)
    (.pid p)))

(defn- job!
  [chats-dir n files]
  (let [dir (io/file chats-dir id ".jobs" (str n))]
    (.mkdirs dir)
    (doseq [[name text] files]
      (spit (io/file dir name) (str text)))))

(deftest jobs-test
  (let [chats-dir (temp-dir)
        now (System/currentTimeMillis)
        told (atom [])]
    (job! chats-dir 1 {"command" "node render.mjs 01" "pid" (dead-pid) "started" (- now 600000)
                       "log" "frame 1\nframe 2\nwork/out/01.mp4: 14 MB." "exit" "0"})
    (job! chats-dir 2 {"command" "sleep 600" "pid" (.pid (java.lang.ProcessHandle/current)) "started" now})
    (job! chats-dir 3 {"command" "node render.mjs 03" "pid" (dead-pid) "started" (- now 60000)})
    (testing "a job whose process lives runs: the machine stays awake"
      (is (jobs/running? chats-dir [id]))
      (is (= [false true false] (map (complement :ended?) (jobs/jobs chats-dir id)))))
    (testing "each ended job is told once, with its exit, its time and its output"
      (jobs/report-ended! chats-dir [id] (fn [id text] (swap! told conj [id text])) now)
      (is (= 2 (count @told)))
      (let [[[_ one] [_ three]] @told]
        (is (str/includes? one "Background job 1 (bin/background) has ended with exit 0 after 10 min: node render.mjs 01"))
        (is (str/includes? one "work/out/01.mp4: 14 MB."))
        (is (str/includes? three "without an exit: it was stopped, or the machine restarted after 60 s"))
        (is (str/includes? three "It printed nothing.")))
      (jobs/report-ended! chats-dir [id] (fn [id text] (swap! told conj [id text])) now)
      (is (= 2 (count @told)) "not twice"))
    (testing "a chat without jobs has none"
      (is (empty? (jobs/jobs chats-dir "0199aaaa-bbbb-7ccc-8ddd-ffffffffffff")))
      (is (not (jobs/running? chats-dir ["0199aaaa-bbbb-7ccc-8ddd-ffffffffffff"]))))))
