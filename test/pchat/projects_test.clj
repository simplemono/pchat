(ns pchat.projects-test
  (:require [babashka.nrepl.server :as server]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.nrepl :as nrepl]
            [pchat.projects :as projects]))

(deftest nrepl-eval-test
  (let [{:keys [socket]} (server/start-server! {:host "127.0.0.1" :port 0 :quiet true})
        port (.getLocalPort socket)]
    (try
      (is (= "3" (:value (nrepl/eval! port "(+ 1 2)"))))
      (is (= "hi\n" (:out (nrepl/eval! port "(println \"hi\")"))))
      (is (string? (:ex (nrepl/eval! port "(/ 1 0)"))))
      (finally
        (.close socket)))))

(deftest names-test
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "pchat-projects"
                  (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (io/file dir "b"))
    (spit (io/file dir "b" "bb.edn") "{}")
    (.mkdirs (io/file dir "a"))
    (spit (io/file dir "a" "bb.edn") "{}")
    (.mkdirs (io/file dir "plain"))
    (.mkdirs (io/file dir ".hidden"))
    (spit (io/file dir ".hidden" "bb.edn") "{}")
    (is (= ["a" "b"] (projects/names dir)))))

(deftest handler-passes-unknown-test
  (let [handle (projects/handle {:db (atom {}) :chats-dir "/tmp/none"})
        w {:ring/route-params {:* "hello.html"} :ring/request {}}]
    (testing "a path that is no running project is left to the caller"
      (is (= w (projects/handler handle "c" w))))
    (testing "no chats, no projects, nothing to sync"
      (is (nil? (projects/sync! handle))))))
